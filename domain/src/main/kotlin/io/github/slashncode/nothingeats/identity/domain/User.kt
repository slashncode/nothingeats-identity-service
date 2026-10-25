package io.github.slashncode.nothingeats.identity.domain

import java.time.Instant
import kotlin.uuid.Uuid

@JvmInline
value class UserId(
    val value: Uuid,
) {
    override fun toString() = value.toString()

    companion object {
        fun random() = UserId(Uuid.random())

        fun parse(raw: String) = UserId(Uuid.parse(raw))
    }
}

/** Trimmed and lower-cased, so one address is one account however it is typed. */
@JvmInline
value class Email private constructor(
    val value: String,
) {
    override fun toString() = value

    companion object {
        const val MAX_LENGTH = 254
        private val SHAPE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        fun parse(raw: String): Email {
            val normalized = raw.trim().lowercase()
            if (normalized.length > MAX_LENGTH || !SHAPE.matches(normalized)) throw InvalidEmail()
            return Email(normalized)
        }
    }
}

enum class Locale(
    val tag: String,
) {
    DE("de"),
    EN("en"),
    ;

    companion object {
        /** Accepts tags like `en-GB`; anything unknown falls back to German. */
        fun parse(tag: String?): Locale = entries.firstOrNull { it.tag == tag?.trim()?.lowercase()?.take(2) } ?: DE
    }
}

enum class Role(
    val claim: String,
) {
    CUSTOMER("customer"),
    ADMIN("admin"),
}

enum class UserStatus { PENDING_VERIFICATION, ACTIVE }

data class User(
    val id: UserId,
    val email: Email,
    val passwordHash: String,
    val locale: Locale,
    val roles: Set<Role>,
    val status: UserStatus,
    val createdAt: Instant,
    val verifiedAt: Instant?,
    /** Product analytics consent as decided in the web app; null until decided. */
    val analyticsConsent: Boolean? = null,
    val consentUpdatedAt: Instant? = null,
) {
    val isVerified: Boolean get() = status == UserStatus.ACTIVE

    fun verified(now: Instant): User = if (isVerified) this else copy(status = UserStatus.ACTIVE, verifiedAt = now)

    fun withPasswordHash(hash: String) = copy(passwordHash = hash)

    fun withRole(role: Role) = copy(roles = roles + role)

    fun withoutRole(role: Role) = copy(roles = roles - role)

    fun withAnalyticsConsent(
        granted: Boolean,
        now: Instant,
    ) = if (analyticsConsent == granted) this else copy(analyticsConsent = granted, consentUpdatedAt = now)

    companion object {
        fun register(
            email: Email,
            passwordHash: String,
            locale: Locale,
            now: Instant,
        ) = User(
            id = UserId.random(),
            email = email,
            passwordHash = passwordHash,
            locale = locale,
            roles = setOf(Role.CUSTOMER),
            status = UserStatus.PENDING_VERIFICATION,
            createdAt = now,
            verifiedAt = null,
        )
    }
}

/**
 * Length is what makes passwords strong (NIST SP 800-63B): no composition rules, a generous
 * maximum so passphrases fit, and no silent truncation.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 10
    const val MAX_LENGTH = 128

    fun check(raw: String) {
        if (raw.length < MIN_LENGTH) throw WeakPassword("must be at least $MIN_LENGTH characters")
        if (raw.length > MAX_LENGTH) throw WeakPassword("must be at most $MAX_LENGTH characters")
        if (raw.isBlank()) throw WeakPassword("must not be blank")
    }
}

class InvalidEmail : Exception("is not a valid email address")

class WeakPassword(
    reason: String,
) : Exception(reason)

class EmailAlreadyRegistered(
    val email: Email,
    cause: Throwable? = null,
) : Exception("An account for this email already exists", cause)
