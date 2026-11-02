package com.kitefoldlabs.bridle.feature

import android.app.Application
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TestMainTest {
    @After
    fun tearDown() = TestMain.reset()

    @Test
    fun `switching Main while another thread uses it is safe`() {
        TestMain.set(Dispatchers.Unconfined) // as a test's setUp does, before any view model exists
        val stop = AtomicBoolean(false)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        // Like a view model's collector that Room resumes on its own thread as a test ends.
        val user =
            thread {
                while (!stop.get()) {
                    try {
                        Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext)
                    } catch (e: IllegalStateException) {
                        errors += e
                    }
                }
            }
        try {
            // Dispatchers.setMain and resetMain here fail with "used concurrently with setting it".
            repeat(SWITCHES) {
                TestMain.reset()
                TestMain.set(Dispatchers.Unconfined)
            }
        } catch (e: IllegalStateException) {
            errors += e
        }
        stop.set(true)
        user.join()
        errors shouldBe emptyList()
    }

    @Test
    fun `Main runs on the set dispatcher, and can delay`() =
        runBlocking<Unit> {
            TestMain.set(Dispatchers.Unconfined)
            Dispatchers.Main.isDispatchNeeded(EmptyCoroutineContext) shouldBe false
            var ran = false
            launch(Dispatchers.Main) {
                delay(1)
                ran = true
            }.join()
            ran shouldBe true
            TestMain.reset()
            Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext) shouldBe false // on the main looper
        }

    private companion object {
        const val SWITCHES = 20_000
    }
}
