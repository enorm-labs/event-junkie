package de.norm.events

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.relational.core.mapping.NamingStrategy

/**
 * Spring Data R2DBC configuration for the BFF.
 *
 * Provides a custom [NamingStrategy] that applies the configured database schema globally,
 * so individual `@Table` annotations don't need to repeat it. The schema is [EVENTS_SCHEMA],
 * and `spring.r2dbc.properties.schema` is checked against it rather than being its source (#540).
 *
 * Unlike the importer, the BFF does **not** enable `@EnableR2dbcAuditing`: it is a read-only
 * service and never populates `@CreatedDate`/`@LastModifiedDate`.
 */
@Configuration
class R2dbcConfiguration {
    /**
     * Overrides the default [NamingStrategy] to qualify all generated SQL with [EVENTS_SCHEMA]
     * (e.g. `events.venue` instead of just `"venue"`).
     *
     * Without this, Spring Data R2DBC generates unqualified table references for derived query
     * methods, which fail because the tables live in a dedicated schema rather than `public`.
     *
     * **The schema comes from the constant, not from the property, and the property is checked
     * against it (#540).** A property that moves derived queries while every hand-written statement
     * keeps its literal `events.` prefix half-migrates the application and still starts cleanly. The
     * property stays, because it is what sets the connection's `search_path` and no Kotlin constant
     * can do that — as a declaration that must agree, not as the source.
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
