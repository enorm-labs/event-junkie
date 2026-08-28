package de.norm.events.scraper

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/** Bean name of the shared scraper [WebClient]; inject with `@Qualifier(SCRAPER_WEB_CLIENT)`. */
const val SCRAPER_WEB_CLIENT = "scraperWebClient"

/**
 * Bean name of the scraper [WebClient] **without** the `robots.txt` check.
 *
 * Everything [SCRAPER_WEB_CLIENT] has except [RobotsTxtFilter], including the *same*
 * [PerHostThrottlingFilter] instance — so a request through it is spaced against the venue's other
 * requests rather than keeping a second, independent timer.
 *
 * It exists because [RobotsRulesCache] has to read `robots.txt` somehow, and reading it through a
 * client that checks `robots.txt` first is a recursion. A separate bean rather than a re-entrancy
 * flag on the request: the flag would have to be set correctly at one call site forever, and a bean
 * cannot be forgotten.
 */
const val SCRAPER_BASE_WEB_CLIENT = "scraperBaseWebClient"

/**
 * The identifying `User-Agent` every scraper request carries.
 *
 * The `Mozilla/5.0 (compatible; …)` prefix is the convention every well-behaved crawler follows,
 * Googlebot and bingbot included, and it is not a masquerade: the string names the product and
 * carries a contact URL, which is what a venue operator needs (ADR-007 best-practice #3). **Keep
 * the product token and the URL** if it ever changes — and keep the token in step with
 * [RobotsRulesCache.ROBOT_NAMES], which is what matches a `robots.txt` group.
 */
const val SCRAPER_USER_AGENT = "Mozilla/5.0 (compatible; EventJunkie/1.0; +https://github.com/enorm-labs/event-junkie)"

/**
 * Builds the single [WebClient] instance shared by every outbound scraper request —
 * both the HTML fetches of [HtmlFetcher] and the JSON/API fetches of [ApiClient].
 *
 * Sharing one instance is deliberate: the [PerHostThrottlingFilter] holds per-host
 * throttle state, so a single filter instance guarantees that HTML and API requests to
 * the *same* host are politeness-throttled **together** rather than each keeping its own
 * independent timer (ADR-007 §"Per-Host Politeness Throttling").
 *
 * The client is configured to **follow HTTP redirects** (`followRedirect(true)`), a response
 * timeout, a transparent identifying `User-Agent` (ADR-007 best-practice #3), and the throttling
 * filter. Redirect following is on because some venues expose only redirecting entry/detail URLs —
 * e.g. Alte Kantine's overview links each event as a `?p=<id>` permalink that 301-redirects to its
 * canonical `/portfolio/<slug>/` page (ADR-007 best-practice #6, canonical URLs); Reactor Netty
 * follows redirects on the same host transparently, so scrapers receive the final document. The
 * response body size limit is controlled by the standard `spring.http.codecs.max-in-memory-size`
 * property (defaults to 256KB; set to 8MB in application.yaml for large venue pages, API payloads and images).
 */
@Configuration
class ScraperHttpClientConfig {
    /**
     * One filter instance for both clients, so a `robots.txt` fetch and the page fetch that
     * triggered it are throttled against the *same* per-host timer rather than each keeping its own.
     */
    @Bean
    fun perHostThrottlingFilter(scraperProperties: ScraperProperties): PerHostThrottlingFilter = PerHostThrottlingFilter(scraperProperties.politeDelayMillis)

    @Bean(SCRAPER_BASE_WEB_CLIENT)
    fun scraperBaseWebClient(
        webClientBuilder: WebClient.Builder,
        scraperProperties: ScraperProperties,
        throttle: PerHostThrottlingFilter
    ): WebClient = baseClient(webClientBuilder, scraperProperties).filter(throttle).build()

    @Bean(SCRAPER_WEB_CLIENT)
    fun scraperWebClient(
        webClientBuilder: WebClient.Builder,
        scraperProperties: ScraperProperties,
        throttle: PerHostThrottlingFilter,
        rulesCache: RobotsRulesCache
    ): WebClient =
        baseClient(webClientBuilder, scraperProperties)
            // Ordered before the throttle so the robots.txt fetch behind this filter is itself
            // throttled — see RobotsTxtFilter's KDoc.
            .filter(RobotsTxtFilter(rulesCache, scraperProperties.robotsEnforced))
            .filter(throttle)
            .build()

    /** Connector, timeout and `User-Agent` — everything both clients share. */
    private fun baseClient(
        webClientBuilder: WebClient.Builder,
        scraperProperties: ScraperProperties
    ): WebClient.Builder =
        webClientBuilder
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient
                        .create()
                        .followRedirect(true)
                        .responseTimeout(scraperProperties.responseTimeout)
                )
            ).defaultHeader("User-Agent", SCRAPER_USER_AGENT)
}
