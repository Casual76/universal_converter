package com.p2r3.convert.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.p2r3.convert.data.Preview
import com.p2r3.convert.data.PreviewLoader
import com.p2r3.convert.data.formatSize
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl

/**
 * Side by side look at what went in and what came out.
 *
 * The two files are one tap apart, and on images you can press and hold to flip
 * back to the original: comparing a conversion is the whole point of looking at
 * it, and that is hard to do when the two are on different screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        pair?.convertedName ?: "Anteprima",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro")
                    }
                }
            )
        }
    ) { padding ->

        if (pair == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Niente da mostrare.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        // Holding down peeks at the original, so a comparison is one gesture.
        val originalIsShown = showingOriginal != peeking
        val shown = if (originalIsShown) original else converted

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // Il controllo dell'engine al posto di quello Material: la pillola scorre fra i due
            // segmenti invece di accendersi sotto quello nuovo, ed e' quello che lo fa sembrare un
            // oggetto solo con una parte mobile invece di due bottoni che si scambiano il turno.
            FluidSegmentedControl(
                options = listOf(false, true),
                selected = showingOriginal,
                onSelect = { showingOriginal = it },
                modifier = Modifier.fillMaxWidth(),
                label = { if (it) "Originale" else "Convertito" }
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
                maxLines = 2
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(ContinuousCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                when (val content = shown) {
                    null -> CircularProgressIndicator()

                    is Preview.Picture -> Image(
                        bitmap = content.image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        peeking = true
                                        tryAwaitRelease()
                                        peeking = false
                                    }
                                )
                            }
                    )

                    is Preview.Text -> Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            content.body,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                        if (content.truncated) {
                            Text(
                                "… troncato",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }

                    is Preview.Binary -> Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            "Formato non visualizzabile: ecco i primi byte.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            content.dump,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .horizontalScroll(rememberScrollState())
                        )
                    }

                    is Preview.Failed -> Text(
                        content.reason,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    if (converted is Preview.Picture) "Tieni premuto sull'immagine per confrontarla con l'altra"
                    else "Tocca le schede per confrontare",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun describe(preview: Preview?): String? = when (preview) {
    is Preview.Picture -> "${preview.width}×${preview.height}"
    is Preview.Text -> "testo"
    is Preview.Binary -> "binario"
    else -> null
}
