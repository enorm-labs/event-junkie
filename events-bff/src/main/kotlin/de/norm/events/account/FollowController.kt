package de.norm.events.account

import de.norm.events.common.PageResponse
import de.norm.events.event.EventFilter
import de.norm.events.event.EventService
import de.norm.events.event.EventSummaryResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/** Following artists, venues and promoters, and the events of everything followed (#3889). Every route needs a session. */
@RestController
@RequestMapping("/api/me")
@Tag(name = "Account", description = "The signed-in visitor's account")
class FollowController(
    private val follows: FollowStore,
    private val events: EventService,
    private val metrics: AccountMetrics,
    private val clock: Clock
) {
    @GetMapping("/follows")
    @Operation(summary = "Everything the account follows, by name")
    suspend fun list(
        @AuthenticationPrincipal account: Account
    ): List<FollowResponse> = follows.list(account.id).map(FollowResponse::of)

    @GetMapping("/follows/{kind}/{slug}")
    @Operation(summary = "Whether the account follows this artist, venue or promoter. A 200 either way, so the page's check is not a console error")
    suspend fun check(
        @AuthenticationPrincipal account: Account,
        @PathVariable kind: String,
        @PathVariable slug: String
    ): FollowState {
        val (followKind, id) = resolve(kind, slug)
        return FollowState(follows.follows(account.id, followKind, id))
    }

    @PutMapping("/follows/{kind}/{slug}")
    @Operation(summary = "Follows an artist, a venue or a promoter. Following twice changes nothing")
    suspend fun follow(
        @AuthenticationPrincipal account: Account,
        @PathVariable kind: String,
        @PathVariable slug: String
    ): ResponseEntity<Unit> {
        val (followKind, id) = resolve(kind, slug)
        if (!follows.follows(account.id, followKind, id) && follows.count(account.id) >= MAX_FOLLOWS) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "You follow $MAX_FOLLOWS things already. Unfollow one first")
        }
        follows.follow(account.id, followKind, id, clock.instant())
        record(FollowAction.FOLLOW, followKind)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/follows/{kind}/{slug}")
    @Operation(summary = "Stops following. Unfollowing what the account does not follow changes nothing")
    suspend fun unfollow(
        @AuthenticationPrincipal account: Account,
        @PathVariable kind: String,
        @PathVariable slug: String
    ): ResponseEntity<Unit> {
        val (followKind, id) = resolve(kind, slug)
        follows.unfollow(account.id, followKind, id)
        record(FollowAction.UNFOLLOW, followKind)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/following")
    @Operation(summary = "Upcoming events of everything the account follows, by date")
    suspend fun following(
        @AuthenticationPrincipal account: Account,
        @Parameter(description = "First day (inclusive), ISO-8601. Without it, everything not over yet, today included.")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate?,
        @ParameterObject
        @PageableDefault(size = 20, sort = ["eventDate"])
        pageable: Pageable
    ): PageResponse<EventSummaryResponse> = events.search(EventFilter(from = from, followed = follows.followedIds(account.id)), pageable)

    @GetMapping("/following/today")
    @Operation(summary = "Today's events of everything the account follows, a running night included, as on the home page (#3922)")
    suspend fun followingToday(
        @AuthenticationPrincipal account: Account
    ): List<EventSummaryResponse> = events.today(EventFilter(followed = follows.followedIds(account.id)))

    private suspend fun resolve(
        kind: String,
        slug: String
    ): Pair<FollowKind, Long> {
        val followKind = FollowKind.ofOrNull(kind) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Nothing to follow here")
        val id = follows.entityId(followKind, slug) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such $kind")
        return followKind to id
    }

    private fun record(
        action: FollowAction,
        kind: FollowKind
    ) {
        metrics.record(action, kind)
        logger.info { "Follow ${action.tag}: ${kind.tag}" }
    }

    private companion object {
        /** Keeps the following query an index read, and is far beyond anyone's real list. */
        const val MAX_FOLLOWS = 500L
    }
}
