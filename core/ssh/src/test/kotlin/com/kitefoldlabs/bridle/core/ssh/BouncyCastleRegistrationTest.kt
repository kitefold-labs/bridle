package com.kitefoldlabs.bridle.core.ssh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.Security

/** Stands in for Android's repackaged BouncyCastle: named "BC", but without sshj's algorithms. */
private class StrippedBouncyCastle : Provider(BouncyCastleRegistration.NAME, "1.0", "platform BC without Ed25519")

/** Stands in for the provider Android keeps just above "BC" for Keystore keys. */
private class KeystoreWorkaround : Provider(WORKAROUND, "1.0", "AndroidKeyStoreBCWorkaround stand-in")

private const val WORKAROUND = "AndroidKeyStoreBCWorkaround"

class BouncyCastleRegistrationTest {
    private lateinit var original: List<Provider>

    @BeforeEach
    fun saveProviders() {
        original = Security.getProviders().toList()
    }

    /** Puts the JVM's provider list back exactly as it was. */
    @AfterEach
    fun restoreProviders() {
        Security.getProviders().forEach { Security.removeProvider(it.name) }
        original.forEach { Security.addProvider(it) }
    }

    private fun positionOf(name: String): Int = Security.getProviders().indexOfFirst { it.name == name }

    /** Simulates Android: [WORKAROUND] then the stripped "BC", two places below the top. */
    private fun androidLikeProviders() {
        Security.removeProvider(BouncyCastleRegistration.NAME)
        Security.insertProviderAt(KeystoreWorkaround(), 2)
        Security.insertProviderAt(StrippedBouncyCastle(), 3)
    }

    @Test
    fun `the platform's stripped BC is replaced in place, below the keystore workaround`() {
        androidLikeProviders()
        // What sshj sees on Android without the fix: "BC" exists but can't do Ed25519.
        shouldThrow<NoSuchAlgorithmException> { SecurityUtils.getSignature("Ed25519") }

        BouncyCastleRegistration.install() shouldBe true

        Security.getProvider(BouncyCastleRegistration.NAME).shouldBeInstanceOf<BouncyCastleProvider>()
        positionOf(BouncyCastleRegistration.NAME) shouldBe 2
        positionOf(WORKAROUND) shouldBe 1
        SecurityUtils.getSignature("Ed25519").provider.shouldBeInstanceOf<BouncyCastleProvider>()
        SecurityUtils.getKeyFactory("Ed25519").provider.name shouldBe BouncyCastleRegistration.NAME
        SecurityUtils.getSignature("SHA256withECDSA").provider.shouldBeInstanceOf<BouncyCastleProvider>()
    }

    @Test
    fun `installing twice changes nothing`() {
        androidLikeProviders()
        BouncyCastleRegistration.install() shouldBe true
        val after = Security.getProviders().toList()
        BouncyCastleRegistration.install() shouldBe false
        Security.getProviders().toList() shouldBe after
    }

    @Test
    fun `creating the sshj engine installs it`() {
        androidLikeProviders()
        SshjEngine()
        Security.getProvider(BouncyCastleRegistration.NAME).shouldBeInstanceOf<BouncyCastleProvider>()
    }

    @Test
    fun `without any BC the full provider is added last`() {
        Security.removeProvider(BouncyCastleRegistration.NAME)
        BouncyCastleRegistration.install() shouldBe true
        positionOf(BouncyCastleRegistration.NAME) shouldBe Security.getProviders().size - 1
        Security.getProvider(BouncyCastleRegistration.NAME).shouldBeInstanceOf<BouncyCastleProvider>()
    }
}
