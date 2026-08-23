package com.p2r3.convert.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.p2r3.convert.data.formatSize
import com.p2r3.convert.engine.AssetDownload
import com.p2r3.convert.engine.ConversionProgress
import com.p2r3.convert.engine.ConversionStage
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Live view of a running conversion.
 *
 * The engine picks a complete route before it starts, so the whole itinerary is
 * shown at once: what is done, what is running, what is still ahead. That turns
 * a multi hop conversion from an opaque wait into something you can follow.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConversionProgressCard(
    progress: ConversionProgress,
    download: AssetDownload?,
    onCancel: () -> Unit
) {
    // Ticks on its own so the elapsed time keeps moving even while the engine
    // is busy inside a single long step and sends nothing.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(progress.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val elapsed = ((now - progress.startedAt) / 1000).coerceAtLeast(0)

    val target = progress.fraction(download?.fraction)
    val animated by animateFloatAsState(
        targetValue = target ?: 0f,
        animationSpec = tween(durationMillis = 450),
        label = "progress"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ContinuousCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (progress.stage) {
                            ConversionStage.Preparing -> "Preparo i file"
                            ConversionStage.Searching -> "Cerco il percorso"
                            ConversionStage.Downloading -> "Scarico ${progress.handler}"
                            ConversionStage.Converting -> "Converto"
                            ConversionStage.Writing -> "Salvo il risultato"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        subtitleFor(progress, download),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    if (target != null) "${(animated * 100).roundToInt()}%" else formatElapsed(elapsed),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (target != null) {
                LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (progress.path.size > 1) {
                PathStepper(progress.path, progress.step)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    footnoteFor(progress, elapsed, target != null),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = onCancel) { Text("Annulla") }
            }
        }
    }
}

/** The itinerary, one chip per format the file passes through. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PathStepper(path: List<String>, step: Int) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        path.forEachIndexed { index, format ->
            if (index > 0) {
                Text(
                    "→",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
            StepChip(
                format = format,
                state = when {
                    index == 0 || index < step -> StepState.Done
                    index == step -> StepState.Current
                    else -> StepState.Pending
                }
            )
        }
    }
}

private enum class StepState { Done, Current, Pending }

@Composable
private fun StepChip(format: String, state: StepState) {
    val container by animateColorAsState(
        when (state) {
            StepState.Done -> MaterialTheme.colorScheme.secondaryContainer
            StepState.Current -> MaterialTheme.colorScheme.primary
            StepState.Pending -> Color.Transparent
        },
        label = "chip"
    )
    val content = when (state) {
        StepState.Done -> MaterialTheme.colorScheme.onSecondaryContainer
        StepState.Current -> MaterialTheme.colorScheme.onPrimary
        StepState.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // The running step breathes, so a slow conversion still looks alive.
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "glow"
    )

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(container.copy(alpha = if (state == StepState.Current) glow else container.alpha))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .alpha(if (state == StepState.Pending) 0.6f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state == StepState.Done) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            format.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            fontWeight = if (state == StepState.Current) FontWeight.Bold else FontWeight.Medium
        )
    }
}

private fun subtitleFor(progress: ConversionProgress, download: AssetDownload?): String = when (progress.stage) {
    ConversionStage.Preparing -> "Leggo il file"
    ConversionStage.Searching ->
        if (progress.explored > 0) "${progress.explored} percorsi valutati" else "Interrogo il grafo dei formati"
    ConversionStage.Downloading ->
        download?.let { "${formatSize(it.downloaded)} di ${formatSize(it.total)}" } ?: "Preparo il motore"
    ConversionStage.Converting ->
        if (progress.steps > 0) {
            "Passo ${progress.step.coerceAtLeast(1)} di ${progress.steps}" +
                if (progress.handler.isNotBlank()) " · ${progress.handler}" else ""
        } else progress.detail
    ConversionStage.Writing -> progress.detail.ifBlank { "Quasi finito" }
}

private fun footnoteFor(progress: ConversionProgress, elapsed: Long, showsPercent: Boolean): String {
    val parts = mutableListOf<String>()
    if (showsPercent) parts += formatElapsed(elapsed)
    if (progress.attempt > 1) parts += "${progress.attempt}° percorso"
    if (progress.deadEnds > 0) parts += "${progress.deadEnds} vicoli ciechi"
    return parts.joinToString(" · ")
}

private fun formatElapsed(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)
