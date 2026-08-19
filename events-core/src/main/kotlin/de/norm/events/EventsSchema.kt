package de.norm.events

/**
 * The database schema every table in this system lives in, and the only place its name is written.
 *
 * **`spring.r2dbc.properties.schema` reads as a knob and is not one (#540).** It reaches the two
 * `NamingStrategy` beans, not the hand-written statements, so changing it half-migrates the
 * application while it still starts cleanly — derived queries move to the new schema, raw SQL keeps
 * pointing at the old one. With #438's readiness probe in place that is worse than it sounds: the
 * probe would report **Ready** against one schema while `/api/events` fails against another.
 *
 * **A `const val` is the only single source of truth that reaches every call site.** Spring Data's
 * `@Query` and `@Table` take annotation arguments, which must be compile-time constants, so no
 * runtime property can reach them — ADR-004 recorded that gap as "developers must remember the
 * `events.` prefix", which is enforcement by memory. A constant interpolates into an annotation
 * string, so one name reaches raw SQL, derived queries, the health indicator and the migrations.
 *
 * **The YAML still declares it, and must agree.** `spring.flyway.schemas` creates the schema and
 * `spring.r2dbc.properties.schema` sets the connection's `search_path`; neither can read a Kotlin
 * constant. They stay as declarations that must match: `R2dbcConfiguration` fails the context on
 * divergence, and `SchemaConfigurationTest` fails the build if any `application.yaml` in either
 * module declares a different name. Migration SQL stays unqualified — Flyway sets `search_path`
 * from `spring.flyway.schemas` before running it.
 */
const val EVENTS_SCHEMA: String = "events"
