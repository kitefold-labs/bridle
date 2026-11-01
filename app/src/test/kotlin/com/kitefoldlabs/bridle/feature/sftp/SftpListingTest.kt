package com.kitefoldlabs.bridle.feature.sftp

import com.kitefoldlabs.bridle.core.ssh.RemoteFile
import io.kotest.matchers.shouldBe
import org.junit.Test

class SftpListingTest {
    private fun file(
        name: String,
        type: RemoteFile.Type = RemoteFile.Type.FILE,
        size: Long = 0,
        mtime: Long = 0,
    ) = RemoteFile(name, "/x/$name", type, size, mtime, 0)

    private val entries =
        listOf(
            file("b.txt", size = 10, mtime = 3),
            file("A.txt", size = 30, mtime = 1),
            file("src", RemoteFile.Type.DIRECTORY, mtime = 2),
            file(".env", size = 20, mtime = 5),
            file("c.txt", size = 30, mtime = 3),
        )

    @Test
    fun `directories first, then by the chosen order`() {
        SftpListing.arrange(entries, SftpSort.NAME, showHidden = false).map { it.name } shouldBe
            listOf("src", "A.txt", "b.txt", "c.txt")
        SftpListing.arrange(entries, SftpSort.NAME, showHidden = true).map { it.name } shouldBe
            listOf("src", ".env", "A.txt", "b.txt", "c.txt")
        SftpListing.arrange(entries, SftpSort.SIZE, showHidden = false).map { it.name } shouldBe
            listOf("src", "A.txt", "c.txt", "b.txt")
        SftpListing.arrange(entries, SftpSort.DATE, showHidden = false).map { it.name } shouldBe
            listOf("src", "b.txt", "c.txt", "A.txt")
    }

    @Test
    fun `breadcrumbs open every ancestor`() {
        SftpListing.breadcrumbs("/") shouldBe listOf(Crumb("/", "/"))
        SftpListing.breadcrumbs("/home/me/src/") shouldBe
            listOf(Crumb("/", "/"), Crumb("home", "/home"), Crumb("me", "/home/me"), Crumb("src", "/home/me/src"))
    }

    @Test
    fun `permissions in symbolic and octal form`() {
        SftpListing.symbolic(0b111_101_101) shouldBe "rwxr-xr-x"
        SftpListing.symbolic(0b110_100_000) shouldBe "rw-r-----"
        SftpListing.symbolic(0b111_111_111_111) shouldBe "rwsrwsrwt"
        SftpListing.symbolic(0b111_000_000_000) shouldBe "--S--S--T"
        SftpListing.octal(0b110_100_100) shouldBe "644"
        SftpListing.octal(0b100_111_101_101) shouldBe "4755"
        SftpListing.octal(0) shouldBe "000"
    }

    @Test
    fun `octal parsing accepts three or four digits`() {
        SftpListing.parseOctal(" 644 ") shouldBe 0b110_100_100
        SftpListing.parseOctal("0755") shouldBe 0b111_101_101
        SftpListing.parseOctal("4755") shouldBe 0b100_111_101_101
        SftpListing.parseOctal("64") shouldBe null
        SftpListing.parseOctal("07555") shouldBe null
        SftpListing.parseOctal("648") shouldBe null
        SftpListing.parseOctal("rwx") shouldBe null
    }

    @Test
    fun `names and sizes`() {
        SftpListing.isValidName("notes.md") shouldBe true
        SftpListing.isValidName(" ") shouldBe false
        SftpListing.isValidName("a/b") shouldBe false
        SftpListing.isValidName(".") shouldBe false
        SftpListing.isValidName("..") shouldBe false
        SftpListing.formatSize(512) shouldBe "512 B"
        SftpListing.formatSize(1536) shouldBe "1.5 KB"
        SftpListing.formatSize(3L * 1024 * 1024) shouldBe "3.0 MB"
        SftpListing.formatSize(Long.MAX_VALUE) shouldBe "8388608.0 TB"
    }

    @Test
    fun `binary detection`() {
        SftpListing.looksBinary("plain text\n") shouldBe false
        SftpListing.looksBinary("nul\u0000byte") shouldBe true
        SftpListing.looksBinary(String(byteArrayOf(0xC3.toByte(), 0x28), Charsets.UTF_8)) shouldBe true
    }

    @Test
    fun `transfer progress`() {
        val transfer = Transfer(1, 1, TransferDirection.UPLOAD, "a", "/a", transferred = 5, total = 10)
        transfer.fraction shouldBe 0.5f
        transfer.copy(total = null).fraction shouldBe null
        transfer.copy(total = 0).fraction shouldBe null
        transfer.copy(transferred = 20).fraction shouldBe 1f
        transfer.isActive shouldBe true
        transfer.copy(status = TransferStatus.RUNNING).isActive shouldBe true
        transfer.copy(status = TransferStatus.DONE).isActive shouldBe false
    }
}
