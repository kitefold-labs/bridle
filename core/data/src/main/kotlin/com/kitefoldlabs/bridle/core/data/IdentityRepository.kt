package com.kitefoldlabs.bridle.core.data

import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.data.db.toEntity
import com.kitefoldlabs.bridle.core.data.db.toModel
import com.kitefoldlabs.bridle.core.data.db.toRow
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.Validators
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * SSH identities. The [Identity] model carries metadata only; an imported key's ciphertext is
 * stored alongside it and read separately with [encryptedPrivateKey], so lists and lookups never
 * load key material. Deleting an identity clears it from the hosts and groups that used it.
 *
 * This layer never sees plaintext: callers encrypt with a Keystore key before [insert] /
 * [setEncryptedPrivateKey] and decrypt after [encryptedPrivateKey].
 */
public class IdentityRepository internal constructor(
    database: BridleDatabase,
) {
    private val dao = database.identityDao()

    /** Every identity, by alias. */
    public fun observeAll(): Flow<List<Identity>> =
        dao.observeAll().map { identities -> identities.map { it.toModel() } }

    public suspend fun get(id: EntityId): Identity? = dao.get(id)?.toModel()

    /**
     * Inserts a new identity (its id must be 0) with an optional encrypted private key, and
     * returns its id.
     *
     * @throws InvalidEntityException if the alias is not a valid label.
     * @throws android.database.sqlite.SQLiteConstraintException if another identity already uses
     *   the same Keystore alias.
     */
    public suspend fun insert(
        identity: Identity,
        encryptedPrivateKey: ByteArray? = null,
    ): EntityId {
        require(identity.id == 0L) { "insert() takes a new identity; use update() for id ${identity.id}" }
        Validators.label(identity.alias).requireValid()
        return dao.insert(identity.toEntity(encryptedPrivateKey))
    }

    /**
     * Updates an identity's metadata and keeps its encrypted key.
     *
     * @return false if there is no identity with that id.
     * @throws InvalidEntityException if the alias is not a valid label.
     */
    public suspend fun update(identity: Identity): Boolean {
        Validators.label(identity.alias).requireValid()
        return dao.update(identity.toRow()) > 0
    }

    /** The ciphertext stored for [id], or null if there is none (or no such identity). */
    public suspend fun encryptedPrivateKey(id: EntityId): ByteArray? = dao.encryptedPrivateKey(id)

    /** Stores (or, with null, removes) the ciphertext for [id]. Returns false if there is no such identity. */
    public suspend fun setEncryptedPrivateKey(
        id: EntityId,
        ciphertext: ByteArray?,
    ): Boolean = dao.setEncryptedPrivateKey(id, ciphertext) > 0

    /** Deletes the identity and its ciphertext. Deleting any Keystore entry is up to the caller. */
    public suspend fun delete(id: EntityId): Boolean = dao.delete(id) > 0
}
