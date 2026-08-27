package de.norm.events.scraper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Instant

/**
 * Unit tests for [RobotsTxtFilter].
 *
 * The cache is stubbed throughout: what this class asserts is the **decision** the filter makes
 * from a [RobotsCheck], not how the rules were obtained. [RobotsRulesCacheTest] covers the fetching
 * and the parsing.
 *
 * The distinction that carries the most weight here is report-only versus enforcing. Phase 1 of
 * #790 ships with enforcement off, and a filter that quietly blocked in that state would stop
 * imports across every venue at once.
 */
class RobotsTxtFilterTest {
    private val next = mockk<ExchangeFunction>()

    private fun request(url: String) = ClientRequest.create(HttpMethod.GET, URI.create(url)).build()

    private fun cacheAnswering(
        allowed: Boolean,
        robotsTxtUrl: String? = "https://venue.example/robots.txt"
    ): RobotsRulesCache =
        mockk<RobotsRulesCache>().also {
            coEvery { it.check(any()) } returns
                RobotsCheck(
                    host = "venue.example",
                    robotsTxtUrl = robotsTxtUrl,
                    allowed = allowed,
                    checkedAt = Instant.EPOCH
                )
        }

    private fun stubNextReturnsOk() {
        every { next.exchange(any()) } returns Mono.just(ClientResponse.create(HttpStatus.OK).build())
    }

    @Nested
    inner class AllowedRequests {
        @Test
        fun `sends the request when robots txt permits it`() =
            runTest {
                stubNextReturnsOk()
                val filter = RobotsTxtFilter(cacheAnswering(allowed = true), enforced = true)

                val response = filter.filter(request("https://venue.example/events"), next).awaitSingle()

                response.statusCode() shouldBe HttpStatus.OK
                verify(exactly = 1) { next.exchange(any()) }
            }
    }

    @Nested
    inner class Defaults {
        @Test
        fun `enforcement is on unless a deployment turns it off`() {
            // The guard on the flag itself. Enforcement off is a survey mode (#790), and a default
            // that drifts back to it would leave a deployment ignoring robots.txt while every other
            // test here still passes — the filter would run, log, and forbid nothing.
            ScraperProperties().robotsEnforced shouldBe true
        }
    }

    @Nested
    inner class ReportOnly {
        @Test
        fun `still sends a disallowed request while enforcement is off`() =
            runTest {
                stubNextReturnsOk()
                val filter = RobotsTxtFilter(cacheAnswering(allowed = false), enforced = false)

                val response = filter.filter(request("https://venue.example/private"), next).awaitSingle()

                // The point of phase 1: the finding is recorded and the estate keeps importing.
                response.statusCode() shouldBe HttpStatus.OK
                verify(exactly = 1) { next.exchange(any()) }
            }
    }

    @Nested
    inner class Enforcing {
        @Test
        fun `blocks a disallowed request and never calls next`() =
            runTest {
                stubNextReturnsOk()
                val filter = RobotsTxtFilter(cacheAnswering(allowed = false), enforced = true)

                shouldThrow<RobotsDisallowedException> {
                    filter.filter(request("https://venue.example/private"), next).awaitSingle()
                }

                // The assertion that matters: the request was not sent, not merely that it failed.
                verify(exactly = 0) { next.exchange(any()) }
            }

        @Test
        fun `names the url and the rules that blocked it`() =
            runTest {
                val filter = RobotsTxtFilter(cacheAnswering(allowed = false), enforced = true)

                val error =
                    shouldThrow<RobotsDisallowedException> {
                        filter.filter(request("https://venue.example/private"), next).awaitSingle()
                    }

                error.url shouldBe "https://venue.example/private"
                error.robotsTxtUrl shouldBe "https://venue.example/robots.txt"
            }

        @Test
        fun `is classified as its own failure reason, not as a parse failure`() {
            val error = RobotsDisallowedException("https://venue.example/private", robotsTxtUrl = null)

            scrapeFailureReason(error) shouldBe "robots_disallowed"
        }
    }
}
