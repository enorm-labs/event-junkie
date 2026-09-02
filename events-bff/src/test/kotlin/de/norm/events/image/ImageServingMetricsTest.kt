package de.norm.events.image

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.util.unit.DataSize

/**
 * The two questions the Caffeine meters cannot answer, asserted by the strings a rule selects on.
 *
 * Names are written out literally rather than through the constants, deliberately: referring to
 * [ImageServingMetrics.SERVED] would pass through any rename, and a rename is the change that breaks
 * `gen_alerts.py` with nothing to report it.
 */
class ImageServingMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val cache = ImageObjectCache(ImageServingProperties(cache = ImageServingProperties.Cache(DataSize.ofMegabytes(1))), registry)
    private val metrics = ImageServingMetrics(registry, cache)

    /**
     * **`missing` is the outcome a rule is written against, and on a healthy origin it never
     * happens** — so without registering it at zero the series does not exist, and a rule against it
     * can never fire while looking exactly like health (`deploy/alerts/README.md`).
     */
    @Test
    fun `every outcome exists at zero before a single request`() {
        listOf("found", "unknown", "missing", "unavailable").forEach { withClue("outcome=$it") { counter(it) shouldBe 0.0 } }
    }

    @Test
    fun `each outcome counts separately`() {
        metrics.record(ImageServingMetrics.Outcome.FOUND)
        metrics.record(ImageServingMetrics.Outcome.FOUND)
        metrics.record(ImageServingMetrics.Outcome.MISSING)

        counter("found") shouldBe 2.0
        counter("missing") shouldBe 1.0
        counter("unavailable") shouldBe 0.0
    }

    /**
     * **The gap this gauge exists for.** `CaffeineCacheMetrics` publishes `cache_size`, which is the
     * number of entries — but the cache is bounded by *weight*, so only bytes say whether the
     * ceiling is anywhere near. Two entries are `cache_size 2` whether they hold 50 kB or 5 MB, and
     * that difference is the whole of the sizing question in #847.
     */
    @Test
    @DisplayName("the weight gauge reports bytes, where cache_size reports entries")
    fun `the weight gauge reports bytes rather than entries`() =
        runTest {
            cache.get("one") { ImageObject.Found(ByteArray(BIG)) }
            cache.get("two") { ImageObject.Found(ByteArray(SMALL)) }

            weight() shouldBe (BIG + SMALL).toDouble()
            registry
                .find("cache.size")
                .tag("cache", "images")
                .gauge()
                .shouldNotBeNull()
                .value() shouldBe 2.0
        }

    @Test
    fun `an empty cache weighs nothing`() {
        weight() shouldBe 0.0
    }

    private fun counter(outcome: String): Double =
        withClue("no counter for $outcome") {
            registry
                .find("bff.images.served")
                .tag("outcome", outcome)
                .counter()
                .shouldNotBeNull()
        }.count()

    private fun weight(): Double = withClue("no weight gauge") { registry.find("bff.images.cache.weight").gauge().shouldNotBeNull() }.value()

    private companion object {
        const val BIG = 40_000
        const val SMALL = 12_000
    }
}
