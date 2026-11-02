package com.p2r3.convert.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.p2r3.convert.data.Preview
import com.p2r3.convert.data.PreviewLoader
import com.p2r3.convert.data.formatSize
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.fluidZoomable
import dev.antigravity.fluidengine.ui.fluid.rememberFluidZoomState
import dev.antigravity.fluidengine.ui.page.FluidPageChrome
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState

/**
 * Side by side look at what went in and what came out.
 *
 * The two files are one tap apart, and on images you can press and hold to flip
 * back to the original: comparing a conversion is the whole point of looking at
 * it, and that is hard to do when the two are on different screens.
 *
 * Non e' una `FluidScreen` (e' un visore alto quanto la pagina, non una lista): la barra la descrive
 * [FluidPageChrome] e la disegna l'host delle pagine, ancorata come quella delle altre. Le immagini
 * si ingrandiscono col pizzico e il doppio tocco (`fluidZoomable`); finche' non sono ingrandite,
 * tenere premuto mostra l'altra.
 */
@Composable
fun PreviewScreen(viewModel: ConverterViewModel, index: Int, onBack: () -> Unit) {

    val context = LocalContext.current
    val pair = remember(index) { viewModel.previewPair(index) }
    val loader = remember { PreviewLoader(context) }

    var converted by remember { mutableStateOf<Preview?>(null) }
    var original by remember { mutableStateOf<Preview?>(null) }
    var showingOriginal by remember { mutableStateOf(false) }
    var peeking by remember { mutableStateOf(false) }

    LaunchedEffect(pair) {
        val current = pair ?: return@LaunchedEffect
        converted = loader.load(current.convertedUri)
        original = loader.load(current.originalUri)
    }

    FluidPageChrome(title = pair?.convertedName ?: "Anteprima", onBack = onBack)

    if (pair == null) {
        Box(
            Modifier.fillMaxSize().padding(top = FluidScreenDefaults.topBarHeight()),
            contentAlignment = Alignment.Center,
        ) {
            FluidEmptyState(
                title = "Niente da mostrare",
                detail = "Il risultato di questa conversione non c'è più: rifalla e riapri l'anteprima.",
                illustration = { Icon(Icons.Rounded.HideImage, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        return
    }

    // Holding down peeks at the original, so a comparison is one gesture.
    val originalIsShown = showingOriginal != peeking
    val shown = if (originalIsShown) original else converted

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = FluidScreenDefaults.topBarHeight() + 8.dp)
            .padding(horizontal = FluidScreenDefaults.HorizontalPadding)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FluidSegmentedControl(
            options = listOf(false, true),
            selected = showingOriginal,
            onSelect = { showingOriginal = it },
            modifier = Modifier.fillMaxWidth(),
            label = { if (it) "Originale" else "Convertito" },
        )

        Text(
            buildString {
                append(if (originalIsShown) pair.originalName else pair.convertedName)
                append("  ·  ")
                append(formatSize(if (originalIsShown) pair.originalSize else pair.convertedSize))
                describe(shown)?.let { append("  ·  ").append(it) }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(ContinuousCornerShape(FluidRadius.Card))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            when (val content = shown) {
                null -> FluidSpinner()

                is Preview.Picture -> {
                    // Una sola lente per le due immagini: passando dall'una all'altra si confronta lo
                    // stesso dettaglio allo stesso ingrandimento.
                    val zoom = rememberFluidZoomState()
                    Image(
                        bitmap = content.image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            // Fuori dallo zoom nella catena: il confronto osserva il dito e non consuma
                            // niente, cosi' il doppio tocco e il pizzico di `fluidZoomable` restano suoi.
                            .pointerInput(Unit) { peekGesture(isZoomed = { zoom.isZoomed }, onPeek = { peeking = it }) }
                            .fluidZoomable(
                                state = zoom,
                                contentAspectRatio = content.width.toFloat() / content.height.coerceAtLeast(1),
                            ),
                    )
                }

                is Preview.Text -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(
                        content.body,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                    if (content.truncated) {
                        Text(
                            "… troncato",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                is Preview.Binary -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(
                        "Formato non visualizzabile: ecco i primi byte.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        content.dump,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                    )
                }

                is Preview.Failed -> Text(
                    content.reason,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }

        Text(
            if (converted is Preview.Picture) {
                "Tieni premuto sull'immagine per vedere l'altra · pizzica o tocca due volte per ingrandire"
            } else {
                "Tocca le schede per confrontare"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
    }
}

/**
 * Tenere premuto un dito solo mostra l'altra immagine, finche' il dito resta giu'.
 *
 * Parte dopo [PeekDelayMillis] di dito fermo e non al contatto: un doppio tocco (che ingrandisce) non
 * deve far lampeggiare l'altra immagine due volte. Con l'immagine ingrandita un dito la sposta, e con
 * due dita si pizzica: in entrambi i casi non e' un confronto. Non consuma niente.
 */
private suspend fun PointerInputScope.peekGesture(isZoomed: () -> Boolean, onPeek: (Boolean) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (isZoomed()) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        // Durante l'attesa il dito che si alza, si sposta o ne chiama un secondo annulla: null vuol dire
        // che l'attesa e' finita con il dito ancora giu' e fermo.
        val cancelled = withTimeoutOrNull(PeekDelayMillis) {
            var settled = true
            while (settled) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                settled = pressed.size == 1 && (pressed.first().position - down.position).getDistance() <= slop
            }
            true
        }
        if (cancelled == true) return@awaitEachGesture
        onPeek(true)
        try {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } != 1) break
            }
        } finally {
            onPeek(false)
        }
    }
}

/** Quanto tenere fermo il dito prima che compaia l'altra immagine: meno del doppio tocco, piu' di un tocco. */
private const val PeekDelayMillis = 160L

private fun describe(preview: Preview?): String? = when (preview) {
    is Preview.Picture -> "${preview.width}×${preview.height}"
    is Preview.Text -> "testo"
    is Preview.Binary -> "binario"
    else -> null
}
