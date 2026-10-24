-- V194 — when an inactive account was warned that it will be deleted (#3905).
--
-- The BFF's daily cleanup sets it as it claims an account for the warning mail, and deletes the account
-- only once the warning is a month old. A sign-in clears it. NULL means no warning is pending.

ALTER TABLE accounts.account ADD COLUMN deletion_warned_at TIMESTAMPTZ;

CREATE INDEX account_last_sign_in_idx ON accounts.account (last_sign_in_at);
