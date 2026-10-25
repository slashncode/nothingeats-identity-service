package io.github.slashncode.nothingeats.identity.adapters.crypto

import io.github.slashncode.nothingeats.identity.application.PasswordCheck
import io.github.slashncode.nothingeats.identity.application.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** OWASP's minimum for Argon2id: 19 MiB memory, 2 iterations, 1 lane. */
data class Argon2Settings(
    val memoryKib: Int = 19_456,
    val iterations: Int = 2,
    val parallelism: Int = 1,
)

/** Hashes at once; each holds [Argon2Settings.memoryKib] of heap, so a burst must queue, not pile up. */
private const val MAX_CONCURRENT_HASHES = 4

/**
 * Argon2id in pure Java (BouncyCastle, no native library in the image), stored in the standard PHC
 * format `$argon2id$v=19$m=…,t=…,p=…$salt$hash` so the parameters travel with every hash.
 */
class Argon2PasswordHasher(
    private val settings: Argon2Settings = Argon2Settings(),
) : PasswordHasher {
    private val random = SecureRandom()
    private val encoder = Base64.getEncoder().withoutPadding()
    private val decoder = Base64.getDecoder()
    private val permits = Semaphore(MAX_CONCURRENT_HASHES)

    override val dummyHash: String = hashBlocking(encoder.encodeToString(ByteArray(SALT_BYTES).also(random::nextBytes)))

    override suspend fun hash(password: String): String = permits.withPermit { withContext(Dispatchers.Default) { hashBlocking(password) } }

    override suspend fun verify(
        password: String,
        hash: String,
    ): PasswordCheck =
        permits.withPermit {
            withContext(Dispatchers.Default) {
                val parsed = parse(hash) ?: return@withContext PasswordCheck(matches = false, needsRehash = false)
                val actual = derive(password, parsed.salt, parsed.settings, parsed.hash.size)
                PasswordCheck(matches = MessageDigest.isEqual(actual, parsed.hash), needsRehash = parsed.settings != settings)
            }
        }

    private fun hashBlocking(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(password, salt, settings, HASH_BYTES)
        return "\$argon2id\$v=19\$m=${settings.memoryKib},t=${settings.iterations},p=${settings.parallelism}" +
            "\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        settings: Argon2Settings,
        length: Int,
    ): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(settings.memoryKib)
                .withIterations(settings.iterations)
                .withParallelism(settings.parallelism)
                .withSalt(salt)
                .build(),
        )
        return ByteArray(length).also { generator.generateBytes(password.toByteArray(Charsets.UTF_8), it) }
    }

    private class Parsed(
        val settings: Argon2Settings,
        val salt: ByteArray,
        val hash: ByteArray,
    )

    private fun parse(encoded: String): Parsed? {
        val parts = encoded.split("$")
        if (parts.size != PHC_PARTS || parts[1] != "argon2id" || parts[2] != "v=19") return null
        val values = parts[3].split(",").associate { it.substringBefore("=") to it.substringAfter("=").toIntOrNull() }
        val memory = values["m"] ?: return null
        val iterations = values["t"] ?: return null
        val parallelism = values["p"] ?: return null
        return runCatching {
            Parsed(Argon2Settings(memory, iterations, parallelism), decoder.decode(parts[4]), decoder.decode(parts[5]))
        }.getOrNull()
    }

    private companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        const val PHC_PARTS = 6
    }
}
