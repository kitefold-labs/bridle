package com.kitefoldlabs.bridle.core.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ValidatorsTest {
    private fun reason(v: Validation) = (v as Validation.Invalid).reason

    @Test
    fun `label rules`() {
        Validators.label("Workstation") shouldBe Validation.Valid
        reason(Validators.label("  ")) shouldBe Validation.Reason.BLANK
        reason(Validators.label("x".repeat(65))) shouldBe Validation.Reason.TOO_LONG
        reason(Validators.label("bad\u0007bell")) shouldBe Validation.Reason.ILLEGAL_CHARACTERS
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "example.com", "devbox", "devbox.tail1234.ts.net", "10.0.0.1", "100.101.102.103",
            "::1", "[::1]", "fe80::1%wlan0", "2001:db8::42", "host-1.example.com.",
        ],
    )
    fun `valid hostnames`(host: String) {
        Validators.hostname(host) shouldBe Validation.Valid
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["-bad.com", "bad-.com", "a..b", "host name", "host;rm", "ex\$ample", "under_score.com", "gg::1"],
    )
    fun `invalid hostnames`(host: String) {
        reason(Validators.hostname(host)) shouldBe Validation.Reason.ILLEGAL_CHARACTERS
    }

    @Test
    fun `hostname blank and length`() {
        reason(Validators.hostname("")) shouldBe Validation.Reason.BLANK
        reason(Validators.hostname("[]")) shouldBe Validation.Reason.BLANK
        reason(Validators.hostname("a.".repeat(127) + "ab")) shouldBe Validation.Reason.TOO_LONG
    }

    @Test
    fun `ports`() {
        Validators.port(1) shouldBe Validation.Valid
        Validators.port(65_535) shouldBe Validation.Valid
        reason(Validators.port(0)) shouldBe Validation.Reason.OUT_OF_RANGE
        reason(Validators.port(65_536)) shouldBe Validation.Reason.OUT_OF_RANGE
    }

    @Test
    fun `usernames`() {
        Validators.username("nate") shouldBe Validation.Valid
        Validators.username("svc.deploy-1") shouldBe Validation.Valid
        Validators.username("machine$") shouldBe Validation.Valid
        reason(Validators.username("")) shouldBe Validation.Reason.BLANK
        reason(Validators.username("x".repeat(33))) shouldBe Validation.Reason.TOO_LONG
        reason(Validators.username("-oProxyCommand")) shouldBe Validation.Reason.ILLEGAL_CHARACTERS
        reason(Validators.username("a b")) shouldBe Validation.Reason.ILLEGAL_CHARACTERS
    }

    @Test
    fun `session names`() {
        Validators.sessionName("claude-api_2") shouldBe Validation.Valid
        reason(Validators.sessionName("")) shouldBe Validation.Reason.BLANK
        reason(Validators.sessionName("x".repeat(65))) shouldBe Validation.Reason.TOO_LONG
        reason(Validators.sessionName("a:b")) shouldBe Validation.Reason.ILLEGAL_CHARACTERS
        reason(Validators.sessionName("a.b")) shouldBe Validation.Reason.ILLEGAL_CHARACTERS
        reason(Validators.sessionName("-t")) shouldBe Validation.Reason.ILLEGAL_CHARACTERS
        reason(Validators.sessionName("a'b")) shouldBe Validation.Reason.ILLEGAL_CHARACTERS
    }

    @Test
    fun `env names`() {
        Validators.envName("ANTHROPIC_MODEL") shouldBe Validation.Valid
        reason(Validators.envName(" ")) shouldBe Validation.Reason.BLANK
        reason(Validators.envName("1ABC")) shouldBe Validation.Reason.ILLEGAL_CHARACTERS
    }

    @Test
    fun `all returns first failure`() {
        Validators.all() shouldBe Validation.Valid
        Validators
            .all(
                Validation.Valid,
                Validators.port(0),
                Validators.label(""),
            ).shouldBeInstanceOf<Validation.Invalid>()
            .field shouldBe "port"
    }
}
