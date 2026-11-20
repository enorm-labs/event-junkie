package de.norm.events.account

import org.springframework.modulith.ApplicationModule

/**
 * The importer's side of the `accounts` schema the BFF owns at runtime (ADR-053): the sign-up switch (#3906), the
 * problem reports and their allowlist (#4399), and a read-only list of the accounts for the operator (#5317).
 */
@ApplicationModule(allowedDependencies = [])
class AccountModule
