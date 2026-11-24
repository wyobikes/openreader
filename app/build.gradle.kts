import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

// Release signing details live in keystore.properties, which is not committed.
// Without that file the release build is left unsigned.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) } }

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// APKs come out as androidreader-<variant>-<build type>.apk.
base { archivesName = "androidreader" }

android {
  namespace = "dev.local.androidreader"
  compileSdk = 37
  defaultConfig {
    applicationId = "dev.local.androidreader"
    minSdk = 24
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"
  }

  signingConfigs {
    if (keystoreFile.exists()) {
      create("release") {
        storeFile = file(keystore.getProperty("storeFile"))
        storePassword = keystore.getProperty("storePassword")
        keyAlias = keystore.getProperty("keyAlias")
        keyPassword = keystore.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      signingConfig = signingConfigs.findByName("release")
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions {
    unitTests.all {
      // `-Plive` turns on tests that talk to real servers.
      it.systemProperty("live", providers.gradleProperty("live").isPresent.toString())
      it.testLogging.showStandardStreams = true
    }
  }
  // Two builds of the same app that differ only in the in-app browser engine.
  flavorDimensions += "browser"
  productFlavors {
    // System WebView with a host blocklist: small and simple.
    create("webview") {
      dimension = "browser"
      applicationIdSuffix = ".webview"
      resValue("string", "app_name", "Android Reader (WebView)")
    }
    // Firefox's engine (GeckoView) running the real uBlock Origin: much larger.
    create("ublock") {
      dimension = "browser"
      applicationIdSuffix = ".ublock"
      minSdk = 26
      resValue("string", "app_name", "Android Reader (uBlock)")
      ndk { abiFilters += "arm64-v8a" }
    }
  }

  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    resValues = true
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

/** Downloads a browser extension package, checks its SHA-256, and unpacks it as an app asset. */
abstract class FetchExtensionTask : DefaultTask() {
  @get:Input abstract val url: Property<String>
  @get:Input abstract val sha256: Property<String>
  @get:Input abstract val folder: Property<String>
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun fetch() {
    val bytes = URI(url.get()).toURL().openStream().use { it.readBytes() }
    val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    check(actual == sha256.get()) { "Checksum mismatch for ${url.get()}: expected ${sha256.get()}, got $actual" }

    val root = outputDir.get().asFile
    root.deleteRecursively()
    val target = root.resolve(folder.get()).canonicalFile
    ZipInputStream(bytes.inputStream()).use { zip ->
      generateSequence { zip.nextEntry }.forEach { entry ->
        val out = target.resolve(entry.name).canonicalFile
        check(out.startsWith(target)) { "Unsafe path in archive: ${entry.name}" }
        // Signature files only matter for a packed extension.
        if (entry.isDirectory || entry.name.startsWith("META-INF/")) return@forEach
        out.parentFile.mkdirs()
        out.outputStream().use { zip.copyTo(it) }
      }
    }
  }
}

// uBlock Origin (GPLv3), pinned to a release and verified before it is bundled.
val fetchUblock =
  tasks.register<FetchExtensionTask>("fetchUblockOrigin") {
    url = "https://github.com/gorhill/uBlock/releases/download/1.77.0/uBlock0_1.77.0.firefox.signed.xpi"
    sha256 = "894ea48dedd26a637ab98ba627ae70b7ed94798eabfd4b089f5e5e73524a24eb"
    folder = "ublock"
    outputDir = layout.buildDirectory.dir("generated/ublockAssets")
  }

androidComponents {
  onVariants(selector().withFlavor("browser" to "ublock")) { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(fetchUblock, FetchExtensionTask::outputDir)
  }
}

dependencies {
  "ublockImplementation"(libs.geckoview.arm64)

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Networking, storage, background work
  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.work.runtime)
  implementation(libs.junrar)
  // Text extraction for the PDF to EPUB conversion
  implementation(libs.pdfbox.android)
  testImplementation(libs.okhttp.mockwebserver)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
