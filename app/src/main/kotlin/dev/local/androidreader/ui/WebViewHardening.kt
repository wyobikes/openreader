package dev.local.androidreader.ui

import android.webkit.WebSettings

/**
 * Stops web content in this view from reaching anything on the device: no `file:` addresses, no
 * other apps' content providers, and no mixing of plain-http content into https pages. Android's
 * defaults for these differ between versions, so every one is set explicitly.
 */
// Two of these setters are deprecated because the access they control is discouraged; turning it
// off is exactly the recommended use, and they still take effect on the Android versions we support.
@Suppress("DEPRECATION")
fun WebSettings.denyLocalAccess() {
  allowFileAccess = false
  allowContentAccess = false
  allowFileAccessFromFileURLs = false
  allowUniversalAccessFromFileURLs = false
  mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
}
