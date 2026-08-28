package de.norm.events.image

import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

/**
 * Decides whether an image URL may be requested at all.
 *
 * **The URL comes out of scraped HTML, so this is server-side request forgery** (ADR-019 §4). A
 * venue page we do not control names the address, and the importer runs inside a private network
 * next to a database and a k3s API.
 *
 * Two independent checks, because either alone is bypassable. The scheme rules out `file:`, `gopher:`
 * and the rest. The address check rules out the private ranges — and it is done on the **resolved**
 * address, because `evil.test` resolving to `10.1.1.10` looks like an ordinary hostname until it is
 * looked up.
 *
 * **This is a control and not a guarantee.** Resolution here and resolution by the HTTP client are
 * two separate lookups, so a DNS entry that changes between them defeats it. Closing that needs a
 * custom resolver on the connector, which is worth doing when this leaves the importer's own
 * network; it is recorded rather than pretended away.
 *
 * **`open` only so a test can permit loopback.** [ImageFetcher]'s own suite runs against a local
 * HTTP server, which this class refuses by design. The refusal is asserted in [ImageUrlValidatorTest]
 * against the real implementation, so overriding it there costs no coverage of the control itself.
 */
@Component
open class ImageUrlValidator {
    /**
     * The reason a URL was refused, or null when it may be fetched.
     *
     * One guard clause per check, which is why it exceeds the return-count rule. A security control
     * that reads top to bottom is one a reviewer can check; the nested form hides which condition
     * admitted a URL.
     */
    @Suppress("ReturnCount")
    open fun reject(url: String): String? {
        val uri =
            try {
                URI(url)
            } catch (_: URISyntaxException) {
                // URI(String) throws the checked URISyntaxException, not IllegalArgumentException.
                // Catching the wrong one let a malformed URL leave here as an exception instead of
                // as a refusal, which the caller would have recorded as a transport fault.
                return "malformed URL"
            }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "scheme is not http or https"

        val host = uri.host ?: return "no host"

        val addresses =
            try {
                InetAddress.getAllByName(host)
            } catch (_: UnknownHostException) {
                return "host does not resolve"
            }

        // Every address, not the first. A host with one public and one private address is otherwise
        // admitted on the public one and connected on whichever the client picks.
        return addresses.firstOrNull { it.isBlocked() }?.let { "resolves to a blocked address" }
    }

    /**
     * Whether one address is somewhere the importer must not be pointed at.
     *
     * `isSiteLocalAddress` covers 10/8, 172.16/12 and 192.168/16. The rest are named because the JDK
     * has no single predicate for them and each has been used for this: loopback reaches the node's
     * own services, link-local reaches cloud metadata at 169.254.169.254, and a wildcard or multicast
     * address is never a legitimate image host.
     */
    private fun InetAddress.isBlocked(): Boolean =
        isLoopbackAddress ||
            isSiteLocalAddress ||
            isLinkLocalAddress ||
            isAnyLocalAddress ||
            isMulticastAddress ||
            isUniqueLocalAddress()

    /** IPv6 `fc00::/7`, which `isSiteLocalAddress` does not report. */
    private fun InetAddress.isUniqueLocalAddress(): Boolean = address.size == IPV6_BYTES && (address[0].toInt() and UNIQUE_LOCAL_MASK) == UNIQUE_LOCAL_PREFIX

    private companion object {
        const val IPV6_BYTES = 16
        const val UNIQUE_LOCAL_MASK = 0xFE
        const val UNIQUE_LOCAL_PREFIX = 0xFC
    }
}
