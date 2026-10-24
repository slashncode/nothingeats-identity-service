package io.github.slashncode.nothingeats.example.application

import io.github.slashncode.nothingeats.example.domain.Item
import io.github.slashncode.nothingeats.example.domain.ItemId

/** Port for storing items; implemented in the adapters module. */
interface ItemRepository {
    /** @throws io.github.slashncode.nothingeats.example.domain.ItemNameTaken if the name is already used. */
    suspend fun add(item: Item)

    suspend fun find(id: ItemId): Item?
}
