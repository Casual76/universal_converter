package com.p2r3.convert.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p2r3.convert.BuildConfig
import com.p2r3.convert.data.ThemeMode
import com.p2r3.convert.data.formatSize
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow

/**
 * Le impostazioni, sui componenti del Fluid Engine.
 *
 * [FluidScreen] non e' solo un contenitore con un titolo: porta con se' il titolo grande che si
 * ritira nella barra mentre si scorre, la barra in vetro che sfoca quello che le passa sotto, e il
 * bordo elastico in fondo alla lista. Sono le tre cose che prima mancavano, e nessuna delle tre
 * arriva dal tema — arrivano dall'uso di questo componente al posto di uno `Scaffold`.
 */
@Composable
fun SettingsScreen(
    viewModel: ConverterViewModel,
    onBack: () -> Unit,
    onOpenLog: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pack by viewModel.packState.collectAsStateWithLifecycle()
    val download by viewModel.assetDownload.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshPackState() }

    FluidScreen(
        title = "Impostazioni",
        onBack = onBack,
    ) {
        item(key = "aspetto-header") { FluidSectionHeader(title = "Aspetto") }
        item(key = "aspetto") {
            FluidListGroup {
                // Il controllo segmentato al posto dei chip: tre scelte che si escludono sono un
                // interruttore a tre posizioni, non tre bottoni che si spuntano a vicenda.
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Tema", style = MaterialTheme.typography.titleMedium)
                    FluidSegmentedControl(
                        options = ThemeMode.entries.toList(),
                        selected = settings.themeMode,
                        onSelect = viewModel::setThemeMode,
                        label = { it.label },
                    )
                }
                FluidListDivider()
                FluidListRow(
                    title = "Colori dinamici",
                    subtitle = "Segui la palette dello sfondo del telefono",
                    badge = {
                        FluidSwitch(
                            checked = settings.dynamicColor,
                            onCheckedChange = viewModel::setDynamicColor,
                        )
                    },
                )
            }
        }

        item(key = "conversione-header") { FluidSectionHeader(title = "Conversione") }
        item(key = "conversione") {
            FluidListGroup {
                FluidListRow(
                    title = "Modalità semplice",
                    subtitle = "Il motore sceglie da solo lo strumento migliore. " +
                        "Disattivala per selezionare a mano il singolo handler.",
                    badge = {
                        FluidSwitch(
                            checked = settings.simpleMode,
                            onCheckedChange = viewModel::setSimpleMode,
                        )
                    },
                )
                FluidListDivider()
                FluidListRow(
                    title = "Salva automaticamente",
                    subtitle = "Scrive i risultati in Download/Convert appena pronti",
                    badge = {
                        FluidSwitch(
                            checked = settings.autoSave,
                            onCheckedChange = viewModel::setAutoSave,
                        )
                    },
                )
            }
        }

        item(key = "motori-header") {
            FluidSectionHeader(
                title = "Motori scaricabili",
                detail = "I motori pesanti (FFmpeg, Pandoc, Typst, ImageMagick) non sono nell'app: " +
                    "vengono scaricati la prima volta che servono e restano sul telefono.",
            )
        }
        item(key = "motori") {
            FluidListGroup {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "${pack.downloadedCount} di ${pack.fileCount} scaricati - " +
                            "${formatSize(pack.downloadedBytes)} di ${formatSize(pack.totalBytes)}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (pack.totalBytes > 0) {
                                pack.downloadedBytes.toFloat() / pack.totalBytes
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    download?.let {
                        Text(
                            "In corso: ${it.name} - ${formatSize(it.downloaded)} / ${formatSize(it.total)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FluidButton(
                            text = "Scarica tutto",
                            onClick = viewModel::downloadEverything,
                            modifier = Modifier.weight(1f),
                            style = FluidButtonStyle.Tinted,
                        )
                        FluidButton(
                            text = "Libera spazio",
                            onClick = viewModel::clearDownloads,
                            modifier = Modifier.weight(1f),
                            style = FluidButtonStyle.Plain,
                        )
                    }
                }
            }
        }

        item(key = "info-header") { FluidSectionHeader(title = "Informazioni") }
        item(key = "info") {
            FluidListGroup {
                FluidListRow(
                    title = "Log del motore",
                    subtitle = "Cosa sta facendo il motore, riga per riga",
                    onClick = onOpenLog,
                )
                FluidListDivider()
                FluidListRow(title = "Versione app", subtitle = BuildConfig.VERSION_NAME)
                FluidListDivider()
                FluidListRow(
                    title = "Motore di conversione",
                    subtitle = "p2r3/convert @ ${BuildConfig.ENGINE_VERSION}",
                )
            }
        }

        item(key = "crediti-header") {
            FluidSectionHeader(
                title = "Crediti e licenza",
                detail = "Le conversioni le fa \"Convert to it!\" di PortalRunner (p2r3) e dei suoi " +
                    "collaboratori, usato senza modifiche. Questa app è la scocca nativa che ci sta " +
                    "intorno: il merito dei formati supportati e' loro.",
            )
        }
        item(key = "crediti") {
            FluidListGroup {
                FluidListRow(
                    title = "Progetto originale",
                    subtitle = ENGINE_REPO,
                    onClick = { openUrl(context, ENGINE_REPO) },
                )
                FluidListDivider()
                FluidListRow(
                    title = "Licenza GNU GPL v2",
                    subtitle = "Il motore è GPL-2.0, quindi lo è anche questa app: " +
                        "puoi usarla, studiarla, modificarla e ridistribuirla.",
                    onClick = { openUrl(context, LICENSE_URL) },
                )
                FluidListDivider()
                FluidListRow(
                    title = "Codice sorgente di questa app",
                    subtitle = APP_REPO,
                    onClick = { openUrl(context, APP_REPO) },
                )
            }
        }
    }
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.System -> "Sistema"
        ThemeMode.Light -> "Chiaro"
        ThemeMode.Dark -> "Scuro"
    }

private const val ENGINE_REPO = "https://github.com/p2r3/convert"
private const val APP_REPO = "https://github.com/Casual76/universal_converter"
private const val LICENSE_URL = "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html"

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
