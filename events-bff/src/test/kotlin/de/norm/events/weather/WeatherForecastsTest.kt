package de.norm.events.weather

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class WeatherForecastsTest {
    private val server = MockWebServer().apply { start() }

    @AfterEach
    fun stop() = server.close()

    /** The fixture was issued 2027-06-10T09:00Z and runs from 2027-06-12T17:00Z to 2027-06-13T00:00Z. */
    private fun forecasts(now: Instant = Instant.parse("2027-06-10T10:00:00Z")) =
        WeatherForecasts(WebClient.builder(), Clock.fixed(now, ZoneOffset.UTC), server.url("/mosmix.kmz").toString())

    private fun answerFixture() {
        val kmz = requireNotNull(javaClass.getResourceAsStream("/weather/MOSMIX_L_LATEST_10384.kmz")).readBytes()
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body(Buffer().write(kmz))
                .build()
        )
    }

    private fun answerError() =
        server.enqueue(
            MockResponse
                .Builder()
                .code(503)
                .build()
        )

    private suspend fun loaded(now: Instant = Instant.parse("2027-06-10T10:00:00Z")) =
        forecasts(now).also {
            answerFixture()
            it.refresh()
        }

    private fun WeatherForecasts.at(
        date: String,
        start: String?,
        venueTypes: List<String> = listOf("club", "open-air"),
        doors: String? = null
    ) = forStart(venueTypes, LocalDate.parse(date), start?.let(LocalTime::parse), doors?.let(LocalTime::parse))

    @Test
    fun `an open-air start inside the window gets the forecast of its hour`() =
        runTest {
            val forecasts = loaded()

            // 20:00 in Berlin is 18:00 UTC: the temperature of 18:00, the weather and the rain of the hour to 19:00.
            forecasts.at("2027-06-12", "20:30") shouldBe WeatherForecastResponse(WeatherCondition.PARTLY_CLOUDY, 22, 5)
            forecasts.at("2027-06-12", "21:00") shouldBe WeatherForecastResponse(WeatherCondition.CLOUDY, 21, 10)
            forecasts.at("2027-06-12", "22:00") shouldBe WeatherForecastResponse(WeatherCondition.RAIN, 19, 70)
            server.takeRequest(1, TimeUnit.SECONDS).shouldNotBeNull().headers["User-Agent"] shouldBe
                "EventJunkie/1.0 (+https://github.com/enorm-labs/event-junkie)"
        }

    @Test
    fun `the doors time stands in for a missing start, and an event with neither gets nothing`() =
        runTest {
            val forecasts = loaded()

            forecasts.at("2027-06-12", null, doors = "23:00").shouldNotBeNull().condition shouldBe WeatherCondition.FOG
            forecasts.at("2027-06-12", null).shouldBeNull()
        }

    @Test
    fun `an event over midnight reads the hours of the next UTC day`() =
        runTest {
            val forecasts = loaded()

            // The night of the 12th, dated the 13th at 00:30: 22:00 UTC, and the hour to 23:00 that has no rain value.
            forecasts.at("2027-06-13", "00:30") shouldBe WeatherForecastResponse(WeatherCondition.THUNDERSTORM, 18, null)
            // 02:00 is the last step, so the hour after it is not in the file.
            forecasts.at("2027-06-13", "02:00").shouldBeNull()
        }

    @Test
    fun `a venue without open-air gets nothing`() =
        runTest {
            loaded().at("2027-06-12", "20:00", venueTypes = listOf("club")).shouldBeNull()
        }

    @Test
    fun `a start more than seven days ahead or before this hour gets nothing`() {
        // Seven days to the hour from now is the first hour outside.
        heldAt(Instant.parse("2027-06-05T18:00:00Z")).at("2027-06-12", "20:30").shouldBeNull()
        heldAt(Instant.parse("2027-06-05T18:01:00Z")).at("2027-06-12", "20:30").shouldNotBeNull()
        heldAt(Instant.parse("2027-06-12T19:00:00Z")).at("2027-06-12", "20:30").shouldBeNull()
        // Twenty minutes in, the start hour is this hour and still answers.
        heldAt(Instant.parse("2027-06-12T18:20:00Z")).at("2027-06-12", "20:00").shouldNotBeNull()
    }

    /** The fixture's hours, held as if the DWD had issued them an hour before [now]. */
    private fun heldAt(now: Instant) =
        forecasts(now).apply {
            val kmz = requireNotNull(javaClass.getResourceAsStream("/weather/MOSMIX_L_LATEST_10384.kmz"))
            hold(MosmixForecast.fromKmz(kmz).copy(issuedAt = now.minusSeconds(3600)))
        }

    @Test
    fun `outside 1 May to 30 September nothing is shown, whatever the forecast holds`() {
        val hours =
            mapOf(
                Instant.parse("2027-09-30T18:00:00Z") to MosmixHour(15.0, 0, 0),
                Instant.parse("2027-09-30T19:00:00Z") to MosmixHour(14.0, 0, 0),
                Instant.parse("2027-10-01T18:00:00Z") to MosmixHour(15.0, 0, 0),
                Instant.parse("2027-10-01T19:00:00Z") to MosmixHour(14.0, 0, 0),
                Instant.parse("2027-04-30T18:00:00Z") to MosmixHour(15.0, 0, 0),
                Instant.parse("2027-04-30T19:00:00Z") to MosmixHour(14.0, 0, 0)
            )
        val autumn = forecasts(Instant.parse("2027-09-29T12:00:00Z")).apply { hold(MosmixForecast(Instant.parse("2027-09-29T09:00:00Z"), hours)) }
        autumn.at("2027-09-30", "20:00").shouldNotBeNull().condition shouldBe WeatherCondition.CLEAR
        autumn.at("2027-10-01", "20:00").shouldBeNull()

        val spring = forecasts(Instant.parse("2027-04-29T12:00:00Z")).apply { hold(MosmixForecast(Instant.parse("2027-04-29T09:00:00Z"), hours)) }
        spring.at("2027-04-30", "20:00").shouldBeNull()
    }

    @Test
    fun `a failed first fetch shows nothing`() =
        runTest {
            val forecasts = forecasts()
            answerError()

            forecasts.refresh()

            forecasts.at("2027-06-12", "20:30").shouldBeNull()
        }

    @Test
    fun `a failed fetch keeps the forecast held, until it is a day old`() =
        runTest {
            val forecasts = loaded()
            answerError()
            forecasts.refresh()
            forecasts.at("2027-06-12", "20:30").shouldNotBeNull()

            val dayLater = forecasts(Instant.parse("2027-06-11T10:00:00Z"))
            answerFixture()
            dayLater.refresh()
            dayLater.at("2027-06-12", "20:30").shouldBeNull()
        }

    @Test
    fun `a body that is not a KMZ keeps nothing`() =
        runTest {
            val forecasts = forecasts()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("<html>maintenance</html>")
                    .build()
            )

            forecasts.refresh()

            forecasts.at("2027-06-12", "20:30").shouldBeNull()
        }
}
