package de.norm.events.event

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata declaring the event module's allowed dependencies.
 *
 * The module holds the three event enums and the money scale, and references no other module.
 * The shared model has no event aggregate: ADR-003's Status line says why.
 */
@ApplicationModule(allowedDependencies = [])
class EventModule
