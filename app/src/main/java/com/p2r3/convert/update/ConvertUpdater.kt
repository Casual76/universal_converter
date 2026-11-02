package com.p2r3.convert.update

import android.content.Context
import com.p2r3.convert.BuildConfig
import dev.antigravity.fluidengine.foundation.AppUpdater
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.antigravity.fluidengine.update.UpdateSource

/**
 * Dove Convert to it! pubblica le sue release: lo stesso `manifest.json` che il Pampa Store legge per
 * mostrare l'app. Una fonte sola, quindi non esiste il caso in cui lo store offre una versione e
 * l'app un'altra.
 */
const val CONVERT_MANIFEST_URL =
    "https://raw.githubusercontent.com/Casual76/universal_converter/main/manifest.json"

/** L'aggiornamento in-app, sui moduli dell'engine: lo stesso specchio di Fluid Glass e KeyVoice. */
fun convertUpdater(context: Context, manifestUrl: String = CONVERT_MANIFEST_URL): AppUpdater {
    val http = EngineHttp(userAgent = "ConvertToItUpdater/${BuildConfig.VERSION_NAME}")
    return EngineAppUpdater(
        http = http,
        source = UpdateSource(manifestUrl = manifestUrl, applicationId = context.packageName),
        installer = AndroidAppUpdateInstaller(context.applicationContext, http),
    )
}
