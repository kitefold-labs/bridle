package com.kitefoldlabs.bridle.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Conventions: observed lists are Flows, writes are suspend functions, and `*Blocking` functions
 * are synchronous for callers that already run on an I/O thread inside a transaction (backup
 * import) or behind a synchronous interface (KnownHostsStore).
 *
 * Writes never use OnConflictStrategy.REPLACE on rows that others reference: REPLACE deletes
 * the old row first, which would fire ON DELETE CASCADE / SET NULL.
 */

@Dao
internal interface HostDao {
    /** Favourites first, then most recently connected, then never-connected hosts by label. */
    @Query(
        """
        SELECT * FROM hosts
        ORDER BY favorite DESC, lastConnectedAtEpochMs IS NULL, lastConnectedAtEpochMs DESC,
                 label COLLATE NOCASE, id
        """,
    )
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    fun observe(id: Long): Flow<HostEntity?>

    @Query("SELECT * FROM hosts ORDER BY id")
    suspend fun getAll(): List<HostEntity>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun get(id: Long): HostEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM hosts WHERE id = :id)")
    suspend fun exists(id: Long): Boolean

    /** Returns the new row id, or -1 when an existing row was updated. */
    @Upsert
    suspend fun upsert(host: HostEntity): Long

    @Insert
    fun insertBlocking(host: HostEntity): Long

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("UPDATE hosts SET lastConnectedAtEpochMs = :atEpochMs WHERE id = :id")
    suspend fun markConnected(
        id: Long,
        atEpochMs: Long,
    ): Int
}

@Dao
internal interface GroupDao {
    @Query("SELECT * FROM host_groups ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM host_groups ORDER BY id")
    suspend fun getAll(): List<GroupEntity>

    @Query("SELECT * FROM host_groups WHERE id = :id")
    suspend fun get(id: Long): GroupEntity?

    @Upsert
    suspend fun upsert(group: GroupEntity): Long

    @Insert
    fun insertBlocking(group: GroupEntity): Long

    @Query("DELETE FROM host_groups WHERE id = :id")
    suspend fun delete(id: Long): Int
}

@Dao
internal interface IdentityDao {
    @Query("SELECT $IDENTITY_COLUMNS FROM identities ORDER BY alias COLLATE NOCASE, id")
    fun observeAll(): Flow<List<IdentityRow>>

    @Query("SELECT $IDENTITY_COLUMNS FROM identities ORDER BY id")
    suspend fun getAll(): List<IdentityRow>

    @Query("SELECT $IDENTITY_COLUMNS FROM identities WHERE id = :id")
    suspend fun get(id: Long): IdentityRow?

    @Insert
    suspend fun insert(identity: IdentityEntity): Long

    /** Updates the metadata only; the encrypted key is left as it is. */
    @Update(entity = IdentityEntity::class)
    suspend fun update(identity: IdentityRow): Int

    @Query("SELECT encryptedPrivateKey FROM identities WHERE id = :id")
    suspend fun encryptedPrivateKey(id: Long): ByteArray?

    @Query("UPDATE identities SET encryptedPrivateKey = :ciphertext WHERE id = :id")
    suspend fun setEncryptedPrivateKey(
        id: Long,
        ciphertext: ByteArray?,
    ): Int

    @Query("DELETE FROM identities WHERE id = :id")
    suspend fun delete(id: Long): Int

    companion object {
        const val IDENTITY_COLUMNS =
            "id, alias, source, algorithm, publicKeyOpenSsh, keystoreAlias, requireUserAuth, " +
                "authValiditySeconds, createdAtEpochMs"
    }
}

@Dao
internal interface KnownHostDao {
    @Query("SELECT * FROM known_hosts ORDER BY host, port, keyType")
    fun observeAll(): Flow<List<KnownHostEntity>>

    @Query("SELECT * FROM known_hosts ORDER BY host, port, keyType")
    suspend fun getAll(): List<KnownHostEntity>

    /** Oldest trusted key first: that is the one the user saw first, so it is preferred. */
    @Query("SELECT * FROM known_hosts WHERE host = :host AND port = :port ORDER BY addedAtEpochMs, keyType")
    fun findBlocking(
        host: String,
        port: Int,
    ): List<KnownHostEntity>

    /** A trusted key is never overwritten by an insert; see [replace]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertBlocking(knownHost: KnownHostEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(knownHost: KnownHostEntity): Long

    @Query(
        """
        UPDATE known_hosts SET lastSeenAtEpochMs = :nowEpochMs
        WHERE host = :host AND port = :port AND keyType = :keyType
        """,
    )
    fun touchBlocking(
        host: String,
        port: Int,
        keyType: String,
        nowEpochMs: Long,
    ): Int

    @Query("DELETE FROM known_hosts WHERE host = :host AND port = :port AND keyType = :keyType")
    suspend fun delete(
        host: String,
        port: Int,
        keyType: String,
    ): Int

    @Query("DELETE FROM known_hosts WHERE host = :host AND port = :port")
    suspend fun deleteAll(
        host: String,
        port: Int,
    ): Int

    /** Forgets every key of the host and trusts [knownHost] instead, atomically. */
    @Transaction
    suspend fun replace(knownHost: KnownHostEntity) {
        deleteAll(knownHost.host, knownHost.port)
        insert(knownHost)
    }
}

@Dao
internal interface LaunchProfileDao {
    @Query("SELECT * FROM launch_profiles ORDER BY pinned DESC, name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<LaunchProfileEntity>>

    @Query("SELECT * FROM launch_profiles WHERE hostId = :hostId ORDER BY pinned DESC, name COLLATE NOCASE, id")
    fun observeForHost(hostId: Long): Flow<List<LaunchProfileEntity>>

    @Query("SELECT * FROM launch_profiles ORDER BY id")
    suspend fun getAll(): List<LaunchProfileEntity>

    @Query("SELECT * FROM launch_profiles WHERE id = :id")
    suspend fun get(id: Long): LaunchProfileEntity?

    @Upsert
    suspend fun upsert(profile: LaunchProfileEntity): Long

    @Insert
    fun insertBlocking(profile: LaunchProfileEntity): Long

    @Query("DELETE FROM launch_profiles WHERE id = :id")
    suspend fun delete(id: Long): Int
}

@Dao
internal interface SnippetDao {
    @Query("SELECT * FROM snippets ORDER BY packageName IS NOT NULL, packageName, name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets ORDER BY id")
    suspend fun getAll(): List<SnippetEntity>

    @Query("SELECT * FROM snippets WHERE id = :id")
    suspend fun get(id: Long): SnippetEntity?

    @Upsert
    suspend fun upsert(snippet: SnippetEntity): Long

    @Insert
    fun insertBlocking(snippet: SnippetEntity): Long

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun delete(id: Long): Int
}

@Dao
internal interface PortForwardDao {
    @Query("SELECT * FROM port_forwards WHERE hostId = :hostId ORDER BY bindPort, id")
    fun observeForHost(hostId: Long): Flow<List<PortForwardEntity>>

    @Query("SELECT * FROM port_forwards ORDER BY id")
    suspend fun getAll(): List<PortForwardEntity>

    @Query("SELECT * FROM port_forwards WHERE id = :id")
    suspend fun get(id: Long): PortForwardEntity?

    @Upsert
    suspend fun upsert(portForward: PortForwardEntity): Long

    @Insert
    fun insertBlocking(portForward: PortForwardEntity): Long

    @Query("DELETE FROM port_forwards WHERE id = :id")
    suspend fun delete(id: Long): Int
}

@Dao
internal interface ComposeHistoryDao {
    @Query("SELECT text FROM compose_history WHERE hostId = :hostId ORDER BY position")
    fun observe(hostId: Long): Flow<List<String>>

    @Query("SELECT text FROM compose_history WHERE hostId = :hostId ORDER BY position")
    suspend fun get(hostId: Long): List<String>

    @Query("SELECT * FROM compose_history ORDER BY hostId, position")
    suspend fun getAll(): List<ComposeHistoryEntity>

    @Insert
    suspend fun insert(entries: List<ComposeHistoryEntity>)

    @Query("DELETE FROM compose_history WHERE hostId = :hostId")
    suspend fun clear(hostId: Long): Int

    @Query("DELETE FROM compose_history")
    suspend fun clearAll(): Int
}
