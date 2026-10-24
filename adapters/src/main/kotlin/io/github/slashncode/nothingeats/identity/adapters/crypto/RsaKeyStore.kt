package io.github.slashncode.nothingeats.identity.adapters.crypto

import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.identity.application.PublicKey
import io.github.slashncode.nothingeats.identity.application.SigningKeys
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.time.Clock
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid

data class KeysConfig(
    /** Base64 of 32 random bytes (AES-256) that encrypts the private keys at rest. */
    val encryptionKey: Secret,
    /** How long a retired key is still published; must exceed the access token lifetime. */
    val retiredKeyGrace: Duration = 1.hours,
    val reloadInterval: Duration = 60.seconds,
)

internal object SigningKeysTable : Table("signing_keys") {
    val kid = text("kid")
    val status = text("status")
    val privateKey = binary("private_key")
    val modulus = text("modulus")
    val exponent = text("exponent")
    val createdAt = timestamp("created_at")
    val activatedAt = timestamp("activated_at").nullable()
    val retiredAt = timestamp("retired_at").nullable()

    override val primaryKey = PrimaryKey(kid)
}

private enum class KeyStatus { CURRENT, NEXT, RETIRED }

class SigningKey(
    val keyId: String,
    val privateKey: RSAPrivateKey,
    val publicKey: RSAPublicKey,
)

/**
 * RS256 signing keys in Postgres. There is always a CURRENT key that signs and a NEXT key that is
 * already published, so verifiers know it before it signs anything; retired keys stay published for
 * [KeysConfig.retiredKeyGrace]. Private keys are AES-GCM encrypted with a key from the environment.
 * Every pod reloads the keys periodically, so a rotation (cron job) reaches all of them.
 */
class RsaKeyStore(
    private val db: PostgresDatabase,
    private val config: KeysConfig,
    private val clock: Clock = Clock.systemUTC(),
) : SigningKeys,
    LifecycleComponent {
    override val name = "signing-keys"
    override val order = LifecycleOrder.APPLICATION

    private class Snapshot(
        val current: SigningKey,
        val published: Map<String, PublicKey>,
    )

    private val log = LoggerFactory.getLogger(RsaKeyStore::class.java)
    private val aes =
        SecretKeySpec(Base64.getDecoder().decode(config.encryptionKey.value).also { require(it.size == AES_KEY_BYTES) }, "AES")
    private val random = SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reloader: Job? = null

    @Volatile private var snapshot: Snapshot? = null

    override suspend fun start() {
        db.transaction {
            lockKeys()
            val statuses = SigningKeysTable.selectAll().map { it[SigningKeysTable.status] }
            if (KeyStatus.CURRENT.name !in statuses) insertKey(KeyStatus.CURRENT)
            if (KeyStatus.NEXT.name !in statuses) insertKey(KeyStatus.NEXT)
        }
        reload()
    }

    override suspend fun onReady() {
        reloader =
            scope.launch {
                while (isActive) {
                    delay(config.reloadInterval)
                    try {
                        reload()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("Reloading signing keys failed", e)
                    }
                }
            }
    }

    override suspend fun stop() {
        reloader?.cancelAndJoin()
    }

    fun current(): SigningKey = loaded().current

    /** Used by the JWKS provider of this service's own token verification. */
    fun publicKey(keyId: String): PublicKey? = loaded().published[keyId]

    override suspend fun publicKeys(): List<PublicKey> = loaded().published.values.sortedBy { it.keyId }

    override suspend fun rotate() {
        val now = clock.instant()
        db.transaction {
            lockKeys()
            SigningKeysTable.update({ SigningKeysTable.status eq KeyStatus.CURRENT.name }) {
                it[status] = KeyStatus.RETIRED.name
                it[retiredAt] = now
            }
            SigningKeysTable.update({ SigningKeysTable.status eq KeyStatus.NEXT.name }) {
                it[status] = KeyStatus.CURRENT.name
                it[activatedAt] = now
            }
            insertKey(KeyStatus.NEXT)
            // Long after they stopped being published, retired keys are deleted.
            val forgetBefore = now.minus((config.retiredKeyGrace * 2).toJavaDuration())
            SigningKeysTable.deleteWhere {
                (SigningKeysTable.status eq KeyStatus.RETIRED.name) and (SigningKeysTable.retiredAt less forgetBefore)
            }
        }
        log.info("Rotated signing keys")
        reload()
    }

    suspend fun reload() {
        val now = clock.instant()
        val rows =
            db.transaction(readOnly = true) {
                val publishedStatuses = listOf(KeyStatus.CURRENT.name, KeyStatus.NEXT.name)
                SigningKeysTable
                    .selectAll()
                    .where {
                        (SigningKeysTable.status inList publishedStatuses) or
                            (SigningKeysTable.retiredAt greater now.minus(config.retiredKeyGrace.toJavaDuration()))
                    }.toList()
            }
        val current = rows.single { it[SigningKeysTable.status] == KeyStatus.CURRENT.name }
        val kid = current[SigningKeysTable.kid]
        snapshot =
            Snapshot(
                current = SigningKey(kid, decryptPrivateKey(kid, current[SigningKeysTable.privateKey]), publicKeyOf(current)),
                published =
                    rows.associate {
                        it[SigningKeysTable.kid] to
                            PublicKey(it[SigningKeysTable.kid], it[SigningKeysTable.modulus], it[SigningKeysTable.exponent])
                    },
            )
    }

    private fun loaded() = snapshot ?: error("Signing keys are not loaded yet")

    private fun JdbcTransaction.lockKeys() {
        // Serializes key bootstrap and rotation across pods.
        exec("SELECT pg_advisory_xact_lock($ADVISORY_LOCK)")
    }

    private fun insertKey(status: KeyStatus) {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_BITS) }.generateKeyPair()
        val public = pair.public as RSAPublicKey
        val kid = "k-" + Uuid.random().toHexString().take(KID_LENGTH)
        val now = clock.instant()
        SigningKeysTable.insert {
            it[SigningKeysTable.kid] = kid
            it[SigningKeysTable.status] = status.name
            it[privateKey] = encrypt(kid, pair.private.encoded)
            it[modulus] = base64Url(public.modulus)
            it[exponent] = base64Url(public.publicExponent)
            it[createdAt] = now
            it[activatedAt] = if (status == KeyStatus.CURRENT) now else null
        }
    }

    // The key id is authenticated data: an encrypted key cannot be moved to another row.
    private fun encrypt(
        kid: String,
        plain: ByteArray,
    ): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(GCM_TAG_BITS, iv)) }
        cipher.updateAAD(kid.toByteArray())
        return iv + cipher.doFinal(plain)
    }

    private fun decryptPrivateKey(
        kid: String,
        stored: ByteArray,
    ): RSAPrivateKey {
        val cipher =
            Cipher.getInstance(AES_GCM).apply {
                init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(GCM_TAG_BITS, stored, 0, GCM_IV_BYTES))
            }
        cipher.updateAAD(kid.toByteArray())
        val plain = cipher.doFinal(stored, GCM_IV_BYTES, stored.size - GCM_IV_BYTES)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(plain)) as RSAPrivateKey
    }

    private fun publicKeyOf(row: ResultRow): RSAPublicKey {
        val decoder = Base64.getUrlDecoder()
        val spec =
            RSAPublicKeySpec(
                BigInteger(1, decoder.decode(row[SigningKeysTable.modulus])),
                BigInteger(1, decoder.decode(row[SigningKeysTable.exponent])),
            )
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    private fun base64Url(value: BigInteger): String {
        val bytes = value.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val AES_KEY_BYTES = 32
        const val AES_GCM = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val RSA_BITS = 2048
        const val KID_LENGTH = 12
        const val ADVISORY_LOCK = 7_310_001L
    }
}
