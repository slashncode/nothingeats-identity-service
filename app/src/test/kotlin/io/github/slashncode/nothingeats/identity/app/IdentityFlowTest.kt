package io.github.slashncode.nothingeats.identity.app

import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.identity.adapters.crypto.KeysConfig
import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventJson
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.testing.KafkaTestCluster
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.github.slashncode.nothingeats.platform.testing.ValkeyTestServer
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private const val TOPIC = "identity.user.v1"

private fun freePort() = ServerSocket(0).use { it.localPort }

private fun randomAesKey() = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

/** The whole service wired by the real composition root, against real Postgres and Kafka. */
class IdentityFlowTest :
    FunSpec({
        val http = HttpClient.newHttpClient()

        fun service(): Pair<ServiceComponent, String> {
            KafkaTestCluster.createTopics(TOPIC)
            val port = freePort()
            val config =
                ServiceConfig(
                    http = HttpServerConfig(port = port),
                    database = PostgresTestDatabases.create(),
                    kafka = KafkaConfig(KafkaTestCluster.bootstrapServers),
                    keys = KeysConfig(Secret(randomAesKey())),
                    valkey = ValkeyTestServer.config(),
                    admin = AdminConfig(emails = listOf("boss@nothingeats.dev")),
                    lifecycle = LifecycleConfig(drainDelay = kotlin.time.Duration.ZERO),
                )
            return ServiceComponent::class.create(config) to "http://localhost:$port/api/identity"
        }

        fun send(
            method: String,
            url: String,
            body: String? = null,
            headers: Map<String, String> = emptyMap(),
        ): HttpResponse<String> {
            val request =
                HttpRequest.newBuilder(URI(url)).method(
                    method,
                    body?.let { BodyPublishers.ofString(it) } ?: BodyPublishers.noBody(),
                )
            if (body != null) request.header("Content-Type", "application/json")
            // A different client per request: per-client limits must not add up across tests.
            request.header("X-Forwarded-For", "10.${(0..255).random()}.${(0..255).random()}.${(1..254).random()}")
            headers.forEach { (name, value) -> request.header(name, value) }
            return http.send(request.build(), BodyHandlers.ofString())
        }

        fun HttpResponse<String>.json() = Json.parseToJsonElement(body()).jsonObject

        fun HttpResponse<String>.refreshCookie(): String =
            headers().allValues("Set-Cookie").first { it.startsWith("ne_refresh=") }.substringBefore(";")

        fun tokenFromEvent(
            email: String,
            type: String,
            field: String,
        ): String {
            KafkaClients.consumer(KafkaConfig(KafkaTestCluster.bootstrapServers), "test-${UUID.randomUUID()}").use { consumer ->
                consumer.subscribe(listOf(TOPIC))
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    for (record in consumer.poll(Duration.ofMillis(250))) {
                        val event = EventJson.decodeFromString(CloudEvent.serializer(), record.value().decodeToString())
                        val data = event.data.jsonObject
                        if (event.type == type && data["email"]?.jsonPrimitive?.content == email) {
                            return data[field]!!.jsonPrimitive.content
                        }
                    }
                }
            }
            error("No $type event for $email")
        }

        fun deletedEvent(userId: String): JsonObject {
            KafkaClients.consumer(KafkaConfig(KafkaTestCluster.bootstrapServers), "test-${UUID.randomUUID()}").use { consumer ->
                consumer.subscribe(listOf(TOPIC))
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    for (record in consumer.poll(Duration.ofMillis(250))) {
                        val event = EventJson.decodeFromString(CloudEvent.serializer(), record.value().decodeToString())
                        if (event.type == "nothingeats.identity.user.deleted.v1" && event.subject == userId) return event.data.jsonObject
                    }
                }
            }
            error("No deleted event for $userId")
        }

        /** The verification token as the notification service will read it: from the event on Kafka. */
        fun verificationTokenFor(email: String): String =
            tokenFromEvent(email, "nothingeats.identity.user.registered.v1", "verificationToken")

        suspend fun registeredAndLoggedIn(
            api: String,
            email: String = "ghost-${UUID.randomUUID()}@nothingeats.dev",
        ): Pair<String, HttpResponse<String>> {
            send(
                "POST",
                "$api/v1/registrations",
                """{"email":"$email","password":"correct horse battery","locale":"en"}""",
            ).statusCode() shouldBe
                202
            val token = verificationTokenFor(email)
            send("POST", "$api/v1/verifications", """{"token":"$token"}""").statusCode() shouldBe 204
            val login = send("POST", "$api/v1/sessions", """{"email":"$email","password":"correct horse battery"}""")
            login.statusCode() shouldBe 200
            return email to login
        }

        test("register, verify with the token from the event, log in and call /me") {
            val (service, api) = service()
            service.migrations.migrate()
            service.lifecycle.start()
            try {
                val (email, login) = registeredAndLoggedIn(api)

                val accessToken = login.json()["accessToken"]!!.jsonPrimitive.content
                login.headers().firstValue("Set-Cookie").get() shouldContain "HttpOnly"
                login.headers().firstValue("Set-Cookie").get() shouldContain "SameSite=Strict"
                val me = send("GET", "$api/v1/me", headers = mapOf("Authorization" to "Bearer $accessToken"))
                me.statusCode() shouldBe 200
                me.json()["email"]!!.jsonPrimitive.content shouldBe email
                me.json()["locale"]!!.jsonPrimitive.content shouldBe "en"

                send("POST", "$api/v1/sessions", """{"email":"$email","password":"wrong password!!"}""").statusCode() shouldBe 401
            } finally {
                service.lifecycle.stop()
            }
        }

        test("refresh rotates the cookie; replaying an old one revokes the family") {
            val (service, api) = service()
            service.migrations.migrate()
            service.lifecycle.start()
            try {
                val (_, login) = registeredAndLoggedIn(api)
                val first = login.refreshCookie()

                val refreshed = send("POST", "$api/v1/sessions/refresh", headers = mapOf("Cookie" to first))
                refreshed.statusCode() shouldBe 200
                val second = refreshed.refreshCookie()
                second shouldNotBe first

                val replay = send("POST", "$api/v1/sessions/refresh", headers = mapOf("Cookie" to first))
                replay.statusCode() shouldBe 401
                replay.json()["code"]!!.jsonPrimitive.content shouldBe "REFRESH_TOKEN_REUSED"
                send("POST", "$api/v1/sessions/refresh", headers = mapOf("Cookie" to second)).statusCode() shouldBe 401
            } finally {
                service.lifecycle.stop()
            }
        }

        test("JWKS publishes current and next key; tokens survive a key rotation") {
            val (service, api) = service()
            service.migrations.migrate()
            service.lifecycle.start()
            try {
                send("GET", "$api/.well-known/jwks.json").json()["keys"]!!.jsonArray shouldHaveSize 2
                val (_, login) = registeredAndLoggedIn(api)
                val accessToken = login.json()["accessToken"]!!.jsonPrimitive.content

                service.signingKeys.rotate()

                eventually(5.seconds) { send("GET", "$api/.well-known/jwks.json").json()["keys"]!!.jsonArray shouldHaveSize 3 }
                send("GET", "$api/v1/me", headers = mapOf("Authorization" to "Bearer $accessToken")).statusCode() shouldBe 200
            } finally {
                service.lifecycle.stop()
            }
        }
        test("forgotten password: mail link, new password, every old session ends") {
            val (service, api) = service()
            service.migrations.migrate()
            service.lifecycle.start()
            try {
                val (email, login) = registeredAndLoggedIn(api)
                val oldCookie = login.refreshCookie()

                send("POST", "$api/v1/password-resets", """{"email":"nobody-${UUID.randomUUID()}@nothingeats.dev"}""").statusCode() shouldBe
                    202
                send("POST", "$api/v1/password-resets", """{"email":"$email"}""").statusCode() shouldBe 202
                val token = tokenFromEvent(email, "nothingeats.identity.user.password-reset-requested.v1", "resetToken")

                send(
                    "POST",
                    "$api/v1/password-resets/confirmations",
                    """{"token":"$token","password":"a brand new passphrase"}""",
                ).statusCode() shouldBe
                    204
                send("POST", "$api/v1/password-resets/confirmations", """{"token":"$token","password":"a brand new passphrase"}""")
                    .json()["code"]!!
                    .jsonPrimitive.content shouldBe "PASSWORD_RESET_TOKEN_INVALID"

                send("POST", "$api/v1/sessions/refresh", headers = mapOf("Cookie" to oldCookie)).statusCode() shouldBe 401
                send("POST", "$api/v1/sessions", """{"email":"$email","password":"correct horse battery"}""").statusCode() shouldBe 401
                send("POST", "$api/v1/sessions", """{"email":"$email","password":"a brand new passphrase"}""").statusCode() shouldBe 200
                tokenFromEvent(email, "nothingeats.identity.user.password-changed.v1", "userId") shouldNotBe ""
            } finally {
                service.lifecycle.stop()
            }
        }

        test("password guessing is stopped per account with 429 and Retry-After (Valkey)") {
            val (service, api) = service()
            service.migrations.migrate()
            service.lifecycle.start()
            try {
                val target = "target-${UUID.randomUUID()}@nothingeats.dev"
                repeat(10) {
                    send("POST", "$api/v1/sessions", """{"email":"$target","password":"guess number $it"}""").statusCode() shouldBe 401
                }

                val blocked = send("POST", "$api/v1/sessions", """{"email":"$target","password":"guess number 11"}""")
                blocked.statusCode() shouldBe 429
                blocked.json()["code"]!!.jsonPrimitive.content shouldBe "TOO_MANY_ATTEMPTS"
                (
                    blocked
                        .headers()
                        .firstValue("Retry-After")
                        .get()
                        .toInt() in 1..900
                ) shouldBe true
            } finally {
                service.lifecycle.stop()
            }
        }

        test("bootstrap admins can look up accounts and grant roles; customers cannot") {
            val (service, api) = service()
            service.migrations.migrate()
            service.lifecycle.start()
            try {
                val (_, boss) = registeredAndLoggedIn(api, email = "boss@nothingeats.dev")
                val (ghost, customer) = registeredAndLoggedIn(api)
                val bossToken = boss.json()["accessToken"]!!.jsonPrimitive.content
                boss
                    .json()["user"]!!
                    .jsonObject["roles"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content } shouldBe listOf("admin", "customer")

                val customerToken = customer.json()["accessToken"]!!.jsonPrimitive.content
                send(
                    "GET",
                    "$api/v1/admin/users?email=$ghost",
                    headers = mapOf("Authorization" to "Bearer $customerToken"),
                ).statusCode() shouldBe
                    403

                val found = send("GET", "$api/v1/admin/users?email=$ghost", headers = mapOf("Authorization" to "Bearer $bossToken"))
                found.statusCode() shouldBe 200
                val ghostId = found.json()["id"]!!.jsonPrimitive.content
                val granted =
                    send("PUT", "$api/v1/admin/users/$ghostId/roles/admin", headers = mapOf("Authorization" to "Bearer $bossToken"))
                granted.json()["roles"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("admin", "customer")
            } finally {
                service.lifecycle.stop()
            }
        }

        test("magic link: mailed token logs in with a session cookie") {
            val (service, api) = service()
            service.migrations.migrate()
            service.lifecycle.start()
            try {
                val (email, _) = registeredAndLoggedIn(api)

                send("POST", "$api/v1/magic-links", """{"email":"$email"}""").statusCode() shouldBe 202
                val token = tokenFromEvent(email, "nothingeats.identity.user.magic-link-requested.v1", "magicToken")
                val login = send("POST", "$api/v1/magic-links/sessions", """{"token":"$token"}""")

                login.statusCode() shouldBe 200
                login.refreshCookie() shouldContain "ne_refresh="
                send("POST", "$api/v1/magic-links/sessions", """{"token":"$token"}""").statusCode() shouldBe 401
            } finally {
                service.lifecycle.stop()
            }
        }

        test("export, consent, then deletion: only the id survives, as tombstone and event") {
            val (service, api) = service()
            service.migrations.migrate()
            service.lifecycle.start()
            try {
                val (email, login) = registeredAndLoggedIn(api)
                val auth = mapOf("Authorization" to "Bearer ${login.json()["accessToken"]!!.jsonPrimitive.content}")
                val userId =
                    login
                        .json()["user"]!!
                        .jsonObject["id"]!!
                        .jsonPrimitive.content

                send(
                    "PUT",
                    "$api/v1/me/consent",
                    """{"analytics":true}""",
                    auth,
                ).json()["analyticsConsent"]!!.jsonPrimitive.content shouldBe
                    "true"
                val export = send("GET", "$api/v1/me/export", headers = auth)
                export.headers().firstValue("Content-Disposition").get() shouldContain "attachment"
                export
                    .json()["account"]!!
                    .jsonObject["email"]!!
                    .jsonPrimitive.content shouldBe email
                export.json()["activeSessions"]!!.jsonPrimitive.content shouldBe "1"
                export.body() shouldNotContain "password_hash"

                send("POST", "$api/v1/me/deletion", """{"password":"wrong password!!"}""", auth).statusCode() shouldBe 403
                send("POST", "$api/v1/me/deletion", """{"password":"correct horse battery"}""", auth).statusCode() shouldBe 204

                send("POST", "$api/v1/sessions", """{"email":"$email","password":"correct horse battery"}""").statusCode() shouldBe 401
                send("POST", "$api/v1/sessions/refresh", headers = mapOf("Cookie" to login.refreshCookie())).statusCode() shouldBe 401
                deletedEvent(userId).keys shouldBe setOf("userId")

                // No PostHog personal API key in tests: the tombstone waits for the next run.
                service.cronTasks.single { it.name == "erase-analytics" }.run()
            } finally {
                service.lifecycle.stop()
            }
        }
    })
