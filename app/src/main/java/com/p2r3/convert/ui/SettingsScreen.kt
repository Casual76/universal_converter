package com.p2r3.convert.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p2r3.convert.BuildConfig
import com.p2r3.convert.data.ThemeMode
import com.p2r3.convert.data.formatSize

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Impostazioni") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {

            SectionTitle("Aspetto")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.System -> "Sistema"
                                    ThemeMode.Light -> "Chiaro"
                                    ThemeMode.Dark -> "Scuro"
                                }
                            )
                        }
                    )
                }
            }

            SwitchRow(
                title = "Colori dinamici",
                subtitle = "Segui la palette dello sfondo del telefono",
                checked = settings.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor
            )

            SectionTitle("Conversione")

            SwitchRow(
                title = "Modalità semplice",
                subtitle = "Il motore sceglie da solo lo strumento migliore. " +
                    "Disattivala per selezionare a mano il singolo handler.",
                checked = settings.simpleMode,
                onCheckedChange = viewModel::setSimpleMode
            )

            SwitchRow(
                title = "Salva automaticamente",
                subtitle = "Scrive i risultati in Download/Convert appena pronti",
                checked = settings.autoSave,
                onCheckedChange = viewModel::setAutoSave
            )

            SectionTitle("Motori scaricabili")

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "I motori pesanti (FFmpeg, Pandoc, Typst, ImageMagick) non sono nell'app: " +
                            "vengono scaricati la prima volta che servono e restano sul telefono.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "${pack.downloadedCount} di ${pack.fileCount} scaricati · " +
                            "${formatSize(pack.downloadedBytes)} di ${formatSize(pack.totalBytes)}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (pack.totalBytes > 0) pack.downloadedBytes.toFloat() / pack.totalBytes else 0f
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    download?.let {
                        Text(
                            "In corso: ${it.name} · ${formatSize(it.downloaded)} / ${formatSize(it.total)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::downloadEverything,
                            modifier = Modifier.weight(1f)
                        ) { Text("Scarica tutto") }
                        TextButton(
                            onClick = viewModel::clearDownloads,
                            modifier = Modifier.weight(1f)
                        ) { Text("Libera spazio") }
                    }
                }
            }

            SectionTitle("Informazioni")

            ListItem(
                modifier = Modifier.clickable(onClick = onOpenLog),
                headlineContent = { Text("Log del motore") },
                supportingContent = { Text("Cosa sta facendo il motore, riga per riga") }
            )
            ListItem(
                headlineContent = { Text("Versione app") },
                supportingContent = { Text(BuildConfig.VERSION_NAME) }
            )
            ListItem(
                headlineContent = { Text("Motore di conversione") },
                supportingContent = { Text("p2r3/convert @ ${BuildConfig.ENGINE_VERSION}") }
            )

            SectionTitle("Crediti e licenza")

            Text(
                "Le conversioni le fa \"Convert to it!\" di PortalRunner (p2r3) e dei suoi " +
                    "collaboratori, usato senza modifiche. Questa app è la scocca nativa che " +
                    "ci sta intorno: il merito dei formati supportati è loro.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            ListItem(
                modifier = Modifier.clickable { openUrl(context, ENGINE_REPO) },
                headlineContent = { Text("Progetto originale") },
                supportingContent = { Text(ENGINE_REPO) }
            )
            ListItem(
                modifier = Modifier.clickable { openUrl(context, LICENSE_URL) },
                headlineContent = { Text("Licenza GNU GPL v2") },
                supportingContent = {
                    Text(
                        "Il motore è GPL-2.0, quindi lo è anche questa app: " +
                            "puoi usarla, studiarla, modificarla e ridistribuirla."
                    )
                }
            )
            ListItem(
                modifier = Modifier.clickable { openUrl(context, APP_REPO) },
                headlineContent = { Text("Codice sorgente di questa app") },
                supportingContent = { Text(APP_REPO) }
            )
        }
    }
}

private const val ENGINE_REPO = "https://github.com/p2r3/convert"
private const val APP_REPO = "https://github.com/Casual76/universal_converter"
private const val LICENSE_URL = "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html"

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}
