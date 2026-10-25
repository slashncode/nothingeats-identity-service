# nothingeats-identity-service

Identity & access for [NothingEats](https://github.com/slashncode/nothingeats): accounts, email verification, login, tokens and the JWKS every other service verifies against.

## API (`/api/identity`)

| Method | Path | Auth | What |
|---|---|---|---|
| `POST` | `/v1/registrations` | – | `{email, password, locale?}` → `202`. Same answer whether the address exists or not; the owner of an existing account gets a mail instead. |
| `POST` | `/v1/verifications` | – | `{token}` from the mail → `204`; the account becomes active |
| `POST` | `/v1/sessions` | – | `{email, password}` → access token in the body, refresh token as `ne_refresh` cookie |
| `POST` | `/v1/sessions/refresh` | cookie | New access token, rotated refresh cookie |
| `DELETE` | `/v1/sessions/current` | cookie | Revokes the refresh token family, clears the cookie |
| `GET` | `/v1/me` | Bearer | Profile of the caller |
| `POST` | `/v1/magic-links` | – | `{email}` → `202`; owners get a 15-minute login link |
| `POST` | `/v1/magic-links/sessions` | – | `{token}` → session like `/v1/sessions`; also confirms the address |
| `GET` | `/v1/me/export` | Bearer | Everything identity holds about the account, as a JSON download (GDPR art. 15/20) |
| `PUT` | `/v1/me/consent` | Bearer | `{analytics}`: the product-analytics decision, kept with the account |
| `POST` | `/v1/me/deletion` | Bearer | `{password}` → `204`; deletes the account (GDPR art. 17), see below |
| `POST` | `/v1/password-resets` | – | `{email}` → `202`, same answer for every address; owners get a one-hour link |
| `POST` | `/v1/password-resets/confirmations` | – | `{token, password}` → `204`; ends every session of the account, mails "password changed" |
| `GET` | `/v1/admin/users?email=` | role `admin` | Look up an account |
| `PUT`/`DELETE` | `/v1/admin/users/{id}/roles/{role}` | role `admin` | Grant or revoke a role (not your own `admin`); effective with the next access token |
| `GET` | `/.well-known/jwks.json` | – | Public signing keys (current + next + recently retired) |

Errors are RFC 9457 problems with stable codes, e.g. `INVALID_CREDENTIALS`, `EMAIL_NOT_VERIFIED`, `REFRESH_TOKEN_REUSED`, `TOO_MANY_ATTEMPTS` (429 with `Retry-After`).

## Security design

- **Passwords**: Argon2id (19 MiB, t=2, p=1) in PHC format, rehashed on login when parameters change. Length-only policy, 10–128 characters (NIST SP 800-63B).
- **No account enumeration**: registration always answers `202` and always hashes; login checks a dummy hash for unknown addresses and answers `INVALID_CREDENTIALS` for both cases.
- **Access tokens**: RS256, 15 minutes, `iss`/`aud`/`sub`/`roles`/`locale`, verified by every service via JWKS.
- **Refresh tokens**: 256-bit opaque, stored as SHA-256, `httpOnly; SameSite=Strict` cookie scoped to `/api/identity/v1/sessions`, rotated on every use. Presenting a used token revokes the whole token family.
- **Signing keys**: RSA-2048 in Postgres, private keys AES-256-GCM encrypted with `KEYS__ENCRYPTION_KEY` (key id as authenticated data). Always a current and a next key; `cron rotate-signing-keys` retires, promotes and creates; retired keys stay published for an hour.
- **Throttling** in Valkey (fixed windows, hashed keys, failing open): 30 logins per client address and 15 minutes, 10 failed logins per address (then locked for the window, even for the right password, so a 429 is no guessing oracle), 10 registrations and 10 reset requests per client and hour, 3 reset mails per address and hour. The client address is the one Traefik vouches for (last `X-Forwarded-For` entry).
- **Password reset**: one-hour single-use link (hash stored), confirming it proves the address, ends every session and mails the owner.
- **Account deletion**: password confirmed again (limited like logins), then in one transaction the user row (tokens cascade), a tombstone with only the id, and `user.deleted` (id only) for every service. `cron erase-analytics` erases the person, events and recordings in PostHog and keeps the tombstone until PostHog confirmed; without a personal API key (`POSTHOG__PERSONAL_API_KEY`, scope `person:write`) the tombstones wait.
- **Admins**: addresses in `ADMIN__EMAILS` become admins when they verify or log in; admins grant further roles through the admin API.
- **Events** through the transactional outbox: see [docs/events.md](docs/events.md).

## Run

```bash
./gradlew build    # tests need Docker (Postgres + Kafka via Testcontainers)

export DATABASE__URL=jdbc:postgresql://localhost:15432/identity DATABASE__USER=identity DATABASE__PASSWORD=...
export KEYS__ENCRYPTION_KEY=$(openssl rand -base64 32)
export VALKEY__PASSWORD=...   # the local cluster's Valkey on localhost:16379
./gradlew :app:run --args=migrate && ./gradlew :app:run
```

In the k3d cluster: `just dev identity-service` from the hub repo.

## License

MIT
