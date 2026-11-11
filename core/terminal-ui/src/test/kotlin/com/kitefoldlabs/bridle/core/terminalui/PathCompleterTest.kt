package com.kitefoldlabs.bridle.core.terminalui

import com.kitefoldlabs.bridle.core.ssh.RemoteFile
import com.kitefoldlabs.bridle.core.ssh.SftpSession
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

class PathCompleterTest {
    private val home =
        listOf(
            entry("wiki.txt"),
            dir("work"),
            dir("Work Notes"),
            entry(".wconf"),
            dir(".wdir"),
            entry("w it's"),
            entry("Zeta"),
            entry("alpha"),
            dir("src"),
        )
    private val tree =
        mapOf(
            "/home/me/" to home,
            "/home/me/src/" to listOf(entry("main.kt"), dir("models"), entry("Main.java")),
            "/etc/" to listOf(entry("ssh_config"), dir("ssh")),
        )
    private val sftp = FakeSftp(tree)
    private var opened = 0
    private val completer =
        PathCompleter({
            opened++
            sftp
        })

    private fun suggest(
        line: String,
        cwd: String? = "/home/me",
    ) = runBlocking { completer.suggest(line, cwd) }

    private fun insertions(
        line: String,
        cwd: String? = "/home/me",
    ) = suggest(line, cwd).map { it.insertion }

    @Test
    fun `entries starting with the typed prefix, directories first and escaped`() {
        suggest("cd w") shouldBe
            listOf(
                PathSuggestion("work", isDirectory = true, insertion = "ork/"),
                PathSuggestion("w it's", isDirectory = false, insertion = "\\ it\\'s"),
                PathSuggestion("wiki.txt", isDirectory = false, insertion = "iki.txt"),
            )
        insertions("cd 'w") shouldBe listOf("ork/", " it'\\''s", "iki.txt")
        insertions("ls ") shouldBe listOf("src/", "work/", "Work\\ Notes/", "alpha", "w\\ it\\'s", "wiki.txt", "Zeta")
    }

    @Test
    fun `dotfiles only when the prefix starts with a dot`() {
        insertions("cat .w") shouldBe listOf("dir/", "conf")
        insertions("cat .") shouldBe listOf("wdir/", "wconf")
    }

    @Test
    fun `subdirectories, home and absolute paths are listed`() {
        insertions("vim src/ma") shouldBe listOf("in.kt") // prefixes are case-sensitive
        insertions("vim ~/src/mo") shouldBe listOf("dels/")
        insertions("cat /etc/ss", cwd = null) shouldBe listOf("h/", "h_config")
        sftp.listed shouldBe listOf("/home/me/src/", "/etc/")
    }

    @Test
    fun `nothing without a working directory, in command position or for expanded words`() {
        suggest("cd w", cwd = null).shouldBeEmpty()
        suggest("w").shouldBeEmpty()
        suggest("").shouldBeEmpty()
        suggest("cd \$HOME/w").shouldBeEmpty()
        opened shouldBe 1 // only for the first, which had no directory to list
        sftp.listed.shouldBeEmpty()
        insertions("./s") shouldBe listOf("rc/")
    }

    @Test
    fun `a name typed in full only gets a slash if it is a directory`() {
        insertions("cat alpha").shouldBeEmpty()
        insertions("cd src") shouldBe listOf("/")
    }

    @Test
    fun `listings are cached until invalidated`() {
        suggest("cd w")
        suggest("cd wo")
        suggest("cat a")
        sftp.listed shouldBe listOf("/home/me/")
        completer.invalidate()
        suggest("cd w")
        sftp.listed shouldBe listOf("/home/me/", "/home/me/")
    }

    @Test
    fun `a listing finished after invalidate is not cached`() {
        sftp.onList = { completer.invalidate() }
        suggest("cd w")
        sftp.onList = {}
        suggest("cd w")
        sftp.listed.size shouldBe 2
    }

    @Test
    fun `symlinks are resolved so links to directories get a slash`() {
        val links =
            FakeSftp(
                mapOf(
                    "/p/" to
                        listOf(
                            entry("ldir", RemoteFile.Type.SYMLINK),
                            entry("lfile", RemoteFile.Type.SYMLINK),
                            entry("lbroken", RemoteFile.Type.SYMLINK),
                        ),
                ),
                links = mapOf("/p/ldir" to RemoteFile.Type.DIRECTORY, "/p/lfile" to RemoteFile.Type.FILE),
            )
        val completer = PathCompleter({ links })
        runBlocking {
            completer.suggest("cd l", "/p").map { it.insertion } shouldBe listOf("dir/", "broken", "file")
            completer.suggest("cd ld", "/p").map { it.insertion } shouldBe listOf("ir/")
        }
        links.statted shouldBe listOf("/p/ldir", "/p/lfile", "/p/lbroken") // once each
    }

    @Test
    fun `failures give no suggestions and reopen the session`() {
        val flaky = FakeSftp(mapOf("/ok/" to listOf(entry("a"))), failing = setOf("/no/"))
        var opens = 0
        val completer =
            PathCompleter({
                opens++
                if (opens == 1) throw IOException("not connected") else flaky
            })
        runBlocking {
            completer.suggest("cat a", "/ok").shouldBeEmpty() // couldn't open
            completer.suggest("cat a", "/no").shouldBeEmpty() // listing failed: session dropped
            flaky.closed shouldBe true
            completer.suggest("cat a", "/no").shouldBeEmpty() // cached as empty
            completer.suggest("cat a", "/ok").map { it.insertion } shouldBe emptyList() // "a" is typed in full
            completer.suggest("cat ", "/ok").map { it.insertion } shouldBe listOf("a")
        }
        opens shouldBe 3
        flaky.listed shouldBe listOf("/no/", "/ok/")
    }

    @Test
    fun `a failing stat drops the session and stops resolving`() {
        val links =
            FakeSftp(
                mapOf("/p/" to listOf(entry("a", RemoteFile.Type.SYMLINK), entry("b", RemoteFile.Type.SYMLINK))),
                failing = setOf("/p/a"),
            )
        val completer = PathCompleter({ links })
        runBlocking { completer.suggest("cat ", "/p").map { it.insertion } shouldBe listOf("a", "b") }
        links.statted shouldBe listOf("/p/a")
        links.closed shouldBe true
    }

    @Test
    fun `a host without SFTP or home gives nothing`() {
        val noSftp = PathCompleter({ throw UnsupportedOperationException("SFTP is not available") })
        runBlocking { noSftp.suggest("cat a", "/x").shouldBeEmpty() }
        val noHome =
            object : SftpSession by sftp {
                override suspend fun canonicalize(path: String): String = throw IOException("no home")
            }
        runBlocking { PathCompleter({ noHome }).suggest("cd ~/s", "/x").shouldBeEmpty() }
    }

    @Test
    fun `suggestions and listings are capped`() {
        val many = (0 until PathCompleter.MAX_ENTRIES + 5).map { entry("f%05d".format(it)) }
        val big = FakeSftp(mapOf("/big/" to many))
        val completer = PathCompleter({ big }, maxSuggestions = 3)
        runBlocking {
            completer.suggest("cat f", "/big").map { it.name } shouldBe listOf("f00000", "f00001", "f00002")
            completer.suggest("cat f1000", "/big").map { it.name } shouldBe emptyList() // past the cut
        }
        completer.close()
        big.closed shouldBe true
    }
}
