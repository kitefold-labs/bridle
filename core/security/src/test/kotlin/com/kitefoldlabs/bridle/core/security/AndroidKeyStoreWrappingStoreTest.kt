package com.kitefoldlabs.bridle.core.security

import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.InvalidAlgorithmParameterException
import java.security.ProviderException

private class WrappingPlatform(
    override val sdkInt: Int,
) : KeystorePlatform {
    override fun hasSystemFeature(
        name: String,
        version: Int,
    ): Boolean = false
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidKeyStoreWrappingStoreTest {
    private val store = AndroidKeyStoreWrappingStore(WrappingPlatform(sdkInt = 34))

    @Before
    fun setUp() = FakeAndroidKeyStore.install()

    @After
    fun tearDown() = FakeAndroidKeyStore.uninstall()

    @Test
    fun `wrapping keys decrypt only, with rsa oaep and the requested authentication`() {
        store.create("w1", UserAuthPolicy.PerUse)
        store.create("w2", UserAuthPolicy.TimeBound(120))
        val (perUse, timed) = FakeAndroidKeyStore.specs
        perUse.keystoreAlias shouldBe "w1"
        perUse.purposes shouldBe KeyProperties.PURPOSE_DECRYPT
        perUse.keySize shouldBe AndroidKeyStoreWrappingStore.KEY_SIZE
        perUse.encryptionPaddings.toList() shouldBe listOf(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
        perUse.digests.toList() shouldBe listOf(KeyProperties.DIGEST_SHA256)
        perUse.isUserAuthenticationRequired shouldBe true
        perUse.userAuthenticationValidityDurationSeconds shouldBe 0
        perUse.userAuthenticationType shouldBe KeyProperties.AUTH_BIOMETRIC_STRONG
        timed.userAuthenticationValidityDurationSeconds shouldBe 120
        timed.userAuthenticationType shouldBe
            (KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        shouldThrow<IllegalArgumentException> { store.create("w3", UserAuthPolicy.None) }
    }

    @Test
    fun `before android 11 the legacy setter is used`() {
        val old = AndroidKeyStoreWrappingStore(WrappingPlatform(sdkInt = 29))
        // The SDK 34 runtime translates -1 ("every use") to 0 with strong biometrics only.
        val perUse = old.parameterSpec("w", UserAuthPolicy.PerUse)
        perUse.userAuthenticationValidityDurationSeconds shouldBe 0
        perUse.userAuthenticationType shouldBe KeyProperties.AUTH_BIOMETRIC_STRONG
        old.parameterSpec("w", UserAuthPolicy.TimeBound(30)).userAuthenticationValidityDurationSeconds shouldBe 30
    }

    @Test
    fun `data keys round-trip through wrap and the unwrapping cipher`() {
        store.create("w", UserAuthPolicy.TimeBound(60))
        val dataKey = ByteArray(32) { it.toByte() }
        val wrapped = store.wrap("w", dataKey)
        wrapped.size shouldBe AndroidKeyStoreWrappingStore.KEY_SIZE / 8
        store.unwrapCipher("w").doFinal(wrapped).toList() shouldBe dataKey.toList()

        store.delete("w")
        store.delete("w")
        shouldThrow<StoredKeyException> { store.wrap("w", dataKey) }
        shouldThrow<StoredKeyException> { store.unwrapCipher("w") }
    }

    @Test
    fun `keystore refusals become KeyVaultUnavailableException`() {
        val failures =
            listOf(InvalidAlgorithmParameterException("no lock"), ProviderException("x"), IllegalStateException("none"))
        for (failure in failures) {
            FakeAndroidKeyStore.failure = failure
            shouldThrow<KeyVaultUnavailableException> { store.create("w", UserAuthPolicy.PerUse) }.cause shouldBe
                failure
        }
    }

    @Test
    fun `the context constructor uses the device keystore`() {
        val real = AndroidKeyStoreWrappingStore(ApplicationProvider.getApplicationContext<android.content.Context>())
        real.create("w", UserAuthPolicy.PerUse)
        FakeAndroidKeyStore.entries.keys shouldBe setOf("w")
    }
}
