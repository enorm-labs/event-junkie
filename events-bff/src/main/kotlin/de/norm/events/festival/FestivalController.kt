package de.norm.events.festival

import de.norm.events.common.ResponseCache
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate

/** Public read API for festivals: the ones the home page announces (#3272). */
@RestController
@RequestMapping("/api/festivals")
@Tag(name = "Festivals", description = "Public endpoint for the festivals the home page announces")
class FestivalController(
    private val festivalRepository: FestivalRepository,
    private val cache: ResponseCache,
    private val clock: Clock
) {
    @GetMapping("/current")
    @Operation(
        summary = "Up to 3 festivals running today or starting within 14 days",
        description =
            "Days are Berlin dates. Running festivals come first, then upcoming ones; each group by `firstDay`, then by id. " +
                "No festival answers an empty array."
    )
    @ApiResponse(
        responseCode = "200",
        description = "The festivals to announce, possibly none",
        content = [Content(array = ArraySchema(schema = Schema(implementation = FestivalResponse::class)))]
    )
    suspend fun current(): List<FestivalResponse> {
        val today = LocalDate.now(clock)
        return cache.get(CurrentFestivalsKey(today)) {
            festivalRepository
                .findCurrent(today, today.plusDays(HORIZON_DAYS), MAX_FESTIVALS)
                .map(FestivalResponse::fromEntity)
                .toList()
        }
    }

    private companion object {
        const val HORIZON_DAYS = 14L
        const val MAX_FESTIVALS = 3
    }
}

/** Keyed on the day, so the answer turns over at Berlin midnight and not a TTL later. */
private data class CurrentFestivalsKey(
    val today: LocalDate
)
