package com.videofetcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(com.videofetcher.feature.settings.viewmodel.SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.videofetcher.feature.settings.viewmodel.SettingsViewModel(container.settingsRepository) as T
        }
        if (modelClass.isAssignableFrom(com.videofetcher.feature.files.viewmodel.FilesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.videofetcher.feature.files.viewmodel.FilesViewModel(container.fileRepository) as T
        }
        if (modelClass.isAssignableFrom(com.videofetcher.feature.home.viewmodel.HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.videofetcher.feature.home.viewmodel.HomeViewModel(
                container.downloadRepository,
                container.downloadManager,
                container.pauseManager
            ) as T
        }
        if (modelClass.isAssignableFrom(com.videofetcher.feature.quickshare.viewmodel.QuickShareViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.videofetcher.feature.quickshare.viewmodel.QuickShareViewModel(container.downloadRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
