package com.p2r3.convert.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le impostazioni come le ha lasciate una 2.x: le stesse chiavi devono dare le stesse scelte, e le due
 * nuove della 3.0 il loro default. E' l'aggiornamento sul posto, provato senza un telefono.
 */
class SettingsStoreTest {

    @Test
    fun `a fresh install reads the defaults`() {
        assertEquals(Settings(), SettingsStore.readSettings(emptyPreferences()))
    }

    @Test
    fun `a 2_x install keeps every choice and gets the new defaults`() {
        val old = preferencesOf(
            SettingsStore.SIMPLE_MODE to false,
            SettingsStore.THEME_MODE to "Dark",
            SettingsStore.DYNAMIC_COLOR to false,
            SettingsStore.AUTO_SAVE to true,
        )
        assertEquals(
            Settings(
                simpleMode = false,
                themeMode = ThemeMode.Dark,
                dynamicColor = false,
                autoSave = true,
                pureBlack = false,
                haptics = true,
            ),
            SettingsStore.readSettings(old),
        )
    }

    @Test
    fun `the keys are the 2_x names`() {
        // Cambiare un nome qui fa perdere la scelta a chi aggiorna: sono una promessa, non un dettaglio.
        assertEquals("simple_mode", SettingsStore.SIMPLE_MODE.name)
        assertEquals("theme_mode", SettingsStore.THEME_MODE.name)
        assertEquals("dynamic_color", SettingsStore.DYNAMIC_COLOR.name)
        assertEquals("auto_save", SettingsStore.AUTO_SAVE.name)
    }

    @Test
    fun `an unknown theme falls back to the system one`() {
        val prefs = preferencesOf(SettingsStore.THEME_MODE to "Sepia")
        assertEquals(ThemeMode.System, SettingsStore.readSettings(prefs).themeMode)
    }

    @Test
    fun `the new switches are read when present`() {
        val prefs = preferencesOf(SettingsStore.PURE_BLACK to true, SettingsStore.HAPTICS to false)
        val settings = SettingsStore.readSettings(prefs)
        assertEquals(true, settings.pureBlack)
        assertEquals(false, settings.haptics)
    }
}
