package de.norm.events.importing

import de.norm.events.event.EventNotFoundException
import de.norm.events.event.EventRepository
import de.norm.events.event.PartyFeature
import de.norm.events.event.PinnedField
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The party features of one event, and the cues the rules could not settle (#3066). */
@Schema(description = "The party features of an event, with the uncertain cues the worklist lists")
data class EventFeaturesResponse(
    @Schema(description = "Database ID of the event", example = "4711")
    val eventId: Long,
    @Schema(description = "The features the event carries, in display order")
    val features: List<EventFeatureEntry>,
    @Schema(description = "The UNCERTAIN_PARTY_FEATURE cues of the event: a feature the rules saw a hint of, but did not set")
    val uncertain: List<EventFeatureEntry>,
    @Schema(description = "Whether `features` is pinned, so the importer keeps them (ADR-042)", example = "true")
    val pinned: Boolean
)

@Schema(description = "One feature and the words of the event's text behind it")
data class EventFeatureEntry(
    @Schema(description = "The PartyFeature slug", example = "flinta-only")
    val feature: String,
    @Schema(
        description = "The words that set the feature or raised the cue. A feature set by hand that no text set reads `set by hand`",
        example = "FLINTA* only"
    )
    val phrase: String
)

@Schema(description = "The full list of party features an event carries. An empty list clears them")
data class EventFeaturesRequest(
    @Schema(description = "PartyFeature slugs", example = "[\"queer\", \"open-end\"]")
    val features: List<String>
)

/** Thrown when a feature is named by a slug no [PartyFeature] has. */
class UnknownPartyFeatureException(
    slug: String
) : RuntimeException("Unknown party feature '$slug'. Valid: ${PartyFeature.entries.joinToString(", ") { it.slug }}")

/**
 * The party features of an event set by hand (#3066). Setting them pins [PinnedField.FEATURES], so
 * [EventFeatureSync] leaves them alone, and resolves the event's uncertain cues. Removing the pin
 * hands the features back to the rules at the next import.
 */
@Service
class EventFeatureService(
    private val eventRepository: EventRepository,
    private val featureRepository: EventFeatureRepository,
    private val qualityFlagRepository: EventQualityFlagRepository
) {
    private val logger = KotlinLogging.logger {}

    /** @throws EventNotFoundException if no event with the given [id] exists. */
    suspend fun find(id: Long): EventFeaturesResponse {
        val event = eventRepository.findById(id) ?: throw EventNotFoundException(id)
        return response(id, pinned = PinnedField.FEATURES.key in event.pinnedFields)
    }

    /**
     * Replaces the features of event [id] with [slugs] and pins them. A feature the event keeps
     * keeps its phrase. Deletes the event's [QualityFlagKind.UNCERTAIN_PARTY_FEATURE] flags.
     *
     * @throws UnknownPartyFeatureException if a slug names no [PartyFeature].
     * @throws EventNotFoundException if no event with the given [id] exists.
     */
    @Transactional
    suspend fun set(
        id: Long,
        slugs: List<String>
    ): EventFeaturesResponse {
        val features = slugs.map { PartyFeature.fromSlug(it) ?: throw UnknownPartyFeatureException(it) }.toSortedSet()
        val event = eventRepository.findById(id) ?: throw EventNotFoundException(id)
        val phrases = featureRepository.findByEventIds(listOf(id)).associate { it.feature to it.matchedPhrase }
        featureRepository.replaceFor(listOf(id), features.map { EventFeatureRow(id, it.slug, phrases[it.slug] ?: SET_BY_HAND) })
        val cleared = qualityFlagRepository.deleteKind(id, QualityFlagKind.UNCERTAIN_PARTY_FEATURE)
        if (PinnedField.FEATURES.key !in event.pinnedFields) {
            eventRepository.save(event.copy(pinnedFields = event.pinnedFields + PinnedField.FEATURES.key))
        }
        logger.info {
            "Set the party features of event $id by hand to ${features.map { it.slug }} and pinned them; " +
                "cleared $cleared uncertain cue(s)"
        }
        return response(id, pinned = true)
    }

    private suspend fun response(
        id: Long,
        pinned: Boolean
    ): EventFeaturesResponse {
        val features =
            featureRepository
                .findByEventIds(listOf(id))
                .sortedBy { PartyFeature.fromSlug(it.feature)?.ordinal ?: Int.MAX_VALUE }
                .map { EventFeatureEntry(it.feature, it.matchedPhrase) }
        val uncertain =
            qualityFlagRepository
                .findByEventIds(listOf(id))
                .filter { it.kind == QualityFlagKind.UNCERTAIN_PARTY_FEATURE }
                .map { EventFeatureEntry(it.value.substringBefore(':').trim(), it.value.substringAfter(':').trim()) }
        return EventFeaturesResponse(id, features, uncertain, pinned)
    }

    companion object {
        /** The phrase of a feature that no text of the event set, since `matched_phrase` takes no blank. */
        const val SET_BY_HAND = "set by hand"
    }
}
