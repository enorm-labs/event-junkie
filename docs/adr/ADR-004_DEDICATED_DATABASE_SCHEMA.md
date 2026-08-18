# ADR-004: Dedicated `events` Database Schema

## Status

Accepted

## Context

PostgreSQL supports multiple schemas within a single database. By default, tables are created in the `public`
schema. The question is whether to use `public` or a dedicated schema for the application's tables.

## Decision

All application tables live in a **dedicated `events` schema** (not `public`).

**The name is written once**, as `EVENTS_SCHEMA` in `events-core`, and everything else resolves to it:

1. **Custom `@Query` and raw SQL** interpolate the constant — `"SELECT * FROM $EVENTS_SCHEMA.event_source"`. Raw queries bypass `@Table` and the
   `NamingStrategy` metadata (see [ADR-002](ADR-002_R2DBC_QUERY_DERIVATION.md)), so they have to name the schema themselves; a Kotlin `const val` is usable
   inside an annotation argument, which is what makes one source of truth reach even a `@Query`.
2. **Derived queries** are qualified by the `NamingStrategy` bean in each module's `R2dbcConfiguration`, which returns the constant. `@Table` annotations
   therefore carry only the table name.
3. **Flyway** (importer only): `spring.flyway.schemas: events` creates the schema and sets `search_path` before running migrations. Migration SQL stays
   **unqualified** for exactly that reason — see the amendment below for why this one sentence survived intact.
4. **R2DBC** (both modules): `spring.r2dbc.properties.schema: events` sets the connection's `search_path`. Neither this nor Flyway's property can read a Kotlin
   constant, so both remain in `application.yaml` as **declarations that must agree** — `R2dbcConfiguration` fails the context on divergence, and
   `SchemaConfigurationTest` fails the build if any `application.yaml` in either module declares a different name.

---

### Amendment, 2026-08-18 — this ADR contradicted itself, and #540 is the resolution

The original text said migration SQL is unqualified _"so the target schema remains configurable via `application.yaml`"_, and three lines later that custom
`@Query` SQL **must** carry the `events.` prefix — listing under Negative that _"developers must remember the `events.` prefix"_.

**Both statements were true and they did not reconcile.** The schema was configurable for Flyway and for derived queries, and fixed for raw SQL. Changing the
property produced a **half-migrated application that started cleanly**: derived queries moved to the new schema, seven hand-written statements kept pointing at
`events`. Nobody changed the property, which is why it stayed harmless.

[#438](https://github.com/enorm-labs/event-junkie/issues/438) changed the cost of it being wrong. The BFF's readiness probe began querying the property's schema
while `EventSearchRepository` queried a constant — so a mismatch would produce a BFF that reports **Ready** and fails `/api/events`, which is precisely the
failure class #438 closed, reintroduced by a different route and invisible to the probe built to catch it. That the third consumer was added without anyone
noticing the split widening is the strongest argument that "developers must remember" was never enforcement.

**What changed.** The constant is now the source and the properties are checked against it, rather than the other way round. `@Table(schema = ...)` is not part
of it: no entity had ever carried one, so that line described an intention rather than the tree.

**What did not change, and why the unqualified-migrations sentence was right after all:** Flyway sets `search_path` from `spring.flyway.schemas` before running
migration SQL, so an unqualified migration follows the configuration and a qualified one would pin itself to a schema the configuration no longer controls. That
is the one place the schema genuinely _is_ configurable, and `SchemaConfigurationTest` now asserts migrations stay unqualified rather than leaving it to
convention.

## Consequences

- **Positive**: Clean separation from PostgreSQL system tables and any other applications sharing the same database; makes it obvious which tables belong to
  Event Junkie; easier to grant/revoke schema-level permissions; avoids conflicts if multiple apps share a database in development.
- **Negative**: Requires consistent configuration across Flyway, R2DBC, and raw SQL; slightly more setup than using `public`. The prefix is no longer something
  developers must remember — raw SQL interpolates `EVENTS_SCHEMA` and `SchemaConfigurationTest` fails the build on a literal — but the two YAML declarations
  still have to agree with the constant, which is enforced rather than assumed.

## References

- [`EventsSchema.kt`](../../events-core/src/main/kotlin/de/norm/events/EventsSchema.kt) — the constant, and the reasoning for it
- [`SchemaConfigurationTest.kt`](../../events-core/src/test/kotlin/de/norm/events/SchemaConfigurationTest.kt) — what enforces this ADR
- [`V001__create_initial_schema.sql`](../../events-importer/src/main/resources/db/migration/V001__create_initial_schema.sql)
- [PostgreSQL Schemas documentation](https://www.postgresql.org/docs/current/ddl-schemas.html)
