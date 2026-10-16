package de.norm.events.discogs

import de.norm.events.common.ApiWebClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

/** Bean name of the Discogs [WebClient]; inject with `@Qualifier(DISCOGS_WEB_CLIENT)`. */
const val DISCOGS_WEB_CLIENT = "discogsWebClient"

/**
 * The [WebClient] every Discogs request goes through: its own, for the reasons
 * `MusicBrainzHttpClientConfig` gives. Discogs asks for a `User-Agent` that names the product, which
 * [ApiWebClient] sets.
 *
 * The consumer credentials go on as a default header here, once, so no call site builds the
 * string and nothing logs it. With either credential empty no header is set, and
 * [DiscogsProperties.active] keeps the sweep from sending anything.
 */
@Configuration
class DiscogsHttpClientConfig {
    @Bean(DISCOGS_WEB_CLIENT)
    fun discogsWebClient(
        webClientBuilder: WebClient.Builder,
        properties: DiscogsProperties,
        buildProperties: ObjectProvider<BuildProperties>
    ): WebClient =
        ApiWebClient
            .builder(webClientBuilder, properties.timeout, buildProperties)
            .baseUrl(properties.baseUrl)
            .defaultHeaders { headers ->
                if (properties.active) {
                    headers.set("Authorization", "Discogs key=${properties.consumerKey}, secret=${properties.consumerSecret}")
                }
            }.build()
}
