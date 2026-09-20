package de.norm.events

import org.springframework.boot.health.contributor.AbstractReactiveHealthIndicator
import org.springframework.boot.health.contributor.Health
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Reports whether this instance can read the `events` schema, so readiness means "can serve".
 * Registered as the health component `eventsSchema` and named in the readiness group in
 * `application.yaml`, so renaming this class breaks that group at startup
 * (`validate-group-membership` stays `true`). Boot's `r2dbc` indicator only calls
 * `Connection.validate(REMOTE)`, the window #263 measured on k3d: Ready 1.2 seconds before
 * Flyway created the schema.
 *
 * `SELECT EXISTS (SELECT 1 FROM <schema>.event)` returns one row even against an empty table
 * (`LIMIT 1` returns none, and an empty result builds as `UNKNOWN`, blocking a first install),
 * stays O(1), and exercises the real grant on the real table. The schema is interpolated because
 * no dialect parameterises an identifier, and it must stay [EVENTS_SCHEMA] rather than
 * `spring.r2dbc.properties.schema`: a probe reading a property the queries ignore reports green
 * while querying a different schema (#540).
 */
@Component
class EventsSchemaHealthIndicator(
    private val databaseClient: DatabaseClient
) : AbstractReactiveHealthIndicator("The events schema is not readable — readiness will report DOWN") {
    private val probeSql = "SELECT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event)"

    override fun doHealthCheck(builder: Health.Builder): Mono<Health> =
        databaseClient
            .sql(probeSql)
            .fetch()
            .first()
            .thenReturn(
                builder
                    .up()
                    .withDetail("schema", EVENTS_SCHEMA)
                    .withDetail("probe", "$EVENTS_SCHEMA.event")
                    .build()
            )
}
