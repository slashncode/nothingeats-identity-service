package io.github.slashncode.nothingeats.identity.application

import io.github.slashncode.nothingeats.identity.domain.Email
import io.github.slashncode.nothingeats.identity.domain.EmailVerification
import io.github.slashncode.nothingeats.identity.domain.RefreshToken
import io.github.slashncode.nothingeats.identity.domain.User
import io.github.slashncode.nothingeats.identity.domain.UserId
import java.time.Instant
import kotlin.time.Duration
import kotlin.uuid.Uuid

interface Users {
    /** @throws io.github.slashncode.nothingeats.identity.domain.EmailAlreadyRegistered */
    suspend fun add(user: User)

    suspend fun save(user: User)

    suspend fun findById(id: UserId): User?

    suspend fun findByEmail(email: Email): User?
}

interface EmailVerifications {
    suspend fun add(verification: EmailVerification)

    suspend fun find(tokenHash: String): EmailVerification?

    suspend fun markUsed(
        tokenHash: String,
        now: Instant,
    )
}

interface RefreshTokens {
    suspend fun add(token: RefreshToken)

    suspend fun find(tokenHash: String): RefreshToken?

    /** Atomically marks the token used; false if someone else used it first. */
    suspend fun markUsed(
        id: Uuid,
        now: Instant,
    ): Boolean

    suspend fun revokeFamily(
        familyId: Uuid,
        now: Instant,
    )
}

data class PasswordCheck(
    val matches: Boolean,
    /** The hash uses outdated parameters and should be replaced while the password is at hand. */
    val needsRehash: Boolean,
)

interface PasswordHasher {
    suspend fun hash(password: String): String

    suspend fun verify(
        password: String,
        hash: String,
    ): PasswordCheck

    /** A real hash of a random password, checked when an account does not exist so timing reveals nothing. */
    val dummyHash: String
}

data class AccessToken(
    val token: String,
    val expiresIn: Duration,
)

interface AccessTokens {
    suspend fun issue(user: User): AccessToken
}

data class PublicKey(
    val keyId: String,
    val modulus: String,
    val exponent: String,
)

interface SigningKeys {
    /** Keys a verifier may meet: the current one, the next one, and recently retired ones. */
    suspend fun publicKeys(): List<PublicKey>

    /** Retires the current key, promotes the next one and creates a new next key. */
    suspend fun rotate()
}

/** Domain events, stored in the outbox within the caller's transaction. */
interface UserEvents {
    suspend fun registered(
        user: User,
        verificationToken: String,
    )

    /** Someone registered with the address of an existing account. */
    suspend fun registrationAttempted(existing: User)

    suspend fun verified(user: User)
}

interface Transactions {
    suspend fun <T> run(block: suspend () -> T): T
}

interface Secrets {
    /** 256 random bits, URL-safe. */
    fun newToken(): String
}
