# ADR-005: Database Migrations Owned by the Importer Only

## Status

Accepted

## Context

The system has two Spring Boot applications that access the same PostgreSQL database:

- **events-importer** — Writes data (imports events from external sources, admin CRUD).
- **events-bff** — Reads data (serves the frontend API).

Both could theoretically run Flyway migrations at startup. The question is which application should own and execute database migrations.

## Decision

**Only `events-importer` runs Flyway migrations.** The BFF does not include Flyway and does not modify the database schema.

- All migration files live in `events-importer/src/main/resources/db/migration/`.
- Migration naming follows `V001__description.sql`, `V002__description.sql`, etc.
- **Each schema change is its own migration.** `V001__create_initial_schema.sql` was consolidated while nothing was deployed and is closed as of 2026-08-19 —
  see the amendment below.
- The importer configures `spring.flyway.schemas: events` to target the dedicated schema.

## Consequences

- **Positive**: Single source of truth for schema changes — no risk of conflicting migrations from two apps; the BFF stays lightweight and read-focused; clear
  ownership makes it obvious where to add new migrations; the BFF can start up faster (no migration check).
- **Negative**: The importer must be deployed/started before or alongside the BFF when schema changes are introduced; in development, `events-importer` must run
  (or have run) before `events-bff` can access new tables/columns. Docker Compose dev services and Spring Boot's auto-start mitigate this for local development.
- New tables or columns are always added via a migration in the importer module, even if they are primarily read by the BFF.

## Amendment, 2026-08-19 — the consolidation window is closed (#415)

Until now, every schema change was folded back into `V001__create_initial_schema.sql` rather than added as `V002`, on the rule in AGENTS.md: _"while the project
is in development (not yet deployed to production)."_ That was a reasonable trade — it kept the whole schema readable in one file, and re-reading a migration
history nobody had ever applied would have been ceremony.

**The trigger named the wrong event.** What consolidation depended on was not the absence of _production_ but the absence of _any database with `V001`
applied_ — and staging became one long before production will. Editing an applied migration produces `FlywayValidateException: Migration checksum mismatch`, the
importer's context fails to start, the pod never becomes Ready, and the HelmRelease's `remediateLastFailure: true` rolls the release back. The operator sees
"the deploy reverted", two layers away from the cause, on a change that looked like adding a column.

It closed when three changes in flight were each editing `V001` simultaneously, which is what a policy looks like once it has stopped being free.

**Resetting a pre-launch environment is still the cheaper option, and will not be for long.** Every event in this system is scraped from a public page and every
venue and source is re-creatable from `http/importer/dev-seed.http`, so dropping the `events` schema costs one import cycle rather than data. That property ends
the day anything is stored that was not derived — which is the same day the reset stops being available and the migration history starts being the only way
forward.

## References

- [Flyway documentation](https://documentation.red-gate.com/flyway)
- [events-importer/src/main/resources/db/migration/](../../events-importer/src/main/resources/db/migration/)
