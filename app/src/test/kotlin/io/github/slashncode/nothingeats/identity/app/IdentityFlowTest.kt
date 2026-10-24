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
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
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
            headers.forEach { (name, value) -> request.header(name, value) }
            return http.send(request.build(), BodyHandlers.ofString())
        }

        fun HttpResponse<String>.json() = Json.parseToJsonElement(body()).jsonObject

        fun HttpResponse<String>.refreshCookie(): String =
            headers().allValues("Set-Cookie").first { it.startsWith("ne_refresh=") }.substringBefore(";")

        /** The verification token as the notification service will read it: from the event on Kafka. */
        fun verificationTokenFor(email: String): String {
            KafkaClients.consumer(KafkaConfig(KafkaTestCluster.bootstrapServers), "test-${UUID.randomUUID()}").use { consumer ->
                consumer.subscribe(listOf(TOPIC))
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    for (record in consumer.poll(Duration.ofMillis(250))) {
                        val event = EventJson.decodeFromString(CloudEvent.serializer(), record.value().decodeToString())
                        val data = event.data.jsonObject
                        if (event.type == "nothingeats.identity.user.registered.v1" && data["email"]?.jsonPrimitive?.content == email) {
                            return data["verificationToken"]!!.jsonPrimitive.content
                        }
                    }
                }
            }
            error("No registration event for $email")
        }

        suspend fun registeredAndLoggedIn(api: String): Pair<String, HttpResponse<String>> {
            val email = "ghost-${UUID.randomUUID()}@nothingeats.dev"
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
    })
