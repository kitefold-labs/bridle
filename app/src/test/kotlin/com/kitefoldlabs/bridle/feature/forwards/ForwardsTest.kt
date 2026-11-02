package com.kitefoldlabs.bridle.feature.forwards

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.PortForwardType
import com.kitefoldlabs.bridle.core.session.ConnectionManager
import com.kitefoldlabs.bridle.core.ssh.AuthenticationFailedException
import com.kitefoldlabs.bridle.core.ssh.SshConnection
import com.kitefoldlabs.bridle.feature.FakeConnection
import com.kitefoldlabs.bridle.feature.connections
import com.kitefoldlabs.bridle.feature.eventually
import com.kitefoldlabs.bridle.feature.testData
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ForwardsTest {
    private lateinit var data: DataModule
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var next: () -> SshConnection = { FakeConnection() }
    private val manager: ConnectionManager = connections({ next() }, scope)
    private lateinit var forwards: ForwardManager
    private var hostId: EntityId = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        data = testData(ApplicationProvider.getApplicationContext())
        forwards = ForwardManager(manager, data.portForwards, scope)
        hostId = runBlocking { data.hosts.upsert(Host(label = "dev", hostname = "dev.example.com", username = "me")) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        data.close()
        Dispatchers.resetMain()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun viewModel() =
        ForwardsViewModel(SavedStateHandle(mapOf(ForwardsDestination.ARG_HOST to hostId)), data.portForwards, forwards)

    private fun local(
        port: Int,
        id: EntityId = 0,
    ) = PortForward(
        id = id,
        hostId = hostId,
        type = PortForwardType.LOCAL,
        bindPort = port,
        destHost = "localhost",
        destPort = 3000,
    )

    @Test
    fun `draft validation`() {
        ForwardDraft().validate() shouldContainExactly setOf(ForwardField.BIND_PORT, ForwardField.DEST_PORT)
        ForwardDraft(bindPort = "80", destHost = "bad host", destPort = "70000").validate() shouldContainExactly
            ForwardField.entries.toSet()
        ForwardDraft(bindPort = "8080", destPort = "3000").validate() shouldBe emptySet()
        ForwardDraft(type = PortForwardType.DYNAMIC, bindPort = "1080", destHost = "").validate() shouldBe emptySet()
        ForwardDraft(type = PortForwardType.DYNAMIC, bindPort = "1080", autoStart = true).toForward(5) shouldBe
            PortForward(hostId = 5, type = PortForwardType.DYNAMIC, bindPort = 1080, autoStart = true)
        val local = ForwardDraft(id = 9, bindPort = "8080", destHost = " db ", destPort = "5432").toForward(5)
        local shouldBe PortForward(9, 5, PortForwardType.LOCAL, 8080, "db", 5432)
        ForwardDraft.from(local) shouldBe ForwardDraft(9, PortForwardType.LOCAL, "8080", "db", "5432")
        ForwardDraft.from(PortForward(hostId = 5, type = PortForwardType.DYNAMIC, bindPort = 1080)) shouldBe
            ForwardDraft(type = PortForwardType.DYNAMIC, bindPort = "1080", destHost = "localhost", destPort = "")
    }

    @Test
    fun `edits, starts, counts connections and stops forwards`() {
        val vm = viewModel()
        eventually { vm.state.value.loaded }
        vm.saveDraft() // nothing to save
        vm.create()
        vm.saveDraft()
        vm.state.value.draft!!
            .errors shouldContainExactly setOf(ForwardField.BIND_PORT, ForwardField.DEST_PORT)
        val port = freePort()
        vm.updateDraft { it.copy(bindPort = "$port", destPort = "3000") }
        vm.saveDraft()
        vm.state.value.draft
            .shouldBeNull()
        eventually { vm.state.value.forwards.size == 1 }
        val forward =
            vm.state.value.forwards
                .single()

        vm.setRunning(forward, true)
        eventually {
            vm.state.value.statuses[forward.id]
                ?.phase == ForwardStatus.Phase.RUNNING
        }
        vm.state.value.statuses
            .getValue(forward.id)
            .localPort shouldBe port
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.getOutputStream().write("ping".toByteArray())
            val echo = ByteArray(4)
            socket.getInputStream().read(echo)
            String(echo) shouldBe "ping"
            eventually {
                vm.state.value.statuses
                    .getValue(forward.id)
                    .connections == 1
            }
        }
        eventually {
            vm.state.value.statuses
                .getValue(forward.id)
                .connections == 0
        }

        // Saving a running forward restarts it with the new settings.
        val newPort = freePort()
        vm.edit(forward)
        vm.updateDraft { it.copy(bindPort = "$newPort") }
        vm.saveDraft()
        eventually {
            vm.state.value.statuses[forward.id]
                ?.localPort == newPort
        }

        vm.setRunning(forward, false)
        eventually {
            vm.state.value.statuses
                .isEmpty()
        }
        vm.create()
        vm.cancelDraft()
        vm.updateDraft { it.copy(bindPort = "1") }
        vm.state.value.draft
            .shouldBeNull()

        vm.setRunning(forward, true)
        vm.delete(forward)
        eventually {
            vm.state.value.forwards
                .isEmpty() &&
                vm.state.value.statuses
                    .isEmpty()
        }
    }

    @Test
    fun `the host id is required`() {
        shouldThrow<IllegalStateException> { ForwardsViewModel(SavedStateHandle(), data.portForwards, forwards) }
    }

    @Test
    fun `saving a stopped forward does not start it`() {
        val vm = viewModel()
        vm.create()
        vm.updateDraft { it.copy(type = PortForwardType.DYNAMIC, bindPort = "${freePort()}") }
        vm.saveDraft()
        eventually { vm.state.value.forwards.size == 1 }
        forwards.statuses.value shouldBe emptyMap()
    }

    @Test
    fun `dynamic forwards, failures and auto start`() {
        val socks =
            PortForward(
                id = 1,
                hostId = hostId,
                type = PortForwardType.DYNAMIC,
                bindPort = freePort(),
                autoStart = true,
            )
        val manual = local(freePort(), id = 2)
        runBlocking {
            data.portForwards.upsert(socks)
            data.portForwards.upsert(manual)
            forwards.startAutoForwards(hostId)
        }
        eventually { forwards.statuses.value[1]?.phase == ForwardStatus.Phase.RUNNING }
        forwards.statuses.value.keys shouldBe setOf(1L)
        forwards.start(socks) // already running
        forwards.statuses.value.keys shouldBe setOf(1L)

        // The port is taken by the SOCKS proxy.
        forwards.start(local(socks.bindPort, id = 3))
        eventually { forwards.statuses.value[3]?.phase == ForwardStatus.Phase.FAILED }
        forwards.statuses.value
            .getValue(3)
            .error!!
            .isNotEmpty() shouldBe true

        // An invalid forward fails instead of crashing.
        forwards.start(PortForward(id = 4, hostId = hostId, type = PortForwardType.LOCAL, bindPort = freePort()))
        eventually { forwards.statuses.value[4]?.phase == ForwardStatus.Phase.FAILED }
        forwards.statuses.value
            .getValue(4)
            .error!! shouldContain "port"

        // "Disconnect all" stops every forward.
        runBlocking { manager.closeAll() }
        eventually { forwards.statuses.value.isEmpty() }
    }

    @Test
    fun `a forward no longer wanted once it holds its lease stops and lets the connection go`() {
        val asked = AtomicInteger()
        // Wanted when started, not by the time the lease is held (the host's last tab closed).
        forwards.start(local(freePort(), id = 6)) { asked.getAndIncrement() == 0 }
        eventually { asked.get() == 2 && forwards.statuses.value.isEmpty() }
        eventually { runBlocking { manager.activeHosts() }.isEmpty() }
        // Not wanted at all: nothing starts.
        forwards.start(local(freePort(), id = 7)) { false }
        forwards.statuses.value shouldBe emptyMap()
        asked.get() shouldBe 2
    }

    @Test
    fun `rejected credentials are reported`() {
        next = { throw AuthenticationFailedException("denied") }
        forwards.start(local(freePort(), id = 5))
        eventually { forwards.statuses.value[5]?.phase == ForwardStatus.Phase.FAILED }
        forwards.statuses.value
            .getValue(5)
            .error shouldBe "denied"
        forwards.stop(5)
        forwards.stop(5)
        forwards.statuses.value shouldBe emptyMap()
    }
}
