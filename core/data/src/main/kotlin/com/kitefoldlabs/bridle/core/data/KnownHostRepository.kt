package com.kitefoldlabs.bridle.core.data

import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.data.db.toEntity
import com.kitefoldlabs.bridle.core.data.db.toModel
import com.kitefoldlabs.bridle.core.model.KnownHost
import com.kitefoldlabs.bridle.core.ssh.KnownHostsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * Trusted host keys. Implements [KnownHostsStore] for `TofuHostKeyVerifier`, whose calls are
 * synchronous and come from an SSH I/O thread (never the main thread).
 *
 * Host names are normalised with [normalizeHost] on every read and write, so `Example.com`,
 * `example.com`, `[::1]` and `::1` each name one host.
 *
 * A trusted key is never overwritten implicitly: [trust] ignores a key type that is already
 * trusted. When the user accepts a changed key, call [replace] (or [forget] and reconnect, which
 * shows the first-use prompt again).
 */
public class KnownHostRepository internal constructor(
    database: BridleDatabase,
) : KnownHostsStore {
    private val dao = database.knownHostDao()

    override fun find(
        host: String,
        port: Int,
    ): List<KnownHost> = dao.findBlocking(normalizeHost(host), port).map { it.toModel() }

    override fun trust(knownHost: KnownHost) {
        dao.insertBlocking(knownHost.toEntity(normalizeHost(knownHost.host)))
    }

    override fun touch(
        host: String,
        port: Int,
        keyType: String,
        nowEpochMs: Long,
    ) {
        dao.touchBlocking(normalizeHost(host), port, keyType, nowEpochMs)
    }

    /** Every trusted key, by host, port and key type. */
    public fun observeAll(): Flow<List<KnownHost>> = dao.observeAll().map { keys -> keys.map { it.toModel() } }

    /** Forgets every key of `host:port` and trusts [knownHost] instead, atomically. */
    public suspend fun replace(knownHost: KnownHost): Unit =
        dao.replace(knownHost.toEntity(normalizeHost(knownHost.host)))

    /** Forgets one key. Returns false if it was not trusted. */
    public suspend fun delete(
        host: String,
        port: Int,
        keyType: String,
    ): Boolean = dao.delete(normalizeHost(host), port, keyType) > 0

    /** Forgets every key of `host:port`; the next connection asks again. Returns how many were removed. */
    public suspend fun forget(
        host: String,
        port: Int,
    ): Int = dao.deleteAll(normalizeHost(host), port)

    public companion object {
        /** Lower-cases [host] and strips IPv6 brackets: `[FE80::1]` → `fe80::1`. */
        public fun normalizeHost(host: String): String = host.trim().removeSurrounding("[", "]").lowercase(Locale.ROOT)
    }
}
