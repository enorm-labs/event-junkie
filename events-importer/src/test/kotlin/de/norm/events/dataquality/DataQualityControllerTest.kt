package de.norm.events.dataquality

import de.norm.events.BaseControllerTest
import org.junit.jupiter.api.Test

/**
 * The HTTP contract, and mostly the error cases — the happy path is covered end to end by
 * [DataQualityReportIntegrationTest] against seeded data, which asserts far more than a status code.
 *
 * The 400s carry weight here. **An unknown `?issue=` must not return an empty list**: an empty list
 * is the honest answer to "this metric has no offenders", so returning it for a typo would report
 * perfect data quality for a metric that does not exist. That is the most misleading thing this
 * endpoint could do, and it is one `?:` away.
 */
class DataQualityControllerTest : BaseControllerTest() {
    @Test
    fun `the report is served even with no events at all`() {
        webTestClient
            .get()
            .uri("/api/admin/data-quality")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.overall.totalEvents")
            .isEqualTo(0)
            .jsonPath("$.overall.concertsWithoutArtistPct")
            .isEqualTo(0.0)
            .jsonPath("$.perSource")
            .isArray
    }

    @Test
    fun `an unknown issue is a 400 that names every valid value, not an empty worklist`() {
        webTestClient
            .get()
            .uri("/api/admin/data-quality/worklist?issue=missingArtist")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { detail ->
                check(detail.contains("concertsWithoutArtist")) {
                    "the error must list the valid values, or the caller has to read the source: $detail"
                }
            }
    }

    @Test
    fun `a limit outside the allowed range is a 400 rather than an unbounded query`() {
        webTestClient
            .get()
            .uri("/api/admin/data-quality/worklist?issue=concertsWithoutArtist&limit=100000")
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a negative offset is a 400`() {
        webTestClient
            .get()
            .uri("/api/admin/data-quality/worklist?issue=concertsWithoutArtist&offset=-1")
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a valid issue with no offenders is an empty page, which is a different answer`() {
        webTestClient
            .get()
            .uri("/api/admin/data-quality/worklist?issue=concertsWithoutArtist")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.issue")
            .isEqualTo("concertsWithoutArtist")
            .jsonPath("$.count")
            .isEqualTo(0)
    }
}
