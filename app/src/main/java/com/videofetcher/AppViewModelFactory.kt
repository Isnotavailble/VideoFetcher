package com.videofetcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DownloaderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DownloaderViewModel(container) as T
        }
        if (modelClass.isAssignableFrom(com.videofetcher.feature.settings.viewmodel.SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.videofetcher.feature.settings.viewmodel.SettingsViewModel(container.settingsRepository) as T
        }
        if (modelClass.isAssignableFrom(com.videofetcher.feature.files.viewmodel.FilesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.videofetcher.feature.files.viewmodel.FilesViewModel(container.fileRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
