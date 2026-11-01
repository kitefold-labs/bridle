package com.kitefoldlabs.bridle.core.ssh

import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.server.shell.ProcessShellFactory
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * An in-process SSH server (Apache MINA SSHD) that runs real `/bin/sh` processes, so tests
 * exercise the full protocol path: key exchange, host-key verification, auth, channels and PTYs.
 */
class TestSshServer(
    private val home: Path,
    val hostKey: KeyPair = TestKeys.ec(),
    private val password: String = PASSWORD,
    private val authorizedKeys: Set<PublicKey> = emptySet(),
    /** Replaces the `/bin/sh`-backed exec handler (for protocol-level tests). */
    private val commands: CommandFactory? = null,
) : AutoCloseable {
    val server: SshServer =
        SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            passwordAuthenticator = PasswordAuthenticator { user, pass, _ -> user == USER && pass == password }
            publickeyAuthenticator =
                PublickeyAuthenticator { user, key, _ ->
                    user == USER && authorizedKeys.any { KeyUtils.compareKeys(it, key) }
                }
            keyboardInteractiveAuthenticator = null
            // HOME points at a temp dir so scripts never touch the real home directory.
            commandFactory = commands
                ?: CommandFactory { channel, command ->
                    ProcessShellCommandFactory.INSTANCE.createCommand(channel, "env HOME=$home $command")
                }
            shellFactory = ProcessShellFactory("sh", "env", "HOME=$home", "/bin/sh", "-i")
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            start()
        }

    val port: Int get() = server.port

    fun target(
        vararg credentials: Credential,
        jump: ConnectTarget? = null,
        hostname: String = "127.0.0.1",
    ): ConnectTarget = ConnectTarget(hostname, port, USER, credentials.toList(), jump)

    override fun close() = server.stop(true)

    companion object {
        const val USER = "tester"
        const val PASSWORD = "correct horse"

        fun password(value: String = PASSWORD) = Credential.Password { value.toCharArray() }
    }
}

object TestKeys {
    fun ec(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    fun ed25519(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun rsa(bits: Int = 2048): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()
}

/** In-memory [KnownHostsStore]. */
class MemoryKnownHosts : KnownHostsStore {
    val hosts = mutableListOf<com.kitefoldlabs.bridle.core.model.KnownHost>()
    val touched = mutableListOf<String>()

    override fun find(
        host: String,
        port: Int,
    ) = hosts.filter { it.host == host && it.port == port }

    override fun trust(knownHost: com.kitefoldlabs.bridle.core.model.KnownHost) {
        hosts += knownHost
    }

    override fun touch(
        host: String,
        port: Int,
        keyType: String,
        nowEpochMs: Long,
    ) {
        touched += "$host:$port:$keyType@$nowEpochMs"
    }
}

/** Trust everything the first time (records prompts). */
class AcceptingPrompter(
    private val answer: Boolean = true,
) : HostKeyPrompter {
    val prompts = mutableListOf<HostKeyPrompt>()

    override fun confirmNewHost(prompt: HostKeyPrompt): Boolean {
        prompts += prompt
        return answer
    }
}
