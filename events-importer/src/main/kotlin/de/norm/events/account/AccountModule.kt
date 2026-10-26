package de.norm.events.account

import org.springframework.modulith.ApplicationModule

/**
 * The importer's one write into the `accounts` schema the BFF owns at runtime (ADR-053): the sign-up switch (#3906).
 * Accounts themselves are the BFF's; this module never reads them.
 */
@ApplicationModule(allowedDependencies = [])
class AccountModule
