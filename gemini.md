# VideoFetcher - Project Documentation

## Overview

VideoFetcher is an Android application designed to download videos via URL. It operates via two main features:

1. **In-App Download:** A full Jetpack Compose UI for pasting URLs, tracking downloads, and viewing completed files.
2. **Quick Share:** A transparent activity (bottom sheet) triggered via the Android Share sheet (`Intent.ACTION_SEND`), allowing users to download videos directly from other apps without opening the full UI.

The app acts as a robust **Download Manager**, capable of downloading multiple videos in parallel using a queueing system managed by a unified `DownloadService`.

## Core Components

### UI & Activities

- **`MainActivity.kt`**: Main entry point. Hosts the Jetpack Compose UI (`VideoDownloaderUI`). Extracts URLs from intents on launch and instantly renders native window backgrounds to prevent UI flashing.
- **`QuickDownloadActivity.kt`**: A lightweight, transparent Compose activity handling shared URLs. Parses URLs via Regex, displays a Modal Bottom Sheet, checks permissions, and starts the `DownloadService`.
- **`VideoDownloaderScreen.kt`**: The primary Compose UI utilizing a Material 3 3-tab layout (`NavigationBar`):
    - **Home**: URL input (with clipboard auto-paste), format selection, and a scrollable queue of active downloads (`ActiveDownloadCard`).
    - **Files**: Displays paused and completed video cards with a Pull-to-Refresh container.
    - **Settings**: Manages App Appearance (Dark/Light mode) and other preferences.

### Service & Business Logic

- **`DownloadService.kt`**: A foreground service executing downloads using `YoutubeDL` and `FFmpeg`. Manages a queue of parallel downloads (`maxParallelDownloads = 3`), maps active Coroutine `Job`s, and handles rich ongoing notifications per video.
- **`DownloaderViewModel.kt`**: Bridges UI and background logic. Initializes engines and routes actions. Handles complex background file fetching with smart caching (preventing UI flashes/glitches) and lazy thumbnail generation via `MediaMetadataRetriever`.
- **`PauseRepository.kt`**: Uses `SharedPreferences` to persist paused download details (URL, title, quality, progress) into JSON objects for session recovery.

### State Management

- **`DownloadManager.kt`**: A centralized singleton holding the app's global state flows.
- **`DownloaderState.kt`**: Split into `EngineState` (`Initializing`, `Idle`, `Error`) and `DownloadState` per URL (`Queued`, `Downloading`, `Success`, `Error`, `Cancelled`). The UI observes a `Map<String, DownloadState>`.
- **`FilesListState.kt`**: Sealed class managing the downloaded files list lifecycle (`Fetching`, `Success`, `Error`, `Idle`). Includes the `DownloadedFileDetails` data class.

### Build & CI/CD

- **`build.gradle.kts`**: Setup for Kotlin 17, target SDK 34, and Compose. Implements ABI splitting (`armeabi-v7a`, `arm64-v8a`) to drastically reduce APK sizes (from ~200MB down to ~50MB).
- **GitHub Actions (`main.yml`)**: Automated pipeline to build debug APKs and upload the split ABI APKs as artifacts on every push to the `main` branch.

## Key Libraries Used

- **YoutubeDL-Android**: `io.github.junkfood02.youtubedl-android:library` & `ffmpeg`
- **Jetpack Compose**: Material 3, Lifecycle Viewmodel, Activity Compose
- **Coil**: For asynchronous thumbnail image loading

## Developer Guidelines

- **Ignored Files Check:** As per project rules, files such as `local.properties`, `build/`, `.gradle`, `videofetcher.jks`, and `key.properties` are explicitly ignored and should not be tracked or committed.
- **Adding Features:** New download entry points should follow the established pattern: resolve the target URL and quality, then start `DownloadService` by pushing intent extras (`URL`, `QUALITY`).
- **UI State:** Rely on `DownloadManager.activeDownloads` and `DownloadManager.engineState` to react to state changes. State logic handles map updates implicitly based on URL keys.
