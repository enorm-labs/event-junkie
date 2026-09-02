package de.norm.events.promoter

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [canonicalPromoterName].
 *
 * The cases are drawn from real promoter names seen across the venue importers,
 * where the same promoter appears abbreviated on one site and with a full trading
 * name on another.
 */
class PromoterNormalizerTest {
    @Test
    fun `merges abbreviated and full trading-name variants of the same promoter`() {
        assertSoftly {
            // "Loft" reduces to a single word, then a NAME_CORRECTIONS entry pins the fuller
            // preferred brand name so every variant resolves to "Loft Concerts".
            canonicalPromoterName("LOFT") shouldBe "Loft Concerts"
            canonicalPromoterName("Loft Concerts") shouldBe "Loft Concerts"
            canonicalPromoterName("Loft Concert GmbH") shouldBe "Loft Concerts"
            canonicalPromoterName("Loft Concerts GmbH") shouldBe "Loft Concerts"
            canonicalPromoterName("UNDERCOVER") shouldBe "Undercover"
            canonicalPromoterName("Undercover GmbH") shouldBe "Undercover"
            canonicalPromoterName("TRINITY") shouldBe "Trinity"
            canonicalPromoterName("Trinity Music") shouldBe "Trinity"
            canonicalPromoterName("Trinity Music GmbH") shouldBe "Trinity"
            canonicalPromoterName("LANDSTREICHER") shouldBe "Landstreicher"
            canonicalPromoterName("Landstreicher Konzerte") shouldBe "Landstreicher"
            canonicalPromoterName("Landstreicher Konzerte GmbH") shouldBe "Landstreicher"
            canonicalPromoterName("Boese") shouldBe "Boese"
            canonicalPromoterName("Boese Live") shouldBe "Boese"
            // The radio station is spelled three ways across sources, and the spaced form
            // de-shouts to "Flux Fm"; all four share one key and fold onto its own casing.
            canonicalPromoterName("FluxFM") shouldBe "FluxFM"
            canonicalPromoterName("fluxfm") shouldBe "FluxFM"
            canonicalPromoterName("Flux FM") shouldBe "FluxFM"
            canonicalPromoterName("FLUXFM") shouldBe "FluxFM"
        }
    }

    @Test
    fun `de-shouts all-caps labels but preserves intentional mixed casing`() {
        assertSoftly {
            canonicalPromoterName("SIMPLY QUIZ") shouldBe "Simply Quiz"
            canonicalPromoterName("THE SWAG") shouldBe "The Swag"
            canonicalPromoterName("FANIA BRAVA") shouldBe "Fania Brava"
            // Mixed casing is a deliberate style choice — leave it alone.
            canonicalPromoterName("GreyZone Concerts") shouldBe "GreyZone"
            canonicalPromoterName("Greyzone") shouldBe "Greyzone"
        }
    }

    @Test
    fun `folds known typos and spelling variants onto their correct spelling`() {
        assertSoftly {
            canonicalPromoterName("Trinty") shouldBe "Trinity"
            canonicalPromoterName("TRINTY") shouldBe "Trinity"
            // The correction applies after descriptor-stripping and de-shouting.
            canonicalPromoterName("Trinty Music GmbH") shouldBe "Trinity"
            canonicalPromoterName("Radioactve") shouldBe "Radioactive"
            canonicalPromoterName("Radioactve Events") shouldBe "Radioactive"
            // The correctly spelled name is untouched and resolves to the same canonical form.
            canonicalPromoterName("Trinity Music") shouldBe "Trinity"
        }
    }

    @Test
    fun `folds spacing and casing variants of the same name onto one spelling`() {
        // A single correction-map entry, keyed on the space-insensitive form, merges all three.
        assertSoftly {
            canonicalPromoterName("All Rooms") shouldBe "All Rooms"
            canonicalPromoterName("Allrooms") shouldBe "All Rooms"
            canonicalPromoterName("ALLROOMS") shouldBe "All Rooms"
        }
    }

    @Test
    fun `does not strip a descriptor word that is not trailing`() {
        // "Concert" is load-bearing here (it's the promoter "Concert Concept"),
        // and only trailing descriptors are stripped, so the name is left intact.
        canonicalPromoterName("Concert Concept") shouldBe "Concert Concept"
    }

    @Test
    fun `never strips a promoter down to nothing`() {
        assertSoftly {
            // A promoter named purely of descriptor words keeps its single word.
            canonicalPromoterName("Records") shouldBe "Records"
            canonicalPromoterName("Concerts") shouldBe "Concerts"
        }
    }

    @Test
    fun `keeps a trailing descriptor when the remaining name has no letters`() {
        // Stripping "Concerts" off "36 Concerts" would leave the bare number "36",
        // which is not a usable promoter name — so the descriptor is kept.
        assertSoftly {
            canonicalPromoterName("36 Concerts") shouldBe "36 Concerts"
            canonicalPromoterName("36 Concerts GmbH") shouldBe "36 Concerts"
            // A letter-bearing name still strips its trailing descriptor as before.
            canonicalPromoterName("Loft 36 Concerts") shouldBe "Loft 36"
        }
    }

    @Test
    fun `normalizes surrounding and internal whitespace`() {
        canonicalPromoterName("  Loft   Concerts  ") shouldBe "Loft Concerts"
    }

    @Test
    fun `strips a trailing parenthetical annotation before descriptor-stripping`() {
        assertSoftly {
            // The "(wf)" annotation shielded "GmbH" from the trailing-descriptor strip.
            canonicalPromoterName("MIND Enterprises GmbH (wf)") shouldBe "Mind Enterprises"
            canonicalPromoterName("Live Nation (GSA)") shouldBe "Live Nation"
        }
    }

    @Test
    fun `flags bare generic labels as non-promoter names`() {
        assertSoftly {
            // Pure descriptor labels a source drops into the promoter slot — not real promoters.
            isNonPromoterName("Event.") shouldBe true
            isNonPromoterName("Konzert") shouldBe true
            isNonPromoterName("Concerts GmbH") shouldBe true
            isNonPromoterName("   ") shouldBe true
            // Anything with a distinctive word is a real promoter and kept.
            isNonPromoterName("Concert Concept") shouldBe false
            isNonPromoterName("Loft Concerts GmbH") shouldBe false
            isNonPromoterName("MIND Enterprises GmbH (wf)") shouldBe false
        }
    }

    // Audit T-9: two Loge promoters kept the presenter verb they head their billings with.
    @Test
    fun `strips a trailing presenter verb`() {
        assertSoftly {
            canonicalPromoterName("porcupine records & little league shows prsnt:") shouldBe
                "porcupine records & little league shows"
            canonicalPromoterName("Geisburg Records prsnt:") shouldBe "Geisburg"
            canonicalPromoterName("Kaenguruh präsentiert") shouldBe "Kaenguruh"
        }
    }

    @Test
    fun `keeps Presents where it is part of the brand name`() {
        // "AEG Presents" is the company's actual trading name, not a verb — stripping it would
        // leave the unusable "Aeg".
        canonicalPromoterName("AEG Presents") shouldBe "Aeg Presents"
    }

    // Audit T-10: spelling variants that fragmented one promoter into several rows.
    @Test
    fun `folds curated spelling variants onto one promoter`() {
        assertSoftly {
            canonicalPromoterName("radioeins") shouldBe "radioeins"
            canonicalPromoterName("Radio Eins") shouldBe "radioeins"
            canonicalPromoterName("tipBerlin") shouldBe "tip Berlin"
            canonicalPromoterName("tip Berlin") shouldBe "tip Berlin"
            canonicalPromoterName("KKT") shouldBe "KKT"
            canonicalPromoterName("KKT GmbH – Kikis Kleiner Tourneeservice") shouldBe "KKT"
        }
    }
}
