package de.norm.events.account

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for visitor accounts (ADR-053): sign-in by e-mail link, the session, and `/api/me`.
 * Writes only the `accounts` schema. Sends its mail through `mail`.
 */
@ApplicationModule(allowedDependencies = ["mail", "common"])
class AccountModule
