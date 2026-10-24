package io.github.slashncode.nothingeats.identity.adapters.messaging

import io.github.slashncode.nothingeats.identity.application.UserEvents
import io.github.slashncode.nothingeats.identity.domain.User
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
}

@Serializable
data class UserRegisteredV1(
    val userId: String,
    val email: String,
    val locale: String,
    val verificationToken: String,
)

@Serializable
data class RegistrationAttemptedV1(
    val userId: String,
    val email: String,
    val locale: String,
)

@Serializable
data class UserVerifiedV1(
    val userId: String,
    val email: String,
    val locale: String,
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
                UserRegisteredV1(user.id.toString(), user.email.value, user.locale.tag, verificationToken),
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
                RegistrationAttemptedV1(existing.id.toString(), existing.email.value, existing.locale.tag),
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
                UserVerifiedV1(user.id.toString(), user.email.value, user.locale.tag),
                UserVerifiedV1.serializer(),
            )
        }
    }
}
