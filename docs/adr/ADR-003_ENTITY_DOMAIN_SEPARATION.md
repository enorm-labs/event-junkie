# ADR-003: Separation of Persistence Entities and Domain Model

## Status

**Accepted — plain domain classes in `events-core`, and annotated `*Entity` classes per application.**

## Context

The project has a shared library (`events-core`) consumed by multiple applications (BFF, importer). The domain model (e.g. `Venue`, `Artist`, `Event`) needs to
be accessible to all consumers without coupling them to persistence concerns.

Two approaches were considered:

1. **Single class for both domain and persistence** — annotate the domain classes with `@Table`, `@Id` and the rest.
   Simpler, less boilerplate, and common in Spring Data projects. But it couples the shared library to Spring Data
   R2DBC. Every consumer then pulls in R2DBC dependencies, whether or not it needs them. The BFF could want a
   different data access strategy later.
2. **Separate domain classes and persistence entities** — keep the domain classes as plain Kotlin data classes in
   `events-core`. Define annotated persistence entities in each application module that needs them.

## Decision

Use **separate classes**. The domain model is plain Kotlin data classes in `events-core`. The `*Entity.kt` classes
carry the Spring Data annotations (`@Table`, `@Id`), in the importer and in the BFF where needed.

Each entity provides two conversion functions:

- `toDomain()` — instance method converting the entity to the domain class
- `fromDomain()` — companion factory creating an entity from the domain class

```kotlin
// events-core: clean domain class
data class Venue(
    val id: Long? = null,
    val name: String,
    val slug: String,
    // ...
)

// events-importer: persistence entity
@Table("venue", schema = "events")
data class VenueEntity(
    @Id val id: Long? = null,
    val name: String,
    val slug: String,
    // ...
) {
    fun toDomain(): Venue = Venue(id = id, name = name, slug = slug, ...)

    companion object {
        fun fromDomain(venue: Venue): VenueEntity = VenueEntity(id = venue.id, ...)
    }
}
```

## Consequences

- **Positive**: `events-core` has zero Spring Data dependencies, so any module can consume it without pulling in
  R2DBC. A consumer can carry Swagger annotations without polluting the shared model, though we chose not to (see
  AGENTS.md). Each app can map to its own table structure.
- **Negative**: boilerplate in the `toDomain()` and `fromDomain()` converters. A field added to the domain class must
  be added to the entity too, and the two can drift. Code generation or a mapping library could mitigate that later.
  For now the explicitness is preferred.
- Domain classes in `events-core` are intentionally kept free of Swagger/OpenAPI annotations to avoid coupling the shared library to web-layer concerns.

## References

- [`Venue.kt`](../../events-core/src/main/kotlin/de/norm/events/venue/Venue.kt) (domain class)
- [`VenueEntity.kt`](../../events-importer/src/main/kotlin/de/norm/events/venue/VenueEntity.kt) (persistence entity)
