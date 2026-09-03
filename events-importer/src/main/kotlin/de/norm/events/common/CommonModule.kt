package de.norm.events.common

import org.springframework.modulith.ApplicationModule

/**
 * Marks the `common` package as an OPEN Spring Modulith module, so every feature module can use
 * [PageResponse] without declaring a dependency on it. Shared, dependency-free API plumbing only.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
class CommonModule
