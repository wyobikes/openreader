package dev.local.androidreader

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.local.androidreader.ui.HomeScreen
import dev.local.androidreader.ui.ReaderScreen
import dev.local.androidreader.ui.ReaderTarget

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Home)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Home> {
          HomeScreen(
            onOpenComic = { backStack.add(Reader(it.file.path)) },
            onReadOnline = { entry -> entry.onlineBookId?.let { backStack.add(OnlineReader(entry.sourceId, it, entry.title)) } },
          )
        }
        entry<Reader> { key -> ReaderScreen(ReaderTarget.Local(key.path), onBack = { backStack.removeLastOrNull() }) }
        entry<OnlineReader> { key ->
          ReaderScreen(ReaderTarget.Online(key.sourceId, key.bookId, key.title), onBack = { backStack.removeLastOrNull() })
        }
      },
  )
}
