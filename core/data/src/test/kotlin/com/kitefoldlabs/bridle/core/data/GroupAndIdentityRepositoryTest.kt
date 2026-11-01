package com.kitefoldlabs.bridle.core.data

import android.database.sqlite.SQLiteConstraintException
import app.cash.turbine.test
import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.Validation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GroupAndIdentityRepositoryTest {
    private val db: BridleDatabase = Fixtures.database()
    private val groups = GroupRepository(db)
    private val identities = IdentityRepository(db)

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `groups are upserted, listed by name and deleted`() =
        runTest {
            val work = groups.upsert(Group(name = "work", color = 1, defaultUsername = "dev"))
            val home = groups.upsert(Group(name = "Home"))

            groups.get(work) shouldBe Group(id = work, name = "work", color = 1, defaultUsername = "dev")
            groups.upsert(Group(id = work, name = "Work")) shouldBe work

            groups.observeAll().test {
                awaitItem().map { it.name } shouldBe listOf("Home", "Work")
                groups.delete(home) shouldBe true
                awaitItem().map { it.name } shouldBe listOf("Work")
                cancelAndIgnoreRemainingEvents()
            }
            groups.delete(home) shouldBe false
            groups.get(home).shouldBeNull()
        }

    @Test
    fun `groups need a valid name`() =
        runTest {
            shouldThrow<InvalidEntityException> { groups.upsert(Group(name = " ")) }.problem shouldBe
                Validation.Invalid("label", Validation.Reason.BLANK)
        }

    @Test
    fun `deleting an identity clears it as a group default`() =
        runTest {
            val identityId = identities.insert(Fixtures.identity())
            val groupId = groups.upsert(Group(name = "Work", defaultIdentityId = identityId))

            identities.delete(identityId) shouldBe true

            groups.get(groupId)?.defaultIdentityId.shouldBeNull()
        }

    @Test
    fun `identities keep their ciphertext apart from the model`() =
        runTest {
            val imported = Fixtures.identity(alias = "Laptop key", filler = 3, keystoreAlias = null)
            val ciphertext = byteArrayOf(1, 2, 3, 4)
            val id = identities.insert(imported, ciphertext)

            identities.get(id) shouldBe imported.copy(id = id)
            identities.encryptedPrivateKey(id) shouldBe ciphertext

            identities.update(imported.copy(id = id, alias = "Old laptop")) shouldBe true
            identities.get(id)?.alias shouldBe "Old laptop"
            identities.encryptedPrivateKey(id) shouldBe ciphertext

            identities.setEncryptedPrivateKey(id, byteArrayOf(9)) shouldBe true
            identities.encryptedPrivateKey(id) shouldBe byteArrayOf(9)
            identities.setEncryptedPrivateKey(id, null) shouldBe true
            identities.encryptedPrivateKey(id).shouldBeNull()
        }

    @Test
    fun `keystore identities have no ciphertext`() =
        runTest {
            val id = identities.insert(Fixtures.identity())

            identities.encryptedPrivateKey(id).shouldBeNull()
        }

    @Test
    fun `identities are listed by alias`() =
        runTest {
            identities.insert(Fixtures.identity(alias = "zeta", filler = 1))
            identities.insert(Fixtures.identity(alias = "Alpha", filler = 2))

            identities.observeAll().test {
                awaitItem().map { it.alias } shouldBe listOf("Alpha", "zeta")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `unknown identities are reported`() =
        runTest {
            identities.get(5).shouldBeNull()
            identities.update(Fixtures.identity().copy(id = 5)) shouldBe false
            identities.setEncryptedPrivateKey(5, byteArrayOf(1)) shouldBe false
            identities.encryptedPrivateKey(5).shouldBeNull()
            identities.delete(5) shouldBe false
        }

    @Test
    fun `insert takes new identities only`() =
        runTest {
            shouldThrow<IllegalArgumentException> { identities.insert(Fixtures.identity().copy(id = 3)) }
        }

    @Test
    fun `aliases are validated on insert and update`() =
        runTest {
            shouldThrow<InvalidEntityException> { identities.insert(Fixtures.identity(alias = "")) }
            val id = identities.insert(Fixtures.identity())
            shouldThrow<InvalidEntityException> { identities.update(Fixtures.identity(alias = "").copy(id = id)) }
        }

    @Test
    fun `two identities cannot share a keystore alias`() =
        runTest {
            identities.insert(Fixtures.identity(alias = "one", filler = 1, keystoreAlias = "same"))

            shouldThrow<SQLiteConstraintException> {
                identities.insert(Fixtures.identity(alias = "two", filler = 2, keystoreAlias = "same"))
            }
        }
}
