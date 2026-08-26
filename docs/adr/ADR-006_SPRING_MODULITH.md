# ADR-006: Spring Modulith for Module Boundary Enforcement

## Status

**Accepted — Spring Modulith, with each module declaring its `allowedDependencies` and a test failing the build on a
violation.**

## Context

The application is organized by feature and domain: `venue`, `artist`, `event`, `promoter`. As the codebase grows,
modules easily develop unintended dependencies. The venue module imports something from the event module by accident,
and the coupling is circular or simply unwanted.

Two approaches were considered:

1. **Convention + code review** — Rely on developers to respect module boundaries. No tooling enforcement. Works for small teams but breaks down as the codebase
   scales.
2. **Spring Modulith** — Declare module boundaries with annotations and verify them automatically in tests. Catches violations at build time.

## Decision

Use **Spring Modulith** to enforce module boundaries across all three Gradle subprojects (`events-core`,
`events-bff`, `events-importer`).

Each direct sub-package under `de.norm.events` is an application module. Module metadata is declared via a `*Module.kt` marker class:

```kotlin
@ApplicationModule(allowedDependencies = [])
class VenueModule  // Self-contained, no dependencies on other modules

@ApplicationModule(allowedDependencies = ["artist", "venue", "promoter"])
class EventModule  // Events depend on artists, venues, and promoters
```

`ModularityTests` in each subproject verify the declared structure:

```kotlin
class ModularityTests {
    @Test
    fun `verify modular structure`() {
        ApplicationModules.of(EventsImporterApplication::class.java).verify()
    }
}
```

This test fails the build if any module accesses another module's internals or uses an undeclared dependency.

### Flat Package Structure (no parent grouping)

The decision to keep all modules as **direct sub-packages** of `de.norm.events` (flat structure) is deliberate. Grouping domain modules under
`de.norm.events.domain.*` and infrastructure modules under
`de.norm.events.infrastructure.*` was considered and rejected:

- **Spring Modulith module detection depends on it.** Modulith treats each direct sub-package of the base package as a module. Nesting `artist` under
  `domain.artist` would make `domain` the module, collapsing `artist`, `event`, `promoter`, and `venue` into a single module and losing all inter-module
  boundary enforcement.
- **Workarounds add complexity without value.** Using `@ApplicationModule(type = Type.OPEN)` or
  `@NamedInterface` to restore sub-module detection is verbose and fragile.
- **Flat is the Spring Modulith convention.** The official documentation recommends direct sub-packages as modules.
- **At 6 packages, grouping adds no discoverability benefit.** The distinction between domain (`artist`, `event`, `promoter`, `venue`) and infrastructure
  (`slug`, `scraper`) is already obvious from context. Revisit if the project grows beyond ~15 modules.

## Consequences

- **Positive**: the build enforces module boundaries, rather than convention alone, and CI catches a violation at
  once. `allowedDependencies` is living documentation of the dependency graph. Spring Modulith can also generate
  module documentation into `build/spring-modulith-docs/`.
- **Negative**: every new feature module needs a `*Module.kt` marker and has to declare its dependencies. A new
  cross-module dependency means editing `allowedDependencies`. That friction is intentional: it forces a conscious
  decision.
- The `spring-modulith-starter-core` dependency is declared with `api()` scope in `events-core` so it's transitively available to all consumers.

## References

- [Spring Modulith documentation](https://docs.spring.io/spring-modulith/reference/)
- [`VenueModule.kt`](../../events-importer/src/main/kotlin/de/norm/events/venue/VenueModule.kt) (example marker)
- [`ModularityTests.kt`](../../events-importer/src/test/kotlin/de/norm/events/ModularityTests.kt)
