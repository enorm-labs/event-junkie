package de.norm.events.artist

import de.norm.events.scraper.EventSource
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [canonicalArtistName].
 *
 * The cases are drawn from real artist names seen across the venue importers,
 * where the same act appears ALL-CAPS on one site and mixed case on another.
 */
class ArtistNormalizerTest {
    @Test
    fun `de-shouts all-caps act names to a clean display form`() {
        assertSoftly {
            canonicalArtistName("GREEN LUNG") shouldBe "Green Lung"
            canonicalArtistName("MUNA") shouldBe "Muna"
            canonicalArtistName("THE BUTLERS") shouldBe "The Butlers"
            canonicalArtistName("EL FLECHA NEGRA") shouldBe "El Flecha Negra"
        }
    }

    @Test
    fun `an ARTIST_SLUG_OVERRIDES entry gives a second act its own slug, in any letter case`() {
        assertSoftly {
            artistSlugFor("Göre") shouldBe "goere"
            artistSlugFor("GÖRE") shouldBe "goere"
            // The act that held the slug first keeps it.
            artistSlugFor("Gore") shouldBe "gore"
            // A name without an entry slugs its canonical form, as before.
            artistSlugFor("OXO86") shouldBe "oxo-86"
        }
    }

    @Test
    fun `a source-scoped entry sends one source's billing of a shared name to its own slug`() {
        assertSoftly {
            artistSlugFor("MASCHINE", "admiralspalast:2026-10-24-maschine") shouldBe "maschine-2"
            // Another source, and the admin API with no source, keep the plain slug.
            artistSlugFor("Maschine", "derweissehase:2026-10-10-maschine") shouldBe "maschine"
            artistSlugFor("Maschine") shouldBe "maschine"
            // The scope is the prefix, not a substring of the event id.
            artistSlugFor("Maschine", "derweissehase:admiralspalast:1") shouldBe "maschine"
        }
    }

    @Test
    fun `every source scope is the sourceId prefix of a real source`() {
        val prefixes = EventSource.entries.map { it.sourceIdPrefix }.toSet()
        sourceScopedArtistSlugPrefixes.filterNot { it in prefixes }.shouldBeEmpty()
    }

    @Test
    fun `folds a backtick typed as an apostrophe`() {
        canonicalArtistName("Dingo`s Dream") shouldBe "Dingo's Dream"
    }

    @Test
    fun `preserves intentional mixed casing`() {
        assertSoftly {
            canonicalArtistName("Green Lung") shouldBe "Green Lung"
            canonicalArtistName("GreyZone") shouldBe "GreyZone"
            canonicalArtistName("will.i.am") shouldBe "will.i.am"
            canonicalArtistName("El Flecha Negra") shouldBe "El Flecha Negra"
        }
    }

    @Test
    fun `keeps short all-caps tokens as acronyms`() {
        assertSoftly {
            canonicalArtistName("DJ KOZE") shouldBe "DJ Koze"
            canonicalArtistName("MC SOLAAR") shouldBe "MC Solaar"
            canonicalArtistName("UK SUBS") shouldBe "UK Subs"
            // Act names that are themselves initialisms.
            canonicalArtistName("FKJ") shouldBe "FKJ"
            canonicalArtistName("AZ") shouldBe "AZ"
            canonicalArtistName("DBG") shouldBe "DBG"
            // An initialism handle inside a multi-token name, where the standalone
            // short-initialism rule does not apply (Matrix bills "DJ JC & DJ GUS").
            canonicalArtistName("DJ JC") shouldBe "DJ JC"
            canonicalArtistName("JAXX TMS") shouldBe "Jaxx TMS"
            // Short all-caps tokens with digits are acronym-like too.
            canonicalArtistName("MC5") shouldBe "MC5"
            canonicalArtistName("UB40") shouldBe "UB40"
        }
    }

    @Test
    fun `keeps a standalone two-letter all-caps name verbatim rather than title-casing it`() {
        assertSoftly {
            // A single short all-caps name is an initialism/stylisation, not a shouted word.
            canonicalArtistName("JJ") shouldBe "JJ"
            canonicalArtistName("EV") shouldBe "EV"
            canonicalArtistName("YU") shouldBe "YU"
            canonicalArtistName("MØ") shouldBe "MØ"
            // Scoped to the whole name: a short word inside a multi-word name still de-shouts.
            canonicalArtistName("WARS OF ATTRITION") shouldBe "Wars Of Attrition"
            // Three-plus letters stay a title-cased shouted word (accepted residual).
            canonicalArtistName("MUNA") shouldBe "Muna"
        }
    }

    @Test
    fun `leaves stylised tokens with digits, dots or slashes untouched`() {
        assertSoftly {
            canonicalArtistName("AC/DC") shouldBe "AC/DC"
            canonicalArtistName("R.E.M.") shouldBe "R.E.M."
            canonicalArtistName("H2O") shouldBe "H2O"
            canonicalArtistName("HGICH.T") shouldBe "HGICH.T"
        }
    }

    @Test
    fun `de-shouts words with attached punctuation, keeping the punctuation in place`() {
        assertSoftly {
            // Possessives and contractions — the apostrophe must not freeze the word in caps.
            canonicalArtistName("MURPHY'S LAW") shouldBe "Murphy's Law"
            canonicalArtistName("FLAMIN' GROOVIES") shouldBe "Flamin' Groovies"
            // An Irish O' prefix keeps the capital after it (#2914).
            canonicalArtistName("LISA O'NEILL") shouldBe "Lisa O'Neill"
            // Trailing/leading punctuation, commas and parentheses.
            canonicalArtistName("AGATHA IS DEAD!") shouldBe "Agatha Is Dead!"
            canonicalArtistName("SICKBOYRARI (BLACK KRAY)") shouldBe "Sickboyrari (Black Kray)"
            canonicalArtistName("EARTH, WIND & FIRE") shouldBe "Earth, Wind & Fire"
        }
    }

    // Production minted `Lsd & The Search for God`, `Sdp & …`, `Ufo 361`, `$Ono$ Cliq` and
    // `Lexy & K-paul` from shouted billings (#1846).
    @Test
    fun `keeps an acronym, a stylised token and each part of a hyphenated name`() {
        assertSoftly {
            canonicalArtistName("LSD & The Search for God") shouldBe "LSD & The Search for God"
            canonicalArtistName("SDP & THE CAPITAL DANCE ORCHESTRA") shouldBe "SDP & The Capital Dance Orchestra"
            canonicalArtistName("UFO 361") shouldBe "UFO 361"
            canonicalArtistName("\$ONO\$ CLIQ") shouldBe "\$ONO\$ Cliq"
            canonicalArtistName("LEXY & K-PAUL") shouldBe "Lexy & K-Paul"
            canonicalArtistName("K-POP FOREVER!") shouldBe "K-Pop Forever!"
            canonicalArtistName("KING NO-ONE") shouldBe "King No-One"
            // Each part meets the acronym list on its own.
            canonicalArtistName("WILDCARD DJ-SLOT") shouldBe "Wildcard DJ-Slot"
            canonicalArtistName("RAFA-FX") shouldBe "Rafa-FX"
            // A digit still keeps the whole token as written.
            canonicalArtistName("BLINK-182") shouldBe "BLINK-182"
        }
    }

    // #2082: initialisms the acronym list lacked, a colon inside a token, a stylisation, and `$`.
    @Test
    fun `keeps the capitals the plausibility check found flattened`() {
        assertSoftly {
            canonicalArtistName("UVB") shouldBe "UVB"
            canonicalArtistName("Joe BRT") shouldBe "Joe BRT"
            canonicalArtistName("DJ TC (Blactro)") shouldBe "DJ TC (Blactro)"
            canonicalArtistName("FORMAT:B") shouldBe "Format:B"
            canonicalArtistName("CCOSMO") shouldBe "CCOSMO"
            canonicalArtistName("A\$AP Rocky") shouldBe "A\$AP Rocky"
            canonicalArtistName("DJ LITO BOLTON") shouldBe "DJ Lito Bolton"
        }
    }

    // Säälchen bills `MICHA`, and the artist writes it so (#3623).
    @Test
    fun `keeps MICHA in capitals however it is billed, and leaves a longer name alone`() {
        assertSoftly {
            canonicalArtistName("MICHA") shouldBe "MICHA"
            canonicalArtistName("Micha") shouldBe "MICHA"
            canonicalArtistName("micha") shouldBe "MICHA"
            canonicalArtistName("Micha Moor") shouldBe "Micha Moor"
            canonicalArtistName("MICHAEL") shouldBe "Michael"
            canonicalArtistName("Michael") shouldBe "Michael"
        }
    }

    // silent green bills `Andrea Pensado + HMOT + bela`, Schokoladen `… + D'CO (…)` and `… + LVFT (…)`; each act is split off alone (#3641).
    @Test
    fun `keeps HMOT, D'CO and LVFT in capitals, alone or in a shouted billing`() {
        assertSoftly {
            canonicalArtistName("HMOT") shouldBe "HMOT"
            canonicalArtistName("D'CO") shouldBe "D'CO"
            canonicalArtistName("LVFT") shouldBe "LVFT"
            canonicalArtistName("HMOT LIVE") shouldBe "HMOT Live"
            // A spelling the venue styles itself is kept, as for every other entry.
            canonicalArtistName("Hmot") shouldBe "Hmot"
            // Soulcat's dub DJ `DCO` is another act, and still de-shouts to its own slug.
            canonicalArtistName("DCO") shouldBe "Dco"
            artistSlugFor("DCO") shouldBe "dco"
            artistSlugFor("D'CO") shouldBe "d-co"
        }
    }

    @Test
    fun `keeps FIL in capitals`() {
        canonicalArtistName("FIL") shouldBe "FIL"
        artistSlugFor("FIL") shouldBe "fil"
    }

    // Weekend billed `HB&P Soundsystem` (#3480) and The Door `ABF & Friends` (#3501), stored as `Hb&p …` and `Abf …` (#3507).
    @Test
    fun `keeps the initialisms of a billing that is not shouted`() {
        assertSoftly {
            canonicalArtistName("HB&P Soundsystem") shouldBe "HB&P Soundsystem"
            canonicalArtistName("ABF & Friends") shouldBe "ABF & Friends"
            canonicalArtistName("NVST b2b Ben Klock") shouldBe "NVST b2b Ben Klock"
            // A billing in capitals throughout is shouted, as before.
            canonicalArtistName("HB&P SOUNDSYSTEM") shouldBe "Hb&p Soundsystem"
            canonicalArtistName("NVST B2B BEN KLOCK") shouldBe "Nvst B2B Ben Klock"
            // Lowercase alone is not mixed case, so `b2b` does not stop a de-shout.
            canonicalArtistName("NVST b2b MUNA") shouldBe "Nvst b2b Muna"
            // `ß` has no capital in common use, so it does not make a billing mixed case.
            canonicalArtistName("GROßE FREIHEIT") shouldBe "GROßE Freiheit"
            // A Cyrillic billing in capitals de-shouts like a Latin one.
            canonicalArtistName("ВУИНДЛИЯ") shouldBe "Вуиндлия"
            // Two all-caps words side by side are a shouted name, also across a `&` (SO36, V089).
            canonicalArtistName("Kai & Funky von TON STEINE SCHERBEN") shouldBe "Kai & Funky von Ton Steine Scherben"
            canonicalArtistName("PÖBEL & GESOCKS Live") shouldBe "Pöbel & Gesocks Live"
            // A long billing: each lone initialism stays, each shouted run de-shouts.
            canonicalArtistName("THE ORB & Friends Featuring KLF And MGMT With Special Guests DJ KOZE") shouldBe
                "The Orb & Friends Featuring KLF And MGMT With Special Guests DJ Koze"
        }
    }

    @Test
    fun `never strips a word from a band name`() {
        assertSoftly {
            // Words a promoter normalizer would strip are load-bearing in band names.
            canonicalArtistName("THE RECORDS") shouldBe "The Records"
            canonicalArtistName("ARCADE FIRE CONCERTS") shouldBe "Arcade Fire Concerts"
        }
    }

    @Test
    fun `normalizes surrounding and internal whitespace`() {
        canonicalArtistName("  GREEN   LUNG  ") shouldBe "Green Lung"
    }

    @Test
    fun `falls back to the trimmed input for blank names`() {
        canonicalArtistName("   ") shouldBe ""
    }

    // Audit T-10: the Berlin punk band is written "OXO86" by SO36 and "Oxo 86" by Huxleys. Its
    // digit keeps it out of the de-shouter (a stylised token), so the spellings never converged
    // and the act sat in two artist rows.
    @Test
    fun `folds a curated spelling variant onto one act`() {
        assertSoftly {
            canonicalArtistName("OXO86") shouldBe "Oxo 86"
            canonicalArtistName("Oxo 86") shouldBe "Oxo 86"
            canonicalArtistName("oxo86") shouldBe "Oxo 86"
        }
    }

    @Test
    fun `does not fold two acts that merely share a normalized key`() {
        // "Paul K" is a DJ billed at Ritter Butzke, "Paulk" a live act at Badehaus — same letters,
        // different acts. Only a curated entry may merge artists, and there is none for these.
        assertSoftly {
            canonicalArtistName("Paul K") shouldBe "Paul K"
            canonicalArtistName("Paulk") shouldBe "Paulk"
        }
    }

    @Test
    fun `folds the duplicates the artist cleanup confirmed onto one spelling`() {
        assertSoftly {
            canonicalArtistName("DJ Buttchin") shouldBe "Buttchin"
            canonicalArtistName("DJ Don Rogall") shouldBe "Don Rogall"
            canonicalArtistName("DJ BEN WASHINGTON") shouldBe "Ben Washington"
            canonicalArtistName("sterni") shouldBe "Sterni"
            canonicalArtistName("Bluesky") shouldBe "Blue Sky"
            canonicalArtistName("TraumaMia") shouldBe "Trauma Mia"
            canonicalArtistName("Uppercuts") shouldBe "The Uppercuts"
            canonicalArtistName("The Uppercuts") shouldBe "The Uppercuts"
            canonicalArtistName("Dark Tenor") shouldBe "The Dark Tenor"
            // Different acts that share a key stay apart.
            canonicalArtistName("DJ Gus") shouldBe "DJ Gus"
            canonicalArtistName("K-Lone") shouldBe "K-Lone"
        }
    }
}
