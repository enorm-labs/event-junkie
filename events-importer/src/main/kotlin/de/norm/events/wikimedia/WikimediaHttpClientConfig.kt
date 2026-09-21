package de.norm.events.wikimedia

import de.norm.events.common.ApiUserAgent
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/** Bean name of the Wikimedia [WebClient]; inject with `@Qualifier(WIKIMEDIA_WEB_CLIENT)`. */
const val WIKIMEDIA_WEB_CLIENT = "wikimediaWebClient"

/**
 * The [WebClient] every Wikidata and Commons request goes through: no base URL, because it serves
 * two hosts, and the [ApiUserAgent] Wikimedia asks for as MusicBrainz does.
 */
@Configuration
class WikimediaHttpClientConfig {
    @Bean(WIKIMEDIA_WEB_CLIENT)
    fun wikimediaWebClient(
        webClientBuilder: WebClient.Builder,
        properties: WikimediaProperties,
        buildProperties: ObjectProvider<BuildProperties>
    ): WebClient =
        webClientBuilder
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient
                        .create()
                        .compress(true)
                        .responseTimeout(properties.timeout)
                )
            ).defaultHeader("User-Agent", ApiUserAgent.of(buildProperties.ifAvailable?.version))
            .defaultHeader("Accept", "application/json")
            .build()
}
