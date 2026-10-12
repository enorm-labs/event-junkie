package de.norm.events.feed

import de.norm.events.common.Site
import de.norm.events.common.WeakETag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

/**
 * The HTTP contract the RSS feed and the calendar subscription share: a client polls both, so both
 * answer a rendered document in one locale, with a validator and the same lifetime.
 */
object PolledFeed {
    /** Set on the response rather than by the cache filter, so a `304` keeps it: the filter gives a non-`2xx` answer `no-store`. */
    fun cacheControl(ttlSeconds: Long): CacheControl = CacheControl.maxAge(Duration.ofSeconds(ttlSeconds)).cachePublic()

    /** The locale arrives as free text, which the parameter binding cannot constrain. */
    fun requireSupportedLocale(locale: String) {
        if (locale !in Site.LOCALES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'locale' must be one of ${Site.LOCALES.joinToString(", ")}")
        }
    }

    /** [body] with its validator: a client polls with `If-None-Match`, and Spring answers 304 for a matching tag. */
    fun response(
        body: String,
        contentType: String,
        cacheControl: CacheControl
    ): ResponseEntity<String> =
        ResponseEntity
            .ok()
            .cacheControl(cacheControl)
            .eTag(WeakETag.of(body))
            .contentType(MediaType.parseMediaType("$contentType;charset=UTF-8"))
            .body(body)
}
