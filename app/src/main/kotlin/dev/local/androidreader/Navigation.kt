package dev.local.androidreader

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.local.androidreader.ui.HomeScreen
import dev.local.androidreader.ui.ReaderScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Home)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Home> { HomeScreen(onOpenComic = { backStack.add(Reader(it.file.path)) }) }
        entry<Reader> { key -> ReaderScreen(path = key.path, onBack = { backStack.removeLastOrNull() }) }
      },
  )
}
