package dev.local.androidreader

import dev.local.androidreader.library.Importer
import dev.local.androidreader.library.LibraryRepository
import dev.local.androidreader.library.NaturalOrder
import dev.local.androidreader.reader.PageSource
import dev.local.androidreader.source.ComicFormat
import dev.local.androidreader.source.OpdsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class LibraryTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun zip(name: String, entries: Map<String, ByteArray>): File {
    val file = tmp.newFile(name)
    ZipOutputStream(file.outputStream()).use { out ->
      entries.forEach { (entryName, bytes) ->
        out.putNextEntry(ZipEntry(entryName))
        out.write(bytes)
        out.closeEntry()
      }
    }
    return file
  }

  private fun zipBytes(entries: Map<String, ByteArray>): ByteArray = zip("inner${System.nanoTime()}.zip", entries).readBytes()

  private val page = byteArrayOf(1, 2, 3)

  @Test
  fun naturalOrderSortsNumbersByValue() {
    val sorted = listOf("p10.jpg", "p2.jpg", "P1.jpg", "p02a.jpg", "cover.jpg").sortedWith(NaturalOrder)
    assertEquals(listOf("cover.jpg", "P1.jpg", "p2.jpg", "p02a.jpg", "p10.jpg"), sorted)
  }

  @Test
  fun pageNamesSkipJunkAndSort() {
    val names = listOf("b/10.png", "b/9.jpg", "__MACOSX/b/._9.jpg", "b/.hidden.jpg", "ComicInfo.xml", "b/Thumbs.db")
    assertEquals(listOf("b/9.jpg", "b/10.png"), PageSource.pageNames(names))
  }

  @Test
  fun sampleSizeHalvesUntilNearTarget() {
    assertEquals(1, PageSource.sampleSize(1500, 2300, 2000))
    assertEquals(2, PageSource.sampleSize(3000, 4600, 2000))
    assertEquals(4, PageSource.sampleSize(6000, 9000, 2000))
  }

  @Test
  fun zipOfImagesBecomesCbzWhateverItWasCalled() {
    val library = tmp.newFolder("lib")
    val source = zip("download.bin", mapOf("01.jpg" to page, "02.jpg" to page))
    assertEquals(ComicFormat.ZIP, Importer.sniff(source))
    val result = Importer.import(source, library, "My: Comic?.zip")
    assertEquals(listOf("My Comic.cbz"), result.map { it.name })
    assertFalse(source.exists())
    ZipFile(result[0]).use { assertEquals(2, it.size()) }
  }

  @Test
  fun bundleIsUnpackedAndNamesCannotEscapeTheLibrary() {
    val library = tmp.newFolder("lib")
    val inner = zipBytes(mapOf("1.png" to page))
    val bundle =
      zip(
        "bundle.zip",
        mapOf(
          "Series/Issue 01.cbz" to inner,
          "../../evil.cbz" to inner,
          "Series/readme.txt" to page,
          "Series/broken.cbz" to byteArrayOf(9, 9, 9, 9, 9),
        ),
      )
    val result = Importer.import(bundle, library, "bundle.zip")
    assertEquals(setOf("Issue 01.cbz", "evil.cbz"), result.map { it.name }.toSet())
    assertTrue(result.all { it.parentFile == library })
    // Nothing left behind: no temp files, nothing outside the library.
    assertEquals(setOf("Issue 01.cbz", "evil.cbz"), library.list()!!.toSet())
    assertFalse(File(tmp.root, "evil.cbz").exists())
  }

  @Test
  fun duplicateNamesGetASuffixAndPdfIsRecognised() {
    val library = tmp.newFolder("lib")
    Importer.import(zip("a.zip", mapOf("1.jpg" to page)), library, "Same.cbz")
    val second = Importer.import(zip("b.zip", mapOf("1.jpg" to page)), library, "Same.cbz")
    assertEquals("Same (2).cbz", second[0].name)
    val pdf = tmp.newFile("doc").apply { writeText("%PDF-1.7 fake") }
    assertEquals("Doc.pdf", Importer.import(pdf, library, "Doc")[0].name)
  }

  @Test
  fun rejectsFilesThatAreNotComics() {
    val library = tmp.newFolder("lib")
    val html = tmp.newFile("page.cbz").apply { writeText("<html>Sign in to download</html>") }
    assertThrows(IOException::class.java) { Importer.import(html, library, "page.cbz") }
    assertFalse(html.exists())
    val noPages = zip("empty.zip", mapOf("notes.txt" to page))
    assertThrows(IOException::class.java) { Importer.import(noPages, library, "empty.zip") }
    assertEquals(0, library.list()!!.size)
  }

  @Test
  fun repositoryPersistsProgressAndCatalogues() {
    val dir = tmp.newFolder("lib")
    val state = File(tmp.root, "library.json")
    File(dir, "Issue 10.cbz").writeBytes(page)
    File(dir, "Issue 2.cbz").writeBytes(page)
    File(dir, "notes.txt").writeBytes(page)
    File(dir, "x.cbz.part").writeBytes(page)

    val repo = LibraryRepository(dir, state)
    assertEquals(listOf("Issue 2", "Issue 10"), repo.comics.value.map { it.title })
    val first = repo.comics.value[0]
    assertFalse(first.isStarted)
    repo.updateState(first.file) { it.copy(lastPage = 4, pageCount = 5, lastOpened = 1) }
    assertTrue(repo.comics.value[0].isFinished)
    repo.saveOpds(OpdsConfig("Home", "http://nas/opds"))
    repo.saveOpds(OpdsConfig("Home renamed", "http://nas/opds"))

    val reopened = LibraryRepository(dir, state)
    assertEquals(4, reopened.comics.value[0].state.lastPage)
    assertEquals(listOf("Home renamed"), reopened.opdsCatalogs.value.map { it.name })

    reopened.delete(first.file)
    assertEquals(listOf("Issue 10"), reopened.comics.value.map { it.title })
    assertEquals(0, LibraryRepository(dir, state).state(first.file).lastPage)
  }

  @Test
  fun itemsSortByFileTypeUntilMovedAndTheMoveIsRemembered() {
    val dir = tmp.newFolder("lib")
    val state = File(tmp.root, "library.json")
    listOf("Issue 1.cbz", "Novel.epub", "Scan.pdf").forEach { File(dir, it).writeBytes(page) }

    val repo = LibraryRepository(dir, state)
    fun shelves(r: LibraryRepository) = r.comics.value.associate { it.file.name to it.isComic }
    assertEquals(mapOf("Issue 1.cbz" to true, "Novel.epub" to false, "Scan.pdf" to false), shelves(repo))

    // A comic saved as a PDF goes to Comics; reading progress on it is untouched.
    val scan = repo.comics.value.first { it.file.name == "Scan.pdf" }
    repo.updateState(scan.file) { it.copy(lastPage = 7, pageCount = 20, lastOpened = 1) }
    repo.moveToShelf(scan.file, comics = true)
    repo.moveToShelf(File(dir, "Issue 1.cbz"), comics = false)
    assertEquals(mapOf("Issue 1.cbz" to false, "Novel.epub" to false, "Scan.pdf" to true), shelves(repo))
    assertEquals(7, repo.comics.value.first { it.file.name == "Scan.pdf" }.state.lastPage)
    assertTrue(scan.file.exists())

    // Survives a restart and a rescan of the folder, and can be moved back.
    val reopened = LibraryRepository(dir, state)
    reopened.refresh()
    assertEquals(mapOf("Issue 1.cbz" to false, "Novel.epub" to false, "Scan.pdf" to true), shelves(reopened))
    reopened.moveToShelf(scan.file, comics = false)
    assertEquals(false, shelves(reopened)["Scan.pdf"])
  }

  @Test
  fun corruptStateFileIsIgnored() {
    val dir = tmp.newFolder("lib")
    val state = File(tmp.root, "library.json").apply { writeText("{not json") }
    assertEquals(0, LibraryRepository(dir, state).comics.value.size)
  }
}
