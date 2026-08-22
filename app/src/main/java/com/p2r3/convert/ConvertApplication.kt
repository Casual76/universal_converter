package com.p2r3.convert

import android.app.Application
import com.p2r3.convert.data.FileGateway
import com.p2r3.convert.data.SettingsStore
import com.p2r3.convert.engine.ConversionEngine

/** Owns the pieces that must outlive a single screen. */
class ConvertApplication : Application() {
    val engine: ConversionEngine by lazy { ConversionEngine(this) }
    val settingsStore: SettingsStore by lazy { SettingsStore(this) }
    val fileGateway: FileGateway by lazy { FileGateway(this) }
}
