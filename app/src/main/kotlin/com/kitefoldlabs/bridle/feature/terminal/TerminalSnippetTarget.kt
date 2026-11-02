package com.kitefoldlabs.bridle.feature.terminal

import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.terminal.BracketedPaste
import com.kitefoldlabs.bridle.core.terminalui.asTerminalConnection
import com.kitefoldlabs.bridle.feature.snippets.SnippetTarget
import com.kitefoldlabs.bridle.service.SessionCoordinator
import com.kitefoldlabs.bridle.service.TerminalTab
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pastes snippets into the open terminal tabs, then shows the terminal so the user can check the
 * command and press Enter there.
 *
 * The tab is the one on screen, else the selected one, if it is on the snippet's host; otherwise
 * the newest tab on that host. Only tabs whose session is open count: input typed while a tab
 * connects or reconnects is dropped, and an ended tab has nobody to type to.
 *
 * The text goes through [BracketedPaste], like any paste: sanitised, wrapped in paste markers when
 * the remote program has bracketed paste on, and never followed by Enter. When the program has it
 * off and the text has several lines, the shell would run each line as it arrives, so the paste
 * goes to the tab's screen instead, which asks first ([TerminalNavigator.requestPaste]).
 */
@Singleton
class TerminalSnippetTarget
    @Inject
    constructor(
        private val coordinator: SessionCoordinator,
        private val navigator: TerminalNavigator,
    ) : SnippetTarget {
        override suspend fun paste(
            hostId: EntityId,
            text: String,
        ): Boolean {
            val tab = tabFor(hostId) ?: return false
            val bracketed = (tab.attachment as TerminalAttachment).engine.isBracketedPasteEnabled
            if (BracketedPaste.needsConfirmation(text, bracketed)) {
                navigator.requestPaste(tab.id, text)
            } else {
                // The same path as the terminal screen's own paste (TerminalScreenState).
                tab.session.asTerminalConnection().send(BracketedPaste.encode(text, bracketed, sendEnter = false))
                navigator.show(tab.id)
            }
            return true
        }

        private fun tabFor(hostId: EntityId): TerminalTab? {
            val open =
                coordinator.tabs.value.filter { tab ->
                    tab.hostId == hostId &&
                        tab.attachment is TerminalAttachment &&
                        tab.session.state.value == TerminalState.Open
                }
            val preferred = listOf(navigator.visibleTabId.value, navigator.selectedTabId.value)
            return preferred.firstNotNullOfOrNull { id -> open.firstOrNull { it.id == id } } ?: open.lastOrNull()
        }
    }

/** Snippets paste into terminal tabs (fills [com.kitefoldlabs.bridle.feature.snippets.SnippetTargetModule]). */
@Module
@InstallIn(SingletonComponent::class)
interface TerminalSnippetTargetModule {
    @Binds
    fun snippetTarget(impl: TerminalSnippetTarget): SnippetTarget
}
