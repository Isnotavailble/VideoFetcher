# VideoFetcher - Project Documentation
## Rules: 
- always respect the .gitignore rules and never track ignored files (e.g., `local.properties`, `build/`, `.gradle`, `videofetcher.jks`, `key.properties`).
- do not use the `material-icons-extended` library to avoid bloating the APK size. Instead, create custom lightweight XML vector drawables for any missing icons and apply theme colors dynamically in Compose.
- avoid using local databases (like Room or SQLite) to track downloaded files, as they will be wiped on app reinstall. Instead, append a custom signature (e.g., `_vdf`) to downloaded filenames to allow instant recognition via MediaStore queries.
- use Storage Access Framework (SAF) for custom folder selection and persistent file management, and never request `MANAGE_EXTERNAL_STORAGE` to ensure compliance with Google Play policies.
- maintain clean and breathable UI layouts with proper typography hierarchies, using appropriate line heights and compact icon layouts to conserve screen space.
- follow the good UIUX with black and white colors and good typography. You must strictly use the unified `AppTypography` and semantic colors defined in the global theme provider (`Theme.kt`). Never hardcode `fontSize = X.sp` or colors (like `Color.Red`) directly in composables. Instead, reference `MaterialTheme.typography` and `MaterialTheme.colorScheme` to ensure cross-OS consistency (e.g., bypassing MIUI Force-Dark inversion bugs).


## Overview

VideoFetcher is an Android application designed to download videos via URL. It operates via two main features:

1. **In-App Download:** A full Jetpack Compose UI for pasting URLs, tracking downloads, and viewing completed files.
2. **Quick Share:** A transparent activity (bottom sheet) triggered via the Android Share sheet (`Intent.ACTION_SEND`), allowing users to fetch metadata, choose qualities, and download videos directly from other apps without opening the full UI.

The app acts as a robust **Download Manager**, capable of downloading multiple videos in parallel using a queueing system managed by a unified `DownloadService`.

## Core Components

### UI & Activities

- **`MainActivity.kt`**: Main entry point. Hosts the Jetpack Compose UI (`VideoDownloaderUI`). Extracts URLs from intents on launch and instantly renders native window backgrounds to prevent UI flashing.
- **`QuickDownloadActivity.kt`**: A lightweight, transparent Compose activity handling shared URLs. Parses URLs via Regex, displays a Modal Bottom Sheet, checks permissions, and starts the `DownloadService`.
- **`VideoDownloaderScreen.kt`**: The primary Compose UI utilizing a Material 3 3-tab layout (`NavigationBar`):
    - **Home**: URL input (with clipboard auto-paste), a **Progressive Disclosure UI** (X-Ray metadata fetching with a dynamic "Media Card" reveal), resolution selection, and a scrollable queue of active downloads (`ActiveDownloadCard`).
    - **Files**: Displays paused and completed video cards with a Pull-to-Refresh container.
    - **Settings**: Manages App Appearance, global UI toggles (like "Select Resolution" Lightning vs. X-Ray mode), custom SAF download directories, and hosts the dedicated community-focused **About** screen.

### Service & Business Logic

 - **`DownloadService.kt`**: A foreground service executing downloads using `YoutubeDL` and `FFmpeg`. Manages parallel queues, supports custom SAF paths, and safely injects the `_vdf` signature. It strictly enforces Android-compatible **H.264/AVC codecs** and directly embeds thumbnails into the MP4 container for seamless Gallery support.
 - **`DownloaderViewModel.kt`**: Bridges UI and background logic. Handles local file MediaStore querying, lazy thumbnail generation, and SAF deletion. Additionally, it manages high-speed "X-Ray" metadata fetching (optimized with `--force-ipv4` and `--no-playlist`) and **Resolution Bucketing** (mapping raw server heights to familiar UI tiers like 4K, 2K, 1080p).
- **`PauseRepository.kt`**: Uses `SharedPreferences` to persist paused download details (URL, title, quality, progress) into JSON objects for session recovery.
 - **`PermissionManager.kt`**: Manages persistent folder access (SAF) via SharedPreferences to silently manage files across reinstalls. Also acts as the central local datastore for global user preferences (e.g., `resolution_selection_enabled`).

### State Management

- **`DownloadManager.kt`**: A centralized singleton holding the app's global state flows.
- **`DownloaderState.kt`**: Split into `EngineState` (`Initializing`, `Idle`, `Error`) and `DownloadState` per URL (`Queued`, `Downloading`, `Success`, `Error`, `Cancelled`). The UI observes a `Map<String, DownloadState>`.
- **`FilesListState.kt`**: Sealed class managing the downloaded files list lifecycle (`Fetching`, `Success`, `Error`, `Idle`). Includes the `DownloadedFileDetails` data class.
 - **`VideoInfoState`**: Sealed class inside the ViewModel tracking the X-Ray metadata fetching lifecycle (`Idle`, `Fetching`, `Success`, `Error`) to drive the progressive UI animations.

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

### Architecture & Style Rules

- **Iconography:** Do **NOT** use the `material-icons-extended` library, as it drastically bloats the APK size. If an icon is missing from the core Material icons, create a custom lightweight XML vector drawable in `res/drawable/` (e.g., `ic_pause.xml`) and apply theme colors dynamically using Compose's `tint` parameter.
- **Stateless File Management:** Avoid local databases (Room/SQLite) to track downloaded files, as they wipe on reinstall. Always append the custom `_vdf` signature (e.g., `video_name_(1080p)_vdf.mp4`) to filenames. This allows the app to recognize its own files instantly via MediaStore.
- **Storage Permissions:** Use Storage Access Framework (SAF) `ACTION_OPEN_DOCUMENT_TREE` for custom folder selection and persistent file management. Never request `MANAGE_EXTERNAL_STORAGE` to guarantee strict Google Play compliance.
- **UI/UX Typography & Layouts:** Maintain clean, breathable visual hierarchies. All typography must strictly use the unified `AppTypography` defined in `Theme.kt` (accessed via `MaterialTheme.typography`) to ensure consistent `lineHeight` ratios globally. For list items (like download cards), prefer compact horizontal icon layouts over bulky vertical text buttons to conserve screen real estate.
