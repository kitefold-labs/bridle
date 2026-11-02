package com.kitefoldlabs.bridle.feature.hosts

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** The picked document is bigger than the screen that asked for it accepts. */
class DocumentTooLargeException : IOException("The document is too large")

/**
 * Reads small text documents the user picked with the Storage Access Framework (an
 * `~/.ssh/config` or a private key file). Never holds on to the document or its URI.
 */
class DocumentReader
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /**
         * The document's text, decoded as UTF-8. No more than [maxChars] + 1 characters are read,
         * and the read buffer is zeroed afterwards (the document may be a private key).
         *
         * @throws DocumentTooLargeException if it has more than [maxChars] characters.
         * @throws IOException if it can't be opened or read.
         */
        suspend fun readText(
            uri: Uri,
            maxChars: Int = MAX_CHARS,
        ): String =
            withContext(Dispatchers.IO) {
                val stream =
                    try {
                        context.contentResolver.openInputStream(uri)
                    } catch (e: SecurityException) {
                        // The provider revoked or never granted access.
                        throw IOException("Can't open the document", e)
                    } ?: throw IOException("Can't open the document")
                val buffer = CharArray(maxChars + 1)
                try {
                    stream.reader().use { reader ->
                        var total = 0
                        while (total < buffer.size) {
                            val read = reader.read(buffer, total, buffer.size - total)
                            if (read < 0) break
                            total += read
                        }
                        if (total > maxChars) throw DocumentTooLargeException()
                        String(buffer, 0, total)
                    }
                } finally {
                    buffer.fill('\u0000')
                }
            }

        companion object {
            /** Generous for an ssh config or a 4096-bit RSA key. */
            const val MAX_CHARS: Int = 256 * 1024
        }
    }
