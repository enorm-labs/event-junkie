package de.norm.events.common

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.HttpClientRequest
import reactor.netty.http.client.HttpClientResponse
import java.net.URI
import java.time.Duration

/**
 * The [WebClient.Builder] every open-API client starts from: Discogs, MusicBrainz, Wikimedia and the
 * Instagram Graph API. It sets what they share — a compressing connector with the caller's response
 * timeout, the [ApiUserAgent] and `Accept: application/json` — and each config adds only its base
 * URL and its credential header.
 *
 * **A redirect is followed only to the host the request went to.** MusicBrainz answers a merged MBID
 * with a 301 to its survivor and Wikipedia redirects a title to its canonical one; both stay on the
 * host. A redirect to another host is returned unfollowed: the Discogs and Graph API credentials are
 * default headers, and the request must not carry them anywhere the config did not name. The
 * Instagram image client does not use this builder, because it follows no redirect at all (ADR-049).
 */
object ApiWebClient {
    private val logger = KotlinLogging.logger {}

    private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

    fun builder(
        webClientBuilder: WebClient.Builder,
        timeout: Duration,
        buildProperties: ObjectProvider<BuildProperties>
    ): WebClient.Builder =
        webClientBuilder
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient
                        .create()
                        .compress(true)
                        .followRedirect(::isSameHostRedirect)
                        .responseTimeout(timeout)
                )
            ).defaultHeader("User-Agent", ApiUserAgent.of(buildProperties.ifAvailable?.version))
            .defaultHeader("Accept", "application/json")

    private fun isSameHostRedirect(
        request: HttpClientRequest,
        response: HttpClientResponse
    ): Boolean {
        if (response.status().code() !in REDIRECT_STATUSES) return false
        val from = request.resourceUrl()?.let { runCatching { URI(it) }.getOrNull() }
        val to = response.responseHeaders().get("Location")?.let { runCatching { from?.resolve(it) }.getOrNull() }
        val sameHost = from != null && to != null && to.host.equals(from.host, ignoreCase = true)
        if (!sameHost) {
            logger.warn { "Not following a ${response.status().code()} from ${from?.host} to ${to?.host ?: "an unreadable Location"}: not the request's host" }
        }
        return sameHost
    }
}
