package com.kitefoldlabs.bridle.service

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.kitefoldlabs.bridle.MainActivity
import com.kitefoldlabs.bridle.core.model.EntityId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** The tmux session a notification tap opens. */
data class SessionLink(
    val hostId: EntityId,
    val session: String,
)

/**
 * Builds and reads the intents that open a session from a Claude notification.
 *
 * MainActivity is exported (it is the launcher activity), so any installed app can start it with
 * whatever extras it likes. Intents built here carry a random token that only Bridle's own
 * (immutable) PendingIntents hold, and [consume] ignores intents without it, so another app cannot
 * make Bridle connect to a host and create or attach a tmux session. The token is kept in
 * app-private storage, which backups exclude, so a notification still opens its session after
 * the process restarts.
 */
@Singleton
class SessionLinks
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val token: String by lazy {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_TOKEN, null) ?: newToken().also { prefs.edit { putString(KEY_TOKEN, it) } }
        }

        /** The intent a notification about [link] opens. */
        fun intent(link: SessionLink): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_HOST, link.hostId)
                .putExtra(EXTRA_SESSION, link.session)
                .putExtra(EXTRA_TOKEN, token)

        /**
         * The session [intent] opens, if it is one of Bridle's notification taps. Its extras are
         * removed, so a recreated activity does not open the session again; an intent replayed
         * from the recent-apps list (which keeps the original extras) opens nothing either.
         */
        fun consume(intent: Intent?): SessionLink? {
            if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            val hostId = intent.getLongExtra(EXTRA_HOST, NO_HOST)
            val session = intent.getStringExtra(EXTRA_SESSION)
            val presented = intent.getStringExtra(EXTRA_TOKEN)
            intent.removeExtra(EXTRA_HOST)
            intent.removeExtra(EXTRA_SESSION)
            intent.removeExtra(EXTRA_TOKEN)
            val genuine = presented != null && MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())
            return if (genuine && hostId != NO_HOST && session != null) SessionLink(hostId, session) else null
        }

        companion object {
            const val EXTRA_HOST: String = "host"
            const val EXTRA_SESSION: String = "session"
            const val EXTRA_TOKEN: String = "token"
            private const val NO_HOST = -1L
            private const val PREFS = "session_links"
            private const val KEY_TOKEN = "token"
            private const val TOKEN_BYTES = 32

            private fun newToken(): String =
                ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        }
    }
