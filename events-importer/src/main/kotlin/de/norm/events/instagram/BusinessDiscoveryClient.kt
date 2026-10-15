package de.norm.events.instagram

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientException
import org.springframework.web.reactive.function.client.awaitBodyOrNull
import org.springframework.web.reactive.function.client.awaitExchange
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** One post as Business Discovery returns it. The image is never read (ADR-048 rule 3). */
data class InstagramPost(
    val mediaId: String,
    val caption: String?,
    val permalink: String,
    val postedAt: OffsetDateTime,
    val mediaType: String?
)

/** What one Business Discovery call gave. */
sealed interface DiscoveryResult {
    data class Posts(
        val posts: List<InstagramPost>
    ) : DiscoveryResult

    /** Error 110: a wrong handle or a personal account. The API does not tell the two apart. Only this venue is affected. */
    data class AccountUnreadable(
        val code: Int
    ) : DiscoveryResult

    /** A missing permission (10, 200) or an expired token (190). No venue can be read until a person acts. */
    data class AccessRefused(
        val code: Int,
        val message: String
    ) : DiscoveryResult

    /** A rate limit, a server error or no answer. The next pass tries again. */
    data class Unavailable(
        val reason: String
    ) : DiscoveryResult
}

/**
 * The Graph API's Business Discovery, read as our own business account (ADR-048 rule 1).
 *
 * `GET {ig-user-id}?fields=business_discovery.username(<handle>){media.limit(n){…}}`. The fields are
 * `id`, `caption`, `permalink`, `timestamp` and `media_type`, never `media_url` (rule 3). The handle
 * goes into the field expression, so only Instagram's own handle characters pass.
 */
@Component
class BusinessDiscoveryClient(
    @Qualifier(INSTAGRAM_WEB_CLIENT) private val webClient: WebClient,
    private val properties: InstagramProperties
) {
    private val logger = KotlinLogging.logger {}

    suspend fun recentPosts(handle: String): DiscoveryResult {
        require(HANDLE.matches(handle)) { "Not an Instagram handle: '$handle'" }
        val fields = "business_discovery.username($handle){media.limit(${properties.mediaLimit}){id,caption,permalink,timestamp,media_type}}"
        return try {
            val (status, body) =
                webClient
                    .get()
                    .uri { it.path(properties.igUserId).queryParam("fields", "{fields}").build(fields) }
                    .awaitExchange { response -> response.statusCode().value() to response.awaitBodyOrNull<String>().orEmpty() }
            read(handle, status, body)
        } catch (e: IOException) {
            logger.warn(e) { "Business Discovery did not answer for '$handle'" }
            DiscoveryResult.Unavailable("no answer")
        } catch (e: WebClientException) {
            logger.warn(e) { "Business Discovery request failed for '$handle'" }
            DiscoveryResult.Unavailable("request failed")
        }
    }

    private fun read(
        handle: String,
        status: Int,
        body: String
    ): DiscoveryResult {
        val root =
            try {
                mapper.readTree(body)
            } catch (e: JacksonException) {
                logger.warn(e) { "Business Discovery answered $status with no JSON for '$handle'" }
                null
            }
        val error = root?.get("error")
        return when {
            error != null -> {
                error(error)
            }

            root == null || status >= HTTP_ERROR -> {
                DiscoveryResult.Unavailable("HTTP $status")
            }

            else -> {
                DiscoveryResult.Posts(
                    root
                        .path("business_discovery")
                        .path("media")
                        .path("data")
                        .toList()
                        .mapNotNull(::post)
                )
            }
        }
    }

    private fun error(node: JsonNode): DiscoveryResult {
        val code = node.path("code").asInt(0)
        val message = node.path("message").asString("")
        return when (code) {
            ACCOUNT_NOT_FOUND -> DiscoveryResult.AccountUnreadable(code)
            in ACCESS_CODES -> DiscoveryResult.AccessRefused(code, message)
            else -> DiscoveryResult.Unavailable("Graph error $code")
        }
    }

    /** A post without an id, a permalink or a readable time is skipped: it could not be drafted or linked. */
    private fun post(node: JsonNode): InstagramPost? {
        val id = node.path("id").asString("")
        val permalink = node.path("permalink").asString("")
        val postedAt = timestamp(node.path("timestamp").asString(""))
        return if (id.isEmpty() || !permalink.startsWith(PERMALINK_PREFIX) || postedAt == null) {
            null
        } else {
            InstagramPost(
                mediaId = id,
                caption = node.get("caption")?.asString()?.takeIf { it.isNotBlank() },
                permalink = permalink,
                postedAt = postedAt,
                mediaType = node.get("media_type")?.asString()
            )
        }
    }

    private fun timestamp(value: String): OffsetDateTime? =
        try {
            OffsetDateTime.parse(value, TIMESTAMP)
        } catch (_: DateTimeParseException) {
            null
        }

    companion object {
        /** Letters, digits, dots and underscores, at most 30: Instagram's own rule. */
        val HANDLE = Regex("^[A-Za-z0-9._]{1,30}$")

        private const val HTTP_ERROR = 400
        private const val ACCOUNT_NOT_FOUND = 110
        private const val PERMALINK_PREFIX = "https://www.instagram.com/"

        /** 10 and 200: a permission the token lacks. 190: the token expired or was revoked. */
        private val ACCESS_CODES = setOf(10, 190, 200)

        /** Graph writes the offset without a colon: `2026-10-13T16:00:12+0000`. */
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")

        private val mapper: JsonMapper = JsonMapper.builder().build()
    }
}
