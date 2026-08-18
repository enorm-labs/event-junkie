package de.norm.events

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.AbstractReactiveHealthIndicator
import org.springframework.boot.health.contributor.Health
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Reports whether this instance can actually read the `events` schema, so that readiness can mean
 * "can serve" rather than "Spring finished starting".
 *
 * Registered as the health component **`eventsSchema`** — Spring derives the name from the bean name
 * with the `HealthIndicator` suffix removed — and named in the readiness group in `application.yaml`.
 * Renaming this class renames the health component and breaks that group at **startup**, not at
 * runtime: `management.endpoint.health.validate-group-membership` defaults to `true`, so a group that
 * includes a contributor which no longer exists fails the context. That is the desired behaviour and
 * the reason the property is left at its default.
 *
 * ### Why this exists alongside the stock `r2dbc` indicator
 *
 * Boot's `ConnectionFactoryHealthIndicator` calls `Connection.validate(REMOTE)`. It proves the
 * database is reachable and proves nothing about the schema — which is precisely the failure
 * [#263](https://github.com/enorm-labs/event-junkie/issues/263) measured on k3d: PostgreSQL was up
 * the whole time, the BFF reported Ready 1.2 seconds before the importer's Flyway migrations created
 * the schema it queries, and Kubernetes routed traffic into that window. Adding `r2dbc` to the
 * readiness group alone would have left that window exactly as wide as it was.
 *
 * The two are kept separate rather than folded into one so that `/actuator/health/readiness` says
 * *which* of the two is down — "the database is gone" and "the schema is not there yet" have
 * different operators and different fixes.
 *
 * ### Why this query
 *
 * `SELECT EXISTS (SELECT 1 FROM <schema>.event)` was chosen over three alternatives:
 *
 * - **It always returns exactly one row**, including against an empty table. `SELECT 1 FROM … LIMIT 1`
 *   returns *none* on a fresh database, and an empty result reaches `Health.Builder.build()` with no
 *   status set — reporting `UNKNOWN` on a first install, which is legitimate and must not block
 *   readiness.
 * - **It is O(1), not O(rows).** PostgreSQL stops the subquery at the first tuple, so this does not
 *   become a sequential scan as the table grows. `SELECT count(*)` would.
 * - **It exercises the real grant on the real table**, unlike an `information_schema` lookup, which
 *   answers "is it visible" rather than "can I read it".
 *
 * A missing schema or a revoked grant raises an `R2dbcException`, which
 * [AbstractReactiveHealthIndicator] turns into `DOWN` carrying the cause — so the failure path needs
 * no handling here.
 *
 * The schema name is interpolated rather than bound because no SQL dialect parameterises an
 * identifier. It comes from `spring.r2dbc.properties.schema`, the same property
 * [R2dbcConfiguration] feeds to the `NamingStrategy`; it is configuration, never request input.
 */
@Component
class EventsSchemaHealthIndicator(
    private val databaseClient: DatabaseClient,
    @Value("\${spring.r2dbc.properties.schema}") private val schema: String
) : AbstractReactiveHealthIndicator("The events schema is not readable — readiness will report DOWN") {
    private val probeSql = "SELECT EXISTS (SELECT 1 FROM $schema.event)"

    override fun doHealthCheck(builder: Health.Builder): Mono<Health> =
        databaseClient
            .sql(probeSql)
            .fetch()
            .first()
            .thenReturn(
                builder
                    .up()
                    .withDetail("schema", schema)
                    .withDetail("probe", "$schema.event")
                    .build()
            )
}
