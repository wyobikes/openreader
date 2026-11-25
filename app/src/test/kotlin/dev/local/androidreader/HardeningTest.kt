package dev.local.androidreader

import dev.local.androidreader.download.DownloadHeaders
import dev.local.androidreader.epub.EpubBook
import dev.local.androidreader.library.AesGcmSecretBox
import dev.local.androidreader.library.Importer
import dev.local.androidreader.library.SecretSealer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator

class HardeningTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun sealer(): SecretSealer {
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    return SecretSealer(AesGcmSecretBox { key })
  }

  @Test
  fun referrerFollowsBrowserDefaults() {
    val page = "https://user:pw@comics.example/series/42?page=3#panel-7"
    // Within one site: the page address, minus anything private in it.
    assertEquals("https://comics.example/series/42?page=3", DownloadHeaders.referer(page, "https://comics.example/files/42.cbz"))
    // To another site: only which site the link came from.
    assertEquals("https://comics.example/", DownloadHeaders.referer(page, "https://cdn.example/files/42.cbz"))
    assertEquals("https://comics.example/", DownloadHeaders.referer(page, "https://comics.example:8443/files/42.cbz"))
    // From a secure page to an insecure download: nothing.
    assertNull(DownloadHeaders.referer(page, "http://comics.example/files/42.cbz"))
    assertEquals("http://nas:8083/", DownloadHeaders.referer("http://nas:8083/opds/new", "https://elsewhere.example/a.cbz"))
    assertNull(DownloadHeaders.referer("about:blank", "https://comics.example/a.cbz"))
    assertNull(DownloadHeaders.referer(page, "not a url"))
  }

  @Test
  fun queuedHeadersAreEncryptedAndReadBack() {
    val sealer = sealer()
    val headers = mapOf("User-Agent" to "Mozilla/5.0 (Android)", "Cookie" to "session=abc123; theme=dark", "Referer" to "https://comics.example/")
    val packed = DownloadHeaders.pack(headers, sealer)
    assertFalse(packed.contains("abc123") || packed.contains("Mozilla"))
    assertEquals(headers, DownloadHeaders.unpack(packed, sealer))
    // Nothing to store, nothing stored.
    assertEquals("", DownloadHeaders.pack(emptyMap(), sealer))
    assertEquals("", DownloadHeaders.pack(mapOf("Cookie" to "  "), sealer))
    assertTrue(DownloadHeaders.unpack("", sealer).isEmpty())
    assertTrue(DownloadHeaders.unpack(null, sealer).isEmpty())
    // Encrypted on a phone whose key has since gone: the download simply proceeds without them.
    assertTrue(DownloadHeaders.unpack(packed, sealer()).isEmpty())
  }

  @Test
  fun withoutEncryptionCookiesAreNotWrittenToDisk() {
    val plain = SecretSealer(null)
    val packed = DownloadHeaders.pack(mapOf("User-Agent" to "Mozilla/5.0", "cookie" to "session=abc123", "Authorization" to "Basic eDp5"), plain)
    assertFalse(packed.contains("abc123") || packed.contains("eDp5"))
    assertEquals(mapOf("User-Agent" to "Mozilla/5.0"), DownloadHeaders.unpack(packed, plain))
    assertEquals("", DownloadHeaders.pack(mapOf("Cookie" to "session=abc123"), plain))
  }

  @Test
  fun headersThatCouldSmuggleExtraLinesAreDropped() {
    val sealer = sealer()
    val packed =
      DownloadHeaders.pack(
        mapOf("User-Agent" to "ok", "X-Bad" to "one\nCookie: stolen=1", "Bad\rName" to "v", "Bad: Name" to "v"),
        sealer,
      )
    assertEquals(mapOf("User-Agent" to "ok"), DownloadHeaders.unpack(packed, sealer))
  }

  private fun zip(name: String, entries: Map<String, ByteArray>): File {
    val file = File(tmp.newFolder(), name)
    ZipOutputStream(file.outputStream()).use { out ->
      entries.forEach { (entryName, bytes) ->
        out.putNextEntry(ZipEntry(entryName))
        out.write(bytes)
        out.closeEntry()
      }
    }
    return file
  }

  /** A one-page comic. Random page data does not compress, so the archive is about [pageBytes] in size. */
  private fun comic(pageBytes: Int): ByteArray =
    zip("inner.zip", mapOf("1.png" to ByteArray(pageBytes).also { java.util.Random(pageBytes.toLong()).nextBytes(it) })).readBytes()

  @Test
  fun unpackingStopsAtTheSizeCeilingsAndLeavesNothingBehind() {
    val small = comic(100)
    // 200 KB of zeros compresses to almost nothing: a small archive that unpacks large.
    val large = ByteArray(200_000)

    // One file over the per-file ceiling.
    val library = tmp.newFolder("lib")
    val oneBig = zip("bundle.zip", mapOf("a.cbz" to small, "huge.cbz" to large))
    assertTrue(oneBig.length() < 5_000)
    val perFile = Importer.Limits(maxEntryBytes = 50_000, maxTotalBytes = 10_000_000)
    assertTrue(assertThrows(IOException::class.java) { Importer.import(oneBig, library, "bundle.zip", perFile) }.message!!.contains("too large"))
    assertEquals(0, library.list()!!.size)
    assertFalse(oneBig.exists())

    // Many files that are each fine but together pass the overall ceiling: ten comics of about
    // 20 KB each against a 60 KB allowance.
    val many = zip("many.zip", (1..10).associate { "issue $it.cbz" to comic(20_000 + it) })
    assertTrue(many.length() > 150_000)
    val overall = Importer.Limits(maxEntryBytes = 10_000_000, maxTotalBytes = 60_000)
    assertTrue(assertThrows(IOException::class.java) { Importer.import(many, library, "many.zip", overall) }.message!!.contains("too large"))
    assertEquals(0, library.list()!!.size)
    // The same archive with room to spare imports in full.
    val again = zip("many.zip", (1..10).associate { "issue $it.cbz" to comic(20_000 + it) })
    assertEquals(10, Importer.import(again, library, "many.zip", Importer.Limits(maxTotalBytes = 1_000_000)).size)
    library.listFiles()!!.forEach { it.delete() }

    // Too many entries is refused before anything is written.
    val crowded = zip("crowded.zip", (1..30).associate { "p$it.jpg" to byteArrayOf(1) })
    assertTrue(assertThrows(IOException::class.java) { Importer.import(crowded, library, "crowded.zip", Importer.Limits(maxEntries = 20)) }.message!!.contains("too many files"))
    assertEquals(0, library.list()!!.size)
  }

  @Test
  fun ordinaryArchivesAreUnaffectedByTheCeilings() {
    val library = tmp.newFolder("lib")
    val bundle = zip("bundle.zip", mapOf("Series/Issue 01.cbz" to comic(5_000), "Series/Issue 02.cbz" to comic(6_000)))
    val result = Importer.import(bundle, library, "bundle.zip", Importer.Limits(maxEntryBytes = 50_000, maxTotalBytes = 100_000, maxEntries = 10))
    assertEquals(setOf("Issue 01.cbz", "Issue 02.cbz"), result.map { it.name }.toSet())
    // Only the two comics remain: no temporary files.
    assertEquals(setOf("Issue 01.cbz", "Issue 02.cbz"), library.list()!!.toSet())
    // And the defaults are generous enough that a real import never meets them.
    assertTrue(Importer.Limits().maxEntryBytes >= 1L shl 30)
  }

  @Test
  fun bookIndexFilesCannotGrowWithoutLimit() {
    assertEquals(1000, EpubBook.readLimited(ByteArrayInputStream(ByteArray(1000)), 1000).size)
    assertThrows(IOException::class.java) { EpubBook.readLimited(ByteArrayInputStream(ByteArray(1001)), 1000) }
    assertEquals(0, EpubBook.readLimited(ByteArrayInputStream(ByteArray(0)), 10).size)

    // A book whose contents file expands past the limit is refused instead of exhausting memory.
    val container =
      """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>
         <rootfile full-path="big.opf"/></rootfiles></container>"""
    val bomb = zip("bomb.epub", mapOf("META-INF/container.xml" to container.toByteArray(), "big.opf" to ByteArray(EpubBook.MAX_INDEX_BYTES + 1) { ' '.code.toByte() }))
    assertTrue(bomb.length() < 100_000)
    assertTrue(assertThrows(IOException::class.java) { EpubBook.open(bomb) }.message!!.contains("too large"))
  }
}
