package com.kitefoldlabs.bridle.feature.snippets

import com.kitefoldlabs.bridle.core.model.EntityId
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Where a snippet is typed when the user picks "Paste into terminal". The terminal feature
 * implements this for its open tabs and binds it in a Hilt module:
 *
 * ```
 * @Binds fun snippetTarget(impl: TerminalSnippetTarget): SnippetTarget
 * ```
 *
 * Until it does, [SnippetTargetModule] leaves the binding absent and snippets fall back to
 * [None], which reports that no terminal is open.
 */
fun interface SnippetTarget {
    /**
     * Pastes [text] (as a bracketed paste, not executed until the user presses Enter) into the
     * focused terminal tab on [hostId]. Returns false if no terminal on that host is open.
     */
    suspend fun paste(
        hostId: EntityId,
        text: String,
    ): Boolean

    /** No terminal integration: nothing is ever pasted. */
    object None : SnippetTarget {
        override suspend fun paste(
            hostId: EntityId,
            text: String,
        ): Boolean = false
    }
}

/** Lets the terminal feature bind a [SnippetTarget] without clashing with a default binding. */
@Module
@InstallIn(SingletonComponent::class)
interface SnippetTargetModule {
    @BindsOptionalOf
    fun snippetTarget(): SnippetTarget
}
