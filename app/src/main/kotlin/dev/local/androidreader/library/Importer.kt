package dev.local.androidreader.library

import com.github.junrar.Archive
import dev.local.androidreader.source.ComicFormat
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Turns a downloaded or picked file into readable library files: PDFs and CBZs are moved in,
 * CBRs are converted to CBZ, and archives that bundle several comics are unpacked.
 */
object Importer {
  private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic")

  fun isImage(name: String): Boolean {
    val base = name.substringAfterLast('/')
    return !base.startsWith(".") && "__MACOSX/" !in name && base.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
  }

  private fun isComicFile(name: String): Boolean =
    !name.substringAfterLast('/').startsWith(".") &&
      "__MACOSX/" !in name &&
      ComicFormat.fromName(name).let { it != null && it != ComicFormat.ZIP }

  /** Identifies a file by its first bytes, so a wrong or missing extension does not matter. */
  fun sniff(file: File): ComicFormat? {
    val head = ByteArray(8)
    val read = file.inputStream().use { it.read(head) }
    if (read < 4) return null
    return when {
      head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> ComicFormat.ZIP
      String(head, 0, 4, Charsets.ISO_8859_1) == "Rar!" -> ComicFormat.CBR
      String(head, 0, 4, Charsets.ISO_8859_1) == "%PDF" -> ComicFormat.PDF
      else -> null
    }
  }

  /**
   * Ceilings on what unpacking an archive may write. A small archive can be built to expand into
   * an enormous amount of data; without a ceiling, importing one would fill the phone's storage.
   * The defaults are far above any real comic or book.
   */
  data class Limits(
    val maxEntryBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxTotalBytes: Long = 8L * 1024 * 1024 * 1024,
    val maxEntries: Int = 50_000,
  )

  /** Counts down what one import may still write, across everything it unpacks. */
  private class Budget(val limits: Limits) {
    private var remaining = limits.maxTotalBytes

    fun checkEntries(count: Int) {
      if (count > limits.maxEntries) throw IOException("This archive has too many files to unpack safely ($count)")
    }

    /** An output stream that refuses to grow past the per-file and overall ceilings. */
    fun limit(out: OutputStream): OutputStream =
      object : OutputStream() {
        private var entry = 0L

        private fun spend(bytes: Int) {
          entry += bytes
          remaining -= bytes
          if (entry > limits.maxEntryBytes || remaining < 0) throw IOException("This archive is too large to unpack safely")
        }

        override fun write(b: Int) {
          spend(1)
          out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
          spend(len)
          out.write(b, off, len)
        }

        override fun flush() = out.flush()

        override fun close() = out.close()
      }
  }

  /**
   * Imports [file] into [libraryDir] under [displayName] (extension is replaced as needed) and
   * deletes [file]. Returns the library files created.
   */
  fun import(file: File, libraryDir: File, displayName: String, limits: Limits = Limits()): List<File> =
    import(file, libraryDir, displayName, depth = 0, budget = Budget(limits))

  private fun import(file: File, libraryDir: File, displayName: String, depth: Int, budget: Budget): List<File> {
    libraryDir.mkdirs()
    val title = stripExtension(displayName)
    try {
      return when (sniff(file)) {
        ComicFormat.PDF -> listOf(moveInto(file, libraryDir, "$title.pdf"))
        ComicFormat.ZIP -> importZip(file, libraryDir, title, depth, budget)
        ComicFormat.CBR -> importRar(file, libraryDir, title, depth, budget)
        else -> throw IOException("\"$displayName\" is not a CBZ, CBR, ZIP, PDF or EPUB file")
      }
    } finally {
      file.delete()
    }
  }

  private fun importZip(file: File, libraryDir: File, title: String, depth: Int, budget: Budget): List<File> {
    var isEpub = false
    val extracted = ArrayList<Pair<File, String>>()
    val images =
      try {
        ZipFile(file).use { zip ->
          val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
          budget.checkEntries(names.size)
          val bundledNames = names.filter(::isComicFile)
          // An EPUB is itself a zip; this file is what marks one.
          if (names.any { it == "META-INF/container.xml" }) {
            isEpub = true
            0
          } else if (bundledNames.isNotEmpty() && depth == 0) {
            for (name in bundledNames) {
              val temp = File.createTempFile("bundle", ".tmp", libraryDir)
              extracted += temp to name.substringAfterLast('/')
              zip.getInputStream(zip.getEntry(name)).use { input -> budget.limit(temp.outputStream()).use { input.copyTo(it) } }
            }
            0
          } else {
            names.count(::isImage)
          }
        }
      } catch (e: Exception) {
        // Leave nothing half-unpacked behind.
        extracted.forEach { it.first.delete() }
        throw e
      }
    if (isEpub) return listOf(moveInto(file, libraryDir, "$title.epub"))
    if (extracted.isNotEmpty()) return importBundle(extracted, libraryDir, budget)
    if (images == 0) throw IOException("\"$title\" contains no comic pages")
    return listOf(moveInto(file, libraryDir, "$title.cbz"))
  }

  private fun importRar(file: File, libraryDir: File, title: String, depth: Int, budget: Budget): List<File> {
    val bundled = ArrayList<Pair<File, String>>()
    var target: File? = null
    try {
      Archive(file).use { rar ->
        val headers = rar.fileHeaders.filter { !it.isDirectory }
        budget.checkEntries(headers.size)
        val bundledHeaders = headers.filter { isComicFile(it.fileName.replace('\\', '/')) }
        if (bundledHeaders.isNotEmpty() && depth == 0) {
          for (header in bundledHeaders) {
            val temp = File.createTempFile("bundle", ".tmp", libraryDir)
            bundled.add(temp to header.fileName.replace('\\', '/').substringAfterLast('/'))
            budget.limit(temp.outputStream()).use { rar.extractFile(header, it) }
          }
        } else {
          val pages = headers.filter { isImage(it.fileName.replace('\\', '/')) }
          if (pages.isEmpty()) throw IOException("\"$title\" contains no comic pages")
          val out = uniqueFile(libraryDir, "$title.cbz")
          target = out
          ZipOutputStream(out.outputStream().buffered()).use { zip ->
            // Pages are already compressed images; storing them is faster and no larger.
            zip.setLevel(0)
            val used = HashSet<String>()
            for (header in pages) {
              var name = header.fileName.replace('\\', '/').trimStart('/')
              if (name.split('/').any { it == ".." }) name = name.substringAfterLast('/')
              if (!used.add(name)) continue
              zip.putNextEntry(ZipEntry(name))
              // Each page counts against the ceilings; closing the limiter must not close the zip.
              val page = budget.limit(object : FilterOutputStream(zip) { override fun close() = Unit })
              rar.extractFile(header, page)
              page.flush()
              zip.closeEntry()
            }
          }
        }
      }
    } catch (e: Exception) {
      target?.delete()
      bundled.forEach { it.first.delete() }
      if (e is IOException) throw e
      // junrar reports unsupported archives (notably RAR5) with its own exception types.
      throw IOException("Could not read RAR archive \"$title\" (${e.javaClass.simpleName}); RAR5 is not supported", e)
    }
    return target?.let(::listOf) ?: importBundle(bundled, libraryDir, budget)
  }

  private fun importBundle(parts: List<Pair<File, String>>, libraryDir: File, budget: Budget): List<File> {
    val results = ArrayList<File>()
    var firstError: IOException? = null
    for ((temp, name) in parts) {
      try {
        results += import(temp, libraryDir, name, depth = 1, budget = budget)
      } catch (e: IOException) {
        if (firstError == null) firstError = e
      } finally {
        temp.delete()
      }
    }
    if (results.isEmpty()) throw firstError ?: IOException("Archive contained no readable comics")
    return results
  }

  private fun moveInto(file: File, dir: File, name: String): File {
    val dest = uniqueFile(dir, name)
    if (!file.renameTo(dest)) {
      file.copyTo(dest, overwrite = true)
    }
    return dest
  }

  /** A safe library title: extension removed, characters that are illegal in file names dropped. */
  fun stripExtension(name: String): String {
    val base = if (ComicFormat.fromName(name) != null) name.substringBeforeLast('.') else name
    return base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.').ifEmpty { "comic" }
  }

  /** A file in [dir] called [name], or with " (2)", " (3)"… added if that is taken. */
  fun uniqueFile(dir: File, name: String): File {
    var candidate = File(dir, name)
    var n = 2
    while (candidate.exists()) {
      candidate = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}")
      n++
    }
    return candidate
  }
}
