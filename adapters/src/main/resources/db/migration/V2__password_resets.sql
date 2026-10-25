-- One-time links to choose a new password. Only hashes of the mailed tokens are stored.
create table password_resets
(
    token_hash text primary key,
    user_id    uuid        not null references users (id) on delete cascade,
    expires_at timestamptz not null,
    used_at    timestamptz
);

create index refresh_tokens_user on refresh_tokens (user_id);
