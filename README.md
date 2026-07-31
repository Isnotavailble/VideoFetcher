
  
# VideoFetcher
 <a href="https://github.com/yt-dlp/yt-dlp">
 <img src="https://img.shields.io/badge/Powered_by-yt--dlp-red?style=for-the-badge" alt="yt-dlp" />
 </a> 
 <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge" alt="License: MIT" /></a>


VideoFetcher was built for the community of privacy seekers. In a world full of tracking and subscriptions, tools like this should be free, safe, and truly yours.

---

## 🛡️ 100% Private & Local
**No tracking. No telemetry. No hidden web servers.** 
VideoFetcher acts entirely on your device. Every URL analysis, metadata fetch, and media download is processed 100% locally using the power of `yt-dlp` and `FFmpeg` bundled right into the app. What happens on your device, stays on your device.

## ✨ Features
* **Universal Media Extraction:** Download video, audio, and metadata from thousands of websites including YouTube, Twitter, Instagram, TikTok, and more, powered directly by `yt-dlp`.

* **Background Queueing Service:** A robust foreground service that manages parallel downloads and queues seamlessly in the background. Downloads survive and continue even if you close the application.
* **Direct Native Share Integration:** Trigger downloads directly from other apps via Android's native share menu (`Intent.ACTION_SEND`). Our headless Quick Share sheet fetches metadata and starts the download without ever opening the main app.
* **Embedded Metadata & Thumbnails:** Utilizes bundled `FFmpeg` binaries to automatically fetch high-quality thumbnails and directly embed them (along with other media tags) into the final MP4 container for perfect Android Gallery support.
* **Smart Resolution Bucketing:** The app automatically analyzes raw server qualities and maps complex resolutions into familiar UI tiers (4K, 2K, 1080p, 720p) for easy selection.
* **Custom SAF Storage:** Select any folder on your device or external SD Card securely via the Android Storage Access Framework (SAF). We strictly avoid requesting invasive `MANAGE_EXTERNAL_STORAGE` permissions.
* **Customizable Cookies & User-Agents:** Bypass geo-blocks, login walls, age-restrictions, and aggressive bot detection by injecting `.txt` Netscape format cookies and assigning custom HTTP User-Agents tailored to specific domains. All configurations are stored locally in the app's `.cookies` folder, giving you full freedom to easily change, edit, or swap them out whenever you want!

## ⚠️ Caution About Cookies
Using account cookies is entirely at your own risk. When cookies are attached, `yt-dlp` mimics your personal digital identity during requests. Social platforms (especially Meta / Facebook) actively detect automated traffic. **Testing has shown Facebook issuing "Automation Detected" warnings within 3 to 4 days of usage, which can lead to permanent account bans.** Please use cookies with extreme caution and delete them when no longer needed.

## 🛠️ Tech Stack & Libraries
VideoFetcher is made possible thanks to incredible open-source projects:
* **[yt-dlp](https://github.com/yt-dlp/yt-dlp)**: The incredible open-source core powering all extractions.
* **[YoutubeDL-Android](https://github.com/junkfood02/youtubedl-android)**: (by junkfood02) Provides the Android bindings and FFmpeg binaries.
## 🤚 Limitations
* **No playlist support:** The current version does not support downloading playlists. Only individual media URLs are supported.

* **No custom yt-dlp options:** The app does not have a custom command option like other yt-dlp based projects. The app is designed to be simple and user-friendly, so there are not many advanced options.



## 🤝 Community Supported
This app is completely free and ad-free. If it has made your life easier, please consider supporting the development!

### 💬 Get in Touch
Whether you have feedback, found a bug, or want to discuss a project, chat directly on Telegram: **[@Tom_lit](https://teleg.one/Tom_lit)**


