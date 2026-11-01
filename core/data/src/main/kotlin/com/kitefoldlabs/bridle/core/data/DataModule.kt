package com.kitefoldlabs.bridle.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope

/**
 * The data layer's entry point: one database, one settings store, and the repositories over
 * them. Create exactly one per process (with [create]) and share it; a DI container can expose
 * each repository from it. The repositories are thread-safe.
 */
public class DataModule internal constructor(
    private val database: BridleDatabase,
    settingsStore: DataStore<Preferences>,
) {
    public val hosts: HostRepository = HostRepository(database)
    public val groups: GroupRepository = GroupRepository(database)
    public val identities: IdentityRepository = IdentityRepository(database)
    public val knownHosts: KnownHostRepository = KnownHostRepository(database)
    public val launchProfiles: LaunchProfileRepository = LaunchProfileRepository(database)
    public val snippets: SnippetRepository = SnippetRepository(database)
    public val portForwards: PortForwardRepository = PortForwardRepository(database)
    public val composeHistory: ComposeHistoryRepository = ComposeHistoryRepository(database)
    public val backup: BackupRepository = BackupRepository(database)
    public val settings: SettingsRepository = SettingsRepository(settingsStore)

    /** Closes the database. The settings store closes when its scope is cancelled. */
    public fun close() {
        database.close()
    }

    public companion object {
        /** File name of the database, which must be excluded from Android cloud backup. */
        public const val DATABASE_NAME: String = "bridle.db"

        /** Opens the on-disk database and settings store; [scope] owns the settings store's I/O. */
        public fun create(
            context: Context,
            scope: CoroutineScope,
        ): DataModule {
            val app = context.applicationContext
            return DataModule(
                database = Room.databaseBuilder(app, BridleDatabase::class.java, DATABASE_NAME).build(),
                settingsStore = SettingsRepository.createDataStore(app, scope),
            )
        }

        /**
         * A throw-away in-memory database, for tests and previews in other modules.
         * [allowMainThreadQueries] lets synchronous paths run on a test's main thread.
         */
        public fun inMemory(
            context: Context,
            settingsStore: DataStore<Preferences>,
            allowMainThreadQueries: Boolean = false,
        ): DataModule {
            val builder = Room.inMemoryDatabaseBuilder(context.applicationContext, BridleDatabase::class.java)
            if (allowMainThreadQueries) builder.allowMainThreadQueries()
            return DataModule(builder.build(), settingsStore)
        }
    }
}
