package com.kitefoldlabs.bridle.feature.hosts

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.model.KeySource
import com.kitefoldlabs.bridle.core.model.Validation
import com.kitefoldlabs.bridle.feature.MainDispatcherRule
import com.kitefoldlabs.bridle.feature.TestEnv
import com.kitefoldlabs.bridle.feature.await
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HostEditorViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val env = TestEnv()
    private val data = env.data

    @After
    fun tearDown() = env.close()

    private fun editor(id: Long = 0) =
        HostEditorViewModel(
            SavedStateHandle(mapOf(HostEditorViewModel.ARG_HOST_ID to id)),
            data.hosts,
            data.groups,
            data.identities,
        )

    @Test
    fun `new host shows errors for edited fields, then all of them on save`() =
        runBlocking<Unit> {
            val vm = editor()
            vm.state.await { !it.loading }.isNew shouldBe true
            vm.state.value.errors
                .shouldBeEmpty()
            vm.edit(HostField.PORT) { it.copy(port = "0") }
            vm.state.await { HostField.PORT in it.errors }.errors shouldBe
                mapOf(HostField.PORT to Validation.Reason.OUT_OF_RANGE)
            vm.save()
            vm.state
                .await { it.errors.size == 4 }
                .errors.keys shouldContainExactlyInAnyOrder
                listOf(HostField.LABEL, HostField.HOSTNAME, HostField.USERNAME, HostField.PORT)
            vm.state.value.finished shouldBe false
        }

    @Test
    fun `saving a new host with a key, group and jump host`() =
        runBlocking<Unit> {
            val keyId =
                data.identities.insert(
                    Identity(
                        alias = "phone",
                        source = KeySource.IMPORTED,
                        algorithm = KeyAlgorithm.ED25519,
                        publicKeyOpenSsh = "x",
                    ),
                )
            val jumpId = data.hosts.upsert(Host(label = "Bastion", hostname = "b.example", username = "ops"))
            val vm = editor()
            vm.state.await { it.identities.isNotEmpty() && it.jumpCandidates.isNotEmpty() }
            vm.createGroup("  ") // invalid: ignored
            vm.createGroup(" Lab ")
            val groupId =
                vm.state
                    .await { it.form.groupId != null }
                    .form.groupId
            data.groups.get(groupId!!)!!.name shouldBe "Lab"
            vm.edit(null) {
                it.copy(
                    label = "GPU",
                    hostname = "gpu.internal",
                    username = "ml",
                    identityId = keyId,
                    jumpHostId = jumpId,
                )
            }
            vm.save()
            vm.save() // already saving: ignored
            vm.state.await { it.finished }
            val saved =
                data.hosts
                    .observeAll()
                    .first()
                    .single { it.label == "GPU" }
            saved.identityId shouldBe keyId
            saved.jumpHostId shouldBe jumpId
            saved.groupId shouldBe groupId
        }

    @Test
    fun `a deleted reference is reported instead of crashing`() =
        runBlocking<Unit> {
            val vm = editor()
            vm.state.await { !it.loading }
            vm.edit(null) { it.copy(label = "GPU", hostname = "gpu.internal", username = "ml", identityId = 12345) }
            vm.save()
            vm.state.await { it.missingReference }.finished shouldBe false
        }

    @Test
    fun `editing an existing host, excluding loops from jump hosts, and deleting it`() =
        runBlocking<Unit> {
            val a = data.hosts.upsert(Host(label = "A", hostname = "a.example", username = "u"))
            val b = data.hosts.upsert(Host(label = "B", hostname = "b.example", username = "u", jumpHostId = a))
            val c = data.hosts.upsert(Host(label = "C", hostname = "c.example", username = "u"))
            editor(a).save() // still loading: ignored
            val vm = editor(a)
            val loaded = vm.state.await { !it.loading && it.jumpCandidates.isNotEmpty() }
            loaded.isNew shouldBe false
            loaded.form.label shouldBe "A"
            loaded.jumpCandidates.map { it.id } shouldBe listOf(c) // not A itself, not B (jumps through A)
            vm.edit(HostField.LABEL) { it.copy(label = "A2") }
            vm.save()
            vm.state.await { it.finished }
            data.hosts.get(a)!!.label shouldBe "A2"
            data.hosts.get(b)!!.jumpHostId shouldBe a

            val deleting = editor(c)
            deleting.state.await { !it.loading && it.form.label == "C" }
            deleting.requestDelete()
            deleting.state.await { it.confirmDelete }
            deleting.cancelDelete()
            deleting.state.await { !it.confirmDelete }
            deleting.requestDelete()
            deleting.confirmDelete()
            deleting.state.await { it.finished }
            data.hosts.get(c) shouldBe null
        }

    @Test
    fun `missing host and ignored actions`() =
        runBlocking<Unit> {
            val vm = editor(999)
            vm.state.await { it.notFound }
            vm.save() // ignored
            vm.state.value.saving shouldBe false
            HostEditorViewModel(SavedStateHandle(), data.hosts, data.groups, data.identities)
                .state
                .await { !it.loading }
                .isNew shouldBe true
            val fresh = editor()
            fresh.requestDelete() // new hosts can't be deleted
            fresh.state.await { !it.loading }.confirmDelete shouldBe false
        }

    @Test
    fun `jump candidates survive broken chains`() {
        val hosts =
            listOf(
                Host(1, "a", "a.example", username = "u", jumpHostId = 2),
                Host(2, "b", "b.example", username = "u", jumpHostId = 1),
                Host(3, "c", "c.example", username = "u", jumpHostId = 404),
            )
        HostEditorViewModel.jumpCandidates(5, hosts).map { it.id } shouldBe listOf(1L, 2L, 3L)
        HostEditorViewModel.jumpCandidates(0, hosts) shouldNotBe emptyList<Host>()
    }
}
