package io.github.slashncode.nothingeats.example.app

import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.github.slashncode.nothingeats.platform.testing.TestTokens
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import kotlin.time.Duration

private fun freePort() = ServerSocket(0).use { it.localPort }

/** The whole service wired by the real composition root, against a real Postgres. */
class ServiceSmokeTest :
    FunSpec({
        val http = HttpClient.newHttpClient()
        val tokens = TestTokens()

        fun component(): Pair<ServiceComponent, String> {
            val port = freePort()
            val config =
                ServiceConfig(
                    http = HttpServerConfig(port = port),
                    database = PostgresTestDatabases.create(),
                    lifecycle = LifecycleConfig(drainDelay = Duration.ZERO),
                )
            return ServiceComponent::class.create(config, tokens.jwtAuth) to "http://localhost:$port"
        }

        fun get(url: String) = http.send(HttpRequest.newBuilder(URI(url)).GET().build(), BodyHandlers.ofString())

        fun post(
            url: String,
            json: String,
        ) = http.send(
            HttpRequest
                .newBuilder(URI(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${tokens.accessToken()}")
                .POST(BodyPublishers.ofString(json))
                .build(),
            BodyHandlers.ofString(),
        )

        test("migrate, serve, create and read an item, stop") {
            val (service, baseUrl) = component()

            service.migrations.migrate()
            service.lifecycle.start()
            try {
                get("$baseUrl/health/ready").statusCode() shouldBe 200

                val created = post("$baseUrl/api/example/v1/items", """{"name":"Phantom Pizza"}""")
                created.statusCode() shouldBe 201
                val location = created.headers().firstValue("Location").get()

                get("$baseUrl$location").body() shouldContain "Phantom Pizza"
                post("$baseUrl/api/example/v1/items", """{"name":"Phantom Pizza"}""").statusCode() shouldBe 409
            } finally {
                service.lifecycle.stop()
            }
        }

        test("refuses to serve before migrations ran") {
            val (service, _) = component()

            shouldThrow<IllegalStateException> { service.lifecycle.start() }.message shouldContain "'migrate' mode"
        }
    })
