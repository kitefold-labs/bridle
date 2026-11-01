package com.kitefoldlabs.bridle

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.core.ssh.BouncyCastleRegistration
import com.kitefoldlabs.bridle.service.Notifications
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.Security
import java.security.Signature

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = BridleApplication::class)
class BridleApplicationTest {
    @Test
    fun `startup installs crash reporting and notification channels`() {
        val app = ApplicationProvider.getApplicationContext<BridleApplication>()
        app.crashReporter shouldNotBe null
        app
            .getSystemService(
                NotificationManager::class.java,
            ).getNotificationChannel(Notifications.CHANNEL_CLAUDE) shouldNotBe
            null
        Thread.getDefaultUncaughtExceptionHandler() shouldNotBe null
    }

    @Test
    fun `startup replaces a stripped BC provider with full BouncyCastle`() {
        val app = ApplicationProvider.getApplicationContext<BridleApplication>()
        val original = Security.getProvider(BouncyCastleRegistration.NAME)
        try {
            Security.removeProvider(BouncyCastleRegistration.NAME)
            Security.addProvider(object : Provider(BouncyCastleRegistration.NAME, 1.0, "platform BC") {})
            shouldThrow<NoSuchAlgorithmException> { Signature.getInstance("Ed25519", BouncyCastleRegistration.NAME) }
            app.onCreate()
            Signature
                .getInstance("Ed25519", BouncyCastleRegistration.NAME)
                .provider.javaClass.name shouldBe
                "org.bouncycastle.jce.provider.BouncyCastleProvider"
        } finally {
            Security.removeProvider(BouncyCastleRegistration.NAME)
            original?.let(Security::addProvider)
        }
    }
}
