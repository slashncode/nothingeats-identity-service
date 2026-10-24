package io.github.slashncode.nothingeats.example.application

import io.github.slashncode.nothingeats.example.domain.Item
import io.github.slashncode.nothingeats.example.domain.ItemId
import io.github.slashncode.nothingeats.example.domain.ItemNameTaken
import java.util.concurrent.ConcurrentHashMap

/** Test double with the same contract as the real repository, shared via test fixtures. */
class InMemoryItemRepository : ItemRepository {
    private val items = ConcurrentHashMap<ItemId, Item>()

    override suspend fun add(item: Item) {
        if (items.values.any { it.name == item.name }) throw ItemNameTaken(item.name)
        items[item.id] = item
    }

    override suspend fun find(id: ItemId): Item? = items[id]
}
