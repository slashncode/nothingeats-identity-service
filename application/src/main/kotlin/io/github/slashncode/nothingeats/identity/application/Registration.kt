package io.github.slashncode.nothingeats.identity.application

import io.github.slashncode.nothingeats.identity.domain.Email
import io.github.slashncode.nothingeats.identity.domain.EmailAlreadyRegistered
import io.github.slashncode.nothingeats.identity.domain.EmailVerification
import io.github.slashncode.nothingeats.identity.domain.InvalidEmail
import io.github.slashncode.nothingeats.identity.domain.Locale
import io.github.slashncode.nothingeats.identity.domain.PasswordPolicy
import io.github.slashncode.nothingeats.identity.domain.User
import io.github.slashncode.nothingeats.identity.domain.WeakPassword
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * Registers an account and sends a verification mail (via an event). The response never reveals
 * whether the address already has an account: the owner of an existing account gets a mail instead,
 * and the password is hashed either way so the timing is the same.
 */
class RegisterUser(
    private val users: Users,
    private val verifications: EmailVerifications,
    private val hasher: PasswordHasher,
    private val events: UserEvents,
    private val transactions: Transactions,
    private val secrets: Secrets,
    private val clock: Clock,
    private val analytics: ProductAnalytics,
    private val verificationTtl: Duration = 24.hours,
) {
    suspend operator fun invoke(
        email: String,
        password: String,
        locale: String?,
    ) {
        val address = parseEmail(email)
        checkPassword(password)
        val hash = hasher.hash(password)
        val existing = users.findByEmail(address)
        if (existing != null) {
            transactions.run { events.registrationAttempted(existing) }
            return
        }

        val now = clock.instant()
        val user = User.register(address, hash, Locale.parse(locale), now)
        val token = secrets.newToken()
        try {
            transactions.run {
                users.add(user)
                verifications.add(EmailVerification(TokenHashing.hash(token), user.id, now.plus(verificationTtl.toJavaDuration()), null))
                events.registered(user, token)
            }
        } catch (e: EmailAlreadyRegistered) {
            // Lost a race against a parallel registration of the same address.
            users.findByEmail(address)?.let { winner -> transactions.run { events.registrationAttempted(winner) } }
            return
        }
        analytics.capture("user_signed_up", mapOf("locale" to user.locale.tag), distinctId = user.id.toString())
    }
}

/** Confirms the address with the token from the mail; the account becomes active. */
class VerifyEmail(
    private val users: Users,
    private val verifications: EmailVerifications,
    private val events: UserEvents,
    private val transactions: Transactions,
    private val clock: Clock,
    private val analytics: ProductAnalytics,
) {
    suspend operator fun invoke(token: String) {
        val now = clock.instant()
        val user =
            transactions.run {
                val verification = verifications.find(TokenHashing.hash(token))?.takeIf { it.isUsable(now) } ?: throw invalidToken()
                verifications.markUsed(verification.tokenHash, now)
                val user = users.findById(verification.userId) ?: throw invalidToken()
                user.verified(now).also { verified ->
                    users.save(verified)
                    events.verified(verified)
                }
            }
        analytics.capture("email_verified", distinctId = user.id.toString())
    }

    private fun invalidToken() = AppError.Invalid(ErrorCode("VERIFICATION_TOKEN_INVALID"), "The link is invalid or has expired")
}

internal fun parseEmail(raw: String): Email =
    try {
        Email.parse(raw)
    } catch (e: InvalidEmail) {
        throw AppError.Invalid(
            ErrorCode("REGISTRATION_INVALID"),
            "The registration is invalid",
            listOf(
                Violation(
                    "email",
                    e.message ?: "invalid",
                ),
            ),
        )
    }

internal fun checkPassword(raw: String) {
    try {
        PasswordPolicy.check(raw)
    } catch (e: WeakPassword) {
        throw AppError.Invalid(
            ErrorCode("REGISTRATION_INVALID"),
            "The registration is invalid",
            listOf(
                Violation(
                    "password",
                    e.message ?: "invalid",
                ),
            ),
        )
    }
}
