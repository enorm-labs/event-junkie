package de.norm.events.account

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for visitor accounts (ADR-053): sign-in by e-mail link, the session, and `/api/me`.
 * Writes only the `accounts` schema. Sends its mail through `mail` and its push notifications through `push` (#3894), and
 * reads the events of what an account follows through `event` (#3889). Asks `musicbrainz` for the act a watch picks (#5214).
 */
@ApplicationModule(allowedDependencies = ["mail", "push", "common", "event", "musicbrainz"])
class AccountModule
