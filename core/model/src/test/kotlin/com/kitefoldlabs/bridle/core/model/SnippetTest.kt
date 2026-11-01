package com.kitefoldlabs.bridle.core.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SnippetTest {
    @Test
    fun `variables are listed once in order with defaults`() {
        val s =
            Snippet(name = "deploy", body = "cd {{dir:~/src}} && git pull {{remote}} {{branch:main}} && echo {{dir}}")
        s.variables shouldBe
            listOf(
                SnippetVariable("dir", "~/src"),
                SnippetVariable("remote", null),
                SnippetVariable("branch", "main"),
            )
    }

    @Test
    fun `render uses values then defaults`() {
        val s = Snippet(name = "x", body = "git push {{remote}} {{branch:main}}")
        s.render(mapOf("remote" to "origin")) shouldBe "git push origin main"
        s.render(mapOf("remote" to "up", "branch" to "dev")) shouldBe "git push up dev"
    }

    @Test
    fun `render fails on missing value without default`() {
        shouldThrow<IllegalArgumentException> { Snippet(name = "x", body = "{{a}}").render(emptyMap()) }
    }

    @Test
    fun `escapes and malformed braces are literal`() {
        Snippet(name = "x", body = "echo {{{{literal}}").render(emptyMap()) shouldBe "echo {{literal}}"
        Snippet(name = "x", body = "awk '{{ print }}'").render(emptyMap()) shouldBe "awk '{{ print }}'"
        Snippet(name = "x", body = "unterminated {{a").render(emptyMap()) shouldBe "unterminated {{a"
        Snippet(name = "x", body = "{{9bad}} {{ok:1}}").render(emptyMap()) shouldBe "{{9bad}} 1"
        Snippet(name = "x", body = "").variables shouldBe emptyList()
        Snippet(name = "x", body = "{{a:}}").render(emptyMap()) shouldBe ""
    }

    @Test
    fun `text without variables renders unchanged`() =
        runBlocking {
            checkAll(Arb.string(0..80)) { body ->
                val clean = body.replace("{", "")
                Snippet(name = "p", body = clean).render(emptyMap()) shouldBe clean
            }
        }
}
