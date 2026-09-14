package io.github.cyclingtrainer.app.workout

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.cyclingtrainer.app.workout.ZwoParser

/**
 * Loads .zwo courses from a user-chosen public folder (typically
 * Documents/CyclingTrainer) granted once via the system folder picker
 * (ACTION_OPEN_DOCUMENT_TREE). After the grant, every app launch reloads the
 * folder automatically — no further permission prompts.
 *
 * Scoped storage (API 29+) forbids reading arbitrary public folders without
 * a grant, so a one-time pick is unavoidable; the picker URI is persisted by
 * the caller with takePersistableUriPermission.
 */
object CourseSource {

    /** Parses every .zwo directly inside [treeUri]; broken files are skipped. */
    fun load(resolver: ContentResolver, treeUri: Uri): List<Workout> {
        val children = listDocuments(resolver, treeUri)
        val out = ArrayList<Workout>(children.size)
        for ((docId, name) in children) {
            if (!name.endsWith(".zwo", ignoreCase = true)) continue
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            runCatching {
                resolver.openInputStream(fileUri)?.use { ZwoParser.parse(it) }
            }.getOrNull()?.let { out += it }
        }
        return out.sortedBy { it.name }
    }

    private fun listDocuments(
        resolver: ContentResolver,
        treeUri: Uri,
    ): List<Pair<String, String>> {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri) ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        return runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val list = ArrayList<Pair<String, String>>()
                while (c.moveToNext()) {
                    val id = if (idCol >= 0) c.getString(idCol) else null ?: continue
                    if (id == null) continue
                    val name = if (nameCol >= 0) c.getString(nameCol) else "" ?: ""
                    list += id to (name ?: "")
                }
                list
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
