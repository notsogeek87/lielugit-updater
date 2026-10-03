# LieluGit Updater

[![Build](https://github.com/notsogeek87/lielugit-updater/actions/workflows/build.yml/badge.svg)](https://github.com/notsogeek87/lielugit-updater/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

A small, **headless** Android library that updates an app distributed through **GitHub Releases**:
it checks the latest release, downloads the APK and hands it to Android's installer.

- No UI, no theme, no notification, no navigation: it works the same with Jetpack Compose and XML views.
- No GitHub token needed for public repositories.
- Robust SemVer comparison (`1.9.0 < 1.10.0`, `v1.2.3`, `release-1.2.3`, pre-releases).
- Architecture-aware APK selection (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, `universal`). It never silently picks an incompatible one.
- Download to the app's private cache with progress, size check and optional SHA-256 verification.
- Installation through a `content://` URI (FileProvider), never `file://`; detects the "Install unknown apps" permission.
- Two runtime dependencies (see [Dependencies](#dependencies)).

> **Language/SDK**: Kotlin, `minSdk 21`, `compileSdk 35`, Java 17. Package: `com.lielu.githubupdater`.

## Table of contents

1. [Installation](#installation)
2. [Quick start](#quick-start)
3. [Configuration](#configuration)
4. [API](#api)
5. [GitHub Releases and APK format](#github-releases-and-apk-format)
6. [Choosing the APK](#choosing-the-apk)
7. [Signature](#signature)
8. [Android "Install unknown apps" permission](#android-install-unknown-apps-permission)
9. [Jetpack Compose integration](#jetpack-compose-integration)
10. [XML / Views integration](#xml--views-integration)
11. [Errors](#errors)
12. [Architecture](#architecture)
13. [Dependencies](#dependencies)
14. [Versioning](#versioning)
15. [Publishing a release (maintainers)](#publishing-a-release-maintainers)
16. [Limitations](#limitations)
17. [License](#license)

## Installation

Each application picks the exact version of the library it uses.

### From a Maven repository (GitHub Packages)

Released versions are published as `com.lielu:lielugit-updater:<version>` to GitHub Packages
(when the maintainer enabled it, see [Publishing](#publishing-a-release-maintainers)).

```kotlin
// settings.gradle.kts of the app
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/notsogeek87/lielugit-updater")
            credentials {
                // GitHub Packages requires authentication even for public packages:
                // a personal access token (classic) with the `read:packages` scope.
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.lielu:lielugit-updater:1.0.0")
}
```

Put `gpr.user` / `gpr.token` in `~/.gradle/gradle.properties`, never in the repository.

### From a release file (no account needed)

Every GitHub Release of this repository carries `lielugit-updater-<version>.aar` and
`lielugit-updater-<version>-maven.zip` (a ready-to-unzip Maven repository layout).
Unzip the latter into a folder and declare it:

```kotlin
maven { url = uri("path/to/unzipped/maven-repo") }
```

### From Maven Local (development)

```bash
./gradlew :lielugit-updater:publishReleasePublicationToMavenLocal -PVERSION_NAME=1.0.0-SNAPSHOT
```

```kotlin
repositories { mavenLocal() }
dependencies { implementation("com.lielu:lielugit-updater:1.0.0-SNAPSHOT") }
```

### As a local module

Clone the repository next to your app, then in the app's `settings.gradle.kts`:

```kotlin
include(":lielugit-updater")
project(":lielugit-updater").projectDir = file("../lielugit-updater/lielugit-updater")
```

```kotlin
dependencies { implementation(project(":lielugit-updater")) }
```

### What the library adds to your manifest

The manifest merger adds, automatically:

| Entry | Why |
|---|---|
| `INTERNET` permission | Query GitHub and download the APK. |
| `REQUEST_INSTALL_PACKAGES` permission | Required by Android to ask for / use the "Install unknown apps" authorization. |
| `UpdaterFileProvider` (authority `${applicationId}.lielugit.updater.fileprovider`, not exported) | Shares the downloaded APK with the installer through `content://`. It cannot conflict with your own FileProvider. |

> `REQUEST_INSTALL_PACKAGES` is restricted on Google Play. This library targets apps that are
> distributed from GitHub (sideloaded). If you also publish on Play, remove it with
> `tools:node="remove"` in your manifest and do not ship the updater in that flavor.

## Quick start

```kotlin
val updater = UpdateManager(
    context = context,
    config = UpdateConfig(
        githubOwner = "lielu",
        githubRepository = "mon-app",
        apkAssetNamePattern = ".*\\.apk"
    )
)

val update = updater.checkForUpdate()           // suspend; null = up to date

if (update != null) {
    println("Nouvelle version : ${update.versionName}")

    val apk = updater.downloadUpdate(update)    // suspend; progress is published in updater.state

    if (updater.canInstallPackages()) {
        updater.installUpdate(apk)              // opens Android's installer
    } else {
        updater.openInstallPermissionSettings() // the user grants it, then call installUpdate again
    }
}
```

Failures are thrown as `UpdateException` (its `error` is an [`UpdateError`](#errors)) and also published in `updater.state`:

```kotlin
try {
    updater.checkForUpdate()
} catch (e: UpdateException) {
    when (val error = e.error) {
        is UpdateError.NetworkError -> /* offline */ Unit
        is UpdateError.RateLimit -> /* retry after error.resetAtEpochSeconds */ Unit
        else -> Log.w("Updater", error.message)
    }
}
```

## Configuration

```kotlin
data class UpdateConfig(
    val githubOwner: String,
    val githubRepository: String,
    val apkAssetNamePattern: String? = null,
    val checkIntervalHours: Int = 24
)
```

| Field | Meaning |
|---|---|
| `githubOwner`, `githubRepository` | Repository whose **latest** release is checked. |
| `apkAssetNamePattern` | Regex the *whole* asset name must match, e.g. `".*\\.apk"`, `"myapp-arm64-v8a\\.apk"`. `null` = any `.apk`. Validated when the config is created. |
| `checkIntervalHours` | Network checks happen at most this often. Within the interval `checkForUpdate()` answers from a small cache (the previous result, re-evaluated against the installed version). `0` disables it. |

`checkForUpdate(force = true)` ignores the cache. Errors are never cached.

## API

The public surface is deliberately small (the build enables Kotlin's `explicitApi()` mode):

| Type | Role |
|---|---|
| `UpdateConfig` | Configuration. |
| `UpdateManager` | `checkForUpdate(force)`, `downloadUpdate(update)`, `installUpdate(file)`, `state: StateFlow<UpdateState>`, `canInstallPackages()`, `installPermissionSettingsIntent()`, `openInstallPermissionSettings()`, `clearDownloads()`. |
| `UpdateInfo` | Version, notes, URL, file name, size, date, optional SHA-256. |
| `UpdateState` | `Idle`, `Checking`, `UpToDate`, `UpdateAvailable`, `Downloading(progress)`, `Downloaded(file)`, `Installing`, `Error(error)`. |
| `DownloadProgress` | `downloadedBytes`, `totalBytes?`, `percentage?`. |
| `UpdateError` / `UpdateException` | Typed errors. |
| `UpdaterFileProvider` | Declared in the library manifest; nothing to call. |

`UpdateInfo` has one field beyond the minimal description: `sha256: String? = null`, used to verify
the download when GitHub (or a `.sha256` file) provides a checksum. `versionName` is normalized
(`v1.2.3` → `1.2.3`); `versionCode` is only set when the tag carries it as SemVer build metadata (`v1.2.3+45`).

## GitHub Releases and APK format

The library calls `GET https://api.github.com/repos/{owner}/{repo}/releases/latest`
(unauthenticated: 60 requests per hour per IP, which is why checks are cached for 24 h by default).
Per release it reads the tag, name, notes, publication date and assets (name, size, download URL, digest).

For your repository to work:

1. Publish a **GitHub Release** (not just a tag). Drafts and pre-releases are ignored by `/releases/latest`.
2. Name the tag with a version: `v1.2.3`, `1.2.3` or `release-1.2.3` (`-beta.1` suffixes are understood).
3. Attach one or more **`.apk` assets** to the release.
4. The APK must be **signed** (see [Signature](#signature)). Prefer a release build.
5. Optional integrity check, any of:
   - nothing to do: GitHub computes a `digest` (`sha256:…`) for new uploads and the library uses it;
   - or attach `<apk name>.sha256` containing `<64 hex chars>  <file name>` (output of `sha256sum`).

   When a checksum is known and the download differs, the file is deleted and `UpdateError.ChecksumMismatch` is raised.

The `versionName` of the **installed** app must itself be a parsable version (`1.2.3`, `1.2.3-beta`…).
An update is offered when the release version is strictly greater.

## Choosing the APK

1. Candidates: assets ending in `.apk` whose full name matches `apkAssetNamePattern` (if set).
2. An asset named for an architecture the device does not support (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) is **never** selected, even if the pattern targets it. If nothing compatible remains: `UpdateError.ApkNotFound`.
3. Among the rest: the best match for the device's ABI list (`Build.SUPPORTED_ABIS`, in preference order), then a `universal` APK, then a single APK whose name mentions no ABI.
4. Ambiguity is an error, not a guess: e.g. `app-debug.apk` + `app-release.apk` → `ApkNotFound("Several APKs match… narrow apkAssetNamePattern")`.

Architecture names are recognized as whole words in the file name (`app-arm64-v8a.apk`, `app_x86_64-release.apk`; also `arm64`, `armv7`, `x86-64`, `fat`).

## Signature

> **APKs published on GitHub must be signed with the same key as the app already installed.**

Android refuses to update an app with an APK signed by a different key (and the library never
tries to work around that). Practically: always sign release builds with the same keystore
(the one used for the installed version), and don't mix debug-signed and release-signed builds.
If the signatures differ, the installer shows an error such as "App not installed"; no
`UpdateError` can announce it beforehand because Android only checks it at install time.
Also keep the same `applicationId` and a strictly increasing `versionCode`.

## Android "Install unknown apps" permission

Since Android 8, an app must be authorized by the user to install APKs.

```kotlin
if (!updater.canInstallPackages()) {
    // Opens "Install unknown apps" for *your* app. Android handles everything, nothing is bypassed.
    updater.openInstallPermissionSettings()
    // or launch updater.installPermissionSettingsIntent() yourself to get a result callback
}
```

If you call `installUpdate()` without the authorization it throws
`UpdateException(UpdateError.InstallationNotAllowed)`. After the user comes back from the
settings, call `installUpdate(apk)` again (the downloaded file is still in the cache).

Installation always goes through Android's system installer, which asks the user to confirm.
The APK is exposed with a `content://` URI and `FLAG_GRANT_READ_URI_PERMISSION`.

## Jetpack Compose integration

The library exposes state, you draw. Keep one `UpdateManager` per process (e.g. in a ViewModel):

```kotlin
class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val updater = UpdateManager(app, UpdateConfig("lielu", "mon-app"))
    val state = updater.state

    fun check() = viewModelScope.launch { runCatching { updater.checkForUpdate() } } // errors land in `state`

    fun download(update: UpdateInfo) = viewModelScope.launch {
        runCatching {
            val apk = updater.downloadUpdate(update)
            if (updater.canInstallPackages()) updater.installUpdate(apk) else updater.openInstallPermissionSettings()
        }
    }
}

@Composable
fun UpdateBanner(vm: UpdateViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    when (val s = state) {
        is UpdateState.UpdateAvailable -> Button(onClick = { vm.download(s.update) }) {
            Text("Update to ${s.update.versionName}")
        }
        is UpdateState.Downloading -> LinearProgressIndicator(
            progress = { (s.progress.percentage ?: 0) / 100f }
        )
        is UpdateState.Error -> Text(s.error.message)
        else -> Unit
    }
}
```

## XML / Views integration

```kotlin
class MainActivity : AppCompatActivity() {
    private lateinit var updater: UpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        updater = UpdateManager(this, UpdateConfig("lielu", "mon-app"))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updater.state.collect { state ->
                    progressBar.isVisible = state is UpdateState.Downloading
                    if (state is UpdateState.Downloading) progressBar.progress = state.progress.percentage ?: 0
                    updateButton.isVisible = state is UpdateState.UpdateAvailable
                }
            }
        }
        lifecycleScope.launch { runCatching { updater.checkForUpdate() } }
    }
}
```

Notifications are never posted by the library; if you want one, build it from `UpdateState`.

## Errors

| `UpdateError` | When |
|---|---|
| `NetworkError` | Host unreachable, timeout, connection lost while querying GitHub (or opening the download). |
| `GitHubApiError(httpCode, message)` | Unexpected HTTP status, invalid JSON, release without tag, invalid checksum file. |
| `RateLimit(resetAtEpochSeconds)` | GitHub's anonymous quota is exhausted (HTTP 429, or 403 with remaining quota 0). |
| `ReleaseNotFound` | Repository missing/private or without a published release (HTTP 404). |
| `ApkNotFound(message)` | No / incompatible / ambiguous APK, or a non-HTTPS URL. |
| `InvalidVersion(rawVersion)` | The release tag or the installed `versionName` is not a version. |
| `DownloadError(message)` | HTTP error, interrupted or truncated download, disk error. The partial file is deleted. |
| `ChecksumMismatch(expected, actual)` | SHA-256 differs. The file is deleted. |
| `InstallationNotAllowed` | "Install unknown apps" not granted. |
| `InstallationError(message)` | File missing/not from this library, no installer available, installer refused. |

## Architecture

```
lielugit-updater/                    repository
└── lielugit-updater/                the Android library module (com.android.library)
    └── src/main/kotlin/com/lielu/githubupdater/
        ├── UpdateManager.kt         public facade + StateFlow<UpdateState>
        ├── UpdateConfig/Info/State/Error/DownloadProgress.kt   public data types
        ├── UpdaterFileProvider.kt   FileProvider subclass (manifest only)
        └── core/                    internal implementation
            ├── GithubReleaseClient  GitHub API → GithubRelease (org.json)
            ├── Version              SemVer parsing/comparison
            ├── ApkSelector          pattern + CPU ABI selection
            ├── UpdateChecker        check logic + cache (UpdateStore)
            ├── ApkDownloader        download to cacheDir/lielugit-updates, Flow of progress
            ├── ApkInstaller         FileProvider + ACTION_VIEW + permission helpers
            └── HttpTransport        HttpURLConnection behind a small interface
```

Everything in `core/` is `internal`. Everything except `ApkInstaller`, the SharedPreferences
store and `UpdateManager` is plain Kotlin/JVM and unit-tested without Android.
Downloads use `<name>.apk.part` and are renamed only after verification, so a valid-looking APK
is always complete. Older downloads are deleted before each new download (or with `clearDownloads()`).

## Dependencies

| Dependency | Scope | Why |
|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | `api` | `suspend` functions, `Flow`, `StateFlow`. |
| `androidx.core:core` | `implementation` | `FileProvider`. |
| Android platform: `HttpURLConnection`, `org.json`, `MessageDigest` | – | No HTTP/JSON/crypto library is added. |
| `junit`, `kotlin-test`, `kotlinx-coroutines-test`, `org.json:json` | test only | Unit tests. |

License of all dependencies: Apache-2.0 / EPL-1.0 (JUnit) / JSON License (test only, not shipped).

## Versioning

The project follows [Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`, tagged `vMAJOR.MINOR.PATCH`.

| Bump | Example | Meaning |
|---|---|---|
| **MAJOR** | `v1.4.2` → `v2.0.0` | Breaking change to the public API or behavior (removed/changed signature, new minimum SDK, changed contract). Read the changelog before upgrading. |
| **MINOR** | `v1.0.0` → `v1.1.0` | Backward-compatible new feature. |
| **PATCH** | `v1.1.0` → `v1.1.1` | Backward-compatible bug fix. |
| Pre-release | `v1.2.0-rc.1` | Unstable preview; published as a GitHub *pre-release*. |

An app depends on one exact version (`com.lielu:lielugit-updater:1.1.0`) and upgrades when it chooses.
Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## Publishing a release (maintainers)

1. Update `CHANGELOG.md` (move *Unreleased* under the new version) and merge to `main` once CI is green.
2. Create and push the tag:

   ```bash
   git checkout main && git pull
   git tag -a v1.0.0 -m "v1.0.0"
   git push origin v1.0.0
   ```
3. The **Release** workflow runs the tests, builds the AAR, then creates the GitHub Release with
   `lielugit-updater-1.0.0.aar` and `lielugit-updater-1.0.0-maven.zip`. No release is created if a test fails.
   The Gradle version is taken from the tag (`-PVERSION_NAME=1.0.0`); `VERSION_NAME` in `gradle.properties` only matters for local builds.
4. To also publish to Maven (GitHub Packages) set the repository **variable** `MAVEN_PUBLISH_ENABLED` to `true`
   (Settings → Secrets and variables → Actions → Variables). The workflow uses the built-in `GITHUB_TOKEN`.

To publish to Maven Central instead you need a Sonatype account, a verified `com.lielu` namespace and PGP signing; the POM metadata is already prepared in `gradle.properties`.

## Limitations

- Public repositories only (no token support); anonymous GitHub API rate limit applies.
- Only the *latest non-draft, non-prerelease* release is considered.
- Downloads are not resumable; an interrupted download restarts from zero.
- The library cannot install silently: Android always asks the user to confirm.

## License

[MIT](LICENSE) © 2026 notsogeek87
