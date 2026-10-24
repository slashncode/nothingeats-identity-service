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
| `GET` | `/.well-known/jwks.json` | – | Public signing keys (current + next + recently retired) |

Errors are RFC 9457 problems with stable codes, e.g. `INVALID_CREDENTIALS`, `EMAIL_NOT_VERIFIED`, `REFRESH_TOKEN_REUSED`.

## Security design

- **Passwords**: Argon2id (19 MiB, t=2, p=1) in PHC format, rehashed on login when parameters change. Length-only policy, 10–128 characters (NIST SP 800-63B).
- **No account enumeration**: registration always answers `202` and always hashes; login checks a dummy hash for unknown addresses and answers `INVALID_CREDENTIALS` for both cases.
- **Access tokens**: RS256, 15 minutes, `iss`/`aud`/`sub`/`roles`/`locale`, verified by every service via JWKS.
- **Refresh tokens**: 256-bit opaque, stored as SHA-256, `httpOnly; SameSite=Strict` cookie scoped to `/api/identity/v1/sessions`, rotated on every use. Presenting a used token revokes the whole token family.
- **Signing keys**: RSA-2048 in Postgres, private keys AES-256-GCM encrypted with `KEYS__ENCRYPTION_KEY` (key id as authenticated data). Always a current and a next key; `cron rotate-signing-keys` retires, promotes and creates; retired keys stay published for an hour.
- **Events** through the transactional outbox: see [docs/events.md](docs/events.md).

## Run

```bash
./gradlew build    # tests need Docker (Postgres + Kafka via Testcontainers)

export DATABASE__URL=jdbc:postgresql://localhost:15432/identity DATABASE__USER=identity DATABASE__PASSWORD=...
export KEYS__ENCRYPTION_KEY=$(openssl rand -base64 32)
./gradlew :app:run --args=migrate && ./gradlew :app:run
```

In the k3d cluster: `just dev identity-service` from the hub repo.

## License

MIT
