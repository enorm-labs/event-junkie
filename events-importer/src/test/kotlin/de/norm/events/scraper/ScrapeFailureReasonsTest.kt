package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * The `reason` tag on `importer.scrape.failures`.
 *
 * The point of the tag is that **a 403 is not a parse failure** — one is the venue blocking us and
 * the other is the venue's markup having moved, and they need different responses. So the tests
 * below are about the distinctions actually being made, not about coverage of a `when`.
 */
class ScrapeFailureReasonsTest {
    @Nested
    inner class HttpStatus {
        @Test
        fun `a 403 is reported as forbidden, not lumped in with other client errors`() {
            scrapeFailureReason(HttpFetchException(403, "https://venue.example/events")) shouldBe "http_forbidden"
        }

        @Test
        fun `a 429 is rate limiting, which is a different response from a block`() {
            scrapeFailureReason(HttpFetchException(429, "https://venue.example/events")) shouldBe "http_rate_limited"
        }

        @Test
        fun `other client errors share a bucket`() {
            scrapeFailureReason(HttpFetchException(404, "https://venue.example/events")) shouldBe "http_4xx"
            scrapeFailureReason(HttpFetchException(410, "https://venue.example/events")) shouldBe "http_4xx"
        }

        @Test
        fun `server errors are separated from client errors`() {
            scrapeFailureReason(HttpFetchException(500, "https://venue.example/events")) shouldBe "http_5xx"
            scrapeFailureReason(HttpFetchException(503, "https://venue.example/events")) shouldBe "http_5xx"
        }

        @Test
        fun `a status outside both ranges still produces a constant`() {
            scrapeFailureReason(HttpFetchException(302, "https://venue.example/events")) shouldBe "http_other"
        }
    }

    @Nested
    inner class Transport {
        @Test
        fun `an unresolvable host is DNS rather than generic network`() {
            scrapeFailureReason(UnknownHostException("venue.example")) shouldBe "dns"
        }

        @Test
        fun `a timeout is its own reason`() {
            scrapeFailureReason(TimeoutException("took too long")) shouldBe "timeout"
        }

        /**
         * [UnknownHostException] and [SocketTimeoutException] are both [IOException]s, so this asserts
         * the `when` branches are ordered such that the specific ones win. Reordering them would be
         * silent: every case would still produce *a* reason, just the wrong one.
         */
        @Test
        fun `the specific transport failures win over the IOException catch-all`() {
            scrapeFailureReason(IOException("connection reset")) shouldBe "network"
            scrapeFailureReason(UnknownHostException("venue.example")) shouldBe "dns"
        }
    }

    @Nested
    inner class Parsing {
        /**
         * The bucket that means "the venue redesigned its site" — the failure this whole metric
         * exists to make visible.
         */
        @Test
        fun `the exceptions a parser throws when a page is not shaped as expected are parse failures`() {
            scrapeFailureReason(IllegalStateException("no event cards found")) shouldBe "parse"
            scrapeFailureReason(IllegalArgumentException("unparseable date '32 Foo'")) shouldBe "parse"
            scrapeFailureReason(NullPointerException()) shouldBe "parse"
            scrapeFailureReason(IndexOutOfBoundsException("Index 3 out of bounds for length 0")) shouldBe "parse"
        }

        @Test
        fun `anything unrecognised still produces a constant rather than free text`() {
            scrapeFailureReason(RuntimeException("something nobody anticipated")) shouldBe "other"
        }
    }

    /**
     * The cardinality guard. A tag value derived from anything a venue controls is unbounded, and
     * Prometheus creates one time series per distinct combination — so a reason built from an
     * exception message would let a remote site grow the metrics backend without limit. This asserts
     * the property directly rather than trusting that nobody will reach for `error.message`.
     */
    @Nested
    inner class RobotsRefusals {
        @Test
        fun `a venue's own Disallow is reported as a prohibition`() {
            val error = RobotsDisallowedException("https://venue.example/events", "https://venue.example/robots.txt")

            scrapeFailureReason(error) shouldBe "robots_disallowed"
        }

        @Test
        fun `an unreadable robots txt is its own reason, because it is a venue outage rather than a decision`() {
            val error = RobotsDisallowedException("https://venue.example/events", null, unreadableStatus = 503)

            scrapeFailureReason(error) shouldBe "robots_unreadable"
        }

        @Test
        fun `the unreadable case says the venue forbade nothing, so nobody hunts for a rule`() {
            val error = RobotsDisallowedException("https://venue.example/events", null, unreadableStatus = 503)

            error.message!!.contains("could not be read (HTTP 503)") shouldBe true
            error.message!!.contains("forbidden nothing") shouldBe true
        }
    }

    @Test
    fun `no reason ever contains anything from the exception message or URL`() {
        val hostile = "a-very-distinctive-string-only-this-test-uses"
        val reasons =
            listOf(
                scrapeFailureReason(HttpFetchException(404, "https://venue.example/$hostile")),
                scrapeFailureReason(IllegalStateException(hostile)),
                scrapeFailureReason(RuntimeException(hostile)),
                scrapeFailureReason(IOException(hostile)),
                // The robots message carries the URL, so it is the likeliest of these to leak one.
                scrapeFailureReason(RobotsDisallowedException("https://venue.example/$hostile", null, 503)),
                scrapeFailureReason(RobotsDisallowedException("https://venue.example/$hostile", hostile))
            )
        reasons.forEach { it.contains(hostile) shouldBe false }
    }
}
