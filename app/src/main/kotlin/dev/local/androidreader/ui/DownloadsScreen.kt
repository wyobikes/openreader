package dev.local.androidreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.local.androidreader.download.DownloadItem
import dev.local.androidreader.ui.theme.ReaderTheme
import java.util.UUID

/** Returns the trimmed link when it looks like a web address, else null. */
fun validDownloadUrl(text: String): String? =
  text.trim().takeIf { (it.startsWith("https://") || it.startsWith("http://")) && it.length > 10 && ' ' !in it }

@Composable
fun DownloadsContent(
  items: List<DownloadItem>,
  onAddUrl: (String) -> Unit,
  onCancel: (UUID) -> Unit,
  onClearFinished: () -> Unit,
  modifier: Modifier = Modifier,
  convertPdfToEpub: Boolean = false,
  onConvertPdfToEpubChange: (Boolean) -> Unit = {},
) {
  var url by rememberSaveable { mutableStateOf("") }
  val valid = validDownloadUrl(url)
  val submit = {
    valid?.let {
      onAddUrl(it)
      url = ""
    }
    Unit
  }

  Column(modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        modifier = Modifier.weight(1f).padding(end = 8.dp),
        singleLine = true,
        placeholder = { Text("Link to a comic, PDF or EPUB") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { submit() }),
      )
      Button(onClick = submit, enabled = valid != null) { Text("Add") }
    }

    Row(
      Modifier.fillMaxWidth()
        .toggleable(convertPdfToEpub, role = Role.Switch, onValueChange = onConvertPdfToEpubChange)
        .padding(horizontal = 16.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f).padding(end = 12.dp)) {
        Text("Convert PDFs to EPUB", style = MaterialTheme.typography.bodyLarge)
        Text(
          "Downloaded PDFs also get a text-only EPUB copy that reflows to fit the screen.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      // The whole row is the switch; this one only shows the state.
      Switch(checked = convertPdfToEpub, onCheckedChange = null)
    }

    if (items.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
          "No downloads. Paste a link above, or share one to Android Reader from your browser.",
          textAlign = TextAlign.Center,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      return@Column
    }

    if (items.any { it.status >= DownloadItem.Status.DONE }) {
      TextButton(onClick = onClearFinished, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Clear finished") }
    }

    LazyColumn(Modifier.fillMaxSize()) {
      items(items, key = { it.id }) { item ->
        val active = item.status == DownloadItem.Status.RUNNING || item.status == DownloadItem.Status.QUEUED
        ListItem(
          supportingContent = {
            Column {
              Text(
                when (item.status) {
                  DownloadItem.Status.QUEUED -> "Waiting"
                  DownloadItem.Status.RUNNING ->
                    if (item.bytesTotal > 0) "${formatSize(item.bytesDone)} of ${formatSize(item.bytesTotal)}"
                    else if (item.bytesDone > 0) formatSize(item.bytesDone) else "Starting"
                  DownloadItem.Status.DONE -> item.note ?: "Added to library"
                  DownloadItem.Status.FAILED -> "Failed: ${item.error ?: "unknown error"}"
                  DownloadItem.Status.CANCELLED -> "Cancelled"
                },
                color =
                  if (item.status == DownloadItem.Status.FAILED) MaterialTheme.colorScheme.error
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              )
              if (item.status == DownloadItem.Status.RUNNING) {
                val fraction = item.fraction
                if (fraction != null) {
                  LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                } else {
                  LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
              }
            }
          },
          trailingContent =
            if (active) ({ IconButton(onClick = { onCancel(item.id) }) { Icon(Icons.Default.Close, contentDescription = "Cancel download") } })
            else null,
        ) {
          Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 560)
@Composable
fun DownloadsContentPreview() {
  val items =
    listOf(
      DownloadItem(UUID(0, 1), "Planet Stories 01", DownloadItem.Status.RUNNING, 12_400_000, 28_371_918),
      DownloadItem(UUID(0, 2), "Planet Stories 02", DownloadItem.Status.QUEUED),
      DownloadItem(UUID(0, 3), "Amazing Mystery Funnies v2 07", DownloadItem.Status.DONE),
      DownloadItem(UUID(0, 4), "broken-link", DownloadItem.Status.FAILED, error = "HTTP 404 from example.org"),
    )
  ReaderTheme { DownloadsContent(items, {}, {}, {}) }
}
