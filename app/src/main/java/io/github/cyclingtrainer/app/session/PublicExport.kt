package io.github.cyclingtrainer.app.session

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Writes exported files into the public Downloads folder so the user can
 * actually find them (the app's own rides/ dir is app-private and invisible
 * without a file manager + Android/data access).
 *
 * minSdk 33 -> MediaStore insert works without any runtime permission
 * (API 29+ scoped storage; the Downloads collection is user-writable).
 */
object PublicExport {

    /** Folder inside Downloads where exported files land. */
    const val FOLDER = "CyclingTrainer"

    /**
     * Removes any file with the same name in Download/CyclingTrainer so a
     * re-export overwrites instead of producing "name (1).fit" duplicates.
     */
    fun deleteExisting(context: Context, fileName: String): Boolean = runCatching {
        val resolver = context.contentResolver
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(
            fileName,
            Environment.DIRECTORY_DOWNLOADS + "/$FOLDER/",
        )
        resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, selection, args) > 0
    }.getOrDefault(false)

    /**
     * Saves [bytes] as [fileName] under Download/CyclingTrainer/ (overwrites
     * an existing file with the same name).
     * @return the content Uri on success (or null on failure)
     */
    fun writeFitToDownloads(
        context: Context,
        fileName: String,
        bytes: ByteArray,
    ): Uri? = runCatching {
        deleteExisting(context, fileName)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/$FOLDER",
            )
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: run { resolver.delete(uri, null, null); return null }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }.getOrNull()

    /** Cross-check readability via the returned Uri. */
    fun exists(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
