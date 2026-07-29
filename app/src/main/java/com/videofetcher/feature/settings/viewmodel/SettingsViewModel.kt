package com.videofetcher.feature.settings.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videofetcher.repository.SettingsRepository
import com.videofetcher.manager.EngineUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class EngineUpdateState {
    object Idle : EngineUpdateState()
    object Checking : EngineUpdateState()
    object UpToDate : EngineUpdateState()
    data class UpdateAvailable(val version: String) : EngineUpdateState()
    object Updating : EngineUpdateState()
    object Success : EngineUpdateState()
    data class Error(val message: String) : EngineUpdateState()
}

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    private val _engineUpdateState = MutableStateFlow<EngineUpdateState>(EngineUpdateState.Idle)
    val engineUpdateState: StateFlow<EngineUpdateState> = _engineUpdateState.asStateFlow()

    fun isResolutionSelectionEnabled(): Boolean = repository.isResolutionSelectionEnabled()
    fun setResolutionSelectionEnabled(enabled: Boolean) = repository.setResolutionSelectionEnabled(enabled)

    fun isBypassSslEnabled(): Boolean = repository.isBypassSslEnabled()
    fun setBypassSslEnabled(enabled: Boolean) = repository.setBypassSslEnabled(enabled)

    fun isBypassExtractorEnabled(): Boolean = repository.isBypassExtractorEnabled()
    fun setBypassExtractorEnabled(enabled: Boolean) = repository.setBypassExtractorEnabled(enabled)

    fun clearThumbnailCache(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val thumbCacheDir = File(context.cacheDir, "thumbnails")
                if (thumbCacheDir.exists() && thumbCacheDir.isDirectory) {
                    thumbCacheDir.listFiles()?.forEach { it.delete() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun checkForEngineUpdate(context: Context, forceCheck: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (forceCheck) {
                _engineUpdateState.value = EngineUpdateState.Checking
            }
            val status = EngineUpdateManager(context).checkEngineStatus(context, forceCheck)
            _engineUpdateState.value = status
            if (status is EngineUpdateState.UpToDate || status is EngineUpdateState.Success) {
                delay(2000)
                _engineUpdateState.value = EngineUpdateState.Idle
            }
        }
    }

    fun resetEngineUpdateState() {
        _engineUpdateState.value = EngineUpdateState.Idle
    }

    fun updateEngine(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _engineUpdateState.value = EngineUpdateState.Updating
            val success = EngineUpdateManager(context).updateYtDlpDirectly(context)
            if (success) {
                _engineUpdateState.value = EngineUpdateState.Success
                delay(2000)
                _engineUpdateState.value = EngineUpdateState.Idle
            } else {
                _engineUpdateState.value = EngineUpdateState.Error("Failed to update engine.")
                delay(3000)
                _engineUpdateState.value = EngineUpdateState.Idle
            }
        }
    }

}
