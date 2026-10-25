package io.github.slashncode.nothingeats.identity.adapters.http

import io.github.slashncode.nothingeats.identity.adapters.crypto.toJwk
import io.github.slashncode.nothingeats.identity.application.AccountExport
import io.github.slashncode.nothingeats.identity.application.DeleteAccount
import io.github.slashncode.nothingeats.identity.application.ExportAccount
import io.github.slashncode.nothingeats.identity.application.GetProfile
import io.github.slashncode.nothingeats.identity.application.GetPublicKeys
import io.github.slashncode.nothingeats.identity.application.Login
import io.github.slashncode.nothingeats.identity.application.LoginWithMagicLink
import io.github.slashncode.nothingeats.identity.application.Logout
import io.github.slashncode.nothingeats.identity.application.ManageRoles
import io.github.slashncode.nothingeats.identity.application.RefreshSession
import io.github.slashncode.nothingeats.identity.application.RegisterUser
import io.github.slashncode.nothingeats.identity.application.RequestMagicLink
import io.github.slashncode.nothingeats.identity.application.RequestPasswordReset
import io.github.slashncode.nothingeats.identity.application.ResetPassword
import io.github.slashncode.nothingeats.identity.application.Session
import io.github.slashncode.nothingeats.identity.application.SetAnalyticsConsent
import io.github.slashncode.nothingeats.identity.application.VerifyEmail
import io.github.slashncode.nothingeats.identity.domain.User
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.PlatformJson
import io.github.slashncode.nothingeats.platform.http.auth.authenticated
import io.github.slashncode.nothingeats.platform.http.auth.public
import io.github.slashncode.nothingeats.platform.http.auth.requireRole
import io.github.slashncode.nothingeats.platform.http.auth.user
import io.github.slashncode.nothingeats.platform.http.clientAddress
import io.ktor.http.CacheControl
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.acceptLanguage
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock

@Serializable
data class RegistrationRequest(
    val email: String,
    val password: String,
    val locale: String? = null,
)

@Serializable
data class VerificationRequest(
    val token: String,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class PasswordResetRequest(
    val email: String,
)

@Serializable
data class PasswordResetConfirmation(
    val token: String,
    val password: String,
)

@Serializable
data class MagicLinkRequest(
    val email: String,
)

@Serializable
data class MagicLinkLogin(
    val token: String,
)

@Serializable
data class DeletionRequest(
    val password: String,
)

@Serializable
data class ConsentRequest(
    val analytics: Boolean,
)

@Serializable
data class ExportedAccount(
    val id: String,
    val email: String,
    val locale: String,
    val roles: List<String>,
    val status: String,
    val createdAt: String,
    val verifiedAt: String?,
    val analyticsConsent: Boolean?,
    val consentUpdatedAt: String?,
)

@Serializable
data class AccountExportResponse(
    val exportedAt: String,
    val account: ExportedAccount,
    val activeSessions: Int,
    val notes: List<String>,
)

@Serializable
data class MessageResponse(
    val message: String,
)

@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val locale: String,
    val roles: List<String>,
    val verified: Boolean,
    val analyticsConsent: Boolean? = null,
)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val user: UserResponse,
)

data class RefreshCookieConfig(
    val name: String = "ne_refresh",
    /** Only sent to the session endpoints, never to other services. */
    val path: String = "/api/identity/v1/sessions",
    /** True behind HTTPS; false for the plain-HTTP local cluster. */
    val secure: Boolean = false,
)

private fun User.toResponse() =
    UserResponse(
        id.toString(),
        email.value,
        locale.tag,
        roles
            .map {
                it.claim
            }.sorted(),
        isVerified,
        analyticsConsent,
    )

private fun AccountExport.toResponse() =
    AccountExportResponse(
        exportedAt = exportedAt.toString(),
        account =
            ExportedAccount(
                id = user.id.toString(),
                email = user.email.value,
                locale = user.locale.tag,
                roles = user.roles.map { it.claim }.sorted(),
                status = user.status.name.lowercase(),
                createdAt = user.createdAt.toString(),
                verifiedAt = user.verifiedAt?.toString(),
                analyticsConsent = user.analyticsConsent,
                consentUpdatedAt = user.consentUpdatedAt?.toString(),
            ),
        activeSessions = activeSessions,
        notes =
            listOf(
                "Passwords and tokens are stored only as hashes and are not part of the export.",
                "Other NothingEats services hold no personal data about this account yet.",
                "Product analytics (PostHog, EU) holds usage events under the account id if analytics was allowed; deleting the account erases them.",
            ),
    )

/**
 * Public API of the identity context under `/api/identity`. The refresh token travels only as an
 * httpOnly, SameSite=Strict cookie scoped to the session endpoints; the access token is returned
 * in the body and kept in memory by the SPA.
 */
class IdentityRoutes(
    private val registerUser: RegisterUser,
    private val verifyEmail: VerifyEmail,
    private val login: Login,
    private val refresh: RefreshSession,
    private val logout: Logout,
    private val profile: GetProfile,
    private val requestPasswordReset: RequestPasswordReset,
    private val resetPassword: ResetPassword,
    private val roles: ManageRoles,
    private val requestMagicLink: RequestMagicLink,
    private val loginWithMagicLink: LoginWithMagicLink,
    private val exportAccount: ExportAccount,
    private val deleteAccount: DeleteAccount,
    private val setConsent: SetAnalyticsConsent,
    private val publicKeys: GetPublicKeys,
    private val cookie: RefreshCookieConfig,
    private val clock: Clock = Clock.systemUTC(),
) : HttpRoutes {
    override fun Route.register() {
        route("/api/identity") {
            public {
                get("/.well-known/jwks.json") {
                    val keys =
                        JsonArray(
                            publicKeys().map { key ->
                                JsonObject(key.toJwk().mapValues { JsonPrimitive(it.value.toString()) })
                            },
                        )
                    call.response.cacheControl(CacheControl.MaxAge(JWKS_MAX_AGE_SECONDS))
                    call.respondText(
                        PlatformJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("keys" to keys))),
                        ContentType.Application.Json,
                    )
                }
                route("/v1") {
                    post("/registrations") {
                        val request = call.receive<RegistrationRequest>()
                        registerUser(request.email, request.password, request.locale ?: call.request.acceptLanguage(), call.clientAddress)
                        call.respond(HttpStatusCode.Accepted, MessageResponse("Check your inbox to confirm your email address"))
                    }
                    post("/verifications") {
                        verifyEmail(call.receive<VerificationRequest>().token)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/sessions") {
                        val request = call.receive<LoginRequest>()
                        call.respondSession(login(request.email, request.password, call.clientAddress))
                    }
                    post("/sessions/refresh") {
                        call.respondSession(refresh(call.request.cookies[cookie.name]))
                    }
                    delete("/sessions/current") {
                        logout(call.request.cookies[cookie.name])
                        call.setRefreshCookie(value = "", maxAgeSeconds = 0)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/password-resets") {
                        requestPasswordReset(call.receive<PasswordResetRequest>().email, call.clientAddress)
                        call.respond(HttpStatusCode.Accepted, MessageResponse("If the address has an account, a link is on its way"))
                    }
                    post("/magic-links") {
                        requestMagicLink(call.receive<MagicLinkRequest>().email, call.clientAddress)
                        call.respond(HttpStatusCode.Accepted, MessageResponse("If the address has an account, a login link is on its way"))
                    }
                    post("/magic-links/sessions") {
                        call.respondSession(loginWithMagicLink(call.receive<MagicLinkLogin>().token))
                    }
                    post("/password-resets/confirmations") {
                        val request = call.receive<PasswordResetConfirmation>()
                        resetPassword(request.token, request.password)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
            authenticated {
                get("/v1/me") { call.respond(profile(call.user.id).toResponse()) }
                get("/v1/me/export") {
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment
                            .withParameter(
                                ContentDisposition.Parameters.FileName,
                                "nothingeats-account.json",
                            ).toString(),
                    )
                    call.respond(exportAccount(call.user.id).toResponse())
                }
                put("/v1/me/consent") {
                    call.respond(setConsent(call.user.id, call.receive<ConsentRequest>().analytics).toResponse())
                }
                // POST, not DELETE with a body: some proxies drop bodies of DELETE requests.
                post("/v1/me/deletion") {
                    deleteAccount(call.user.id, call.receive<DeletionRequest>().password)
                    call.setRefreshCookie(value = "", maxAgeSeconds = 0)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            requireRole("admin") {
                route("/v1/admin/users") {
                    get { call.respond(roles.find(call.request.queryParameters["email"].orEmpty()).toResponse()) }
                    put("/{id}/roles/{role}") {
                        call.respond(
                            roles.grant(call.user.id, call.parameters["id"].orEmpty(), call.parameters["role"].orEmpty()).toResponse(),
                        )
                    }
                    delete("/{id}/roles/{role}") {
                        call.respond(
                            roles.revoke(call.user.id, call.parameters["id"].orEmpty(), call.parameters["role"].orEmpty()).toResponse(),
                        )
                    }
                }
            }
        }
    }

    private suspend fun ApplicationCall.respondSession(session: Session) {
        val maxAge = session.refreshTokenExpiresAt.epochSecond - clock.instant().epochSecond
        setRefreshCookie(session.refreshToken, maxAge.toInt())
        respond(
            TokenResponse(
                session.accessToken.token,
                expiresIn = session.accessToken.expiresIn.inWholeSeconds,
                user = session.user.toResponse(),
            ),
        )
    }

    private fun ApplicationCall.setRefreshCookie(
        value: String,
        maxAgeSeconds: Int,
    ) {
        response.cookies.append(
            Cookie(
                name = cookie.name,
                value = value,
                maxAge = maxAgeSeconds,
                path = cookie.path,
                secure = cookie.secure,
                httpOnly = true,
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
    }

    private companion object {
        const val JWKS_MAX_AGE_SECONDS = 300
    }
}
