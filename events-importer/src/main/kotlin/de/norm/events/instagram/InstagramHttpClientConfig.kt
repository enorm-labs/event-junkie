package de.norm.events.instagram

import de.norm.events.common.ApiUserAgent
import de.norm.events.common.ApiWebClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/** Bean name of the Graph API [WebClient]; inject with `@Qualifier(INSTAGRAM_WEB_CLIENT)`. */
const val INSTAGRAM_WEB_CLIENT = "instagramWebClient"

/** Bean name of the image [WebClient]; inject with `@Qualifier(INSTAGRAM_IMAGE_WEB_CLIENT)`. */
const val INSTAGRAM_IMAGE_WEB_CLIENT = "instagramImageWebClient"

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
        ApiWebClient
            .builder(webClientBuilder, properties.timeout, buildProperties)
            .baseUrl("${properties.graphBaseUrl.trimEnd('/')}/${properties.graphVersion}/")
            .defaultHeader("Authorization", "Bearer ${properties.accessToken}")
            .build()

    /**
     * The [WebClient] that downloads a post's images from Meta's CDN (ADR-049). It carries no token: the
     * signed URL is the permission. It follows no redirect, so [InstagramImageFetcher]'s host check holds.
     */
    @Bean(INSTAGRAM_IMAGE_WEB_CLIENT)
    fun instagramImageWebClient(
        webClientBuilder: WebClient.Builder,
        properties: InstagramProperties,
        buildProperties: ObjectProvider<BuildProperties>
    ): WebClient =
        webClientBuilder
            .clientConnector(ReactorClientHttpConnector(HttpClient.create().followRedirect(false).responseTimeout(properties.timeout)))
            .codecs { it.defaultCodecs().maxInMemorySize(InstagramImageFetcher.MAX_BYTES) }
            .defaultHeader("User-Agent", ApiUserAgent.of(buildProperties.ifAvailable?.version))
            .build()
}
