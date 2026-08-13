# ADR-001: Reactive Stack (WebFlux + R2DBC + Kotlin Coroutines)

## Status

Accepted

## Context

The application needs to handle potentially many concurrent requests (event listings, search, imports) while maintaining low resource consumption. Two main
approaches exist in the Spring ecosystem:

1. **Spring MVC + JDBC/JPA** — Traditional blocking stack. One thread per request. Well-understood, massive ecosystem, straightforward debugging. However,
   thread-per-request scales poorly under high concurrency without proportionally increasing thread pool sizes and memory.
2. **Spring WebFlux + R2DBC** — Non-blocking reactive stack. A small number of event-loop threads handle many concurrent connections. More efficient under
   I/O-heavy workloads (database queries, external API calls for event imports). Kotlin coroutines make reactive code read like sequential code, avoiding the
   callback and operator complexity of raw Reactor (`Mono`/`Flux`).

## Decision

Use the **reactive stack throughout**: Spring WebFlux for HTTP, R2DBC for database access, and Kotlin coroutines as the programming model.

- Repositories extend `CoroutineCrudRepository` — all database operations are suspending functions.
- Controllers use `suspend fun` for single-value responses and return `Flow<T>` for streaming results.
- **No blocking APIs** (`spring-web`, JDBC, `RestTemplate`) in request paths. All I/O must be non-blocking.

## Consequences

- **Positive**: Efficient concurrency model for I/O-bound workloads; Kotlin coroutines make the code readable and testable despite being non-blocking; natural
  fit for streaming event data.
- **Negative**: R2DBC has more limited query derivation than JPA (see [ADR-002](ADR-002_R2DBC_QUERY_DERIVATION.md)); no lazy loading or entity relationships —
  joins must be handled manually; smaller ecosystem of tooling and community examples compared to JPA; blocking libraries (e.g. some email or file-processing
  libs) require careful wrapping with `Dispatchers.IO`.
- **Testing**: Use `WebTestClient` (reactive) instead of `MockMvc` (servlet). Coroutine tests use `runTest`.
- All team members must understand coroutines and avoid accidentally introducing blocking calls.

## References

- [Spring WebFlux documentation](https://docs.spring.io/spring-framework/reference/web/webflux.html)
- [Spring Data R2DBC documentation](https://docs.spring.io/spring-data/relational/reference/r2dbc.html)
- [Kotlin Coroutines with Spring](https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html)
