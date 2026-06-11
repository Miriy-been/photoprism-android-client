package ua.com.radiokot.photoprism.features.importt.model

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.fasterxml.jackson.annotation.JsonCreator
import okio.Source
import okio.source
import java.io.IOException

data class ImportableFile
@JsonCreator
constructor(
    val contentUri: String,
    val displayName: String,
    val mimeType: String?,
    val size: Long,
    val treeUri: String? = null,
) {
    @SuppressLint("Recycle")
    fun source(contentResolver: ContentResolver): Source {
        // Try the content URI first (MediaStore / shared content URI)
        contentResolver.openInputStream(contentUri.toUri())?.let { return it.source() }

        // Fallback: try SAF document URI via tree URI (for sync with folder picker)
        if (treeUri != null) {
            val treeUriParsed = treeUri.toUri()
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUriParsed)
            val fileDocId = "$treeDocId/$displayName"
            val safUri = DocumentsContract.buildDocumentUriUsingTree(treeUriParsed, fileDocId)
            contentResolver.openInputStream(safUri)?.let { return it.source() }
        }

        throw IOException("Can't open input stream for $contentUri")
    }
}

val Iterable<ImportableFile>.sizeMb: Double
    get() = sumOf(ImportableFile::size).toDouble() / (1024 * 1024)
