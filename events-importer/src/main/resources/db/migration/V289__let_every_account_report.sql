-- V289 — every account may report a problem (#5604).
--
-- V220 made `accounts.account.can_report` an allowlist, false by default. It now defaults to true, and every account
-- gets it. The column stays: the operator turns it off for an account that abuses the form, through the importer's
-- admin API. The BFF caps each account at 200 open reports.
--
-- `report_cap_exempt` lifts that cap for one account: the operator's own, whose reporting runs a cap stopped before
-- (`775e5bdac`). Only the importer's admin API sets it, after the deploy; no address belongs in a migration.

ALTER TABLE accounts.account ALTER COLUMN can_report SET DEFAULT true;

UPDATE accounts.account SET can_report = true WHERE NOT can_report;

ALTER TABLE accounts.account ADD COLUMN report_cap_exempt BOOLEAN NOT NULL DEFAULT false;
