package com.kitefoldlabs.bridle.core.data

import com.kitefoldlabs.bridle.core.data.db.BridleDatabase
import com.kitefoldlabs.bridle.core.data.db.toEntity
import com.kitefoldlabs.bridle.core.data.db.toModel
import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Snippet
import com.kitefoldlabs.bridle.core.model.Validation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Saved command snippets. */
public class SnippetRepository internal constructor(
    database: BridleDatabase,
) {
    private val dao = database.snippetDao()

    /** Every snippet: those without a package first, then by package, then by name. */
    public fun observeAll(): Flow<List<Snippet>> = dao.observeAll().map { snippets -> snippets.map { it.toModel() } }

    public suspend fun get(id: EntityId): Snippet? = dao.get(id)?.toModel()

    /**
     * Inserts [snippet] (when its id is 0) or updates it, and returns its id.
     *
     * @throws InvalidEntityException if the name is blank (the rule backups are imported with).
     */
    public suspend fun upsert(snippet: Snippet): EntityId {
        if (snippet.name.isBlank()) throw InvalidEntityException(Validation.Invalid("name", Validation.Reason.BLANK))
        return upsertedId(snippet.id, dao.upsert(snippet.toEntity()))
    }

    public suspend fun delete(id: EntityId): Boolean = dao.delete(id) > 0
}
