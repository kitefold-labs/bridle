package com.kitefoldlabs.bridle.core.security

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProbeTest {
    @Test
    fun sdk() {
        Probe.sdk() shouldBe 34
    }
}
