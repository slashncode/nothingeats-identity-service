package io.github.slashncode.nothingeats.identity.application

import io.github.slashncode.nothingeats.identity.domain.Email
import io.github.slashncode.nothingeats.identity.domain.Locale
import io.github.slashncode.nothingeats.identity.domain.Role
import io.github.slashncode.nothingeats.platform.core.analytics.ErasureResult
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.testing.RecordingAnalytics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private const val CLIENT = "10.0.0.1"

private class Identity(
    var now: Instant = Instant.parse("2026-10-24T12:00:00Z"),
) {
    val users = InMemoryUsers()
    val verifications = InMemoryEmailVerifications()
    val refreshTokens = InMemoryRefreshTokens()
    val resets = InMemoryPasswordResets()
    val throttle = InMemoryThrottle()
    var admins = AdminBootstrap(listOf("boss@nothingeats.dev"))
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

    val register = RegisterUser(users, verifications, hasher, events, tx, secrets, clock, analytics, throttle)
    val verify = VerifyEmail(users, verifications, events, tx, clock, analytics, admins)
    val login = Login(users, hasher, sessions, tx, analytics, throttle, admins)
    val requestReset = RequestPasswordReset(users, resets, events, tx, secrets, clock, analytics, throttle)
    val resetPassword = ResetPassword(users, resets, refreshTokens, hasher, events, tx, clock, analytics)
    val roles = ManageRoles(users, analytics)
    val magicLinks = InMemoryMagicLinks()
    val erasures = InMemoryErasures()
    val requestMagicLink = RequestMagicLink(users, magicLinks, events, tx, secrets, clock, throttle)
    val loginWithMagicLink = LoginWithMagicLink(users, magicLinks, sessions, tx, clock, analytics, admins)
    val export = ExportAccount(users, refreshTokens, clock)
    val deleteAccount = DeleteAccount(users, hasher, erasures, events, tx, clock, throttle)
    val setConsent = SetAnalyticsConsent(users, clock)
    val eraseAnalytics = EraseAnalyticsData(erasures, analytics, clock)

    init {
        users.tokens = refreshTokens
    }

    val refresh = RefreshSession(users, refreshTokens, sessions, tx, clock, analytics)
    val logout = Logout(refreshTokens, clock)

    suspend fun verifiedUser(
        email: String = "ghost@nothingeats.dev",
        password: String = "correct horse battery",
    ) {
        register(email, password, "de", CLIENT)
        verify(events.lastVerificationToken!!)
    }
}

class IdentityUseCasesTest :
    FunSpec({
        test("registration stores a pending user and asks for verification") {
            val identity = Identity()

            identity.register(" Ghost@NothingEats.dev ", "correct horse battery", "en-GB", CLIENT)

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
            identity.register("ghost@nothingeats.dev", "correct horse battery", "de", CLIENT)

            identity.register("GHOST@nothingeats.dev", "another long password", "en", CLIENT)

            identity.users.all.size shouldBe 1
            identity.events.events shouldContainExactly listOf("registered:ghost@nothingeats.dev", "attempted:ghost@nothingeats.dev")
        }

        test("invalid input names the field") {
            val identity = Identity()

            shouldThrow<AppError.Invalid> {
                identity.register(
                    "nope",
                    "correct horse battery",
                    null,
                    CLIENT,
                )
            }.violations.single().field shouldBe
                "email"
            shouldThrow<AppError.Invalid> { identity.register("a@b.de", "short", null, CLIENT) }.violations.single().field shouldBe
                "password"
        }

        test("a verification token works once and only before it expires") {
            val identity = Identity()
            identity.register("ghost@nothingeats.dev", "correct horse battery", "de", CLIENT)
            val token = identity.events.lastVerificationToken!!

            identity.verify(token)
            identity.users.all.values
                .single()
                .isVerified shouldBe true
            identity.events.events.last() shouldBe "verified:ghost@nothingeats.dev"
            shouldThrow<AppError.Invalid> { identity.verify(token) }.code.value shouldBe "VERIFICATION_TOKEN_INVALID"

            identity.register("late@nothingeats.dev", "correct horse battery", "de", CLIENT)
            identity.now = identity.now.plus(25, ChronoUnit.HOURS)
            shouldThrow<AppError.Invalid> { identity.verify(identity.events.lastVerificationToken!!) }
        }

        test("login answers the same for unknown addresses and wrong passwords") {
            val identity = Identity()
            identity.verifiedUser()

            shouldThrow<AppError.Unauthenticated> {
                identity.login(
                    "nobody@nothingeats.dev",
                    "correct horse battery",
                    CLIENT,
                )
            }.code.value shouldBe
                "INVALID_CREDENTIALS"
            shouldThrow<AppError.Unauthenticated> {
                identity.login(
                    "ghost@nothingeats.dev",
                    "wrong password!!",
                    CLIENT,
                )
            }.code.value shouldBe
                "INVALID_CREDENTIALS"
        }

        test("unverified accounts cannot log in") {
            val identity = Identity()
            identity.register("ghost@nothingeats.dev", "correct horse battery", "de", CLIENT)

            shouldThrow<AppError.Forbidden> { identity.login("ghost@nothingeats.dev", "correct horse battery", CLIENT) }.code.value shouldBe
                "EMAIL_NOT_VERIFIED"
        }

        test("login issues tokens and replaces outdated password hashes") {
            val identity = Identity()
            identity.verifiedUser()
            identity.hasher.outdated = true

            val session = identity.login("ghost@nothingeats.dev", "correct horse battery", CLIENT)

            session.accessToken.token shouldBe "access-for-${session.user.id}"
            identity.refreshTokens.all.size shouldBe 1
            identity.analytics.events
                .last()
                .name shouldBe "user_logged_in"
        }

        test("refreshing rotates the token within its family") {
            val identity = Identity()
            identity.verifiedUser()
            val first = identity.login("ghost@nothingeats.dev", "correct horse battery", CLIENT)

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
            val first = identity.login("ghost@nothingeats.dev", "correct horse battery", CLIENT)
            val second = identity.refresh(first.refreshToken)

            shouldThrow<AppError.Unauthenticated> { identity.refresh(first.refreshToken) }.code.value shouldBe "REFRESH_TOKEN_REUSED"
            shouldThrow<AppError.Unauthenticated> { identity.refresh(second.refreshToken) }.code.value shouldBe "REFRESH_TOKEN_REUSED"
            identity.analytics.events.any { it.name == "refresh_token_reused" } shouldBe true
        }

        test("expired and unknown refresh tokens are rejected, logout revokes") {
            val identity = Identity()
            identity.verifiedUser()
            val session = identity.login("ghost@nothingeats.dev", "correct horse battery", CLIENT)

            shouldThrow<AppError.Unauthenticated> { identity.refresh("made-up") }.code.value shouldBe "REFRESH_TOKEN_INVALID"
            shouldThrow<AppError.Unauthenticated> { identity.refresh(null) }

            identity.logout(session.refreshToken)
            shouldThrow<AppError.Unauthenticated> { identity.refresh(session.refreshToken) }

            val other = identity.login("ghost@nothingeats.dev", "correct horse battery", CLIENT)
            identity.now = identity.now.plus(31, ChronoUnit.DAYS)
            shouldThrow<AppError.Unauthenticated> { identity.refresh(other.refreshToken) }.code.value shouldBe "REFRESH_TOKEN_EXPIRED"
        }
        test("login is throttled per client and locks an account after ten failures, for every address alike") {
            val identity = Identity()
            identity.verifiedUser()

            repeat(10) {
                shouldThrow<AppError.Unauthenticated> { identity.login("ghost@nothingeats.dev", "wrong password!!", "10.0.0.$it") }
            }
            // Locked, even with the right password: otherwise "200 or 429" would confirm a guess.
            shouldThrow<AppError.RateLimited> { identity.login("ghost@nothingeats.dev", "correct horse battery", "10.0.0.99") }

            repeat(10) {
                shouldThrow<AppError.Unauthenticated> { identity.login("nobody@nothingeats.dev", "wrong password!!", "10.0.1.$it") }
            }
            shouldThrow<AppError.RateLimited> { identity.login("nobody@nothingeats.dev", "wrong password!!", "10.0.1.99") }

            repeat(30) { runCatching { identity.login("x$it@nothingeats.dev", "wrong password!!", "10.9.9.9") } }
            shouldThrow<AppError.RateLimited> { identity.login("y@nothingeats.dev", "wrong password!!", "10.9.9.9") }
        }

        test("password reset: same answer for unknown addresses, new password ends every session") {
            val identity = Identity()
            identity.verifiedUser()
            val session = identity.login("ghost@nothingeats.dev", "correct horse battery", CLIENT)

            identity.requestReset("nobody@nothingeats.dev", CLIENT)
            identity.events.events.none { it.startsWith("reset-requested") } shouldBe true

            identity.requestReset("Ghost@nothingeats.dev", CLIENT)
            val token = identity.events.lastResetToken!!
            identity.resets.all.keys
                .single() shouldNotBe token

            shouldThrow<AppError.Invalid> { identity.resetPassword(token, "short") }.code.value shouldBe "PASSWORD_INVALID"
            identity.resetPassword(token, "a brand new passphrase")

            shouldThrow<AppError.Unauthenticated> { identity.refresh(session.refreshToken) }
            shouldThrow<AppError.Unauthenticated> { identity.login("ghost@nothingeats.dev", "correct horse battery", CLIENT) }
            identity
                .login("ghost@nothingeats.dev", "a brand new passphrase", CLIENT)
                .user.email.value shouldBe "ghost@nothingeats.dev"
            identity.events.events.last() shouldBe "password-changed:ghost@nothingeats.dev"

            shouldThrow<AppError.Invalid> { identity.resetPassword(token, "yet another passphrase") }.code.value shouldBe
                "PASSWORD_RESET_TOKEN_INVALID"
        }

        test("reset links expire, prove the address, and are capped at three mails an hour per address") {
            val identity = Identity()
            identity.register("late@nothingeats.dev", "correct horse battery", "de", CLIENT)

            identity.requestReset("late@nothingeats.dev", CLIENT)
            val token = identity.events.lastResetToken!!
            identity.now = identity.now.plus(61, ChronoUnit.MINUTES)
            shouldThrow<AppError.Invalid> { identity.resetPassword(token, "a brand new passphrase") }

            identity.requestReset("late@nothingeats.dev", CLIENT)
            identity.resetPassword(identity.events.lastResetToken!!, "a brand new passphrase")
            identity.users.all.values
                .single()
                .isVerified shouldBe true

            identity.requestReset("late@nothingeats.dev", "10.0.0.2")
            shouldThrow<AppError.RateLimited> { identity.requestReset("late@nothingeats.dev", "10.0.0.3") }
        }

        test("bootstrap admins get the role on verification and login, others never") {
            val identity = Identity()
            identity.verifiedUser("boss@nothingeats.dev")
            identity.verifiedUser("ghost@nothingeats.dev")

            identity.users.all.values
                .single { it.email.value == "boss@nothingeats.dev" }
                .roles shouldBe setOf(Role.CUSTOMER, Role.ADMIN)
            identity.login("ghost@nothingeats.dev", "correct horse battery", CLIENT).user.roles shouldBe setOf(Role.CUSTOMER)
        }

        test("admins grant and revoke roles, but not their own admin role") {
            val identity = Identity()
            identity.verifiedUser("boss@nothingeats.dev")
            identity.verifiedUser("ghost@nothingeats.dev")
            val boss = identity.roles.find("boss@nothingeats.dev")
            val ghost = identity.roles.find("GHOST@nothingeats.dev")

            identity.roles
                .grant(boss.id.toString(), ghost.id.toString(), "admin")
                .roles shouldBe setOf(Role.CUSTOMER, Role.ADMIN)
            identity.roles
                .revoke(boss.id.toString(), ghost.id.toString(), "admin")
                .roles shouldBe setOf(Role.CUSTOMER)

            shouldThrow<AppError.Conflict> { identity.roles.revoke(boss.id.toString(), boss.id.toString(), "admin") }
            shouldThrow<AppError.NotFound> { identity.roles.grant(boss.id.toString(), ghost.id.toString(), "superuser") }
            shouldThrow<AppError.NotFound> { identity.roles.find("nobody@nothingeats.dev") }
            identity.analytics.events.map { it.name } shouldContainAll listOf("role_granted", "role_revoked")
        }

        test("magic link: same answer for every address, one use, logs in and verifies") {
            val identity = Identity()
            identity.register("late@nothingeats.dev", "correct horse battery", "de", CLIENT)

            identity.requestMagicLink("nobody@nothingeats.dev", CLIENT)
            identity.events.events.none { it.startsWith("magic-link") } shouldBe true

            identity.requestMagicLink("late@nothingeats.dev", CLIENT)
            val token = identity.events.lastMagicToken!!
            val session = identity.loginWithMagicLink(token)

            session.user.isVerified shouldBe true
            identity.analytics.events
                .last()
                .properties["method"] shouldBe "magic_link"
            shouldThrow<AppError.Unauthenticated> { identity.loginWithMagicLink(token) }.code.value shouldBe "MAGIC_LINK_INVALID"

            identity.requestMagicLink("late@nothingeats.dev", CLIENT)
            identity.now = identity.now.plus(16, ChronoUnit.MINUTES)
            shouldThrow<AppError.Unauthenticated> { identity.loginWithMagicLink(identity.events.lastMagicToken!!) }
        }

        test("export holds the account, consent and active sessions, but no secrets") {
            val identity = Identity()
            identity.verifiedUser()
            val session = identity.login("ghost@nothingeats.dev", "correct horse battery", CLIENT)
            identity.login("ghost@nothingeats.dev", "correct horse battery", CLIENT)
            identity.setConsent(session.user.id.toString(), granted = true)

            val export = identity.export(session.user.id.toString())

            export.user.email.value shouldBe "ghost@nothingeats.dev"
            export.user.analyticsConsent shouldBe true
            export.activeSessions shouldBe 2
        }

        test("deletion needs the password, removes everything, and leaves only the id for erasure") {
            val identity = Identity()
            identity.verifiedUser()
            val session = identity.login("ghost@nothingeats.dev", "correct horse battery", CLIENT)
            val id = session.user.id

            shouldThrow<AppError.Forbidden> { identity.deleteAccount(id.toString(), "wrong password!!") }.code.value shouldBe
                "PASSWORD_CONFIRMATION_FAILED"
            identity.deleteAccount(id.toString(), "correct horse battery")

            identity.users.all shouldBe emptyMap()
            identity.refreshTokens.all shouldBe emptyMap()
            identity.erasures.all.keys shouldBe setOf(id)
            identity.events.events.last() shouldBe "deleted:$id"
            shouldThrow<AppError.Unauthenticated> { identity.refresh(session.refreshToken) }
        }

        test("analytics erasure waits without a key, retries failures, and finishes once") {
            val identity = Identity()
            identity.verifiedUser()
            val id = identity.login("ghost@nothingeats.dev", "correct horse battery", CLIENT).user.id
            identity.deleteAccount(id.toString(), "correct horse battery")

            identity.analytics.erasure = { ErasureResult.NOT_CONFIGURED }
            identity.eraseAnalytics() shouldBe ErasureRun(erased = 0, failed = 0, waiting = 1)

            identity.analytics.erasure = { error("PostHog is down") }
            identity.eraseAnalytics() shouldBe ErasureRun(erased = 0, failed = 1, waiting = 0)

            identity.analytics.erasure = { ErasureResult.ERASED }
            identity.eraseAnalytics() shouldBe ErasureRun(erased = 1, failed = 0, waiting = 0)
            identity.eraseAnalytics() shouldBe ErasureRun(erased = 0, failed = 0, waiting = 0)
            identity.analytics.erased shouldBe listOf(id.toString())
        }
    })
