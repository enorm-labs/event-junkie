package de.norm.events.musicbrainz

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/** Bean name of the MusicBrainz [WebClient]; inject with `@Qualifier(MUSICBRAINZ_WEB_CLIENT)`. */
const val MUSICBRAINZ_WEB_CLIENT = "musicBrainzWebClient"

/**
 * The [WebClient] every MusicBrainz request goes through.
 *
 * **Its own client, not the scraper's.** The scraper's carries the `robots.txt` filter and a
 * 200 ms per-host throttle; WS/2 is an API with published terms and a one-a-second limit, so it
 * gets a slower pace ([MusicBrainzClient] keeps it) and no robots check. The `User-Agent` is the
 * form MusicBrainz asks for — product, version and a contact URL — and it is not optional: a
 * request without a contact earns 503s (ADR-031).
 */
@Configuration
class MusicBrainzHttpClientConfig {
    @Bean(MUSICBRAINZ_WEB_CLIENT)
    fun musicBrainzWebClient(
        webClientBuilder: WebClient.Builder,
        properties: MusicBrainzProperties,
        buildProperties: ObjectProvider<BuildProperties>
    ): WebClient =
        webClientBuilder
            .baseUrl(properties.baseUrl)
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient
                        .create()
                        .compress(true)
                        .responseTimeout(properties.timeout)
                )
            ).defaultHeader("User-Agent", userAgent(buildProperties.ifAvailable?.version))
            .defaultHeader("Accept", "application/json")
            .build()

    companion object {
        /** `event-junkie/<version> ( https://github.com/enorm-labs/event-junkie )`, `dev` when no build stamped one. */
        fun userAgent(version: String?): String = "event-junkie/${version ?: "dev"} ( https://github.com/enorm-labs/event-junkie )"
    }
}
