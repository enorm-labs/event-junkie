package de.norm.events.weather

import de.norm.events.common.BERLIN
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

/**
 * Holds the DWD MOSMIX_L forecast of station 10384 (Berlin-Tempelhof) and answers it for the start hour of an open-air event
 * (ADR-057, #2726). Every replica fetches the file itself, every six hours. No visitor data leaves: the request names only the station.
 *
 * A failed fetch keeps the forecast already held. A forecast issued more than a day ago is not shown, so a long outage shows nothing.
 */
@Component
class WeatherForecasts(
    webClients: WebClient.Builder,
    private val clock: Clock,
    @Value("\${app.weather.url}") private val url: String
) {
    private val client = webClients.defaultHeader("User-Agent", USER_AGENT).build()
    private val current = AtomicReference<MosmixForecast?>()

    @Scheduled(initialDelayString = "\${app.weather.initial-delay}", fixedDelayString = "\${app.weather.refresh}")
    @Suppress("TooGenericExceptionCaught")
    suspend fun refresh() {
        try {
            val kmz =
                client
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(ByteArray::class.java)
                    .timeout(TIMEOUT)
                    .awaitSingle()
            val forecast = MosmixForecast.fromKmz(ByteArrayInputStream(kmz))
            current.set(forecast)
            logger.info { "Weather: DWD MOSMIX issued ${forecast.issuedAt} read, ${forecast.hours.size} hours" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Weather: reading the DWD MOSMIX forecast failed, keeping the one issued ${current.get()?.issuedAt ?: "never"}" }
        }
    }

    /**
     * The forecast for an event that starts on [eventDate] at [startTime], or else at [doorsTime], at a venue of [venueTypes].
     * Null unless the venue is open-air, the date falls from 1 May to 30 September, and the start hour lies from this hour to seven days
     * ahead. Null too when no current forecast covers that hour.
     */
    fun forStart(
        venueTypes: List<String>,
        eventDate: LocalDate,
        startTime: LocalTime?,
        doorsTime: LocalTime?
    ): WeatherForecastResponse? {
        if (OPEN_AIR !in venueTypes || eventDate.month !in SEASON) return null
        val time = startTime ?: doorsTime ?: return null
        val hour = ZonedDateTime.of(eventDate, time, BERLIN).toInstant().truncatedTo(ChronoUnit.HOURS)
        val now = clock.instant()
        if (hour < now.truncatedTo(ChronoUnit.HOURS) || hour >= now.plus(HORIZON)) return null
        val forecast = current.get()?.takeIf { it.issuedAt >= now.minus(MAX_AGE) } ?: return null
        return answer(forecast, hour)
    }

    /**
     * The temperature is the one at the start of [hour]. The weather and the rain probability are those of the step that ends it, because
     * MOSMIX states both for the hour before a step.
     */
    private fun answer(
        forecast: MosmixForecast,
        hour: Instant
    ): WeatherForecastResponse? {
        val temperature = forecast.hours[hour]?.temperatureCelsius ?: return null
        val closing = forecast.hours[hour.plus(Duration.ofHours(1))] ?: return null
        val condition = closing.significantWeather?.let(WeatherCondition::of) ?: return null
        return WeatherForecastResponse(condition, temperature.roundToInt(), closing.rainProbabilityPercent)
    }

    /** For a test: the forecast as if a fetch had just read it. */
    internal fun hold(forecast: MosmixForecast?) = current.set(forecast)

    private companion object {
        const val OPEN_AIR = "open-air"
        const val USER_AGENT = "EventJunkie/1.0 (+https://github.com/enorm-labs/event-junkie)"
        val SEASON = Month.MAY..Month.SEPTEMBER
        val HORIZON: Duration = Duration.ofDays(7)
        val MAX_AGE: Duration = Duration.ofHours(24)
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
