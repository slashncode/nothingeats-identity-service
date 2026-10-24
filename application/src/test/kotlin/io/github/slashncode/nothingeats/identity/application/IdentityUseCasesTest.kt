package io.github.slashncode.nothingeats.identity.application

import io.github.slashncode.nothingeats.identity.domain.Email
import io.github.slashncode.nothingeats.identity.domain.Locale
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.testing.RecordingAnalytics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private class Identity(
    var now: Instant = Instant.parse("2026-10-24T12:00:00Z"),
) {
    val users = InMemoryUsers()
    val verifications = InMemoryEmailVerifications()
    val refreshTokens = InMemoryRefreshTokens()
    val hasher = FakePasswordHasher()
    val events = RecordingUserEvents()
    val analytics = RecordingAnalytics()
    private val tx = DirectTransactions()
    private val secrets = CountingSecrets()
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = now
        }
    private val sessions = SessionIssuer(refreshTokens, FakeAccessTokens(), secrets, clock)

    val register = RegisterUser(users, verifications, hasher, events, tx, secrets, clock, analytics)
    val verify = VerifyEmail(users, verifications, events, tx, clock, analytics)
    val login = Login(users, hasher, sessions, tx, analytics)
    val refresh = RefreshSession(users, refreshTokens, sessions, tx, clock, analytics)
    val logout = Logout(refreshTokens, clock)

    suspend fun verifiedUser(
        email: String = "ghost@nothingeats.dev",
        password: String = "correct horse battery",
    ) {
        register(email, password, "de")
        verify(events.lastVerificationToken!!)
    }
}

class IdentityUseCasesTest :
    FunSpec({
        test("registration stores a pending user and asks for verification") {
            val identity = Identity()

            identity.register(" Ghost@NothingEats.dev ", "correct horse battery", "en-GB")

            val user =
                identity.users.all.values
                    .single()
            user.email shouldBe Email.parse("ghost@nothingeats.dev")
            user.locale shouldBe Locale.EN
            user.isVerified shouldBe false
            user.passwordHash shouldBe "hashed:correct horse battery"
            identity.events.events shouldContainExactly listOf("registered:ghost@nothingeats.dev")
            identity.analytics.events
                .single()
                .name shouldBe "user_signed_up"
        }

        test("registering an existing address looks the same to the caller but mails the owner instead") {
            val identity = Identity()
            identity.register("ghost@nothingeats.dev", "correct horse battery", "de")

            identity.register("GHOST@nothingeats.dev", "another long password", "en")

            identity.users.all.size shouldBe 1
            identity.events.events shouldContainExactly listOf("registered:ghost@nothingeats.dev", "attempted:ghost@nothingeats.dev")
        }

        test("invalid input names the field") {
            val identity = Identity()

            shouldThrow<AppError.Invalid> { identity.register("nope", "correct horse battery", null) }.violations.single().field shouldBe
                "email"
            shouldThrow<AppError.Invalid> { identity.register("a@b.de", "short", null) }.violations.single().field shouldBe "password"
        }

        test("a verification token works once and only before it expires") {
            val identity = Identity()
            identity.register("ghost@nothingeats.dev", "correct horse battery", "de")
            val token = identity.events.lastVerificationToken!!

            identity.verify(token)
            identity.users.all.values
                .single()
                .isVerified shouldBe true
            identity.events.events.last() shouldBe "verified:ghost@nothingeats.dev"
            shouldThrow<AppError.Invalid> { identity.verify(token) }.code.value shouldBe "VERIFICATION_TOKEN_INVALID"

            identity.register("late@nothingeats.dev", "correct horse battery", "de")
            identity.now = identity.now.plus(25, ChronoUnit.HOURS)
            shouldThrow<AppError.Invalid> { identity.verify(identity.events.lastVerificationToken!!) }
        }

        test("login answers the same for unknown addresses and wrong passwords") {
            val identity = Identity()
            identity.verifiedUser()

            shouldThrow<AppError.Unauthenticated> { identity.login("nobody@nothingeats.dev", "correct horse battery") }.code.value shouldBe
                "INVALID_CREDENTIALS"
            shouldThrow<AppError.Unauthenticated> { identity.login("ghost@nothingeats.dev", "wrong password!!") }.code.value shouldBe
                "INVALID_CREDENTIALS"
        }

        test("unverified accounts cannot log in") {
            val identity = Identity()
            identity.register("ghost@nothingeats.dev", "correct horse battery", "de")

            shouldThrow<AppError.Forbidden> { identity.login("ghost@nothingeats.dev", "correct horse battery") }.code.value shouldBe
                "EMAIL_NOT_VERIFIED"
        }

        test("login issues tokens and replaces outdated password hashes") {
            val identity = Identity()
            identity.verifiedUser()
            identity.hasher.outdated = true

            val session = identity.login("ghost@nothingeats.dev", "correct horse battery")

            session.accessToken.token shouldBe "access-for-${session.user.id}"
            identity.refreshTokens.all.size shouldBe 1
            identity.analytics.events
                .last()
                .name shouldBe "user_logged_in"
        }

        test("refreshing rotates the token within its family") {
            val identity = Identity()
            identity.verifiedUser()
            val first = identity.login("ghost@nothingeats.dev", "correct horse battery")

            val second = identity.refresh(first.refreshToken)

            second.refreshToken shouldNotBe first.refreshToken
            identity.refreshTokens.all.values
                .map { it.familyId }
                .toSet()
                .size shouldBe 1
            identity.refresh(second.refreshToken).user.id shouldBe first.user.id
        }

        test("reusing a refresh token revokes the whole family") {
            val identity = Identity()
            identity.verifiedUser()
            val first = identity.login("ghost@nothingeats.dev", "correct horse battery")
            val second = identity.refresh(first.refreshToken)

            shouldThrow<AppError.Unauthenticated> { identity.refresh(first.refreshToken) }.code.value shouldBe "REFRESH_TOKEN_REUSED"
            shouldThrow<AppError.Unauthenticated> { identity.refresh(second.refreshToken) }.code.value shouldBe "REFRESH_TOKEN_REUSED"
            identity.analytics.events.any { it.name == "refresh_token_reused" } shouldBe true
        }

        test("expired and unknown refresh tokens are rejected, logout revokes") {
            val identity = Identity()
            identity.verifiedUser()
            val session = identity.login("ghost@nothingeats.dev", "correct horse battery")

            shouldThrow<AppError.Unauthenticated> { identity.refresh("made-up") }.code.value shouldBe "REFRESH_TOKEN_INVALID"
            shouldThrow<AppError.Unauthenticated> { identity.refresh(null) }

            identity.logout(session.refreshToken)
            shouldThrow<AppError.Unauthenticated> { identity.refresh(session.refreshToken) }

            val other = identity.login("ghost@nothingeats.dev", "correct horse battery")
            identity.now = identity.now.plus(31, ChronoUnit.DAYS)
            shouldThrow<AppError.Unauthenticated> { identity.refresh(other.refreshToken) }.code.value shouldBe "REFRESH_TOKEN_EXPIRED"
        }
    })
