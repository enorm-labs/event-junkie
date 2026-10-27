package de.norm.events.push

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URISyntaxException

/**
 * The push services a subscription may point at. The browser hands over the endpoint, and the BFF later posts to it,
 * so an endpoint outside this list is refused: otherwise a visitor could make the BFF call any address.
 *
 * Each entry is an origin such as `https://fcm.googleapis.com`. A host may start with a wildcard label, as Microsoft's
 * `*.notify.windows.com` does. An `https` entry allows only the default port.
 */
@Component
class PushEndpoints(
    @Value("\${app.push.allowed-origins}") allowed: List<String>
) {
    // Split by hand: `URI` reads no host from `*.notify.windows.com`.
    private val origins = allowed.map { it.substringBefore("://") to it.substringAfter("://").lowercase() }

    fun isAllowed(endpoint: String): Boolean {
        val uri =
            try {
                URI(endpoint)
            } catch (_: URISyntaxException) {
                return false
            }
        val host = uri.host ?: return false
        if (uri.userInfo != null || endpoint.length > MAX_LENGTH) return false
        return origins.any { (scheme, pattern) ->
            scheme == uri.scheme &&
                hostMatches(pattern, host.lowercase()) &&
                (scheme != "https" || uri.port == -1 || uri.port == HTTPS_PORT)
        }
    }

    private fun hostMatches(
        pattern: String,
        host: String
    ): Boolean =
        if (pattern.startsWith("*.")) {
            host.endsWith(pattern.substring(1)) && host.length > pattern.length - 1
        } else {
            host == pattern
        }

    private companion object {
        const val HTTPS_PORT = 443

        /** Real endpoints are a few hundred characters. */
        const val MAX_LENGTH = 1024
    }
}
