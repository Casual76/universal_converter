package com.p2r3.convert.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.p2r3.convert.ConvertApplication
import com.p2r3.convert.data.PickedFile
import com.p2r3.convert.data.Settings
import com.p2r3.convert.data.ThemeMode
import com.p2r3.convert.engine.ConversionProgress
import com.p2r3.convert.engine.ConversionResult
import com.p2r3.convert.engine.ConversionStage
import com.p2r3.convert.engine.ConvertedFile
import com.p2r3.convert.engine.EngineStatus
import com.p2r3.convert.engine.FailureReason
import com.p2r3.convert.engine.FormatOption
import com.p2r3.convert.engine.InputFile
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

    /** Picks the input format the same way the web UI does: extension, then MIME. */
    private fun detectFormat(file: PickedFile): FormatOption? {
        val options = formats.value.filter { it.from }
        if (options.isEmpty()) return null
        val byExtension = options.filter { it.extension.equals(file.extension, ignoreCase = true) }
        if (byExtension.isNotEmpty()) {
            return byExtension.firstOrNull { it.mime.equals(file.mimeType, ignoreCase = true) } ?: byExtension.first()
        }
        return options.firstOrNull { it.mime.equals(file.mimeType, ignoreCase = true) }
    }

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
    fun setThemeMode(value: ThemeMode) { viewModelScope.launch { app.settingsStore.setThemeMode(value) } }
    fun setDynamicColor(value: Boolean) { viewModelScope.launch { app.settingsStore.setDynamicColor(value) } }
    fun setAutoSave(value: Boolean) { viewModelScope.launch { app.settingsStore.setAutoSave(value) } }

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
