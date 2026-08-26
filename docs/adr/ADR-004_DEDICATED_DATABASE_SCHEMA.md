# ADR-004: Dedicated `events` Database Schema

## Status

**Accepted — every application table lives in an `events` schema, named once as `EVENTS_SCHEMA` in `events-core`.**

## Context

PostgreSQL supports multiple schemas within a single database. By default, tables are created in the `public`
schema. The question is whether to use `public` or a dedicated schema for the application's tables.

## Decision

All application tables live in a **dedicated `events` schema** (not `public`).

**The name is written once**, as `EVENTS_SCHEMA` in `events-core`, and everything else resolves to it:

1. **Custom `@Query` and raw SQL** interpolate the constant: `"SELECT * FROM $EVENTS_SCHEMA.event_source"`. A raw
   query bypasses `@Table` and the `NamingStrategy` metadata (see
   [ADR-002](ADR-002_R2DBC_QUERY_DERIVATION.md)), so it has to name the schema itself. A Kotlin `const val` is usable
   inside an annotation argument. That is what makes one source of truth reach even a `@Query`.
2. **Derived queries** are qualified by the `NamingStrategy` bean in each module's `R2dbcConfiguration`, which returns
   the constant. A `@Table` annotation therefore carries only the table name.
3. **Flyway** (importer only): `spring.flyway.schemas: events` creates the schema and sets `search_path` before
   running migrations. Migration SQL stays **unqualified** for exactly that reason — see below.
4. **R2DBC** (both modules): `spring.r2dbc.properties.schema: events` sets the connection's `search_path`. Neither
   this nor Flyway's property can read a Kotlin constant. Both stay in `application.yaml` as **declarations that must
   agree**. `R2dbcConfiguration` fails the context on divergence, and `SchemaConfigurationTest` fails the build if any
   `application.yaml` declares a different name.

---

### Why the constant is the source, and migration SQL is not qualified

The schema name was once configurable for Flyway and for derived queries, and fixed in raw SQL. Changing the property
then produced a **half-migrated application that started cleanly**. The derived queries moved to the new schema, and
seven hand-written statements kept pointing at `events`. Nobody changed the property, which is why it stayed harmless.

[#438](https://github.com/enorm-labs/event-junkie/issues/438) changed the cost of it being wrong. The BFF's readiness
probe began querying the property's schema while `EventSearchRepository` queried a constant. A mismatch then produces
a BFF that reports **Ready** and fails `/api/events`. That is precisely the failure class #438 closed, reintroduced by
a different route, and invisible to the probe built to catch it. A third consumer arrived without anyone noticing the
split widen, which is the strongest argument that "developers must remember" was never enforcement.

So the constant is the source, and the properties are checked against it. `@Table(schema = ...)` is not part of that:
no entity ever carried one.

Migration SQL stays unqualified for a different reason. Flyway sets `search_path` from `spring.flyway.schemas` before
running it, so an unqualified migration follows the configuration. A qualified one would pin itself to a schema the
configuration no longer controls. That is the one place the schema genuinely _is_ configurable, and
`SchemaConfigurationTest` asserts migrations stay unqualified rather than leaving it to convention.

## Consequences

- **Positive**: a clean separation from the PostgreSQL system tables, and from any other application sharing the
  database. It is obvious which tables belong to Event Junkie. Schema-level permissions are easier to grant and
  revoke, and two apps sharing a development database do not collide.
- **Negative**: the configuration has to stay consistent across Flyway, R2DBC and raw SQL, which is slightly more
  setup than `public`. The prefix is not something a developer must remember: raw SQL interpolates `EVENTS_SCHEMA`,
  and `SchemaConfigurationTest` fails the build on a literal. But the two YAML declarations still have to agree with
  the constant, and that is enforced rather than assumed.

## References

- [`EventsSchema.kt`](../../events-core/src/main/kotlin/de/norm/events/EventsSchema.kt) — the constant, and the reasoning for it
- [`SchemaConfigurationTest.kt`](../../events-core/src/test/kotlin/de/norm/events/SchemaConfigurationTest.kt) — what enforces this ADR
- [`V001__create_initial_schema.sql`](../../events-importer/src/main/resources/db/migration/V001__create_initial_schema.sql)
- [PostgreSQL Schemas documentation](https://www.postgresql.org/docs/current/ddl-schemas.html)
