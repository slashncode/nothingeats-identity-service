package io.github.slashncode.nothingeats.identity.adapters.crypto

import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.platform.kafka.KafkaMigrations
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import java.security.SecureRandom
import java.util.Base64

private fun aesKey() = Secret(Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }))

class CryptoTest :
    FunSpec({
        val hasher = Argon2PasswordHasher()

        test("Argon2id hashes are salted PHC strings that verify the right password only") {
            val first = hasher.hash("correct horse battery")
            val second = hasher.hash("correct horse battery")

            first shouldStartWith "\$argon2id\$v=19\$m=19456,t=2,p=1\$"
            first shouldNotBe second
            hasher.verify("correct horse battery", first).matches shouldBe true
            hasher.verify("correct horse battery!", first).matches shouldBe false
            hasher.verify("anything", "not a phc string").matches shouldBe false
        }

        test("hashes with other parameters still verify but ask for a rehash") {
            val old = Argon2PasswordHasher(Argon2Settings(memoryKib = 8_192, iterations = 1)).hash("correct horse battery")

            val check = hasher.verify("correct horse battery", old)

            check.matches shouldBe true
            check.needsRehash shouldBe true
        }

        test("private keys cannot be read with another encryption key") {
            val config = PostgresTestDatabases.create()
            val migrations = FlywayMigrations(config, listOf(KafkaMigrations, MigrationSet.SERVICE))
            migrations.migrate()
            val database = PostgresDatabase(config, migrations).also { it.start() }
            RsaKeyStore(database, KeysConfig(aesKey())).start()

            shouldThrowAny { RsaKeyStore(database, KeysConfig(aesKey())).start() }
            database.stop()
        }
    })
