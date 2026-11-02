package com.p2r3.convert.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.p2r3.convert.engine.FormatOption
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidSearchField
import dev.antigravity.fluidengine.ui.fluid.FluidChoiceRow
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider

/**
 * Il selettore dei formati: il pannello di vetro in cui si trasforma la tessera toccata.
 *
 * Un `FluidGlassModalPortal` a pop-up con l'origine sulla tessera: la tessera **diventa** il pannello
 * (la sagoma viaggia dal suo rettangolo con la rifrazione addosso) e scegliendo ci si richiude dentro,
 * e la tessera mostra il formato nuovo. E' la forma a oggetto del portale ([target]), mai quella a
 * `visible`: il pannello che esce deve restare intero, con il suo titolo, fino all'ultimo fotogramma.
 *
 * Dentro: la ricerca di vetro, le famiglie come chip che cedono il dito alla fila, e una lista pigra di
 * righe a scelta singola (fino a novecento voci in modalita' avanzata: piatte, sul vetro del pannello,
 * nessun vetro per riga). In modalita' semplice lo stesso formato offerto da piu' strumenti e' una riga
 * sola e lo strumento lo sceglie il motore; in avanzata ogni strumento ha la sua.
 */
@Composable
fun FormatPicker(
    target: PickerTarget?,
    origin: () -> Rect?,
    formats: List<FormatOption>,
    simpleMode: Boolean,
    preselected: (PickerTarget) -> FormatOption?,
    onDismiss: () -> Unit,
    onSelect: (PickerTarget, FormatOption) -> Unit,
) {
    FluidGlassModalPortal(
        item = target,
        onDismissRequest = onDismiss,
        origin = origin,
        presentation = FluidGlassModalPresentation.Popover,
        paneTitle = target?.let(::pickerTitle),
    ) { side ->
        // Il pannello del pop-up non da' un colore al contenuto (lo da' il foglio, non il pop-up):
        // senza, il titolo nel tema scuro e' nero su vetro scuro.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            FormatPickerPane(
                side = side,
                formats = formats,
                simpleMode = simpleMode,
                preselected = preselected(side),
                onSelect = { onSelect(side, it) },
            )
        }
    }
}

private fun pickerTitle(side: PickerTarget) =
    if (side == PickerTarget.Source) "Formato di partenza" else "Formato di destinazione"

@Composable
private fun FormatPickerPane(
    side: PickerTarget,
    formats: List<FormatOption>,
    simpleMode: Boolean,
    preselected: FormatOption?,
    onSelect: (FormatOption) -> Unit,
) {
    var query by remember(side) { mutableStateOf("") }
    var group by remember(side) { mutableStateOf<String?>(null) }

    val available = remember(formats, side, simpleMode) { FormatCatalog.available(formats, side, simpleMode) }
    val groups = remember(available) { FormatCatalog.groups(available) }
    val visible = remember(available, query, group) { FormatCatalog.visible(available, query, group) }

    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val dismissKeyboard = {
        keyboard?.hide()
        focus.clearFocus()
    }
    // Come in iOS: scrivere e poi scorrere chiude la tastiera. Il pannello non si accorcia sopra la
    // tastiera (il pop-up dell'host non segue l'IME), quindi senza questo la fine della lista restava
    // sotto i tasti.
    val hideOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) dismissKeyboard()
                return Offset.Zero
            }
        }
    }
    // Una ricerca nuova parte dalla prima riga: restare a meta' di una lista che e' cambiata sotto il
    // dito vuol dire guardare risultati a caso.
    LaunchedEffect(query, group) { listState.scrollToItem(0) }

    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                pickerTitle(side),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                buildString {
                    append("${available.size} formati")
                    append(if (simpleMode) " · lo strumento lo sceglie il motore" else " · uno per strumento")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FluidSearchField(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
            placeholder = "Formato, estensione o MIME",
            onSearch = dismissKeyboard,
            autoFocus = false,
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "all") {
                FluidChip(label = "Tutti", selected = group == null, onClick = { group = null })
            }
            items(groups, key = { it }) { name ->
                FluidChip(
                    label = FormatCatalog.groupLabel(name),
                    selected = group == name,
                    onClick = { group = if (group == name) null else name },
                )
            }
        }

        if (visible.isEmpty()) {
            FluidEmptyState(
                title = "Nessun formato",
                detail = if (group != null) {
                    "Niente in «${FormatCatalog.groupLabel(group!!)}» corrisponde alla ricerca: prova con «Tutti»."
                } else {
                    "Prova con l'estensione (pdf, mp3) o con il tipo MIME (image/png)."
                },
                illustration = { Icon(Icons.Rounded.SearchOff, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(hideOnScroll).selectableGroup(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
            ) {
                itemsIndexed(visible, key = { _, option -> option.id }) { index, option ->
                    Column {
                        if (index > 0) FluidListDivider()
                        FormatChoiceRow(
                            option = option,
                            simpleMode = simpleMode,
                            selected = option.id == preselected?.id,
                            onSelect = { onSelect(option) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Una voce: il nome breve (quello che si cerca: «JPEG», «MP3») come titolo, il nome lungo con
 * l'estensione e il MIME sotto (e lo strumento, in modalita' avanzata), l'icona della famiglia sulla
 * piastrella del suo colore, e la spunta se e' quella gia' scelta.
 *
 * E' una riga a scelta singola dell'engine ([FluidChoiceRow]): un tocco solo che sceglie, un `Tick`
 * se la scelta si sposta, e per TalkBack un pulsante di opzione con il suo stato.
 */
@Composable
private fun FormatChoiceRow(
    option: FormatOption,
    simpleMode: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    FluidChoiceRow(
        title = option.format.uppercase(),
        subtitle = buildString {
            append(option.name)
            append(" · .${option.extension} · ${option.mime}")
            if (!simpleMode) append(" · ${option.handler}")
        },
        selected = selected,
        onSelect = onSelect,
        tone = FormatCatalog.groupTone(option.group),
        leading = { Icon(formatGroupIcon(option.group), contentDescription = null) },
    )
}

/** L'icona di una famiglia di formati, per le piastrelle: la stessa nel selettore, sul file e sul risultato. */
internal fun formatGroupIcon(group: String?): ImageVector = when (group) {
    "image" -> Icons.Rounded.Image
    "audio" -> Icons.Rounded.AudioFile
    "video" -> Icons.Rounded.VideoFile
    "document" -> Icons.Rounded.Description
    "text" -> Icons.AutoMirrored.Rounded.Notes
    "spreadsheet" -> Icons.Rounded.TableChart
    "presentation" -> Icons.Rounded.Slideshow
    "archive" -> Icons.Rounded.FolderZip
    "data" -> Icons.Rounded.DataObject
    "code" -> Icons.Rounded.Code
    "font" -> Icons.Rounded.FontDownload
    "model" -> Icons.Rounded.ViewInAr
    "database" -> Icons.Rounded.Storage
    "application" -> Icons.Rounded.Apps
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}
