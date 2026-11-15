package de.norm.events.scraper.ballhauswedding

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.dateOrNull
import de.norm.events.slug.SlugGenerator
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Pure parser for `event-pages-sitemap.xml`, the list of every Wix Events page the venue has made. A page made for one
 * date carries it in its slug, `discofox-nacht-2026-12-18-20-00`, and a copy of that page ends in `-1` or `-2`.
 * A slug without a date (`nacht-der-1000-masken`) is an old page, so only a dated slug is read.
 */
internal object BallhausWeddingSitemap {
    fun datedPages(sitemap: Document): List<DatedEventPage> =
        sitemap.select("loc").mapNotNull { loc ->
            val url = loc.text().trim()
            val match = DATED_SLUG.find(url) ?: return@mapNotNull null
            val (words, stamp, copy) = match.destructured
            val start = dateOrNull { LocalDateTime.parse(stamp, SLUG_STAMP) } ?: return@mapNotNull null
            DatedEventPage(url, start.toLocalDate(), start.toLocalTime(), significantWords(words), copy = copy.toIntOrNull() ?: 0)
        }

    /**
     * The pages that may be the own page of each entry [events] list without one, by [ScrapedEvent.sourceId]. A page fits
     * on the same date and start with one significant title word in common, never on the title alone. The copies of one
     * night come newest first, since the venue cancels a page it replaces. A night whose copy an entry links is taken.
     * A free night goes to the first entry it fits, so no page serves two, and an entry that two nights fit takes neither.
     */
    fun match(
        events: List<ScrapedEvent>,
        pages: List<DatedEventPage>,
        programmeUrl: String
    ): Map<String, List<String>> {
        val linked = events.mapTo(mutableSetOf()) { it.sourceUrl }
        val nights = pages.groupBy { Triple(it.date, it.start, it.words) }.values.map { copies -> copies.sortedByDescending { it.copy } }
        val taken = nights.filterTo(mutableSetOf()) { night -> night.any { it.url in linked } }
        return buildMap {
            for (entry in events.filter { it.sourceUrl == programmeUrl }) {
                val night = nights.singleOrNull { it !in taken && fits(entry, it.first()) } ?: continue
                taken += night
                put(entry.sourceId, night.map { it.url })
            }
        }
    }

    private fun fits(
        entry: ScrapedEvent,
        page: DatedEventPage
    ): Boolean = page.date == entry.eventDate && page.start == entry.startTime && significantWords(entry.title).any { it in page.words }

    /** The words of [text] as a slug spells them, less the short ones and the venue's own name. */
    private fun significantWords(text: String): Set<String> =
        SlugGenerator
            .slugify(text)
            .split('-')
            .filter { it.length >= MIN_WORD_LENGTH && it !in VENUE_WORDS }
            .toSet()

    private const val MIN_WORD_LENGTH = 4
    private val VENUE_WORDS = setOf("ballhaus", "wedding")
    private val DATED_SLUG =
        Regex("""/details-registrierung/([a-z0-9-]+?)-(\d{4}-\d{2}-\d{2}-\d{2}-\d{2})(?:-(\d+))?$""")
    private val SLUG_STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd-HH-mm").withResolverStyle(ResolverStyle.STRICT)
}

/** One dated Wix Events page: its date and start from the slug, the slug's title words, and 0 or its copy number. */
internal data class DatedEventPage(
    val url: String,
    val date: LocalDate,
    val start: LocalTime,
    val words: Set<String>,
    val copy: Int
)
