package com.kitefoldlabs.bridle.feature.sftp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/** Name and size of a document picked through the Storage Access Framework. */
data class DocumentInfo(
    val name: String,
    val size: Long?,
)

/** Reads and writes documents the user picked with the Storage Access Framework. */
interface DocumentAccess {
    fun describe(uri: Uri): DocumentInfo

    /** @throws FileNotFoundException if the document can't be opened. */
    fun openInput(uri: Uri): InputStream

    /** Opens [uri] for writing, truncating it. @throws FileNotFoundException if it can't be opened. */
    fun openOutput(uri: Uri): OutputStream

    /** Deletes [uri] if possible (a partial download); never throws. */
    fun delete(uri: Uri)
}

/** [DocumentAccess] over the app's [android.content.ContentResolver]. */
class ContentResolverDocuments
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : DocumentAccess {
        override fun describe(uri: Uri): DocumentInfo {
            var name: String? = null
            var size: Long? = null
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0)
                        size = if (cursor.isNull(1)) null else cursor.getLong(1)
                    }
                }
            return DocumentInfo(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: FALLBACK_NAME, size)
        }

        override fun openInput(uri: Uri): InputStream =
            context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Cannot open the document")

        override fun openOutput(uri: Uri): OutputStream =
            context.contentResolver.openOutputStream(uri, "wt")
                ?: throw FileNotFoundException("Cannot open the document")

        override fun delete(uri: Uri) {
            try {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } catch (
                @Suppress("TooGenericExceptionCaught") ignored: Exception,
            ) {
                // Providers may refuse; a partial file is then left for the user to remove.
            }
        }

        private companion object {
            const val FALLBACK_NAME = "upload"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
interface DocumentsModule {
    @Binds
    fun documents(impl: ContentResolverDocuments): DocumentAccess
}
