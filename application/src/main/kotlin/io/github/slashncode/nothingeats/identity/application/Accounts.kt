package io.github.slashncode.nothingeats.identity.application

import io.github.slashncode.nothingeats.identity.domain.Email
import io.github.slashncode.nothingeats.identity.domain.PasswordReset
import io.github.slashncode.nothingeats.identity.domain.Role
import io.github.slashncode.nothingeats.identity.domain.User
import io.github.slashncode.nothingeats.identity.domain.UserId
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * Accounts with these addresses become admins when they verify or log in (config `ADMIN__EMAILS`).
 * The way to get the first admin without a database console; later admins are granted via
 * [ManageRoles].
 */
class AdminBootstrap(
    emails: Collection<String> = emptyList(),
) {
    private val admins = emails.mapNotNull { runCatching { Email.parse(it) }.getOrNull() }.toSet()

    fun apply(user: User): User = if (user.email in admins && Role.ADMIN !in user.roles) user.withRole(Role.ADMIN) else user
}

/**
 * "Forgot password": mails a one-time link if the address has an account. The answer is the same
 * either way, and per-address limits apply to every address, so nothing reveals which exist.
 */
class RequestPasswordReset(
    private val users: Users,
    private val resets: PasswordResets,
    private val events: UserEvents,
    private val transactions: Transactions,
    private val secrets: Secrets,
    private val clock: Clock,
    private val analytics: ProductAnalytics,
    private val throttle: Throttle,
    private val ttl: Duration = 1.hours,
) {
    suspend operator fun invoke(
        email: String,
        client: String,
    ) {
        throttle.attempt(ThrottleRules.PASSWORD_RESET_PER_CLIENT, client)
        val address =
            runCatching { Email.parse(email) }.getOrNull()
                ?: throw AppError.Invalid(
                    ErrorCode("PASSWORD_RESET_INVALID"),
                    "The email address is invalid",
                    listOf(Violation("email", "is not a valid email address")),
                )
        throttle.attempt(ThrottleRules.PASSWORD_RESET_PER_ACCOUNT, address.value)
        val user = users.findByEmail(address) ?: return
        val token = secrets.newToken()
        transactions.run {
            resets.add(PasswordReset(TokenHashing.hash(token), user.id, clock.instant().plus(ttl.toJavaDuration()), usedAt = null))
            events.passwordResetRequested(user, token)
        }
        analytics.capture("password_reset_requested", distinctId = user.id.toString())
    }
}

/**
 * Sets a new password with the token from the reset mail. Every session ends (whoever knew the
 * old password is out), the address counts as confirmed (the mail arrived), and the owner gets a
 * "your password was changed" mail.
 */
class ResetPassword(
    private val users: Users,
    private val resets: PasswordResets,
    private val refreshTokens: RefreshTokens,
    private val hasher: PasswordHasher,
    private val events: UserEvents,
    private val transactions: Transactions,
    private val clock: Clock,
    private val analytics: ProductAnalytics,
) {
    suspend operator fun invoke(
        token: String,
        password: String,
    ) {
        checkPassword(password, code = "PASSWORD_INVALID")
        val hash = hasher.hash(password)
        val now = clock.instant()
        val user =
            transactions.run {
                val reset = resets.find(TokenHashing.hash(token))?.takeIf { it.isUsable(now) } ?: throw invalidToken()
                if (!resets.markUsed(reset.tokenHash, now)) throw invalidToken()
                val user = users.findById(reset.userId) ?: throw invalidToken()
                user.withPasswordHash(hash).verified(now).also { changed ->
                    users.save(changed)
                    refreshTokens.revokeAll(changed.id, now)
                    events.passwordChanged(changed)
                }
            }
        analytics.capture("password_reset_completed", distinctId = user.id.toString())
    }

    private fun invalidToken() = AppError.Invalid(ErrorCode("PASSWORD_RESET_TOKEN_INVALID"), "The link is invalid or has expired")
}

/** Admin view of accounts: look one up, grant or revoke a role. Takes effect with the next token. */
class ManageRoles(
    private val users: Users,
    private val analytics: ProductAnalytics,
) {
    suspend fun find(email: String): User =
        runCatching { Email.parse(email) }.getOrNull()?.let { users.findByEmail(it) } ?: throw userNotFound()

    suspend fun grant(
        actorId: String,
        userId: String,
        role: String,
    ): User = change(actorId, userId, parseRole(role), granted = true)

    suspend fun revoke(
        actorId: String,
        userId: String,
        role: String,
    ): User {
        val parsed = parseRole(role)
        if (parsed == Role.ADMIN && actorId == userId) {
            throw AppError.Conflict(ErrorCode("CANNOT_REVOKE_OWN_ADMIN"), "Admins cannot remove their own admin role")
        }
        return change(actorId, userId, parsed, granted = false)
    }

    private suspend fun change(
        actorId: String,
        userId: String,
        role: Role,
        granted: Boolean,
    ): User {
        val user = runCatching { UserId.parse(userId) }.getOrNull()?.let { users.findById(it) } ?: throw userNotFound()
        val changed = if (granted) user.withRole(role) else user.withoutRole(role)
        if (changed != user) {
            users.save(changed)
            analytics.capture(
                if (granted) "role_granted" else "role_revoked",
                mapOf("role" to role.claim, "user_id" to user.id.toString()),
                distinctId = actorId,
            )
        }
        return changed
    }

    private fun parseRole(raw: String): Role =
        Role.entries.firstOrNull { it.claim == raw } ?: throw AppError.NotFound(ErrorCode("ROLE_NOT_FOUND"), "No such role")

    private fun userNotFound() = AppError.NotFound(ErrorCode("USER_NOT_FOUND"), "No such user")
}
