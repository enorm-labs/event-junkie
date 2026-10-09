package de.norm.events.venuecheck

import java.net.URI

/**
 * Hosts whose terms forbid automated access (#356). The probe never sends a request to one, and a venue link
 * there is skipped rather than counted as a failure: it says nothing about whether the venue's own site answers.
 *
 * `robots.txt` does not cover these. Resident Advisor's terms forbid scripted access, GraphQL included, whatever
 * its `robots.txt` allows.
 */
internal object ForbiddenHosts {
    private val DOMAINS =
        listOf(
            "ra.co",
            "residentadvisor.net",
            "facebook.com",
            "fb.com",
            "fb.me",
            "instagram.com",
            "eventbrite.com",
            "eventbrite.de"
        )

    /** Whether [url] is on a forbidden host or a subdomain of one. A URL without a readable host is forbidden too. */
    fun isForbidden(url: String): Boolean {
        val host =
            runCatching { URI.create(url.trim()).host }
                .getOrNull()
                ?.lowercase()
                ?.removeSuffix(".")
                ?: return true
        return DOMAINS.any { host == it || host.endsWith(".$it") }
    }
}
