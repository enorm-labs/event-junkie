package de.norm.events.account

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for visitor accounts (ADR-053): sign-in by e-mail link, the session, and `/api/me`.
 * Writes only the `accounts` schema. Sends its mail through `mail`, and reads the events of what an account follows
 * through `event` (#3889).
 */
@ApplicationModule(allowedDependencies = ["mail", "common", "event"])
class AccountModule
