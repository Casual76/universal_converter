package com.p2r3.convert.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.p2r3.convert.ConvertApplication
import com.p2r3.convert.data.PickedFile
import com.p2r3.convert.BuildConfig
import com.p2r3.convert.data.Settings
import com.p2r3.convert.engine.ConversionProgress
import com.p2r3.convert.engine.ConversionResult
import com.p2r3.convert.engine.ConversionStage
import com.p2r3.convert.engine.ConvertedFile
import com.p2r3.convert.engine.EngineStatus
import com.p2r3.convert.engine.FailureReason
import com.p2r3.convert.engine.FormatOption
import com.p2r3.convert.engine.InputFile
import com.p2r3.convert.ui.theme.ConvertAppearance
import com.p2r3.convert.update.convertUpdater
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateUiState
import dev.antigravity.fluidengine.ui.fluid.isBusy
import dev.antigravity.fluidengine.ui.fluid.toFluidUpdateRelease
import dev.antigravity.fluidengine.ui.fluid.toFluidUpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where the conversion flow currently stands. */
sealed interface ConversionPhase {
    data object Idle : ConversionPhase
    data class Running(val progress: ConversionProgress) : ConversionPhase
    data class Done(val files: List<ConvertedFile>, val path: List<String>, val saved: Int = 0) : ConversionPhase
    data class Error(val reason: FailureReason, val detail: String) : ConversionPhase
}

data class ConverterState(
    val files: List<PickedFile> = emptyList(),
    val source: FormatOption? = null,
    val target: FormatOption? = null,
    val phase: ConversionPhase = ConversionPhase.Idle
) {
    val canConvert: Boolean
        get() = files.isNotEmpty() && source != null && target != null && phase !is ConversionPhase.Running
}

class ConverterViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ConvertApplication
    private val engine = app.engine
    private val gateway = app.fileGateway

    val engineStatus: StateFlow<EngineStatus> = engine.status
    val formats: StateFlow<List<FormatOption>> = engine.formats
    val logs = engine.logs
    val assetDownload = engine.assetStore.download
    val assetFailure = engine.assetStore.failure

    val settings: StateFlow<Settings> = app.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _state = MutableStateFlow(ConverterState())
    val state: StateFlow<ConverterState> = _state.asStateFlow()

    private var conversion: Job? = null

    /* ---------------------------------------------------------------------- */
    /* File selection                                                         */
    /* ---------------------------------------------------------------------- */

    fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { uris.map(gateway::describe) }
            // The engine converts one format at a time, so keep the batch uniform.
            val head = picked.first()
            val uniform = picked.filter { it.extension == head.extension }
            _state.value = _state.value.copy(
                files = uniform,
                source = detectFormat(uniform.first()),
                phase = ConversionPhase.Idle
            )
        }
    }

    fun clearFiles() {
        _state.value = ConverterState(target = _state.value.target)
    }

    /** Il formato di partenza dal nome e dal tipo del file: vedi [FormatCatalog.detect]. */
    private fun detectFormat(file: PickedFile): FormatOption? =
        FormatCatalog.detect(formats.value, file.extension, file.mimeType)

    fun selectSource(option: FormatOption) {
        _state.value = _state.value.copy(source = option)
    }

    fun selectTarget(option: FormatOption) {
        _state.value = _state.value.copy(target = option)
    }

    /* ---------------------------------------------------------------------- */
    /* Conversion                                                             */
    /* ---------------------------------------------------------------------- */

    fun convert() {
        val current = _state.value
        val source = current.source ?: return
        val target = current.target ?: return
        if (current.files.isEmpty()) return

        conversion?.cancel()
        conversion = viewModelScope.launch {
            _state.value = current.copy(phase = ConversionPhase.Running(ConversionProgress(ConversionStage.Preparing)))

            val result = engine.convert(
                inputs = current.files.map { InputFile(it.name, it.uri) },
                fromId = source.id,
                toId = target.id,
                simpleMode = settings.value.simpleMode,
                onProgress = { progress ->
                    _state.value = _state.value.let { state ->
                        if (state.phase is ConversionPhase.Running) state.copy(phase = ConversionPhase.Running(progress))
                        else state
                    }
                }
            )

            _state.value = _state.value.copy(
                phase = when (result) {
                    is ConversionResult.Success -> ConversionPhase.Done(result.files, result.path)
                    is ConversionResult.Failure -> ConversionPhase.Error(result.reason, result.detail)
                }
            )

            if (result is ConversionResult.Success && settings.value.autoSave) saveAll()
        }
    }

    fun cancelConversion() {
        engine.cancelAll()
        conversion?.cancel()
        _state.value = _state.value.copy(phase = ConversionPhase.Idle)
    }

    fun dismissResult() {
        _state.value = _state.value.copy(phase = ConversionPhase.Idle)
    }

    /* ---------------------------------------------------------------------- */
    /* Results                                                                */
    /* ---------------------------------------------------------------------- */

    fun saveAll() {
        val done = _state.value.phase as? ConversionPhase.Done ?: return
        viewModelScope.launch {
            val saved = done.files.count { gateway.saveToDownloads(it) != null }
            _state.value = _state.value.copy(phase = done.copy(saved = saved))
        }
    }

    fun shareIntent() = (_state.value.phase as? ConversionPhase.Done)?.let { gateway.shareIntent(it.files) }

    /** Condivide solo questi file (il menu di una riga del risultato). */
    fun shareIntent(files: List<ConvertedFile>) = gateway.shareIntent(files)

    fun openIntent(file: ConvertedFile) = gateway.openIntent(file)

    /**
     * Pairs one converted file with the input it came from, for the viewer.
     * Batches convert one file to one file, so the indexes line up; a handler
     * that fans out (an archive, say) falls back to the first input.
     */
    fun previewPair(index: Int): PreviewPair? {
        val current = _state.value
        val done = current.phase as? ConversionPhase.Done ?: return null
        val converted = done.files.getOrNull(index) ?: return null
        val original = current.files.getOrNull(index) ?: current.files.firstOrNull() ?: return null
        return PreviewPair(
            originalName = original.name,
            originalUri = original.uri,
            originalSize = original.size,
            convertedName = converted.name,
            convertedUri = Uri.fromFile(File(converted.path)),
            convertedSize = converted.size
        )
    }

    /* ---------------------------------------------------------------------- */
    /* Settings                                                               */
    /* ---------------------------------------------------------------------- */

    fun setSimpleMode(value: Boolean) { viewModelScope.launch { app.settingsStore.setSimpleMode(value) } }
    fun setAutoSave(value: Boolean) { viewModelScope.launch { app.settingsStore.setAutoSave(value) } }

    /**
     * Quello che la sezione «Aspetto» dell'engine ha scritto (tema, nero, accento, aptica), riportato
     * nelle impostazioni dell'app con [ConvertAppearance.fromEngine].
     */
    fun setAppearance(value: EngineSettings) {
        val next = ConvertAppearance.fromEngine(settings.value, value)
        if (next != settings.value) viewModelScope.launch { app.settingsStore.setAppearance(next) }
    }

    /* ---------------------------------------------------------------------- */
    /* In-app updates                                                         */
    /* ---------------------------------------------------------------------- */

    private val updater by lazy { convertUpdater(app) }
    private var availableUpdate: AvailableAppUpdate? = null

    private val _update = MutableStateFlow<FluidUpdateUiState>(FluidUpdateUiState.Idle)
    /** Lo stato dell'aggiornamento in-app, per la sezione delle impostazioni. */
    val update: StateFlow<FluidUpdateUiState> = _update.asStateFlow()

    private val _lastUpdateCheck = MutableStateFlow<Long?>(null)
    val lastUpdateCheck: StateFlow<Long?> = _lastUpdateCheck.asStateFlow()

    /**
     * Controlla il manifest pubblicato, quello che legge anche il Pampa Store: una fonte sola, quindi
     * lo store e l'app non possono proporre due versioni diverse. Solo a richiesta: l'app non va in
     * rete da sola per questo.
     */
    fun checkForUpdate() {
        if (_update.value.isBusy) return
        _update.value = FluidUpdateUiState.Checking
        viewModelScope.launch {
            updater.check(currentVersionName = BuildConfig.VERSION_NAME)
                .onSuccess { found ->
                    availableUpdate = found
                    _update.value = found?.let { FluidUpdateUiState.Available(it.toFluidUpdateRelease()) }
                        ?: FluidUpdateUiState.UpToDate
                }
                .onFailure { _update.value = FluidUpdateUiState.CheckFailed(it.message) }
            _lastUpdateCheck.value = System.currentTimeMillis()
        }
    }

    fun installUpdate() {
        val update = availableUpdate ?: return
        if (_update.value.isBusy) return
        val release = update.toFluidUpdateRelease()
        viewModelScope.launch {
            updater.install(update).collect { state -> _update.value = state.toFluidUpdateState(release) }
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Engine downloads                                                       */
    /* ---------------------------------------------------------------------- */

    private val _packState = MutableStateFlow(EnginePackState())
    val packState: StateFlow<EnginePackState> = _packState.asStateFlow()

    fun refreshPackState() {
        val store = engine.assetStore
        _packState.value = EnginePackState(
            downloadedBytes = store.downloadedBytes(),
            totalBytes = store.totalRemoteBytes(),
            fileCount = store.remoteAssets.size,
            downloadedCount = store.remoteAssets.count(store::isDownloaded)
        )
    }

    fun downloadEverything() {
        viewModelScope.launch(Dispatchers.IO) {
            engine.assetStore.downloadAll()
            withContext(Dispatchers.Main) { refreshPackState() }
        }
    }

    fun clearDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            engine.assetStore.clearDownloads()
            withContext(Dispatchers.Main) { refreshPackState() }
        }
    }
}

/** The two sides the in-app viewer shows. */
data class PreviewPair(
    val originalName: String,
    val originalUri: Uri,
    val originalSize: Long,
    val convertedName: String,
    val convertedUri: Uri,
    val convertedSize: Long
)

data class EnginePackState(
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val fileCount: Int = 0,
    val downloadedCount: Int = 0
)
