# ADR-005: Database Migrations Owned by the Importer Only

## Status

**Accepted — `events-importer` owns and runs every Flyway migration, and the BFF has no Flyway at all.**

## Context

The system has two Spring Boot applications that access the same PostgreSQL database:

- **events-importer** — Writes data (imports events from external sources, admin CRUD).
- **events-bff** — Reads data (serves the frontend API).

Both could theoretically run Flyway migrations at startup. The question is which application should own and execute database migrations.

## Decision

**Only `events-importer` runs Flyway migrations.** The BFF does not include Flyway and does not modify the database schema.

- All migration files live in `events-importer/src/main/resources/db/migration/`.
- Migration naming follows `V001__description.sql`, `V002__description.sql`, etc.
- **Each schema change is its own migration.** `V001__create_initial_schema.sql` was consolidated while nothing was
  deployed. That window closed on 2026-08-19 — see below.
- The importer configures `spring.flyway.schemas: events` to target the dedicated schema.

## Consequences

- **Positive**: one source of truth for schema changes, so two apps cannot write conflicting migrations. The BFF
  stays lightweight and read-focused, and starts faster for want of a migration check. Clear ownership makes it
  obvious where a new migration goes.
- **Negative**: a schema change means deploying or starting the importer before the BFF, or alongside it. In
  development, `events-importer` has to run before `events-bff` can reach a new table or column. Docker Compose dev
  services and Spring Boot's auto-start mitigate that locally.
- New tables or columns are always added via a migration in the importer module, even if they are primarily read by the BFF.

## Why the consolidation window is closed (#415)

Every schema change used to be folded back into `V001__create_initial_schema.sql` rather than added as `V002`. The
rule in AGENTS.md said _"while the project is in development (not yet deployed to production)."_ That was a
reasonable
trade. It kept the whole schema readable in one file. Re-reading a migration history nobody had ever applied would
have been ceremony.

**The trigger named the wrong event.** Consolidation depended on the absence of _any database with `V001` applied_, not
on the absence of _production_. Staging became one long before production will. Editing an applied migration produces
`FlywayValidateException: Migration checksum mismatch`. The importer's context then fails to start, the pod never
becomes Ready, and the HelmRelease's `remediateLastFailure: true` rolls the release back. The operator sees "the deploy
reverted", two layers away from the cause, on a change that looked like adding a column.

The window closed when three changes in flight were each editing `V001` at once. That is what a policy looks like once
it stops being free.

**Resetting a pre-launch environment is still the cheaper option, and will not be for long.** Every event in this
system is scraped from a public page, and every venue and source is re-creatable from
`http/importer/dev-seed.http`. Dropping the `events` schema therefore costs one import cycle rather than data. That
property ends the day anything is stored that was not derived. The reset stops being available on the same day, and
the migration history becomes the only way forward.

## References

- [Flyway documentation](https://documentation.red-gate.com/flyway)
- [events-importer/src/main/resources/db/migration/](../../events-importer/src/main/resources/db/migration/)
