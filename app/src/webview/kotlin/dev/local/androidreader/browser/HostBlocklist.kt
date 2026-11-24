package dev.local.androidreader.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** A set of ad and tracker domains. A host is blocked when it or any parent domain is listed. */
class HostBlocklist(private val domains: Set<String>) {
  val size: Int
    get() = domains.size

  fun isBlocked(host: String): Boolean {
    var name = host.lowercase().trimEnd('.')
    while (true) {
      if (name in domains) return true
      val dot = name.indexOf('.')
      if (dot < 0) return false
      name = name.substring(dot + 1)
      // Never match on a bare top-level domain.
      if ('.' !in name) return false
    }
  }

  companion object {
    private val VALID = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)+$")
    private val LOCAL = setOf("localhost.localdomain", "0.0.0.0", "127.0.0.1", "255.255.255.255")

    /**
     * Reads the common list formats: hosts files (`0.0.0.0 ads.example`), plain domain lists,
     * and simple adblock rules (`||ads.example^`). Anything else is skipped.
     */
    fun parse(lines: Sequence<String>): HostBlocklist {
      val domains = HashSet<String>()
      for (raw in lines) {
        val line = raw.substringBefore('#').trim().lowercase()
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') continue
        val candidates =
          if (line.startsWith("||")) listOf(line.removePrefix("||").substringBefore('^'))
          else line.split(' ', '\t').filter { it.isNotEmpty() }.let { if (it.size > 1) it.drop(1) else it }
        for (candidate in candidates) {
          if (candidate !in LOCAL && VALID.matches(candidate)) domains.add(candidate)
        }
      }
      return HostBlocklist(domains)
    }
  }
}

/** Keeps the blocklist on disk and refreshes it from the network about once a week. */
class BlocklistStore(
  private val file: File,
  private val client: OkHttpClient,
  private val sourceUrl: String = DEFAULT_SOURCE,
) {
  @Volatile private var list = HostBlocklist(emptySet())
  private val mutex = Mutex()
  private var loaded = false

  val size: Int
    get() = list.size

  fun isBlocked(host: String): Boolean = list.isBlocked(host)

  /** Loads the saved list, then updates it if it is missing or stale. Safe to call repeatedly. */
  suspend fun ensureLoaded(now: Long = System.currentTimeMillis()) =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        if (!loaded && file.exists()) list = file.useLines(block = HostBlocklist::parse)
        loaded = true
        if (!file.exists() || now - file.lastModified() > MAX_AGE_MS) runCatching { update() }
      }
    }

  private fun update() {
    val temp = File(file.parentFile, file.name + ".tmp")
    client.newCall(Request.Builder().url(sourceUrl).build()).execute().use { response ->
      if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
      temp.outputStream().use { response.body.byteStream().copyTo(it) }
    }
    val fresh = temp.useLines(block = HostBlocklist::parse)
    // A tiny result means an error page or a moved list, not a real update; keep the old one.
    if (fresh.size < MIN_ENTRIES) {
      temp.delete()
      throw IOException("Blocklist looked wrong (${fresh.size} entries)")
    }
    file.delete()
    temp.renameTo(file)
    list = fresh
  }

  companion object {
    /** Steven Black's unified hosts list (MIT licence): ads and malware domains. */
    const val DEFAULT_SOURCE = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    const val MIN_ENTRIES = 1000
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
  }
}
