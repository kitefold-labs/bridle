package com.kitefoldlabs.bridle.core.terminalui

import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.ConnectionLease
import com.kitefoldlabs.bridle.core.session.HostConnectionState
import com.kitefoldlabs.bridle.core.session.TerminalSession
import com.kitefoldlabs.bridle.core.session.TerminalState
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.core.terminal.ShellEvent
import com.kitefoldlabs.bridle.core.terminal.ShellPrompt
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test
import kotlin.time.Duration

class CommandLineSuggestionsTest {
    private val engine = FakeTerminalEngine()
    private val sftp =
        FakeSftp(
            mapOf(
                "/home/me/" to listOf(dir("work"), entry("wiki.txt"), entry("notes")),
                "/srv/" to listOf(entry("www")),
            ),
        )
    private val suggestions =
        CommandLineSuggestions(engine, PathCompleter({ sftp }), Dispatchers.Default, Duration.ZERO)

    @After
    fun tearDown() = suggestions.close()

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }

    /** A new prompt in [cwd], as the shell integration reports it. */
    private fun prompt(cwd: String = "/home/me") {
        engine.shellPrompt.value = ShellPrompt(active = true, atPrompt = true, directories = mapOf(null to cwd))
        engine.onShellEvent!!(ShellEvent.PromptStart())
        engine.onShellEvent!!(ShellEvent.InputStart())
    }

    private fun type(text: String) = suggestions.onInput(text.toByteArray())

    private fun items() = suggestions.suggestions.value.items

    private fun names() = items().map { it.name }

    @Test
    fun `the strip is available once the shell sends an OSC 133 code`() {
        suggestions.available.value shouldBe false
        prompt()
        eventually { suggestions.available.value }
    }

    @Test
    fun `typing a path suggests matching entries of the working directory`() {
        prompt()
        type("cd w")
        eventually { suggestions.suggestions.value.line == "cd w" }
        items() shouldBe
            listOf(PathSuggestion("work", true, "ork/"), PathSuggestion("wiki.txt", false, "iki.txt"))

        prompt("/srv")
        type("ls w")
        eventually { names() == listOf("www") }
    }

    @Test
    fun `a suggestion is accepted only for the line it was made for`() {
        prompt()
        type("cd w")
        eventually { names() == listOf("work", "wiki.txt") }
        val work = items().first()
        suggestions.accept(work)!!.toString(Charsets.UTF_8) shouldBe "ork/"
        suggestions.accept(work.copy(insertion = "x")).shouldBeNull() // not one of the suggestions

        type("i") // the chips still show "cd w" until the lookup for "cd wi" lands
        suggestions.accept(work).shouldBeNull()
        eventually { names() == listOf("wiki.txt") }
        type("\t")
        eventually { items().isEmpty() }
        suggestions.accept(work).shouldBeNull()
    }

    @Test
    fun `each new prompt lists the directory again`() {
        prompt()
        type("cd w")
        eventually { names().isNotEmpty() }
        prompt()
        type("cd w")
        eventually { sftp.listed.size == 2 }
    }

    @Test
    fun `turning suggestions off empties them`() {
        prompt()
        type("cd w")
        eventually { names().isNotEmpty() }
        suggestions.enabled.value = false
        eventually { items().isEmpty() }
        suggestions.suggestions.value.line
            .shouldBeNull()
        suggestions.enabled.value = true
        eventually { names().isNotEmpty() }
    }

    @Test
    fun `the line is forgotten whenever the session isn't open`() {
        val state = MutableStateFlow<TerminalState>(TerminalState.Open)
        suggestions.followState(state)
        prompt()
        type("cd w")
        eventually { names().isNotEmpty() }
        state.value = TerminalState.Reattaching
        eventually { items().isEmpty() }
        state.value = TerminalState.Open
        type("w") // unknown until the next prompt
        Thread.sleep(50)
        items() shouldBe emptyList()
    }

    @Test
    fun `following a session sees what is sent to it`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lease =
            object : ConnectionLease {
                override val hostId = 1L
                override val state = MutableStateFlow<HostConnectionState>(HostConnectionState.Connecting)

                override suspend fun awaitConnection(): SshConnection = awaitCancellation()

                override fun close() = Unit
            }
        val session = TerminalSession(lease, AttachSpec.Shell, { _, _ -> }, scope)
        suggestions.follow(session)
        Thread.sleep(50) // following resets the line while the session is still connecting
        prompt()
        session.send("cd w".toByteArray())
        eventually { names() == listOf("work", "wiki.txt") }
        scope.cancel()
    }

    @Test
    fun `closing stops listening and closes the SFTP session`() {
        prompt()
        type("cd w")
        eventually { names().isNotEmpty() }
        suggestions.close()
        engine.onShellEvent.shouldBeNull()
        sftp.closed shouldBe true
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
