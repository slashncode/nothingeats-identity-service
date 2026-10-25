package io.github.slashncode.nothingeats.identity.application

import io.github.slashncode.nothingeats.identity.domain.User
import io.github.slashncode.nothingeats.identity.domain.UserId
import io.github.slashncode.nothingeats.platform.core.analytics.ErasureResult
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/** Everything identity holds about an account (GDPR art. 15/20). Tokens are secrets, not data. */
data class AccountExport(
    val user: User,
    val activeSessions: Int,
    val exportedAt: Instant,
)

class ExportAccount(
    private val users: Users,
    private val refreshTokens: RefreshTokens,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: String): AccountExport {
        val user = findUser(users, userId)
        val now = clock.instant()
        return AccountExport(user, refreshTokens.countActive(user.id, now), now)
    }
}

/**
 * Deletes the account (GDPR art. 17) after the password was confirmed once more. In one
 * transaction: the user row (tokens cascade), a tombstone with only the id for the erasures that
 * happen outside this database, and `user.deleted` so every service erases what it holds.
 */
class DeleteAccount(
    private val users: Users,
    private val hasher: PasswordHasher,
    private val erasures: Erasures,
    private val events: UserEvents,
    private val transactions: Transactions,
    private val clock: Clock,
    private val throttle: Throttle,
) {
    suspend operator fun invoke(
        userId: String,
        password: String,
    ) {
        val user = findUser(users, userId)
        throttle.ensureAllowed(ThrottleRules.DELETION_FAILURES_PER_ACCOUNT, user.id.toString())
        if (!hasher.verify(password, user.passwordHash).matches) {
            throttle.attempt(ThrottleRules.DELETION_FAILURES_PER_ACCOUNT, user.id.toString())
            throw AppError.Forbidden(ErrorCode("PASSWORD_CONFIRMATION_FAILED"), "The password is wrong")
        }
        val now = clock.instant()
        transactions.run {
            users.delete(user.id)
            erasures.add(user.id, now)
            events.deleted(user.id)
        }
    }
}

class SetAnalyticsConsent(
    private val users: Users,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        userId: String,
        granted: Boolean,
    ): User {
        val user = findUser(users, userId)
        val changed = user.withAnalyticsConsent(granted, clock.instant())
        if (changed != user) users.save(changed)
        return changed
    }
}

data class ErasureRun(
    val erased: Int,
    val failed: Int,
    /** Still waiting because erasure is not configured here (e.g. no PostHog personal API key). */
    val waiting: Int,
)

/**
 * `cron erase-analytics`: erases deleted accounts at the analytics vendor. Durable: a tombstone stays
 * until the vendor confirmed, so an outage or a missing API key only delays the erasure.
 */
class EraseAnalyticsData(
    private val erasures: Erasures,
    private val analytics: ProductAnalytics,
    private val clock: Clock,
    private val batchSize: Int = 100,
) {
    private val log = LoggerFactory.getLogger(EraseAnalyticsData::class.java)

    suspend operator fun invoke(): ErasureRun {
        val pending = erasures.pendingAnalytics(batchSize)
        var erased = 0
        var failed = 0
        for (userId in pending) {
            val result =
                try {
                    analytics.erasePerson(userId.toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Analytics erasure for {} failed, retrying next run: {}", userId, e.message)
                    failed++
                    continue
                }
            if (result == ErasureResult.NOT_CONFIGURED) {
                log.warn("Analytics erasure is not configured; {} deleted accounts are waiting", pending.size - erased - failed)
                return ErasureRun(erased, failed, waiting = pending.size - erased - failed)
            }
            erasures.markAnalyticsErased(userId, clock.instant())
            erased++
        }
        return ErasureRun(erased, failed, waiting = 0)
    }
}

internal suspend fun findUser(
    users: Users,
    userId: String,
): User =
    runCatching { UserId.parse(userId) }.getOrNull()?.let { users.findById(it) }
        ?: throw AppError.NotFound(ErrorCode("USER_NOT_FOUND"), "No such user")
