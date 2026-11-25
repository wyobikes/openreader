package dev.local.androidreader.epub

import java.net.URLDecoder

/** Helpers for showing a book's chapters in a web view. No Android dependencies. */
object BookHtml {
  /** A made-up address the book's files are served from; `.invalid` can never be a real site. */
  const val ORIGIN = "https://book.invalid/"

  /** The address of a file inside the book. */
  fun url(path: String): String =
    ORIGIN + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

  /** The file inside the book that an address refers to, or null for any other address. */
  fun path(url: String): String? {
    if (!url.startsWith(ORIGIN)) return null
    val raw = url.removePrefix(ORIGIN).substringBefore('#').substringBefore('?')
    // Decode once only: a file name may itself contain "#" or "%".
    return runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull()?.let(EpubBook::normalize)
  }

  /**
   * Reading styles laid over the book's own: comfortable margins and line height, pictures that
   * fit the screen, and for dark mode, light text on a dark page whatever the book specifies.
   */
  fun css(dark: Boolean, topInset: Int = 0, bottomInset: Int = 0): String {
    // The page fills the whole screen in full-screen reading, so the text itself keeps clear of
    // the camera cutout at the top and the gesture area at the bottom. Insets are in CSS pixels.
    val top = "calc(1em + ${topInset.coerceAtLeast(0)}px)"
    val bottom = "calc(4em + ${bottomInset.coerceAtLeast(0)}px)"
    val base =
      "html{-webkit-text-size-adjust:100%}" +
        "body{margin:0 auto!important;padding:$top 1.2em $bottom!important;max-width:42em;line-height:1.55;overflow-wrap:break-word}" +
        "img,svg,video{max-width:100%!important;height:auto!important}" +
        "pre{white-space:pre-wrap}"
    return if (dark) {
      base +
        "html,body{background:#121212!important}" +
        "body,body *{color:#e3e3e3!important;background-color:transparent!important;border-color:#555!important}" +
        "a,a *{color:#8ab4f8!important}"
    } else {
      base + "html,body{background:#ffffff}body{color:#1b1b1b}"
    }
  }

  /** Adds a style block to a chapter, inside its head when it has one. */
  fun inject(document: String, css: String): String {
    val style = "<style>$css</style>"
    val headEnd = Regex("</head\\s*>", RegexOption.IGNORE_CASE).find(document)
    if (headEnd != null) return document.substring(0, headEnd.range.first) + style + document.substring(headEnd.range.first)
    val bodyStart = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(document)
    if (bodyStart != null) return document.substring(0, bodyStart.range.last + 1) + style + document.substring(bodyStart.range.last + 1)
    return style + document
  }

  fun isChapterType(mediaType: String): Boolean = mediaType == "application/xhtml+xml" || mediaType == "text/html"
}
