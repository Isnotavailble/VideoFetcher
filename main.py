import os
import re
import threading
import yt_dlp
from kivy.lang import Builder
from kivy.clock import mainthread
from kivy.utils import platform
from kivymd.app import MDApp
from kivymd.uix.screen import MDScreen
from plyer import storagepath

# Request Android Permissions at startup
if platform == 'android':
    from android.permissions import request_permissions, Permission
    request_permissions([
        Permission.INTERNET, 
        Permission.READ_EXTERNAL_STORAGE, 
        Permission.WRITE_EXTERNAL_STORAGE
    ])

# UI Layout using Kivy Design Language
KV = '''
MDScreen:
    md_bg_color: app.theme_cls.bg_normal

    MDBoxLayout:
        orientation: 'vertical'
        padding: "24dp"
        spacing: "20dp"
        pos_hint: {"center_x": .5, "center_y": .5}

        MDLabel:
            text: "Video Fetcher"
            font_style: "H4"
            halign: "center"
            theme_text_color: "Primary"
            size_hint_y: None
            height: self.texture_size[1]

        MDTextField:
            id: url_input
            hint_text: "Enter video URL (http://...)"
            mode: "rectangle"
            icon_right: "link"

        MDBoxLayout:
            orientation: 'horizontal'
            spacing: "10dp"
            size_hint_y: None
            height: "48dp"

            MDLabel:
                text: "Max Quality:"
                theme_text_color: "Secondary"
                size_hint_x: 0.4

            Spinner:
                id: quality_spinner
                text: "1080p"
                values: ["1080p", "720p", "480p"]
                background_color: app.theme_cls.primary_color
                size_hint_x: 0.6

        MDRaisedButton:
            text: "DOWNLOAD"
            pos_hint: {"center_x": .5}
            size_hint_x: 1
            on_release: app.start_download()

        MDProgressBar:
            id: progress_bar
            value: 0
            size_hint_y: None
            height: "4dp"
            opacity: 0

        MDLabel:
            id: status_label
            text: "Ready."
            halign: "center"
            theme_text_color: "Hint"
            font_style: "Caption"
'''

class VideoDownloaderApp(MDApp):
    def build(self):
        self.theme_cls.primary_palette = "Cyan"
        self.theme_cls.theme_style = "Dark"
        return Builder.load_string(KV)

    def validate_url(self, url):
        if not url:
            return False
        pattern = re.compile(r'^https?://[^\s/$.?#].[^\s]*$', re.IGNORECASE)
        return re.match(pattern, url) is not None

    def get_download_path(self):
        """Finds the correct public Downloads folder, prioritizing Android."""
        if platform == 'android':
            try:
                # Get the public Android Downloads directory
                return storagepath.get_downloads_dir()
            except Exception:
                return "/storage/emulated/0/Download"
        else:
            # Fallback for desktop testing
            return os.path.join(os.path.expanduser("~"), "Downloads")

    def start_download(self):
        url = self.root.ids.url_input.text.strip()
        quality_choice = self.root.ids.quality_spinner.text

        if not self.validate_url(url):
            self.update_status("Error: Invalid URL", error=True)
            return

        self.root.ids.progress_bar.opacity = 1
        self.root.ids.progress_bar.value = 0
        self.update_status("Initializing download...")

        # Map UI choice to resolution
        if quality_choice == '720p':
            max_height = 720
        elif quality_choice == '480p':
            max_height = 480
        else:
            max_height = 1080

        # Run yt-dlp in a background thread so the app doesn't freeze
        threading.Thread(target=self.process_download, args=(url, max_height), daemon=True).start()

    def process_download(self, url, max_height):
        download_path = self.get_download_path()
        output_template = os.path.join(download_path, '%(extractor)s_%(id)s.%(ext)s')

        # Mobile configuration (No FFmpeg integration for v1.0)
        ydl_opts = {
            'outtmpl': output_template,
            'restrictfilenames': True,
            'progress_hooks': [self.yt_dlp_hook],
            'noplaylist': True,
            'quiet': True,
            'no_warnings': True,
            # Fallback to the best pre-merged format if ffmpeg isn't available
            'format': f'best[height<={max_height}]/best',
        }

        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                ydl.download([url])
            self.download_complete("Download finished! Saved to Downloads.")
        except Exception as e:
            self.update_status(f"Download failed: {str(e)[:50]}...", error=True)

    def yt_dlp_hook(self, d):
        if d['status'] == 'downloading':
            # Extract percentage safely
            percent_str = d.get('_percent_str', '0%').replace('%', '').replace('\x1b[0;94m', '').replace('\x1b[0m', '').strip()
            try:
                percent = float(percent_str)
            except ValueError:
                percent = 0.0

            speed = d.get('_speed_str', 'N/A')
            eta = d.get('_eta_str', 'N/A')
            
            # Send updates to the main UI thread
            self.update_progress(percent, f"Downloading: {percent}% | Speed: {speed} | ETA: {eta}")

    @mainthread
    def update_progress(self, percent, text):
        self.root.ids.progress_bar.value = percent
        self.root.ids.status_label.text = text
        self.root.ids.status_label.theme_text_color = "Primary"

    @mainthread
    def update_status(self, text, error=False):
        self.root.ids.status_label.text = text
        self.root.ids.status_label.theme_text_color = "Error" if error else "Primary"

    @mainthread
    def download_complete(self, text):
        self.root.ids.progress_bar.value = 100
        self.root.ids.status_label.text = text
        self.root.ids.status_label.theme_text_color = "Custom"
        self.root.ids.status_label.text_color = (0, 1, 0, 1) # Green

if __name__ == '__main__':
    VideoDownloaderApp().run()