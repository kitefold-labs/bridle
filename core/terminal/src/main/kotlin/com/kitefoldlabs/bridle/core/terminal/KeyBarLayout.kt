package com.kitefoldlabs.bridle.core.terminal

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a key-bar key does. Serialised with a `"type"` discriminator; see
 * `docs/terminal-input.md` for the JSON format.
 */
@Serializable
public sealed interface KeyAction {
    /** A named key such as Escape or Up, with optional modifiers (`"type": "key"`). */
    @Serializable
    @SerialName("key")
    public data class Press(
        val key: Key,
        val modifiers: Modifiers = Modifiers.NONE,
    ) : KeyAction

    /**
     * One character with modifiers, for chords such as Ctrl+C (`"type": "char"`). [char] must be
     * exactly one printable code point.
     */
    @Serializable
    @SerialName("char")
    public data class Chord(
        val char: String,
        val modifiers: Modifiers = Modifiers.NONE,
    ) : KeyAction

    /** Taps the sticky on-screen [modifier] (`"type": "modifier"`). */
    @Serializable
    @SerialName("modifier")
    public data class Toggle(
        val modifier: StickyModifier,
    ) : KeyAction

    /** Types literal, printable [text] (`"type": "text"`). */
    @Serializable
    @SerialName("text")
    public data class Text(
        val text: String,
    ) : KeyAction

    /**
     * Runs [actions] in order (`"type": "macro"`), e.g. `/compact` then Enter. Macros cannot
     * contain other macros or modifier toggles, and ignore sticky modifiers.
     */
    @Serializable
    @SerialName("macro")
    public data class Macro(
        val actions: List<KeyAction>,
    ) : KeyAction
}

/** One key on the bar: a [label], the tap [action], and an optional swipe-up alternate. */
@Serializable
public data class KeyBarKey(
    val label: String,
    val action: KeyAction,
    val swipeUp: KeyAction? = null,
    val swipeUpLabel: String? = null,
)

/** Thrown by [KeyBarLayout.parse] for malformed, invalid or oversized layouts. */
public class KeyBarLayoutException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * A Termux-style extra-keys bar: rows of keys shown above the soft keyboard.
 *
 * Layouts are validated on construction (including when parsed): sizes are capped so a shared
 * layout file can't exhaust memory or the screen, and text, characters and labels must be
 * printable. Control bytes can only come from `key` and `char` actions, which keeps every layout
 * readable.
 *
 * @throws IllegalArgumentException if the layout breaks one of the rules above.
 */
@Serializable
public data class KeyBarLayout(
    val rows: List<List<KeyBarKey>>,
) {
    init {
        validate()
    }

    /** This layout as pretty-printed JSON, readable by [parse]. */
    public fun toJson(): String = json.encodeToString(serializer(), this)

    private fun validate() {
        require(rows.isNotEmpty()) { "layout must have at least one row" }
        require(rows.size <= MAX_ROWS) { "layout has ${rows.size} rows; the limit is $MAX_ROWS" }
        rows.forEachIndexed { r, row ->
            require(row.isNotEmpty()) { "rows[$r] is empty" }
            require(row.size <= MAX_KEYS_PER_ROW) {
                "rows[$r] has ${row.size} keys; the limit is $MAX_KEYS_PER_ROW"
            }
            row.forEachIndexed { k, key -> validateKey("rows[$r][$k]", key) }
        }
    }

    public companion object {
        public const val MAX_ROWS: Int = 6
        public const val MAX_KEYS_PER_ROW: Int = 16
        public const val MAX_LABEL_LENGTH: Int = 16
        public const val MAX_TEXT_LENGTH: Int = 256
        public const val MAX_MACRO_ACTIONS: Int = 32

        /** Upper bound on the JSON accepted by [parse], checked before decoding. */
        public const val MAX_JSON_LENGTH: Int = 64 * 1024

        @OptIn(ExperimentalSerializationApi::class)
        private val json =
            Json {
                ignoreUnknownKeys = true
                decodeEnumsCaseInsensitive = true
                explicitNulls = false
                prettyPrint = true
            }

        /**
         * Parses and validates a layout. Unknown fields are ignored and enum names are
         * case-insensitive, so layouts written for newer versions still load.
         *
         * @throws KeyBarLayoutException with a message naming the offending field.
         */
        public fun parse(text: String): KeyBarLayout {
            if (text.length > MAX_JSON_LENGTH) {
                throw KeyBarLayoutException(
                    "Key-bar layout is ${text.length} characters; the limit is $MAX_JSON_LENGTH",
                )
            }
            return try {
                json.decodeFromString(serializer(), text)
            } catch (e: IllegalArgumentException) {
                // Also catches SerializationException, which extends IllegalArgumentException.
                throw KeyBarLayoutException("Invalid key-bar layout: ${e.message}", e)
            }
        }

        private fun press(
            label: String,
            key: Key,
            modifiers: Modifiers = Modifiers.NONE,
            swipeUp: KeyAction? = null,
            swipeUpLabel: String? = null,
        ) = KeyBarKey(label, KeyAction.Press(key, modifiers), swipeUp, swipeUpLabel)

        private fun text(
            value: String,
            swipeUp: String? = null,
        ) = KeyBarKey(value, KeyAction.Text(value), swipeUp?.let { KeyAction.Text(it) }, swipeUp)

        private fun toggle(modifier: StickyModifier) = KeyBarKey(modifier.name, KeyAction.Toggle(modifier))

        private fun command(slash: String) =
            KeyBarKey(slash, KeyAction.Macro(listOf(KeyAction.Text(slash), KeyAction.Press(Key.Enter))))

        /** General-purpose layout modeled on Termux's default extra keys. */
        public val DEFAULT: KeyBarLayout =
            KeyBarLayout(
                listOf(
                    listOf(
                        press("ESC", Key.Escape),
                        text("/", swipeUp = "\\"),
                        text("-", swipeUp = "_"),
                        text("|", swipeUp = "&"),
                        text("~", swipeUp = "`"),
                        press("↑", Key.Up, swipeUp = KeyAction.Press(Key.PageUp), swipeUpLabel = "PGUP"),
                    ),
                    listOf(
                        press(
                            "TAB",
                            Key.Tab,
                            swipeUp = KeyAction.Press(Key.Tab, Modifiers.SHIFT),
                            swipeUpLabel = "⇧TAB",
                        ),
                        toggle(StickyModifier.CTRL),
                        toggle(StickyModifier.ALT),
                        press("←", Key.Left, swipeUp = KeyAction.Press(Key.Home), swipeUpLabel = "HOME"),
                        press("↓", Key.Down, swipeUp = KeyAction.Press(Key.PageDown), swipeUpLabel = "PGDN"),
                        press("→", Key.Right, swipeUp = KeyAction.Press(Key.End), swipeUpLabel = "END"),
                    ),
                ),
            )

        /**
         * Layout for driving Claude Code: Esc (interrupt; swipe up for Esc Esc, rewind),
         * Shift+Tab (cycle permission mode), Ctrl+C, quick y/n answers, menu navigation and the
         * most used slash commands.
         */
        public val CLAUDE: KeyBarLayout =
            KeyBarLayout(
                listOf(
                    listOf(
                        press(
                            "ESC",
                            Key.Escape,
                            swipeUp = KeyAction.Macro(listOf(KeyAction.Press(Key.Escape), KeyAction.Press(Key.Escape))),
                            swipeUpLabel = "ESC×2",
                        ),
                        press("⇧TAB", Key.Tab, Modifiers.SHIFT),
                        KeyBarKey("^C", KeyAction.Chord("c", Modifiers.CTRL)),
                        text("/"),
                        press("↑", Key.Up, swipeUp = KeyAction.Press(Key.PageUp), swipeUpLabel = "PGUP"),
                        press("⏎", Key.Enter),
                    ),
                    listOf(
                        text("y"),
                        text("n"),
                        command("/compact"),
                        command("/clear"),
                        command("/resume"),
                        press("↓", Key.Down, swipeUp = KeyAction.Press(Key.PageDown), swipeUpLabel = "PGDN"),
                    ),
                ),
            )

        private fun validateKey(
            path: String,
            key: KeyBarKey,
        ) {
            validateLabel("$path.label", key.label)
            key.swipeUpLabel?.let { validateLabel("$path.swipeUpLabel", it) }
            validateAction("$path.action", key.action, inMacro = false)
            key.swipeUp?.let { validateAction("$path.swipeUp", it, inMacro = false) }
        }

        private fun validateLabel(
            path: String,
            label: String,
        ) {
            require(label.isNotBlank()) { "$path must not be blank" }
            require(label.length <= MAX_LABEL_LENGTH) {
                "$path is ${label.length} characters; the limit is $MAX_LABEL_LENGTH"
            }
            require(label.none(::isTerminalControl)) { "$path must not contain control characters" }
        }

        private fun validateAction(
            path: String,
            action: KeyAction,
            inMacro: Boolean,
        ) {
            when (action) {
                is KeyAction.Press -> Unit
                is KeyAction.Chord -> {
                    val c = action.char
                    require(c.isNotEmpty() && c.codePointCount(0, c.length) == 1) {
                        "$path.char must be exactly one character"
                    }
                    require(c.none(::isTerminalControl)) {
                        "$path.char must be printable; use a \"key\" action for Enter, Tab, Escape…"
                    }
                }
                is KeyAction.Toggle -> require(!inMacro) { "$path: a macro cannot toggle modifiers" }
                is KeyAction.Text -> validateText("$path.text", action.text)
                is KeyAction.Macro -> validateMacro(path, action, inMacro)
            }
        }

        private fun validateText(
            path: String,
            text: String,
        ) {
            require(text.isNotEmpty()) { "$path must not be empty" }
            require(text.length <= MAX_TEXT_LENGTH) {
                "$path is ${text.length} characters; the limit is $MAX_TEXT_LENGTH"
            }
            require(text.none(::isTerminalControl)) {
                "$path must be printable; use a \"key\" action for Enter, Tab, Escape…"
            }
        }

        private fun validateMacro(
            path: String,
            macro: KeyAction.Macro,
            inMacro: Boolean,
        ) {
            require(!inMacro) { "$path: macros cannot be nested" }
            require(macro.actions.isNotEmpty()) { "$path.actions must not be empty" }
            require(macro.actions.size <= MAX_MACRO_ACTIONS) {
                "$path.actions has ${macro.actions.size} actions; the limit is $MAX_MACRO_ACTIONS"
            }
            macro.actions.forEachIndexed { i, a -> validateAction("$path.actions[$i]", a, inMacro = true) }
        }
    }
}
