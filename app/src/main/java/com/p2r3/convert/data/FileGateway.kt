package com.p2r3.convert.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.p2r3.convert.engine.ConvertedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What we know about a file the user picked, without reading its contents. */
data class PickedFile(val uri: Uri, val name: String, val size: Long, val mimeType: String) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** Reads picked files and hands finished ones back to the system. */
class FileGateway(private val context: Context) {

    fun describe(uri: Uri): PickedFile {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
            }
        }
        val mime = context.contentResolver.getType(uri).orEmpty()
        return PickedFile(uri, name.ifBlank { "file" }, size, mime)
    }

    /**
     * Copies a converted file into the public Downloads folder.
     * Returns the resulting media URI, or null when the write failed.
     */
    suspend fun saveToDownloads(file: ConvertedFile): Uri? = withContext(Dispatchers.IO) {
        val source = File(file.path)
        if (!source.exists()) return@withContext null

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Convert")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null

        runCatching {
            resolver.openOutputStream(target)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("No output stream for $target")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(target, values, null, null)
            target
        }.getOrElse {
            resolver.delete(target, null, null)
            null
        }
    }

    /** Builds a share intent for one or more converted files. */
    fun shareIntent(files: List<ConvertedFile>): Intent? {
        val uris = files.mapNotNull { file ->
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(file.path))
            }.getOrNull()
        }
        if (uris.isEmpty()) return null

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first()) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        }
        return intent.apply {
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Intent that opens a converted file in whichever app can handle it. */
    fun openIntent(file: ConvertedFile): Intent? {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(file.path))
        }.getOrNull() ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

/** Human readable byte count, e.g. "12,4 MB". */
fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f kB".format(bytes / 1024.0)
    else -> "$bytes B"
}
