package com.kitefoldlabs.bridle.core.security

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric has no Android Keystore, so Tink falls back to an unwrapped keyset here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidKeystoreAeadProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext<Application>()

    private fun prefs() =
        context.getSharedPreferences(AndroidKeystoreAeadProvider.DEFAULT_PREF_FILE, Context.MODE_PRIVATE)

    @Test
    fun `refuses to store keys without the android keystore and discards the unwrapped keyset`() {
        val provider = AndroidKeystoreAeadProvider(context)
        shouldThrow<KeyVaultUnavailableException> { provider.aead() }
        prefs().getString(AndroidKeystoreAeadProvider.DEFAULT_KEYSET_NAME, null).shouldBeNull()
        // Still refused on the next attempt (lazy initialisation is retried, not cached as a failure).
        shouldThrow<KeyVaultUnavailableException> { provider.aead() }
    }

    @Test
    fun `keyset persists across instances when the keystore is not required`() {
        val first = AndroidKeystoreAeadProvider(context, requireKeystore = false)
        val aead = first.aead()
        first.aead() shouldBeSameInstanceAs aead
        val ciphertext = aead.encrypt(byteArrayOf(1, 2, 3), byteArrayOf(9))
        prefs().getString(AndroidKeystoreAeadProvider.DEFAULT_KEYSET_NAME, null).shouldNotBeNull()
        val second = AndroidKeystoreAeadProvider(context, requireKeystore = false)
        second.aead().decrypt(ciphertext, byteArrayOf(9)) shouldBe byteArrayOf(1, 2, 3)
    }

    @Test
    fun `an unreadable keyset is reported`() {
        prefs().edit().putString("broken", "zz-not-hex").commit()
        shouldThrow<KeyVaultUnavailableException> {
            AndroidKeystoreAeadProvider(context, keysetName = "broken", requireKeystore = false).aead()
        }.cause.shouldNotBeNull()
        prefs().edit().putString("broken2", "00ff00ff").commit()
        shouldThrow<KeyVaultUnavailableException> {
            AndroidKeystoreAeadProvider(context, keysetName = "broken2", requireKeystore = false).aead()
        }.cause.shouldNotBeNull()
    }

    @Test
    fun `vault works end to end on the production provider`() {
        val vault = ImportedKeyVault(AndroidKeystoreAeadProvider(context, requireKeystore = false))
        val pair =
            com.kitefoldlabs.bridle.core.security.testdata.TestKeyFiles
                .ed25519()
        val text =
            com.kitefoldlabs.bridle.core.security.testdata.TestKeyFiles
                .openSsh(pair)
        val imported = vault.import(text.toCharArray(), null, "k")
        vault.signer(imported.identity, imported.encryptedKey).sign("ssh-ed25519", byteArrayOf(1)).size shouldBe 64
    }
}
