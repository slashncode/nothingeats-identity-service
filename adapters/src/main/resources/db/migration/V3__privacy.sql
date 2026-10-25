-- Analytics consent, decided in the web app and kept with the account.
alter table users
    add column analytics_consent  boolean,
    add column consent_updated_at timestamptz;

-- One-time login links (passwordless). Only hashes of the mailed tokens are stored.
create table magic_links
(
    token_hash text primary key,
    user_id    uuid        not null references users (id) on delete cascade,
    expires_at timestamptz not null,
    used_at    timestamptz
);

-- A deleted account leaves nothing but its id here, until every erasure outside this database
-- (product analytics) is done. No address, no name: the id is all the erasure needs.
create table erasures
(
    user_id             uuid primary key,
    deleted_at          timestamptz not null,
    analytics_erased_at timestamptz
);
