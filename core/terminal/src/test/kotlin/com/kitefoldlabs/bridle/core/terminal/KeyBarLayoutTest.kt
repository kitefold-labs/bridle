package com.kitefoldlabs.bridle.core.terminal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File

class KeyBarLayoutTest {
    private fun labels(layout: KeyBarLayout) = layout.rows.flatten().map { it.label }

    private fun actions(layout: KeyBarLayout) = layout.rows.flatten().map { it.action }

    private fun rejects(
        json: String,
        vararg messageParts: String,
    ) {
        val e = shouldThrow<KeyBarLayoutException> { KeyBarLayout.parse(json) }
        messageParts.forEach { e.message shouldContain it }
    }

    private fun oneKey(keyJson: String) = """{"rows": [[$keyJson]]}"""

    private fun oneAction(actionJson: String) = oneKey("""{"label": "K", "action": $actionJson}""")

    @Test
    fun `default layout has the Termux-style keys`() {
        labels(KeyBarLayout.DEFAULT) shouldContainAll
            listOf("ESC", "TAB", "CTRL", "ALT", "←", "↓", "↑", "→", "|", "~", "/", "-")
        actions(KeyBarLayout.DEFAULT) shouldContainAll
            listOf(KeyAction.Toggle(StickyModifier.CTRL), KeyAction.Toggle(StickyModifier.ALT))
    }

    @Test
    fun `claude layout has the Claude Code keys and macros`() {
        actions(KeyBarLayout.CLAUDE) shouldContainAll
            listOf(
                KeyAction.Press(Key.Escape),
                KeyAction.Press(Key.Tab, Modifiers.SHIFT),
                KeyAction.Chord("c", Modifiers.CTRL),
                KeyAction.Text("/"),
                KeyAction.Text("y"),
                KeyAction.Text("n"),
                KeyAction.Press(Key.Up),
                KeyAction.Press(Key.Enter),
                KeyAction.Macro(listOf(KeyAction.Text("/compact"), KeyAction.Press(Key.Enter))),
                KeyAction.Macro(listOf(KeyAction.Text("/clear"), KeyAction.Press(Key.Enter))),
                KeyAction.Macro(listOf(KeyAction.Text("/resume"), KeyAction.Press(Key.Enter))),
            )
    }

    @Test
    fun `presets round-trip through JSON`() {
        for (layout in listOf(KeyBarLayout.DEFAULT, KeyBarLayout.CLAUDE)) {
            KeyBarLayout.parse(layout.toJson()) shouldBe layout
        }
    }

    @Test
    fun `parses the documented format, ignoring unknown fields`() {
        val layout =
            KeyBarLayout.parse(
                """
                {
                  "comment": "unknown fields are ignored",
                  "rows": [
                    [
                      {"label": "ESC", "action": {"type": "key", "key": "escape"}},
                      {"label": "CTRL", "action": {"type": "modifier", "modifier": "ctrl"}},
                      {"label": "^C", "action": {"type": "char", "char": "c", "modifiers": {"ctrl": true}}},
                      {"label": "/", "action": {"type": "text", "text": "/"},
                       "swipeUp": {"type": "text", "text": "?"}, "swipeUpLabel": "?", "color": "red"},
                      {"label": "compact", "action": {"type": "macro", "actions": [
                        {"type": "text", "text": "/compact"}, {"type": "key", "key": "Enter"}]}}
                    ]
                  ]
                }
                """.trimIndent(),
            )
        layout.rows.single() shouldBe
            listOf(
                KeyBarKey("ESC", KeyAction.Press(Key.Escape)),
                KeyBarKey("CTRL", KeyAction.Toggle(StickyModifier.CTRL)),
                KeyBarKey("^C", KeyAction.Chord("c", Modifiers.CTRL)),
                KeyBarKey("/", KeyAction.Text("/"), KeyAction.Text("?"), "?"),
                KeyBarKey(
                    "compact",
                    KeyAction.Macro(listOf(KeyAction.Text("/compact"), KeyAction.Press(Key.Enter))),
                ),
            )
    }

    @Test
    fun `the example in docs-terminal-input parses`() {
        val doc = File("../../docs/terminal-input.md").readText()
        val example = doc.substringAfter("```json\n").substringBefore("```")
        val layout = KeyBarLayout.parse(example)
        layout.rows.map { it.size } shouldBe listOf(5, 2)
        layout.rows[1][1].action shouldBe
            KeyAction.Macro(listOf(KeyAction.Text("/compact"), KeyAction.Press(Key.Enter)))
    }

    @Test
    fun `parses every modifier`() {
        val all = """{"ctrl": true, "alt": true, "shift": true, "meta": true}"""
        val json = """{"type": "key", "key": "Up", "modifiers": $all}"""
        KeyBarLayout.parse(oneAction(json)).rows[0][0].action shouldBe
            KeyAction.Press(Key.Up, Modifiers(ctrl = true, alt = true, shift = true, meta = true))
    }

    @Test
    fun `serialised form omits defaults and nulls`() {
        val json = KeyBarLayout(listOf(listOf(KeyBarKey("ESC", KeyAction.Press(Key.Escape))))).toJson()
        json shouldContain "\"type\": \"key\""
        (json.contains("swipeUp") || json.contains("modifiers")) shouldBe false
    }

    @Test
    fun `rejects malformed JSON and unknown action types`() {
        rejects("not json", "Invalid key-bar layout")
        rejects("""{"rows": [[{"label": "K", "action": {"type": "launch"}}]]}""", "Invalid key-bar layout")
        rejects("""{"rows": [[{"label": "K", "action": {"type": "key", "key": "F13"}}]]}""")
        rejects("""{}""", "rows")
    }

    @Test
    fun `rejects oversized input before decoding`() {
        val huge = " ".repeat(KeyBarLayout.MAX_JSON_LENGTH + 1)
        rejects(huge, "the limit is ${KeyBarLayout.MAX_JSON_LENGTH}")
    }

    @Test
    fun `maximally nested input within the size limit fails cleanly`() {
        // As deep as 64 KiB allows: a layout error (macros can't nest), not a StackOverflowError.
        val depth = (KeyBarLayout.MAX_JSON_LENGTH - 200) / 29
        val nested = """{"type":"macro","actions":[""".repeat(depth) + "]}".repeat(depth)
        rejects(oneAction(nested), "nested")
        // Deeply nested unknown fields are skipped.
        val deep = "[".repeat(30_000) + "]".repeat(30_000)
        val layout =
            KeyBarLayout.parse(
                """{"x": $deep, "rows": [[{"label": "K", "action": {"type": "key", "key": "Up"}}]]}""",
            )
        layout.rows.size shouldBe 1
    }

    @Test
    fun `rejects bad row and key counts`() {
        val key = """{"label": "K", "action": {"type": "text", "text": "k"}}"""
        val row = "[$key]"
        rejects("""{"rows": []}""", "at least one row")
        rejects("""{"rows": [${List(KeyBarLayout.MAX_ROWS + 1) { row }.joinToString()}]}""", "7 rows")
        rejects("""{"rows": [[]]}""", "rows[0] is empty")
        val wide = List(KeyBarLayout.MAX_KEYS_PER_ROW + 1) { key }.joinToString()
        rejects("""{"rows": [$row, [$wide]]}""", "rows[1] has 17 keys")
    }

    @Test
    fun `rejects bad labels`() {
        rejects(oneKey("""{"label": " ", "action": {"type": "text", "text": "k"}}"""), "rows[0][0].label", "blank")
        rejects(
            oneKey("""{"label": "${"x".repeat(17)}", "action": {"type": "text", "text": "k"}}"""),
            "rows[0][0].label is 17 characters",
        )
        rejects(oneKey("""{"label": "a\u001bb", "action": {"type": "text", "text": "k"}}"""), "control")
        rejects(
            oneKey("""{"label": "K", "action": {"type": "text", "text": "k"}, "swipeUpLabel": ""}"""),
            "rows[0][0].swipeUpLabel",
        )
    }

    @Test
    fun `rejects bad chars`() {
        rejects(oneAction("""{"type": "char", "char": ""}"""), "exactly one character")
        rejects(oneAction("""{"type": "char", "char": "ab"}"""), "exactly one character")
        // A lone surrogate counts as one code point but would make KeyEncoder throw on every press.
        rejects(oneAction("""{"type": "char", "char": "\ud800"}"""), "exactly one character")
        rejects(oneAction("""{"type": "char", "char": "\r"}"""), "rows[0][0].action.char must be printable")
        KeyBarLayout.parse(oneAction("""{"type": "char", "char": "😀"}""")).rows[0][0].action shouldBe
            KeyAction.Chord("😀")
    }

    @Test
    fun `rejects bad text`() {
        rejects(oneAction("""{"type": "text", "text": ""}"""), "must not be empty")
        rejects(oneAction("""{"type": "text", "text": "${"x".repeat(257)}"}"""), "257 characters")
        rejects(oneAction("""{"type": "text", "text": "rm -rf ~\n"}"""), "must be printable")
        rejects(oneAction("""{"type": "text", "text": "\u009b201~"}"""), "must be printable")
    }

    @Test
    fun `rejects bad macros`() {
        val text = """{"type": "text", "text": "k"}"""
        rejects(oneAction("""{"type": "macro", "actions": []}"""), "actions must not be empty")
        val tooMany = List(KeyBarLayout.MAX_MACRO_ACTIONS + 1) { text }.joinToString()
        rejects(oneAction("""{"type": "macro", "actions": [$tooMany]}"""), "33 actions")
        rejects(
            oneAction("""{"type": "macro", "actions": [{"type": "macro", "actions": [$text]}]}"""),
            "rows[0][0].action.actions[0]: macros cannot be nested",
        )
        rejects(
            oneAction("""{"type": "macro", "actions": [{"type": "modifier", "modifier": "CTRL"}]}"""),
            "cannot toggle modifiers",
        )
        rejects(oneAction("""{"type": "macro", "actions": [{"type": "text", "text": ""}]}"""), "actions[0].text")
    }

    @Test
    fun `validates the swipe-up action`() {
        val key = """{"label": "K", "action": {"type": "text", "text": "k"}, "swipeUp": {"type": "text", "text": ""}}"""
        rejects(oneKey(key), "rows[0][0].swipeUp.text")
    }

    @Test
    fun `constructing an invalid layout in code fails too`() {
        val e = shouldThrow<IllegalArgumentException> { KeyBarLayout(emptyList()) }
        e.shouldBeInstanceOf<IllegalArgumentException>()
        e.message shouldBe "layout must have at least one row"
    }

    @Test
    fun `exception carries the cause`() {
        val e = shouldThrow<KeyBarLayoutException> { KeyBarLayout.parse("[") }
        (e.cause != null) shouldBe true
        KeyBarLayoutException("plain").cause shouldBe null
    }
}
