package com.kitefoldlabs.bridle.core.ssh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.PathComponents
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.junit.jupiter.api.Test

class SftpUnitTest {
    private fun attrs(typeBits: Int?) =
        FileAttributes
            .Builder()
            .withSize(5)
            .withAtimeMtime(1, 2)
            .apply { if (typeBits != null) withPermissions(typeBits or 0b110_100_100) }
            .build()

    @Test
    fun `file types map from mode bits`() {
        attrs(FileMode.Type.SYMLINK.toMask()).toRemote("l", "/l").type shouldBe RemoteFile.Type.SYMLINK
        attrs(FileMode.Type.FIFO_SPECIAL.toMask()).toRemote("p", "/p").type shouldBe RemoteFile.Type.OTHER
        attrs(FileMode.Type.REGULAR.toMask()).toRemote("f", "/f").permissions shouldBe 0b110_100_100
        val noMode = attrs(null).toRemote("x", "/x")
        noMode.type shouldBe RemoteFile.Type.OTHER
        noMode.permissions shouldBe 0
    }

    @Test
    fun `listing drops dot entries and canonicalize keeps explicit paths`() =
        runBlocking<Unit> {
            val client = mockk<SFTPClient>(relaxed = true)
            val reg = attrs(FileMode.Type.REGULAR.toMask())
            every { client.ls("/d") } returns
                listOf(".", "..", "a").map { RemoteResourceInfo(PathComponents("/d", it, "/"), reg) }
            every { client.canonicalize("/tmp/../x") } returns "/x"
            val sftp = SshjSftpSession(client, Dispatchers.IO)
            sftp.list("/d").map { it.name } shouldBe listOf("a")
            sftp.canonicalize("/tmp/../x") shouldBe "/x"
        }

    @Test
    fun `upload replaces an existing target when plain rename refuses`() =
        runBlocking<Unit> {
            val client = mockk<SFTPClient>(relaxed = true)
            every { client.statExistence(any()) } returns null
            every { client.rename(match { it.startsWith("/f.") }, "/f") } throws
                SFTPException(Response.StatusCode.FAILURE, "exists") andThen Unit
            SshjSftpSession(client, Dispatchers.IO).upload(ByteArray(0).inputStream(), "/f", overwrite = true)
            verify { client.rm("/f") }

            every { client.rename(match { it.startsWith("/g.") }, "/g") } throws
                SFTPException(Response.StatusCode.PERMISSION_DENIED, "no")
            every { client.rm(match { it.startsWith("/g.") }) } throws java.io.IOException("cleanup failed too")
            shouldThrow<SFTPException> {
                SshjSftpSession(client, Dispatchers.IO).upload(ByteArray(0).inputStream(), "/g", overwrite = true)
            }
        }

    @Test
    fun `a failed replace keeps the temp file once the old target is gone`() =
        runBlocking<Unit> {
            val client = mockk<SFTPClient>(relaxed = true)
            every { client.statExistence(any()) } returns null
            every { client.rename(match { it.startsWith("/h.") }, "/h") } throws
                SFTPException(Response.StatusCode.FAILURE, "exists")
            shouldThrow<SFTPException> {
                SshjSftpSession(client, Dispatchers.IO).upload(ByteArray(0).inputStream(), "/h", overwrite = true)
            }
            verify { client.rm("/h") }
            // The temp file is the only copy of the data now: it must not be deleted.
            verify(exactly = 0) { client.rm(match { it.startsWith("/h.") }) }
        }

    @Test
    fun `delete reports lstat errors other than a missing file`() =
        runBlocking<Unit> {
            val client = mockk<SFTPClient>(relaxed = true)
            every { client.lstat("/locked") } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "no")
            shouldThrow<SFTPException> { SshjSftpSession(client, Dispatchers.IO).delete("/locked") }
        }

    @Test
    fun `mtime-only change is still a conflict`() =
        runBlocking<Unit> {
            val client = mockk<SFTPClient>(relaxed = true)
            every { client.statExistence("/n") } returns attrs(FileMode.Type.REGULAR.toMask())
            val sftp = SshjSftpSession(client, Dispatchers.IO)
            val opened = sftp.stat("/n")!!
            shouldThrow<RemoteFileChangedException> { sftp.writeText("/n", "x", opened.copy(modifiedEpochSeconds = 1)) }
            shouldThrow<IllegalArgumentException> { sftp.chmod("/n", -1) }
            sftp.close()
        }

    @Test
    fun `the editor's read buffer stops at its limit`() {
        val buffer = CappedBuffer(4) { size -> RemoteFileTooLargeException("/f", size, 4) }
        buffer.write(byteArrayOf(1, 2, 3), 0, 3)
        buffer.write(4)
        shouldThrow<RemoteFileTooLargeException> { buffer.write(5) }.size shouldBe 5
        shouldThrow<RemoteFileTooLargeException> { buffer.write(byteArrayOf(5, 6), 0, 2) }.size shouldBe 6
        buffer.toByteArray() shouldBe byteArrayOf(1, 2, 3, 4)
    }

    @Test
    fun `port bounds are validated`() {
        val conn = mockk<SshConnection>()
        shouldThrow<IllegalArgumentException> { PortForwarder(conn).local(0, "h", 65_536) }
        shouldThrow<IllegalArgumentException> { PortForwarder(conn).dynamic(-1) }
    }
}
