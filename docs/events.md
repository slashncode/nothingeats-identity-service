# Events: `identity.user.v1`

CloudEvents in structured mode (`application/cloudevents+json`), `source` = `/nothingeats/identity`,
record key and `subject` = the user id (ordering per user). Consumers must ignore unknown fields.

The payloads contain the email address (and, for registrations, the verification token) because the
notification service needs them to send mails. Keep the topic's retention short.

| `type` | When | `data` |
|---|---|---|
| `nothingeats.identity.user.registered.v1` | A new account was created | `userId`, `email`, `locale` (`de`/`en`), `verificationToken` |
| `nothingeats.identity.user.registration-attempted.v1` | Someone registered with the address of an existing account | `userId`, `email`, `locale` |
| `nothingeats.identity.user.verified.v1` | The address was confirmed; the account is active | `userId`, `email`, `locale` |

The verification link is `<web origin>/verify?token=<verificationToken>`; the web app posts the token to
`POST /api/identity/v1/verifications`.
