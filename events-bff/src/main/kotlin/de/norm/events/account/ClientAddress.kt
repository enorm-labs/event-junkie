package de.norm.events.account

import org.springframework.web.server.ServerWebExchange

/**
 * The address a request came from, for the per-address rate limit only: held in memory for an hour,
 * never stored or logged (LEGAL.md §7.5). Traefik sets no `forwardedHeaders.trustedIPs`, so it trusts
 * no peer and replaces a client's `X-Forwarded-For` with the address it sees. Its host ports make that
 * the visitor's (#1013). Staging proved the first half: 31 passkey-option requests, each with a new
 * forged header, met one counter (#4039). Without the header, the socket's peer.
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
