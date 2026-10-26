-- V200 — whether new accounts can be created (#3906): the switch an operator flips when someone scripts the
-- request for a sign-in link. Closed, the BFF sends links only to addresses that already have an account.
--
-- One row, enforced by the boolean key. The importer's admin API writes it; `bff` only reads it, so the
-- default privileges from V193 are narrowed here.

CREATE TABLE accounts.sign_up
(
    id         BOOLEAN PRIMARY KEY  DEFAULT true CHECK (id),
    open       BOOLEAN     NOT NULL DEFAULT true,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO accounts.sign_up DEFAULT VALUES;

REVOKE INSERT, UPDATE, DELETE ON accounts.sign_up FROM bff;
