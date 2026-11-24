package dev.local.androidreader.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.local.androidreader.epub.EpubBook
import dev.local.androidreader.library.Importer
import dev.local.androidreader.library.NaturalOrder
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/** The pages of one comic, decoded on demand so only what is on screen is held in memory. */
interface PageSource : Closeable {
  val pageCount: Int

  /** Decodes a page so that neither side exceeds roughly [maxSide] pixels. Blocking. */
  fun decode(index: Int, maxSide: Int): Bitmap

  companion object {
    fun open(file: File): PageSource = if (file.extension.equals("pdf", ignoreCase = true)) PdfPages(file) else CbzPages(file)

    /** Image entries of an archive in reading order. */
    fun pageNames(entryNames: Collection<String>): List<String> = entryNames.filter(Importer::isImage).sortedWith(NaturalOrder)

    /** The largest power-of-two downscale that keeps both sides at or above the target. */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
      var sample = 1
      while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
      return sample
    }
  }
}

class CbzPages(file: File) : PageSource {
  private val zip = ZipFile(file)
  private val names = PageSource.pageNames(zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList())

  override val pageCount: Int
    get() = names.size

  override fun decode(index: Int, maxSide: Int): Bitmap {
    val entry = zip.getEntry(names[index])
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
    val options =
      BitmapFactory.Options().apply {
        inSampleSize = PageSource.sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        // Half the memory of ARGB_8888; fine for opaque comic pages.
        inPreferredConfig = Bitmap.Config.RGB_565
      }
    return zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
      ?: throw IOException("Page ${index + 1} is not a supported image")
  }

  override fun close() = zip.close()
}

class PdfPages(file: File) : PageSource {
  private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  private val renderer = PdfRenderer(descriptor)

  override val pageCount: Int
    get() = renderer.pageCount

  // PdfRenderer allows only one open page at a time.
  @Synchronized
  override fun decode(index: Int, maxSide: Int): Bitmap =
    renderer.openPage(index).use { page ->
      val scale = maxSide.toFloat() / maxOf(page.width, page.height)
      val bitmap =
        Bitmap.createBitmap(
          (page.width * scale).toInt().coerceAtLeast(1),
          (page.height * scale).toInt().coerceAtLeast(1),
          Bitmap.Config.ARGB_8888,
        )
      bitmap.eraseColor(Color.WHITE)
      page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
      bitmap
    }

  @Synchronized
  override fun close() {
    renderer.close()
    descriptor.close()
  }
}

/** First-page thumbnails, cached on disk and keyed by file name, size and modification time. */
object Covers {
  fun load(file: File, cacheDir: File): Bitmap? {
    val key = MessageDigest.getInstance("SHA-1").digest("${file.name}|${file.length()}|${file.lastModified()}".toByteArray())
    val cached = File(File(cacheDir, "covers").apply { mkdirs() }, key.joinToString("") { "%02x".format(it) } + ".jpg")
    if (cached.exists()) BitmapFactory.decodeFile(cached.path)?.let { return it }
    if (file.extension.equals("epub", ignoreCase = true)) {
      return runCatching {
          EpubBook.open(file).use { book ->
            val cover = book.coverPath ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            book.open(cover)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply { inSampleSize = PageSource.sampleSize(bounds.outWidth, bounds.outHeight, 480) }
            book.open(cover)?.use { BitmapFactory.decodeStream(it, null, options) }?.also { bitmap ->
              cached.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            }
          }
        }
        .getOrNull()
    }
    return runCatching {
        PageSource.open(file).use { pages ->
          if (pages.pageCount == 0) return null
          pages.decode(0, 480).also { bitmap ->
            cached.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
          }
        }
      }
      .getOrNull()
  }
}
