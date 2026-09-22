# ADR-006: Spring Modulith for Module Boundary Enforcement

## Status

**Accepted — Spring Modulith, with each module declaring its `allowedDependencies` and a test failing the build on a
violation.** The one exception is a module declared `Type.OPEN`, which is `common` in each application.

The structure the three applications ended up with is drawn in
[docs/architecture/modules-events-core.md](../architecture/modules-events-core.md),
[modules-events-bff.md](../architecture/modules-events-bff.md) and
[modules-events-importer.md](../architecture/modules-events-importer.md). Those diagrams are generated from the modules
and the build fails when they stop matching.

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
- **Grouping adds no discoverability benefit at this size.** The distinction between domain (`artist`, `event`, `promoter`, `venue`) and infrastructure
  (`slug`, `scraper`) is obvious from context. Spring Modulith reads 6 modules in `events-core`, 10 in `events-bff` and 14 in `events-importer`. Each
  application sees its own packages, plus `licence` from `events-core` on the classpath. **The importer is the one to watch.** It gains a module every few
  venues, and 15 was the number at which this was to be revisited.

## Consequences

- **Positive**: the build enforces module boundaries, rather than convention alone, and CI catches a violation at
  once. `allowedDependencies` is living documentation of the dependency graph. Spring Modulith's `Documenter` also
  writes PlantUML diagrams and module canvases into `build/spring-modulith-docs/`. Those stay there, because GitHub
  does not render PlantUML. The Mermaid diagrams linked above are the committed ones (#1721).
- **Negative**: every new feature module needs a `*Module.kt` marker and has to declare its dependencies. A new
  cross-module dependency means editing `allowedDependencies`. That friction is intentional: it forces a conscious
  decision.
- The `spring-modulith-starter-core` dependency is declared with `api()` scope in `events-core` so it's transitively available to all consumers.

## References

- [Spring Modulith documentation](https://docs.spring.io/spring-modulith/reference/)
- [`VenueModule.kt`](../../events-importer/src/main/kotlin/de/norm/events/venue/VenueModule.kt) (example marker)
- [`ModularityTests.kt`](../../events-importer/src/test/kotlin/de/norm/events/ModularityTests.kt)
