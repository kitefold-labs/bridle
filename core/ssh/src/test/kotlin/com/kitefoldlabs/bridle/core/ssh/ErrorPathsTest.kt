package com.kitefoldlabs.bridle.core.ssh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.PublicKey

class ErrorPathsTest {
    @TempDir
    lateinit var home: Path

    @Test
    fun `a failing signer becomes an authentication failure`() =
        runBlocking<Unit> {
            val pair = TestKeys.ec()
            TestSshServer(home, authorizedKeys = setOf(pair.public)).use { srv ->
                val broken =
                    object : SshKeySigner {
                        override val publicKey: PublicKey = pair.public

                        override fun sign(
                            algorithm: String,
                            data: ByteArray,
                        ): ByteArray = throw GeneralSecurityException("user cancelled biometric prompt")
                    }
                val e =
                    shouldThrow<AuthenticationFailedException> {
                        SshjEngine(Dispatchers.IO).connect(
                            srv.target(Credential.Key(broken)),
                            TofuHostKeyVerifier(MemoryKnownHosts(), AcceptingPrompter()),
                        )
                    }
                e.message shouldContain "Authentication failed"
                SignerAuthMethod(broken).toString() shouldBe "SignerAuthMethod(ecdsa-sha2-nistp256)"
                shouldThrow<UnsupportedOperationException> { PublicOnlyKeyProvider(pair.public).private }
            }
        }

    @Test
    fun `exception defaults and redacted descriptions`() {
        AuthenticationFailedException("a").cause shouldBe null
        ConnectionFailedException("b").cause shouldBe null
        val jumpy =
            ConnectTarget(
                "inner",
                22,
                "u",
                listOf(TestSshServer.password()),
                jump = ConnectTarget("bastion", 22, "u", listOf(TestSshServer.password())),
            )
        jumpy.toString() shouldBe "ConnectTarget(u@inner:22, jump=true)"
        ExecResult(0, "", "").exitStatus shouldBe 0
    }
}
