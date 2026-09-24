package de.norm.events.scraper

import de.norm.events.artist.ArtistRepository
import de.norm.events.event.DescriptionLanguage
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.licence.SourceLicences
import de.norm.events.translation.TranslationEngine
import de.norm.events.translation.TranslationProperties
import de.norm.events.translation.TranslationRequest
import de.norm.events.venue.VenueRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

/**
 * Fills in the missing language for a source whose grant allows it.
 *
 * **Runs after an import commits, never inside it.** A translation is derived text, so losing one
 * costs a retry rather than data, and an engine that is slow or down must not fail a scrape. Every
 * outcome is a log line and a counter.
 *
 * The gate is [SourceLicences.allowsTranslation], which only `PERMITTED` satisfies. Since ADR-027
 * that verdict follows the display rule, so it is set wherever a venue has not prohibited the
 * description — 84 of the 86 sources on both clusters.
 */
@Service
@Suppress("LongParameterList") // Constructor injection: one parameter per collaborator.
class DescriptionTranslationService(
    private val eventRepository: EventRepository,
    private val eventArtistRepository: EventArtistRepository,
    private val artistRepository: ArtistRepository,
    private val eventSourceRepository: EventSourceRepository,
    private val venueRepository: VenueRepository,
    private val engine: TranslationEngine,
    private val properties: TranslationProperties,
    private val metrics: ImporterMetrics
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Translates what this source is missing.
     *
     * Skips an event whose stored translation still matches the description it was made from, so a
     * re-import that changed nothing costs nothing.
     *
     * @return how many descriptions were translated and stored. Zero when the source grants no
     *   translation, when nothing is stale, or when the engine declined every candidate — the three
     *   are deliberately one answer here, because none of them is a failure the caller can act on.
     */
    suspend fun translateFor(
        source: EventSourceEntity,
        venueName: String,
        licences: SourceLicences
    ): Int {
        val id = source.id?.takeIf { licences.allowsTranslation() } ?: return 0

        val stale = eventRepository.findTranslationCandidates(id).toList().filter { it.needsTranslation() }
        val written = stale.take(properties.maxPerRun).count { translate(it, venueName) }
        if (stale.isNotEmpty()) {
            logger.info { "Translated $written of ${stale.size} description(s) for '${source.slug}' with ${engine.id}" }
        }
        return written
    }

    /**
     * Translates one source on demand, or one event of it when [eventSlug] names one.
     *
     * The gate is the same one the import pass uses. **A manual trigger does not bypass it**: the
     * grant is a legal condition, not an operator convenience, so a source without one reports what
     * it is missing rather than translating anyway.
     *
     * @return what the run did, including whether the source permits translation at all.
     */
    suspend fun translateOnDemand(
        slug: String,
        eventSlug: String?
    ): TranslationRunResponse {
        val source = eventSourceRepository.findBySlug(slug) ?: throw EventSourceNotFoundException(slug)
        val licences = SourceLicences.of(source.descriptionLicence, source.imageLicence, source.translationLicence)
        val venueName = source.venueId.let { venueRepository.findById(it)?.name }.orEmpty()

        if (!licences.allowsTranslation()) {
            return TranslationRunResponse(sourceSlug = slug, permitted = false, candidates = 0, translated = 0, engine = engine.id)
        }
        val id = requireNotNull(source.id) { "Event source must be persisted" }
        val candidates =
            eventRepository
                .findTranslationCandidates(id)
                .toList()
                .filter { it.needsTranslation() }
                .filter { eventSlug == null || it.slug == eventSlug }
        val translated = candidates.take(properties.maxPerRun).count { translate(it, venueName) }
        logger.info { "Translated $translated of ${candidates.size} description(s) for '$slug' on request" }
        return TranslationRunResponse(
            sourceSlug = slug,
            permitted = true,
            candidates = candidates.size,
            translated = translated,
            engine = engine.id
        )
    }

    private suspend fun translate(
        event: EventEntity,
        venueName: String
    ): Boolean {
        val description = event.description
        val from = event.descriptionLanguage?.let { code -> DescriptionLanguage.entries.find { it.code == code } }
        if (!engine.enabled || description == null || from == null) return false
        val to = if (from == DescriptionLanguage.GERMAN) DescriptionLanguage.ENGLISH else DescriptionLanguage.GERMAN

        val request =
            TranslationRequest(
                text = description,
                from = from,
                to = to,
                protectedTerms = protectedTermsFor(event, venueName)
            )
        // The engine's lines — a failed call, a refusal, a rejected result — name the event through
        // the context, so the engine stays ignorant of what it translates.
        val translated =
            withContext(LogContext.forEvent(requireNotNull(event.id) { "A translation candidate is a stored row" })) {
                engine.translate(request)
            }
        metrics.recordTranslation(translated != null)
        translated?.let {
            eventRepository.save(
                event.copy(
                    descriptionAlt = it,
                    descriptionAltLanguage = to.code,
                    descriptionAltOrigin = MACHINE_ORIGIN,
                    descriptionAltEngine = engine.id,
                    descriptionAltSourceHash = DescriptionLanguage.hash(description)
                )
            )
        }
        return translated != null
    }

    /** The venue and the acts on this bill. The words a translation is most likely to damage. */
    private suspend fun protectedTermsFor(
        event: EventEntity,
        venueName: String
    ): List<String> {
        val eventId = event.id ?: return listOf(venueName)
        val artistIds = eventArtistRepository.findByEventId(eventId).toList().map { it.artistId }
        val artistNames = artistIds.mapNotNull { artistRepository.findById(it)?.name }
        return (listOf(venueName) + artistNames).distinct()
    }

    /** Whether the stored translation is missing or was made from a text that has since changed. */
    private fun EventEntity.needsTranslation(): Boolean = descriptionAlt == null || descriptionAltSourceHash != description?.let(DescriptionLanguage::hash)

    private companion object {
        const val MACHINE_ORIGIN = "MACHINE"
    }
}
