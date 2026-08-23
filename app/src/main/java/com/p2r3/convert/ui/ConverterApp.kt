package com.p2r3.convert.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p2r3.convert.data.formatSize
import com.p2r3.convert.engine.ConversionStage
import com.p2r3.convert.engine.EngineStatus
import com.p2r3.convert.engine.FailureReason
import com.p2r3.convert.engine.FormatOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(
    viewModel: ConverterViewModel,
    onOpenSettings: () -> Unit,
    onPreview: (Int) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val formats by viewModel.formats.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var picking by remember { mutableStateOf<PickerTarget?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.onFilesPicked(uris) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Convert to it!") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Impostazioni")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            EngineBanner(engineStatus, formats.size)

            FileCard(
                files = state.files,
                onPick = { filePicker.launch(arrayOf("*/*")) },
                onClear = viewModel::clearFiles
            )

            FormatRow(
                source = state.source,
                target = state.target,
                enabled = engineStatus == EngineStatus.Ready,
                onPickSource = { picking = PickerTarget.Source },
                onPickTarget = { picking = PickerTarget.Target }
            )

            Button(
                onClick = viewModel::convert,
                enabled = state.canConvert,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Converti", style = MaterialTheme.typography.titleMedium)
            }

            PhaseSection(viewModel, state, onPreview)
        }
    }

    picking?.let { target ->
        FormatPickerSheet(
            formats = formats,
            direction = target,
            simpleMode = settings.simpleMode,
            preselected = if (target == PickerTarget.Source) state.source else state.target,
            onDismiss = { picking = null },
            onSelect = { option ->
                if (target == PickerTarget.Source) viewModel.selectSource(option)
                else viewModel.selectTarget(option)
                picking = null
            }
        )
    }
}

enum class PickerTarget { Source, Target }

/* -------------------------------------------------------------------------- */
/* Pieces                                                                      */
/* -------------------------------------------------------------------------- */

@Composable
private fun EngineBanner(status: EngineStatus, formatCount: Int) {
    AnimatedVisibility(
        visible = status != EngineStatus.Ready,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (status) {
                            EngineStatus.Broken -> "Il motore non è partito"
                            else -> "Avvio del motore…"
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        when (status) {
                            EngineStatus.Broken -> "Riapri l'app; se persiste, controlla il log nelle impostazioni."
                            else -> "Carico l'elenco dei formati."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (status != EngineStatus.Broken) {
                    LinearProgressIndicator(modifier = Modifier.width(72.dp))
                }
            }
        }
    }
    if (status == EngineStatus.Ready && formatCount > 0) {
        Text(
            "$formatCount formati disponibili",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FileCard(
    files: List<com.p2r3.convert.data.PickedFile>,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        onClick = onPick,
        modifier = Modifier.fillMaxWidth(),
        shape = ContinuousCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (files.isEmpty()) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (files.isEmpty()) Icons.Rounded.UploadFile else Icons.AutoMirrored.Rounded.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (files.isEmpty()) {
                    Text("Scegli un file", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Qualsiasi formato, anche più file insieme",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        files.first().name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            append(formatSize(files.sumOf { it.size }))
                            if (files.size > 1) append(" · ${files.size} file")
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (files.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.Close, contentDescription = "Rimuovi")
                }
            }
        }
    }
}

@Composable
private fun FormatRow(
    source: FormatOption?,
    target: FormatOption?,
    enabled: Boolean,
    onPickSource: () -> Unit,
    onPickTarget: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FormatSlot("Da", source, enabled, Modifier.weight(1f), onPickSource)
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FormatSlot("A", target, enabled, Modifier.weight(1f), onPickTarget)
    }
}

@Composable
private fun FormatSlot(
    label: String,
    option: FormatOption?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val container by animateColorAsState(
        if (option != null) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainer,
        label = "slot"
    )
    Surface(
        modifier = modifier
            .height(96.dp)
            .clip(ContinuousCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = container
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                option?.format?.uppercase() ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                option?.name.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PhaseSection(
    viewModel: ConverterViewModel,
    state: ConverterState,
    onPreview: (Int) -> Unit
) {
    val context = LocalContext.current
    val assetDownload by viewModel.assetDownload.collectAsStateWithLifecycle()

    when (val phase = state.phase) {

        is ConversionPhase.Running -> ConversionProgressCard(
            progress = phase.progress,
            download = assetDownload,
            onCancel = viewModel::cancelConversion
        )

        is ConversionPhase.Done -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Conversione completata", style = MaterialTheme.typography.titleMedium)
                }
                if (phase.path.size > 1) {
                    Text(
                        "Percorso: ${phase.path.joinToString("  →  ")}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                phase.files.forEachIndexed { index, file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPreview(index) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatSize(file.size), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { onPreview(index) }) {
                            Icon(Icons.Rounded.Visibility, contentDescription = "Anteprima")
                        }
                        IconButton(onClick = {
                            viewModel.openIntent(file)?.let { runCatching { context.startActivity(it) } }
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Apri con")
                        }
                    }
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = viewModel::saveAll, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (phase.saved > 0) "Salvato" else "Salva")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.shareIntent()?.let { runCatching { context.startActivity(it) } }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Condividi")
                    }
                }
                if (phase.saved > 0) {
                    Text(
                        "${phase.saved} file in Download/Convert",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        is ConversionPhase.Error -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (phase.reason) {
                        FailureReason.NoPath -> "Nessun percorso di conversione"
                        FailureReason.Cancelled -> "Conversione annullata"
                        FailureReason.Download -> "Download del motore non riuscito"
                        FailureReason.Engine -> "Errore del motore"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    when (phase.reason) {
                        FailureReason.NoPath ->
                            "Il motore non riesce a collegare questi due formati, nemmeno passando per formati intermedi."
                        FailureReason.Download ->
                            "Serve connessione per scaricare questo motore la prima volta."
                        else -> phase.detail
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = viewModel::dismissResult) { Text("Chiudi") }
            }
        }

        ConversionPhase.Idle -> Unit
    }
}
