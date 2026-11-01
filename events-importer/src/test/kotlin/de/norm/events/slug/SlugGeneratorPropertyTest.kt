package de.norm.events.slug

import de.norm.events.common.NameArbs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Properties every slug holds, whatever the name: [SlugGeneratorTest] pins the examples. */
class SlugGeneratorPropertyTest {
    @Test
    fun `a slug is empty or lower-case ASCII words joined by single hyphens`() =
        runTest {
            checkAll(NameArbs.anyName) { name ->
                val slug = SlugGenerator.slugify(name)
                if (slug.isNotEmpty()) slug shouldMatch SLUG_SHAPE
            }
        }

    @Test
    fun `slugging a slug changes nothing`() =
        runTest {
            checkAll(NameArbs.anyName) { name ->
                val slug = SlugGenerator.slugify(name)
                SlugGenerator.slugify(slug) shouldBe slug
            }
        }

    @Test
    fun `letter case does not change the slug`() =
        runTest {
            checkAll(NameArbs.latinName) { name ->
                SlugGenerator.slugify(name.uppercase()) shouldBe SlugGenerator.slugify(name.lowercase())
            }
            checkAll(NameArbs.nonLatinName) { name ->
                SlugGenerator.slugify(name.uppercase()) shouldBe SlugGenerator.slugify(name.lowercase())
            }
        }

    @Test
    fun `surrounding spaces and hyphens do not change the slug`() =
        runTest {
            checkAll(NameArbs.anyName) { name ->
                SlugGenerator.slugify(" - $name -  ") shouldBe SlugGenerator.slugify(name)
            }
        }

    private companion object {
        val SLUG_SHAPE = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    }
}
