package dev.local.longbox.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import dev.local.longbox.LongboxApp
import dev.local.longbox.reader.Covers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

private val thumbnails = LruCache<String, Bitmap>(120)
private const val MAX_THUMBNAIL_BYTES = 2L * 1024 * 1024

/** Shows [bitmap], or a tinted box with the title's first letter while there is none. */
@Composable
private fun Thumbnail(bitmap: Bitmap?, label: String, modifier: Modifier) {
  if (bitmap != null) {
    Image(bitmap.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
  } else {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
      Text(
        label.trim().take(1).uppercase(),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** First page of a library file. */
@Composable
fun CoverImage(file: File, label: String, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  // Previews have no cache folder or real files; show the placeholder there.
  val inPreview = LocalInspectionMode.current
  val key = "file:${file.path}:${file.lastModified()}"
  val bitmap by
    produceState(thumbnails.get(key), key) {
      if (value == null && !inPreview) {
        value = withContext(Dispatchers.IO) { Covers.load(file, context.cacheDir) }?.also { thumbnails.put(key, it) }
      }
    }
  Thumbnail(bitmap, label, modifier)
}

/** A small catalogue thumbnail fetched over the network. */
@Composable
fun RemoteThumbnail(url: String?, label: String, modifier: Modifier = Modifier) {
  val app = LocalContext.current.applicationContext as? LongboxApp
  val bitmap by
    produceState(url?.let(thumbnails::get), url) {
      if (value == null && url != null && app != null) {
        value =
          withContext(Dispatchers.IO) {
            runCatching {
                app.httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                  if (!response.isSuccessful) return@use null
                  // Thumbnails are small; cap the read so a wrong link cannot exhaust memory.
                  val source = response.body.source()
                  source.request(MAX_THUMBNAIL_BYTES)
                  val bytes = source.buffer.readByteArray(minOf(source.buffer.size, MAX_THUMBNAIL_BYTES))
                  BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
              }
              .getOrNull()
          }
            ?.also { thumbnails.put(url, it) }
      }
    }
  Thumbnail(bitmap, label, modifier)
}

fun formatSize(bytes: Long): String =
  when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
  }
