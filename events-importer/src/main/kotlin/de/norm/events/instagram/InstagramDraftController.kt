package de.norm.events.instagram

import de.norm.events.common.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebInputException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * The review of Instagram drafts (ADR-048 rule 5). A person approves a draft after creating its event
 * through `POST /api/admin/events`, or rejects it. Both judge the whole night: every pending draft of
 * the venue and date. These routes work with the drafts switched off, so stored drafts can be judged.
 */
@RestController
@RequestMapping("/api/admin/instagram-drafts")
@Tag(name = "Admin: Instagram", description = "Event drafts from the Instagram posts of venues with no website (ADR-048)")
class InstagramDraftController(
    private val repository: InstagramDraftRepository
) {
    @Operation(
        summary = "Drafts in one state, the soonest night first",
        description = "Drafts of one venue and night sit together, newest post first. The order is fixed: a `sort` parameter is ignored."
    )
    @GetMapping
    suspend fun list(
        @Parameter(description = "PENDING, APPROVED, REJECTED or NOT_AN_EVENT", example = "PENDING")
        @RequestParam(defaultValue = "PENDING") status: String,
        @ParameterObject
        @PageableDefault(size = 50) pageable: Pageable
    ): PageResponse<InstagramDraftResponse> {
        val resolved =
            DraftStatus.entries.firstOrNull { it.name == status }
                ?: throw ServerWebInputException("Unknown status '$status'. Valid values: ${DraftStatus.entries.joinToString(", ")}")
        val rows = repository.findPage(resolved, pageable.pageSize, pageable.offset)
        return PageResponse.of(rows.map(InstagramDraftResponse::from), pageable, repository.count(resolved))
    }

    @Operation(summary = "One draft")
    @GetMapping("/{id}")
    suspend fun get(
        @Parameter(description = "Database ID of the draft", example = "12")
        @PathVariable id: Long
    ): InstagramDraftResponse = InstagramDraftResponse.from(repository.findById(id) ?: throw InstagramDraftNotFoundException(id))

    @Operation(
        summary = "Approve the night of a draft, with the event created for it",
        description =
            "Every pending draft of the venue and date becomes APPROVED, points at the event and loses its caption. " +
                "404 for an unknown draft, 409 for one already judged or an event that does not exist."
    )
    @PostMapping("/{id}/approve")
    suspend fun approve(
        @Parameter(description = "Database ID of the draft", example = "12")
        @PathVariable id: Long,
        @Valid @RequestBody request: ApproveDraftRequest
    ): JudgedNightResponse = judge(id, DraftStatus.APPROVED, request.eventId)

    @Operation(
        summary = "Reject the night of a draft",
        description = "Every pending draft of the venue and date becomes REJECTED and loses its caption. No event is written."
    )
    @PostMapping("/{id}/reject")
    suspend fun reject(
        @Parameter(description = "Database ID of the draft", example = "12")
        @PathVariable id: Long
    ): JudgedNightResponse = judge(id, DraftStatus.REJECTED, eventId = null)

    private suspend fun judge(
        id: Long,
        status: DraftStatus,
        eventId: Long?
    ): JudgedNightResponse {
        repository.findById(id) ?: throw InstagramDraftNotFoundException(id)
        val changed = repository.judgeNight(id, status, eventId)
        if (changed == 0L) throw InstagramDraftAlreadyJudgedException(id)
        return JudgedNightResponse(status = status.name, drafts = changed)
    }
}

@Schema(description = "An event the model drafted from an Instagram post")
data class InstagramDraftResponse(
    val id: Long,
    val venueId: Long,
    val venueSlug: String,
    val venueName: String,
    @Schema(description = "Instagram's id of the post")
    val mediaId: String,
    @Schema(description = "The post's page on Instagram")
    val permalink: String,
    val postedAt: OffsetDateTime,
    @Schema(description = "The post's text. Null once the draft is judged (ADR-048 rule 6)")
    val caption: String?,
    @Schema(description = "Null only for NOT_AN_EVENT")
    val eventDate: LocalDate?,
    val startTime: LocalTime?,
    val title: String?,
    @Schema(description = "Each act exactly as the caption writes it")
    val acts: List<String>,
    val pricePresale: BigDecimal?,
    val priceBoxOffice: BigDecimal?,
    @Schema(description = "The passage of the caption that gives the day")
    val evidence: String?,
    val confidence: Double?,
    val status: String,
    @Schema(description = "The event an approval created")
    val eventId: Long?,
    val createdAt: OffsetDateTime
) {
    companion object {
        fun from(row: DraftRow) =
            InstagramDraftResponse(
                id = row.id,
                venueId = row.venueId,
                venueSlug = row.venueSlug,
                venueName = row.venueName,
                mediaId = row.mediaId,
                permalink = row.permalink,
                postedAt = row.postedAt,
                caption = row.caption,
                eventDate = row.eventDate,
                startTime = row.startTime,
                title = row.title,
                acts = row.acts,
                pricePresale = row.pricePresale,
                priceBoxOffice = row.priceBoxOffice,
                evidence = row.evidence,
                confidence = row.confidence,
                status = row.status.name,
                eventId = row.eventId,
                createdAt = row.createdAt
            )
    }
}

@Schema(description = "The event an approved night became")
data class ApproveDraftRequest(
    @field:NotNull(message = "eventId is required")
    @Schema(description = "The id POST /api/admin/events returned", example = "4711", requiredMode = Schema.RequiredMode.REQUIRED)
    val eventId: Long?
)

@Schema(description = "What a judgement changed")
data class JudgedNightResponse(
    @Schema(description = "The state the night's drafts took", example = "APPROVED")
    val status: String,
    @Schema(description = "The drafts of the venue and date that changed", example = "2")
    val drafts: Long
)

/** Rendered as a 404. */
class InstagramDraftNotFoundException(
    id: Long
) : RuntimeException("Instagram draft $id not found")

/** Rendered as a 409: only a pending draft can be judged. */
class InstagramDraftAlreadyJudgedException(
    id: Long
) : RuntimeException("Instagram draft $id is judged already")
