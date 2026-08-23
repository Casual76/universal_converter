package com.p2r3.convert.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.antigravity.fluidengine.ui.fluid.FluidScreen

/** Live view of what the engine is saying, for when a conversion misbehaves. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(viewModel: ConverterViewModel, onBack: () -> Unit) {

    val context = LocalContext.current
    val lines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.logs.collect { line ->
            lines += line
            // Keep memory bounded: the engine is chatty during pathfinding.
            if (lines.size > 500) lines.removeRange(0, lines.size - 500)
            listState.animateScrollToItem(lines.lastIndex.coerceAtLeast(0))
        }
    }

    FluidScreen(
        title = "Log del motore",
        subtitle = "${lines.size} righe",
        onBack = onBack,
        listState = listState,
        actions = {
            FluidBarAction(
                icon = Icons.Rounded.ContentCopy,
                contentDescription = "Copia",
                onClick = { copyToClipboard(context, lines.joinToString("\n")) }
            )
        },
        itemSpacing = 0.dp
    ) {
        items(lines) { line ->
            Text(
                line,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (line.startsWith("[error]")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("log", text))
}
