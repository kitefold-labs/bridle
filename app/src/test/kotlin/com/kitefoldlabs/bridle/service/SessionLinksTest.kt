package com.kitefoldlabs.bridle.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.MainActivity
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionLinksTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val links = SessionLinks(context)

    @Test
    fun `a notification intent opens its session once`() {
        val intent = links.intent(SessionLink(7, "api"))
        intent.component!!.className shouldBe MainActivity::class.java.name
        (intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) shouldBe Intent.FLAG_ACTIVITY_SINGLE_TOP
        links.consume(intent) shouldBe SessionLink(7, "api")
        links.consume(intent) shouldBe null // consumed: a recreated activity opens nothing
        intent.hasExtra(SessionLinks.EXTRA_TOKEN) shouldBe false
    }

    @Test
    fun `the token survives a process restart`() {
        val intent = links.intent(SessionLink(7, "api"))
        SessionLinks(context).consume(intent) shouldBe SessionLink(7, "api")
    }

    @Test
    fun `intents another app could send open nothing`() {
        fun spoofed(token: String?) =
            Intent(context, MainActivity::class.java)
                .putExtra(SessionLinks.EXTRA_HOST, 7L)
                .putExtra(SessionLinks.EXTRA_SESSION, "api")
                .apply { if (token != null) putExtra(SessionLinks.EXTRA_TOKEN, token) }
        links.consume(null) shouldBe null
        links.consume(spoofed(null)) shouldBe null
        links.consume(spoofed("0".repeat(64))) shouldBe null
        val genuine = links.intent(SessionLink(7, "api")).getStringExtra(SessionLinks.EXTRA_TOKEN)
        links.consume(spoofed(genuine)) shouldBe SessionLink(7, "api")
    }

    @Test
    fun `incomplete or replayed intents open nothing`() {
        val noHost = links.intent(SessionLink(7, "api")).apply { removeExtra(SessionLinks.EXTRA_HOST) }
        links.consume(noHost) shouldBe null
        val noSession = links.intent(SessionLink(7, "api")).apply { removeExtra(SessionLinks.EXTRA_SESSION) }
        links.consume(noSession) shouldBe null
        // Reopened from the recent-apps list, which keeps the original extras.
        val replayed = links.intent(SessionLink(7, "api")).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        links.consume(replayed) shouldBe null
    }
}
