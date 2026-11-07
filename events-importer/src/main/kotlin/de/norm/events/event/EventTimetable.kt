package de.norm.events.event

import de.norm.events.common.BERLIN
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ServerWebInputException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Body of `PUT /api/admin/events/{id}/timetable`: the whole running order, as Berlin clock times. */
@Schema(description = "The set times of an event's lineup. An act on the lineup that the list leaves out loses its set times")
data class TimetableRequest(
    @field:NotNull(message = "Slots must not be null")
    val slots: List<@Valid TimetableSlotRequest>
)

@Schema(description = "One act's set, as Berlin clock times")
data class TimetableSlotRequest(
    @field:NotNull(message = "Artist ID must not be null")
    @field:Positive(message = "Artist ID must be positive")
    @Schema(description = "Database ID of an artist on the event's lineup", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
    val artistId: Long,
    @field:NotNull(message = "Start must not be null")
    @Schema(
        type = "string",
        format = "time",
        description = "Before the event's doors or start, or before 06:00 when it has neither, it is the next day",
        example = "23:00",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    val start: LocalTime,
    @Schema(type = "string", format = "time", description = "At or before the start, it is the next day. Null: open end", example = "02:00")
    val end: LocalTime? = null
)

/**
 * Hand-entered set times (`PUT /api/admin/events/{id}/timetable`). The lineup PUT leaves them alone (#3026),
 * so this is their only write path. A change pins `lineup`, or the next import would clear the times.
 */
@Service
class EventTimetableService(
    private val eventRepository: EventRepository,
    private val eventArtistRepository: EventArtistRepository,
    private val eventService: EventService,
    private val contentStamp: EventContentStamp
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Sets the set times of event [id] to [request]; an act on the lineup that [request] leaves out loses its times.
     *
     * @throws EventNotFoundException if no event with the given [id] exists.
     * @throws ServerWebInputException if a slot names an act off the lineup, or one act twice.
     */
    @Transactional
    suspend fun replace(
        id: Long,
        request: TimetableRequest
    ): EventResponse {
        val event = eventRepository.findById(id) ?: throw EventNotFoundException(id)
        val lineup = eventArtistRepository.findByEventId(id).toList()
        val slots = request.slots.associateBy { it.artistId }
        requireOnLineup(request.slots, lineup.map { it.artistId }.toSet())

        val clock = TimetableClock(event.eventDate, dayBreak = event.doorsTime ?: event.startTime ?: NIGHT_ENDS)
        val changed =
            lineup
                .map { row ->
                    val (start, end) = slots[row.artistId]?.let { clock.instants(it.start, it.end) } ?: (null to null)
                    row.copy(setStart = start, setEnd = end)
                }.filterIndexed { i, row -> row != lineup[i] }
        if (changed.isEmpty()) return eventService.findById(id)

        eventArtistRepository.saveAll(changed).toList()
        val saved = eventRepository.save(event.copy(pinnedFields = (event.pinnedFields + PinnedField.LINEUP.key).distinct()))
        contentStamp.restamp(listOf(saved))
        logger.info { "Set the timetable of event $id: ${slots.size} timed act(s), ${changed.size} row(s) changed; pinned lineup" }
        return eventService.findById(id)
    }

    private fun requireOnLineup(
        slots: List<TimetableSlotRequest>,
        billed: Set<Long>
    ) {
        val twice = slots.groupBy { it.artistId }.filterValues { it.size > 1 }.keys
        if (twice.isNotEmpty()) throw ServerWebInputException("slots: artist(s) $twice appear more than once")
        val unbilled = slots.map { it.artistId }.filterNot { it in billed }
        if (unbilled.isNotEmpty()) throw ServerWebInputException("slots: artist(s) $unbilled are not on the event's lineup")
    }
}

/** A set that starts before this belongs to the night after the event date, when the event names no doors or start. */
private val NIGHT_ENDS: LocalTime = LocalTime.of(6, 0)

/**
 * Clock times on the night of [night]. A start before [dayBreak] is past midnight; an end at or before its start is the
 * day after the start. Unlike a scraped running order, the slots need not come in order, because each floor runs its own.
 */
internal class TimetableClock(
    private val night: LocalDate,
    private val dayBreak: LocalTime
) {
    fun instants(
        start: LocalTime,
        end: LocalTime?
    ): Pair<Instant, Instant?> {
        val startDay = if (start < dayBreak) night.plusDays(1) else night
        val endDay = if (end != null && end <= start) startDay.plusDays(1) else startDay
        return startDay.atTime(start).atZone(BERLIN).toInstant() to end?.let { endDay.atTime(it).atZone(BERLIN).toInstant() }
    }
}
