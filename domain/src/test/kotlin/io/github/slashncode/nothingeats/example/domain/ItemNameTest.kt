package io.github.slashncode.nothingeats.example.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ItemNameTest :
    FunSpec({
        test("accepts names up to the maximum length") {
            ItemName("a".repeat(ItemName.MAX_LENGTH)).value.length shouldBe ItemName.MAX_LENGTH
        }

        test("rejects blank and overlong names") {
            shouldThrow<InvalidItemName> { ItemName("  ") }.message shouldBe "must not be blank"
            shouldThrow<InvalidItemName> { ItemName("a".repeat(ItemName.MAX_LENGTH + 1)) }.message shouldBe
                "must be at most ${ItemName.MAX_LENGTH} characters"
        }
    })
