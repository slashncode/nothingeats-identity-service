package io.github.slashncode.nothingeats.identity.domain

import java.time.Instant
import kotlin.uuid.Uuid

/** A one-time link sent by mail. Only the hash of the token is ever stored. */
data class EmailVerification(
    val tokenHash: String,
    val userId: UserId,
    val expiresAt: Instant,
    val usedAt: Instant?,
) {
    fun isUsable(now: Instant) = usedAt == null && now.isBefore(expiresAt)
}

/** A one-time link to choose a new password; like [EmailVerification], only the hash is stored. */
data class PasswordReset(
    val tokenHash: String,
    val userId: UserId,
    val expiresAt: Instant,
    val usedAt: Instant?,
) {
    fun isUsable(now: Instant) = usedAt == null && now.isBefore(expiresAt)
}

/**
 * An opaque refresh token, stored hashed. Every refresh replaces it with a new token of the same
 * [familyId]. A token that is presented again after it was used means it was stolen: the whole
 * family is revoked, logging out both the thief and the user.
 */
data class RefreshToken(
    val id: Uuid,
    val tokenHash: String,
    val familyId: Uuid,
    val userId: UserId,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val revokedAt: Instant?,
) {
    val wasAlreadyUsed: Boolean get() = usedAt != null || revokedAt != null

    fun isExpired(now: Instant) = !now.isBefore(expiresAt)
}
