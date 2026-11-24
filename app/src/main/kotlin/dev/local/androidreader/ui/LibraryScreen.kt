package dev.local.androidreader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.local.androidreader.library.Comic
import dev.local.androidreader.library.ComicState
import dev.local.androidreader.ui.theme.ReaderTheme
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryContent(
  comics: List<Comic>,
  onOpen: (Comic) -> Unit,
  onDelete: (Comic) -> Unit,
  modifier: Modifier = Modifier,
  onConvertToEpub: (Comic) -> Unit = {},
) {
  var pendingDelete by remember { mutableStateOf<Comic?>(null) }

  if (comics.isEmpty()) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
      Text(
        "Nothing here yet.\nFind comics and books in Catalogues, paste a link in Downloads, or tap + to import files from this device.",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  } else {
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 108.dp),
      modifier = modifier.fillMaxSize(),
      contentPadding = PaddingValues(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      items(comics, key = { it.file.path }) { comic ->
        Column(
          Modifier.clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = { onOpen(comic) }, onLongClick = { pendingDelete = comic })
        ) {
          CoverImage(
            comic.file,
            comic.title,
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
          )
          if (comic.isStarted && comic.state.pageCount > 1) {
            LinearProgressIndicator(
              progress = { comic.state.lastPage / (comic.state.pageCount - 1).toFloat() },
              modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
              drawStopIndicator = {},
            )
          }
          Text(
            comic.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
          )
          Text(
            when {
              comic.isFinished -> "Read"
              comic.isStarted && comic.isBook -> "Section ${comic.state.lastPage + 1} of ${comic.state.pageCount}"
              comic.isStarted -> "Page ${comic.state.lastPage + 1} of ${comic.state.pageCount}"
              else -> "Unread"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }

  // Long press: what can be done with this item.
  pendingDelete?.let { comic ->
    val isPdf = comic.file.extension.equals("pdf", ignoreCase = true)
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text(comic.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
      text = {
        Column {
          if (isPdf) {
            TextButton(
              onClick = {
                onConvertToEpub(comic)
                pendingDelete = null
              }
            ) {
              Text("Make an EPUB copy")
            }
            Text(
              "Text only: pictures and page layout are not carried over. The PDF is kept.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            )
          }
          TextButton(
            onClick = {
              onDelete(comic)
              pendingDelete = null
            }
          ) {
            Text("Delete from this device", color = MaterialTheme.colorScheme.error)
          }
        }
      },
      confirmButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
    )
  }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 640)
@Composable
fun LibraryContentPreview() {
  val comics =
    listOf(
      Comic(File("/x/Planet Stories 01.cbz"), ComicState(lastPage = 12, pageCount = 36, lastOpened = 1)),
      Comic(File("/x/Planet Stories 02.cbz"), ComicState()),
      Comic(File("/x/Amazing Mystery Funnies v2 07.cbz"), ComicState(lastPage = 51, pageCount = 52, lastOpened = 1)),
      Comic(File("/x/Wonderworld Comics 03.pdf"), ComicState()),
    )
  ReaderTheme { LibraryContent(comics, onOpen = {}, onDelete = {}) }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 400)
@Composable
fun LibraryEmptyPreview() {
  ReaderTheme { LibraryContent(emptyList(), onOpen = {}, onDelete = {}) }
}
