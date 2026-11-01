package com.kitefoldlabs.bridle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kitefoldlabs.bridle.core.data.db.Converters
import com.kitefoldlabs.bridle.core.data.settings.Settings
import com.kitefoldlabs.bridle.core.model.Snippet
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DataModuleTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    private fun CoroutineScope.settingsStore() =
        PreferenceDataStoreFactory.create(scope = this) { File(folder.root, "test.preferences_pb") }

    @Test
    fun `create opens the on-disk database and settings`() =
        runTest {
            val data = DataModule.create(context, backgroundScope)

            data.snippets.upsert(Snippet(name = "ls", body = "ls -la"))

            data.snippets
                .observeAll()
                .first()
                .map { it.name } shouldBe listOf("ls")
            data.settings.settings.first() shouldBe Settings()
            context.getDatabasePath(DataModule.DATABASE_NAME).exists() shouldBe true
            data.close()
        }

    @Test
    fun `inMemory can allow synchronous main-thread queries`() =
        runTest {
            val data = DataModule.inMemory(context, backgroundScope.settingsStore(), allowMainThreadQueries = true)

            data.knownHosts.trust(Fixtures.knownHost())

            data.knownHosts.find("devbox.example.test", 22).size shouldBe 1
            data.close()
        }

    @Test
    fun `inMemory refuses main-thread queries by default`() =
        runTest {
            val data = DataModule.inMemory(context, backgroundScope.settingsStore())

            shouldThrow<IllegalStateException> { data.knownHosts.find("devbox.example.test", 22) }
            data.close()
        }

    @Test
    fun `every repository is wired to the same database`() =
        runTest {
            val data = DataModule.inMemory(context, backgroundScope.settingsStore(), allowMainThreadQueries = true)
            val hostId = data.hosts.upsert(Fixtures.host())
            data.composeHistory.save(hostId, listOf("ls"))

            data.backup
                .existingData()
                .hosts
                .single()
                .id shouldBe hostId
            data.groups.observeAll().first() shouldBe emptyList()
            data.identities.observeAll().first() shouldBe emptyList()
            data.launchProfiles.observeAll().first() shouldBe emptyList()
            data.portForwards.observeForHost(hostId).first() shouldBe emptyList()
            data.composeHistory.get(hostId) shouldBe listOf("ls")
            data.close()
        }

    @Test
    fun `converters store sorted JSON`() {
        val converters = Converters()

        converters.tagsToJson(setOf("b", "a")) shouldBe """["a","b"]"""
        converters.tagsFromJson("""["b","a"]""") shouldBe setOf("a", "b")
        converters.envToJson(mapOf("Z" to "1", "A" to "2")) shouldBe """{"A":"2","Z":"1"}"""
        converters.envFromJson("{}") shouldBe emptyMap()
    }
}
