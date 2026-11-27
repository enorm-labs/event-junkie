package de.norm.events.common

import org.springframework.modulith.ApplicationModule

/**
 * Marks the `common` package as an OPEN Spring Modulith module: every type in it, sub-packages
 * included, is API. Shared, dependency-free plumbing only.
 *
 * OPEN does not lift `allowedDependencies`. A module that uses `common` lists it, or
 * `ModularityTests` fails. An `inline` function leaves no reference in the bytecode, so a module
 * that calls only one, such as [runCatchingCancellable], passes without the entry.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
class CommonModule
