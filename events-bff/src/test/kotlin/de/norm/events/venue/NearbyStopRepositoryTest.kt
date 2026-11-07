package de.norm.events.venue

import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import java.math.BigDecimal

/** The nearest stations of a point (#2724): the cap on the count, the cap on the walk, and the rounding. */
class NearbyStopRepositoryTest : BaseControllerTest() {
    @Autowired
    private lateinit var repository: NearbyStopRepository

    private val lat = BigDecimal("52.500000")
    private val lon = BigDecimal("13.400000")

    private suspend fun station(
        id: String,
        northMetres: Double
    ) {
        val latitude = lat.toDouble() + northMetres / 111_195.0
        databaseClient
            .sql(
                "INSERT INTO events.transit_stop (gtfs_stop_id, name, latitude, longitude, lines, imported_at) " +
                    "VALUES (:id, :id, :lat, :lon, '{U1}', now())"
            ).bind("id", id)
            .bind("lat", BigDecimal(latitude).setScale(6, java.math.RoundingMode.HALF_UP))
            .bind("lon", lon)
            .await()
    }

    @Test
    fun `returns the three nearest within the walk, nearest first`(): Unit =
        runBlocking {
            station("far", 1_200.0)
            station("fourth", 900.0)
            station("third", 600.0)
            station("first", 100.0)
            station("second", -300.0)

            repository.nearest(lat, lon).map { it.name to it.walkingMetres } shouldContainExactly
                listOf("first" to 130, "second" to 390, "third" to 780)
        }

    @Test
    fun `a station just beyond the walk is left out`(): Unit =
        runBlocking {
            station("beyond", 1_160.0)
            station("within", 1_150.0)

            repository.nearest(lat, lon).map { it.name } shouldContainExactly listOf("within")
        }

    @Test
    fun `a venue without coordinates has no stations`(): Unit =
        runBlocking {
            station("first", 100.0)

            repository.nearest(null, lon).shouldBeEmpty()
        }

    @Test
    fun `the walk is the straight line times 1_3, rounded to 10 metres`() {
        NearbyStopRepository.walkingMetres(0.0) shouldBe 0
        NearbyStopRepository.walkingMetres(100.0) shouldBe 130
        NearbyStopRepository.walkingMetres(1_000.0) shouldBe 1_300
        NearbyStopRepository.walkingMetres(3.9) shouldBe 10
    }
}
