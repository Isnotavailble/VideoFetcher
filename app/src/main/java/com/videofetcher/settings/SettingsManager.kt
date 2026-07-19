package com.videofetcher.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        val IS_DARK_MODE = booleanPreferencesKey("is_dark_mode")
    }

    val isDark: Flow<Boolean> = appContext.dataStore.data.map { it[IS_DARK_MODE] ?: false }

    suspend fun setDarkMode(isDark: Boolean) = appContext.dataStore.edit { it[IS_DARK_MODE] = isDark }
}