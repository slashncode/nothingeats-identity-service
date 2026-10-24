package io.github.slashncode.nothingeats.example.application

import io.github.slashncode.nothingeats.example.domain.ItemId
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.Violation
import io.github.slashncode.nothingeats.platform.testing.RecordingAnalytics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ItemUseCasesTest :
    FunSpec({
        val now = Instant.parse("2026-10-24T12:00:00.123456789Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)

        test("creates an item with a trimmed name and records who created it") {
            val items = InMemoryItemRepository()
            val analytics = RecordingAnalytics()

            val item = CreateItem(items, clock, analytics)("  Phantom Pizza ", createdBy = "user-1")

            item.name.value shouldBe "Phantom Pizza"
            item.createdAt shouldBe Instant.parse("2026-10-24T12:00:00.123456Z")
            GetItem(items)(item.id) shouldBe item
            analytics.events.single() shouldBe RecordingAnalytics.Event("item_created", "user-1", mapOf("item_id" to item.id.toString()))
        }

        test("invalid names become a validation error on the name field") {
            val error =
                shouldThrow<AppError.Invalid> {
                    CreateItem(
                        InMemoryItemRepository(),
                        clock,
                        RecordingAnalytics(),
                    )(" ", createdBy = "user-1")
                }

            error.code.value shouldBe "ITEM_INVALID"
            error.violations shouldBe listOf(Violation("name", "must not be blank"))
        }

        test("a taken name is a conflict") {
            val items = InMemoryItemRepository()
            val create = CreateItem(items, clock, RecordingAnalytics())
            create("Phantom Pizza", createdBy = "user-1")

            shouldThrow<AppError.Conflict> { create("Phantom Pizza", createdBy = "user-1") }.code.value shouldBe "ITEM_NAME_TAKEN"
        }

        test("an unknown id is not found") {
            shouldThrow<AppError.NotFound> { GetItem(InMemoryItemRepository())(ItemId.random()) }.code.value shouldBe "ITEM_NOT_FOUND"
        }
    })
