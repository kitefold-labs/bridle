package com.kitefoldlabs.bridle.core.terminal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.char
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Bytes as a Latin-1 string, so expectations can be written as escape-sequence literals. */
internal fun ByteArray.latin1(): String = String(this, Charsets.ISO_8859_1)

class KeyEncoderTest {
    private val encoder = KeyEncoder()

    private fun key(
        key: Key,
        modifiers: Modifiers = Modifiers.NONE,
        app: Boolean = false,
    ) = encoder.encode(key, modifiers, app).latin1()

    private fun char(
        c: Char,
        modifiers: Modifiers = Modifiers.NONE,
    ) = encoder.encodeChar(c.code, modifiers).latin1()

    @Test
    fun `wheel notches follow the mouse reporting mode`() {
        encoder.encodeWheel(up = true, column = 1, row = 1, MouseReporting.OFF) shouldBe ByteArray(0)
        encoder.encodeWheel(up = true, column = 12, row = 3, MouseReporting.SGR).latin1() shouldBe "\u001b[<64;12;3M"
        encoder.encodeWheel(up = false, column = 300, row = 0, MouseReporting.SGR).latin1() shouldBe "\u001b[<65;300;1M"
        encoder.encodeWheel(up = true, column = 1, row = 1, MouseReporting.X10).latin1() shouldBe "\u001b[M\u0060!!"
        encoder.encodeWheel(up = false, column = 300, row = 0, MouseReporting.X10).latin1() shouldBe "\u001b[Ma\u00ff!"
    }

    @Test
    fun `unmodified keys in normal mode`() {
        val expected =
            mapOf(
                Key.Enter to "\r",
                Key.Tab to "\t",
                Key.Backspace to "\u007f",
                Key.Escape to "\u001b",
                Key.Up to "\u001b[A",
                Key.Down to "\u001b[B",
                Key.Right to "\u001b[C",
                Key.Left to "\u001b[D",
                Key.Home to "\u001b[H",
                Key.End to "\u001b[F",
                Key.Insert to "\u001b[2~",
                Key.Delete to "\u001b[3~",
                Key.PageUp to "\u001b[5~",
                Key.PageDown to "\u001b[6~",
                Key.F1 to "\u001bOP",
                Key.F2 to "\u001bOQ",
                Key.F3 to "\u001bOR",
                Key.F4 to "\u001bOS",
                Key.F5 to "\u001b[15~",
                Key.F6 to "\u001b[17~",
                Key.F7 to "\u001b[18~",
                Key.F8 to "\u001b[19~",
                Key.F9 to "\u001b[20~",
                Key.F10 to "\u001b[21~",
                Key.F11 to "\u001b[23~",
                Key.F12 to "\u001b[24~",
            )
        expected.keys shouldBe Key.entries.toSet()
        expected.forEach { (k, bytes) -> key(k) shouldBe bytes }
    }

    @Test
    fun `application cursor mode switches unmodified cursor keys to SS3`() {
        key(Key.Up, app = true) shouldBe "\u001bOA"
        key(Key.Down, app = true) shouldBe "\u001bOB"
        key(Key.Right, app = true) shouldBe "\u001bOC"
        key(Key.Left, app = true) shouldBe "\u001bOD"
        key(Key.Home, app = true) shouldBe "\u001bOH"
        key(Key.End, app = true) shouldBe "\u001bOF"
        // Keys outside the cursor set are unaffected by DECCKM.
        key(Key.PageUp, app = true) shouldBe "\u001b[5~"
        key(Key.F1, app = true) shouldBe "\u001bOP"
        key(Key.Enter, app = true) shouldBe "\r"
    }

    @Test
    fun `modified cursor keys use the xterm modifier parameter in both modes`() {
        key(Key.Up, Modifiers.SHIFT) shouldBe "\u001b[1;2A"
        key(Key.Up, Modifiers.ALT) shouldBe "\u001b[1;3A"
        key(Key.Up, Modifiers(shift = true, alt = true)) shouldBe "\u001b[1;4A"
        key(Key.Up, Modifiers.CTRL) shouldBe "\u001b[1;5A"
        key(Key.Up, Modifiers.CTRL, app = true) shouldBe "\u001b[1;5A"
        key(Key.Left, Modifiers(ctrl = true, shift = true)) shouldBe "\u001b[1;6D"
        key(Key.Right, Modifiers(ctrl = true, alt = true)) shouldBe "\u001b[1;7C"
        key(Key.Down, Modifiers.META) shouldBe "\u001b[1;9B"
        key(Key.Home, Modifiers(ctrl = true, alt = true, shift = true, meta = true)) shouldBe "\u001b[1;16H"
        key(Key.End, Modifiers.CTRL, app = true) shouldBe "\u001b[1;5F"
    }

    @Test
    fun `modified function and editing keys`() {
        key(Key.F1, Modifiers.SHIFT) shouldBe "\u001b[1;2P"
        key(Key.F4, Modifiers.CTRL) shouldBe "\u001b[1;5S"
        key(Key.F5, Modifiers.CTRL) shouldBe "\u001b[15;5~"
        key(Key.F12, Modifiers.SHIFT) shouldBe "\u001b[24;2~"
        key(Key.PageUp, Modifiers.SHIFT) shouldBe "\u001b[5;2~"
        key(Key.Delete, Modifiers.CTRL) shouldBe "\u001b[3;5~"
        key(Key.Insert, Modifiers.ALT) shouldBe "\u001b[2;3~"
    }

    @Test
    fun `simple keys take an ESC prefix for alt and meta`() {
        key(Key.Enter, Modifiers.ALT) shouldBe "\u001b\r"
        key(Key.Enter, Modifiers.META) shouldBe "\u001b\r"
        key(Key.Enter, Modifiers.CTRL) shouldBe "\r"
        key(Key.Enter, Modifiers.SHIFT) shouldBe "\r"
        key(Key.Escape, Modifiers.ALT) shouldBe "\u001b\u001b"
        key(Key.Tab, Modifiers.ALT) shouldBe "\u001b\t"
        key(Key.Tab, Modifiers.CTRL) shouldBe "\t"
    }

    @Test
    fun `shift tab is back-tab`() {
        key(Key.Tab, Modifiers.SHIFT) shouldBe "\u001b[Z"
        key(Key.Tab, Modifiers(shift = true, ctrl = true)) shouldBe "\u001b[Z"
        key(Key.Tab, Modifiers(shift = true, alt = true)) shouldBe "\u001b\u001b[Z"
    }

    @Test
    fun `backspace sends DEL or BS and ctrl swaps them`() {
        key(Key.Backspace) shouldBe "\u007f"
        key(Key.Backspace, Modifiers.CTRL) shouldBe "\b"
        key(Key.Backspace, Modifiers.ALT) shouldBe "\u001b\u007f"
        val ctrlH = KeyEncoder(BackspaceMode.CTRL_H)
        ctrlH.backspace shouldBe BackspaceMode.CTRL_H
        ctrlH.encode(Key.Backspace).latin1() shouldBe "\b"
        ctrlH.encode(Key.Backspace, Modifiers.CTRL).latin1() shouldBe "\u007f"
        encoder.backspace shouldBe BackspaceMode.DEL
    }

    @Test
    fun `ctrl maps characters to C0 control codes`() {
        char('a', Modifiers.CTRL) shouldBe "\u0001"
        char('A', Modifiers.CTRL) shouldBe "\u0001"
        char('c', Modifiers.CTRL) shouldBe "\u0003"
        char('z', Modifiers.CTRL) shouldBe "\u001a"
        for (c in listOf('@', ' ', '2')) char(c, Modifiers.CTRL) shouldBe "\u0000"
        for (c in listOf('[', '3')) char(c, Modifiers.CTRL) shouldBe "\u001b"
        for (c in listOf('\\', '4')) char(c, Modifiers.CTRL) shouldBe "\u001c"
        for (c in listOf(']', '5')) char(c, Modifiers.CTRL) shouldBe "\u001d"
        for (c in listOf('^', '6')) char(c, Modifiers.CTRL) shouldBe "\u001e"
        for (c in listOf('_', '7', '/')) char(c, Modifiers.CTRL) shouldBe "\u001f"
        for (c in listOf('?', '8')) char(c, Modifiers.CTRL) shouldBe "\u007f"
    }

    @Test
    fun `ctrl leaves characters without a control code unchanged`() {
        char('1', Modifiers.CTRL) shouldBe "1"
        char('{', Modifiers.CTRL) shouldBe "{"
        encoder.encodeChar('é'.code, Modifiers.CTRL) shouldBe "é".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `shift upper-cases and alt or meta prefix ESC`() {
        char('a') shouldBe "a"
        char('a', Modifiers.SHIFT) shouldBe "A"
        char('1', Modifiers.SHIFT) shouldBe "1"
        char('x', Modifiers.ALT) shouldBe "\u001bx"
        char('x', Modifiers.META) shouldBe "\u001bx"
        char('c', Modifiers(ctrl = true, alt = true)) shouldBe "\u001b\u0003"
        char('b', Modifiers(ctrl = true, shift = true)) shouldBe "\u0002"
    }

    @Test
    fun `unicode is encoded as UTF-8`() {
        encoder.encodeChar(0x1F600) shouldBe "😀".toByteArray(Charsets.UTF_8)
        encoder.encodeChar(0x1F600, Modifiers.ALT) shouldBe byteArrayOf(0x1b) + "😀".toByteArray(Charsets.UTF_8)
        encoder.encodeChar('ß'.code, Modifiers.SHIFT) shouldBe "ß".toByteArray(Charsets.UTF_8)
        encoder.encodeText("héllo 世界") shouldBe "héllo 世界".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `rejects values that are not Unicode scalar values`() {
        for (bad in listOf(-1, 0xD800, 0xDFFF, 0x110000)) {
            shouldThrow<IllegalArgumentException> { encoder.encodeChar(bad) }
        }
    }

    @Test
    fun `ctrl plus any letter is a single control byte from 1 to 26`() =
        runBlocking<Unit> {
            checkAll(Arb.char('a'..'z', 'A'..'Z')) { c ->
                val bytes = encoder.encodeChar(c.code, Modifiers.CTRL)
                bytes.size shouldBe 1
                bytes[0].toInt() shouldBeInRange 1..26
            }
        }

    @Test
    fun `unmodified scalar values round-trip through UTF-8`() =
        runBlocking<Unit> {
            checkAll(Arb.int(0..0x10FFFF).filter { it !in 0xD800..0xDFFF }) { cp ->
                String(encoder.encodeChar(cp), Charsets.UTF_8).codePointAt(0) shouldBe cp
            }
        }
}

class ModifiersTest {
    @Test
    fun `xterm parameter is one plus the modifier bits`() {
        Modifiers.NONE.xtermParameter shouldBe 1
        Modifiers.SHIFT.xtermParameter shouldBe 2
        Modifiers.ALT.xtermParameter shouldBe 3
        Modifiers.CTRL.xtermParameter shouldBe 5
        Modifiers.META.xtermParameter shouldBe 9
        Modifiers(ctrl = true, alt = true, shift = true, meta = true).xtermParameter shouldBe 16
    }

    @Test
    fun `isEmpty and escape prefix`() {
        Modifiers.NONE.isEmpty shouldBe true
        Modifiers.CTRL.isEmpty shouldBe false
        Modifiers.ALT.isEmpty shouldBe false
        Modifiers.SHIFT.isEmpty shouldBe false
        Modifiers.META.isEmpty shouldBe false
        Modifiers.ALT.escapePrefix shouldBe true
        Modifiers.META.escapePrefix shouldBe true
        Modifiers.CTRL.escapePrefix shouldBe false
    }

    @Test
    fun `plus is a union`() {
        Modifiers.CTRL + Modifiers.NONE shouldBe Modifiers.CTRL
        Modifiers.NONE + Modifiers.CTRL shouldBe Modifiers.CTRL
        Modifiers.NONE + Modifiers.NONE shouldBe Modifiers.NONE
        Modifiers(ctrl = true, alt = true, shift = true, meta = true) + Modifiers.NONE shouldBe
            Modifiers(ctrl = true, alt = true, shift = true, meta = true)
        Modifiers.ALT + Modifiers.SHIFT + Modifiers.META shouldBe Modifiers(alt = true, shift = true, meta = true)
    }
}
