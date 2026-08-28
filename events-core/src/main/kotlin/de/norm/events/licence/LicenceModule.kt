package de.norm.events.licence

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the licence vocabulary.
 *
 * Its own package rather than a member of `event`, and the reason is structural. The BFF's gate
 * reads this enum while the `event` module depends on that gate, so leaving the enum in
 * `de.norm.events.event` made Modulith see `event → sourcelicence → event` and fail the build —
 * these packages are split across the core jar and each application (#283).
 *
 * Depends on nothing, and must stay that way. It is a vocabulary, not a model.
 */
@ApplicationModule(allowedDependencies = [])
class LicenceModule
