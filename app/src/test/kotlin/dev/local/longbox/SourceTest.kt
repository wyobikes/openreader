package dev.local.longbox

import dev.local.longbox.source.ComicFormat
import dev.local.longbox.source.InternetArchiveSource
import dev.local.longbox.source.OpdsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTest {
  @Test
  fun archiveSearchParsesEntriesAndPaging() {
    val body =
      """{"response":{"numFound":61,"start":0,"docs":[
        {"identifier":"planet-01","title":"Planet Stories 1","creator":["Fiction House","Someone"],"year":1939},
        {"identifier":"no-title"},
        {"title":"missing identifier"}]}}"""
    val page = InternetArchiveSource.parseSearch(body, "planet", 1)
    assertEquals(2, page.entries.size)
    assertEquals("Planet Stories 1", page.entries[0].title)
    assertEquals("Fiction House · 1939", page.entries[0].subtitle)
    assertEquals("https://archive.org/services/img/planet-01", page.entries[0].thumbnailUrl)
    assertEquals("no-title", page.entries[1].title)
    assertEquals("planet" to 2, InternetArchiveSource.decodeLocation(page.nextLocation))
    assertNull(InternetArchiveSource.parseSearch(body, "planet", 3).nextLocation)
  }

  @Test
  fun archiveFilesKeepComicsAndDropDerivedZips() {
    val body =
      """{"files":[
        {"name":"Comic Cuts 01.pdf","format":"Text PDF","size":"7596779","source":"derivative"},
        {"name":"Comic Cuts 01_jp2.zip","format":"Single Page Processed JP2 ZIP","size":"1","source":"derivative"},
        {"name":"sub dir/Comic Cuts #01.cbz","format":"Comic Book ZIP","size":"28371918","source":"original"},
        {"name":"__ia_thumb.jpg","source":"original"}]}"""
    val files = InternetArchiveSource.parseFiles("comic-cuts", body)
    assertEquals(listOf("Comic Cuts #01.cbz", "Comic Cuts 01.pdf"), files.map { it.name })
    assertEquals(ComicFormat.CBZ, files[0].format)
    assertEquals(28371918L, files[0].sizeBytes)
    assertEquals("https://archive.org/download/comic-cuts/sub%20dir/Comic%20Cuts%20%2301.cbz", files[0].url)
  }

  @Test
  fun archiveQueryStripsSyntaxAndKeepsLicenceScope() {
    val q = InternetArchiveSource.buildQuery("planet) OR licenseurl:* (")
    assertTrue(q.startsWith("(planet  OR licenseurl"))
    assertFalse(q.substringBefore(" AND mediatype").contains(":"))
    assertTrue(q.endsWith("licenseurl:(*creativecommons* OR *publicdomain*)"))
  }

  @Test
  fun opdsFeedParsesFoldersBooksAndLinks() {
    val xml =
      """<?xml version="1.0" encoding="UTF-8"?>
      <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
        <link rel="self" href="/opds/v1.2/catalog" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
        <link rel="next" href="/opds/v1.2/catalog?page=1" type="application/atom+xml"/>
        <link rel="search" href="/opds/v1.2/search?q={searchTerms}" type="application/atom+xml"/>
        <entry><title>All series</title><id>series</id>
          <link rel="subsection" href="series" type="application/atom+xml;profile=opds-catalog;kind=navigation"/></entry>
        <entry><title>Issue: One/Two</title><id>book1</id><author><name>A. Writer</name></author>
          <link rel="http://opds-spec.org/image/thumbnail" href="/img/1.jpg" type="image/jpeg"/>
          <link rel="http://opds-spec.org/acquisition" href="/books/1/file" type="application/vnd.comicbook+zip" length="1234"/>
          <link rel="http://opds-spec.org/acquisition" href="/books/1/file.epub" type="application/epub+zip"/></entry>
        <entry><title>Nothing usable</title><id>x</id><link rel="alternate" href="/x" type="text/html"/></entry>
      </feed>"""
    val feed = OpdsSource.parseFeed(xml, "https://srv.example/opds/v1.2/catalog", "opds:test")
    assertEquals("https://srv.example/opds/v1.2/catalog?page=1", feed.page.nextLocation)
    assertEquals("https://srv.example/opds/v1.2/search?q={searchTerms}", feed.searchUrl)
    assertEquals(2, feed.page.entries.size)
    val (folder, book) = feed.page.entries
    assertTrue(folder.isFolder)
    assertEquals("https://srv.example/opds/v1.2/series", folder.location)
    assertFalse(book.isFolder)
    assertEquals("A. Writer", book.subtitle)
    assertEquals("https://srv.example/img/1.jpg", book.thumbnailUrl)
    assertEquals(1, book.files.size)
    assertEquals("Issue One Two.cbz", book.files[0].name)
    assertEquals("https://srv.example/books/1/file", book.files[0].url)
    assertEquals(1234L, book.files[0].sizeBytes)
  }

  @Test
  fun formatDetection() {
    assertEquals(ComicFormat.CBR, ComicFormat.fromName("https://x/a%20b.CBR?token=1"))
    assertNull(ComicFormat.fromName("page.html"))
    assertEquals(ComicFormat.PDF, ComicFormat.fromMimeType("application/pdf; charset=binary"))
  }
}
