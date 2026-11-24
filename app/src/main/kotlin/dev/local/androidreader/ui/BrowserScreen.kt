package dev.local.androidreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.local.androidreader.browser.BrowserEngineView
import dev.local.androidreader.browser.BrowserState
import dev.local.androidreader.browser.DownloadRequest
import dev.local.androidreader.browser.ENGINE_LABEL
import dev.local.androidreader.ui.theme.ReaderTheme

/** Address bar and navigation buttons around the engine view of the current build variant. */
@Composable
fun BrowserContent(state: BrowserState, onDownload: (DownloadRequest) -> Unit, modifier: Modifier = Modifier) {
  val focusManager = LocalFocusManager.current
  var address by remember { mutableStateOf(state.url) }
  var editing by remember { mutableStateOf(false) }
  // Follow the page as it navigates, but never overwrite what is being typed.
  LaunchedEffect(state.url) { if (!editing) address = state.url }

  Column(modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = { state.controls?.back() }, enabled = state.canGoBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous page")
      }
      IconButton(onClick = { state.controls?.forward() }, enabled = state.canGoForward) {
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next page")
      }
      OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        modifier =
          Modifier.weight(1f).onFocusChanged {
            editing = it.isFocused
            if (!it.isFocused) address = state.url
          },
        singleLine = true,
        placeholder = { Text("Search or enter address") },
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions =
          KeyboardActions(
            onGo = {
              val target = BrowserState.toUrl(address)
              state.url = target
              state.controls?.load(target)
              focusManager.clearFocus()
            }
          ),
      )
      if (state.loading) {
        IconButton(onClick = { state.controls?.stop() }) { Icon(Icons.Default.Close, contentDescription = "Stop loading") }
      } else {
        IconButton(onClick = { state.controls?.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "Reload page") }
      }
    }

    Box(Modifier.fillMaxWidth().height(4.dp)) {
      if (state.loading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
    }

    Text(
      state.blocked?.let { "$ENGINE_LABEL · $it blocked on this page" } ?: ENGINE_LABEL,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
    )

    if (LocalInspectionMode.current) {
      // Design-time previews cannot host a real browser engine.
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Web page") }
    } else {
      BrowserEngineView(state, onDownload, Modifier.fillMaxWidth().weight(1f))
    }
  }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 480)
@Composable
fun BrowserContentPreview() {
  val state =
    remember {
      BrowserState("https://archive.org/details/comics").apply {
        loading = true
        progress = 60
        canGoBack = true
        blocked = 14
      }
    }
  ReaderTheme { BrowserContent(state, onDownload = {}) }
}
