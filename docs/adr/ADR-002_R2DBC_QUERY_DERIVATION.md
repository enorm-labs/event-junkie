# ADR-002: Spring Data R2DBC Query Derivation Limitations

## Status

**Accepted — a derived query per property, and `@Query` with explicit SQL wherever derivation stops.**

## Context

This project uses a fully reactive stack (WebFlux + R2DBC + Kotlin coroutines). Repositories extend
`CoroutineCrudRepository` from Spring Data. Spring Data JPA offers very rich
[derived query method](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html) support that automatically generates queries from method names.

Spring Data R2DBC supports query derivation as well, but with **some limitations** compared to JPA.

See: [Spring Data R2DBC — Query Methods](https://docs.spring.io/spring-data/relational/reference/r2dbc/query-methods.html)

## What Works (Derived Methods)

Spring Data R2DBC supports derived query methods for both **reads and deletes**:

```kotlin
// SELECT — derived from method name
fun findByEventId(eventId: Long): Flow<EventArtistEntity>
suspend fun findBySourceId(sourceId: String): EventEntity?

// DELETE — derived from method name (see "Modifying Queries" in the R2DBC docs)
suspend fun deleteByEventId(eventId: Long)
```

Derived delete methods support these return types (Kotlin coroutine equivalents):

- `Unit` (`Mono<Void>`) — just deletes without returning a count
- `Int` (`Mono<Integer>`) — returns the number of affected rows
- `Boolean` (`Mono<Boolean>`) — returns whether at least one row was removed

## What Requires `@Query`

Derived **update** methods (e.g. `updateBy*`) are **not supported** — use `@Modifying` + `@Query` with raw SQL:

```kotlin
@Modifying
@Query("UPDATE events.person SET firstname = :firstname WHERE lastname = :lastname")
suspend fun updateFirstnameByLastname(firstname: String, lastname: String): Int
```

### Schema prefix in `@Query`

Raw `@Query` SQL bypasses the `@Table(schema = "events")` annotation on the entity class. Therefore, table references in custom queries **must** include the
schema prefix explicitly (e.g. `events.event_artist` instead of just
`event_artist`).

## Consequences

- Prefer derived query methods (`findBy*`, `deleteBy*`) over `@Query` when possible — they are simpler and automatically respect `@Table` metadata (including
  the schema).
- For update operations, use `@Modifying` + `@Query` with the fully qualified table name including the `events.`
  schema prefix.
- If the project ever migrates to a blocking JPA stack, `@Query`-annotated methods could potentially be replaced with derived methods. This is unlikely given
  the reactive architecture commitment.

## References

- [Spring Data R2DBC — Query Methods](https://docs.spring.io/spring-data/relational/reference/r2dbc/query-methods.html)
- [Spring Data R2DBC — Modifying Queries](https://docs.spring.io/spring-data/relational/reference/r2dbc/query-methods.html#r2dbc.repositories.modifying)
- [Spring Data JPA — Query Methods](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
- [`EventRepositories.kt`](../../events-importer/src/main/kotlin/de/norm/events/event/EventRepositories.kt)
