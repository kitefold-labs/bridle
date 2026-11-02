package com.kitefoldlabs.bridle.feature.snippets

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Snippet
import com.kitefoldlabs.bridle.core.model.SnippetRunMode
import com.kitefoldlabs.bridle.core.ssh.ExecResult
import com.kitefoldlabs.bridle.feature.FakeConnection
import com.kitefoldlabs.bridle.feature.connections
import com.kitefoldlabs.bridle.feature.eventually
import com.kitefoldlabs.bridle.feature.testData
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Optional

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SnippetsViewModelTest {
    private lateinit var data: DataModule
    private val connection = FakeConnection()
    private val pasted = mutableListOf<Pair<EntityId, String>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        data = testData(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        data.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(target: SnippetTarget? = null) =
        SnippetsViewModel(
            SavedStateHandle(mapOf(SnippetsDestination.ARG_HOST to 3L)),
            data.snippets,
            connections({ connection }),
            Optional.ofNullable(target),
        )

    @Test
    fun `creates, edits and deletes snippets`() {
        val vm = viewModel()
        eventually { vm.state.value.loaded }
        vm.state.value.snippets shouldBe emptyList()
        vm.saveDraft() // no draft: ignored
        vm.create()
        vm.saveDraft()
        vm.state.value.draft!!
            .nameMissing shouldBe true
        vm.updateDraft {
            it.copy(
                name = " Logs ",
                body = "journalctl -u {{unit:nginx}} -n {{lines}}",
                runMode = SnippetRunMode.EXEC,
                packageName = " ",
            )
        }
        vm.state.value.draft!!
            .variables
            .map { it.name } shouldBe listOf("unit", "lines")
        vm.saveDraft()
        eventually { vm.state.value.snippets.size == 1 }
        eventually { vm.state.value.notice == SnippetNotice.SAVED }
        vm.consumeNotice()
        vm.state.value.draft
            .shouldBeNull()
        val saved =
            vm.state.value.snippets
                .single()
        saved.name shouldBe "Logs"
        saved.packageName.shouldBeNull()

        vm.edit(saved)
        vm.state.value.draft shouldBe SnippetDraft(saved.id, "Logs", saved.body, SnippetRunMode.EXEC, "")
        vm.updateDraft { it.copy(packageName = "ops") }
        vm.saveDraft()
        eventually {
            vm.state.value.snippets
                .single()
                .packageName == "ops"
        }
        vm.edit(
            vm.state.value.snippets
                .single(),
        )
        vm.state.value.draft!!
            .packageName shouldBe "ops"
        vm.cancelDraft()
        shouldThrow<IllegalStateException> {
            SnippetsViewModel(SavedStateHandle(), data.snippets, connections({ connection }), Optional.empty())
        }

        vm.create()
        vm.cancelDraft()
        vm.state.value.draft
            .shouldBeNull()
        vm.updateDraft { it.copy(name = "ignored") }
        vm.state.value.draft
            .shouldBeNull()

        vm.delete(
            vm.state.value.snippets
                .single(),
        )
        eventually {
            vm.state.value.snippets
                .isEmpty()
        }
        eventually { vm.state.value.notice == SnippetNotice.DELETED }
    }

    @Test
    fun `fills variables and pastes into the terminal`() {
        val vm = viewModel { host, text -> pasted.add(host to text) }
        val snippet = Snippet(name = "cd", body = "cd {{dir:/srv}} && ls {{flags}}")
        vm.startRun(snippet)
        vm.state.value.run!!
            .values shouldBe mapOf("dir" to "/srv", "flags" to "")
        vm.setValue("flags", "-la")
        vm.state.value.run!!
            .command shouldBe "cd /srv && ls -la"
        vm.paste()
        pasted shouldBe listOf(3L to "cd /srv && ls -la")
        vm.state.value.run
            .shouldBeNull()
        vm.state.value.notice shouldBe SnippetNotice.PASTED
    }

    @Test
    fun `without a terminal, paste explains and keeps the dialog open`() {
        val vm = viewModel()
        vm.paste() // nothing to run
        vm.exec()
        vm.setValue("x", "y")
        vm.startRun(Snippet(name = "up", body = "uptime"))
        vm.paste()
        vm.state.value.notice shouldBe SnippetNotice.NO_TERMINAL
        vm.state.value.run!!
            .snippet.name shouldBe "up"
        vm.closeRun()
        vm.state.value.run
            .shouldBeNull()
        SnippetTarget.None.hashCode() shouldBe SnippetTarget.None.hashCode()
    }

    @Test
    fun `runs with exec and shows the output or the error`() {
        val vm = viewModel()
        connection.execResult = ExecResult(0, "up 3 days\n", "")
        vm.startRun(Snippet(name = "up", body = "uptime", runMode = SnippetRunMode.EXEC))
        vm.exec()
        eventually {
            vm.state.value.run!!
                .result != null
        }
        vm.state.value.run!!
            .result!!
            .stdout shouldBe "up 3 days\n"
        vm.state.value.run!!
            .running shouldBe false
        connection.commands shouldBe listOf("uptime")

        connection.execFailure = IOException("channel closed")
        vm.exec()
        eventually {
            vm.state.value.run!!
                .error != null
        }
        vm.state.value.run!!
            .error shouldBe "channel closed"
        vm.state.value.run!!
            .result
            .shouldBeNull()

        connection.execFailure = IllegalStateException()
        vm.exec()
        eventually {
            vm.state.value.run!!
                .error == "IllegalStateException"
        }
    }

    @Test
    fun `closing the dialog stops the command, and its result never reaches another snippet`() {
        val vm = viewModel()
        val gate = CompletableDeferred<Unit>()
        connection.execGate = gate
        connection.execResult = ExecResult(0, "\u001B[32mslow\u001B[0m\n", "")
        vm.startRun(Snippet(name = "slow", body = "sleep 60; echo slow", runMode = SnippetRunMode.EXEC))
        vm.exec()
        eventually { connection.commands.isNotEmpty() }
        vm.state.value.run!!
            .running shouldBe true
        vm.closeRun()
        vm.startRun(Snippet(name = "other", body = "true", runMode = SnippetRunMode.EXEC))
        gate.complete(Unit)
        vm.state.value.run!!
            .result
            .shouldBeNull()
        vm.state.value.run!!
            .running shouldBe false
        connection.execGate = null
        vm.exec()
        eventually {
            vm.state.value.run!!
                .output != null
        }
        vm.state.value.run!!
            .output!!
            .text shouldBe "slow\n" // shown without its colour codes
    }
}
