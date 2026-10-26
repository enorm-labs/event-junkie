package de.norm.events.account

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Schema(description = "Whether visitors can create new accounts")
data class SignUpResponse(
    @Schema(description = "False while sign-up is paused: the BFF sends links only to addresses that already have an account.")
    val open: Boolean,
    @Schema(description = "When the switch last changed.")
    val changedAt: Instant
)

data class SignUpRequest(
    @Schema(description = "False pauses sign-up, true opens it again.", example = "false")
    val open: Boolean
)

/**
 * Pauses or opens sign-up (#3906), for the day someone scripts the request for a sign-in link. The BFF reads the
 * switch on every request, so a change takes effect at once on every replica. `docs/ops/EMAIL.md` §9 has the runbook.
 */
@RestController
@RequestMapping("/api/admin/sign-up")
@Tag(name = "Admin: Sign-up", description = "Pause or open the creation of new visitor accounts")
class SignUpController(
    private val store: SignUpStore
) {
    @GetMapping
    @Operation(summary = "Whether sign-up is open")
    suspend fun read(): SignUpResponse = store.read().toResponse()

    @PutMapping
    @Operation(summary = "Pause or open sign-up. Existing accounts keep signing in either way")
    suspend fun set(
        @RequestBody request: SignUpRequest
    ): SignUpResponse {
        val state = store.set(request.open)
        if (state.open) logger.info { "Sign-up is open" } else logger.warn { "Sign-up is paused: only existing accounts get sign-in links" }
        return state.toResponse()
    }

    private fun SignUpState.toResponse() = SignUpResponse(open, changedAt)
}
