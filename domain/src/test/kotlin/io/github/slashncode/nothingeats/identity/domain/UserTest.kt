package io.github.slashncode.nothingeats.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import java.time.Instant

class UserTest :
    FunSpec({
        test("emails are trimmed and lower-cased") {
            Email.parse("  Ghost.Eater@Example.COM ").value shouldBe "ghost.eater@example.com"
        }

        context("rejects malformed emails") {
            withData("no-at-sign", "two@@example.com", "spaces in@example.com", "missing@tld", "@example.com") { raw ->
                shouldThrow<InvalidEmail> { Email.parse(raw) }
            }
        }

        test("passwords are checked by length only") {
            PasswordPolicy.check("correct horse")
            PasswordPolicy.check("a".repeat(PasswordPolicy.MAX_LENGTH))
            shouldThrow<WeakPassword> { PasswordPolicy.check("short") }.message shouldBe "must be at least 10 characters"
            shouldThrow<WeakPassword> { PasswordPolicy.check("a".repeat(PasswordPolicy.MAX_LENGTH + 1)) }
            shouldThrow<WeakPassword> { PasswordPolicy.check(" ".repeat(12)) }
        }

        test("locales fall back to German") {
            Locale.parse("en-GB") shouldBe Locale.EN
            Locale.parse("fr") shouldBe Locale.DE
            Locale.parse(null) shouldBe Locale.DE
        }

        test("a new user is a customer waiting for verification; verifying is idempotent") {
            val now = Instant.parse("2026-10-24T12:00:00Z")
            val user = User.register(Email.parse("a@b.de"), "hash", Locale.DE, now)

            user.roles shouldBe setOf(Role.CUSTOMER)
            user.isVerified shouldBe false

            val verified = user.verified(now.plusSeconds(60))
            verified.isVerified shouldBe true
            verified.verified(now.plusSeconds(120)).verifiedAt shouldBe now.plusSeconds(60)
        }
    })
