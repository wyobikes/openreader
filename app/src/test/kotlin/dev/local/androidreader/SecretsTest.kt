package dev.local.androidreader

import dev.local.androidreader.library.AesGcmSecretBox
import dev.local.androidreader.library.LibraryRepository
import dev.local.androidreader.library.SecretBox
import dev.local.androidreader.library.SecretSealer
import dev.local.androidreader.source.KomgaConfig
import dev.local.androidreader.source.OpdsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecretsTest {
  @get:Rule val tmp = TemporaryFolder()

  /** Stands in for the phone's keystore: a key that exists only in this object. */
  private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  private fun box(key: SecretKey = newKey()) = AesGcmSecretBox { key }

  private val broken =
    object : SecretBox {
      override fun encrypt(plain: String): String = throw IllegalStateException("keystore unavailable")

      override fun decrypt(stored: String): String = throw IllegalStateException("keystore unavailable")
    }

  @Test
  fun encryptionRoundTripsAndHidesTheSecret() {
    val box = box()
    for (secret in listOf("hunter2", "pässwörd with spaces & symbols !\"#", "x", "a".repeat(5000))) {
      val stored = box.encrypt(secret)
      // A very short secret can turn up in the encrypted text by chance, so only longer ones are checked.
      if (secret.length >= 6) assertFalse(stored.contains(secret))
      assertEquals(secret, box.decrypt(stored))
    }
    // The same secret never encrypts to the same text twice.
    assertNotEquals(box.encrypt("hunter2"), box.encrypt("hunter2"))
  }

  @Test
  fun alteredOrForeignDataIsRejected() {
    val key = newKey()
    val stored = box(key).encrypt("hunter2")
    // Flip one character in the middle.
    val i = stored.length / 2
    val altered = stored.substring(0, i) + (if (stored[i] == 'A') 'B' else 'A') + stored.substring(i + 1)
    assertThrows(Exception::class.java) { box(key).decrypt(altered) }
    // A different key, as after the phone's keystore loses the original.
    assertThrows(Exception::class.java) { box().decrypt(stored) }
    assertThrows(Exception::class.java) { box(key).decrypt("") }
    assertThrows(Exception::class.java) { box(key).decrypt("not base64 !!") }
  }

  @Test
  fun sealingNeverLosesASecret() {
    val working = SecretSealer(box())
    val token = working.seal("hunter2")
    assertTrue(working.isEncrypted(token))
    assertFalse(token.contains("hunter2"))
    assertEquals("hunter2", working.open(token))
    assertEquals("", working.seal(""))
    assertEquals("", working.open(""))

    // No keystore, or one that fails: the secret is kept readable instead of being dropped.
    for (sealer in listOf(SecretSealer(null), SecretSealer(broken))) {
      val plain = sealer.seal("hunter2")
      assertFalse(sealer.isEncrypted(plain))
      assertEquals("hunter2", sealer.open(plain))
    }
    // A secret that itself looks like a token is still stored and read back exactly.
    for (odd in listOf("e:abc", "p:abc", "p:", ":")) {
      assertEquals(odd, working.open(working.seal(odd)))
      assertEquals(odd, SecretSealer(null).open(SecretSealer(null).seal(odd)))
    }

    // Encrypted with a key that is gone: reported as unreadable, not as an empty or wrong secret.
    assertNull(SecretSealer(box()).open(token))
    assertNull(SecretSealer(null).open(token))
    assertNull(working.open("garbage"))
  }

  private fun repository(dir: File, state: File, box: SecretBox?) = LibraryRepository(dir, state, box)

  @Test
  fun savedSignInsAreEncryptedOnDiskAndReadBack() {
    val dir = tmp.newFolder("lib")
    val state = File(tmp.root, "library.json")
    val key = newKey()
    val repo = repository(dir, state, box(key))
    repo.saveOpds(OpdsConfig("Calibre", "http://nas:8083/opds", "reader", "opds-password-123"))
    repo.saveKomga(KomgaConfig("Komga", "https://komga.example", "me@example.org", "komga-password-456", "api-key-789"))
    repo.saveKomga(KomgaConfig("Open", "http://open.example"))

    val onDisk = state.readText()
    for (secret in listOf("opds-password-123", "komga-password-456", "api-key-789")) assertFalse(secret, onDisk.contains(secret))
    // Names, addresses and user names are not secrets and stay readable.
    assertTrue(onDisk.contains("http://nas:8083/opds") && onDisk.contains("reader") && onDisk.contains("\"secretsFormat\":1"))

    val reopened = repository(dir, state, box(key))
    assertEquals("opds-password-123", reopened.opdsCatalogs.value.single().password)
    val komga = reopened.komgaServers.value.first { it.name == "Komga" }
    assertEquals("komga-password-456", komga.password)
    assertEquals("api-key-789", komga.apiKey)
    assertEquals("", reopened.komgaServers.value.first { it.name == "Open" }.password)
    assertTrue(reopened.lostSignIns.isEmpty())
  }

  @Test
  fun aFileFromBeforeEncryptionIsMigratedWithNothingLost() {
    val dir = tmp.newFolder("lib")
    val state = File(tmp.root, "library.json")
    // Exactly what earlier versions of the app wrote.
    state.writeText(
      """{"comics":{"Issue 1.cbz":{"lastPage":4,"pageCount":20,"lastOpened":1}},
          "opds":[{"name":"Calibre","url":"http://nas:8083/opds","username":"reader","password":"old-plain-password"}],
          "komga":[{"name":"Komga","url":"http://nas:25600","username":"me@example.org","password":"e:looks-like-a-token","apiKey":"old-api-key"}],
          "theme":"DARK","convertPdfToEpub":true,"bookTextZoom":130}"""
    )
    val key = newKey()
    val repo = repository(dir, state, box(key))
    assertEquals("old-plain-password", repo.opdsCatalogs.value.single().password)
    // A password that happens to look like a token is not mistaken for one.
    assertEquals("e:looks-like-a-token", repo.komgaServers.value.single().password)
    assertEquals("old-api-key", repo.komgaServers.value.single().apiKey)

    // The file was rewritten at once, sealed, and nothing else in it changed.
    val onDisk = state.readText()
    assertFalse(onDisk.contains("old-plain-password") || onDisk.contains("old-api-key") || onDisk.contains("looks-like-a-token"))
    val reopened = repository(dir, state, box(key))
    assertEquals("old-plain-password", reopened.opdsCatalogs.value.single().password)
    assertEquals("e:looks-like-a-token", reopened.komgaServers.value.single().password)
    assertEquals("old-api-key", reopened.komgaServers.value.single().apiKey)
    assertEquals(4, reopened.state("Issue 1.cbz").lastPage)
    assertEquals(130, reopened.bookTextZoom)
    assertTrue(reopened.convertPdfToEpub.value)
  }

  @Test
  fun aLostKeyKeepsTheServerAndAsksForTheSignInAgain() {
    val dir = tmp.newFolder("lib")
    val state = File(tmp.root, "library.json")
    val first = repository(dir, state, box())
    val calibre = OpdsConfig("Calibre", "http://nas:8083/opds", "reader", "opds-password-123")
    first.saveOpds(calibre)
    first.saveKomga(KomgaConfig("Open", "http://open.example"))

    // Same file, but the keystore now holds a different key.
    val afterLoss = repository(dir, state, box())
    val server = afterLoss.opdsCatalogs.value.single()
    assertEquals("Calibre" to "reader", server.name to server.username)
    assertEquals("", server.password)
    assertEquals(setOf(calibre.id), afterLoss.lostSignIns)

    // Entering the sign-in again repairs it.
    afterLoss.saveOpds(calibre.copy(password = "new-password"))
    assertTrue(afterLoss.lostSignIns.isEmpty())
    assertEquals("new-password", afterLoss.opdsCatalogs.value.single().password)
  }

  @Test
  fun aPhoneWithoutAWorkingKeystoreStillKeepsItsServers() {
    val dir = tmp.newFolder("lib")
    val state = File(tmp.root, "library.json")
    state.writeText("""{"opds":[{"name":"Calibre","url":"http://nas:8083/opds","username":"reader","password":"old-plain-password"}]}""")
    val repo = repository(dir, state, broken)
    assertEquals("old-plain-password", repo.opdsCatalogs.value.single().password)
    repo.saveKomga(KomgaConfig("Komga", "http://nas:25600", apiKey = "api-key-789"))

    val reopened = repository(dir, state, broken)
    assertEquals("old-plain-password", reopened.opdsCatalogs.value.single().password)
    assertEquals("api-key-789", reopened.komgaServers.value.single().apiKey)
    assertTrue(reopened.lostSignIns.isEmpty())
    // And once the keystore works, the next save encrypts them.
    val key = newKey()
    val recovered = repository(dir, state, box(key))
    recovered.setBookTextZoom(110)
    assertFalse(state.readText().contains("old-plain-password") || state.readText().contains("api-key-789"))
    assertEquals("old-plain-password", repository(dir, state, box(key)).opdsCatalogs.value.single().password)
  }
}
