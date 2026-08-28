package de.norm.events.sourcelicence

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the per-source licence gate.
 *
 * Depends only on the `licence` vocabulary. The gate answers a question about a source and never sees an event, which is
 * what keeps `event` → `sourcelicence` a one-way edge: the caller applies the answer to its own
 * types. Handing this module an [de.norm.events.event.EventEntity] to redact would be the same
 * behaviour and a cycle.
 */
@ApplicationModule(allowedDependencies = ["licence"])
class SourceLicenceModule
