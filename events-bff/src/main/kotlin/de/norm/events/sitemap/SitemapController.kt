package de.norm.events.sitemap

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * The detail-page sitemaps (#367). A crawler never calls this path: a sitemap only vouches for URLs
 * under its own directory, so the frontend serves each one at the site root, as
 * `/sitemap-<kind>.xml`, through the meta-injection sidecar, and `/sitemap.xml` indexes them.
 */
@RestController
@RequestMapping("/api/sitemaps")
@Tag(name = "Sitemaps", description = "Detail-page sitemaps, served at the site root by the frontend")
class SitemapController(
    private val repository: SitemapRepository
) {
    @GetMapping("/{kind:events|venues|artists|promoters}.xml", produces = [MediaType.APPLICATION_XML_VALUE])
    @Operation(summary = "List the detail pages of one kind worth crawling, in both locales, as a sitemap")
    suspend fun sitemap(
        @PathVariable kind: String
    ): ResponseEntity<String> {
        val sitemapKind = requireNotNull(SitemapKind.fromPath(kind)) { "The path pattern admits $kind" }
        return ResponseEntity
            .ok()
            // An hour, as the static sitemap has: a crawler reads it a few times a day at most.
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
            .contentType(MediaType.APPLICATION_XML)
            .body(SitemapXml.render(sitemapKind, repository.entries(sitemapKind)))
    }
}
