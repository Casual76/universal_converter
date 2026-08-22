package com.p2r3.convert.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { System, Light, Dark }

data class Settings(
    /** Simple mode lets the engine pick the tool; advanced pins a specific one. */
    val simpleMode: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    /** Write results straight to Downloads instead of waiting for a tap. */
    val autoSave: Boolean = false
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            simpleMode = prefs[SIMPLE_MODE] ?: true,
            themeMode = prefs[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            dynamicColor = prefs[DYNAMIC_COLOR] ?: true,
            autoSave = prefs[AUTO_SAVE] ?: false
        )
    }

    suspend fun setSimpleMode(value: Boolean) = context.dataStore.edit { it[SIMPLE_MODE] = value }
    suspend fun setThemeMode(value: ThemeMode) = context.dataStore.edit { it[THEME_MODE] = value.name }
    suspend fun setDynamicColor(value: Boolean) = context.dataStore.edit { it[DYNAMIC_COLOR] = value }
    suspend fun setAutoSave(value: Boolean) = context.dataStore.edit { it[AUTO_SAVE] = value }

    private companion object {
        val SIMPLE_MODE = booleanPreferencesKey("simple_mode")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AUTO_SAVE = booleanPreferencesKey("auto_save")
    }
}
