package io.github.slashncode.nothingeats.identity.app

import io.github.slashncode.nothingeats.identity.adapters.cron.EraseAnalyticsTask
import io.github.slashncode.nothingeats.identity.adapters.cron.RotateSigningKeysTask
import io.github.slashncode.nothingeats.identity.adapters.crypto.Argon2PasswordHasher
import io.github.slashncode.nothingeats.identity.adapters.crypto.JwtAccessTokens
import io.github.slashncode.nothingeats.identity.adapters.crypto.KeyStoreJwkProvider
import io.github.slashncode.nothingeats.identity.adapters.crypto.RsaKeyStore
import io.github.slashncode.nothingeats.identity.adapters.crypto.SecureRandomSecrets
import io.github.slashncode.nothingeats.identity.adapters.http.IdentityRoutes
import io.github.slashncode.nothingeats.identity.adapters.messaging.OutboxUserEvents
import io.github.slashncode.nothingeats.identity.adapters.persistence.ExposedEmailVerifications
import io.github.slashncode.nothingeats.identity.adapters.persistence.ExposedErasures
import io.github.slashncode.nothingeats.identity.adapters.persistence.ExposedMagicLinks
import io.github.slashncode.nothingeats.identity.adapters.persistence.ExposedPasswordResets
import io.github.slashncode.nothingeats.identity.adapters.persistence.ExposedRefreshTokens
import io.github.slashncode.nothingeats.identity.adapters.persistence.ExposedTransactions
import io.github.slashncode.nothingeats.identity.adapters.persistence.ExposedUsers
import io.github.slashncode.nothingeats.identity.adapters.throttle.ValkeyThrottle
import io.github.slashncode.nothingeats.identity.application.AccessTokens
import io.github.slashncode.nothingeats.identity.application.AdminBootstrap
import io.github.slashncode.nothingeats.identity.application.DeleteAccount
import io.github.slashncode.nothingeats.identity.application.EmailVerifications
import io.github.slashncode.nothingeats.identity.application.EraseAnalyticsData
import io.github.slashncode.nothingeats.identity.application.Erasures
import io.github.slashncode.nothingeats.identity.application.ExportAccount
import io.github.slashncode.nothingeats.identity.application.GetProfile
import io.github.slashncode.nothingeats.identity.application.GetPublicKeys
import io.github.slashncode.nothingeats.identity.application.Login
import io.github.slashncode.nothingeats.identity.application.LoginWithMagicLink
import io.github.slashncode.nothingeats.identity.application.Logout
import io.github.slashncode.nothingeats.identity.application.MagicLinks
import io.github.slashncode.nothingeats.identity.application.ManageRoles
import io.github.slashncode.nothingeats.identity.application.PasswordHasher
import io.github.slashncode.nothingeats.identity.application.PasswordResets
import io.github.slashncode.nothingeats.identity.application.RefreshSession
import io.github.slashncode.nothingeats.identity.application.RefreshTokens
import io.github.slashncode.nothingeats.identity.application.RegisterUser
import io.github.slashncode.nothingeats.identity.application.RequestMagicLink
import io.github.slashncode.nothingeats.identity.application.RequestPasswordReset
import io.github.slashncode.nothingeats.identity.application.ResetPassword
import io.github.slashncode.nothingeats.identity.application.Secrets
import io.github.slashncode.nothingeats.identity.application.SessionIssuer
import io.github.slashncode.nothingeats.identity.application.SetAnalyticsConsent
import io.github.slashncode.nothingeats.identity.application.SigningKeys
import io.github.slashncode.nothingeats.identity.application.Throttle
import io.github.slashncode.nothingeats.identity.application.Transactions
import io.github.slashncode.nothingeats.identity.application.UserEvents
import io.github.slashncode.nothingeats.identity.application.Users
import io.github.slashncode.nothingeats.identity.application.VerifyEmail
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleManager
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.core.run.CronTask
import io.github.slashncode.nothingeats.platform.core.run.ServiceRunner
import io.github.slashncode.nothingeats.platform.http.ExceptionReporter
import io.github.slashncode.nothingeats.platform.http.HttpExtension
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.HttpServer
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth
import io.github.slashncode.nothingeats.platform.http.auth.JwtConfig
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaMigrations
import io.github.slashncode.nothingeats.platform.kafka.Outbox
import io.github.slashncode.nothingeats.platform.kafka.OutboxRelay
import io.github.slashncode.nothingeats.platform.kafka.PurgeMessagingTask
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.posthog.PostHogAnalytics
import io.github.slashncode.nothingeats.platform.posthog.PostHogExceptionReporter
import io.github.slashncode.nothingeats.platform.posthog.PostHogHttpExtension
import io.github.slashncode.nothingeats.platform.valkey.RateLimiter
import io.github.slashncode.nothingeats.platform.valkey.Valkey
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import me.tatarka.inject.annotations.Scope
import java.time.Clock

@Scope
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ServiceScope

/**
 * The composition root: the only place that wires ports to adapters.
 * Lifecycle components, health checks, routes and cron tasks are collected with `@IntoSet`.
 */
@ServiceScope
@Component
abstract class ServiceComponent(
    private val config: ServiceConfig,
) {
    abstract val runner: ServiceRunner
    abstract val lifecycle: LifecycleManager
    abstract val migrations: FlywayMigrations
    abstract val signingKeys: SigningKeys
    abstract val cronTasks: Set<CronTask>

    // Platform

    @ServiceScope
    @Provides
    fun readiness(): Readiness = Readiness()

    @Provides
    fun clock(): Clock = Clock.systemUTC()

    @ServiceScope
    @Provides
    fun flywayMigrations(): FlywayMigrations = FlywayMigrations(config.database, listOf(KafkaMigrations, MigrationSet.SERVICE))

    @ServiceScope
    @Provides
    fun postgres(migrations: FlywayMigrations): PostgresDatabase = PostgresDatabase(config.database, migrations)

    @ServiceScope
    @Provides
    fun httpServer(
        readiness: Readiness,
        checks: Set<HealthCheck>,
        routes: Set<HttpRoutes>,
        auth: JwtAuth,
        extensions: Set<HttpExtension>,
        exceptionReporter: ExceptionReporter,
    ): HttpServer = HttpServer(config.http, readiness, HealthChecks(checks), routes, auth, extensions, exceptionReporter)

    /** Verifies this service's own tokens straight from the key store. */
    @ServiceScope
    @Provides
    fun jwtAuth(keys: RsaKeyStore): JwtAuth =
        JwtAuth(JwtConfig(issuer = config.tokens.access.issuer, audience = config.tokens.access.audience), KeyStoreJwkProvider(keys))

    @ServiceScope
    @Provides
    fun lifecycleManager(
        components: Set<LifecycleComponent>,
        readiness: Readiness,
    ): LifecycleManager = LifecycleManager(components, readiness, config.lifecycle.drainDelay)

    @Provides
    fun serviceRunner(
        lifecycle: LifecycleManager,
        migrations: FlywayMigrations,
        cronTasks: Set<CronTask>,
    ): ServiceRunner = ServiceRunner(lifecycle, migrations, cronTasks)

    @IntoSet
    @Provides
    fun postgresLifecycle(postgres: PostgresDatabase): LifecycleComponent = postgres

    @IntoSet
    @Provides
    fun postgresHealth(postgres: PostgresDatabase): HealthCheck = postgres

    @IntoSet
    @Provides
    fun httpLifecycle(server: HttpServer): LifecycleComponent = server

    @ServiceScope
    @Provides
    fun postHog(): PostHogAnalytics = PostHogAnalytics(config.posthog)

    @Provides
    fun analytics(postHog: PostHogAnalytics): ProductAnalytics = postHog

    @Provides
    fun exceptionReporter(analytics: ProductAnalytics): ExceptionReporter = PostHogExceptionReporter(analytics)

    @IntoSet
    @Provides
    fun postHogLifecycle(postHog: PostHogAnalytics): LifecycleComponent = postHog

    @IntoSet
    @Provides
    fun postHogHttp(): HttpExtension = PostHogHttpExtension()

    // Messaging

    @ServiceScope
    @Provides
    fun outbox(clock: Clock): Outbox = Outbox(source = "/nothingeats/identity", clock = clock)

    @ServiceScope
    @Provides
    fun outboxRelay(postgres: PostgresDatabase): OutboxRelay =
        OutboxRelay(postgres, KafkaClients.producer(config.kafka, "identity-service"))

    @IntoSet
    @Provides
    fun outboxRelayLifecycle(relay: OutboxRelay): LifecycleComponent = relay

    @IntoSet
    @Provides
    fun purgeMessaging(postgres: PostgresDatabase): CronTask = PurgeMessagingTask(postgres)

    // Rate limits. Valkey is no readiness check: the limiter fails open, logins keep working.

    @ServiceScope
    @Provides
    fun valkey(): Valkey = Valkey(config.valkey)

    @IntoSet
    @Provides
    fun valkeyLifecycle(valkey: Valkey): LifecycleComponent = valkey

    @Provides
    fun throttle(
        valkey: Valkey,
        clock: Clock,
    ): Throttle = ValkeyThrottle(RateLimiter(valkey, prefix = "identity", clock = clock))

    // Identity

    @ServiceScope
    @Provides
    fun keyStore(
        postgres: PostgresDatabase,
        clock: Clock,
    ): RsaKeyStore = RsaKeyStore(postgres, config.keys, clock)

    @Provides
    fun signingKeysPort(keys: RsaKeyStore): SigningKeys = keys

    @IntoSet
    @Provides
    fun keyStoreLifecycle(keys: RsaKeyStore): LifecycleComponent = keys

    @IntoSet
    @Provides
    fun rotateSigningKeys(keys: SigningKeys): CronTask = RotateSigningKeysTask(keys)

    @Provides
    fun users(postgres: PostgresDatabase): Users = ExposedUsers(postgres)

    @Provides
    fun emailVerifications(postgres: PostgresDatabase): EmailVerifications = ExposedEmailVerifications(postgres)

    @Provides
    fun refreshTokens(postgres: PostgresDatabase): RefreshTokens = ExposedRefreshTokens(postgres)

    @Provides
    fun passwordResets(postgres: PostgresDatabase): PasswordResets = ExposedPasswordResets(postgres)

    @Provides
    fun adminBootstrap(): AdminBootstrap = AdminBootstrap(config.admin.emails)

    @Provides
    fun magicLinks(postgres: PostgresDatabase): MagicLinks = ExposedMagicLinks(postgres)

    @Provides
    fun erasures(postgres: PostgresDatabase): Erasures = ExposedErasures(postgres)

    @IntoSet
    @Provides
    fun eraseAnalytics(
        erasures: Erasures,
        analytics: ProductAnalytics,
        clock: Clock,
    ): CronTask = EraseAnalyticsTask(EraseAnalyticsData(erasures, analytics, clock))

    @Provides
    fun transactions(postgres: PostgresDatabase): Transactions = ExposedTransactions(postgres)

    @ServiceScope
    @Provides
    fun passwordHasher(): PasswordHasher = Argon2PasswordHasher()

    @Provides
    fun secrets(): Secrets = SecureRandomSecrets()

    @Provides
    fun accessTokens(
        keys: RsaKeyStore,
        clock: Clock,
    ): AccessTokens = JwtAccessTokens(keys, config.tokens.access, clock)

    @Provides
    fun userEvents(
        outbox: Outbox,
        postgres: PostgresDatabase,
    ): UserEvents = OutboxUserEvents(outbox, postgres)

    @Provides
    fun sessionIssuer(
        refreshTokens: RefreshTokens,
        accessTokens: AccessTokens,
        secrets: Secrets,
        clock: Clock,
    ): SessionIssuer = SessionIssuer(refreshTokens, accessTokens, secrets, clock, config.tokens.refreshTtl)

    @Provides
    fun registerUser(
        users: Users,
        verifications: EmailVerifications,
        hasher: PasswordHasher,
        events: UserEvents,
        transactions: Transactions,
        secrets: Secrets,
        clock: Clock,
        analytics: ProductAnalytics,
        throttle: Throttle,
    ): RegisterUser =
        RegisterUser(users, verifications, hasher, events, transactions, secrets, clock, analytics, throttle, config.tokens.verificationTtl)

    @Provides
    fun verifyEmail(
        users: Users,
        verifications: EmailVerifications,
        events: UserEvents,
        transactions: Transactions,
        clock: Clock,
        analytics: ProductAnalytics,
        admins: AdminBootstrap,
    ): VerifyEmail = VerifyEmail(users, verifications, events, transactions, clock, analytics, admins)

    @Provides
    fun login(
        users: Users,
        hasher: PasswordHasher,
        sessions: SessionIssuer,
        transactions: Transactions,
        analytics: ProductAnalytics,
        throttle: Throttle,
        admins: AdminBootstrap,
    ): Login = Login(users, hasher, sessions, transactions, analytics, throttle, admins)

    @Provides
    fun requestPasswordReset(
        users: Users,
        resets: PasswordResets,
        events: UserEvents,
        transactions: Transactions,
        secrets: Secrets,
        clock: Clock,
        analytics: ProductAnalytics,
        throttle: Throttle,
    ): RequestPasswordReset = RequestPasswordReset(users, resets, events, transactions, secrets, clock, analytics, throttle)

    @Provides
    fun resetPassword(
        users: Users,
        resets: PasswordResets,
        refreshTokens: RefreshTokens,
        hasher: PasswordHasher,
        events: UserEvents,
        transactions: Transactions,
        clock: Clock,
        analytics: ProductAnalytics,
    ): ResetPassword = ResetPassword(users, resets, refreshTokens, hasher, events, transactions, clock, analytics)

    @Provides
    fun refreshSession(
        users: Users,
        refreshTokens: RefreshTokens,
        sessions: SessionIssuer,
        transactions: Transactions,
        clock: Clock,
        analytics: ProductAnalytics,
    ): RefreshSession = RefreshSession(users, refreshTokens, sessions, transactions, clock, analytics)

    @Provides
    fun logout(
        refreshTokens: RefreshTokens,
        clock: Clock,
    ): Logout = Logout(refreshTokens, clock)

    @IntoSet
    @Provides
    fun identityRoutes(
        registerUser: RegisterUser,
        verifyEmail: VerifyEmail,
        login: Login,
        refreshSession: RefreshSession,
        logout: Logout,
        users: Users,
        keys: SigningKeys,
        clock: Clock,
        requestPasswordReset: RequestPasswordReset,
        resetPassword: ResetPassword,
        analytics: ProductAnalytics,
        magicLinks: MagicLinks,
        events: UserEvents,
        transactions: Transactions,
        secrets: Secrets,
        throttle: Throttle,
        sessions: SessionIssuer,
        admins: AdminBootstrap,
        refreshTokens: RefreshTokens,
        hasher: PasswordHasher,
        erasures: Erasures,
    ): HttpRoutes =
        IdentityRoutes(
            registerUser,
            verifyEmail,
            login,
            refreshSession,
            logout,
            GetProfile(users),
            requestPasswordReset,
            resetPassword,
            ManageRoles(users, analytics),
            RequestMagicLink(users, magicLinks, events, transactions, secrets, clock, throttle),
            LoginWithMagicLink(users, magicLinks, sessions, transactions, clock, analytics, admins),
            ExportAccount(users, refreshTokens, clock),
            DeleteAccount(users, hasher, erasures, events, transactions, clock, throttle),
            SetAnalyticsConsent(users, clock),
            GetPublicKeys(keys),
            config.cookie,
            clock,
        )
}
