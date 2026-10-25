package io.github.slashncode.nothingeats.identity.adapters.throttle

import io.github.slashncode.nothingeats.identity.application.Throttle
import io.github.slashncode.nothingeats.identity.application.ThrottleRule
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.valkey.RateDecision
import io.github.slashncode.nothingeats.platform.valkey.RateLimit
import io.github.slashncode.nothingeats.platform.valkey.RateLimiter

/** The throttle rules as fixed-window counters in Valkey (failing open if Valkey is down). */
class ValkeyThrottle(
    private val limiter: RateLimiter,
) : Throttle {
    override suspend fun attempt(
        rule: ThrottleRule,
        subject: String,
    ) = enforce(limiter.attempt(rule.toLimit(), subject))

    override suspend fun ensureAllowed(
        rule: ThrottleRule,
        subject: String,
    ) = enforce(limiter.peek(rule.toLimit(), subject))

    private fun enforce(decision: RateDecision) {
        if (decision is RateDecision.Limited) {
            throw AppError.RateLimited(ErrorCode("TOO_MANY_ATTEMPTS"), "Too many attempts, please wait", decision.retryAfter)
        }
    }

    private fun ThrottleRule.toLimit() = RateLimit(name, limit, window)
}
