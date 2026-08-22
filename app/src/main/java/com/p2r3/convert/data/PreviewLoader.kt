package com.p2r3.convert.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** What the in-app viewer can show for one file. */
sealed interface Preview {
    data class Picture(val image: ImageBitmap, val width: Int, val height: Int) : Preview
    data class Text(val body: String, val truncated: Boolean) : Preview
    /** Anything we cannot render: shown as a hex dump, which is still telling. */
    data class Binary(val dump: String, val size: Long) : Preview
    data class Failed(val reason: String) : Preview
}

/**
 * Turns a file into something worth looking at.
 *
 * Deliberately format agnostic: it asks Android whether the bytes decode as an
 * image, then whether they look like text, and falls back to a hex dump. That
 * covers the engine's ~900 formats without a lookup table that would rot.
 */
class PreviewLoader(private val context: Context) {

    suspend fun load(uri: Uri): Preview = withContext(Dispatchers.IO) {
        val head = runCatching { read(uri, HEAD_BYTES) }.getOrNull()
            ?: return@withContext Preview.Failed("Non riesco a leggere il file.")
        if (head.isEmpty()) return@withContext Preview.Failed("Il file è vuoto.")

        decodeImage(uri)?.let { return@withContext it }
        if (looksLikeText(head)) {
            val body = runCatching { read(uri, TEXT_BYTES) }.getOrNull()
                ?: return@withContext Preview.Failed("Non riesco a leggere il file.")
            return@withContext Preview.Text(
                body = body.decodeToString(),
                truncated = body.size >= TEXT_BYTES
            )
        }
        Preview.Binary(hexDump(head.copyOf(minOf(head.size, DUMP_BYTES))), size(uri))
    }

    /** Bounds-only decode first, so a huge image never costs a full decode. */
    private fun decodeImage(uri: Uri): Preview.Picture? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { open(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching { open(uri)?.use { BitmapFactory.decodeStream(it, null, options) } }
            .getOrNull() ?: return null
        return Preview.Picture(bitmap.asImageBitmap(), bounds.outWidth, bounds.outHeight)
    }

    private fun open(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)

    private fun read(uri: Uri, limit: Int): ByteArray = open(uri)!!.use { stream ->
        val buffer = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val count = stream.read(buffer, read, limit - read)
            if (count < 0) break
            read += count
        }
        buffer.copyOf(read)
    }

    private fun size(uri: Uri): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull()?.takeIf { it >= 0 } ?: 0L

    /**
     * A NUL byte means binary; otherwise text is mostly printable. Being wrong
     * only costs a hex dump instead of gibberish, so the bar is deliberately low.
     */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = bytes.copyOf(minOf(bytes.size, 4096))
        if (sample.any { it == 0.toByte() }) return false
        val printable = sample.count { byte ->
            val value = byte.toInt() and 0xFF
            value >= 0x20 || value == 0x09 || value == 0x0A || value == 0x0D
        }
        return printable.toFloat() / sample.size > 0.9f
    }

    private fun hexDump(bytes: ByteArray): String = buildString {
        for (offset in bytes.indices step 16) {
            val row = bytes.copyOfRange(offset, minOf(offset + 16, bytes.size))
            append("%08x  ".format(offset))
            row.forEach { append("%02x ".format(it)) }
            repeat(16 - row.size) { append("   ") }
            append(" |")
            row.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(if (value in 0x20..0x7E) value.toChar() else '.')
            }
            append("|\n")
        }
    }

    private companion object {
        const val HEAD_BYTES = 64 * 1024
        const val TEXT_BYTES = 256 * 1024
        const val DUMP_BYTES = 1024
        const val MAX_DIMENSION = 2048
    }
}
