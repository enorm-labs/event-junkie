package de.norm.events

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing
import org.springframework.data.relational.core.mapping.NamingStrategy

/**
 * Spring Data R2DBC configuration.
 *
 * - Enables auditing so `@CreatedDate` and `@LastModifiedDate` annotations on entity fields
 *   are automatically populated on save. This results in a dual-write for `updated_at`:
 *   Spring Data sets it in the entity before the UPDATE, and the PostgreSQL `set_updated_at()`
 *   trigger also sets it on every UPDATE. Both write `now()` so values are nearly identical.
 *   The DB triggers serve as a safety net for raw SQL paths (e.g. `@Query` updates) that
 *   bypass Spring Data's auditing infrastructure.
 * - Provides a custom [NamingStrategy] that applies the configured database schema globally,
 *   so individual `@Table` annotations don't need to repeat it. The schema is [EVENTS_SCHEMA],
 *   and `spring.r2dbc.properties.schema` is checked against it rather than being its source (#540).
 */
@Configuration
@EnableR2dbcAuditing
class R2dbcConfiguration {
    /**
     * Overrides the default [NamingStrategy] to qualify all generated SQL with [EVENTS_SCHEMA]
     * (e.g. `events.venue` instead of just `"venue"`).
     *
     * Without this, Spring Data R2DBC generates unqualified table references for derived query
     * methods, which fail because the tables live in a dedicated schema rather than `public`.
     *
     * **The schema comes from the constant, not from the property, and the property is checked
     * against it (#540).** It used to come from `spring.r2dbc.properties.schema` alone, while every
     * hand-written statement carried a literal `events.` — so the property moved derived queries and
     * left raw SQL behind, producing an application that started cleanly and was half-migrated. The
     * property has not gone away, because it is what sets the connection's `search_path` and no
     * Kotlin constant can do that; it is now a declaration that must agree.
     *
     * `require` rather than a log line, deliberately: a warning about a schema mismatch is a warning
     * nobody reads until `/api/events` is already failing, and this is exactly the state #438's
     * readiness probe cannot see.
     */
    @Bean
    fun namingStrategy(
        @Value("\${spring.r2dbc.properties.schema}") configuredSchema: String
    ): NamingStrategy {
        require(configuredSchema == EVENTS_SCHEMA) {
            "spring.r2dbc.properties.schema is '$configuredSchema' but every hand-written statement and " +
                "@Query in this application names '$EVENTS_SCHEMA' (EVENTS_SCHEMA). An annotation value is a " +
                "compile-time constant and cannot follow a property, so the two cannot be reconciled at runtime: " +
                "derived queries would use one schema and raw SQL the other, and the application would start " +
                "cleanly and serve errors. Change EVENTS_SCHEMA in events-core, or put the property back."
        }
        return object : NamingStrategy {
            override fun getSchema(): String = EVENTS_SCHEMA
        }
    }
}
