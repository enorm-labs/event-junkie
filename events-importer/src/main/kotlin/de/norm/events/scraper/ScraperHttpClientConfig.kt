package de.norm.events.scraper

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/** Bean name of the shared scraper [WebClient]; inject with `@Qualifier(SCRAPER_WEB_CLIENT)`. */
const val SCRAPER_WEB_CLIENT = "scraperWebClient"

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
 * property (defaults to 256KB; set to 2MB in application.yaml for large venue pages / API payloads).
 */
@Configuration
class ScraperHttpClientConfig {
    @Bean(SCRAPER_WEB_CLIENT)
    fun scraperWebClient(
        webClientBuilder: WebClient.Builder,
        scraperProperties: ScraperProperties
    ): WebClient =
        webClientBuilder
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient
                        .create()
                        .followRedirect(true)
                        .responseTimeout(scraperProperties.responseTimeout)
                )
            ).defaultHeader(
                "User-Agent",
                "Mozilla/5.0 (compatible; EventJunkie/1.0; +https://github.com/enorm-labs/event-junkie)"
            ).filter(
                PerHostThrottlingFilter(scraperProperties.politeDelayMillis)
            ).build()
}
