package io.github.slashncode.nothingeats.identity.application

import io.github.slashncode.nothingeats.identity.domain.Email
import io.github.slashncode.nothingeats.identity.domain.EmailAlreadyRegistered
import io.github.slashncode.nothingeats.identity.domain.EmailVerification
import io.github.slashncode.nothingeats.identity.domain.RefreshToken
import io.github.slashncode.nothingeats.identity.domain.User
import io.github.slashncode.nothingeats.identity.domain.UserId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class InMemoryUsers : Users {
    val all = ConcurrentHashMap<UserId, User>()

    override suspend fun add(user: User) {
        if (all.values.any { it.email == user.email }) throw EmailAlreadyRegistered(user.email)
        all[user.id] = user
    }

    override suspend fun save(user: User) {
        all[user.id] = user
    }

    override suspend fun findById(id: UserId) = all[id]

    override suspend fun findByEmail(email: Email) = all.values.firstOrNull { it.email == email }
}

class InMemoryEmailVerifications : EmailVerifications {
    val all = ConcurrentHashMap<String, EmailVerification>()

    override suspend fun add(verification: EmailVerification) {
        all[verification.tokenHash] = verification
    }

    override suspend fun find(tokenHash: String) = all[tokenHash]

    override suspend fun markUsed(
        tokenHash: String,
        now: Instant,
    ) {
        all.computeIfPresent(tokenHash) { _, v -> v.copy(usedAt = now) }
    }
}

class InMemoryRefreshTokens : RefreshTokens {
    val all = ConcurrentHashMap<Uuid, RefreshToken>()

    override suspend fun add(token: RefreshToken) {
        all[token.id] = token
    }

    override suspend fun find(tokenHash: String) = all.values.firstOrNull { it.tokenHash == tokenHash }

    override suspend fun markUsed(
        id: Uuid,
        now: Instant,
    ): Boolean {
        var claimed = false
        all.computeIfPresent(id) { _, t ->
            if (t.usedAt == null && t.revokedAt == null) {
                claimed = true
                t.copy(usedAt = now)
            } else {
                t
            }
        }
        return claimed
    }

    override suspend fun revokeFamily(
        familyId: Uuid,
        now: Instant,
    ) {
        all.replaceAll { _, t -> if (t.familyId == familyId && t.revokedAt == null) t.copy(revokedAt = now) else t }
    }
}

/** Not a real hash: fast and readable, with a switch to simulate outdated parameters. */
class FakePasswordHasher : PasswordHasher {
    var outdated = false

    override suspend fun hash(password: String) = "hashed:$password"

    override suspend fun verify(
        password: String,
        hash: String,
    ) = PasswordCheck(matches = hash == "hashed:$password", needsRehash = outdated)

    override val dummyHash = "hashed:dummy-${Uuid.random()}"
}

class FakeAccessTokens : AccessTokens {
    override suspend fun issue(user: User) = AccessToken("access-for-${user.id}", 15.minutes)
}

class RecordingUserEvents : UserEvents {
    val events = CopyOnWriteArrayList<String>()
    var lastVerificationToken: String? = null

    override suspend fun registered(
        user: User,
        verificationToken: String,
    ) {
        events += "registered:${user.email}"
        lastVerificationToken = verificationToken
    }

    override suspend fun registrationAttempted(existing: User) {
        events += "attempted:${existing.email}"
    }

    override suspend fun verified(user: User) {
        events += "verified:${user.email}"
    }
}

/** Runs blocks directly; the in-memory fakes have no rollback. */
class DirectTransactions : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

class CountingSecrets : Secrets {
    private val counter = AtomicInteger()

    override fun newToken() = "token-${counter.incrementAndGet()}"
}
