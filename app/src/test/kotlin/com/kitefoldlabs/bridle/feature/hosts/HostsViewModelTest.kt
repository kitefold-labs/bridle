package com.kitefoldlabs.bridle.feature.hosts

import android.app.Application
import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.session.AttachSpec
import com.kitefoldlabs.bridle.core.session.HostConnectionState
import com.kitefoldlabs.bridle.core.ssh.AuthenticationFailedException
import com.kitefoldlabs.bridle.feature.FakeConnector
import com.kitefoldlabs.bridle.feature.MainDispatcherRule
import com.kitefoldlabs.bridle.feature.ScriptedConnection
import com.kitefoldlabs.bridle.feature.TestEnv
import com.kitefoldlabs.bridle.feature.await
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HostsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val connector = FakeConnector()
    private val env = TestEnv(connector)

    @After
    fun tearDown() = env.close()

    private fun host(
        id: Long,
        label: String,
        favorite: Boolean = false,
        last: Long? = null,
        group: Long? = null,
    ) = Host(
        id,
        label,
        "$label.example",
        username = "u",
        favorite = favorite,
        lastConnectedAtEpochMs = last,
        groupId = group,
    )

    @Test
    fun `connection states map to dots`() {
        HostConnectionState.Idle.dot() shouldBe ConnectionDot.IDLE
        HostConnectionState.Connecting.dot() shouldBe ConnectionDot.CONNECTING
        HostConnectionState.Connected(ScriptedConnection()).dot() shouldBe ConnectionDot.CONNECTED
        HostConnectionState.Reconnecting(1, 1.seconds, null).dot() shouldBe ConnectionDot.RECONNECTING
        HostConnectionState.Failed(AuthenticationFailedException("no"), retryable = false).dot() shouldBe
            ConnectionDot.FAILED
    }

    @Test
    fun `sections split favourites, recents and groups`() {
        val groups = listOf(Group(1, "Lab"), Group(2, "Empty"))
        val hosts =
            listOf(
                host(1, "fav", favorite = true, last = 5),
                host(2, "recent-old", last = 1),
                host(3, "recent-new", last = 9),
                host(4, "in-lab", group = 1),
                host(5, "loose"),
                host(6, "orphan", group = 99),
            ) + (10L..15L).map { host(it, "r$it", last = it * 100) }
        val sections = HostsViewModel.sections(hosts, groups, mapOf(1L to ConnectionDot.CONNECTED), query = "")
        sections.map { it.kind } shouldBe
            listOf(SectionKind.FAVORITES, SectionKind.RECENT, SectionKind.GROUP, SectionKind.OTHER)
        sections[0].rows.single().dot shouldBe ConnectionDot.CONNECTED
        sections[1].rows.map { it.host.label } shouldBe listOf("r15", "r14", "r13", "r12", "r11")
        sections[2].groupName shouldBe "Lab"
        sections[2].rows.map { it.host.label } shouldBe listOf("in-lab")
        sections[3].rows.map { it.host.label } shouldBe listOf("recent-old", "recent-new", "loose", "orphan", "r10")
        sections[3].rows.first().dot shouldBe ConnectionDot.IDLE
    }

    @Test
    fun `search matches label, address, user and tags`() {
        val hosts =
            listOf(
                host(1, "Alpha"),
                Host(2, "b", "build.example", username = "ci"),
                Host(3, "c", "c.example", username = "root"),
                Host(4, "d", "d.example", username = "x", tags = setOf("GPU")),
            )

        fun search(q: String) =
            HostsViewModel
                .sections(hosts, emptyList(), emptyMap(), q)
                .single()
                .rows
                .map { it.host.id }
        search(" alp ") shouldBe listOf(1L)
        search("BUILD") shouldBe listOf(2L)
        search("root") shouldBe listOf(3L)
        search("gpu") shouldBe listOf(4L)
        search("nothing") shouldBe emptyList()
    }

    @Test
    fun `view model lists hosts, toggles favourites and deletes after confirmation`() =
        runBlocking<Unit> {
            val vm = HostsViewModel(env.data.hosts, env.data.groups, env.coordinator)
            vm.state.await { !it.loading }.noHosts shouldBe true
            val id = env.data.hosts.upsert(Host(label = "Dev", hostname = "dev.example", username = "nate"))
            val listed = vm.state.await { it.sections.isNotEmpty() }
            listed.sections.single().kind shouldBe SectionKind.OTHER
            val dev =
                listed.sections
                    .single()
                    .rows
                    .single()
                    .host

            vm.toggleFavorite(dev)
            val favorite = vm.state.await { s -> s.sections.firstOrNull()?.kind == SectionKind.FAVORITES }
            vm.toggleFavorite(
                favorite.sections
                    .first()
                    .rows
                    .single()
                    .host,
            )
            vm.state.await { s -> s.sections.firstOrNull()?.kind == SectionKind.OTHER }

            vm.setQuery("zzz")
            vm.state
                .await { it.query == "zzz" }
                .sections
                .single()
                .rows shouldBe emptyList()
            vm.setQuery("")

            vm.confirmDelete() // nothing pending: ignored
            vm.requestDelete(dev)
            vm.state.await { it.pendingDelete == dev }
            vm.cancelDelete()
            vm.state.await { it.pendingDelete == null }
            vm.requestDelete(dev)
            vm.confirmDelete()
            vm.state.await { it.noHosts }
            env.data.hosts.get(id) shouldBe null
        }

    @Test
    fun `dots follow the shared connection once a tab opens`() =
        runBlocking<Unit> {
            val id = env.data.hosts.upsert(Host(label = "Dev", hostname = "dev.example", username = "nate"))
            val vm = HostsViewModel(env.data.hosts, env.data.groups, env.coordinator)
            vm.state
                .await { it.sections.isNotEmpty() }
                .sections[0]
                .rows[0]
                .dot shouldBe ConnectionDot.IDLE
            env.coordinator.openTab(id, "Dev", AttachSpec.Shell, mockk(relaxed = true))
            vm.state.await { s ->
                s.sections
                    .firstOrNull()
                    ?.rows
                    ?.firstOrNull()
                    ?.dot == ConnectionDot.CONNECTED
            }
            env.coordinator.disconnectAll()
        }
}
