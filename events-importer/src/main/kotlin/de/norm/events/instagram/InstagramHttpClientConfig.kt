package de.norm.events.instagram

import de.norm.events.common.ApiUserAgent
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/** Bean name of the Graph API [WebClient]; inject with `@Qualifier(INSTAGRAM_WEB_CLIENT)`. */
const val INSTAGRAM_WEB_CLIENT = "instagramWebClient"

/**
 * The [WebClient] every Graph API request goes through. Its own client, not the scraper's: an API
 * with its own terms gets no `robots.txt` check, and the token travels in a header, never in a URL.
 */
@Configuration
class InstagramHttpClientConfig {
    @Bean(INSTAGRAM_WEB_CLIENT)
    fun instagramWebClient(
        webClientBuilder: WebClient.Builder,
        properties: InstagramProperties,
        buildProperties: ObjectProvider<BuildProperties>
    ): WebClient =
        webClientBuilder
            .baseUrl("${properties.graphBaseUrl.trimEnd('/')}/${properties.graphVersion}/")
            .clientConnector(ReactorClientHttpConnector(HttpClient.create().compress(true).responseTimeout(properties.timeout)))
            .defaultHeader("User-Agent", ApiUserAgent.of(buildProperties.ifAvailable?.version))
            .defaultHeader("Authorization", "Bearer ${properties.accessToken}")
            .defaultHeader("Accept", "application/json")
            .build()
}
