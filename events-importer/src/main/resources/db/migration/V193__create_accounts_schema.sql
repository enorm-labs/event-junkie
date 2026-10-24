-- V193 — the `accounts` schema for visitor sign-in, and the `bff` role that may write it (#3887, ADR-053).
--
-- The BFF faces the internet, so it gets its own role: read on `events`, read and write on `accounts`,
-- nothing else. A stolen BFF credential then cannot change event data.
--
-- The `events` role on the clusters cannot CREATE ROLE, so an operator creates `bff` first
-- (docs/ops/SECRETS.md §events-bff-db). Where this runs as a superuser — tests, compose, k3d, DAST — the
-- block creates it NOLOGIN, because only the grants matter there. Anywhere else a missing role stops the
-- migration with that pointer, instead of granting to nobody.
--
-- Qualified names here, unlike the other migrations: Flyway's `search_path` is `events` (ADR-004).
-- No sequences: the keys are a UUID and two token hashes, so `bff` needs no USAGE on one.

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'bff') THEN
            IF (SELECT rolsuper OR rolcreaterole FROM pg_roles WHERE rolname = current_user) THEN
                CREATE ROLE bff NOLOGIN;
            ELSE
                RAISE EXCEPTION 'The role bff does not exist. Create it as the postgres superuser first: docs/ops/SECRETS.md §events-bff-db';
            END IF;
        END IF;
    END
$$;

CREATE SCHEMA accounts;

CREATE TABLE accounts.account
(
    id              UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    -- As typed at the first sign-in. Unique without regard to case, by the index below.
    email           TEXT        NOT NULL,
    -- The language of every mail to this account, `de` or `en`.
    locale          TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Drives the deletion of inactive accounts the privacy notice promises.
    last_sign_in_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX account_email_key ON accounts.account (lower(email));

-- A sign-in link. Only the SHA-256 of the token is stored, so a database read cannot sign anyone in.
-- The row outlives its use: the rate limit counts the links an address asked for in the last hour.
CREATE TABLE accounts.one_time_token
(
    token_hash  TEXT PRIMARY KEY,
    email       TEXT        NOT NULL,
    locale      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

CREATE INDEX one_time_token_email_created_idx ON accounts.one_time_token (lower(email), created_at);
CREATE INDEX one_time_token_expires_idx ON accounts.one_time_token (expires_at);

-- A signed-in browser. The cookie holds the token, this table its SHA-256.
CREATE TABLE accounts.session
(
    token_hash   TEXT PRIMARY KEY,
    account_id   UUID        NOT NULL REFERENCES accounts.account (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX session_account_idx ON accounts.session (account_id);
CREATE INDEX session_expires_idx ON accounts.session (expires_at);

-- Read on `events`, now and for every table a later migration adds. The default privileges apply to
-- tables this role creates, which is every migration's role.
GRANT USAGE ON SCHEMA events TO bff;
GRANT SELECT ON ALL TABLES IN SCHEMA events TO bff;
ALTER DEFAULT PRIVILEGES IN SCHEMA events GRANT SELECT ON TABLES TO bff;

GRANT USAGE ON SCHEMA accounts TO bff;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA accounts TO bff;
ALTER DEFAULT PRIVILEGES IN SCHEMA accounts GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO bff;
