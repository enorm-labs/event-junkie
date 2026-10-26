package de.norm.events.account

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.util.UUID

private val logger = KotlinLogging.logger {}

/** Saved searches (#3890): the events list's filters under a name, shown on Following with their count. Every route needs a session. */
@RestController
@RequestMapping("/api/me/searches")
@Tag(name = "Account", description = "The signed-in visitor's account")
class SavedSearchController(
    private val searches: SavedSearchStore,
    private val metrics: AccountMetrics,
    private val clock: Clock
) {
    @GetMapping
    @Operation(summary = "The account's saved searches, by name")
    suspend fun list(
        @AuthenticationPrincipal account: Account
    ): List<SavedSearchResponse> = searches.list(account.id).map(SavedSearchResponse::of)

    @PostMapping
    @Operation(
        summary = "Saves the events list's filters under a name",
        description = "The query takes the events list's filter keys and `when` (today, weekend, week). Saving the same filters again answers the first search."
    )
    suspend fun save(
        @AuthenticationPrincipal account: Account,
        @RequestBody request: SavedSearchRequest
    ): SavedSearchResponse {
        val query = SavedSearchQuery.normalise(request.query).getOrElse { refuse(HttpStatus.BAD_REQUEST, it.message.orEmpty()) }
        if (searches.count(account.id) >= MAX_SEARCHES) refuse(HttpStatus.CONFLICT, "At most $MAX_SEARCHES saved searches")
        val saved = searches.save(account.id, name(request.name), query, clock.instant())
        record(SavedSearchAction.SAVE)
        return SavedSearchResponse.of(saved)
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Renames a saved search")
    suspend fun rename(
        @AuthenticationPrincipal account: Account,
        @PathVariable id: UUID,
        @RequestBody request: SavedSearchRename
    ): SavedSearchResponse {
        val renamed = searches.rename(account.id, id, name(request.name)) ?: refuse(HttpStatus.NOT_FOUND, "No such saved search")
        record(SavedSearchAction.RENAME)
        return SavedSearchResponse.of(renamed)
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Deletes a saved search. Deleting one that is gone changes nothing")
    suspend fun delete(
        @AuthenticationPrincipal account: Account,
        @PathVariable id: UUID
    ): ResponseEntity<Unit> {
        if (searches.delete(account.id, id)) record(SavedSearchAction.DELETE)
        return ResponseEntity.noContent().build()
    }

    private fun record(action: SavedSearchAction) {
        metrics.record(action)
        logger.info { "Saved search ${action.tag}" }
    }

    private companion object {
        const val MAX_SEARCHES = 20L
        const val MAX_NAME = 80

        fun name(raw: String): String =
            raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: refuse(HttpStatus.BAD_REQUEST, "A name of 1 to $MAX_NAME characters")

        fun refuse(
            status: HttpStatus,
            reason: String
        ): Nothing = throw ResponseStatusException(status, reason)
    }
}
