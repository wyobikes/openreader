package dev.local.androidreader.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.local.androidreader.ReaderApp
import dev.local.androidreader.browser.BrowserState
import dev.local.androidreader.download.Downloader
import dev.local.androidreader.library.Comic
import dev.local.androidreader.library.Importer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Tab(val label: String) {
  LIBRARY("Library"),
  BROWSE("Catalogues"),
  WEB("Web"),
  DOWNLOADS("Downloads"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenComic: (Comic) -> Unit, browseViewModel: BrowseViewModel = viewModel()) {
  val context = LocalContext.current
  val app = context.applicationContext as ReaderApp
  var tabIndex by rememberSaveable { mutableIntStateOf(0) }
  val tab = Tab.entries[tabIndex]
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()

  val comics by app.library.comics.collectAsStateWithLifecycle()
  val downloads by app.downloads.items.collectAsStateWithLifecycle(emptyList())
  val browse by browseViewModel.state.collectAsStateWithLifecycle()

  // Downloads finish in the background, so pick up new files whenever the list changes.
  LaunchedEffect(downloads) { withContext(Dispatchers.IO) { app.library.refresh() } }

  LaunchedEffect(browse.message) {
    browse.message?.let {
      snackbar.showSnackbar(it)
      browseViewModel.messageShown()
    }
  }

  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  val askForNotifications = {
    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      if (uris.isEmpty()) return@rememberLauncherForActivityResult
      scope.launch {
        val failures = withContext(Dispatchers.IO) { uris.mapNotNull { importPicked(context, app, it) } }
        app.library.refresh()
        snackbar.showSnackbar(
          if (failures.isEmpty()) "Imported ${uris.size} file${if (uris.size == 1) "" else "s"}" else failures.first()
        )
      }
    }

  BackHandler(enabled = tab == Tab.BROWSE && browse.canGoUp) { browseViewModel.goUp() }

  // Leaving the Web tab discards the engine view, so remember the address to return to.
  var lastWebUrl by rememberSaveable { mutableStateOf(BrowserState.HOME_URL) }
  val browser = remember { BrowserState(lastWebUrl) }
  LaunchedEffect(browser.url) { lastWebUrl = browser.url }
  BackHandler(enabled = tab == Tab.WEB && browser.canGoBack) { browser.controls?.back() }

  Scaffold(
    // The Web tab has its own address bar and needs the vertical space.
    topBar = { if (tab != Tab.WEB) TopAppBar(title = { Text(if (tab == Tab.LIBRARY) "Android Reader" else tab.label) }) },
    snackbarHost = { SnackbarHost(snackbar) },
    floatingActionButton = {
      if (tab == Tab.LIBRARY) {
        FloatingActionButton(onClick = { picker.launch(arrayOf("*/*")) }) {
          Icon(Icons.Default.Add, contentDescription = "Import comics from this device")
        }
      }
    },
    bottomBar = {
      NavigationBar {
        Tab.entries.forEachIndexed { index, item ->
          NavigationBarItem(
            selected = index == tabIndex,
            onClick = { tabIndex = index },
            label = { Text(item.label) },
            icon = {
              Icon(
                when (item) {
                  Tab.LIBRARY -> Icons.Default.Home
                  Tab.BROWSE -> Icons.AutoMirrored.Filled.List
                  Tab.WEB -> Icons.Default.Search
                  Tab.DOWNLOADS -> Icons.Default.KeyboardArrowDown
                },
                contentDescription = null,
              )
            },
          )
        }
      }
    },
  ) { padding ->
    val modifier = Modifier.padding(padding)
    when (tab) {
      Tab.LIBRARY ->
        LibraryContent(comics, onOpen = onOpenComic, onDelete = { app.library.delete(it.file) }, modifier = modifier)
      Tab.BROWSE ->
        BrowseContent(
          state = browse,
          onSelectSource = browseViewModel::selectSource,
          onSearch = browseViewModel::search,
          onOpen = browseViewModel::open,
          onLoadMore = browseViewModel::loadMore,
          onGoUp = { browseViewModel.goUp() },
          onDownload = { entry, file ->
            askForNotifications()
            browseViewModel.download(entry, file)
          },
          onDismissDetail = browseViewModel::dismissDetail,
          onAddOpds = browseViewModel::addOpds,
          onAddKomga = browseViewModel::addKomga,
          onRemoveCatalog = browseViewModel::removeSelectedCatalog,
          modifier = modifier,
        )
      Tab.WEB ->
        BrowserContent(
          state = browser,
          onDownload = { request ->
            askForNotifications()
            val name = request.fileName ?: Downloader.fileNameFromUrl(request.url)
            app.downloads.enqueue(title = name ?: request.url, url = request.url, fileName = name, headers = request.headers)
            scope.launch { snackbar.showSnackbar("Added to downloads: ${name ?: "file"}") }
          },
          modifier = modifier.statusBarsPadding(),
        )
      Tab.DOWNLOADS ->
        DownloadsContent(
          items = downloads,
          onAddUrl = { url ->
            askForNotifications()
            app.downloads.enqueue(title = url.substringAfterLast('/').substringBefore('?').ifEmpty { url }, url = url)
          },
          onCancel = app.downloads::cancel,
          onClearFinished = app.downloads::clearFinished,
          modifier = modifier,
        )
    }
  }
}

/** Copies a picked document into the library. Returns an error message, or null on success. */
private fun importPicked(context: Context, app: ReaderApp, uri: Uri): String? {
  val name =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "comic"
  val temp = File.createTempFile("import", ".tmp", context.cacheDir)
  return try {
    context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
      ?: return "Could not open $name"
    Importer.import(temp, app.library.dir, name)
    null
  } catch (e: Exception) {
    e.message ?: "Could not import $name"
  } finally {
    temp.delete()
  }
}
