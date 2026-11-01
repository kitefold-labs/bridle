package com.kitefoldlabs.bridle.core.data

import com.kitefoldlabs.bridle.core.model.EntityId
import com.kitefoldlabs.bridle.core.model.Validation

/** Thrown by repository writes when the entity fails the same validation the UI applies. */
public class InvalidEntityException(
    public val problem: Validation.Invalid,
) : IllegalArgumentException("Invalid ${problem.field}: ${problem.reason}")

internal fun Validation.requireValid() {
    if (this is Validation.Invalid) throw InvalidEntityException(this)
}

/** Room's `@Upsert` returns -1 when it updated an existing row instead of inserting one. */
internal fun upsertedId(
    requestedId: EntityId,
    rowId: Long,
): EntityId = if (rowId == UPSERT_UPDATED) requestedId else rowId

private const val UPSERT_UPDATED = -1L
