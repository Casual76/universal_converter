package com.p2r3.convert

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.p2r3.convert.ui.ConverterScreen
import com.p2r3.convert.ui.ConverterViewModel
import com.p2r3.convert.ui.LogScreen
import com.p2r3.convert.ui.PreviewScreen
import com.p2r3.convert.ui.SettingsScreen
import com.p2r3.convert.ui.theme.ConvertTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Files handed over by another app through the share sheet. */
    private val sharedFiles = MutableStateFlow<List<Uri>>(emptyList())

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

            ConvertTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                Box(Modifier.fillMaxSize()) {
                    // The engine lives here: a full size, fully transparent WebView.
                    // Handlers that rasterise DOM content need a real laid out page,
                    // so it stays in the tree instead of running detached.
                    AndroidView(
                        factory = { context ->
                            (application as ConvertApplication).engine.createWebView(context)
                        },
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(0f)
                    )

                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = "converter",
                        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
                        exitTransition = { slideOutHorizontally { -it / 6 } + fadeOut() },
                        popEnterTransition = { slideInHorizontally { -it / 6 } + fadeIn() },
                        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() }
                    ) {
                        composable("converter") {
                            ConverterScreen(
                                viewModel = viewModel,
                                onOpenSettings = { navController.navigate("settings") },
                                onPreview = { index -> navController.navigate("preview/$index") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                                onOpenLog = { navController.navigate("log") }
                            )
                        }
                        composable("preview/{index}") { entry ->
                            PreviewScreen(
                                viewModel = viewModel,
                                index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("log") {
                            LogScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
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
        if (isFinishing) (application as ConvertApplication).engine.release()
        super.onDestroy()
    }
}
