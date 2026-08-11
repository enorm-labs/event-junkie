# ADR-004: Dedicated `events` Database Schema

## Status

Accepted

## Context

PostgreSQL supports multiple schemas within a single database. By default, tables are created in the `public`
schema. The question is whether to use `public` or a dedicated schema for the application's tables.

## Decision

All application tables live in a **dedicated `events` schema** (not `public`).

This is configured in three places:

1. **Flyway** (importer only): `spring.flyway.schemas: events` — Flyway automatically creates the schema if it doesn't exist and sets `search_path` before
   running migrations. Migration SQL uses unqualified table names so the target schema remains configurable via `application.yaml`.
2. **R2DBC** (both BFF and importer): `spring.r2dbc.properties.schema: events` — sets the default schema for all R2DBC operations. Entity `@Table` annotations
   also specify `schema = "events"` for explicitness.
3. **Custom `@Query` SQL**: Must use the schema prefix (e.g. `events.event_artist`) because raw queries bypass `@Table` metadata
   (see [ADR-002](ADR-002_R2DBC_QUERY_DERIVATION.md)).

## Consequences

- **Positive**: Clean separation from PostgreSQL system tables and any other applications sharing the same database; makes it obvious which tables belong to
  Event Junkie; easier to grant/revoke schema-level permissions; avoids conflicts if multiple apps share a database in development.
- **Negative**: Requires consistent configuration across Flyway, R2DBC, and raw SQL; developers must remember the `events.` prefix in `@Query` annotations;
  slightly more setup than using `public`.

## References

- [`V001__create_initial_schema.sql`](../../events-importer/src/main/resources/db/migration/V001__create_initial_schema.sql)
- [PostgreSQL Schemas documentation](https://www.postgresql.org/docs/current/ddl-schemas.html)


