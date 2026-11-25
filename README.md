# Open Reader

**An open source comic and book reader for Android that also finds and fetches what you read.**

Open Reader reads comics (CBZ, CBR) and books (EPUB, PDF), connects to your own library servers,
and has a built-in browser and downloader, so finding something, downloading it and reading it all
happen in one app. No accounts, no ads, no tracking.

## What it does

- **Reads comics.** CBZ and CBR, full screen, with pinch and double-tap zoom, tap-to-turn, and
  right-to-left mode for manga.
- **Reads books.** EPUB as flowing text, with adjustable text size, a contents list, and light
  and dark pages. PDFs open page by page.
- **Keeps a tidy library.** Everything you add lands on one of two shelves, **Comics** or
  **Books**, and each item remembers where you stopped.
- **Connects to your servers.**
  - **Komga** has built-in support: browse, search, download, or read online page by page
    without downloading.
  - **OPDS** covers most other libraries: Calibre-Web, Calibre-Web-Automated, Kavita and
    anything else that offers an OPDS catalogue.
  - The **Internet Archive** is included as a source of openly licensed comics.
- **Browses and downloads.** A built-in web browser with ad blocking hands any download
  straight to the built-in downloader, which queues it, resumes it if interrupted, and files it
  in the library.
- **Converts PDFs to EPUB**, so text-based PDFs reflow to fit your screen.
- **Looks after your eyes.** Follow the system theme, or choose light or dark.

## How the library is organised

Files are sorted by type when they arrive:

| File type | Shelf |
|---|---|
| `.cbz`, `.cbr` | Comics |
| `.epub`, `.pdf` | Books |

CBR files are converted to CBZ on the way in. A zip that bundles several comics or books is
unpacked into its parts. Files are recognised by their contents, so a wrong or missing file
extension does not matter.

If something lands on the wrong shelf (a comic saved as a PDF, say), **press and hold it and
choose "Move to Comics" or "Move to Books"**. The move is remembered.

## Converting PDF to EPUB

There are two ways:

- **One at a time:** press and hold a PDF in the library and choose **Convert to EPUB**.
- **Automatically:** turn on **Convert PDFs to EPUB** in the Downloads tab, and every PDF you
  download gets an EPUB copy.

The original PDF is always kept. Only the text is carried over; pictures, tables and page layout
are not.

> **Scanned PDFs cannot be converted.** A scan is a set of pictures of pages with no text behind
> them, so there is nothing to convert. Open Reader will tell you when that is the case.

## The built-in browser

There is nothing special about it, and that is deliberate. It does two things well:

- **Blocks ads.** Requests to known advertising and tracking hosts are dropped, and pop-up
  windows are refused.
- **One-tap downloads.** Tap a download link and the file goes to the downloader, then into the
  right shelf of your library. No file manager, no moving things around afterwards.

You can also paste a link into the Downloads tab, or share a link to Open Reader from another
browser.

## Connecting a server

Open the **Catalogues** tab and tap **+ Add server**. Every server has to pass a connection test
before it can be added, and the test explains what is wrong if it fails.

- **Komga:** enter the server address (for example `http://192.168.1.10:25600`) and either an
  API key or your email and password.
- **OPDS:** enter the catalogue address. For Calibre-Web and Calibre-Web-Automated this is the
  address you normally use with `/opds` on the end, for example
  `http://192.168.1.10:8083/opds`, plus your usual username and password.

Notes:

- The phone has to be able to reach the server, so use the server's network address, not
  `localhost`.
- Your account on the server needs permission to download.
- Books in formats Open Reader cannot open (MOBI and AZW3, for example) are not listed.
- Calibre's own content server is reachable over OPDS only when it does not ask for a password.
  With a password it uses a sign-in method this app does not support; Calibre-Web is the
  simpler route.

## Reading

**Comics.** Tap the right or left side of the page to turn it, and the middle to show the
controls. Pinch or double-tap to zoom. The reader is full screen, uses the whole display
including the camera cutout, and keeps the screen on.

**Books.** Tap the right or left side to move a screenful forward or back, and the middle for
the controls: contents, text size, and previous or next section.

## Privacy

- No accounts, analytics, advertising or tracking of any kind.
- Server sign-in details are stored on the device without encryption, in the app's private
  storage. For that reason the app is excluded from cloud backup and device-to-device transfer.
- Plain `http://` connections are allowed, so that home servers without a certificate work.
- The ad-blocking list is downloaded from its public source about once a week.

## Building it yourself

You need a JDK (17 or newer) and the Android SDK. No Android Studio is required.

```bash
./gradlew assembleWebviewDebug
```

The APK is written to `app/build/outputs/apk/webview/debug/`.

The project has two build variants that differ only in the browser engine:

| Variant | Browser | Status |
|---|---|---|
| `webview` | The system WebView with a host blocklist | The main build |
| `ublock` | Firefox's engine with uBlock Origin | Experimental and very large (over 200 MB); not currently maintained |

Run the tests with `./gradlew testWebviewDebugUnitTest`, and the Android checks with
`./gradlew lintWebviewDebug`.

## Status

Open Reader is a personal project, built for personal use and shared in case it is useful to
someone else. It comes with no warranty and no promise of support.

## Licence

Open Reader is free software, licensed under the **GNU General Public License, version 3 or
(at your option) any later version**. See [LICENSE](LICENSE).

It builds on other people's work:

| Component | Used for | Licence |
|---|---|---|
| AndroidX and Jetpack Compose | The app framework and interface | Apache 2.0 |
| OkHttp | Network requests | Apache 2.0 |
| kotlinx.serialization | Reading and saving data | Apache 2.0 |
| pdfbox-android | Extracting text from PDFs | Apache 2.0 |
| junrar | Reading CBR (RAR) files | UnRAR licence |
| Steven Black's hosts list | The ad-blocking list, downloaded at run time | MIT |
| GeckoView and uBlock Origin | The `ublock` variant only | MPL 2.0 and GPL 3.0 |

One thing to know if you redistribute builds: junrar's UnRAR licence carries a restriction that
the GPL does not (its code may not be used to recreate the RAR compression method). If that
matters for your use, build without CBR support.
