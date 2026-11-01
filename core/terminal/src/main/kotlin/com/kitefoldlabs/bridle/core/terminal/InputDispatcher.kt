package com.kitefoldlabs.bridle.core.terminal

/**
 * Turns everything the user presses (key-bar actions, soft-keyboard text, hardware keys) into
 * bytes for the remote, applying the sticky on-screen modifiers in one place:
 *
 * - key, char and single-character inputs get the sticky modifiers plus any hardware ones, and
 *   release single-tap modifiers;
 * - multi-character text and macros are sent literally and leave sticky modifiers armed;
 * - modifier toggles update [sticky] and send nothing.
 *
 * Paste and the compose box go through [BracketedPaste] instead. Not thread-safe.
 */
public class InputDispatcher(
    public val encoder: KeyEncoder = KeyEncoder(),
    public val sticky: StickyModifiers = StickyModifiers(),
) {
    /**
     * Handles a key-bar [action] (a tap or a swipe-up alternate) at [nowMillis].
     * [applicationCursorKeys] is the emulator's DECCKM state.
     */
    public fun onKeyBarAction(
        action: KeyAction,
        nowMillis: Long,
        applicationCursorKeys: Boolean = false,
    ): ByteArray =
        when (action) {
            is KeyAction.Toggle -> {
                sticky.tap(action.modifier, nowMillis)
                ByteArray(0)
            }
            is KeyAction.Press -> onKey(action.key, action.modifiers, applicationCursorKeys)
            is KeyAction.Chord -> encoder.encodeChar(action.char.codePointAt(0), sticky.consume(action.modifiers))
            is KeyAction.Text -> type(action.text)
            is KeyAction.Macro ->
                action.actions.fold(ByteArray(0)) { acc, step -> acc + macroStep(step, applicationCursorKeys) }
        }

    /** Handles a named [key] from the soft or hardware keyboard, with [hardware] modifiers held. */
    public fun onKey(
        key: Key,
        hardware: Modifiers = Modifiers.NONE,
        applicationCursorKeys: Boolean = false,
    ): ByteArray = encoder.encode(key, sticky.consume(hardware), applicationCursorKeys)

    /**
     * Handles typed [text] (an IME commit or a hardware character). Line feeds are sent as CR,
     * like the Enter key. A single character gets the active modifiers, so sticky Ctrl then `c`
     * sends `^C`. Longer text is sent as UTF-8 with control characters removed, like a paste
     * ([BracketedPaste.sanitize]): keyboards commit clipboard contents this way (Gboard's clipboard
     * strip), so it must not be a way around paste sanitising.
     */
    public fun type(
        text: String,
        hardware: Modifiers = Modifiers.NONE,
    ): ByteArray {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        if (normalized.isEmpty()) return ByteArray(0)
        val codePoint = normalized.codePointAt(0)
        val single = Character.charCount(codePoint) == normalized.length && KeyEncoder.isScalarValue(codePoint)
        return when {
            !single -> encoder.encodeText(BracketedPaste.sanitize(normalized))
            codePoint == '\r'.code -> onKey(Key.Enter, hardware)
            else -> encoder.encodeChar(codePoint, sticky.consume(hardware))
        }
    }

    /** One macro step; macros ignore sticky modifiers. Toggles and nested macros are skipped. */
    private fun macroStep(
        step: KeyAction,
        applicationCursorKeys: Boolean,
    ): ByteArray =
        when (step) {
            is KeyAction.Press -> encoder.encode(step.key, step.modifiers, applicationCursorKeys)
            is KeyAction.Chord -> encoder.encodeChar(step.char.codePointAt(0), step.modifiers)
            is KeyAction.Text -> encoder.encodeText(step.text)
            is KeyAction.Toggle, is KeyAction.Macro -> ByteArray(0)
        }
}
