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
    val autoSave: Boolean = false,
    /**
     * Il nero vero nel tema scuro (3.0). Nuovo: chi aggiorna non l'aveva, e il default e' la
     * tavolozza che aveva gia'.
     */
    val pureBlack: Boolean = false,
    /**
     * Le vibrazioni dell'interfaccia (3.0). Nuovo, e acceso di serie come nell'engine: prima della
     * 3.0 l'app non vibrava perche' non aveva tasti che vibrassero, non perche' qualcuno l'avesse
     * spento.
     */
    val haptics: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * Le impostazioni su DataStore.
 *
 * Le chiavi sono quelle della 2.x, una per una e con gli stessi nomi: un'installazione che si
 * aggiorna sul posto ritrova tema, colori, modalita' e salvataggio come li aveva lasciati. Le due
 * nuove ([Settings.pureBlack], [Settings.haptics]) hanno chiavi nuove e il loro default quando mancano.
 */
class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map(::readSettings)

    suspend fun setSimpleMode(value: Boolean) = context.dataStore.edit { it[SIMPLE_MODE] = value }
    suspend fun setAutoSave(value: Boolean) = context.dataStore.edit { it[AUTO_SAVE] = value }

    /** L'aspetto in una scrittura sola: tema, colori, nero e aptica cambiano insieme dalla sezione «Aspetto». */
    suspend fun setAppearance(value: Settings) = context.dataStore.edit {
        it[THEME_MODE] = value.themeMode.name
        it[DYNAMIC_COLOR] = value.dynamicColor
        it[PURE_BLACK] = value.pureBlack
        it[HAPTICS] = value.haptics
    }

    internal companion object {
        val SIMPLE_MODE = booleanPreferencesKey("simple_mode")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AUTO_SAVE = booleanPreferencesKey("auto_save")
        val PURE_BLACK = booleanPreferencesKey("pure_black")
        val HAPTICS = booleanPreferencesKey("haptics")

        fun readSettings(prefs: Preferences) = Settings(
            simpleMode = prefs[SIMPLE_MODE] ?: true,
            themeMode = prefs[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            dynamicColor = prefs[DYNAMIC_COLOR] ?: true,
            autoSave = prefs[AUTO_SAVE] ?: false,
            pureBlack = prefs[PURE_BLACK] ?: false,
            haptics = prefs[HAPTICS] ?: true,
        )
    }
}
