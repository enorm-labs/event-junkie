package de.norm.events.scraper

import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * The closed set of `reason` values, in one place so the counter, the `event_source.last_failure_reason`
 * column and the `importer.sources.failed{reason}` gauge cannot disagree about it (#708). The gauge
 * publishes every entry of [ALL] on every tick, zero included: a reason absent from the exposition is
 * indistinguishable from one at zero, and a rule written on it then reads health (#618).
 */
internal object ScrapeFailureReason {
    const val ROBOTS_UNREADABLE = "robots_unreadable"
    const val ROBOTS_DISALLOWED = "robots_disallowed"
    const val HTTP_RATE_LIMITED = "http_rate_limited"
    const val HTTP_FORBIDDEN = "http_forbidden"
    const val HTTP_4XX = "http_4xx"
    const val HTTP_5XX = "http_5xx"
    const val HTTP_OTHER = "http_other"
    const val DNS = "dns"
    const val TIMEOUT = "timeout"
    const val NETWORK = "network"
    const val PARSE = "parse"
    const val OTHER = "other"

    val ALL: List<String> =
        listOf(
            ROBOTS_UNREADABLE,
            ROBOTS_DISALLOWED,
            HTTP_RATE_LIMITED,
            HTTP_FORBIDDEN,
            HTTP_4XX,
            HTTP_5XX,
            HTTP_OTHER,
            DNS,
            TIMEOUT,
            NETWORK,
            PARSE,
            OTHER
        )
}

/**
 * Classifies a failed import into the `reason` tag of `importer.scrape.failures`.
 *
 * **A 403 is not a parse failure**, and that distinction is the reason the tag exists at all
 * (PLATFORM_SETUP.md §7). They need different responses: one is the venue blocking us — a
 * User-Agent, a rate, or a deliberate block, none of which more code will fix — and the other is the
 * venue's markup having moved, which is a scraper change. Aggregated into one counter they are
 * indistinguishable, and the graph says only "something is wrong".
 *
 * **The cardinality rule this function exists to enforce: every return value below is a constant.** Nothing derived from the exception message, the URL,
 * or anything else the venue controls may become a tag value: Prometheus creates one time series per
 * distinct tag combination, and a tag fed by free text is unbounded — a venue that returns a
 * different error string per request would, on its own, exhaust the metrics backend. That failure is
 * gradual and looks like the monitoring being slow rather than like a bug here.
 *
 * `http_4xx` / `http_5xx` rather than the exact status for the same reason at a smaller scale: the
 * class is what determines the response, and the exact code is already in the log line and in
 * `event_source.last_error`, which is where you look once an alert has told you where to look.
 */
internal fun scrapeFailureReason(error: Throwable): String =
    when (error) {
        // Its own reason for the same purpose as `http_forbidden`: a policy answer, not a parse
        // failure, and no amount of scraper work fixes it. Merging the two hides the one case where
        // the response is to stop importing the venue rather than to repair a selector (#790).
        // Split because the two causes want opposite responses (#887). A real prohibition is a
        // decision somebody has to take; an unreadable robots.txt is a venue outage that fixes
        // itself. One alert for both fires on every bad day a venue has, and gets muted.
        is RobotsDisallowedException -> {
            if (error.unreadableStatus != null) {
                ScrapeFailureReason.ROBOTS_UNREADABLE
            } else {
                ScrapeFailureReason.ROBOTS_DISALLOWED
            }
        }

        is HttpFetchException -> {
            when (error.statusCode) {
                HTTP_TOO_MANY_REQUESTS -> ScrapeFailureReason.HTTP_RATE_LIMITED
                HTTP_FORBIDDEN -> ScrapeFailureReason.HTTP_FORBIDDEN
                in CLIENT_ERRORS -> ScrapeFailureReason.HTTP_4XX
                in SERVER_ERRORS -> ScrapeFailureReason.HTTP_5XX
                else -> ScrapeFailureReason.HTTP_OTHER
            }
        }

        // Ordered before IOException: both of these are IOExceptions, and a `when` takes the first
        // branch that matches.
        is UnknownHostException -> {
            ScrapeFailureReason.DNS
        }

        is TimeoutException -> {
            ScrapeFailureReason.TIMEOUT
        }

        is IOException -> {
            ScrapeFailureReason.NETWORK
        }

        // Everything the parsers throw when a page is not shaped the way the scraper expects —
        // a missing element, an unparseable date, a JSON field that changed type. This is the bucket
        // that means "the venue redesigned its site".
        is IllegalStateException, is IllegalArgumentException, is NullPointerException, is IndexOutOfBoundsException -> {
            ScrapeFailureReason.PARSE
        }

        else -> {
            ScrapeFailureReason.OTHER
        }
    }

private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private val CLIENT_ERRORS = 400..499
private val SERVER_ERRORS = 500..599
