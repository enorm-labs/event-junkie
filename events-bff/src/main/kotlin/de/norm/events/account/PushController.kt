package de.norm.events.account

import de.norm.events.push.PushEndpoints
import de.norm.events.push.PushSender
import de.norm.events.push.PushTarget
import de.norm.events.push.WebPushCrypto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Web Push on or off for one browser (#3894, ADR-055). The browser subscribes with our public key and hands over its
 * subscription, which the BFF stores. Every route needs a session. The endpoint is never logged: it identifies a browser.
 */
@RestController
@RequestMapping("/api/me/push")
@Tag(name = "Account", description = "The signed-in visitor's account")
class PushController(
    private val store: PushSubscriptionStore,
    private val changes: PlanChangeStore,
    private val sender: ObjectProvider<PushSender>,
    private val endpoints: PushEndpoints,
    private val metrics: AccountMetrics,
    private val clock: Clock
) {
    @GetMapping
    @Operation(summary = "Whether this cluster sends push notifications, and the public key a browser subscribes with")
    fun settings(): PushSettings = PushSettings(sender.ifAvailable?.publicKey)

    @PutMapping
    @Operation(summary = "Stores this browser's push subscription. Subscribing again changes nothing")
    suspend fun subscribe(
        @AuthenticationPrincipal account: Account,
        @RequestBody request: PushSubscriptionRequest
    ): ResponseEntity<Unit> {
        if (sender.ifAvailable == null) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Push notifications are not available")
        val target = PushTarget(request.endpoint, request.keys.p256dh, request.keys.auth)
        refusal(account, target)?.let { throw it }
        store.subscribe(account.id, target, clock.instant())
        record(PushAction.SUBSCRIBE)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/unsubscribe")
    @Operation(summary = "Forgets this browser's push subscription. A POST, so the endpoint stays out of the URL and the request log")
    suspend fun unsubscribe(
        @AuthenticationPrincipal account: Account,
        @RequestBody request: PushUnsubscribeRequest
    ): ResponseEntity<Unit> {
        store.unsubscribe(account.id, request.endpoint)
        record(PushAction.UNSUBSCRIBE)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/changes")
    @Operation(summary = "Turns the account's pushes about changes to events it marked as interested or going on or off")
    suspend fun changes(
        @AuthenticationPrincipal account: Account,
        @RequestBody request: ChangePushRequest
    ): ResponseEntity<Unit> {
        changes.setChangePush(account.id, request.enabled)
        logger.info { "Push changes ${if (request.enabled) "on" else "off"}" }
        return ResponseEntity.noContent().build()
    }

    /** Why this subscription cannot be stored, or null when it can. */
    private suspend fun refusal(
        account: Account,
        target: PushTarget
    ): ResponseStatusException? =
        when {
            !endpoints.isAllowed(target.endpoint) || !validKeys(target) -> {
                ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a push subscription this site can use")
            }

            !store.owns(account.id, target.endpoint) && store.count(account.id) >= MAX_SUBSCRIPTIONS -> {
                ResponseStatusException(HttpStatus.CONFLICT, "Notifications are on in $MAX_SUBSCRIPTIONS browsers already. Turn one off first")
            }

            else -> {
                null
            }
        }

    /** The browser's key is a P-256 point, and the auth secret 16 bytes (RFC 8291). Anything else would fail at the first send. */
    private fun validKeys(target: PushTarget): Boolean =
        runCatching {
            WebPushCrypto.publicKey(WebPushCrypto.fromBase64Url(target.p256dh))
            WebPushCrypto.fromBase64Url(target.auth).size == AUTH_SECRET_BYTES
        }.getOrDefault(false)

    private fun record(action: PushAction) {
        metrics.record(action)
        logger.info { "Push ${action.tag}" }
    }

    private companion object {
        /** A phone, a laptop, a work machine and a few more; a real list is short. */
        const val MAX_SUBSCRIPTIONS = 10L
        const val AUTH_SECRET_BYTES = 16
    }
}

/** [publicKey] is null when this cluster has no VAPID keys: the account page then says notifications are not available. */
data class PushSettings(
    val publicKey: String?
)

/** The shape of `PushSubscription.toJSON()` in the browser, so the SPA sends it as it is. */
data class PushSubscriptionRequest(
    val endpoint: String,
    val keys: PushKeys
)

data class PushKeys(
    val p256dh: String,
    val auth: String
)

/** "Changes to my plans" (#4045), for the account rather than the browser. */
data class ChangePushRequest(
    val enabled: Boolean
)

data class PushUnsubscribeRequest(
    val endpoint: String
)
