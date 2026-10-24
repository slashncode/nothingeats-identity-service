package io.github.slashncode.nothingeats.example.domain

import java.time.Instant
import kotlin.uuid.Uuid

// Example aggregate of the service template. Replace it with the real domain of the service.

@JvmInline
value class ItemId(
    val value: Uuid,
) {
    override fun toString() = value.toString()

    companion object {
        fun random() = ItemId(Uuid.random())
    }
}

@JvmInline
value class ItemName(
    val value: String,
) {
    init {
        if (value.isBlank()) throw InvalidItemName("must not be blank")
        if (value.length > MAX_LENGTH) throw InvalidItemName("must be at most $MAX_LENGTH characters")
    }

    companion object {
        const val MAX_LENGTH = 100
    }
}

data class Item(
    val id: ItemId,
    val name: ItemName,
    val createdAt: Instant,
)

/** Domain rules are plain exceptions; the application layer turns them into API errors. */
class InvalidItemName(
    reason: String,
) : Exception(reason)

class ItemNameTaken(
    val name: ItemName,
    cause: Throwable? = null,
) : Exception("An item named '${name.value}' already exists", cause)
