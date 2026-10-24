package io.github.slashncode.nothingeats.identity.adapters.crypto

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.slashncode.nothingeats.identity.application.AccessToken
import io.github.slashncode.nothingeats.identity.application.AccessTokens
import io.github.slashncode.nothingeats.identity.application.PublicKey
import io.github.slashncode.nothingeats.identity.application.Secrets
import io.github.slashncode.nothingeats.identity.domain.User
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid

data class AccessTokenConfig(
    /** Must equal what verifiers expect (`JWT__ISSUER` in every service). */
    val issuer: String = "http://nothingeats.localhost/api/identity",
    val audience: String = "nothingeats",
    val ttl: Duration = 15.minutes,
)

/** RS256 access tokens with the claims every service reads: `sub`, `roles`, `locale`. */
class JwtAccessTokens(
    private val keys: RsaKeyStore,
    private val config: AccessTokenConfig,
    private val clock: Clock = Clock.systemUTC(),
) : AccessTokens {
    override suspend fun issue(user: User): AccessToken {
        val key = keys.current()
        val now = clock.instant()
        val token =
            JWT
                .create()
                .withKeyId(key.keyId)
                .withIssuer(config.issuer)
                .withAudience(config.audience)
                .withSubject(user.id.toString())
                .withJWTId(Uuid.random().toString())
                .withClaim("roles", user.roles.map { it.claim })
                .withClaim("locale", user.locale.tag)
                .withIssuedAt(now)
                .withExpiresAt(now.plus(config.ttl.toJavaDuration()))
                .sign(Algorithm.RSA256(key.publicKey, key.privateKey))
        return AccessToken(token, config.ttl)
    }
}

fun PublicKey.toJwk(): Map<String, Any> =
    mapOf(
        "kty" to "RSA",
        "use" to "sig",
        "alg" to "RS256",
        "kid" to keyId,
        "n" to modulus,
        "e" to exponent,
    )

/** Lets this service verify its own tokens from the key store, without an HTTP call to itself. */
class KeyStoreJwkProvider(
    private val keys: RsaKeyStore,
) : JwkProvider {
    override fun get(keyId: String): Jwk =
        keys.publicKey(keyId)?.let { Jwk.fromValues(it.toJwk()) } ?: throw SigningKeyNotFoundException("Unknown key $keyId", null)
}

class SecureRandomSecrets : Secrets {
    private val random = SecureRandom()

    override fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
