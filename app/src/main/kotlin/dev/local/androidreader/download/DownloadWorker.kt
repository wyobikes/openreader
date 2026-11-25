package dev.local.androidreader.download

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.local.androidreader.ReaderApp
import dev.local.androidreader.epub.PdfToEpub
import dev.local.androidreader.library.Importer
import dev.local.androidreader.library.SecretSealer
import dev.local.androidreader.source.OpdsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

data class DownloadItem(
  val id: UUID,
  val title: String,
  val status: Status,
  val bytesDone: Long = 0,
  val bytesTotal: Long = 0,
  val error: String? = null,
  /** Extra detail for a finished download, such as the outcome of a conversion. */
  val note: String? = null,
) {
  enum class Status {
    QUEUED,
    RUNNING,
    DONE,
    FAILED,
    CANCELLED,
  }

  val fraction: Float?
    get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else null
}

/** The download queue. WorkManager persists it, so it survives the app being closed. */
class DownloadQueue(context: Context, private val sealer: SecretSealer) {
  private val workManager = WorkManager.getInstance(context)

  val items: Flow<List<DownloadItem>> =
    workManager.getWorkInfosByTagFlow(TAG).map { infos -> infos.map(::toItem).sortedBy { it.status.ordinal } }

  /** [sourceId] lets the worker attach that catalogue's credentials to the request. */
  fun enqueue(
    title: String,
    url: String,
    fileName: String? = null,
    sourceId: String? = null,
    headers: Map<String, String> = emptyMap(),
  ) {
    // Android writes a queued job's data to disk, so the browser's headers (which can include
    // cookies) are encrypted first.
    val packedHeaders = DownloadHeaders.pack(headers, sealer)
    val request =
      OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(
          workDataOf(KEY_URL to url, KEY_NAME to fileName, KEY_TITLE to title, KEY_SOURCE to sourceId, KEY_HEADERS to packedHeaders)
        )
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .addTag(TAG)
        .addTag(TITLE_PREFIX + title.take(100))
        .build()
    // Keyed by URL so tapping the same file twice does not download it twice.
    workManager.enqueueUniqueWork("download:$url", ExistingWorkPolicy.KEEP, request)
  }

  fun cancel(id: UUID) {
    workManager.cancelWorkById(id)
  }

  fun clearFinished() {
    workManager.pruneWork()
  }

  private fun toItem(info: WorkInfo): DownloadItem {
    val title = info.tags.firstOrNull { it.startsWith(TITLE_PREFIX) }?.removePrefix(TITLE_PREFIX) ?: "Download"
    val status =
      when (info.state) {
        WorkInfo.State.RUNNING -> DownloadItem.Status.RUNNING
        WorkInfo.State.SUCCEEDED -> DownloadItem.Status.DONE
        WorkInfo.State.FAILED -> DownloadItem.Status.FAILED
        WorkInfo.State.CANCELLED -> DownloadItem.Status.CANCELLED
        else -> DownloadItem.Status.QUEUED
      }
    return DownloadItem(
      id = info.id,
      title = title,
      status = status,
      bytesDone = info.progress.getLong(KEY_DONE, 0),
      bytesTotal = info.progress.getLong(KEY_TOTAL, 0),
      error = info.outputData.getString(KEY_ERROR),
      note = info.outputData.getString(KEY_NOTE),
    )
  }

  companion object {
    const val TAG = "download"
    const val TITLE_PREFIX = "title:"
    const val KEY_URL = "url"
    const val KEY_NAME = "name"
    const val KEY_TITLE = "title"
    const val KEY_SOURCE = "source"
    const val KEY_HEADERS = "headers"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val KEY_ERROR = "error"
    const val KEY_NOTE = "note"
  }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val app = applicationContext as ReaderApp
    val url = inputData.getString(DownloadQueue.KEY_URL) ?: return fail("No URL given")
    val title = inputData.getString(DownloadQueue.KEY_TITLE) ?: "Download"
    val sourceId = inputData.getString(DownloadQueue.KEY_SOURCE)

    // Long downloads need a foreground notification; if the system refuses, carry on in the
    // background and rely on resume + retry.
    runCatching { setForeground(foregroundInfo(title, 0, 0)) }

    // The catalogue's sign-in, if the file is on the catalogue's own server (a catalogue can link
    // to files hosted anywhere), plus anything the browser passed along.
    val headers =
      app.sources().firstOrNull { it.id == sourceId }?.headersFor(url).orEmpty() +
        DownloadHeaders.unpack(inputData.getString(DownloadQueue.KEY_HEADERS), SecretSealer(app.secrets))

    // A stable temp name per URL lets a retried job resume the same partial file.
    val temp = File(File(applicationContext.cacheDir, "downloads").apply { mkdirs() }, sha1(url))
    var downloaded = false
    var note: String? = null
    return try {
      withContext(Dispatchers.IO) {
        var lastNotified = 0L
        val result =
          app.downloader.download(url, temp, headers, isCancelled = { isStopped }) { done, total ->
            setProgressAsync(workDataOf(DownloadQueue.KEY_DONE to done, DownloadQueue.KEY_TOTAL to (total ?: 0L)))
            val now = System.currentTimeMillis()
            if (now - lastNotified > 1000) {
              lastNotified = now
              runCatching { setForegroundAsync(foregroundInfo(title, done, total ?: 0)) }
            }
          }
        downloaded = true
        val name =
          inputData.getString(DownloadQueue.KEY_NAME)
            ?: result.suggestedName
            ?: Downloader.fileNameFromUrl(url)
            ?: OpdsSource.safeName(title)
        val added = Importer.import(result.file, app.library.dir, name)
        // Optional: give each downloaded PDF an EPUB copy made from its text. The PDF is kept,
        // and a PDF that cannot be converted (a scan, say) is not treated as a failed download.
        if (app.library.convertPdfToEpub.value) {
          for (pdf in added.filter { it.extension.equals("pdf", ignoreCase = true) }) {
            note =
              try {
                PdfToEpub.convert(applicationContext, pdf, app.library.dir)
                "Added to library with an EPUB copy"
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                "Added as PDF only: ${e.message ?: "could not convert"}"
              }
          }
        }
        app.library.refresh()
      }
      Result.success(workDataOf(DownloadQueue.KEY_NOTE to note))
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      // Network trouble is worth retrying (the partial file is kept); bad content is not.
      val permanent = downloaded || Regex("HTTP 4\\d\\d").containsMatchIn(e.message.orEmpty())
      if (permanent || runAttemptCount >= 5) fail(e.message ?: "Download failed") else Result.retry()
    } catch (e: Exception) {
      fail(e.message ?: e.javaClass.simpleName)
    }
  }

  private fun fail(message: String): Result = Result.failure(workDataOf(DownloadQueue.KEY_ERROR to message))

  // The version checks below are needed on Android 7, which the WebView variant supports; the
  // uBlock variant needs Android 8 and would otherwise report them as unnecessary.
  @SuppressLint("ObsoleteSdkInt")
  private fun foregroundInfo(title: String, done: Long, total: Long): ForegroundInfo {
    val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }
    val notification =
      NotificationCompat.Builder(applicationContext, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
        .build()
    val notificationId = id.hashCode()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(notificationId, notification)
    }
  }

  private fun sha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

  companion object {
    private const val CHANNEL = "downloads"
  }
}
