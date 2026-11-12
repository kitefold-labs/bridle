package com.kitefoldlabs.bridle.core.terminal

import com.kitefoldlabs.bridle.core.terminal.PathWord.Quote
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class PathWordTest {
    private fun parse(line: String) = PathWord.parse(line)

    @Test
    fun `the last word of the line is the path`() {
        parse("cd wo") shouldBe PathWord("wo", Quote.NONE)
        parse("ls -l src/ma") shouldBe PathWord("src/ma", Quote.NONE)
        parse("cd ") shouldBe PathWord("", Quote.NONE)
        parse("") shouldBe PathWord("", Quote.NONE, isCommand = true)
        parse("make && ./bu") shouldBe PathWord("./bu", Quote.NONE, isCommand = true)
        parse("cat <in|sort>ou") shouldBe PathWord("ou", Quote.NONE)
        parse("cp a\tb") shouldBe PathWord("b", Quote.NONE)
        parse("tar --file=dist/ar") shouldBe PathWord("dist/ar", Quote.NONE)
    }

    @Test
    fun `words in command position are marked`() {
        parse("ls")!!.isCommand shouldBe true
        parse("  ls")!!.isCommand shouldBe true
        parse("ls ")!!.isCommand shouldBe false
        parse("ls | gr")!!.isCommand shouldBe true
        parse("a; (b")!!.isCommand shouldBe true
        parse("cat >ou")!!.isCommand shouldBe false
        parse(">ou")!!.isCommand shouldBe false
        parse("FOO=ba")!!.isCommand shouldBe false
        parse("'my scr")!!.isCommand shouldBe true
    }

    @Test
    fun `quotes and escapes are removed, and an open quote is kept`() {
        parse("cd my\\ di") shouldBe PathWord("my di", Quote.NONE)
        parse("cd 'my di") shouldBe PathWord("my di", Quote.SINGLE)
        parse("cd \"my di") shouldBe PathWord("my di", Quote.DOUBLE)
        parse("cd 'a b'/c\\'d") shouldBe PathWord("a b/c'd", Quote.NONE)
        parse("cd \"a\\\"b\\\\c\\x") shouldBe PathWord("a\"b\\c\\x", Quote.DOUBLE)
        parse("cd 'a\\") shouldBe PathWord("a\\", Quote.SINGLE) // no escapes in single quotes
        parse("echo '#' x") shouldBe PathWord("x", Quote.NONE)
    }

    @Test
    fun `tilde means home only at the start of a word`() {
        parse("cd ~/wo") shouldBe PathWord("~/wo", Quote.NONE, tilde = true)
        parse("cd ~").shouldBeNull() // no directory yet
        parse("cd ~root/x").shouldBeNull()
        parse("cd ~'x'/y").shouldBeNull()
        // A directory literally named ~: quoted, escaped, or after "=" (not an assignment).
        parse("cd '~/x'") shouldBe PathWord("~/x", Quote.NONE)
        parse("cd \\~/x")!!.resolveDirectory("/srv", "/home/me") shouldBe "/srv/~/"
        parse("x --dir=~/a") shouldBe PathWord("~/a", Quote.NONE)
        parse("cd a~b") shouldBe PathWord("a~b", Quote.NONE)
    }

    @Test
    fun `words the shell would expand are refused`() {
        for (line in listOf("cd \$HO", "cd \${x}/", "cd `pwd`/", "ls *.k", "ls a?", "ls [ab]", "ls {a,b}", "ls !\$")) {
            parse(line).shouldBeNull()
        }
        parse("cd \"\$HOME/x").shouldBeNull()
        parse("cd \"a!b").shouldBeNull()
        parse("echo \$x wo") shouldBe PathWord("wo", Quote.NONE) // only the last word matters
        parse("ls x # comment").shouldBeNull()
        parse("# cd wo").shouldBeNull()
        parse("ls a#b") shouldBe PathWord("a#b", Quote.NONE)
        parse("cd wo\\").shouldBeNull()
    }

    @Test
    fun `directory, prefix and the directory to list`() {
        val word = parse("cd src/ma")!!
        word.directory shouldBe "src/"
        word.prefix shouldBe "ma"
        word.resolveDirectory("/home/me", "/home/me") shouldBe "/home/me/src/"
        word.resolveDirectory("/", null) shouldBe "/src/"
        word.resolveDirectory(null, "/home/me").shouldBeNull()
        word.resolveDirectory("relative", null).shouldBeNull()

        parse("cd wo")!!.resolveDirectory("/srv", null) shouldBe "/srv/"
        parse("cd /etc/ss")!!.resolveDirectory(null, null) shouldBe "/etc/"
        parse("cd ~/wo")!!.resolveDirectory("/srv", "/home/me") shouldBe "/home/me/"
        parse("cd ~/a/b")!!.resolveDirectory("/srv", "/home/me/") shouldBe "/home/me/a/"
        parse("cd ~/wo")!!.resolveDirectory("/srv", null).shouldBeNull()
        parse("cd ./a/.//b/../c")!!.resolveDirectory("/srv/", null) shouldBe "/srv/a/b/../"
        parse("cd /")!!.resolveDirectory(null, null) shouldBe "/"
        parse("cd wo")!!.resolveDirectory("/", null) shouldBe "/"
    }

    @Test
    fun `insertions are escaped for the quote the line is in`() {
        parse("cd w")!!.insertion("work", isDirectory = true) shouldBe "ork/"
        parse("cd w")!!.insertion("w x'y\"z\$", isDirectory = false) shouldBe "\\ x\\'y\\\"z\\\$"
        parse("cd ")!!.insertion("~tilde#hash", isDirectory = false) shouldBe "\\~tilde\\#hash"
        parse("cd ")!!.insertion("café-1.2_x+y,z:a@b%c", isDirectory = false) shouldBe "café-1.2_x+y,z:a@b%c"
        parse("cd 'w")!!.insertion("w x'y\"z\$!", isDirectory = true) shouldBe " x'\\''y\"z\$!/"
        parse("cd \"w")!!.insertion("w x'y\"z\$`\\", isDirectory = false) shouldBe " x'y\\\"z\\\$\\`\\\\"
        parse("cd \"w")!!.insertion("w!", isDirectory = false).shouldBeNull() // history expansion
        parse("cd w")!!.insertion("other", isDirectory = false).shouldBeNull()
        parse("cd w")!!.insertion("W", isDirectory = false).shouldBeNull() // prefixes are case-sensitive
        parse("cd w")!!.insertion("w\nrm -rf", isDirectory = false).shouldBeNull()
        parse("cd w")!!.insertion("w\u001b[2J", isDirectory = false).shouldBeNull()
    }

    @Test
    fun `an inserted name reads back as itself`() =
        runBlocking<Unit> {
            val names = Arb.string(1..12).filter { name -> name.none { it.isISOControl() || it == '\uFFFD' } }
            checkAll(names, Arb.element("cd ", "cd '", "cd \"", "cd dir/", "cd 'a b'/")) { name, line ->
                val word = PathWord.parse(line)!!
                val typed = word.insertion(word.prefix + name, isDirectory = false) ?: return@checkAll
                val reparsed = PathWord.parse(line + typed)!!
                reparsed.path shouldBe word.path + name
            }
        }

    @Test
    fun `bash reads inserted names as the names`() {
        shellReadsNames("bash", "--norc", "--noprofile", "-i")
    }

    @Test
    fun `zsh reads inserted names as the names`() {
        shellReadsNames("zsh", "-f", "-i")
    }

    /**
     * Types each tricky name after a prefix in each quoting context into an interactive [shell]
     * (so history expansion is on, as at a real prompt), which prints the word it read.
     */
    private fun shellReadsNames(
        shell: String,
        vararg args: String,
    ) {
        val path =
            System
                .getenv(
                    "PATH",
                ).orEmpty()
                .split(':')
                .map { Path.of(it, shell) }
                .firstOrNull(Files::isExecutable)
        assumeTrue(path != null, "$shell is not installed")
        val expected = mutableListOf<String>()
        val script = StringBuilder()
        for (line in listOf("printf '%s\\n' ", "printf '%s\\n' '", "printf '%s\\n' \"")) {
            val word = PathWord.parse(line)!!
            for (name in TRICKY_NAMES) {
                val typed = word.insertion(name, isDirectory = false) ?: continue
                val close =
                    when (word.quote) {
                        Quote.NONE -> ""
                        Quote.SINGLE -> "'"
                        Quote.DOUBLE -> "\""
                    }
                script
                    .append(line)
                    .append(typed)
                    .append(close)
                    .append('\n')
                expected += name
            }
        }
        val process = ProcessBuilder(listOf(path.toString()) + args).redirectError(ProcessBuilder.Redirect.DISCARD)
        process.environment()["HOME"] = Files.createTempDirectory("pathword").toString()
        val running = process.start()
        running.outputStream.use { it.write(script.append("exit\n").toString().toByteArray()) }
        val out = running.inputStream.readBytes().toString(Charsets.UTF_8)
        running.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
        out.lines().dropLast(1) shouldBe expected
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        val TRICKY_NAMES =
            listOf(
                "plain",
                "with space",
                "it's",
                "say \"hi\"",
                "\$HOME",
                "`id`",
                "a\\b",
                "semi;colon",
                "pipe|amp&",
                "<in>",
                "(sub)",
                "*star?",
                "[ab]",
                "{a,b}",
                "~tilde",
                "#hash",
                "bang!",
                "hist!!",
                "eq=val",
                "café",
                "日本語",
                "tab\tbed",
                "-dash",
                "%pct",
                "back`tick",
            )
    }
}
