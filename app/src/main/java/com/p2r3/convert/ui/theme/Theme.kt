package com.p2r3.convert.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
private val ConvertBrand = AccentPreset(
    name = "convert",
    label = "Convert",
    light = Color(0xFF3B5BDB),
    dark = Color(0xFFB6C4FF),
)

/**
 * Il tema dell'app, ora costruito sul Fluid Engine.
 *
 * La firma resta identica di proposito: il punto di chiamata non sa che sotto e' cambiato tutto, e
 * tornare indietro e' un `git checkout` di questo solo file. Quello che arriva in cambio delle due
 * palette scritte a mano che c'erano prima: angoli continui, la scala tipografica iOS, il motion
 * scheme condiviso, AMOLED e la stessa identita' visiva delle altre app costruite sull'engine.
 */
@Composable
fun ConvertTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    FluidTheme(
        settings = EngineSettings(
            themeMode = themeMode.toEngine(),
            // L'engine tiene separati "quale sorgente per l'accento" e "il colore dinamico e'
            // permesso": con il dinamico spento si torna all'indaco dell'app invece che a un preset
            // qualsiasi.
            accentMode = if (dynamicColor) AccentMode.DYNAMIC else AccentMode.BRAND,
            dynamicColorEnabled = dynamicColor,
        ),
        brand = ConvertBrand,
        content = content,
    )
}

private fun ThemeMode.toEngine(): EngineThemeMode = when (this) {
    ThemeMode.System -> EngineThemeMode.SYSTEM
    ThemeMode.Light -> EngineThemeMode.LIGHT
    ThemeMode.Dark -> EngineThemeMode.DARK
}
