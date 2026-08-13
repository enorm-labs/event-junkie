# ADR-003: Separation of Persistence Entities and Domain Model

## Status

Accepted

## Context

The project has a shared library (`events-core`) consumed by multiple applications (BFF, importer). The domain model (e.g. `Venue`, `Artist`, `Event`) needs to
be accessible to all consumers without coupling them to persistence concerns.

Two approaches were considered:

1. **Single class for both domain and persistence** — Annotate domain classes with `@Table`, `@Id`, etc. Simpler, less boilerplate. Common in many Spring Data
   projects. However, this couples the shared library to Spring Data R2DBC, meaning every consumer must pull in R2DBC dependencies even if they don't need them
   (e.g. the BFF might use a different data access strategy in the future).
2. **Separate domain classes and persistence entities** — Keep domain classes as plain Kotlin data classes in
   `events-core`, and define annotated persistence entities in each application module that needs them.

## Decision

Use **separate classes**: plain Kotlin data classes in `events-core` for the domain model, and `*Entity.kt`
classes with Spring Data annotations (`@Table`, `@Id`) in the importer (and BFF where needed).

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

- **Positive**: `events-core` has zero Spring Data dependencies and can be consumed by any module without pulling in R2DBC; domain classes can carry Swagger
  annotations in consumers without polluting the shared model (we chose not to — see AGENTS.md); each app can map to its own table structure if needed.
- **Negative**: Boilerplate in `toDomain()`/`fromDomain()` converters; fields added to the domain class must also be added to the entity (risk of drift). This
  could be mitigated with code generation or mapping libraries in the future, but for now the explicitness is preferred.
- Domain classes in `events-core` are intentionally kept free of Swagger/OpenAPI annotations to avoid coupling the shared library to web-layer concerns.

## References

- [`Venue.kt`](../../events-core/src/main/kotlin/de/norm/events/venue/Venue.kt) (domain class)
- [`VenueEntity.kt`](../../events-importer/src/main/kotlin/de/norm/events/venue/VenueEntity.kt) (persistence entity)
