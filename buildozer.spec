[app]

# Title of your application
title = Video Fetcher

# Package name
package.name = videofetcher

# Package domain (needed for android/ios packaging)
package.domain = org.kaungpainghein

# Source code where the main.py lives
source.dir = .

# Source files to include (let empty to include all the files)
source.include_exts = py,png,jpg,kv,atlas

# Application versioning
version = 1.0.0

# Application requirements
# comma separated e.g. requirements = sqlite3,kivy
requirements = python3,kivy==2.3.0,kivymd==1.1.1,yt-dlp,plyer,certifi,urllib3,charset-normalizer,idna,requests

# Android specific
android.permissions = INTERNET, READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE

# Minimum API your APK / AAB will support.
android.minapi = 21

# Android API to use
android.api = 33

# Architecture (arm64-v8a is the modern standard for Android)
android.archs = arm64-v8a

# Allow network requests to cleartext HTTP if needed (sometimes required for yt-dlp)
android.allow_backup = True

[buildozer]
# (int) Log level (0 = error only, 1 = info, 2 = debug (with command output))
log_level = 2

# (int) Display warning if buildozer is run as root (0 = False, 1 = True)
warn_on_root = 1