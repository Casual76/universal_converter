package com.p2r3.convert.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.p2r3.convert.engine.FormatOption

/**
 * Format chooser. In simple mode identical formats offered by several handlers
 * collapse into one row and the engine decides which tool to use; advanced mode
 * exposes every handler separately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatPickerSheet(
    formats: List<FormatOption>,
    direction: PickerTarget,
    simpleMode: Boolean,
    preselected: FormatOption?,
    onDismiss: () -> Unit,
    onSelect: (FormatOption) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }

    val available = remember(formats, direction, simpleMode) {
        val filtered = formats.filter { if (direction == PickerTarget.Source) it.from else it.to }
        if (simpleMode) filtered.distinctBy { it.mime to it.format } else filtered
    }

    val groups = remember(available) {
        available.map { it.group }.distinct().sorted()
    }

    val visible = remember(available, query, group) {
        val needle = query.trim().lowercase()
        available.asSequence()
            .filter { group == null || it.group == group }
            .filter { needle.isEmpty() || it.searchIndex.contains(needle) }
            .sortedWith(compareBy({ it.format.lowercase() }, { it.handler }))
            .toList()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                if (direction == PickerTarget.Source) "Formato di partenza" else "Formato di destinazione",
                style = MaterialTheme.typography.titleLarge
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Cerca formato, estensione o MIME") },
                singleLine = true
            )

            LazyRow(
                modifier = Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = group == null,
                        onClick = { group = null },
                        label = { Text("Tutti") }
                    )
                }
                items(groups) { name ->
                    FilterChip(
                        selected = group == name,
                        onClick = { group = if (group == name) null else name },
                        label = { Text(name.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            Text(
                "Nessun formato corrisponde alla ricerca.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(visible, key = { it.id }) { option ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(option) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (option.id == preselected?.id)
                                MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface
                        ),
                        leadingContent = { FormatBadge(option.format) },
                        headlineContent = {
                            Text(option.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(".${option.extension}  ·  ${option.mime}")
                                    if (!simpleMode) append("  ·  ${option.handler}")
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatBadge(format: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            format.uppercase().take(4),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1
        )
    }
}
