package io.github.slashncode.nothingeats.example.adapters.persistence

import io.github.slashncode.nothingeats.example.application.ItemRepository
import io.github.slashncode.nothingeats.example.domain.Item
import io.github.slashncode.nothingeats.example.domain.ItemId
import io.github.slashncode.nothingeats.example.domain.ItemName
import io.github.slashncode.nothingeats.example.domain.ItemNameTaken
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.persistence.isUniqueViolation
import io.github.slashncode.nothingeats.platform.persistence.violatedConstraint
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Mirrors `db/migration`; the migrations are the source of truth for the schema. */
internal object ItemsTable : Table("items") {
    val id = uuid("id")
    val name = text("name")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)

    const val NAME_UNIQUE = "items_name_key"
}

class ExposedItemRepository(
    private val db: PostgresDatabase,
) : ItemRepository {
    override suspend fun add(item: Item) {
        try {
            db.transaction {
                ItemsTable.insert {
                    it[id] = item.id.value
                    it[name] = item.name.value
                    it[createdAt] = item.createdAt
                }
            }
        } catch (e: Exception) {
            if (e.isUniqueViolation() && e.violatedConstraint == ItemsTable.NAME_UNIQUE) throw ItemNameTaken(item.name, e)
            throw e
        }
    }

    override suspend fun find(id: ItemId): Item? =
        db.transaction(readOnly = true) {
            ItemsTable
                .selectAll()
                .where { ItemsTable.id eq id.value }
                .singleOrNull()
                ?.toItem()
        }

    private fun ResultRow.toItem() =
        Item(
            id = ItemId(this[ItemsTable.id]),
            name = ItemName(this[ItemsTable.name]),
            createdAt = this[ItemsTable.createdAt],
        )
}
