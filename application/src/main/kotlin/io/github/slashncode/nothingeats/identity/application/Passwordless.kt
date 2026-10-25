package io.github.slashncode.nothingeats.identity.application

import io.github.slashncode.nothingeats.identity.domain.Email
import io.github.slashncode.nothingeats.identity.domain.MagicLink
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * "Send me a login link": mails a 15-minute single-use link if the address has an account. Same
 * answer and same limits for every address, like the password reset.
 */
class RequestMagicLink(
    private val users: Users,
    private val links: MagicLinks,
    private val events: UserEvents,
    private val transactions: Transactions,
    private val secrets: Secrets,
    private val clock: Clock,
    private val throttle: Throttle,
    private val ttl: Duration = 15.minutes,
) {
    suspend operator fun invoke(
        email: String,
        client: String,
    ) {
        throttle.attempt(ThrottleRules.MAGIC_LINK_PER_CLIENT, client)
        val address =
            runCatching { Email.parse(email) }.getOrNull()
                ?: throw AppError.Invalid(
                    ErrorCode("MAGIC_LINK_INVALID"),
                    "The email address is invalid",
                    listOf(Violation("email", "is not a valid email address")),
                )
        throttle.attempt(ThrottleRules.MAGIC_LINK_PER_ACCOUNT, address.value)
        val user = users.findByEmail(address) ?: return
        val token = secrets.newToken()
        transactions.run {
            links.add(MagicLink(TokenHashing.hash(token), user.id, clock.instant().plus(ttl.toJavaDuration()), usedAt = null))
            events.magicLinkRequested(user, token)
        }
    }
}

/** Exchanges the link for a session. Opening it proves the address, so it also verifies the account. */
class LoginWithMagicLink(
    private val users: Users,
    private val links: MagicLinks,
    private val sessions: SessionIssuer,
    private val transactions: Transactions,
    private val clock: Clock,
    private val analytics: ProductAnalytics,
    private val admins: AdminBootstrap = AdminBootstrap(),
) {
    suspend operator fun invoke(token: String): Session {
        val now = clock.instant()
        val session =
            transactions.run {
                val link = links.find(TokenHashing.hash(token))?.takeIf { it.isUsable(now) } ?: throw invalidLink()
                if (!links.markUsed(link.tokenHash, now)) throw invalidLink()
                val user = users.findById(link.userId) ?: throw invalidLink()
                val current = admins.apply(user.verified(now))
                if (current != user) users.save(current)
                sessions.issue(current)
            }
        analytics.capture("user_logged_in", mapOf("method" to "magic_link"), distinctId = session.user.id.toString())
        return session
    }

    private fun invalidLink() =
        AppError.Unauthenticated(ErrorCode("MAGIC_LINK_INVALID"), "The link is invalid, has expired or was used already")
}
