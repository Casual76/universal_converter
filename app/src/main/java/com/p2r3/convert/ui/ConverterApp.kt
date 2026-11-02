package com.p2r3.convert.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p2r3.convert.data.PickedFile
import com.p2r3.convert.data.formatSize
import com.p2r3.convert.engine.ConvertedFile
import com.p2r3.convert.engine.EngineStatus
import com.p2r3.convert.engine.FailureReason
import com.p2r3.convert.engine.FormatOption
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidGlassIconButton
import dev.antigravity.fluidengine.ui.fluid.FluidGlassSize
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.FluidTextStyles
import dev.antigravity.fluidengine.ui.fluid.fluidExpandOrigin
import dev.antigravity.fluidengine.ui.fluid.fluidGlassGroups
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone

/**
 * La pagina del convertitore: un file, due formati, un tasto, e quello che succede dopo.
 *
 * Tutta sul vocabolario dell'engine 3.0, come la galleria Fluid Glass: la pagina ha un fondale
 * ambientale e il contenuto e' vetro che lo rifrange (il file in un gruppo, i due formati in due
 * tessere, l'avanzamento e il risultato in pannelli). I due formati sono le ancore del selettore: la
 * tessera toccata **diventa** il pannello e ci si richiude dentro, invece di un foglio che arriva da
 * sotto e non ha niente a che fare con il dito.
 */
@Composable
fun ConverterScreen(
    viewModel: ConverterViewModel,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
    onPreview: (Int) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val formats by viewModel.formats.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val download by viewModel.assetDownload.collectAsStateWithLifecycle()

    var picking by remember { mutableStateOf<PickerTarget?>(null) }
    // Dove stanno le due tessere, per far nascere il selettore da quella toccata.
    var sourceRect by remember { mutableStateOf<Rect?>(null) }
    var targetRect by remember { mutableStateOf<Rect?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.onFilesPicked(uris) }
    val pickFiles = { filePicker.launch(arrayOf("*/*")) }

    val context = LocalContext.current
    val ready = engineStatus == EngineStatus.Ready

    FluidScreen(
        title = "Convert to it!",
        subtitle = if (formats.isEmpty()) "Il motore si sta avviando" else "${formats.size} formati disponibili",
        actions = {
            FluidBarAction(
                icon = Icons.Rounded.Settings,
                contentDescription = "Impostazioni",
                onClick = onOpenSettings,
            )
        },
        ambient = remember { FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Ripples) },
    ) {
        if (engineStatus != EngineStatus.Ready) {
            item(key = "engine") {
                EngineNotice(status = engineStatus, onOpenLog = onOpenLog, modifier = Modifier.animateItem())
            }
        }

        item(key = "files") {
            FileGroup(files = state.files, source = state.source, onPick = pickFiles, onClear = viewModel::clearFiles)
        }

        item(key = "formats") {
            FormatRow(
                source = state.source,
                target = state.target,
                enabled = ready,
                onPickSource = { picking = PickerTarget.Source },
                onPickTarget = { picking = PickerTarget.Target },
                onSourcePlaced = { sourceRect = it },
                onTargetPlaced = { targetRect = it },
            )
        }

        item(key = "convert") {
            FluidButton(
                text = "Converti",
                onClick = viewModel::convert,
                // Con un risultato in pagina il tasto principale e' «Salva»: due tasti pieni uno sopra
                // l'altro non dicono quale dei due e' il prossimo passo.
                style = if (state.phase is ConversionPhase.Done) FluidButtonStyle.Tinted else FluidButtonStyle.Filled,
                enabled = state.canConvert,
                loading = state.phase is ConversionPhase.Running,
                size = FluidButtonSize.Large,
                fillWidth = true,
                leading = { Icon(Icons.Rounded.Bolt, contentDescription = null) },
            )
        }

        if (state.files.isEmpty() && state.phase == ConversionPhase.Idle) {
            item(key = "hint") {
                FluidSectionFootnote(
                    text = "Puoi anche mandare un file a Convert to it! da un'altra app, con «Condividi».",
                    modifier = Modifier.animateItem(),
                )
            }
        }

        phaseItems(
            phase = state.phase,
            download = download,
            target = state.target,
            onCancel = viewModel::cancelConversion,
            onDismiss = viewModel::dismissResult,
            onPreview = onPreview,
            onOpen = { file -> viewModel.openIntent(file)?.let { runCatching { context.startActivity(it) } } },
            onShareOne = { file -> viewModel.shareIntent(listOf(file))?.let { runCatching { context.startActivity(it) } } },
            onSaveAll = viewModel::saveAll,
            onShareAll = { viewModel.shareIntent()?.let { runCatching { context.startActivity(it) } } },
        )
    }

    FormatPicker(
        target = picking,
        origin = { if (picking == PickerTarget.Source) sourceRect else targetRect },
        formats = formats,
        simpleMode = settings.simpleMode,
        preselected = { side -> if (side == PickerTarget.Source) state.source else state.target },
        onDismiss = { picking = null },
        onSelect = { side, option ->
            if (side == PickerTarget.Source) viewModel.selectSource(option) else viewModel.selectTarget(option)
            picking = null
        },
    )
}

/* -------------------------------------------------------------------------- */
/* Pieces                                                                      */
/* -------------------------------------------------------------------------- */

/** Il motore che si accende, o che non e' partito: un messaggio, mai una scheda colorata a mano. */
@Composable
private fun EngineNotice(status: EngineStatus, onOpenLog: () -> Unit, modifier: Modifier = Modifier) {
    if (status == EngineStatus.Broken) {
        FluidInlineMessage(
            title = "Il motore non è partito",
            message = "Riapri l'app; se il problema resta, il log dice cosa è successo.",
            tone = FluidTone.Danger,
            onClick = onOpenLog,
            action = "Apri il log",
            leading = { Icon(Icons.Rounded.ErrorOutline, contentDescription = null) },
            modifier = modifier,
        )
    } else {
        FluidInlineMessage(
            title = "Avvio del motore…",
            message = "Carico l'elenco dei formati.",
            tone = FluidTone.Info,
            leading = { FluidSpinner() },
            modifier = modifier,
        )
    }
}

/**
 * Il file da convertire: una riga in un gruppo di vetro.
 *
 * Toccata sceglie (o cambia) il file; la crocetta lo toglie. Tenuta premuta offre le stesse due cose
 * in un menu, per chi non sa che la riga si tocca.
 */
@Composable
private fun FileGroup(files: List<PickedFile>, source: FormatOption?, onPick: () -> Unit, onClear: () -> Unit) {
    FluidListGroup(glass = true) {
        if (files.isEmpty()) {
            FluidListRow(
                title = "Scegli un file",
                subtitle = "Qualsiasi formato, anche più file insieme",
                tone = FluidTone.Primary,
                leading = { Icon(Icons.Rounded.UploadFile, contentDescription = null) },
                onClick = onPick,
            )
        } else {
            val first = files.first()
            FluidListRow(
                title = if (files.size > 1) "${first.name} e altri ${files.size - 1}" else first.name,
                subtitle = buildString {
                    append(formatSize(files.sumOf { it.size }))
                    if (files.size > 1) append(" · ${files.size} file")
                    append(" · tocca per cambiarlo")
                },
                titleMaxLines = 1,
                // La piastrella dice gia' che file e': l'icona e il colore della sua famiglia, gli stessi
                // del selettore.
                tone = source?.let { FormatCatalog.groupTone(it.group) } ?: FluidTone.Primary,
                leading = { Icon(formatGroupIcon(source?.group), contentDescription = null) },
                onClick = onPick,
                disclosure = false,
                contextActions = {
                    listOf(
                        FluidContextAction(label = "Scegli un altro file", icon = Icons.Rounded.SwapHoriz, onClick = onPick),
                        FluidContextAction(label = "Togli", icon = Icons.Rounded.Close, destructive = true, onClick = onClear),
                    )
                },
                badge = {
                    FluidGlassIconButton(onClick = onClear, size = FluidGlassSize.Compact) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Togli il file",
                            modifier = Modifier.size(FluidGlassSize.Compact.iconSize),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun FormatRow(
    source: FormatOption?,
    target: FormatOption?,
    enabled: Boolean,
    onPickSource: () -> Unit,
    onPickTarget: () -> Unit,
    onSourcePlaced: (Rect) -> Unit,
    onTargetPlaced: (Rect) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormatTile(
            label = "Da",
            option = source,
            enabled = enabled,
            modifier = Modifier.weight(1f).fluidExpandOrigin(onMeasured = onSourcePlaced),
            onClick = onPickSource,
        )
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormatTile(
            label = "A",
            option = target,
            enabled = enabled,
            modifier = Modifier.weight(1f).fluidExpandOrigin(onMeasured = onTargetPlaced),
            onClick = onPickTarget,
        )
    }
}

/**
 * Una delle due tessere: la parola piccola, il formato grande, il suo nome per esteso.
 *
 * E' una card di vetro che cede sotto il dito, non un tasto: e' larga mezza pagina, e crescendo
 * invaderebbe la sua gemella. Con il motore spento non si tocca (l'elenco dei formati non c'e'
 * ancora), e lo dice con il colore del testo, non con un velo sopra il vetro.
 */
@Composable
private fun FormatTile(
    label: String,
    option: FormatOption?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    FluidCard(
        modifier = modifier,
        glass = true,
        highlighted = option != null,
        onClick = if (enabled) onClick else null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label.uppercase(), style = FluidTextStyles.uppercaseCaption, color = quiet)
            Text(
                option?.format?.uppercase() ?: "Scegli",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (option == null || !enabled) quiet else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                option?.name ?: if (enabled) "un formato" else "il motore si avvia",
                style = MaterialTheme.typography.bodySmall,
                color = quiet,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Quello che succede dopo «Converti», come elementi della lista e non come un blocco unico: il
 * risultato di un lotto di cinquanta file e' cinquanta righe, e una colonna sola alta come cinquanta
 * righe e' il nodo che smette di ricevere i tocchi e il pannello che arriva nero (`fluidGlassGroups`).
 */
private fun LazyListScope.phaseItems(
    phase: ConversionPhase,
    download: com.p2r3.convert.engine.AssetDownload?,
    target: FormatOption?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onPreview: (Int) -> Unit,
    onOpen: (ConvertedFile) -> Unit,
    onShareOne: (ConvertedFile) -> Unit,
    onSaveAll: () -> Unit,
    onShareAll: () -> Unit,
) {
    when (phase) {
        is ConversionPhase.Running -> item(key = "running") {
            ConversionProgressCard(
                progress = phase.progress,
                download = download,
                onCancel = onCancel,
                modifier = Modifier.animateItem(),
            )
        }

        is ConversionPhase.Done -> {
            item(key = "result-header") {
                FluidSectionHeader(
                    title = "Risultato",
                    detail = buildString {
                        append(if (phase.files.size == 1) "1 file" else "${phase.files.size} file")
                        if (phase.path.size > 1) append(" · ${phase.path.joinToString(" → ") { it.uppercase() }}")
                    },
                    modifier = Modifier.animateItem(),
                )
            }
            val tone = target?.let { FormatCatalog.groupTone(it.group) } ?: FluidTone.Primary
            val icon = formatGroupIcon(target?.group)
            fluidGlassGroups(items = phase.files.withIndex().toList(), key = "result") { (index, file) ->
                FluidListRow(
                    title = file.name,
                    subtitle = formatSize(file.size),
                    titleMaxLines = 1,
                    tone = tone,
                    leading = { Icon(icon, contentDescription = null) },
                    onClick = { onPreview(index) },
                    contextActions = {
                        listOf(
                            FluidContextAction(label = "Anteprima", icon = Icons.Rounded.Visibility) { onPreview(index) },
                            FluidContextAction(label = "Apri con…", icon = Icons.AutoMirrored.Rounded.OpenInNew) { onOpen(file) },
                            FluidContextAction(label = "Condividi", icon = Icons.Rounded.Share) { onShareOne(file) },
                        )
                    },
                )
            }
            item(key = "result-actions") {
                val allSaved = phase.saved >= phase.files.size
                Row(
                    modifier = Modifier.fillMaxWidth().animateItem(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FluidButton(
                        text = if (phase.saved > 0) "Salvato" else "Salva",
                        onClick = onSaveAll,
                        modifier = Modifier.weight(1f),
                        style = FluidButtonStyle.Filled,
                        size = FluidButtonSize.Large,
                        enabled = !allSaved,
                        fillWidth = true,
                        leading = {
                            Icon(if (phase.saved > 0) Icons.Rounded.Check else Icons.Rounded.Download, contentDescription = null)
                        },
                    )
                    FluidButton(
                        text = "Condividi",
                        onClick = onShareAll,
                        modifier = Modifier.weight(1f),
                        style = FluidButtonStyle.Tinted,
                        size = FluidButtonSize.Large,
                        fillWidth = true,
                        leading = { Icon(Icons.Rounded.Share, contentDescription = null) },
                    )
                }
            }
            item(key = "result-note") {
                FluidSectionFootnote(
                    text = if (phase.saved > 0) {
                        "${phase.saved} file in Download/Convert. Tocca un file per vederlo accanto all'originale, " +
                            "tienilo premuto per aprirlo o condividerlo."
                    } else {
                        "Tocca un file per vederlo accanto all'originale, tienilo premuto per aprirlo o condividerlo."
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        is ConversionPhase.Error -> item(key = "error") {
            FluidInlineMessage(
                title = when (phase.reason) {
                    FailureReason.NoPath -> "Nessun percorso di conversione"
                    FailureReason.Cancelled -> "Conversione annullata"
                    FailureReason.Download -> "Download del motore non riuscito"
                    FailureReason.Engine -> "Errore del motore"
                },
                message = when (phase.reason) {
                    FailureReason.NoPath ->
                        "Il motore non riesce a collegare questi due formati, nemmeno passando per formati intermedi."
                    FailureReason.Download ->
                        "Serve connessione per scaricare questo motore la prima volta."
                    else -> phase.detail.ifBlank { "Il log del motore dice cosa è successo." }
                },
                tone = if (phase.reason == FailureReason.Cancelled) FluidTone.Neutral else FluidTone.Danger,
                leading = { Icon(Icons.Rounded.ErrorOutline, contentDescription = null) },
                onDismiss = onDismiss,
                modifier = Modifier.animateItem(),
            )
        }

        ConversionPhase.Idle -> Unit
    }
}
