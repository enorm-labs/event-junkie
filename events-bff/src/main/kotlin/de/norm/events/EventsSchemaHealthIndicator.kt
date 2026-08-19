package de.norm.events

import org.springframework.boot.health.contributor.AbstractReactiveHealthIndicator
import org.springframework.boot.health.contributor.Health
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Reports whether this instance can actually read the `events` schema, so readiness means "can
 * serve" rather than "Spring finished starting".
 *
 * Registered as the health component **`eventsSchema`** (the bean name minus the `HealthIndicator`
 * suffix) and named in the readiness group in `application.yaml`, so renaming this class breaks that
 * group at startup rather than at runtime — `management.endpoint.health.validate-group-membership`
 * is deliberately left at its default `true`.
 *
 * Boot's stock `r2dbc` indicator only calls `Connection.validate(REMOTE)`, proving the database is
 * reachable and nothing about the schema — the window [#263](https://github.com/enorm-labs/event-junkie/issues/263)
 * measured on k3d, where the BFF reported Ready 1.2 seconds before Flyway created the schema it
 * queries. The two stay separate so `/actuator/health/readiness` names which one is down.
 *
 * `SELECT EXISTS (SELECT 1 FROM <schema>.event)` beats the alternatives three ways: it returns one
 * row even against an empty table (`… LIMIT 1` returns none, and an empty result builds as
 * `UNKNOWN`, blocking readiness on a first install); PostgreSQL stops the subquery at the first
 * tuple, so it stays O(1) where `count(*)` is O(rows); and it exercises the real grant on the real
 * table, which an `information_schema` lookup does not. A missing schema or revoked grant raises an
 * `R2dbcException` that [AbstractReactiveHealthIndicator] turns into `DOWN` with the cause.
 *
 * The schema is interpolated because no SQL dialect parameterises an identifier, and it must stay
 * [EVENTS_SCHEMA] rather than `spring.r2dbc.properties.schema`: a probe reading a property the
 * queries ignore reports green while querying a different schema (#540).
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
