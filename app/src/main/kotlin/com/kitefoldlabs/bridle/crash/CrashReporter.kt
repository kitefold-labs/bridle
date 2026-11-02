package com.kitefoldlabs.bridle.crash

import android.content.Context
import android.os.Build
import com.kitefoldlabs.bridle.BuildConfig
import com.kitefoldlabs.bridle.core.backup.CrashReport
import com.kitefoldlabs.bridle.core.backup.GitHubIssueUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in, serverless crash reporting. On a crash a scrubbed [CrashReport] (no hostnames, users,
 * paths or terminal content) is written to app-private storage. On the next launch the UI offers to
 * file it as a pre-filled GitHub issue, which the user reviews before submitting. Nothing is
 * sent automatically.
 */
@Singleton
class CrashReporter
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /**
         * Values the scrubber can't recognise by shape (single-word hostnames, usernames, session
         * names), kept up to date by [CrashScrubList]. Read once per report, never blocking.
         */
        @Volatile var sensitiveValues: Collection<String> = emptyList()

        private val file: File get() = File(context.noBackupFilesDir, FILE_NAME)
        private val json = Json { ignoreUnknownKeys = true }

        // Anything at all may go wrong while reporting a crash; it must not stop the crash.
        @Suppress("TooGenericExceptionCaught")
        fun install(previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()) {
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    record(error)
                } catch (ignored: Throwable) {
                    // Reporting must never replace the crash: the previous handler (Android's,
                    // which kills the process) has to run whatever happened here.
                } finally {
                    previous?.uncaughtException(thread, error)
                }
            }
        }

        /** Saves [error] as a scrubbed report (best effort; never throws from the crash path). */
        fun record(
            error: Throwable,
            now: Long = System.currentTimeMillis(),
        ) {
            val report =
                CrashReport.from(
                    throwable = error,
                    appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    timestampEpochMs = now,
                    sensitive = sensitiveValues,
                )
            try {
                file.writeText(json.encodeToString(CrashReport.serializer(), report))
            } catch (_: IOException) {
                // Disk full or similar: losing a crash report is acceptable.
            }
        }

        /** The report saved by the last crash, if any. */
        fun pending(): CrashReport? =
            try {
                file.takeIf { it.exists() }?.readText()?.let { json.decodeFromString(CrashReport.serializer(), it) }
            } catch (_: IOException) {
                null
            } catch (_: SerializationException) {
                discard()
                null
            } catch (_: IllegalArgumentException) {
                discard()
                null
            }

        fun discard() {
            file.delete()
        }

        /** The pre-filled GitHub "new issue" URL for [report], with the user's optional notes. */
        fun issueUrl(
            report: CrashReport,
            notes: String = "",
        ): String = GitHubIssueUrl.build(report = report, userNotes = notes)

        private companion object {
            const val FILE_NAME = "pending-crash.json"
        }
    }
