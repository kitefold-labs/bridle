package com.kitefoldlabs.bridle.feature.terminal

import android.content.Context
import com.kitefoldlabs.bridle.core.terminalui.EngineSink
import com.kitefoldlabs.bridle.core.terminalui.TerminalEngine
import com.kitefoldlabs.bridle.core.terminalui.TermlibEngine
import com.kitefoldlabs.bridle.service.SessionService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Creates the emulator for a new terminal tab. Injected so tests can substitute a fake: termlib's
 * JNI library does not load on the host JVM.
 */
fun interface TerminalEngineFactory {
    fun create(): TerminalEngine
}

/** Starts (or refreshes) the foreground service that keeps open tabs alive in the background. */
fun interface SessionServiceStarter {
    fun ensureRunning()
}

/**
 * What a tab opened by [TerminalLauncher] keeps in
 * [com.kitefoldlabs.bridle.service.TerminalTab.attachment]: its emulator and the sink feeding it.
 * Both live as long as the tab, so the screen survives navigation and rotation; closing the tab
 * closes them.
 */
class TerminalAttachment(
    val engine: TerminalEngine,
    val sink: EngineSink,
) : AutoCloseable {
    override fun close() {
        sink.close()
        engine.close()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object TerminalModule {
    @Provides
    fun engineFactory(): TerminalEngineFactory = TerminalEngineFactory { TermlibEngine() }

    @Provides
    fun serviceStarter(
        @ApplicationContext context: Context,
    ): SessionServiceStarter = SessionServiceStarter { SessionService.ensureRunning(context) }
}
