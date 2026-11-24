package dev.local.androidreader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.androidreader.ReaderApp
import dev.local.androidreader.source.CatalogEntry
import dev.local.androidreader.source.CatalogPage
import dev.local.androidreader.source.ComicSource
import dev.local.androidreader.source.KomgaConfig
import dev.local.androidreader.source.KomgaSource
import dev.local.androidreader.source.OpdsConfig
import dev.local.androidreader.source.OpdsSource
import dev.local.androidreader.source.ServerCheck
import dev.local.androidreader.source.RemoteFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class SourceInfo(val id: String, val name: String, val removable: Boolean)

data class EntryDetail(val entry: CatalogEntry, val files: List<RemoteFile>? = null, val error: String? = null)

data class BrowseUiState(
  val sources: List<SourceInfo> = emptyList(),
  val selected: Int = 0,
  val entries: List<CatalogEntry> = emptyList(),
  val nextLocation: String? = null,
  val loading: Boolean = false,
  val error: String? = null,
  val canGoUp: Boolean = false,
  val detail: EntryDetail? = null,
  val message: String? = null,
  /** Result of trying the server being added; null before a test and after any edit. */
  val serverCheck: ServerCheck? = null,
  /** Sign-in headers the selected source needs for its thumbnails. */
  val thumbnailHeaders: Map<String, String> = emptyMap(),
)

class BrowseViewModel(application: Application) : AndroidViewModel(application) {
  private val app = application as ReaderApp
  private var sources: List<ComicSource> = emptyList()
  private val parents = ArrayDeque<CatalogPage>()
  private var loadJob: Job? = null

  private val _state = MutableStateFlow(BrowseUiState())
  val state: StateFlow<BrowseUiState> = _state.asStateFlow()

  private val current: ComicSource?
    get() = sources.getOrNull(_state.value.selected)

  init {
    viewModelScope.launch {
      combine(app.library.opdsCatalogs, app.library.komgaServers) { _, _ -> }.collect {
        val selectedId = current?.id
        sources = app.sources()
        val index = sources.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        _state.update { s ->
          s.copy(
            sources = sources.map { SourceInfo(it.id, it.name, it.id.startsWith("opds:") || it.id.startsWith("komga:")) },
            selected = index,
            thumbnailHeaders = sources[index].requestHeaders,
          )
        }
        if (selectedId == null || sources[index].id != selectedId) loadRoot()
      }
    }
  }

  fun selectSource(index: Int) {
    if (index == _state.value.selected) return
    _state.update { it.copy(selected = index, thumbnailHeaders = sources.getOrNull(index)?.requestHeaders.orEmpty()) }
    loadRoot()
  }

  fun loadRoot() {
    parents.clear()
    load(replace = true) { it.browse(null) }
  }

  fun search(query: String) {
    if (query.isBlank()) return loadRoot()
    parents.clear()
    load(replace = true) { it.search(query) }
  }

  fun loadMore() {
    val next = _state.value.nextLocation ?: return
    if (_state.value.loading) return
    load(replace = false) { it.browse(next) }
  }

  fun open(entry: CatalogEntry) {
    val source = current ?: return
    if (entry.isFolder) {
      parents.addLast(CatalogPage(_state.value.entries, _state.value.nextLocation))
      load(replace = true) { it.browse(entry.location) }
      return
    }
    _state.update { it.copy(detail = EntryDetail(entry, entry.files.ifEmpty { null })) }
    if (entry.files.isNotEmpty()) return
    viewModelScope.launch {
      val detail =
        try {
          val files = source.files(entry)
          EntryDetail(entry, files, if (files.isEmpty()) "No comic files in this item" else null)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          EntryDetail(entry, emptyList(), e.message ?: "Could not load files")
        }
      _state.update { if (it.detail?.entry?.id == entry.id) it.copy(detail = detail) else it }
    }
  }

  /** Returns false when already at the top of the catalogue. */
  fun goUp(): Boolean {
    val parent = parents.removeLastOrNull() ?: return false
    loadJob?.cancel()
    _state.update {
      it.copy(entries = parent.entries, nextLocation = parent.nextLocation, loading = false, error = null, canGoUp = parents.isNotEmpty())
    }
    return true
  }

  fun dismissDetail() = _state.update { it.copy(detail = null) }

  fun download(entry: CatalogEntry, file: RemoteFile) {
    app.downloads.enqueue(title = file.name.substringBeforeLast('.'), url = file.url, fileName = file.name, sourceId = entry.sourceId)
    _state.update { it.copy(detail = null, message = "Queued ${file.name}") }
  }

  fun messageShown() = _state.update { it.copy(message = null) }

  private var checkJob: Job? = null

  fun testOpds(config: OpdsConfig) = test(OpdsSource(app.httpClient, config), "an OPDS catalogue")

  fun testKomga(config: KomgaConfig) = test(KomgaSource(app.httpClient, config), "a Komga server")

  private fun test(source: ComicSource, kind: String) {
    checkJob?.cancel()
    _state.update { it.copy(serverCheck = ServerCheck.Running) }
    checkJob =
      viewModelScope.launch {
        val result = ServerCheck.run(source, kind)
        _state.update { it.copy(serverCheck = result) }
      }
  }

  /** Called when the details change or the dialog closes: an old result no longer applies. */
  fun clearServerCheck() {
    checkJob?.cancel()
    _state.update { if (it.serverCheck == null) it else it.copy(serverCheck = null) }
  }

  fun addOpds(config: OpdsConfig) {
    app.library.saveOpds(config)
    select(config.id)
  }

  fun addKomga(config: KomgaConfig) {
    app.library.saveKomga(config)
    select(config.id)
  }

  private fun select(id: String) {
    // The saved list is observed on the main thread, so the new source may not be listed yet.
    sources = app.sources()
    val index = sources.indexOfFirst { it.id == id }
    if (index < 0) return
    _state.update { s ->
      s.copy(
        sources = sources.map { SourceInfo(it.id, it.name, it.id.startsWith("opds:") || it.id.startsWith("komga:")) },
        selected = index,
        thumbnailHeaders = sources[index].requestHeaders,
      )
    }
    loadRoot()
  }

  fun removeSelectedCatalog() {
    val id = current?.id ?: return
    app.library.opdsCatalogs.value.firstOrNull { it.id == id }?.let(app.library::removeOpds)
    app.library.komgaServers.value.firstOrNull { it.id == id }?.let(app.library::removeKomga)
  }

  private fun load(replace: Boolean, request: suspend (ComicSource) -> CatalogPage) {
    val source = current ?: return
    loadJob?.cancel()
    _state.update {
      it.copy(loading = true, error = null, entries = if (replace) emptyList() else it.entries, canGoUp = parents.isNotEmpty())
    }
    loadJob =
      viewModelScope.launch {
        try {
          val page = request(source)
          _state.update {
            it.copy(
              entries = if (replace) page.entries else it.entries + page.entries,
              nextLocation = page.nextLocation,
              loading = false,
            )
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          _state.update { it.copy(loading = false, nextLocation = null, error = e.message ?: "Could not load catalogue") }
        }
      }
  }
}
