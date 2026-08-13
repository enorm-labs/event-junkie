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
- The importer configures `spring.flyway.schemas: events` to target the dedicated schema.

## Consequences

- **Positive**: Single source of truth for schema changes — no risk of conflicting migrations from two apps; the BFF stays lightweight and read-focused; clear
  ownership makes it obvious where to add new migrations; the BFF can start up faster (no migration check).
- **Negative**: The importer must be deployed/started before or alongside the BFF when schema changes are introduced; in development, `events-importer` must run
  (or have run) before `events-bff` can access new tables/columns. Docker Compose dev services and Spring Boot's auto-start mitigate this for local development.
- New tables or columns are always added via a migration in the importer module, even if they are primarily read by the BFF.

## References

- [Flyway documentation](https://documentation.red-gate.com/flyway)
- [events-importer/src/main/resources/db/migration/](../../events-importer/src/main/resources/db/migration/)
