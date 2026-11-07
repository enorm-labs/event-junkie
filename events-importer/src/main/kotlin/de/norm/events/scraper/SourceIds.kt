package de.norm.events.scraper

import de.norm.events.slug.SlugGenerator
import java.time.LocalDate

/**
 * The `sourceId` of a venue whose rows carry no id of their own: the date and the slugged title,
 * `"<prefix>2026-10-17-<title-slug>"`. A changed shape re-keys every stored row of the venue (#4651),
 * so a scraper that needs another shape writes it out instead of changing this one.
 */
fun EventSource.dateTitleId(
    date: LocalDate,
    title: String
): String = "$sourceIdPrefix$date-${SlugGenerator.slugify(title)}"

/** The last segment of [url]'s path, a trailing slash ignored: `…/events/night-1/` gives `night-1`. */
fun lastPathSegment(url: String): String = url.trimEnd('/').substringAfterLast('/')
