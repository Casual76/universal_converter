package com.p2r3.convert.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.p2r3.convert.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Stage of a running conversion, used to drive the progress UI. */
enum class ConversionStage { Preparing, Searching, Downloading, Converting, Writing }

/**
 * Everything known about a running conversion.
 *
 * Fields accumulate instead of being replaced: the engine announces the whole
 * route once, up front, then reports one event per step, so the UI can show
 * where it is and what is still coming rather than a single moving bar.
 */
data class ConversionProgress(
    val stage: ConversionStage,
    /** Formats the file passes through, source first. One more entry than steps. */
    val path: List<String> = emptyList(),
    /** 1-based index of the step being executed right now. */
    val step: Int = 0,
    val steps: Int = 0,
    /** Tool doing the current step, e.g. "FFmpeg". */
    val handler: String = "",
    val detail: String = "",
    /** How many complete routes have been tried, including this one. */
    val attempt: Int = 0,
    /** Candidate routes the pathfinder has looked at. */
    val explored: Int = 0,
    /** Routes that turned out not to work. */
    val deadEnds: Int = 0,
    val startedAt: Long = System.currentTimeMillis()
) {
    /**
     * Overall completion, or null while there is genuinely nothing to measure.
     * [downloadFraction] folds an engine download into the current step so the
     * bar keeps moving through the slowest part of a first run.
     */
    fun fraction(downloadFraction: Float? = null): Float? = when (stage) {
        ConversionStage.Preparing -> 0.02f
        ConversionStage.Searching -> null
        ConversionStage.Writing -> 0.97f
        ConversionStage.Downloading, ConversionStage.Converting -> {
            if (steps <= 0) null else {
                val done = (step - 1).coerceAtLeast(0) + (downloadFraction ?: 0f).coerceIn(0f, 1f)
                (SEARCH_SHARE + (1f - SEARCH_SHARE - WRITE_SHARE) * (done / steps)).coerceIn(0f, 0.96f)
            }
        }
    }

    private companion object {
        /** Slice of the bar reserved for finding a route. */
        const val SEARCH_SHARE = 0.08f
        /** Slice reserved for handing the result back. */
        const val WRITE_SHARE = 0.05f
    }
}

/** One file handed to the engine, read straight from its content URI. */
data class InputFile(val name: String, val uri: Uri)

enum class EngineStatus { Cold, Booting, Ready, Broken }

/**
 * Drives the upstream conversion engine, which runs untouched inside an
 * invisible WebView.
 *
 * The engine keeps every one of its ~80 format handlers, but nothing about it
 * is user facing here: files go in over the app assets domain and come back as
 * transferable ArrayBuffers, so no copy is ever made through base64 unless the
 * device's WebView is too old to carry binary messages.
 */
@SuppressLint("SetJavaScriptEnabled")
class ConversionEngine(context: Context) {

    val assetStore = EngineAssetStore(context.applicationContext)

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private var webView: WebView? = null
    private var nativePort: WebMessagePortCompat? = null

    private val portThread = HandlerThread("convert-engine-port").apply { start() }
    private val portHandler = Handler(portThread.looper)

    private val _status = MutableStateFlow(EngineStatus.Cold)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _formats = MutableStateFlow<List<FormatOption>>(emptyList())
    val formats: StateFlow<List<FormatOption>> = _formats.asStateFlow()

    private val _logs = MutableSharedFlow<String>(replay = 120, extraBufferCapacity = 240)
    val logs: SharedFlow<String> = _logs

    private val ready = CompletableDeferred<Unit>()

    /** Input files currently exposed to the page, keyed by a random token. */
    private val servedInputs = ConcurrentHashMap<String, Uri>()

    /** State of every job the engine is working on right now. */
    private val jobs = ConcurrentHashMap<String, Job>()

    private val outputRoot = File(appContext.cacheDir, "outputs")

    private class Job(val directory: File) {
        val deferred = CompletableDeferred<ConversionResult>()
        val files = mutableListOf<ConvertedFile>()
        var expected: Int = -1
        var path: List<String> = emptyList()
        var progress: (ConversionProgress) -> Unit = {}
        @Volatile var state = ConversionProgress(ConversionStage.Preparing)

        /** Applies an update and pushes the whole picture to the UI. */
        fun report(update: (ConversionProgress) -> ConversionProgress) {
            state = update(state)
            progress(state)
        }
        /** Open stream for the base64 fallback path. */
        var stream: FileOutputStream? = null
        var streamName: String = ""

        @Synchronized
        fun record(name: String, file: File) {
            files += ConvertedFile(name, file.absolutePath, file.length())
            settleIfComplete()
        }

        @Synchronized
        fun finish(count: Int, usedPath: List<String>) {
            expected = count
            path = usedPath
            settleIfComplete()
        }

        @Synchronized
        private fun settleIfComplete() {
            if (expected >= 0 && files.size >= expected) {
                deferred.complete(ConversionResult.Success(files.toList(), path))
            }
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Lifecycle                                                              */
    /* ---------------------------------------------------------------------- */

    /**
     * Creates the headless WebView. It is attached to the UI tree fully
     * transparent: handlers that rasterise DOM content need a real, laid out
     * page, which a detached WebView cannot give them.
     */
    fun createWebView(context: Context): WebView {
        webView?.let { return it }

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(DOMAIN)
            .addPathHandler("/convert/", EnginePathHandler())
            .addPathHandler("/io/", InputPathHandler())
            .build()

        val view = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = false
            isFocusable = false
            isFocusableInTouchMode = false
            // It sits behind the whole UI: swallow anything that reaches it.
            setOnTouchListener { _, _ -> true }

            addJavascriptInterface(Bridge(), "AndroidEngine")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String) {
                    openBinaryChannel(view)
                    view.evaluateJavascript("window.ConvertEngine.boot()", null)
                }
            }
        }

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        webView = view
        _status.value = EngineStatus.Booting
        view.loadUrl("https://$DOMAIN/convert/engine.html")
        return view
    }

    fun release() {
        nativePort?.close()
        nativePort = null
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        _status.value = EngineStatus.Cold
    }

    suspend fun awaitReady() = ready.await()

    /* ---------------------------------------------------------------------- */
    /* Conversion                                                             */
    /* ---------------------------------------------------------------------- */

    /**
     * Converts [inputs] from one format to another and returns the produced
     * files, already written to the app's cache.
     */
    suspend fun convert(
        inputs: List<InputFile>,
        fromId: Int,
        toId: Int,
        simpleMode: Boolean,
        onProgress: (ConversionProgress) -> Unit
    ): ConversionResult {
        awaitReady()

        val jobId = UUID.randomUUID().toString()
        val directory = File(outputRoot, jobId).apply { mkdirs() }
        val job = Job(directory).apply { progress = onProgress }
        jobs[jobId] = job

        val tokens = inputs.map { input ->
            val token = UUID.randomUUID().toString()
            servedInputs[token] = input.uri
            token to input
        }

        job.report { ConversionProgress(ConversionStage.Preparing) }

        val request = buildString {
            append("""{"job":"""").append(jobId).append("""","fromId":""").append(fromId)
            append(""","toId":""").append(toId)
            append(""","simpleMode":""").append(simpleMode)
            append(""","inputs":[""")
            tokens.forEachIndexed { index, (token, input) ->
                if (index > 0) append(",")
                append("""{"name":""").append(quote(input.name))
                append(""","url":"https://""").append(DOMAIN).append("""/io/""").append(token).append(""""}""")
            }
            append("]}")
        }

        return try {
            val view = webView
            if (view == null) {
                job.deferred.complete(ConversionResult.Failure(FailureReason.Engine, "Engine not running."))
            } else {
                withContext(Dispatchers.Main) {
                    view.evaluateJavascript("window.ConvertEngine.convert(${quote(request)})", null)
                }
            }
            job.deferred.await()
        } finally {
            tokens.forEach { (token, _) -> servedInputs.remove(token) }
            jobs.remove(jobId)
        }
    }

    fun cancel(jobId: String) {
        webView?.post { webView?.evaluateJavascript("window.ConvertEngine.cancel(${quote(jobId)})", null) }
    }

    /** Cancels every running job, used when the user backs out of a conversion. */
    fun cancelAll() = jobs.keys.forEach(::cancel)

    /* ---------------------------------------------------------------------- */
    /* Binary channel                                                         */
    /* ---------------------------------------------------------------------- */

    /**
     * Hands the page one end of a message channel. Output files travel back
     * over it as transferable ArrayBuffers; devices whose WebView cannot carry
     * binary payloads silently fall back to chunked base64.
     */
    private fun openBinaryChannel(view: WebView) {
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        if (!supported) {
            Log.i(TAG, "WebView lacks binary messaging, using the base64 fallback.")
            return
        }

        val channel = WebViewCompat.createWebMessageChannel(view)
        val local = channel[0]
        nativePort = local

        var header: OutputHeader? = null
        local.setWebMessageCallback(portHandler, object : WebMessagePortCompat.WebMessageCallbackCompat() {
            override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                when (message?.type) {
                    WebMessageCompat.TYPE_STRING ->
                        header = runCatching { json.decodeFromString<OutputHeader>(message.data.orEmpty()) }.getOrNull()

                    WebMessageCompat.TYPE_ARRAY_BUFFER -> {
                        val current = header ?: return
                        header = null
                        val job = jobs[current.job] ?: return
                        val file = File(job.directory, safeName(current.index, current.name))
                        runCatching { file.writeBytes(message.arrayBuffer) }
                            .onSuccess { job.record(current.name, file) }
                            .onFailure { Log.e(TAG, "Could not store output", it) }
                    }
                }
            }
        })

        WebViewCompat.postWebMessage(
            view,
            WebMessageCompat(PORT_HANDSHAKE, arrayOf(channel[1])),
            Uri.parse("*")
        )
    }

    /* ---------------------------------------------------------------------- */
    /* JavaScript bridge                                                      */
    /* ---------------------------------------------------------------------- */

    private inner class Bridge {

        @JavascriptInterface
        fun emit(payload: String) {
            val event = parseEngineEvent(payload) ?: return
            handleEvent(event)
        }

        @JavascriptInterface
        fun chunk(jobId: String, base64: String) {
            val job = jobs[jobId] ?: return
            runCatching { job.stream?.write(Base64.decode(base64, Base64.DEFAULT)) }
                .onFailure { Log.e(TAG, "Could not append output chunk", it) }
        }
    }

    private fun handleEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Ready -> {
                _formats.value = event.formats
                _status.value = EngineStatus.Ready
                _logs.tryEmit("engine ready: ${event.handlers} handlers, ${event.formats.size} formats")
                ready.complete(Unit)
            }

            is EngineEvent.Log -> _logs.tryEmit("[${event.level}] ${event.message}")

            is EngineEvent.Searching ->
                jobs[event.job]?.report { it.copy(stage = ConversionStage.Searching) }

            is EngineEvent.Search ->
                jobs[event.job]?.report {
                    it.copy(
                        stage = ConversionStage.Searching,
                        explored = event.explored,
                        detail = event.candidate.joinToString(" → ")
                    )
                }

            is EngineEvent.Attempt ->
                jobs[event.job]?.report {
                    it.copy(
                        stage = ConversionStage.Converting,
                        path = event.path,
                        attempt = event.attempt,
                        steps = (event.path.size - 1).coerceAtLeast(0),
                        step = 0,
                        detail = ""
                    )
                }

            is EngineEvent.Step ->
                jobs[event.job]?.report {
                    it.copy(
                        stage = ConversionStage.Converting,
                        step = event.step,
                        steps = event.steps,
                        handler = event.handler,
                        detail = "${event.from} → ${event.to}"
                    )
                }

            is EngineEvent.HandlerInit ->
                jobs[event.job]?.report {
                    it.copy(stage = ConversionStage.Downloading, handler = event.handler)
                }

            is EngineEvent.HandlerReady ->
                jobs[event.job]?.report { it.copy(stage = ConversionStage.Converting) }

            is EngineEvent.DeadEnd -> {
                _logs.tryEmit("dead end ${event.path.joinToString(" → ")}: ${event.reason}")
                jobs[event.job]?.report { it.copy(deadEnds = it.deadEnds + 1) }
            }

            is EngineEvent.OutputBegin -> {
                val job = jobs[event.job] ?: return
                job.report { it.copy(stage = ConversionStage.Writing, detail = event.name) }
                val file = File(job.directory, safeName(event.index, event.name))
                job.stream = FileOutputStream(file)
                job.streamName = event.name
            }

            is EngineEvent.OutputEnd -> {
                val job = jobs[event.job] ?: return
                job.stream?.close()
                job.stream = null
                job.record(job.streamName, File(job.directory, safeName(event.index, job.streamName)))
            }

            is EngineEvent.Done -> jobs[event.job]?.finish(event.count, event.path)

            is EngineEvent.Failed -> {
                val reason = when (event.message) {
                    "no-path" -> FailureReason.NoPath
                    "cancelled" -> FailureReason.Cancelled
                    else -> FailureReason.Engine
                }
                jobs[event.job]?.deferred?.complete(ConversionResult.Failure(reason, event.message))
            }

            is EngineEvent.Fatal -> {
                _status.value = EngineStatus.Broken
                _logs.tryEmit("fatal: ${event.message}")
            }

            EngineEvent.Loaded -> _logs.tryEmit("engine bundle loaded")
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Resource serving                                                       */
    /* ---------------------------------------------------------------------- */

    /** Serves the engine bundle: bundled assets first, on-demand pack second. */
    private inner class EnginePathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val clean = path.removePrefix("/")
            val stream: InputStream = runCatching { appContext.assets.open("convert/$clean") }
                .getOrElse {
                    val file = assetStore.resolve(clean) ?: return notFound()
                    runCatching { file.inputStream() }.getOrElse { return notFound() }
                }
            return response(mimeOf(clean), stream)
        }
    }

    /** Streams a picked file straight from its content URI, with no copy. */
    private inner class InputPathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val uri = servedInputs[path.removePrefix("/")] ?: return notFound()
            val stream = runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
                ?: return notFound()
            return response("application/octet-stream", stream)
        }
    }

    private fun response(mime: String, stream: InputStream) = WebResourceResponse(
        mime,
        null,
        200,
        "OK",
        mapOf(
            // Cross origin isolation lets the engines that want threads use them.
            "Cross-Origin-Resource-Policy" to "same-origin",
            "Cross-Origin-Embedder-Policy" to "require-corp",
            "Cross-Origin-Opener-Policy" to "same-origin",
            "Cache-Control" to "no-cache"
        ),
        stream
    )

    private fun notFound() = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArray(0).inputStream()
    )

    private fun mimeOf(path: String) = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html"
        "js", "mjs", "cjs" -> "text/javascript"
        "json" -> "application/json"
        "wasm" -> "application/wasm"
        "css" -> "text/css"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private fun safeName(index: Int, name: String) =
        "$index-" + name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)

    private fun quote(value: String) = JsonPrimitive(value).toString()

    private companion object {
        const val TAG = "ConversionEngine"
        const val DOMAIN = "appassets.androidplatform.net"
        const val PORT_HANDSHAKE = "__convert_native_port__"
    }
}
