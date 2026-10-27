-- V204 — Passkeys (#3888, ADR-053 rule 7): the credentials an account signs in with, and the challenges in flight.
--
-- `credential` is the attested credential data as webauthn4j encodes it: the credential id and the COSE public key,
-- with the AAGUID zeroed, so the row does not say which authenticator model made it. The private key never leaves
-- the visitor's device. `sign_count` and the three flags are what the next verification compares against.
--
-- `passkey_user_handle` is the user id the authenticator stores with the passkey: 32 random bytes, set at the first
-- passkey. Never the account id or the address, because the authenticator keeps it and may sync it. No UNIQUE: a
-- sign-in finds the account by the credential id, and 32 random bytes do not collide.
--
-- A challenge is single use and lives five minutes. It is in the database, not in memory, because the BFF runs two
-- replicas. `account_id` is set for a registration and NULL for a sign-in, so one cannot stand in for the other.
--
-- Qualified names: Flyway's `search_path` is `events` (ADR-004).

ALTER TABLE accounts.account
    ADD COLUMN passkey_user_handle BYTEA;

CREATE TABLE accounts.passkey
(
    id              UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    account_id      UUID        NOT NULL REFERENCES accounts.account (id) ON DELETE CASCADE,
    credential_id   BYTEA       NOT NULL UNIQUE,
    credential      BYTEA       NOT NULL,
    sign_count      BIGINT      NOT NULL,
    uv_initialized  BOOLEAN,
    backup_eligible BOOLEAN,
    backup_state    BOOLEAN,
    transports      TEXT[]      NOT NULL DEFAULT '{}',
    name            TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ
);

CREATE INDEX passkey_account_idx ON accounts.passkey (account_id);

CREATE TABLE accounts.passkey_challenge
(
    challenge  BYTEA PRIMARY KEY,
    account_id UUID REFERENCES accounts.account (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
