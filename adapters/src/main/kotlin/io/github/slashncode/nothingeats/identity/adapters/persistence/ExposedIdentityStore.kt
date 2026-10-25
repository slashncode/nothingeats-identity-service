package io.github.slashncode.nothingeats.identity.adapters.persistence

import io.github.slashncode.nothingeats.identity.application.EmailVerifications
import io.github.slashncode.nothingeats.identity.application.Erasures
import io.github.slashncode.nothingeats.identity.application.MagicLinks
import io.github.slashncode.nothingeats.identity.application.PasswordResets
import io.github.slashncode.nothingeats.identity.application.RefreshTokens
import io.github.slashncode.nothingeats.identity.application.Transactions
import io.github.slashncode.nothingeats.identity.application.Users
import io.github.slashncode.nothingeats.identity.domain.Email
import io.github.slashncode.nothingeats.identity.domain.EmailAlreadyRegistered
import io.github.slashncode.nothingeats.identity.domain.EmailVerification
import io.github.slashncode.nothingeats.identity.domain.Locale
import io.github.slashncode.nothingeats.identity.domain.MagicLink
import io.github.slashncode.nothingeats.identity.domain.PasswordReset
import io.github.slashncode.nothingeats.identity.domain.RefreshToken
import io.github.slashncode.nothingeats.identity.domain.Role
import io.github.slashncode.nothingeats.identity.domain.User
import io.github.slashncode.nothingeats.identity.domain.UserId
import io.github.slashncode.nothingeats.identity.domain.UserStatus
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.persistence.isUniqueViolation
import io.github.slashncode.nothingeats.platform.persistence.violatedConstraint
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.uuid.Uuid

internal object UsersTable : Table("users") {
    val id = uuid("id")
    val email = text("email")
    val passwordHash = text("password_hash")
    val locale = text("locale")
    val roles = text("roles")
    val status = text("status")
    val createdAt = timestamp("created_at")
    val verifiedAt = timestamp("verified_at").nullable()
    val analyticsConsent = bool("analytics_consent").nullable()
    val consentUpdatedAt = timestamp("consent_updated_at").nullable()

    override val primaryKey = PrimaryKey(id)

    const val EMAIL_UNIQUE = "users_email_key"
}

internal object MagicLinksTable : Table("magic_links") {
    val tokenHash = text("token_hash")
    val userId = uuid("user_id")
    val expiresAt = timestamp("expires_at")
    val usedAt = timestamp("used_at").nullable()

    override val primaryKey = PrimaryKey(tokenHash)
}

internal object ErasuresTable : Table("erasures") {
    val userId = uuid("user_id")
    val deletedAt = timestamp("deleted_at")
    val analyticsErasedAt = timestamp("analytics_erased_at").nullable()

    override val primaryKey = PrimaryKey(userId)
}

internal object EmailVerificationsTable : Table("email_verifications") {
    val tokenHash = text("token_hash")
    val userId = uuid("user_id")
    val expiresAt = timestamp("expires_at")
    val usedAt = timestamp("used_at").nullable()

    override val primaryKey = PrimaryKey(tokenHash)
}

internal object PasswordResetsTable : Table("password_resets") {
    val tokenHash = text("token_hash")
    val userId = uuid("user_id")
    val expiresAt = timestamp("expires_at")
    val usedAt = timestamp("used_at").nullable()

    override val primaryKey = PrimaryKey(tokenHash)
}

internal object RefreshTokensTable : Table("refresh_tokens") {
    val id = uuid("id")
    val tokenHash = text("token_hash")
    val familyId = uuid("family_id")
    val userId = uuid("user_id")
    val expiresAt = timestamp("expires_at")
    val usedAt = timestamp("used_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

class ExposedTransactions(
    private val db: PostgresDatabase,
) : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = db.transaction { block() }
}

class ExposedUsers(
    private val db: PostgresDatabase,
) : Users {
    override suspend fun add(user: User) {
        try {
            db.transaction { UsersTable.insert { it.fill(user) } }
        } catch (e: Exception) {
            if (e.isUniqueViolation() && e.violatedConstraint == UsersTable.EMAIL_UNIQUE) throw EmailAlreadyRegistered(user.email, e)
            throw e
        }
    }

    override suspend fun save(user: User) {
        db.transaction { UsersTable.update({ UsersTable.id eq user.id.value }) { it.fill(user) } }
    }

    override suspend fun findById(id: UserId): User? =
        db.transaction(readOnly = true) {
            UsersTable
                .selectAll()
                .where { UsersTable.id eq id.value }
                .singleOrNull()
                ?.toUser()
        }

    override suspend fun findByEmail(email: Email): User? =
        db.transaction(readOnly = true) {
            UsersTable
                .selectAll()
                .where { UsersTable.email eq email.value }
                .singleOrNull()
                ?.toUser()
        }

    override suspend fun delete(id: UserId) {
        db.transaction { UsersTable.deleteWhere { UsersTable.id eq id.value } }
    }

    private fun UpdateBuilder<*>.fill(user: User) {
        this[UsersTable.id] = user.id.value
        this[UsersTable.email] = user.email.value
        this[UsersTable.passwordHash] = user.passwordHash
        this[UsersTable.locale] = user.locale.tag
        this[UsersTable.roles] = user.roles.joinToString(",") { it.claim }
        this[UsersTable.status] = user.status.name
        this[UsersTable.createdAt] = user.createdAt
        this[UsersTable.verifiedAt] = user.verifiedAt
        this[UsersTable.analyticsConsent] = user.analyticsConsent
        this[UsersTable.consentUpdatedAt] = user.consentUpdatedAt
    }

    private fun ResultRow.toUser() =
        User(
            id = UserId(this[UsersTable.id]),
            email = Email.parse(this[UsersTable.email]),
            passwordHash = this[UsersTable.passwordHash],
            locale = Locale.parse(this[UsersTable.locale]),
            roles =
                this[UsersTable.roles]
                    .split(",")
                    .mapNotNull { claim -> Role.entries.firstOrNull { it.claim == claim } }
                    .toSet(),
            status = UserStatus.valueOf(this[UsersTable.status]),
            createdAt = this[UsersTable.createdAt],
            verifiedAt = this[UsersTable.verifiedAt],
            analyticsConsent = this[UsersTable.analyticsConsent],
            consentUpdatedAt = this[UsersTable.consentUpdatedAt],
        )
}

class ExposedEmailVerifications(
    private val db: PostgresDatabase,
) : EmailVerifications {
    override suspend fun add(verification: EmailVerification) {
        db.transaction {
            EmailVerificationsTable.insert {
                it[tokenHash] = verification.tokenHash
                it[userId] = verification.userId.value
                it[expiresAt] = verification.expiresAt
                it[usedAt] = verification.usedAt
            }
        }
    }

    override suspend fun find(tokenHash: String): EmailVerification? =
        db.transaction(readOnly = true) {
            EmailVerificationsTable
                .selectAll()
                .where { EmailVerificationsTable.tokenHash eq tokenHash }
                .singleOrNull()
                ?.let {
                    EmailVerification(
                        tokenHash = it[EmailVerificationsTable.tokenHash],
                        userId = UserId(it[EmailVerificationsTable.userId]),
                        expiresAt = it[EmailVerificationsTable.expiresAt],
                        usedAt = it[EmailVerificationsTable.usedAt],
                    )
                }
        }

    override suspend fun markUsed(
        tokenHash: String,
        now: Instant,
    ) {
        db.transaction {
            EmailVerificationsTable.update({ EmailVerificationsTable.tokenHash eq tokenHash }) { it[usedAt] = now }
        }
    }
}

class ExposedPasswordResets(
    private val db: PostgresDatabase,
) : PasswordResets {
    override suspend fun add(reset: PasswordReset) {
        db.transaction {
            PasswordResetsTable.insert {
                it[tokenHash] = reset.tokenHash
                it[userId] = reset.userId.value
                it[expiresAt] = reset.expiresAt
                it[usedAt] = reset.usedAt
            }
        }
    }

    override suspend fun find(tokenHash: String): PasswordReset? =
        db.transaction(readOnly = true) {
            PasswordResetsTable
                .selectAll()
                .where { PasswordResetsTable.tokenHash eq tokenHash }
                .singleOrNull()
                ?.let {
                    PasswordReset(
                        tokenHash = it[PasswordResetsTable.tokenHash],
                        userId = UserId(it[PasswordResetsTable.userId]),
                        expiresAt = it[PasswordResetsTable.expiresAt],
                        usedAt = it[PasswordResetsTable.usedAt],
                    )
                }
        }

    // Conditional, like refresh tokens: of two confirmations with the same link only one wins.
    override suspend fun markUsed(
        tokenHash: String,
        now: Instant,
    ): Boolean =
        db.transaction {
            PasswordResetsTable.update({ (PasswordResetsTable.tokenHash eq tokenHash) and PasswordResetsTable.usedAt.isNull() }) {
                it[usedAt] = now
            } == 1
        }
}

class ExposedMagicLinks(
    private val db: PostgresDatabase,
) : MagicLinks {
    override suspend fun add(link: MagicLink) {
        db.transaction {
            MagicLinksTable.insert {
                it[tokenHash] = link.tokenHash
                it[userId] = link.userId.value
                it[expiresAt] = link.expiresAt
                it[usedAt] = link.usedAt
            }
        }
    }

    override suspend fun find(tokenHash: String): MagicLink? =
        db.transaction(readOnly = true) {
            MagicLinksTable
                .selectAll()
                .where { MagicLinksTable.tokenHash eq tokenHash }
                .singleOrNull()
                ?.let {
                    MagicLink(
                        tokenHash = it[MagicLinksTable.tokenHash],
                        userId = UserId(it[MagicLinksTable.userId]),
                        expiresAt = it[MagicLinksTable.expiresAt],
                        usedAt = it[MagicLinksTable.usedAt],
                    )
                }
        }

    override suspend fun markUsed(
        tokenHash: String,
        now: Instant,
    ): Boolean =
        db.transaction {
            MagicLinksTable.update({ (MagicLinksTable.tokenHash eq tokenHash) and MagicLinksTable.usedAt.isNull() }) {
                it[usedAt] = now
            } == 1
        }
}

class ExposedErasures(
    private val db: PostgresDatabase,
) : Erasures {
    override suspend fun add(
        userId: UserId,
        now: Instant,
    ) {
        db.transaction {
            ErasuresTable.insert {
                it[ErasuresTable.userId] = userId.value
                it[deletedAt] = now
            }
        }
    }

    override suspend fun pendingAnalytics(limit: Int): List<UserId> =
        db.transaction(readOnly = true) {
            ErasuresTable
                .selectAll()
                .where { ErasuresTable.analyticsErasedAt.isNull() }
                .orderBy(ErasuresTable.deletedAt)
                .limit(limit)
                .map { UserId(it[ErasuresTable.userId]) }
        }

    override suspend fun markAnalyticsErased(
        userId: UserId,
        now: Instant,
    ) {
        db.transaction {
            ErasuresTable.update({ ErasuresTable.userId eq userId.value }) { it[analyticsErasedAt] = now }
        }
    }
}

class ExposedRefreshTokens(
    private val db: PostgresDatabase,
) : RefreshTokens {
    override suspend fun add(token: RefreshToken) {
        db.transaction {
            RefreshTokensTable.insert {
                it[id] = token.id
                it[tokenHash] = token.tokenHash
                it[familyId] = token.familyId
                it[userId] = token.userId.value
                it[expiresAt] = token.expiresAt
                it[usedAt] = token.usedAt
                it[revokedAt] = token.revokedAt
            }
        }
    }

    override suspend fun find(tokenHash: String): RefreshToken? =
        db.transaction(readOnly = true) {
            RefreshTokensTable
                .selectAll()
                .where { RefreshTokensTable.tokenHash eq tokenHash }
                .singleOrNull()
                ?.let {
                    RefreshToken(
                        id = it[RefreshTokensTable.id],
                        tokenHash = it[RefreshTokensTable.tokenHash],
                        familyId = it[RefreshTokensTable.familyId],
                        userId = UserId(it[RefreshTokensTable.userId]),
                        expiresAt = it[RefreshTokensTable.expiresAt],
                        usedAt = it[RefreshTokensTable.usedAt],
                        revokedAt = it[RefreshTokensTable.revokedAt],
                    )
                }
        }

    // A conditional update: of two concurrent refreshes with the same token only one wins.
    override suspend fun markUsed(
        id: Uuid,
        now: Instant,
    ): Boolean =
        db.transaction {
            RefreshTokensTable.update({
                (RefreshTokensTable.id eq id) and RefreshTokensTable.usedAt.isNull() and RefreshTokensTable.revokedAt.isNull()
            }) { it[usedAt] = now } == 1
        }

    override suspend fun revokeFamily(
        familyId: Uuid,
        now: Instant,
    ) {
        db.transaction {
            RefreshTokensTable.update({ (RefreshTokensTable.familyId eq familyId) and RefreshTokensTable.revokedAt.isNull() }) {
                it[revokedAt] = now
            }
        }
    }

    override suspend fun revokeAll(
        userId: UserId,
        now: Instant,
    ) {
        db.transaction {
            RefreshTokensTable.update({ (RefreshTokensTable.userId eq userId.value) and RefreshTokensTable.revokedAt.isNull() }) {
                it[revokedAt] = now
            }
        }
    }

    override suspend fun countActive(
        userId: UserId,
        now: Instant,
    ): Int =
        db.transaction(readOnly = true) {
            RefreshTokensTable
                .select(RefreshTokensTable.familyId)
                .where {
                    (RefreshTokensTable.userId eq userId.value) and RefreshTokensTable.usedAt.isNull() and
                        RefreshTokensTable.revokedAt.isNull() and (RefreshTokensTable.expiresAt greater now)
                }.withDistinct()
                .count()
                .toInt()
        }
}
