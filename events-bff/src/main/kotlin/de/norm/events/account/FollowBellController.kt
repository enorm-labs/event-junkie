package de.norm.events.account

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

private val logger = KotlinLogging.logger {}

/**
 * The bell on a follow (#3894, ADR-055): `instant` pushes its new dates to every browser of the account with
 * notifications on, `off` sends nothing. Every route needs a session.
 */
@RestController
@RequestMapping("/api/me/follows")
@Tag(name = "Account", description = "The signed-in visitor's account")
class FollowBellController(
    private val follows: FollowStore,
    private val metrics: AccountMetrics
) {
    @PutMapping("/notify")
    @Operation(summary = "Turns the bell of every follow on or off at once, as the account page offers when notifications first go on")
    suspend fun notifyAll(
        @AuthenticationPrincipal account: Account,
        @RequestBody request: NotifyRequest
    ): NotifyCount {
        val action = bellAction(request.notify)
        val count = follows.setAllNotify(account.id, request.notify)
        metrics.record(action)
        logger.info { "Push ${action.tag}: all $count follows" }
        return NotifyCount(count)
    }

    @PutMapping("/{kind}/{slug}/notify")
    @Operation(summary = "Turns the bell of one follow on (`instant`) or off (`off`)")
    suspend fun notify(
        @AuthenticationPrincipal account: Account,
        @PathVariable kind: String,
        @PathVariable slug: String,
        @RequestBody request: NotifyRequest
    ): ResponseEntity<Unit> {
        val action = bellAction(request.notify)
        val (followKind, id) = follows.resolve(kind, slug)
        if (!follows.setNotify(account.id, followKind, id, request.notify)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "You do not follow this $kind")
        }
        metrics.record(action)
        logger.info { "Push ${action.tag}: ${followKind.tag}" }
        return ResponseEntity.noContent().build()
    }

    /** `digest` waits for the digest (#3891), so it is refused rather than stored with nothing to read it. */
    private fun bellAction(notify: String): PushAction =
        when (notify) {
            NOTIFY_INSTANT -> PushAction.BELL_ON
            NOTIFY_OFF -> PushAction.BELL_OFF
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "notify is `instant` or `off`")
        }

    private companion object {
        const val NOTIFY_INSTANT = "instant"
        const val NOTIFY_OFF = "off"
    }
}
