package dev.local.androidreader.library

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

/** Encrypts small secrets (passwords, API keys, cookies) before they are written to disk. */
interface SecretBox {
  /** Returns text that only [decrypt] on this device can turn back. Throws if that is not possible. */
  fun encrypt(plain: String): String

  /** Throws if [stored] was not produced by [encrypt] with the same key, or has been altered. */
  fun decrypt(stored: String): String
}

/**
 * AES-256 in GCM mode, which both hides the secret and detects tampering. Each call uses a fresh
 * random nonce, stored in front of the result, so encrypting the same password twice gives
 * different output.
 */
open class AesGcmSecretBox(private val key: () -> SecretKey) : SecretBox {
  @Synchronized
  override fun encrypt(plain: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val nonce = cipher.iv
    check(nonce.size == NONCE_BYTES) { "Unexpected nonce size ${nonce.size}" }
    return Base64.encode(nonce + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
  }

  @Synchronized
  override fun decrypt(stored: String): String {
    val bytes = Base64.decode(stored)
    require(bytes.size > NONCE_BYTES) { "Too short to be an encrypted secret" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
    return String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES), Charsets.UTF_8)
  }

  private companion object {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
  }
}

/**
 * A [SecretBox] whose key lives in the Android Keystore. The key is created on first use, never
 * leaves the keystore (on most phones it is held in dedicated hardware), and cannot be copied off
 * the device.
 *
 * What this protects: a copy of the app's saved data taken off the phone is useless without the
 * key. What it does not: anything able to run as this app on this phone, such as a rooted
 * device, can ask the keystore to decrypt.
 *
 * The key is not tied to the lock screen, because downloads need the sign-in while the phone is
 * locked.
 */
class KeystoreSecretBox(alias: String = "openreader.signins.v1") :
  AesGcmSecretBox({
    val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
    (keystore.getKey(alias, null) as? SecretKey)
      ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        .apply {
          init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .setKeySize(256)
              .build()
          )
        }
        .generateKey()
  }) {
  private companion object {
    const val PROVIDER = "AndroidKeyStore"
  }
}

/**
 * How secrets are written into the saved-settings file. Every stored secret is a token:
 * `e:` followed by encrypted text, or `p:` followed by the secret itself when encryption is not
 * available on this device. An empty secret stays empty.
 *
 * Sealing never loses a secret: if encrypting fails, or the result does not decrypt back to the
 * same text, the secret is kept in its `p:` form, which is no worse than before encryption existed.
 */
class SecretSealer(private val box: SecretBox?) {
  fun seal(secret: String): String {
    if (secret.isEmpty()) return ""
    val encrypted =
      box?.let { runCatching { it.encrypt(secret).takeIf { text -> it.decrypt(text) == secret } }.getOrNull() }
    return if (encrypted != null) ENCRYPTED + encrypted else PLAIN + secret
  }

  /** The secret, or null when it was encrypted and can no longer be decrypted on this device. */
  fun open(token: String): String? =
    when {
      token.isEmpty() -> ""
      token.startsWith(PLAIN) -> token.removePrefix(PLAIN)
      token.startsWith(ENCRYPTED) -> box?.let { runCatching { it.decrypt(token.removePrefix(ENCRYPTED)) }.getOrNull() }
      else -> null
    }

  fun isEncrypted(token: String): Boolean = token.startsWith(ENCRYPTED)

  private companion object {
    const val ENCRYPTED = "e:"
    const val PLAIN = "p:"
  }
}
