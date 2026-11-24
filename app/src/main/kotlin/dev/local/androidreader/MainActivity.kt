package dev.local.androidreader

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.androidreader.library.ThemeMode
import dev.local.androidreader.ui.theme.ReaderTheme
import dev.local.androidreader.ui.validDownloadUrl

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    // Only handle a share on first launch, not again when the screen rotates.
    if (savedInstanceState == null) handleShare(intent)
    val library = (application as ReaderApp).library
    setContent {
      val mode by library.themeMode.collectAsStateWithLifecycle()
      val dark =
        when (mode) {
          ThemeMode.SYSTEM -> isSystemInDarkTheme()
          ThemeMode.LIGHT -> false
          ThemeMode.DARK -> true
        }
      // Keep the status and navigation bar icons readable against the chosen theme.
      DisposableEffect(dark) {
        enableEdgeToEdge(
          statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
          navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
        onDispose {}
      }
      ReaderTheme(darkTheme = dark) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleShare(intent)
  }

  private companion object {
    // The same translucent bars enableEdgeToEdge uses by default.
    val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
    val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
  }

  /** A link shared from another app is queued as a download. */
  private fun handleShare(intent: Intent?) {
    if (intent?.action != Intent.ACTION_SEND) return
    val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    // Shared text is often "Page title https://link"; take the link.
    val url = text.split(Regex("\\s+")).firstNotNullOfOrNull(::validDownloadUrl)
    if (url == null) {
      Toast.makeText(this, "No link found in the shared text", Toast.LENGTH_LONG).show()
      return
    }
    val app = application as ReaderApp
    app.downloads.enqueue(title = url.substringAfterLast('/').substringBefore('?').ifEmpty { url }, url = url)
    Toast.makeText(this, "Added to Android Reader downloads", Toast.LENGTH_SHORT).show()
  }
}
