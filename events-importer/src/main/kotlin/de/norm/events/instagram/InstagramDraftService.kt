package de.norm.events.instagram

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** What one venue's read gave. */
sealed interface VenueDraftResult {
    /** The posts read, the new ones among them, the drafts stored, and the model calls made. */
    data class Read(
        val posts: Int,
        val newPosts: Int,
        val drafts: Int,
        val notEvents: Int,
        val rejected: Int,
        val failed: Int,
        val calls: Int,
        val tokens: Long,
        /** Drafts of a night a person rejected before, stored as rejected. */
        val ofRejectedNights: Int = 0
    ) : VenueDraftResult

    /** The venue's `instagram_url` names no profile. */
    data object NoHandle : VenueDraftResult

    /** Business Discovery could not read this account, or nothing at all. */
    data class NotRead(
        val result: DiscoveryResult
    ) : VenueDraftResult
}

/**
 * Reads one venue's recent posts and stores a draft for each event the model finds (ADR-048).
 *
 * A post goes to the model once, with its caption and its images (ADR-049): a post that announces
 * nothing, or whose answer was refused, is stored as `NOT_AN_EVENT`. A failed call stores nothing, so
 * the next pass asks again, and so does a post with no caption whose images did not download. Only
 * posts of the last [InstagramProperties.lookbackDays] go to the model, and only events from today
 * on are drafted.
 */
@Service
@ConditionalOnProperty(name = ["app.instagram.enabled"], havingValue = "true")
class InstagramDraftService(
    private val client: BusinessDiscoveryClient,
    private val extractor: AnthropicPostExtractor,
    private val images: InstagramImageFetcher,
    private val repository: InstagramDraftRepository,
    private val properties: InstagramProperties,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

    /** Reads [venue] and makes at most [maxCalls] model calls. */
    suspend fun draftFrom(
        venue: InstagramVenue,
        maxCalls: Int
    ): VenueDraftResult {
        val discovery = InstagramHandle.of(venue.instagramUrl)?.let { client.recentPosts(it) }
        return when (discovery) {
            null -> VenueDraftResult.NoHandle
            is DiscoveryResult.Posts -> read(venue, discovery.posts, maxCalls)
            else -> VenueDraftResult.NotRead(discovery)
        }
    }

    private suspend fun read(
        venue: InstagramVenue,
        posts: List<InstagramPost>,
        maxCalls: Int
    ): VenueDraftResult.Read {
        val today = LocalDate.now(clock.withZone(BERLIN))
        val recent = posts.filter { !it.postedAt.toLocalDate().isBefore(today.minusDays(properties.lookbackDays)) }
        val known = repository.knownMediaIds(recent.map { it.mediaId })
        val fresh = recent.filter { it.mediaId !in known }
        var tally = VenueDraftResult.Read(posts.size, fresh.size, 0, 0, 0, 0, 0, 0, 0)
        for (post in fresh) {
            if (post.caption == null && post.imageUrls.isEmpty()) {
                repository.insert(listOf(NewDraft(venue.id, post, DraftStatus.NOT_AN_EVENT)))
                tally = tally.copy(notEvents = tally.notEvents + 1)
            } else if (tally.calls < maxCalls) {
                // A post over the cap stays unread, so the next pass sends it.
                val fetched = images.fetch(post.imageUrls)
                if (post.caption == null && fetched.isEmpty()) {
                    tally = tally.copy(failed = tally.failed + 1)
                    continue
                }
                val result = extractor.extract(PostInput(venue.name, post.caption.orEmpty(), post.postedAt, fetched))
                tally = store(venue, post, result, today, tally.copy(calls = tally.calls + 1, tokens = tally.tokens + result.tokens))
            }
        }
        return tally
    }

    private suspend fun store(
        venue: InstagramVenue,
        post: InstagramPost,
        result: ExtractionResult,
        today: LocalDate,
        tally: VenueDraftResult.Read
    ): VenueDraftResult.Read =
        when (result) {
            is ExtractionResult.Failed -> {
                tally.copy(failed = tally.failed + 1)
            }

            is ExtractionResult.Rejected -> {
                logger.warn { "Instagram venue '${venue.slug}': the answer for ${post.permalink} was refused" }
                repository.insert(listOf(NewDraft(venue.id, post, DraftStatus.NOT_AN_EVENT, model = extractor.model)))
                tally.copy(rejected = tally.rejected + 1)
            }

            is ExtractionResult.Events -> {
                for (dropped in result.dropped) {
                    logger.warn {
                        "Instagram venue '${venue.slug}': a drafted event of ${post.permalink} for '${dropped.date}' was dropped: ${dropped.check.label}"
                    }
                }
                val upcoming = result.events.filter { !it.date.isBefore(today) }
                if (upcoming.isEmpty()) {
                    repository.insert(listOf(NewDraft(venue.id, post, DraftStatus.NOT_AN_EVENT, model = extractor.model)))
                    tally.copy(notEvents = tally.notEvents + 1)
                } else {
                    val (ofRejected, pending) = upcoming.map { draft(venue, post, it) }.partition { it.status == DraftStatus.REJECTED }
                    val stored = repository.insert(pending)
                    val rejected = if (ofRejected.isEmpty()) 0L else repository.insert(ofRejected)
                    logger.debug { "Drafted $stored event(s) from ${post.permalink}, $rejected of a rejected night" }
                    tally.copy(drafts = tally.drafts + stored.toInt(), ofRejectedNights = tally.ofRejectedNights + rejected.toInt())
                }
            }
        }

    /** A night judged before is not asked again: a rejected one stays rejected, an approved one gets an update proposal. */
    private suspend fun draft(
        venue: InstagramVenue,
        post: InstagramPost,
        event: DraftedEvent
    ): NewDraft =
        when (val earlier = repository.earlierJudgement(venue.id, event.date)) {
            is EarlierJudgement.Approved -> NewDraft(venue.id, post, DraftStatus.PENDING, event, extractor.model, eventId = earlier.eventId)
            EarlierJudgement.Rejected -> NewDraft(venue.id, post, DraftStatus.REJECTED, event, extractor.model)
            null -> NewDraft(venue.id, post, DraftStatus.PENDING, event, extractor.model)
        }

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
