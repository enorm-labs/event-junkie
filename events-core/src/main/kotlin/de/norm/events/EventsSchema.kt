package de.norm.events

/**
 * The database schema every table in this system lives in, and the only place its name is written.
 *
 * **`spring.r2dbc.properties.schema` reads as a knob and is not one (#540).** Before this constant
 * existed the name was configurable in three places and hardcoded in seven: the two `NamingStrategy`
 * beans and the BFF's readiness probe honoured the property, while every hand-written statement
 * carried a literal `events.` prefix. Changing the property therefore produced a **half-migrated
 * application that started cleanly** — derived queries moved to the new schema, raw SQL kept pointing
 * at the old one — and #438 raised the cost of that: the BFF's readiness probe queries the property's
 * schema, so a mismatch produces a BFF that reports **Ready** and fails `/api/events`. Precisely the
 * failure class #438 closed, reintroduced by a different route and invisible to the probe built to
 * catch it.
 *
 * **A `const val` is the only form of single source of truth that reaches every call site**, and that
 * is why the constant wins over the property rather than the other way round. Spring Data's
 * `@Query` and `@Table` take annotation arguments, which must be compile-time constants — an
 * annotation cannot read a runtime property at all, no matter how carefully it is written. ADR-004
 * recorded that limitation as *"developers must remember the `events.` prefix"*, which is enforcement
 * by memory. A `const val` interpolates into an annotation string, so the same name reaches raw SQL,
 * derived queries, the health indicator and the migrations.
 *
 * **The YAML still declares it, and must agree.** `spring.flyway.schemas` is what creates the schema
 * and `spring.r2dbc.properties.schema` is what sets the connection's `search_path`; neither can read
 * a Kotlin constant. So they stay, as *declarations that must match* rather than as independent
 * knobs — `R2dbcConfiguration` fails the context on divergence, and `SchemaConfigurationTest` fails
 * the build if any `application.yaml` in either module declares a different name.
 *
 * Migration SQL stays unqualified, which is unchanged and still correct: Flyway sets `search_path`
 * from `spring.flyway.schemas` before running it.
 */
const val EVENTS_SCHEMA: String = "events"
