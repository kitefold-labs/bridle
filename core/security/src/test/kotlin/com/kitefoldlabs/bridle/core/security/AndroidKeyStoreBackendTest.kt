package com.kitefoldlabs.bridle.core.security

import android.content.pm.PackageManager
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.model.KeyAlgorithm
import com.kitefoldlabs.bridle.core.ssh.JcaSshKeySigner
import com.kitefoldlabs.bridle.core.ssh.OpenSshKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.InvalidAlgorithmParameterException
import java.security.ProviderException
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec

private class FakePlatform(
    override val sdkInt: Int = 34,
    private val strongBox: Boolean = false,
    private val keystoreVersion: Int = 0,
) : KeystorePlatform {
    override fun hasSystemFeature(
        name: String,
        version: Int,
    ): Boolean =
        when (name) {
            PackageManager.FEATURE_STRONGBOX_KEYSTORE -> strongBox
            AndroidKeyStoreBackend.FEATURE_HARDWARE_KEYSTORE -> keystoreVersion >= version
            else -> false
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidKeyStoreBackendTest {
    @Before
    fun install() {
        FakeAndroidKeyStore.install()
    }

    @After
    fun uninstall() {
        FakeAndroidKeyStore.uninstall()
    }

    @Test
    fun `capabilities come from platform features`() {
        AndroidKeyStoreBackend(FakePlatform(strongBox = true)).isStrongBoxAvailable shouldBe true
        AndroidKeyStoreBackend(FakePlatform()).isStrongBoxAvailable shouldBe false
        AndroidKeyStoreBackend(FakePlatform(sdkInt = 33, keystoreVersion = 200)).supportsEd25519 shouldBe true
        AndroidKeyStoreBackend(FakePlatform(sdkInt = 33, keystoreVersion = 100)).supportsEd25519 shouldBe false
        AndroidKeyStoreBackend(FakePlatform(sdkInt = 32, keystoreVersion = 300)).supportsEd25519 shouldBe false
    }

    @Test
    fun `context constructor reads the package manager`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE, true)
        val backend = AndroidKeyStoreBackend(context)
        backend.isStrongBoxAvailable shouldBe true
        backend.supportsEd25519 shouldBe false
        KeystorePlatform.of(context).sdkInt shouldBe 34
    }

    @Test
    fun `p256 goes to strongbox when available`() {
        val backend = AndroidKeyStoreBackend(FakePlatform(strongBox = true))
        val generated = backend.generateKeyPair("a", KeyGenSpec(KeyAlgorithm.ECDSA_P256))
        generated.level shouldBe HardwareLevel.STRONGBOX
        OpenSshKeys.typeName(generated.publicKey) shouldBe "ecdsa-sha2-nistp256"
        val spec = FakeAndroidKeyStore.specs.single()
        spec.isStrongBoxBacked shouldBe true
        spec.purposes shouldBe KeyProperties.PURPOSE_SIGN
        spec.digests.toList() shouldContainExactly
            listOf(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
        (spec.algorithmParameterSpec as ECGenParameterSpec).name shouldBe "secp256r1"
        spec.isUserAuthenticationRequired shouldBe false
    }

    @Test
    fun `strongbox refusal falls back to the TEE`() {
        FakeAndroidKeyStore.rejectStrongBox = true
        val backend = AndroidKeyStoreBackend(FakePlatform(strongBox = true))
        val generated = backend.generateKeyPair("a", KeyGenSpec(KeyAlgorithm.ECDSA_P256))
        generated.level shouldBe HardwareLevel.TRUSTED_ENVIRONMENT
        FakeAndroidKeyStore.specs.map { it.isStrongBoxBacked } shouldContainExactly listOf(true, false)
        backend.aliases() shouldContainExactly listOf("a")
    }

    @Test
    fun `strongbox is skipped when not preferred, absent or ineligible`() {
        val withStrongBox = AndroidKeyStoreBackend(FakePlatform(strongBox = true))
        withStrongBox.generateKeyPair("a", KeyGenSpec(KeyAlgorithm.ECDSA_P256, preferStrongBox = false)).level shouldBe
            HardwareLevel.TRUSTED_ENVIRONMENT
        withStrongBox.generateKeyPair("b", KeyGenSpec(KeyAlgorithm.ECDSA_P384)).level shouldBe
            HardwareLevel.TRUSTED_ENVIRONMENT
        AndroidKeyStoreBackend(FakePlatform()).generateKeyPair("c", KeyGenSpec(KeyAlgorithm.ECDSA_P256)).level shouldBe
            HardwareLevel.TRUSTED_ENVIRONMENT
        FakeAndroidKeyStore.specs.map { it.isStrongBoxBacked } shouldContainExactly listOf(false, false, false)
        (FakeAndroidKeyStore.specs[1].algorithmParameterSpec as ECGenParameterSpec).name shouldBe "secp384r1"
    }

    @Test
    fun `rsa keys are 3072-bit pkcs1 with sha2 digests`() {
        val backend = AndroidKeyStoreBackend(FakePlatform())
        val generated = backend.generateKeyPair("r", KeyGenSpec(KeyAlgorithm.RSA))
        OpenSshKeys.typeName(generated.publicKey) shouldBe "ssh-rsa"
        val spec = FakeAndroidKeyStore.specs.single()
        spec.keySize shouldBe 3072
        (spec.algorithmParameterSpec as RSAKeyGenParameterSpec).publicExponent shouldBe RSAKeyGenParameterSpec.F4
        spec.digests.toList() shouldContainExactly listOf(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
        spec.signaturePaddings.toList() shouldContainExactly listOf(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
    }

    @Test
    fun `ed25519 needs keymint support and is normalised for sshj`() {
        shouldThrow<KeyGenerationException> {
            AndroidKeyStoreBackend(FakePlatform()).generateKeyPair("e", KeyGenSpec(KeyAlgorithm.ED25519))
        }
        val backend = AndroidKeyStoreBackend(FakePlatform(sdkInt = 33, keystoreVersion = 200))
        val generated = backend.generateKeyPair("e", KeyGenSpec(KeyAlgorithm.ED25519))
        OpenSshKeys.typeName(generated.publicKey) shouldBe "ssh-ed25519"
        OpenSshKeys.typeName(backend.publicKey("e").shouldNotBeNull()) shouldBe "ssh-ed25519"
        val spec = FakeAndroidKeyStore.specs.single()
        (spec.algorithmParameterSpec as ECGenParameterSpec).name shouldBe "ed25519"
        spec.digests.toList() shouldContainExactly listOf(KeyProperties.DIGEST_NONE)
    }

    @Test
    fun `user auth parameters on android 11 and later`() {
        val backend = AndroidKeyStoreBackend(FakePlatform(sdkInt = 34))
        backend.generateKeyPair("t", KeyGenSpec(KeyAlgorithm.ECDSA_P256, UserAuthPolicy.TimeBound(120)))
        backend.generateKeyPair("p", KeyGenSpec(KeyAlgorithm.ECDSA_P256, UserAuthPolicy.PerUse))
        val (timed, perUse) = FakeAndroidKeyStore.specs
        timed.isUserAuthenticationRequired shouldBe true
        timed.userAuthenticationValidityDurationSeconds shouldBe 120
        timed.userAuthenticationType shouldBe
            (KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        perUse.isUserAuthenticationRequired shouldBe true
        perUse.userAuthenticationValidityDurationSeconds shouldBe 0
        perUse.userAuthenticationType shouldBe KeyProperties.AUTH_BIOMETRIC_STRONG
    }

    @Test
    fun `user auth parameters before android 11`() {
        val backend = AndroidKeyStoreBackend(FakePlatform(sdkInt = 29))
        backend.generateKeyPair("t", KeyGenSpec(KeyAlgorithm.ECDSA_P256, UserAuthPolicy.TimeBound(60)))
        backend.generateKeyPair("p", KeyGenSpec(KeyAlgorithm.ECDSA_P256, UserAuthPolicy.PerUse))
        val (timed, perUse) = FakeAndroidKeyStore.specs
        // The SDK 34 runtime translates the legacy setter exactly as Android 11+ devices do:
        // -1 ("every use") becomes validity 0 with strong biometrics only.
        timed.isUserAuthenticationRequired shouldBe true
        timed.userAuthenticationValidityDurationSeconds shouldBe 60
        timed.userAuthenticationType shouldBe
            (KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        perUse.isUserAuthenticationRequired shouldBe true
        perUse.userAuthenticationValidityDurationSeconds shouldBe 0
        perUse.userAuthenticationType shouldBe KeyProperties.AUTH_BIOMETRIC_STRONG
    }

    @Test
    fun `keys can be used, listed and deleted`() {
        val backend = AndroidKeyStoreBackend(FakePlatform())
        backend.privateKey("k").shouldBeNull()
        backend.publicKey("k").shouldBeNull()
        val generated = backend.generateKeyPair("k", KeyGenSpec(KeyAlgorithm.ECDSA_P256))
        val signer = JcaSshKeySigner(generated.publicKey, backend.privateKey("k").shouldNotBeNull())
        signer.sign("ecdsa-sha2-nistp256", byteArrayOf(1, 2, 3)).isNotEmpty() shouldBe true
        backend.aliases() shouldContainExactly listOf("k")
        shouldThrow<KeyGenerationException> { backend.generateKeyPair("k", KeyGenSpec(KeyAlgorithm.ECDSA_P256)) }
        backend.delete("k")
        backend.delete("k")
        backend.aliases().shouldBeEmpty()
    }

    @Test
    fun `keystore failures become KeyGenerationException`() {
        val backend = AndroidKeyStoreBackend(FakePlatform())
        FakeAndroidKeyStore.failure = ProviderException("keystore exploded")
        shouldThrow<KeyGenerationException> {
            backend.generateKeyPair("x", KeyGenSpec(KeyAlgorithm.ECDSA_P256))
        }.cause.shouldBeInstanceOf<ProviderException>()
        FakeAndroidKeyStore.failure = InvalidAlgorithmParameterException("bad spec")
        shouldThrow<KeyGenerationException> {
            backend.generateKeyPair("y", KeyGenSpec(KeyAlgorithm.RSA))
        }.cause.shouldBeInstanceOf<InvalidAlgorithmParameterException>()
        // A per-use key on a device without an enrolled biometric.
        FakeAndroidKeyStore.failure = IllegalStateException("At least one biometric must be enrolled")
        shouldThrow<KeyGenerationException> {
            backend.generateKeyPair("z", KeyGenSpec(KeyAlgorithm.ECDSA_P256, UserAuthPolicy.PerUse))
        }.cause.shouldBeInstanceOf<IllegalStateException>()
    }

    @Test
    fun `strongbox failure of another kind still falls back`() {
        val backend = AndroidKeyStoreBackend(FakePlatform(strongBox = true))
        FakeAndroidKeyStore.failure = ProviderException("strongbox busy")
        shouldThrow<KeyGenerationException> {
            backend.generateKeyPair("x", KeyGenSpec(KeyAlgorithm.ECDSA_P256))
        }
        FakeAndroidKeyStore.specs.map { it.isStrongBoxBacked } shouldContainExactly listOf(true, false)
    }

    @Test
    fun `curve names`() {
        AndroidKeyStoreBackend.curveName(KeyAlgorithm.ECDSA_P256) shouldBe "secp256r1"
        AndroidKeyStoreBackend.curveName(KeyAlgorithm.ECDSA_P384) shouldBe "secp384r1"
    }
}
