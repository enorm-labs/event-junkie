package de.norm.events.image

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

/**
 * The meters the image cache publishes, asserted by the strings a rule selects on.
 *
 * **Names and tags are written out literally rather than through the constants**, deliberately and
 * for the reason the architecture rules give: referring to `ImageCacheMetrics.URLS` would pass
 * through any rename, and a rename is exactly the change that breaks `gen_alerts.py` with nothing to
 * report it.
 */
class ImageCacheMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = ImageCacheMetrics(registry)

    /**
     * **The property every alert rule here depends on.** A Micrometer counter is absent from the
     * exposition until it first increments, and a rule against an absent series never fires while
     * looking exactly like health — `deploy/alerts/README.md` records the outage that taught this.
     */
    @Test
    fun `every counter exists at zero before anything happens`() {
        listOf("fetched", "unchanged", "failed").forEach { counter("images.fetch", "outcome", it) shouldBe 0.0 }
        listOf("written", "refused").forEach { counter("images.derivatives", "outcome", it) shouldBe 0.0 }
        listOf("rows", "objects", "strays").forEach { counter("images.sweep.deleted", "kind", it) shouldBe 0.0 }
    }

    @Test
    fun `every gauge exists at zero before anything happens`() {
        listOf("cached", "failed", "pending", "withheld").forEach { gauge("images.urls", "state", it) shouldBe 0.0 }
        listOf("rows", "strays").forEach { gauge("images.sweep.candidates", "kind", it) shouldBe 0.0 }
        registry
            .find("images.derivatives.backlog")
            .gauge()
            .shouldNotBeNull()
            .value() shouldBe 0.0
    }

    @Test
    fun `a fetch pass counts each outcome separately`() {
        metrics.recordFetchPass(CacheOutcome(fetched = 3, unchanged = 2, failed = 1))
        metrics.recordFetchPass(CacheOutcome(fetched = 1))

        counter("images.fetch", "outcome", "fetched") shouldBe 4.0
        counter("images.fetch", "outcome", "unchanged") shouldBe 2.0
        counter("images.fetch", "outcome", "failed") shouldBe 1.0
    }

    /** `variants`, not `images`: the counter measures files written, which is what the backlog drains. */
    @Test
    fun `a derivative pass counts files rather than images`() {
        metrics.recordDerivativePass(DerivativeOutcome(images = 2, variants = 9, refused = 3))

        counter("images.derivatives", "outcome", "written") shouldBe 9.0
        counter("images.derivatives", "outcome", "refused") shouldBe 3.0
    }

    /**
     * **The whole point of the candidate gauges.** `app.images.sweep.enabled` is off by default so a
     * deletion rule can be watched before it is trusted, and in that mode the only report was a log
     * line every six hours. The gauge has to move; the deletion counter must not.
     */
    @Test
    fun `a reporting-only sweep publishes what it found and deletes nothing`() {
        metrics.recordSweep(RemovalOutcome(images = 4, objects = 12, strays = 7), deleting = false)

        gauge("images.sweep.candidates", "kind", "rows") shouldBe 4.0
        gauge("images.sweep.candidates", "kind", "strays") shouldBe 7.0
        counter("images.sweep.deleted", "kind", "objects") shouldBe 0.0
        counter("images.sweep.deleted", "kind", "rows") shouldBe 0.0
    }

    @Test
    fun `a deleting sweep publishes both`() {
        metrics.recordSweep(RemovalOutcome(images = 4, objects = 12, strays = 7), deleting = true)

        gauge("images.sweep.candidates", "kind", "rows") shouldBe 4.0
        counter("images.sweep.deleted", "kind", "rows") shouldBe 4.0
        counter("images.sweep.deleted", "kind", "objects") shouldBe 12.0
        counter("images.sweep.deleted", "kind", "strays") shouldBe 7.0
    }

    /**
     * A gauge is set rather than added to, so a backlog that drains is reported as draining.
     * A counter would only ever rise, which is the wrong shape for a queue depth.
     */
    @Test
    fun `the url state gauges replace their previous value`() {
        metrics.updateUrlStates(ImageUrlCountsRow(cached = 10, failed = 2, pending = 40, withheld = 1))
        metrics.updateUrlStates(ImageUrlCountsRow(cached = 50, failed = 2, pending = 0, withheld = 1))

        gauge("images.urls", "state", "cached") shouldBe 50.0
        gauge("images.urls", "state", "pending") shouldBe 0.0
        gauge("images.urls", "state", "failed") shouldBe 2.0
        gauge("images.urls", "state", "withheld") shouldBe 1.0
    }

    @Test
    fun `the derivative backlog is a gauge`() {
        metrics.updateDerivativeBacklog(120)
        metrics.updateDerivativeBacklog(3)

        registry
            .find("images.derivatives.backlog")
            .gauge()
            .shouldNotBeNull()
            .value() shouldBe 3.0
    }

    private fun counter(
        name: String,
        key: String,
        value: String
    ): Double =
        registry
            .find(name)
            .tag(key, value)
            .counter()
            .shouldNotBeNull()
            .count()

    private fun gauge(
        name: String,
        key: String,
        value: String
    ): Double =
        registry
            .find(name)
            .tag(key, value)
            .gauge()
            .shouldNotBeNull()
            .value()
}
