package io.github.slashncode.nothingeats.identity.adapters.messaging

import io.github.slashncode.nothingeats.identity.application.UserEvents
import io.github.slashncode.nothingeats.identity.domain.User
import io.github.slashncode.nothingeats.identity.domain.UserId
import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.kafka.Outbox
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import kotlinx.serialization.Serializable

/**
 * The `identity.user.v1` topic. Contract: docs/events.md. Payloads carry what the mails need;
 * the topic's retention is short because they contain the address and, for registrations, the token.
 */
object UserTopic {
    const val NAME = "identity.user.v1"
    const val REGISTERED = "nothingeats.identity.user.registered.v1"
    const val REGISTRATION_ATTEMPTED = "nothingeats.identity.user.registration-attempted.v1"
    const val VERIFIED = "nothingeats.identity.user.verified.v1"
    const val PASSWORD_RESET_REQUESTED = "nothingeats.identity.user.password-reset-requested.v1"
    const val PASSWORD_CHANGED = "nothingeats.identity.user.password-changed.v1"
    const val MAGIC_LINK_REQUESTED = "nothingeats.identity.user.magic-link-requested.v1"
    const val DELETED = "nothingeats.identity.user.deleted.v1"
}

@Serializable
data class UserRegisteredV1(
    val userId: String,
    val email: String,
    val locale: String,
    val verificationToken: String,
    /** Whether the request came with analytics consent: consumers send server-side analytics only then. */
    val analytics: Boolean,
)

@Serializable
data class RegistrationAttemptedV1(
    val userId: String,
    val email: String,
    val locale: String,
    /** Whether the request came with analytics consent: consumers send server-side analytics only then. */
    val analytics: Boolean,
)

@Serializable
data class UserVerifiedV1(
    val userId: String,
    val email: String,
    val locale: String,
    /** Whether the request came with analytics consent: consumers send server-side analytics only then. */
    val analytics: Boolean,
)

@Serializable
data class PasswordResetRequestedV1(
    val userId: String,
    val email: String,
    val locale: String,
    val resetToken: String,
    /** Whether the request came with analytics consent: consumers send server-side analytics only then. */
    val analytics: Boolean,
)

@Serializable
data class PasswordChangedV1(
    val userId: String,
    val email: String,
    val locale: String,
    /** Whether the request came with analytics consent: consumers send server-side analytics only then. */
    val analytics: Boolean,
)

@Serializable
data class MagicLinkRequestedV1(
    val userId: String,
    val email: String,
    val locale: String,
    val magicToken: String,
    /** Whether the request came with analytics consent: consumers send server-side analytics only then. */
    val analytics: Boolean,
)

/** Only the id: consumers erase what they hold about it; the address is already gone. */
@Serializable
data class UserDeletedV1(
    val userId: String,
)

/** Writes the events to the outbox, inside the transaction of the use case that raised them. */
class OutboxUserEvents(
    private val outbox: Outbox,
    private val db: PostgresDatabase,
) : UserEvents {
    override suspend fun registered(
        user: User,
        verificationToken: String,
    ) {
        db.transaction {
            outbox.enqueue(
                UserTopic.NAME,
                user.id.toString(),
                UserTopic.REGISTERED,
                UserRegisteredV1(user.id.toString(), user.email.value, user.locale.tag, verificationToken, AnalyticsContext.consented()),
                UserRegisteredV1.serializer(),
            )
        }
    }

    override suspend fun registrationAttempted(existing: User) {
        db.transaction {
            outbox.enqueue(
                UserTopic.NAME,
                existing.id.toString(),
                UserTopic.REGISTRATION_ATTEMPTED,
                RegistrationAttemptedV1(existing.id.toString(), existing.email.value, existing.locale.tag, AnalyticsContext.consented()),
                RegistrationAttemptedV1.serializer(),
            )
        }
    }

    override suspend fun verified(user: User) {
        db.transaction {
            outbox.enqueue(
                UserTopic.NAME,
                user.id.toString(),
                UserTopic.VERIFIED,
                UserVerifiedV1(user.id.toString(), user.email.value, user.locale.tag, AnalyticsContext.consented()),
                UserVerifiedV1.serializer(),
            )
        }
    }

    override suspend fun passwordResetRequested(
        user: User,
        resetToken: String,
    ) {
        db.transaction {
            outbox.enqueue(
                UserTopic.NAME,
                user.id.toString(),
                UserTopic.PASSWORD_RESET_REQUESTED,
                PasswordResetRequestedV1(user.id.toString(), user.email.value, user.locale.tag, resetToken, AnalyticsContext.consented()),
                PasswordResetRequestedV1.serializer(),
            )
        }
    }

    override suspend fun passwordChanged(user: User) {
        db.transaction {
            outbox.enqueue(
                UserTopic.NAME,
                user.id.toString(),
                UserTopic.PASSWORD_CHANGED,
                PasswordChangedV1(user.id.toString(), user.email.value, user.locale.tag, AnalyticsContext.consented()),
                PasswordChangedV1.serializer(),
            )
        }
    }

    override suspend fun magicLinkRequested(
        user: User,
        token: String,
    ) {
        db.transaction {
            outbox.enqueue(
                UserTopic.NAME,
                user.id.toString(),
                UserTopic.MAGIC_LINK_REQUESTED,
                MagicLinkRequestedV1(user.id.toString(), user.email.value, user.locale.tag, token, AnalyticsContext.consented()),
                MagicLinkRequestedV1.serializer(),
            )
        }
    }

    override suspend fun deleted(userId: UserId) {
        db.transaction {
            outbox.enqueue(
                UserTopic.NAME,
                userId.toString(),
                UserTopic.DELETED,
                UserDeletedV1(userId.toString()),
                UserDeletedV1.serializer(),
            )
        }
    }
}
