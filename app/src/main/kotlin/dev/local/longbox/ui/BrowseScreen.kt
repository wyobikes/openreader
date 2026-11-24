package dev.local.longbox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.local.longbox.source.CatalogEntry
import dev.local.longbox.source.ComicFormat
import dev.local.longbox.source.OpdsConfig
import dev.local.longbox.source.RemoteFile
import dev.local.longbox.ui.theme.LongboxTheme

@Composable
fun BrowseContent(
  state: BrowseUiState,
  onSelectSource: (Int) -> Unit,
  onSearch: (String) -> Unit,
  onOpen: (CatalogEntry) -> Unit,
  onLoadMore: () -> Unit,
  onGoUp: () -> Unit,
  onDownload: (CatalogEntry, RemoteFile) -> Unit,
  onDismissDetail: () -> Unit,
  onAddCatalog: (OpdsConfig) -> Unit,
  onRemoveCatalog: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var query by rememberSaveable { mutableStateOf("") }
  var showAddCatalog by rememberSaveable { mutableStateOf(false) }

  Column(modifier.fillMaxSize()) {
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      state.sources.forEachIndexed { index, source ->
        FilterChip(selected = index == state.selected, onClick = { onSelectSource(index) }, label = { Text(source.name) })
      }
      AssistChip(onClick = { showAddCatalog = true }, label = { Text("+ OPDS catalogue") })
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      if (state.canGoUp) {
        IconButton(onClick = onGoUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up one level") }
      }
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.weight(1f),
        singleLine = true,
        placeholder = { Text("Search this source") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
      )
    }

    if (state.sources.getOrNull(state.selected)?.removable == true && !state.canGoUp) {
      TextButton(onClick = onRemoveCatalog, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Remove this catalogue") }
    }

    LazyColumn(Modifier.fillMaxSize()) {
      items(state.entries, key = { it.id }) { entry ->
        ListItem(
          headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
          supportingContent = entry.subtitle?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
          leadingContent = {
            RemoteThumbnail(entry.thumbnailUrl, entry.title, Modifier.size(width = 48.dp, height = 68.dp).clip(RoundedCornerShape(4.dp)))
          },
          trailingContent = if (entry.isFolder) ({ Text("›", style = MaterialTheme.typography.titleLarge) }) else null,
          modifier = Modifier.clickable { onOpen(entry) },
        )
      }
      item(key = "footer") {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
          when {
            state.loading -> CircularProgressIndicator()
            state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
            state.nextLocation != null -> {
              // Reaching the footer means the list was scrolled to the end.
              LaunchedEffect(state.nextLocation) { onLoadMore() }
              CircularProgressIndicator()
            }
            state.entries.isEmpty() -> Text("Nothing found", color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    }
  }

  state.detail?.let { detail ->
    AlertDialog(
      onDismissRequest = onDismissDetail,
      title = { Text(detail.entry.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
      text = {
        Column {
          when {
            detail.files == null -> CircularProgressIndicator()
            detail.error != null -> Text(detail.error, color = MaterialTheme.colorScheme.error)
            else ->
              LazyColumn {
                items(detail.files) { file ->
                  ListItem(
                    headlineContent = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                      Text(listOfNotNull(file.format.name, file.sizeBytes?.let(::formatSize)).joinToString(" · "))
                    },
                    modifier = Modifier.clickable { onDownload(detail.entry, file) },
                  )
                }
              }
          }
        }
      },
      confirmButton = { TextButton(onClick = onDismissDetail) { Text("Close") } },
    )
  }

  if (showAddCatalog) {
    AddCatalogDialog(
      onDismiss = { showAddCatalog = false },
      onAdd = {
        showAddCatalog = false
        onAddCatalog(it)
      },
    )
  }
}

@Composable
private fun AddCatalogDialog(onDismiss: () -> Unit, onAdd: (OpdsConfig) -> Unit) {
  var name by rememberSaveable { mutableStateOf("") }
  var url by rememberSaveable { mutableStateOf("") }
  var username by rememberSaveable { mutableStateOf("") }
  var password by rememberSaveable { mutableStateOf("") }
  val trimmedUrl = url.trim()
  val valid = trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add OPDS catalogue") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
        OutlinedTextField(
          url,
          { url = it },
          label = { Text("Catalogue URL") },
          placeholder = { Text("https://server/opds/v1.2/catalog") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(username, { username = it }, label = { Text("Username (optional)") }, singleLine = true)
        OutlinedTextField(
          password,
          { password = it },
          label = { Text("Password (optional)") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
      }
    },
    confirmButton = {
      TextButton(
        enabled = valid,
        onClick = { onAdd(OpdsConfig(name.trim().ifEmpty { trimmedUrl.substringAfter("://").substringBefore('/') }, trimmedUrl, username.trim(), password)) },
      ) {
        Text("Add")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Preview(showBackground = true, widthDp = 380, heightDp = 640)
@Composable
fun BrowseContentPreview() {
  val entries =
    listOf(
      CatalogEntry("ia", "a", "Planet Stories v1 #1", "Fiction House · 1939", null),
      CatalogEntry("ia", "b", "Amazing Mystery Funnies", "Centaur · 1938", null),
      CatalogEntry("ia", "c", "Golden age collection", null, null, location = "x"),
    )
  LongboxTheme {
    BrowseContent(
      BrowseUiState(
        sources = listOf(SourceInfo("ia", "Internet Archive", false), SourceInfo("opds:x", "Home server", true)),
        entries = entries,
      ),
      {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {},
    )
  }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 640)
@Composable
fun BrowseDetailPreview() {
  val entry = CatalogEntry("ia", "a", "Planet Stories v1 #1")
  LongboxTheme {
    BrowseContent(
      BrowseUiState(
        sources = listOf(SourceInfo("ia", "Internet Archive", false)),
        entries = listOf(entry),
        detail =
          EntryDetail(
            entry,
            listOf(
              RemoteFile("Planet Stories 01.cbz", "https://x/a.cbz", ComicFormat.CBZ, 28_371_918),
              RemoteFile("Planet Stories 01.pdf", "https://x/a.pdf", ComicFormat.PDF, 7_596_779),
            ),
          ),
      ),
      {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {},
    )
  }
}
