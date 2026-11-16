package de.norm.events.account

import de.norm.events.musicbrainz.MusicBrainzSearch
import de.norm.events.musicbrainz.MusicBrainzUnavailableException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Watching an act that has never played Berlin (#5214): the visitor picks it from MusicBrainz, and the account holds its
 * MusicBrainz id until an artist row carries it ([WatchConversionJob]). Every route needs a session, so the search is
 * no open MusicBrainz proxy.
 */
@RestController
@RequestMapping("/api/me")
@Tag(name = "Account", description = "The signed-in visitor's account")
class WatchController(
    private val watches: WatchStore,
    private val follows: FollowStore,
    private val musicBrainz: MusicBrainzSearch,
    private val metrics: AccountMetrics,
    private val clock: Clock
) {
    @GetMapping("/musicbrainz/artists")
    @Operation(
        summary = "The MusicBrainz artists for a name, to watch one",
        description =
            "Asks MusicBrainz from the server, at most once a second, and keeps an answer for a day. The visitor's address does not reach " +
                "MusicBrainz. A candidate an artist row already carries has `followSlug`: follow that artist instead. 503 when MusicBrainz does not answer."
    )
    suspend fun candidates(
        @Parameter(description = "The act's name, $MIN_TERM to $MAX_TERM characters", required = true)
        @RequestParam q: String
    ): List<MusicBrainzCandidateResponse> {
        val term = q.trim()
        if (term.length !in MIN_TERM..MAX_TERM) refuse(HttpStatus.BAD_REQUEST, "'q' must have $MIN_TERM to $MAX_TERM characters")
        val found = musicBrainz.search(term)
        val slugs = watches.artistSlugs(found.map { it.id })
        return found.map { MusicBrainzCandidateResponse(it.id, it.name, it.type, it.country, it.disambiguation, slugs[it.id]) }
    }

    @GetMapping("/watches")
    @Operation(summary = "The acts the account watches that have no Berlin date yet, by name")
    suspend fun listWatches(
        @AuthenticationPrincipal account: Account
    ): List<WatchResponse> = watches.list(account.id).map(WatchResponse::of)

    @PutMapping("/watches/{mbid}")
    @Operation(
        summary = "Watches an act by its MusicBrainz id, with the bell on. Watching twice changes nothing",
        description =
            "The name is read from MusicBrainz, never taken from the request. A merged id is stored as the id it was merged into. " +
                "When an artist row already carries the id, this follows that artist and answers `kind: artist` with its slug."
    )
    suspend fun watch(
        @AuthenticationPrincipal account: Account,
        @PathVariable mbid: String
    ): WatchOutcome {
        val id = mbid(mbid)
        watches.artistSlugs(listOf(id))[id]?.let { return followArtist(account, it) }
        val watched = watches.list(account.id).map { it.mbid }.toSet()
        if (id in watched) return WatchOutcome(WATCH, null)
        val artist = musicBrainz.artist(id) ?: refuse(HttpStatus.NOT_FOUND, "MusicBrainz knows no artist $id")
        // A merged id answers with the survivor, which an artist row may carry already.
        if (artist.id != id) watches.artistSlugs(listOf(artist.id))[artist.id]?.let { return followArtist(account, it) }
        if (artist.id !in watched) {
            if (follows.count(account.id) >= MAX_FOLLOWS) refuse(HttpStatus.CONFLICT, "You follow $MAX_FOLLOWS things already. Unfollow one first")
            watches.watch(account.id, artist.id, artist.name.take(MAX_NAME), clock.instant())
            record(WatchAction.WATCH)
        }
        return WatchOutcome(WATCH, null)
    }

    @DeleteMapping("/watches/{mbid}")
    @Operation(summary = "Stops watching. Unwatching what the account does not watch changes nothing")
    suspend fun unwatch(
        @AuthenticationPrincipal account: Account,
        @PathVariable mbid: String
    ): ResponseEntity<Unit> {
        if (watches.unwatch(account.id, mbid(mbid))) record(WatchAction.UNWATCH)
        return ResponseEntity.noContent().build()
    }

    /** MusicBrainz did not answer: its outage or its rate limit, not the request's fault. */
    @ExceptionHandler(MusicBrainzUnavailableException::class)
    fun unavailable(): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "MusicBrainz did not answer. Try again in a minute.")

    private suspend fun followArtist(
        account: Account,
        slug: String
    ): WatchOutcome {
        val (kind, id) = follows.resolve(FollowKind.ARTIST.tag, slug)
        if (follows.notify(account.id, kind, id) == null) {
            if (follows.count(account.id) >= MAX_FOLLOWS) refuse(HttpStatus.CONFLICT, "You follow $MAX_FOLLOWS things already. Unfollow one first")
            follows.follow(account.id, kind, id, clock.instant())
            follows.setNotify(account.id, kind, id, INSTANT)
            metrics.record(FollowAction.FOLLOW, kind)
            logger.info { "Follow ${FollowAction.FOLLOW.tag}: ${kind.tag}, asked as a watch" }
        }
        return WatchOutcome(kind.tag, slug)
    }

    private fun record(action: WatchAction) {
        metrics.record(action)
        logger.info { "Watch ${action.tag}" }
    }

    private companion object {
        const val WATCH = "watch"
        const val INSTANT = "instant"
        const val MIN_TERM = 3
        const val MAX_TERM = 100
        const val MAX_NAME = 200
        val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun mbid(raw: String): String = raw.lowercase().takeIf { MBID.matches(it) } ?: refuse(HttpStatus.BAD_REQUEST, "Not a MusicBrainz id")
    }
}
