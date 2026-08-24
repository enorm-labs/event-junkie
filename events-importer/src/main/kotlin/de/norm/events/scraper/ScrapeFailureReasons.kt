package de.norm.events.scraper

import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

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
        is HttpFetchException -> {
            when (error.statusCode) {
                HTTP_TOO_MANY_REQUESTS -> "http_rate_limited"
                in CLIENT_ERRORS -> if (error.statusCode == HTTP_FORBIDDEN) "http_forbidden" else "http_4xx"
                in SERVER_ERRORS -> "http_5xx"
                else -> "http_other"
            }
        }

        // Ordered before IOException: both of these are IOExceptions, and a `when` takes the first
        // branch that matches.
        is UnknownHostException -> {
            "dns"
        }

        is TimeoutException -> {
            "timeout"
        }

        is IOException -> {
            "network"
        }

        // Everything the parsers throw when a page is not shaped the way the scraper expects —
        // a missing element, an unparseable date, a JSON field that changed type. This is the bucket
        // that means "the venue redesigned its site".
        is IllegalStateException, is IllegalArgumentException, is NullPointerException, is IndexOutOfBoundsException -> {
            "parse"
        }

        else -> {
            "other"
        }
    }

private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private val CLIENT_ERRORS = 400..499
private val SERVER_ERRORS = 500..599
