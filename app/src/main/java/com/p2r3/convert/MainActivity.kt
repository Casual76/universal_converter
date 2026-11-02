package com.p2r3.convert

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.p2r3.convert.ui.ConverterScreen
import com.p2r3.convert.ui.ConverterViewModel
import com.p2r3.convert.ui.LogScreen
import com.p2r3.convert.ui.PreviewScreen
import com.p2r3.convert.ui.SettingsScreen
import com.p2r3.convert.ui.theme.ConvertTheme
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.FluidScrollToTopBus
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.ProvideFluidChrome
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.rememberFluidChromeController
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.navigation.FluidNavHost
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.antigravity.fluidengine.ui.navigation.rememberFluidNavController
import dev.antigravity.fluidengine.ui.page.FluidBackSwipe
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Files handed over by another app through the share sheet. */
    private val sharedFiles = MutableStateFlow<List<Uri>>(emptyList())

    /**
     * Il formato di arrivo chiesto da chi ci ha mandato il file (PampAI/Aria con
     * `apri_convertitore`): si preseleziona da solo, cosi' chi arriva qui trova la conversione
     * gia' impostata invece di doverla ripetere a mano.
     */
    private val requestedTarget = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readSharedFiles(intent)

        setContent {
            val viewModel: ConverterViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                sharedFiles.collect { uris ->
                    if (uris.isNotEmpty()) {
                        viewModel.onFilesPicked(uris)
                        sharedFiles.value = emptyList()
                    }
                }
            }

            val formats by (application as ConvertApplication).engine.formats.collectAsStateWithLifecycle()
            val target by requestedTarget.collectAsStateWithLifecycle()
            LaunchedEffect(target, formats) {
                val wanted = target?.lowercase()?.trim()?.removePrefix(".") ?: return@LaunchedEffect
                val option = formats.firstOrNull { it.to && (it.extension.lowercase() == wanted || it.format.lowercase() == wanted) }
                    ?: formats.firstOrNull { it.to && it.searchIndex.contains(wanted) }
                if (option != null) {
                    viewModel.selectTarget(option)
                    requestedTarget.value = null
                }
            }

            ConvertTheme(settings = settings) {
                ConvertRoot(viewModel)
            }
        }
    }

    /**
     * La radice, nella forma che ha la galleria dell'engine: il motore sotto tutto, le pagine sopra, e
     * l'host dei modali sopra le pagine.
     *
     * L'host sta alla radice e non dentro una pagina perche' il selettore dei formati, gli avvisi e i
     * menu delle righe sono pannelli di vetro che rifrangono la pagina da cui nascono: un pannello
     * dentro la pagina sarebbe parte di quello che rifrange.
     */
    @androidx.compose.runtime.Composable
    private fun ConvertRoot(viewModel: ConverterViewModel) {
        val modalHost = rememberFluidGlassModalHostState()
        val chrome = rememberFluidChromeController()
        val scrollToTop = remember { FluidScrollToTopBus() }
        val fallbackBackdrop = rememberGlassBackdrop()
        val backdrop = chrome.activeBackdrop.value ?: fallbackBackdrop
        val navController = rememberFluidNavController()

        CompositionLocalProvider(LocalFluidGlassModalHostState provides modalHost) {
            Box(Modifier.fillMaxSize()) {
                // The engine lives here: a full size, fully transparent WebView. Handlers that
                // rasterise DOM content need a real laid out page, so it stays in the tree instead
                // of running detached. The pages above are opaque, so it is never seen. Keyed on the
                // engine's generation: if the page process dies, a fresh WebView takes its place.
                val engine = (application as ConvertApplication).engine
                val generation by engine.generation.collectAsStateWithLifecycle()
                key(generation) {
                    AndroidView(
                        factory = { context -> engine.createWebView(context) },
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(0f),
                    )
                }

                ProvideFluidChrome(controller = chrome, bottomInset = 0.dp, scrollToTop = scrollToTop) {
                    // Le pagine dell'engine (3.0): opache, da destra, e il back sotto il dito. La
                    // barra e' una sola, ancorata, e la descrive ogni pagina.
                    FluidNavHost(
                        navController = navController,
                        startDestination = RouteConverter,
                        modifier = Modifier
                            .fillMaxSize()
                            .fluidGlassModalObscured(),
                    ) {
                        composable(RouteConverter) {
                            ConverterScreen(
                                viewModel = viewModel,
                                onOpenSettings = { navController.navigate(RouteSettings) },
                                onOpenLog = { navController.navigate(RouteLog) },
                                onPreview = { index -> navController.navigate("$RoutePreview/$index") },
                            )
                        }
                        composable(RouteSettings) {
                            SettingsScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                                onOpenLog = { navController.navigate(RouteLog) },
                            )
                        }
                        // Un visore con i suoi gesti (pizzico, spostamento, tieni premuto per
                        // confrontare, testo che scorre di lato): il back a dito parte dal bordo.
                        composable("$RoutePreview/{index}", backSwipe = FluidBackSwipe.EdgeOnly) { entry ->
                            PreviewScreen(
                                viewModel = viewModel,
                                index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(RouteLog) {
                            LogScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }

                FluidGlassModalHost(state = modalHost, backdrop = backdrop)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readSharedFiles(intent)
    }

    /** Accepts a single file or a batch sent from another app. */
    @Suppress("DEPRECATION")
    private fun readSharedFiles(intent: Intent?) {
        intent?.getStringExtra(com.p2r3.convert.pampai.OpenConverterTool.EXTRA_TARGET_FORMAT)
            ?.takeIf { it.isNotBlank() }
            ?.let { requestedTarget.value = it }
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) sharedFiles.value = uris
    }

    override fun onDestroy() {
        // Una conversione chiesta dall'assistente puo' essere ancora in volo: il motore vive nel
        // processo, non nella schermata, e spegnerlo qui la ucciderebbe a meta'.
        val engine = (application as ConvertApplication).engine
        if (isFinishing && engine.jobsInFlight == 0) engine.release()
        super.onDestroy()
    }

    private companion object {
        const val RouteConverter = "converter"
        const val RouteSettings = "settings"
        const val RoutePreview = "preview"
        const val RouteLog = "log"
    }
}
