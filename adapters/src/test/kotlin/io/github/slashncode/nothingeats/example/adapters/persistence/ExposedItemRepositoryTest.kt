package io.github.slashncode.nothingeats.example.adapters.persistence

import io.github.slashncode.nothingeats.example.domain.Item
import io.github.slashncode.nothingeats.example.domain.ItemId
import io.github.slashncode.nothingeats.example.domain.ItemName
import io.github.slashncode.nothingeats.example.domain.ItemNameTaken
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant

class ExposedItemRepositoryTest :
    FunSpec({
        lateinit var database: PostgresDatabase

        beforeTest {
            val config = PostgresTestDatabases.create()
            FlywayMigrations(config).migrate()
            database = PostgresDatabase(config).also { it.start() }
        }

        afterTest { database.stop() }

        fun item(name: String) = Item(ItemId.random(), ItemName(name), Instant.parse("2026-10-24T12:00:00.123456Z"))

        test("stores and finds an item, timestamps included") {
            val repository = ExposedItemRepository(database)
            val item = item("Phantom Pizza")

            repository.add(item)

            repository.find(item.id) shouldBe item
        }

        test("unknown ids are not found") {
            ExposedItemRepository(database).find(ItemId.random()).shouldBeNull()
        }

        test("the unique name constraint surfaces as a domain exception") {
            val repository = ExposedItemRepository(database)
            repository.add(item("Phantom Pizza"))

            shouldThrow<ItemNameTaken> { repository.add(item("Phantom Pizza")) }
        }
    })
