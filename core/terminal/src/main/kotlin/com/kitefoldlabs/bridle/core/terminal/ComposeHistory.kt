package com.kitefoldlabs.bridle.core.terminal

import com.kitefoldlabs.bridle.core.model.EntityId

/**
 * History of compose-box submissions for one host, most recent first, with shell-style up/down
 * navigation that keeps the text being written (the draft) when the user browses away from it.
 *
 * Entries are unique: submitting a text again moves it to the front. Blank texts and texts over
 * [MAX_ENTRY_LENGTH] characters are not recorded, and only the newest [capacity] are kept.
 *
 * History can contain anything the user typed, including secrets, so callers should offer
 * [remove] and [clear] and store it no less carefully than scrollback. Not thread-safe.
 */
public class ComposeHistory(
    entries: List<String> = emptyList(),
    public val capacity: Int = DEFAULT_CAPACITY,
) {
    private val items = ArrayDeque<String>()
    private var cursor = NOT_NAVIGATING
    private var draft = ""

    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
        entries
            .asSequence()
            .filter(::isRecordable)
            .distinct()
            .take(capacity)
            .forEach(items::addLast)
    }

    /** The entries, most recent first. */
    public val entries: List<String>
        get() = items.toList()

    /** True while the user is browsing history rather than editing the draft. */
    public val isNavigating: Boolean
        get() = cursor != NOT_NAVIGATING

    /**
     * Records a submission and ends navigation.
     * @return false if [text] was not recorded (blank or too long).
     */
    public fun add(text: String): Boolean {
        resetNavigation()
        if (!isRecordable(text)) return false
        items.remove(text)
        items.addFirst(text)
        if (items.size > capacity) items.removeLast()
        return true
    }

    /**
     * Moves to the next older entry ("up"). [currentText] is saved as the draft when navigation
     * starts. Returns the text to show, or null when there is no older entry (stay put).
     */
    public fun previous(currentText: String): String? {
        if (cursor + 1 >= items.size) return null
        if (!isNavigating) draft = currentText
        cursor++
        return items[cursor]
    }

    /**
     * Moves to the next newer entry ("down"). Moving past the newest entry returns the saved
     * draft and ends navigation. Returns null when not navigating (stay put).
     */
    public fun next(): String? {
        if (!isNavigating) return null
        cursor--
        return if (isNavigating) items[cursor] else draft.also { draft = "" }
    }

    /** Ends navigation and forgets the draft, e.g. when the compose box is dismissed. */
    public fun resetNavigation() {
        cursor = NOT_NAVIGATING
        draft = ""
    }

    /** Forgets one entry, e.g. one that contained a secret. */
    public fun remove(text: String): Boolean {
        resetNavigation()
        return items.remove(text)
    }

    /** Forgets every entry. */
    public fun clear() {
        resetNavigation()
        items.clear()
    }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 200

        /** Longer submissions (pasted logs, say) are sent but not remembered. */
        public const val MAX_ENTRY_LENGTH: Int = 16 * 1024

        private const val NOT_NAVIGATING = -1

        private fun isRecordable(text: String): Boolean = text.isNotBlank() && text.length <= MAX_ENTRY_LENGTH
    }
}

/**
 * Per-host [ComposeHistory] instances, created on first use. [snapshot] and the constructor
 * convert to and from a plain map for persistence.
 */
public class ComposeHistories(
    snapshot: Map<EntityId, List<String>> = emptyMap(),
    public val capacity: Int = ComposeHistory.DEFAULT_CAPACITY,
) {
    private val byHost =
        snapshot.mapValuesTo(mutableMapOf()) { (_, entries) -> ComposeHistory(entries, capacity) }

    /** The history for [hostId], created empty if needed. */
    public fun forHost(hostId: EntityId): ComposeHistory =
        byHost.getOrPut(hostId) { ComposeHistory(capacity = capacity) }

    /** Drops the history of a deleted host. */
    public fun removeHost(hostId: EntityId) {
        byHost.remove(hostId)
    }

    /** Every non-empty history, most recent entry first. */
    public fun snapshot(): Map<EntityId, List<String>> =
        byHost.mapValues { (_, history) -> history.entries }.filterValues { it.isNotEmpty() }
}
