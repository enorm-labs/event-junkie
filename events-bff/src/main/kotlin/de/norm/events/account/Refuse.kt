package de.norm.events.account

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** Ends the request with [status] and [reason] as the problem detail. The account routes validate their bodies by hand, see `architecture.instructions.md`. */
internal fun refuse(
    status: HttpStatus,
    reason: String
): Nothing = throw ResponseStatusException(status, reason)
