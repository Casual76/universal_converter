package com.p2r3.convert.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.p2r3.convert.data.Settings
import com.p2r3.convert.data.ThemeMode
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.antigravity.fluidengine.foundation.ThemeMode as EngineThemeMode

/**
 * L'indaco dell'app, nelle due versioni che gli servono.
 *
 * Non e' lo stesso colore usato due volte: su fondo chiaro e su fondo scuro lo stesso RGB non
 * mantiene ne' il carattere ne' il contrasto. Da questa coppia l'engine deriva l'intera scala di
 * superfici, quindi cambiare qui cambia tutta l'app in modo coerente.
 */
val ConvertBrand = AccentPreset(
    name = "convert",
    label = "Convert",
    light = Color(0xFF3B5BDB),
    dark = Color(0xFFB6C4FF),
)

/**
 * Il tema dell'app, costruito sul Fluid Engine.
 *
 * Prende le [Settings] dell'app e le traduce al bordo con [ConvertAppearance]: l'app tiene il suo
 * modello (e le sue chiavi su DataStore, quelle della 2.x), l'engine riceve il suo.
 */
@Composable
fun ConvertTheme(
    settings: Settings,
    content: @Composable () -> Unit,
) {
    val engineSettings = remember(settings) { ConvertAppearance.toEngine(settings) }
    FluidTheme(
        settings = engineSettings,
        brand = ConvertBrand,
        content = content,
    )
}

/**
 * Le impostazioni dell'app e quelle dell'engine, nei due versi.
 *
 * Pura, e per questo provata a parte: e' il punto in cui un'installazione che si aggiorna rischia di
 * ritrovarsi un tema diverso da quello che aveva scelto.
 *
 *  * «Colori dinamici» della 2.x e' l'accento: acceso = colori di sistema, spento = l'indaco.
 *    L'engine li tiene in due campi (`accentMode` e `dynamicColorEnabled`), qui sono uno solo.
 *  * Il nero vero e' `amoledEnabled`; il tema `AMOLED` dell'engine (che la sezione «Aspetto» non offre)
 *    si legge come «Scuro» con il nero acceso.
 *  * I preset dell'engine non ci sono: l'app ha il suo marchio e i colori di sistema, nient'altro, e un
 *    `CUSTOM_PRESET` arrivato da chissa' dove torna al marchio.
 */
object ConvertAppearance {

    fun toEngine(settings: Settings): EngineSettings = EngineSettings(
        themeMode = when (settings.themeMode) {
            ThemeMode.System -> EngineThemeMode.SYSTEM
            ThemeMode.Light -> EngineThemeMode.LIGHT
            ThemeMode.Dark -> EngineThemeMode.DARK
        },
        // L'engine tiene separati "quale sorgente per l'accento" e "il colore dinamico e' permesso":
        // con il dinamico spento si torna all'indaco dell'app invece che a un preset qualsiasi.
        accentMode = if (settings.dynamicColor) AccentMode.DYNAMIC else AccentMode.BRAND,
        dynamicColorEnabled = settings.dynamicColor,
        amoledEnabled = settings.pureBlack,
        hapticsEnabled = settings.haptics,
    )

    /** Quello che la sezione «Aspetto» ha scritto, riportato nelle impostazioni dell'app (il resto resta com'era). */
    fun fromEngine(current: Settings, engine: EngineSettings): Settings = current.copy(
        themeMode = when (engine.themeMode) {
            EngineThemeMode.SYSTEM -> ThemeMode.System
            EngineThemeMode.LIGHT -> ThemeMode.Light
            EngineThemeMode.DARK, EngineThemeMode.AMOLED -> ThemeMode.Dark
        },
        pureBlack = engine.amoledEnabled || engine.themeMode == EngineThemeMode.AMOLED,
        dynamicColor = engine.accentMode == AccentMode.DYNAMIC && engine.dynamicColorEnabled,
        haptics = engine.hapticsEnabled,
    )
}
