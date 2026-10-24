create table users
(
    id            uuid primary key,
    email         text        not null constraint users_email_key unique,
    password_hash text        not null,
    locale        text        not null,
    roles         text        not null,
    status        text        not null,
    created_at    timestamptz not null,
    verified_at   timestamptz
);

-- Only hashes of the mailed tokens are stored.
create table email_verifications
(
    token_hash text primary key,
    user_id    uuid        not null references users (id) on delete cascade,
    expires_at timestamptz not null,
    used_at    timestamptz
);

create table refresh_tokens
(
    id         uuid primary key,
    token_hash text        not null constraint refresh_tokens_token_hash_key unique,
    family_id  uuid        not null,
    user_id    uuid        not null references users (id) on delete cascade,
    expires_at timestamptz not null,
    used_at    timestamptz,
    revoked_at timestamptz
);

create index refresh_tokens_family on refresh_tokens (family_id);

-- RS256 signing keys. Private keys are AES-GCM encrypted with a key that is not in the database.
create table signing_keys
(
    kid          text primary key,
    status       text        not null,
    private_key  bytea       not null,
    modulus      text        not null,
    exponent     text        not null,
    created_at   timestamptz not null,
    activated_at timestamptz,
    retired_at   timestamptz
);
