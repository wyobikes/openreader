package dev.local.longbox.library

import com.github.junrar.Archive
import dev.local.longbox.source.ComicFormat
import java.io.File
import java.io.IOException
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
   * Imports [file] into [libraryDir] under [displayName] (extension is replaced as needed) and
   * deletes [file]. Returns the library files created.
   */
  fun import(file: File, libraryDir: File, displayName: String, depth: Int = 0): List<File> {
    libraryDir.mkdirs()
    val title = stripExtension(displayName)
    try {
      return when (sniff(file)) {
        ComicFormat.PDF -> listOf(moveInto(file, libraryDir, "$title.pdf"))
        ComicFormat.ZIP -> importZip(file, libraryDir, title, depth)
        ComicFormat.CBR -> importRar(file, libraryDir, title, depth)
        else -> throw IOException("\"$displayName\" is not a CBZ, CBR, ZIP or PDF file")
      }
    } finally {
      file.delete()
    }
  }

  private fun importZip(file: File, libraryDir: File, title: String, depth: Int): List<File> {
    val (bundled, images) =
      ZipFile(file).use { zip ->
        val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        val bundledNames = names.filter(::isComicFile)
        if (bundledNames.isNotEmpty() && depth == 0) {
          val extracted =
            bundledNames.map { name ->
              val temp = File.createTempFile("bundle", ".tmp", libraryDir)
              zip.getInputStream(zip.getEntry(name)).use { input -> temp.outputStream().use { input.copyTo(it) } }
              temp to name.substringAfterLast('/')
            }
          extracted to 0
        } else {
          emptyList<Pair<File, String>>() to names.count(::isImage)
        }
      }
    if (bundled.isNotEmpty()) return importBundle(bundled, libraryDir)
    if (images == 0) throw IOException("\"$title\" contains no comic pages")
    return listOf(moveInto(file, libraryDir, "$title.cbz"))
  }

  private fun importRar(file: File, libraryDir: File, title: String, depth: Int): List<File> {
    val bundled = ArrayList<Pair<File, String>>()
    var target: File? = null
    try {
      Archive(file).use { rar ->
        val headers = rar.fileHeaders.filter { !it.isDirectory }
        val bundledHeaders = headers.filter { isComicFile(it.fileName.replace('\\', '/')) }
        if (bundledHeaders.isNotEmpty() && depth == 0) {
          for (header in bundledHeaders) {
            val temp = File.createTempFile("bundle", ".tmp", libraryDir)
            temp.outputStream().use { rar.extractFile(header, it) }
            bundled.add(temp to header.fileName.replace('\\', '/').substringAfterLast('/'))
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
              rar.extractFile(header, zip)
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
    return target?.let(::listOf) ?: importBundle(bundled, libraryDir)
  }

  private fun importBundle(parts: List<Pair<File, String>>, libraryDir: File): List<File> {
    val results = ArrayList<File>()
    var firstError: IOException? = null
    for ((temp, name) in parts) {
      try {
        results += import(temp, libraryDir, name, depth = 1)
      } catch (e: IOException) {
        if (firstError == null) firstError = e
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

  private fun uniqueFile(dir: File, name: String): File {
    var candidate = File(dir, name)
    var n = 2
    while (candidate.exists()) {
      candidate = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}")
      n++
    }
    return candidate
  }
}
