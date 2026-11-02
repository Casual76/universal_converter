package com.p2r3.convert.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.p2r3.convert.data.formatSize
import com.p2r3.convert.engine.AssetDownload
import com.p2r3.convert.engine.ConversionProgress
import com.p2r3.convert.engine.ConversionStage
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidIndeterminateBar
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidTextStyles
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.theme.FluidCard
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Live view of a running conversion.
 *
 * The engine picks a complete route before it starts, so the whole itinerary is
 * shown at once: what is done, what is running, what is still ahead. That turns
 * a multi hop conversion from an opaque wait into something you can follow.
 *
 * Sull'engine 3.0 e' una card di vetro come le altre della pagina: la barra e' quella dell'engine
 * (il riempimento tiene almeno 3:1 sulla pista), il passo in corso respira con il respiro di tutte le
 * app (`FluidMotion.pulse`), e «Annulla» e' un tasto vero, che vibra e cresce sotto il dito.
 */
@Composable
fun ConversionProgressCard(
    progress: ConversionProgress,
    download: AssetDownload?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
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
    // Il dato arriva a scatti (un evento per passo): la molla lo rende un moto, non un salto.
    val animated by animateFloatAsState(
        targetValue = target ?: 0f,
        animationSpec = FluidMotion.smooth(),
        label = "progress",
    )

    FluidCard(modifier = modifier, glass = true, highlighted = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stageTitle(progress),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    subtitleFor(progress, download),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (target != null) "${(animated * 100).roundToInt()}%" else formatElapsed(elapsed),
                style = FluidTextStyles.numeric,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (target != null) {
            FluidProgressBar(progress = { animated })
        } else {
            FluidIndeterminateBar()
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
                overflow = TextOverflow.Ellipsis,
            )
            FluidButton(
                text = "Annulla",
                onClick = onCancel,
                style = FluidButtonStyle.Plain,
                size = FluidButtonSize.Small,
            )
        }
    }
}

/** The itinerary, one pill per format the file passes through. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PathStepper(path: List<String>, step: Int) {
    val description = "Percorso: " + path.mapIndexed { index, format ->
        val state = stepState(index, step)
        format.uppercase() + when (state) {
            StepState.Done -> " fatto"
            StepState.Current -> " in corso"
            StepState.Pending -> ""
        }
    }.joinToString(", ")
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        path.forEachIndexed { index, format ->
            if (index > 0) {
                Text(
                    "→",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            StepPill(format = format, state = stepState(index, step))
        }
    }
}

internal enum class StepState { Done, Current, Pending }

/**
 * Lo stato di un formato nell'itinerario: il primo e' il file di partenza (fatto per definizione), i
 * passi prima di quello in corso sono fatti, quello in corso e' [step] (da 1).
 */
internal fun stepState(index: Int, step: Int): StepState = when {
    index == 0 || index < step -> StepState.Done
    index == step -> StepState.Current
    else -> StepState.Pending
}

/**
 * Una pillola dell'itinerario. Non e' un tasto e non si tocca: e' un'etichetta di stato, quindi una
 * capsula piena e non un chip di vetro (un chip di vetro promette un tocco).
 */
@Composable
private fun StepPill(format: String, state: StepState) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when (state) {
            StepState.Done -> scheme.secondaryContainer
            StepState.Current -> scheme.primary
            StepState.Pending -> Color.Transparent
        },
        animationSpec = FluidMotion.color(),
        label = "step pill",
    )
    val content = when (state) {
        StepState.Done -> scheme.onSecondaryContainer
        StepState.Current -> scheme.onPrimary
        StepState.Pending -> scheme.onSurfaceVariant
    }

    // The running step breathes, so a slow conversion still looks alive: the engine's breath, the
    // same 900 ms as every live dot in every app, and full and still with reduced motion.
    val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
    val pulse = rememberInfiniteTransition(label = "pulse")
    val breath by pulse.animateFloat(
        initialValue = FluidMotion.PulseFillLowAlpha,
        targetValue = 1f,
        animationSpec = FluidMotion.pulse(),
        label = "breath",
    )
    val alpha = if (state == StepState.Current && !reducedMotion) breath else 1f

    Row(
        modifier = Modifier
            .clip(FluidCapsuleShape)
            .background(container.copy(alpha = container.alpha * alpha))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state == StepState.Done) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            format.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = if (state == StepState.Pending) content.copy(alpha = 0.7f) else content,
            fontWeight = if (state == StepState.Current) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

private fun stageTitle(progress: ConversionProgress): String = when (progress.stage) {
    ConversionStage.Preparing -> "Preparo i file"
    ConversionStage.Searching -> "Cerco il percorso"
    ConversionStage.Downloading -> "Scarico ${progress.handler}"
    ConversionStage.Converting -> "Converto"
    ConversionStage.Writing -> "Salvo il risultato"
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
