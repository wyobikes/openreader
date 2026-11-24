package dev.local.androidreader.library

import dev.local.androidreader.source.CalibreConfig
import dev.local.androidreader.source.KomgaConfig
import dev.local.androidreader.source.OpdsConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ComicState(
  val lastPage: Int = 0,
  val pageCount: Int = 0,
  val rightToLeft: Boolean = false,
  val lastOpened: Long = 0,
  /** For books: how far down the current chapter, from 0 to 1. */
  val scroll: Float = 0f,
)

data class Comic(val file: File, val state: ComicState) {
  val title: String
    get() = file.nameWithoutExtension

  val isFinished: Boolean
    get() = state.pageCount > 0 && state.lastPage >= state.pageCount - 1

  val isStarted: Boolean
    get() = state.lastOpened > 0

  /** A text book (EPUB), as opposed to a comic or PDF made of page images. */
  val isBook: Boolean
    get() = file.extension.equals("epub", ignoreCase = true)
}

/** Which colours the app uses. */
enum class ThemeMode(val label: String) {
  SYSTEM("Follow system"),
  LIGHT("Light"),
  DARK("Dark"),
}

@Serializable
private data class Saved(
  val comics: Map<String, ComicState> = emptyMap(),
  val opds: List<OpdsConfig> = emptyList(),
  val komga: List<KomgaConfig> = emptyList(),
  val calibre: List<CalibreConfig> = emptyList(),
  val theme: ThemeMode = ThemeMode.SYSTEM,
  val convertPdfToEpub: Boolean = false,
  val bookTextZoom: Int = 100,
)

/** The comics folder plus a small JSON file holding reading positions and catalogue settings. */
class LibraryRepository(val dir: File, private val stateFile: File) {
  private val json = Json { ignoreUnknownKeys = true }
  private var saved: Saved = load()

  private val _comics = MutableStateFlow<List<Comic>>(emptyList())
  val comics: StateFlow<List<Comic>> = _comics.asStateFlow()

  private val _opds = MutableStateFlow(saved.opds)
  val opdsCatalogs: StateFlow<List<OpdsConfig>> = _opds.asStateFlow()

  private val _theme = MutableStateFlow(saved.theme)
  val themeMode: StateFlow<ThemeMode> = _theme.asStateFlow()

  @Synchronized
  fun setTheme(mode: ThemeMode) {
    persist(saved.copy(theme = mode))
    _theme.value = mode
  }

  private val _convertPdf = MutableStateFlow(saved.convertPdfToEpub)

  /** When on, a downloaded PDF also gets an EPUB copy made from its text. */
  val convertPdfToEpub: StateFlow<Boolean> = _convertPdf.asStateFlow()

  @Synchronized
  fun setConvertPdfToEpub(enabled: Boolean) {
    persist(saved.copy(convertPdfToEpub = enabled))
    _convertPdf.value = enabled
  }

  /** Text size in the book reader, as a percentage. */
  val bookTextZoom: Int
    get() = saved.bookTextZoom

  @Synchronized
  fun setBookTextZoom(percent: Int) = persist(saved.copy(bookTextZoom = percent.coerceIn(60, 250)))

  private val _calibre = MutableStateFlow(saved.calibre)
  val calibreServers: StateFlow<List<CalibreConfig>> = _calibre.asStateFlow()

  @Synchronized
  fun saveCalibre(config: CalibreConfig) {
    persist(saved.copy(calibre = saved.calibre.filter { it.url != config.url } + config))
    _calibre.value = saved.calibre
  }

  @Synchronized
  fun removeCalibre(config: CalibreConfig) {
    persist(saved.copy(calibre = saved.calibre.filter { it.url != config.url }))
    _calibre.value = saved.calibre
  }

  private val _komga = MutableStateFlow(saved.komga)
  val komgaServers: StateFlow<List<KomgaConfig>> = _komga.asStateFlow()

  init {
    refresh()
  }

  private fun load(): Saved =
    runCatching { json.decodeFromString(Saved.serializer(), stateFile.readText()) }.getOrDefault(Saved())

  @Synchronized
  private fun persist(next: Saved) {
    saved = next
    val temp = File(stateFile.parentFile, stateFile.name + ".tmp")
    temp.writeText(json.encodeToString(Saved.serializer(), next))
    if (!temp.renameTo(stateFile)) {
      temp.copyTo(stateFile, overwrite = true)
      temp.delete()
    }
  }

  /** Rescans the folder. Cheap enough to call after every import or delete. */
  @Synchronized
  fun refresh() {
    dir.mkdirs()
    val files =
      dir.listFiles { f -> f.isFile && f.extension.lowercase().let { it == "cbz" || it == "pdf" || it == "epub" } }.orEmpty()
    _comics.value =
      files
        .map { Comic(it, saved.comics[it.name] ?: ComicState()) }
        .sortedWith(compareBy(NaturalOrder) { it.title })
  }

  fun updateState(file: File, transform: (ComicState) -> ComicState) = updateState(file.name, transform)

  /** [key] is a library file's name, or an identifier for a book read straight from a server. */
  @Synchronized
  fun updateState(key: String, transform: (ComicState) -> ComicState) {
    val next = transform(saved.comics[key] ?: ComicState())
    persist(saved.copy(comics = saved.comics + (key to next)))
    _comics.update { list -> list.map { if (it.file.name == key) it.copy(state = next) else it } }
  }

  fun state(file: File): ComicState = state(file.name)

  fun state(key: String): ComicState = saved.comics[key] ?: ComicState()

  @Synchronized
  fun delete(file: File) {
    file.delete()
    persist(saved.copy(comics = saved.comics - file.name))
    refresh()
  }

  @Synchronized
  fun saveOpds(config: OpdsConfig) {
    persist(saved.copy(opds = saved.opds.filter { it.url != config.url } + config))
    _opds.value = saved.opds
  }

  @Synchronized
  fun saveKomga(config: KomgaConfig) {
    persist(saved.copy(komga = saved.komga.filter { it.url != config.url } + config))
    _komga.value = saved.komga
  }

  @Synchronized
  fun removeKomga(config: KomgaConfig) {
    persist(saved.copy(komga = saved.komga.filter { it.url != config.url }))
    _komga.value = saved.komga
  }

  @Synchronized
  fun removeOpds(config: OpdsConfig) {
    persist(saved.copy(opds = saved.opds.filter { it.url != config.url }))
    _opds.value = saved.opds
  }
}

/** Orders "Issue 2" before "Issue 10" by comparing digit runs as numbers. */
object NaturalOrder : Comparator<String> {
  private val chunk = Regex("\\d+|\\D+")

  override fun compare(a: String, b: String): Int {
    val x = chunk.findAll(a.lowercase()).map { it.value }.iterator()
    val y = chunk.findAll(b.lowercase()).map { it.value }.iterator()
    while (x.hasNext() && y.hasNext()) {
      val p = x.next()
      val q = y.next()
      val result =
        if (p[0].isDigit() && q[0].isDigit()) {
          val pn = p.trimStart('0')
          val qn = q.trimStart('0')
          if (pn.length != qn.length) pn.length - qn.length else pn.compareTo(qn)
        } else p.compareTo(q)
      if (result != 0) return result
    }
    return x.hasNext().compareTo(y.hasNext())
  }
}
