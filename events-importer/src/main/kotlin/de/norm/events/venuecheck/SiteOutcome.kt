package de.norm.events.venuecheck

/**
 * What one probe of a venue's site found. The names are stored in `venue_site_check.outcome`, so renaming one
 * needs a migration.
 */
enum class SiteOutcome {
    /** The URL answered 2xx or 3xx, after redirects. */
    OK,

    /**
     * The URL answered 4xx or 5xx, or its `robots.txt` answered 5xx (RFC 9309 reads that as a full disallow).
     * A 403 or 429 is not this but [SKIPPED]: see [VenueSiteProber.REFUSED_STATUSES].
     */
    HTTP,

    /** The host name did not resolve. */
    DNS,

    /** The TLS handshake failed: an expired or mismatched certificate, or no TLS at all. */
    TLS,

    /** No answer within the scraper's response or connect timeout. */
    TIMEOUT,

    /** The connection was refused or reset. */
    CONNECTION,

    /** Any other transport fault. */
    OTHER,

    /**
     * No answer about the site: no URL, a forbidden host, a `robots.txt` disallow, or a 403 or 429 (then with its
     * status). The failure run stays as it was.
     */
    SKIPPED;

    /** Whether this outcome adds one to the venue's run of failures. */
    val isFailure: Boolean get() = this != OK && this != SKIPPED
}

/** The outcome for one URL, or for one venue when [VenueSiteProber.probeVenue] combined its URLs. */
data class ProbeResult(
    val url: String?,
    val outcome: SiteOutcome,
    val httpStatus: Int? = null
) {
    /** A [SiteOutcome.SKIPPED] because the server answered 403 or 429. */
    val isRefused: Boolean get() = outcome == SiteOutcome.SKIPPED && httpStatus in VenueSiteProber.REFUSED_STATUSES
}
