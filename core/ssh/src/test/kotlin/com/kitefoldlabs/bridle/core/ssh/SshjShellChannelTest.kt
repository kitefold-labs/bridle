package com.kitefoldlabs.bridle.core.ssh

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import org.junit.jupiter.api.Test
import java.io.IOException

class SshjShellChannelTest {
    @Test
    fun `close leaves the network write to the io dispatcher`() {
        val session = mockk<SessionChannel>(relaxed = true) { every { isOpen } returns true }
        val io = StandardTestDispatcher()
        val channel = SshjShellChannel(session, io)

        channel.close()
        channel.close()

        // Nothing on the caller's thread (the UI's main thread, where Android forbids network I/O).
        verify(exactly = 0) { session.close() }
        channel.isOpen shouldBe false
        io.scheduler.advanceUntilIdle()
        verify(exactly = 1) { session.close() }
    }

    @Test
    fun `a failing close is ignored`() {
        val session = mockk<SessionChannel>(relaxed = true) { every { close() } throws IOException("gone") }
        val io = StandardTestDispatcher()

        SshjShellChannel(session, io).close()
        io.scheduler.advanceUntilIdle()

        verify(exactly = 1) { session.close() }
    }
}
