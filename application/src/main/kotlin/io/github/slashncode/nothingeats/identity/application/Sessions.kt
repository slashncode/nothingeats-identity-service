package io.github.slashncode.nothingeats.identity.application

import io.github.slashncode.nothingeats.identity.domain.Email
import io.github.slashncode.nothingeats.identity.domain.RefreshToken
import io.github.slashncode.nothingeats.identity.domain.User
import io.github.slashncode.nothingeats.identity.domain.UserId
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid

data class Session(
    val user: User,
    val accessToken: AccessToken,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)

/** Issues an access token and a new refresh token in the given (or a new) token family. */
class SessionIssuer(
    private val refreshTokens: RefreshTokens,
    private val accessTokens: AccessTokens,
    private val secrets: Secrets,
    private val clock: Clock,
    private val refreshTtl: Duration = 30.days,
) {
    suspend fun issue(
        user: User,
        familyId: Uuid = Uuid.random(),
    ): Session {
        val token = secrets.newToken()
        val expiresAt = clock.instant().plus(refreshTtl.toJavaDuration())
        refreshTokens.add(
            RefreshToken(Uuid.random(), TokenHashing.hash(token), familyId, user.id, expiresAt, usedAt = null, revokedAt = null),
        )
        return Session(user, accessTokens.issue(user), token, expiresAt)
    }
}

/**
 * Email + password login. Unknown address and wrong password get the same answer and the same
 * timing (a dummy hash is checked). Outdated hashes are replaced while the password is at hand.
 */
class Login(
    private val users: Users,
    private val hasher: PasswordHasher,
    private val sessions: SessionIssuer,
    private val transactions: Transactions,
    private val analytics: ProductAnalytics,
) {
    suspend operator fun invoke(
        email: String,
        password: String,
    ): Session {
        val user = runCatching { Email.parse(email) }.getOrNull()?.let { users.findByEmail(it) }
        val check = hasher.verify(password, user?.passwordHash ?: hasher.dummyHash)
        if (user == null || !check.matches) {
            throw AppError.Unauthenticated(ErrorCode("INVALID_CREDENTIALS"), "Email or password is wrong")
        }
        if (!user.isVerified) {
            throw AppError.Forbidden(ErrorCode("EMAIL_NOT_VERIFIED"), "Please confirm your email address first")
        }
        val current = if (check.needsRehash) user.withPasswordHash(hasher.hash(password)) else user
        val session =
            transactions.run {
                if (current !== user) users.save(current)
                sessions.issue(current)
            }
        analytics.capture("user_logged_in", distinctId = user.id.toString())
        return session
    }
}

/**
 * Exchanges a refresh token for a new session. Presenting a token twice means it leaked:
 * the whole family is revoked and both holders have to log in again.
 */
class RefreshSession(
    private val users: Users,
    private val refreshTokens: RefreshTokens,
    private val sessions: SessionIssuer,
    private val transactions: Transactions,
    private val clock: Clock,
    private val analytics: ProductAnalytics,
) {
    private val log = LoggerFactory.getLogger(RefreshSession::class.java)

    suspend operator fun invoke(refreshToken: String?): Session {
        val now = clock.instant()
        val token = refreshToken?.let { refreshTokens.find(TokenHashing.hash(it)) } ?: throw unauthenticated("REFRESH_TOKEN_INVALID")
        if (token.wasAlreadyUsed || !transactions.run { refreshTokens.markUsed(token.id, now) }) {
            transactions.run { refreshTokens.revokeFamily(token.familyId, now) }
            log.warn("Refresh token reuse for user {}, revoked token family {}", token.userId, token.familyId)
            analytics.capture("refresh_token_reused", distinctId = token.userId.toString())
            throw unauthenticated("REFRESH_TOKEN_REUSED")
        }
        if (token.isExpired(now)) throw unauthenticated("REFRESH_TOKEN_EXPIRED")
        val user = users.findById(token.userId) ?: throw unauthenticated("REFRESH_TOKEN_INVALID")
        return transactions.run { sessions.issue(user, token.familyId) }
    }

    private fun unauthenticated(code: String) = AppError.Unauthenticated(ErrorCode(code), "Please log in again")
}

class Logout(
    private val refreshTokens: RefreshTokens,
    private val clock: Clock,
) {
    suspend operator fun invoke(refreshToken: String?) {
        val token = refreshToken?.let { refreshTokens.find(TokenHashing.hash(it)) } ?: return
        refreshTokens.revokeFamily(token.familyId, clock.instant())
    }
}

class GetProfile(
    private val users: Users,
) {
    suspend operator fun invoke(userId: String): User =
        runCatching { UserId.parse(userId) }.getOrNull()?.let { users.findById(it) }
            ?: throw AppError.NotFound(ErrorCode("USER_NOT_FOUND"), "No such user")
}

class GetPublicKeys(
    private val keys: SigningKeys,
) {
    suspend operator fun invoke(): List<PublicKey> = keys.publicKeys()
}
