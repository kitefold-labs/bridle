package com.kitefoldlabs.bridle.core.backup

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.pattern
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ScrubberTest {
    private fun scrub(
        text: String,
        vararg sensitive: String,
    ) = Scrubber.scrub(text, sensitive.toList())

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "connect to devbox.tail1234.ts.net failed | connect to <host> failed",
            "connect to Server.Example.COM. timed out | connect to <host>. timed out",
            "10.0.0.1:22 refused | <ip>:22 refused",
            "via 100.101.102.103 | via <ip>",
            "addr [::1]:2222 | addr [<ip>]:2222",
            "addr fe80::1%wlan0 down | addr <ip> down",
            "addr 2001:0db8:85a3:0000:0000:8a2e:0370:7334 | addr <ip>",
            "mapped ::ffff:192.0.2.1 here | mapped <ip> here",
            "mac aa:bb:cc:dd:ee:ff | mac <ip>",
            "at 12:34:56 ok | at 12:34:56 ok",
            "mail ops@example.org now | mail <email> now",
            "login nate@devbox failed | login <user>@<host> failed",
            "login root@[fd7a::1] | login <user>@<host>",
            "fp SHA256:AbCdEfGhIjKlMnOpQrStUvWxYz0123456789+/abcde ok | fp <fingerprint> ok",
            "md5 16:27:ac:a5:76:28:2d:36:63:1b:56:4d:eb:df:a6:48 | md5 <fingerprint>",
            "open /home/nate/.ssh/id_ed25519: denied | open <path>: denied",
            "open /Users/nate/x | open <path>",
            "db /data/user/0/com.kitefoldlabs.bridle/databases/x.db | db <path>",
            "file /storage/emulated/0/Download/backup.bridle | file <path>",
            "cd ~/projects/secret && ls | cd <path> && ls",
            "dir C:\\Users\\nate\\key | dir <path>",
            "GET https://example.com/api?token=abc123 failed | GET <url> failed",
            "sftp://user@host:22/tmp/x | <url>",
            "key AAAAC3NzaC1lZDI1NTE5AAAAIFakeFakeFakeFakeFake end | key <redacted> end",
            "hash 0123456789abcdef0123456789abcdef!! | hash <redacted>!!",
            "token deadbeefdeadbeefdeadbeefdeadbeefdeadbeef | token <redacted>",
            "AnExtraordinarilyLongIdentifierWithoutDigitsAtAll | <redacted>",
            "ClassCastException: java.lang.String cannot be cast | ClassCastException: java.lang.String cannot be cast",
            "load com.kitefoldlabs.bridle.Engine\$Session | load com.kitefoldlabs.bridle.Engine\$Session",
            "file config.json missing | file <host> missing",
            "mime text/plain and/or 3/4 | mime text/plain and/or 3/4",
            "sig (Ljava/lang/String;)V | sig (Ljava/lang/String;)V",
            "e.g. version 1.2 or v1.2.3 | e.g. version 1.2 or v1.2.3",
            "Foo::class and a / b | Foo::class and a / b",
            "IllegalArgumentExceptionHandler | IllegalArgumentExceptionHandler",
            "addr_10.0.0.1 down | addr_<ip> down",
            "bind v10.0.0.1 | bind v<ip>",
            "seen ...10.0.0.1 | seen ...<ip>",
            "run 1.2.3.4.5.6 | run <ip>",
            "via my_host.example.com | via <host>",
            "to ...devbox.lan | to ...<host>",
            "at @devbox.lan or -devbox.lan | at @<host> or -<host>",
            "via bücher.example.com | via <host>",
            "token=ghp_abcdefghijklmnopqrstuvwxyz0123 | token=<redacted>",
            "sum=0123456789abcdef0123456789abcdef | sum=<redacted>",
            "mail nate%40example.com | mail <email>",
            "share \\\\fileserver\\share\\x | share <path>",
            "open ../secret/key.pem | open <path>",
            "run ./deploy.sh now | run <path> now",
        ],
    )
    fun `replaces sensitive shapes`(
        input: String,
        expected: String,
    ) {
        scrub(input) shouldBe expected
    }

    @Test
    fun `removes caller supplied values regardless of case, longest first`() {
        scrub("attach Dev-Box as nate in claude-api", "nate", "dev-box", "Dev-Box-2", "ab", " ") shouldBe
            "attach <redacted> as <redacted> in claude-api"
        scrub("prod-db and prod-db-replica", "prod-db", "prod-db-replica") shouldBe "<redacted> and <redacted>"
    }

    private val separators = Arb.element(" ", "(", ")", "'", ",", ": ", "\n", "\t", "\"")
    private val templates =
        Arb.element(
            "Connection to %s failed",
            "%s",
            "unable to resolve %s: no address",
            "Auth failed (%s)",
            "ssh: connect to host %s port 22",
        )

    private suspend fun neverLeaks(gen: Arb<String>) =
        checkAll(300, gen, templates, separators, separators) { secret, template, before, after ->
            val text = template.format(before + secret + after)
            val out = scrub(text)
            out shouldNotContain secret
        }

    @Test
    fun `never leaks generated IPv4 addresses`() =
        runBlocking<Unit> {
            neverLeaks(Arb.list(Arb.int(0..255), 4..4).map { it.joinToString(".") })
        }

    @Test
    fun `never leaks generated IPv6 addresses`() =
        runBlocking<Unit> {
            val group = Arb.int(0..0xFFFF).map { Integer.toHexString(it) }
            val full = Arb.list(group, 8..8).map { it.joinToString(":") }
            val compressed =
                Arb.list(group, 1..6).map { groups ->
                    val split = groups.size / 2
                    groups.take(split).joinToString(":") + "::" + groups.drop(split).joinToString(":")
                }
            neverLeaks(full)
            neverLeaks(compressed)
            neverLeaks(compressed.map { "$it%wlan0" })
        }

    @Test
    fun `never leaks generated hostnames, emails and user at host`() =
        runBlocking<Unit> {
            val label = Arb.pattern("[a-z][a-z0-9-]{0,10}[a-z0-9]")
            val tld = Arb.element("com", "net", "org", "io", "dev", "local", "lan", "internal", "ts.net", "co.uk")
            val host =
                Arb.list(label, 1..3).map { it.joinToString(".") }.let { labels ->
                    Arb.bind(labels, tld) { l, t -> "$l.$t" }
                }
            val user = Arb.pattern("[a-z_][a-z0-9_.-]{0,12}[a-z0-9]")
            neverLeaks(host)
            neverLeaks(Arb.bind(user, host) { u, h -> "$u@$h" })
            neverLeaks(Arb.bind(user, label) { u, h -> "$u@$h" })
        }

    @Test
    fun `never leaks generated paths and key blobs`() =
        runBlocking<Unit> {
            val segment = Arb.pattern("[A-Za-z0-9._-]{1,12}")
            val root = Arb.element("/home/", "/Users/", "/data/user/0/", "/storage/emulated/0/", "/srv/", "~/")
            val path = Arb.bind(root, Arb.list(segment, 1..4)) { r, s -> r + s.joinToString("/") }
            neverLeaks(path)
            neverLeaks(Arb.pattern("[A-Za-z0-9+/]{40,80}={0,2}"))
            neverLeaks(Arb.pattern("[A-Za-z+/]{10,20}[0-9][A-Za-z0-9+/]{10,20}"))
            neverLeaks(Arb.pattern("[0-9a-f]{32,64}"))
        }

    @Test
    fun `never leaks addresses, hostnames or tokens glued to punctuation`() =
        runBlocking<Unit> {
            val glue = Arb.element("_", "=", ".", "...", "@", "-", "[", "<", "/", "a", "v", ":", "#", "%")
            val label = Arb.pattern("[a-z][a-z0-9_-]{0,10}[a-z0-9]")
            val secrets =
                listOf(
                    Arb.list(Arb.int(0..255), 4..4).map { it.joinToString(".") },
                    Arb.bind(Arb.list(label, 1..3), Arb.element("com", "lan", "ts.net")) { l, t ->
                        l.joinToString(".") + "." + t
                    },
                    Arb.pattern("[A-Za-z_]{10,20}[0-9][A-Za-z0-9_]{10,20}"),
                    Arb.pattern("[0-9a-f]{32,64}"),
                )
            secrets.forEach { secret ->
                checkAll(200, secret, glue, separators) { value, before, after ->
                    scrub("key$before$value$after end") shouldNotContain value
                }
            }
        }

    @Test
    fun `scrubbing is idempotent`() =
        runBlocking<Unit> {
            checkAll(300, Arb.string(0..120)) { text ->
                val once = scrub(text)
                scrub(once) shouldBe once
            }
        }

    @Test
    fun `placeholders are documented constants`() {
        Scrubber.scrub("a 1.2.3.4") shouldContain Scrubber.IP
        Scrubber.MIN_SENSITIVE_LENGTH shouldBe 3
    }
}
