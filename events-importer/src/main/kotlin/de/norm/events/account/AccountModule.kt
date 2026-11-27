package de.norm.events.account

import org.springframework.modulith.ApplicationModule

/**
 * The importer's side of the `accounts` schema the BFF owns at runtime (ADR-053): the sign-up switch (#3906), the
 * problem reports and the flag that withdraws reporting from an account (#4399, #5604), and a read-only list of the accounts for the operator (#5317).
 */
@ApplicationModule(allowedDependencies = ["common"])
class AccountModule
