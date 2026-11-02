package com.p2r3.convert.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p2r3.convert.BuildConfig
import com.p2r3.convert.data.formatSize
import com.p2r3.convert.ui.theme.ConvertAppearance
import com.p2r3.convert.ui.theme.ConvertBrand
import dev.antigravity.fluidengine.foundation.EngineBuild
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidToggleRow
import dev.antigravity.fluidengine.ui.fluid.fluidAppearanceSection
import dev.antigravity.fluidengine.ui.fluid.fluidLicensesSection
import dev.antigravity.fluidengine.ui.fluid.fluidUpdateSection
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone

/**
 * Le impostazioni, sul vocabolario dell'engine 3.0.
 *
 * L'aspetto e' la sezione dell'engine ([fluidAppearanceSection]): tema in tre righe di scelta, nero
 * vero, accento (l'indaco di Convert o i colori di sistema, nessun preset) e aptica. Le due scelte
 * della conversione sono righe che *sono* l'interruttore, gli aggiornamenti sono la sezione
 * dell'engine, e in fondo ci sono i due programmi che stanno in questa app, ciascuno con la sua
 * licenza, prima dei crediti dell'engine.
 */
@Composable
fun SettingsScreen(
    viewModel: ConverterViewModel,
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pack by viewModel.packState.collectAsStateWithLifecycle()
    val download by viewModel.assetDownload.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val lastUpdateCheck by viewModel.lastUpdateCheck.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    // Il conto dei motori scaricati si rifa' all'apertura e a ogni file che inizia o finisce.
    LaunchedEffect(download?.name) { viewModel.refreshPackState() }

    val engineSettings = remember(settings) { ConvertAppearance.toEngine(settings) }

    FluidScreen(
        title = "Impostazioni",
        onBack = onBack,
        ambient = remember { FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Dots) },
    ) {
        fluidAppearanceSection(
            settings = engineSettings,
            onSettingsChange = viewModel::setAppearance,
            brand = ConvertBrand,
            presets = emptyList(),
            glass = true,
        )

        item(key = "conversione-header") { FluidSectionHeader(title = "Conversione") }
        item(key = "conversione") {
            FluidListGroup(glass = true) {
                FluidToggleRow(
                    title = "Modalità semplice",
                    subtitle = "Il motore sceglie da solo lo strumento migliore. " +
                        "Spenta, scegli a mano il singolo strumento per ogni formato.",
                    checked = settings.simpleMode,
                    onCheckedChange = viewModel::setSimpleMode,
                    tone = FluidTone.Primary,
                    leading = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                )
                FluidListDivider()
                FluidToggleRow(
                    title = "Salva automaticamente",
                    subtitle = "Scrive i risultati in Download/Convert appena pronti",
                    checked = settings.autoSave,
                    onCheckedChange = viewModel::setAutoSave,
                    tone = FluidTone.Success,
                    leading = { Icon(Icons.Rounded.Save, contentDescription = null) },
                )
            }
        }

        item(key = "motori-header") {
            FluidSectionHeader(
                title = "Motori scaricabili",
                detail = "I motori pesanti (FFmpeg, Pandoc, Typst, ImageMagick) non sono nell'app: " +
                    "si scaricano la prima volta che servono e restano sul telefono.",
            )
        }
        item(key = "motori") {
            val allThere = pack.fileCount > 0 && pack.downloadedCount >= pack.fileCount
            FluidListGroup(glass = true) {
                FluidListRow(
                    title = "${pack.downloadedCount} di ${pack.fileCount} scaricati",
                    subtitle = "${formatSize(pack.downloadedBytes)} di ${formatSize(pack.totalBytes)}",
                    tone = FluidTone.Info,
                    leading = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                    disclosure = false,
                )
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    FluidProgressBar(
                        progress = {
                            if (pack.totalBytes > 0) pack.downloadedBytes.toFloat() / pack.totalBytes else 0f
                        },
                    )
                }
                download?.let {
                    FluidListDivider()
                    FluidListRow(
                        title = "In corso: ${it.name}",
                        subtitle = "${formatSize(it.downloaded)} di ${formatSize(it.total)}",
                        disclosure = false,
                    )
                }
                FluidListDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FluidButton(
                        text = if (allThere) "Tutto scaricato" else "Scarica tutto",
                        onClick = viewModel::downloadEverything,
                        modifier = Modifier.weight(1f),
                        style = FluidButtonStyle.Tinted,
                        enabled = !allThere && download == null,
                        loading = download != null,
                        fillWidth = true,
                    )
                    FluidButton(
                        text = "Libera spazio",
                        onClick = { confirmClear = true },
                        modifier = Modifier.weight(1f),
                        style = FluidButtonStyle.Plain,
                        enabled = pack.downloadedBytes > 0 && download == null,
                        fillWidth = true,
                    )
                }
            }
        }

        fluidUpdateSection(
            state = update,
            onCheck = viewModel::checkForUpdate,
            onInstall = viewModel::installUpdate,
            lastCheckedAtMillis = lastUpdateCheck,
            // Una build di debug ha un altro id e un'altra firma: non si aggiorna da sola.
            supported = !BuildConfig.DEBUG,
            glass = true,
        )

        item(key = "info-header") { FluidSectionHeader(title = "Informazioni") }
        item(key = "info") {
            FluidListGroup(glass = true) {
                FluidListRow(
                    title = "Log del motore",
                    subtitle = "Cosa sta facendo il motore, riga per riga",
                    tone = FluidTone.Neutral,
                    leading = { Icon(Icons.AutoMirrored.Rounded.Article, contentDescription = null) },
                    onClick = onOpenLog,
                )
                FluidListDivider()
                FluidListRow(
                    title = "Versione",
                    subtitle = "Convert to it! ${BuildConfig.VERSION_NAME} · Fluid Engine ${EngineBuild.VERSION}",
                    disclosure = false,
                )
                FluidListDivider()
                FluidListRow(
                    title = "Motore di conversione",
                    subtitle = "p2r3/convert @ ${BuildConfig.ENGINE_VERSION}",
                    disclosure = false,
                )
            }
        }

        item(key = "programmi-header") {
            FluidSectionHeader(
                title = "Due programmi, due licenze",
                detail = "Questa app contiene due programmi distinti, ciascuno con la sua licenza e i suoi " +
                    "sorgenti. Si parlano solo scambiandosi messaggi: l'app chiede una conversione, il " +
                    "motore risponde con i file.",
            )
        }
        item(key = "programmi") {
            FluidListGroup(glass = true) {
                FluidListRow(
                    title = "L'app Convert to it!",
                    subtitle = "GNU GPL v3 · le schermate, i file, l'aspetto, il ponte verso Aria. " +
                        "Tocca per i sorgenti.",
                    tone = FluidTone.Primary,
                    leading = { Icon(Icons.Rounded.Android, contentDescription = null) },
                    onClick = { openUrl(context, APP_REPO) },
                )
                FluidListDivider()
                FluidListRow(
                    title = "Il motore di conversione",
                    subtitle = "GNU GPL v2 · \"Convert to it!\" di PortalRunner (p2r3) e collaboratori, " +
                        "con il nostro ponte headless; gira in una WebView invisibile. Il merito dei formati " +
                        "è loro. Tocca per i sorgenti.",
                    tone = FluidTone.Info,
                    leading = { Icon(Icons.Rounded.Memory, contentDescription = null) },
                    onClick = { openUrl(context, ENGINE_REPO) },
                )
                FluidListDivider()
                FluidListRow(
                    title = "Testo della GPL v3",
                    subtitle = "La licenza dell'app",
                    tone = FluidTone.Neutral,
                    leading = { Icon(Icons.Rounded.Gavel, contentDescription = null) },
                    onClick = { openUrl(context, GPL3_URL) },
                )
                FluidListDivider()
                FluidListRow(
                    title = "Testo della GPL v2",
                    subtitle = "La licenza del motore di conversione",
                    tone = FluidTone.Neutral,
                    leading = { Icon(Icons.Rounded.Gavel, contentDescription = null) },
                    onClick = { openUrl(context, GPL2_URL) },
                )
                FluidListDivider()
                FluidListRow(
                    title = "Dove stanno i sorgenti",
                    subtitle = "Nel repository dell'app: app/ è l'app (GPL v3), convert/ e engine/ sono il " +
                        "motore (GPL v2). Il README spiega come ricostruire l'APK.",
                    tone = FluidTone.Neutral,
                    leading = { Icon(Icons.Rounded.Code, contentDescription = null) },
                    onClick = { openUrl(context, APP_REPO) },
                )
            }
        }

        fluidLicensesSection(
            footnote = "Il Fluid Engine ${EngineBuild.VERSION} include queste opere di terze parti, fra cui " +
                "codice GPL-3.0 (LibrePods): per questo l'app è distribuita sotto GPL-3.0, con i sorgenti a " +
                "disposizione di chi la riceve. Il motore di conversione non ne fa parte: è il programma " +
                "separato qui sopra, sotto GPL-2.0, con i suoi sorgenti. Le licenze complete dell'engine " +
                "sono nel suo repository, in LICENSES/.",
        )
    }

    if (confirmClear) {
        FluidAlert(
            onDismissRequest = { confirmClear = false },
            title = "Liberare ${formatSize(pack.downloadedBytes)}?",
            message = "I motori scaricati si cancellano dal telefono. Torneranno a scaricarsi la prima volta " +
                "che servono, e senza connessione quelle conversioni non partiranno.",
            actions = listOf(
                FluidAlertAction("Annulla", onClick = { confirmClear = false }, emphasis = FluidAlertAction.Emphasis.Preferred),
                FluidAlertAction(
                    "Libera spazio",
                    onClick = {
                        confirmClear = false
                        viewModel.clearDownloads()
                    },
                    emphasis = FluidAlertAction.Emphasis.Destructive,
                ),
            ),
        )
    }
}

private const val ENGINE_REPO = "https://github.com/p2r3/convert"
private const val APP_REPO = "https://github.com/Casual76/universal_converter"
private const val GPL3_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
private const val GPL2_URL = "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html"

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
