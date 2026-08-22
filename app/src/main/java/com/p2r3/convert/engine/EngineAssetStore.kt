package com.p2r3.convert.engine

import android.content.Context
import android.util.Log
import com.p2r3.convert.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Serializable
private data class AssetManifest(
    /** Upstream engine commit, shown to the user. */
    val engineVersion: String,
    /**
     * Identity of the downloadable payload itself, derived from its checksums.
     * Separate from [engineVersion] because rebuilding the shell reshuffles the
     * bundler's file names without changing a single downloadable byte.
     */
    val packVersion: String = engineVersion,
    val assets: List<RemoteAsset> = emptyList()
)

@Serializable
data class RemoteAsset(
    /** Path inside the engine bundle, e.g. "wasm/pandoc.wasm". */
    val path: String,
    /** Flat file name the pack is published under. */
    val asset: String,
    val size: Long,
    val sha256: String
)

/** What the UI shows while a heavy engine is being fetched. */
data class AssetDownload(
    val name: String,
    val downloaded: Long,
    val total: Long
) {
    val fraction: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
}

/**
 * Serves the engine's heavy WASM blobs, downloading them the first time a
 * conversion actually needs one and keeping them on the device afterwards.
 *
 * Bundling all of them would triple the install size for engines most users
 * never touch, so the APK carries only the light majority of the build.
 */
class EngineAssetStore(private val context: Context) {

    private val manifestJson = Json { ignoreUnknownKeys = true }

    private val manifest: AssetManifest by lazy {
        runCatching {
            context.assets.open(MANIFEST_NAME).use {
                manifestJson.decodeFromString<AssetManifest>(it.readBytes().decodeToString())
            }
        }.getOrElse {
            Log.w(TAG, "No on-demand asset manifest bundled.", it)
            AssetManifest(BuildConfig.ENGINE_VERSION)
        }
    }

    private val byPath: Map<String, RemoteAsset> by lazy { manifest.assets.associateBy { it.path } }

    private val storeDir: File by lazy {
        File(context.filesDir, "engine/${manifest.packVersion}").apply { mkdirs() }
    }

    private val _download = MutableStateFlow<AssetDownload?>(null)
    /** Non-null while a download is in flight, for the progress UI. */
    val download: StateFlow<AssetDownload?> = _download.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    /** Every heavy asset this build can fetch, for the settings screen. */
    val remoteAssets: List<RemoteAsset> get() = manifest.assets

    fun isDownloaded(asset: RemoteAsset): Boolean =
        File(storeDir, asset.asset).let { it.exists() && it.length() == asset.size }

    fun downloadedBytes(): Long = manifest.assets.filter(::isDownloaded).sumOf { it.size }

    fun totalRemoteBytes(): Long = manifest.assets.sumOf { it.size }

    /** True when the path is served on demand rather than from the APK. */
    fun isRemote(path: String): Boolean = byPath.containsKey(path)

    /**
     * Returns the local file for an on-demand path, downloading it if needed.
     * Blocking on purpose: it is called from the WebView's resource thread, so
     * the page simply waits for the fetch the same way it would for slow I/O.
     */
    fun resolve(path: String): File? {
        val asset = byPath[path] ?: return null
        val target = File(storeDir, asset.asset)
        if (target.exists() && target.length() == asset.size) return target
        return runCatching { fetch(asset, target) }.getOrElse {
            Log.e(TAG, "Could not fetch ${asset.path}", it)
            _failure.value = asset.path.substringAfterLast('/')
            _download.value = null
            null
        }
    }

    /** Downloads everything that is still missing, for offline use. */
    fun downloadAll(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val missing = manifest.assets.filterNot(::isDownloaded)
        missing.forEachIndexed { index, asset ->
            onProgress(index, missing.size)
            resolve(asset.path)
        }
        onProgress(missing.size, missing.size)
    }

    fun clearDownloads(): Boolean = storeDir.deleteRecursively().also { storeDir.mkdirs() }

    private fun fetch(asset: RemoteAsset, target: File): File {
        val url = "${BuildConfig.ENGINE_ASSET_BASE_URL}/engine-${manifest.packVersion}/${asset.asset}"
        _failure.value = null
        _download.value = AssetDownload(asset.path.substringAfterLast('/'), 0, asset.size)

        val partial = File(target.parentFile, "${asset.asset}.part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} for $url")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        _download.value = AssetDownload(asset.path.substringAfterLast('/'), written, asset.size)
                    }
                }
            }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            if (checksum != asset.sha256) {
                partial.delete()
                throw IOException("Checksum mismatch for ${asset.path}")
            }
            if (!partial.renameTo(target)) throw IOException("Could not store ${asset.path}")
            return target
        } finally {
            connection.disconnect()
            partial.delete()
            _download.value = null
        }
    }

    private companion object {
        const val TAG = "EngineAssetStore"
        const val MANIFEST_NAME = "engine-assets.json"
    }
}
