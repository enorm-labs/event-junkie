package de.norm.events.importing

import de.norm.events.event.EventEntity
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.slug.SlugGenerator
import java.time.Duration
import java.time.LocalTime

/** What [matchEnrichment] found for one enrichment event. */
sealed interface EnrichmentMatch {
    /** The one main event this enrichment event describes. */
    data class Matched(
        val event: EventEntity
    ) : EnrichmentMatch

    /** No main event on that date and within the hour: a show the venue does not list (ADR-043 rule 5). */
    data object Unmatched : EnrichmentMatch

    /** Several main events fit and the title names none of them alone, so filling one would be a guess. */
    data object Ambiguous : EnrichmentMatch
}

/** How far apart two start times may be and still be one show (ADR-043 rule 5). */
private val START_TOLERANCE: Duration = Duration.ofHours(1)

/**
 * The main event of [stored] that [event] describes, by ADR-043 rule 5: same date, and start times at
 * most an hour apart. [stored] holds the main events of the enrichment event's venue. A side without
 * a start time cannot rule a candidate out, so the date alone admits it. When several fit, the title
 * decides: the candidate that shares the most words with [event]'s title, if it is the only one.
 */
fun matchEnrichment(
    event: ScrapedEvent,
    stored: List<EventEntity>
): EnrichmentMatch {
    val candidates = stored.filter { it.eventDate == event.eventDate && startsWithinTolerance(event.startTime, it.startTime) }
    return when (candidates.size) {
        0 -> EnrichmentMatch.Unmatched
        1 -> EnrichmentMatch.Matched(candidates.single())
        else -> byTitle(event, candidates)
    }
}

/** The one of [candidates] whose title shares the most words with [event]'s, or [EnrichmentMatch.Ambiguous]. */
private fun byTitle(
    event: ScrapedEvent,
    candidates: List<EventEntity>
): EnrichmentMatch {
    val words = titleWords(event.storedTitle())
    val scored = candidates.map { it to titleWords(it.title).count { word -> word in words } }
    val best = scored.maxOf { it.second }
    val leaders = scored.filter { it.second == best }
    return if (best > 0 && leaders.size == 1) EnrichmentMatch.Matched(leaders.single().first) else EnrichmentMatch.Ambiguous
}

private fun startsWithinTolerance(
    a: LocalTime?,
    b: LocalTime?
): Boolean = a == null || b == null || Duration.between(a, b).abs() <= START_TOLERANCE

/** The title's words as a slug spells them, so case, accents and punctuation do not count. */
private fun titleWords(title: String): Set<String> =
    SlugGenerator
        .slugify(title)
        .split('-')
        .filter { it.isNotEmpty() }
        .toSet()
