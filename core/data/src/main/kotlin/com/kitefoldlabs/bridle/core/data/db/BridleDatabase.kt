package com.kitefoldlabs.bridle.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The app's only database. It holds configuration and public key material, never plaintext
 * secrets: imported private keys are stored as Keystore-wrapped ciphertext only.
 *
 * Schema changes must bump [version] and ship a migration; exported schemas live in
 * `core/data/schemas` and are committed.
 */
@Database(
    entities = [
        IdentityEntity::class,
        GroupEntity::class,
        HostEntity::class,
        KnownHostEntity::class,
        LaunchProfileEntity::class,
        SnippetEntity::class,
        PortForwardEntity::class,
        ComposeHistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
internal abstract class BridleDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao

    abstract fun groupDao(): GroupDao

    abstract fun identityDao(): IdentityDao

    abstract fun knownHostDao(): KnownHostDao

    abstract fun launchProfileDao(): LaunchProfileDao

    abstract fun snippetDao(): SnippetDao

    abstract fun portForwardDao(): PortForwardDao

    abstract fun composeHistoryDao(): ComposeHistoryDao
}
