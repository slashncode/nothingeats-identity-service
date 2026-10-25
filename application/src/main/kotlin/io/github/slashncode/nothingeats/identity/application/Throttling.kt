package io.github.slashncode.nothingeats.identity.application

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** At most [limit] attempts per [window] and subject (a client address or an email address). */
data class ThrottleRule(
    val name: String,
    val limit: Int,
    val window: Duration,
)

/**
 * Limits for everything an attacker would repeat: guessing passwords, creating accounts, sending
 * reset mails. Per-account limits apply to addresses without an account too, so a 429 reveals
 * nothing about which addresses exist.
 */
object ThrottleRules {
    val LOGIN_PER_CLIENT = ThrottleRule("login-client", limit = 30, window = 15.minutes)

    /**
     * Counts failures only, so successful logins never use it up. Once reached, the account is
     * locked for the rest of the window even for the right password: checking it first would turn
     * "200 or 429" into a guessing oracle.
     */
    val LOGIN_FAILURES_PER_ACCOUNT = ThrottleRule("login-account", limit = 10, window = 15.minutes)

    val REGISTRATION_PER_CLIENT = ThrottleRule("registration-client", limit = 10, window = 1.hours)

    val PASSWORD_RESET_PER_CLIENT = ThrottleRule("password-reset-client", limit = 10, window = 1.hours)

    /** Caps the mails one address can receive, whoever asks for them. */
    val PASSWORD_RESET_PER_ACCOUNT = ThrottleRule("password-reset-account", limit = 3, window = 1.hours)

    val MAGIC_LINK_PER_CLIENT = ThrottleRule("magic-link-client", limit = 10, window = 1.hours)

    val MAGIC_LINK_PER_ACCOUNT = ThrottleRule("magic-link-account", limit = 5, window = 1.hours)

    /** Account deletion asks for the password again; guessing it is as limited as logging in. */
    val DELETION_FAILURES_PER_ACCOUNT = ThrottleRule("deletion-account", limit = 5, window = 15.minutes)
}

interface Throttle {
    /** Counts the attempt; throws `AppError.RateLimited` (429) beyond the limit. */
    suspend fun attempt(
        rule: ThrottleRule,
        subject: String,
    )

    /** Throws `AppError.RateLimited` if the limit is reached, without counting. */
    suspend fun ensureAllowed(
        rule: ThrottleRule,
        subject: String,
    )
}
