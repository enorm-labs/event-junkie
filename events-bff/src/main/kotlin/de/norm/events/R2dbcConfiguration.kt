package de.norm.events

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.relational.core.mapping.NamingStrategy

/**
 * Spring Data R2DBC configuration: a [NamingStrategy] that applies [EVENTS_SCHEMA] globally, with
 * `spring.r2dbc.properties.schema` checked against it rather than being its source (#540). No
 * `@EnableR2dbcAuditing`: a read-only service.
 */
@Configuration
class R2dbcConfiguration {
    /**
     * Qualifies all generated SQL with [EVENTS_SCHEMA] (`events.venue`); without it derived queries
     * reference `public`. The schema comes from the constant and the property is checked against it
     * (#540): a property that moved derived queries while every hand-written statement kept its
     * literal `events.` prefix would half-migrate the application and still start. The property
     * stays because it sets the connection's `search_path`. `require` rather than a log line: a
     * warning nobody reads until `/api/events` is failing, in the state #438's probe cannot see.
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
