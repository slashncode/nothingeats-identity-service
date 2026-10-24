package io.github.slashncode.nothingeats.identity.app

import io.github.slashncode.nothingeats.identity.adapters.crypto.AccessTokenConfig
import io.github.slashncode.nothingeats.identity.adapters.crypto.KeysConfig
import io.github.slashncode.nothingeats.identity.adapters.http.RefreshCookieConfig
import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.persistence.PostgresConfig
import io.github.slashncode.nothingeats.platform.posthog.PostHogSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the service reads from its environment, validated at startup. Required:
 * `DATABASE__URL`, `DATABASE__USER`, `DATABASE__PASSWORD`, `KEYS__ENCRYPTION_KEY`.
 */
data class ServiceConfig(
    val http: HttpServerConfig = HttpServerConfig(),
    val database: PostgresConfig,
    val kafka: KafkaConfig = KafkaConfig(),
    val keys: KeysConfig,
    val tokens: TokenConfig = TokenConfig(),
    val cookie: RefreshCookieConfig = RefreshCookieConfig(),
    val posthog: PostHogSettings = PostHogSettings(),
    val lifecycle: LifecycleConfig = LifecycleConfig(),
)

data class TokenConfig(
    val access: AccessTokenConfig = AccessTokenConfig(),
    val refreshTtl: Duration = 30.days,
    val verificationTtl: Duration = 24.hours,
)

data class LifecycleConfig(
    /** Time between readiness going down and the shutdown, so load balancers stop sending traffic first. */
    val drainDelay: Duration = 5.seconds,
)
