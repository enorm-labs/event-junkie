package de.norm.events.account

import org.springframework.web.server.ServerWebExchange

/**
 * The address a request came from, for the per-address rate limit only: held in memory for an hour,
 * never stored or logged (LEGAL.md §7.5). Traefik appends the client to `X-Forwarded-For`, so the
 * last entry is the one a client cannot forge. Without the header, the socket's peer.
 */
internal fun clientAddress(exchange: ServerWebExchange): String =
    exchange.request.headers
        .getFirst("X-Forwarded-For")
        ?.split(',')
        ?.lastOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: exchange.request.remoteAddress
            ?.address
            ?.hostAddress
        ?: "unknown"
