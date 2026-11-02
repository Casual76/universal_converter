package com.p2r3.convert.ui

import com.p2r3.convert.data.Settings
import com.p2r3.convert.data.ThemeMode
import com.p2r3.convert.ui.theme.ConvertAppearance
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.antigravity.fluidengine.foundation.ThemeMode as EngineThemeMode

/**
 * Le impostazioni dell'app tradotte per l'engine e ritorno: il punto in cui un'installazione 2.x che
 * si aggiorna rischierebbe di ritrovarsi un tema diverso da quello che aveva scelto.
 */
class ConvertAppearanceTest {

    @Test
    fun `the 2_x defaults become the engine defaults with the dynamic accent`() {
        val engine = ConvertAppearance.toEngine(Settings())
        assertEquals(EngineThemeMode.SYSTEM, engine.themeMode)
        assertEquals(AccentMode.DYNAMIC, engine.accentMode)
        assertTrue(engine.dynamicColorEnabled)
        assertFalse(engine.amoledEnabled)
        assertTrue(engine.hapticsEnabled)
    }

    @Test
    fun `a 2_x user with dark theme and no dynamic colour keeps both`() {
        val old = Settings(themeMode = ThemeMode.Dark, dynamicColor = false)
        val engine = ConvertAppearance.toEngine(old)
        assertEquals(EngineThemeMode.DARK, engine.themeMode)
        assertEquals(AccentMode.BRAND, engine.accentMode)
        assertFalse(engine.dynamicColorEnabled)
    }

    @Test
    fun `every app setting survives a round trip through the engine`() {
        for (theme in ThemeMode.entries) {
            for (dynamic in listOf(true, false)) {
                for (black in listOf(true, false)) {
                    for (haptics in listOf(true, false)) {
                        val settings = Settings(
                            simpleMode = false,
                            themeMode = theme,
                            dynamicColor = dynamic,
                            autoSave = true,
                            pureBlack = black,
                            haptics = haptics,
                        )
                        val back = ConvertAppearance.fromEngine(settings, ConvertAppearance.toEngine(settings))
                        assertEquals(settings, back)
                    }
                }
            }
        }
    }

    @Test
    fun `choosing the brand accent turns the dynamic colour off`() {
        val current = Settings(dynamicColor = true)
        val engine = ConvertAppearance.toEngine(current).copy(accentMode = AccentMode.BRAND)
        assertFalse(ConvertAppearance.fromEngine(current, engine).dynamicColor)
    }

    @Test
    fun `choosing the system colours turns the dynamic colour on`() {
        val current = Settings(dynamicColor = false)
        val engine = ConvertAppearance.toEngine(current)
            .copy(accentMode = AccentMode.DYNAMIC, dynamicColorEnabled = true)
        assertTrue(ConvertAppearance.fromEngine(current, engine).dynamicColor)
    }

    @Test
    fun `the engine AMOLED theme reads as dark with pure black`() {
        val back = ConvertAppearance.fromEngine(Settings(), EngineSettings(themeMode = EngineThemeMode.AMOLED))
        assertEquals(ThemeMode.Dark, back.themeMode)
        assertTrue(back.pureBlack)
    }

    @Test
    fun `an engine preset the app does not offer falls back to the brand`() {
        val engine = EngineSettings(accentMode = AccentMode.CUSTOM_PRESET, customAccentName = "fluid")
        assertFalse(ConvertAppearance.fromEngine(Settings(), engine).dynamicColor)
    }

    @Test
    fun `the appearance never touches the conversion settings`() {
        val current = Settings(simpleMode = false, autoSave = true)
        val back = ConvertAppearance.fromEngine(current, EngineSettings(themeMode = EngineThemeMode.LIGHT))
        assertFalse(back.simpleMode)
        assertTrue(back.autoSave)
    }
}
