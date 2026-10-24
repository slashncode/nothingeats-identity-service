package io.github.slashncode.nothingeats.example.app

import io.github.slashncode.nothingeats.example.adapters.http.ItemRoutes
import io.github.slashncode.nothingeats.example.adapters.persistence.ExposedItemRepository
import io.github.slashncode.nothingeats.example.application.CreateItem
import io.github.slashncode.nothingeats.example.application.GetItem
import io.github.slashncode.nothingeats.example.application.ItemRepository
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleManager
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.core.run.ServiceRunner
import io.github.slashncode.nothingeats.platform.http.ExceptionReporter
import io.github.slashncode.nothingeats.platform.http.HttpExtension
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.HttpServer
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.posthog.PostHogAnalytics
import io.github.slashncode.nothingeats.platform.posthog.PostHogExceptionReporter
import io.github.slashncode.nothingeats.platform.posthog.PostHogHttpExtension
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
 * Lifecycle components, health checks and routes are collected with `@IntoSet`, never listed by hand.
 * [jwtAuth] is passed in so tests can use test signing keys.
 */
@ServiceScope
@Component
abstract class ServiceComponent(
    private val config: ServiceConfig,
    @get:Provides val jwtAuth: JwtAuth,
) {
    abstract val runner: ServiceRunner
    abstract val lifecycle: LifecycleManager
    abstract val migrations: FlywayMigrations

    // Platform

    @ServiceScope
    @Provides
    fun readiness(): Readiness = Readiness()

    @Provides
    fun clock(): Clock = Clock.systemUTC()

    @ServiceScope
    @Provides
    fun flywayMigrations(): FlywayMigrations = FlywayMigrations(config.database)

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
    ): ServiceRunner = ServiceRunner(lifecycle, migrations)

    @IntoSet
    @Provides
    fun postgresLifecycle(postgres: PostgresDatabase): LifecycleComponent = postgres

    @IntoSet
    @Provides
    fun postgresHealth(postgres: PostgresDatabase): HealthCheck = postgres

    @IntoSet
    @Provides
    fun httpLifecycle(server: HttpServer): LifecycleComponent = server

    // Items

    @Provides
    fun itemRepository(postgres: PostgresDatabase): ItemRepository = ExposedItemRepository(postgres)

    @Provides
    fun createItem(
        items: ItemRepository,
        clock: Clock,
        analytics: ProductAnalytics,
    ): CreateItem = CreateItem(items, clock, analytics)

    @Provides
    fun getItem(items: ItemRepository): GetItem = GetItem(items)

    @IntoSet
    @Provides
    fun itemRoutes(
        createItem: CreateItem,
        getItem: GetItem,
    ): HttpRoutes = ItemRoutes(createItem, getItem)
}
