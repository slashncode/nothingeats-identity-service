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
| `nothingeats.identity.user.password-reset-requested.v1` | Someone asked for a new password for an existing account | `userId`, `email`, `locale`, `resetToken` |
| `nothingeats.identity.user.password-changed.v1` | The password was changed with a reset link; all sessions ended | `userId`, `email`, `locale` |
| `nothingeats.identity.user.magic-link-requested.v1` | Someone asked for a login link for an existing account | `userId`, `email`, `locale`, `magicToken` |
| `nothingeats.identity.user.deleted.v1` | The account was deleted (GDPR) | `userId` only |

**`user.deleted` is an obligation for every consumer**: erase or anonymise everything held under that
`userId`, idempotently. It carries no address on purpose; identity no longer has one. Older events on
this topic still contain the address until the 7-day retention removes them.

The verification link is `<web origin>/verify?token=<verificationToken>`; the web app posts the token to
`POST /api/identity/v1/verifications`. The reset link is `<web origin>/reset-password?token=<resetToken>`
(valid one hour); the web app posts it with the new password to `POST /api/identity/v1/password-resets/confirmations`.
The login link is `<web origin>/magic-login?token=<magicToken>` (valid 15 minutes); the web app posts it to
`POST /api/identity/v1/magic-links/sessions`.
