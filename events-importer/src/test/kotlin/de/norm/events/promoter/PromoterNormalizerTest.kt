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
            // The stripped "Music" is restored by a correction entry, so the trading name is the
            // display form from every source (#1139).
            canonicalPromoterName("TRINITY") shouldBe "Trinity Music"
            canonicalPromoterName("Trinity Music") shouldBe "Trinity Music"
            canonicalPromoterName("Trinity Music GmbH") shouldBe "Trinity Music"
            canonicalPromoterName("Trinty") shouldBe "Trinity Music"
            // A leading descriptor is kept, and the slug-derived form folds onto it.
            canonicalPromoterName("Konzertbüro Schoneberg") shouldBe "Konzertbüro Schoneberg"
            canonicalPromoterName("Schoneberg") shouldBe "Konzertbüro Schoneberg"
            canonicalPromoterName("KONZERTBÜRO SCHONEBERG") shouldBe "Konzertbüro Schoneberg"
            canonicalPromoterName("LANDSTREICHER") shouldBe "Landstreicher Konzerte"
            canonicalPromoterName("Landstreicher Konzerte") shouldBe "Landstreicher Konzerte"
            canonicalPromoterName("Landstreicher Konzerte GmbH") shouldBe "Landstreicher Konzerte"
            canonicalPromoterName("Boese") shouldBe "Boese Live"
            canonicalPromoterName("Boese Live") shouldBe "Boese Live"
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
            canonicalPromoterName("LassMaMachen") shouldBe "LassMaMachen"
            canonicalPromoterName("amSTARt") shouldBe "amSTARt"
        }
    }

    @Test
    fun `folds known typos and spelling variants onto their correct spelling`() {
        assertSoftly {
            canonicalPromoterName("Trinty") shouldBe "Trinity Music"
            canonicalPromoterName("TRINTY") shouldBe "Trinity Music"
            // The correction applies after descriptor-stripping and de-shouting.
            canonicalPromoterName("Trinty Music GmbH") shouldBe "Trinity Music"
            canonicalPromoterName("Radioactve") shouldBe "Radioactive"
            canonicalPromoterName("Radioactve Events") shouldBe "Radioactive"
            // The correctly spelled name resolves to the same canonical form.
            canonicalPromoterName("Trinity Music") shouldBe "Trinity Music"
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

    // #1318: the rows a review found in the promoter slot that name no promoter.
    @Test
    fun `flags fragments and titles a source prints as the promoter`() {
        assertSoftly {
            // One or two letters, whatever the case.
            isNonPromoterName("Ar") shouldBe true
            isNonPromoterName("QU") shouldBe true
            // Three letters only by name: "KKT", "IBB" and "MCT" are promoters, and so were
            // "Itd" and "Mfp" once the whole credit was read (#1361).
            isNonPromoterName("Bum") shouldBe true
            isNonPromoterName("KKT") shouldBe false
            isNonPromoterName("IBB") shouldBe false
            isNonPromoterName("ITD Events") shouldBe false
            isNonPromoterName("MFP Concerts") shouldBe false
            // Named fragments, keyed without casing or punctuation, with or without a presenter verb.
            isNonPromoterName("Kneipenabend") shouldBe true
            isNonPromoterName("Sunday-Matinee") shouldBe true
            isNonPromoterName("Tag Der Klubkultur") shouldBe true
            isNonPromoterName("Peter Edel & www.stummfilmkonzerte.de") shouldBe true
            isNonPromoterName("Das forgotten female* composers") shouldBe true
            isNonPromoterName("Das forgotten female* composers präsentieren:") shouldBe true
            // Short real names stay.
            isNonPromoterName("Echo") shouldBe false
            isNonPromoterName("Join") shouldBe false
            isNonPromoterName("HB Music") shouldBe false
            isNonPromoterName("Zart") shouldBe false
        }
    }

    // Audit T-9: two Loge promoters kept the presenter verb they head their billings with.
    @Test
    fun `strips a trailing presenter verb`() {
        assertSoftly {
            canonicalPromoterName("porcupine records & little league shows prsnt:") shouldBe
                "porcupine records & little league shows"
            canonicalPromoterName("Geisburg Records prsnt:") shouldBe "Geisburg"
            canonicalPromoterName("Kaenguruh präsentiert") shouldBe "Känguruh Production"
        }
    }

    @Test
    fun `keeps Presents where it is part of the brand name`() {
        // "AEG Presents" is the company's actual trading name, not a verb — stripping it would
        // leave the bare "AEG".
        canonicalPromoterName("AEG Presents") shouldBe "AEG Presents"
    }

    // #304: the de-shout is the artist normalizer's, so its acronym list applies to promoters.
    @Test
    fun `keeps a genuine acronym in its capitals when de-shouting`() {
        assertSoftly {
            canonicalPromoterName("TV NOIR") shouldBe "TV Noir"
            canonicalPromoterName("TV Noir") shouldBe "TV Noir"
            canonicalPromoterName("BOSSA FM") shouldBe "Bossa FM"
            canonicalPromoterName("Bossa FM") shouldBe "Bossa FM"
            // Three letters and not on the list: nothing distinguishes it from a shouted word.
            canonicalPromoterName("MIND Enterprises") shouldBe "Mind Enterprises"
        }
    }

    @Test
    fun `keeps a trailing descriptor when the remaining name is a bare initialism`() {
        assertSoftly {
            // Stripping "Music" would leave "HB", and de-shouting that leaves "Hb": a display name
            // that no longer names anything. The descriptor stays, and the initialism keeps its
            // capitals.
            canonicalPromoterName("HB Music") shouldBe "HB Music"
            canonicalPromoterName("HB MUSIC") shouldBe "HB Music"
            canonicalPromoterName("HB Music GmbH") shouldBe "HB Music"
            canonicalPromoterName("HB") shouldBe "HB"
            // A short shouted word in front of a real name is still a word.
            canonicalPromoterName("MY Concerts Berlin") shouldBe "My Concerts Berlin"
        }
    }

    // Audit T-10: spelling variants that fragmented one promoter into several rows.
    @Test
    fun `folds curated spelling variants onto one promoter`() {
        assertSoftly {
            canonicalPromoterName("radioeins") shouldBe "radioeins"
            canonicalPromoterName("Radio Eins") shouldBe "radioeins"
            canonicalPromoterName("tipBerlin") shouldBe "tipBerlin"
            canonicalPromoterName("tip Berlin") shouldBe "tipBerlin"
            // Zitadelle prints the magazine as a bare "Tip" (#304).
            canonicalPromoterName("Tip") shouldBe "tipBerlin"
            canonicalPromoterName("TIP") shouldBe "tipBerlin"
            canonicalPromoterName("KKT") shouldBe "KKT"
            canonicalPromoterName("Kkt") shouldBe "KKT"
            canonicalPromoterName("KKT GmbH – Kikis Kleiner Tourneeservice") shouldBe "KKT"
        }
    }

    // #328: every pair here was two rows on staging, and a person confirmed each is one promoter.
    @Test
    fun `strips the longer German legal forms and descriptors`() {
        assertSoftly {
            canonicalPromoterName("Concert Concept Veranstaltungs-GmbH") shouldBe "Concert Concept"
            canonicalPromoterName("Concert Concept Veranstaltungs") shouldBe "Concert Concept"
            canonicalPromoterName("FKP Scorpio Konzertproduktionen") shouldBe "FKP Scorpio"
            canonicalPromoterName("Fkp Scorpio") shouldBe "FKP Scorpio"
            canonicalPromoterName("Landstreicher Kulturproduktionen") shouldBe "Landstreicher Konzerte"
            canonicalPromoterName("Karsten Jahnke Konzertdirektion") shouldBe "Karsten Jahnke Konzertdirektion"
            canonicalPromoterName("Karsten Jahnke") shouldBe "Karsten Jahnke Konzertdirektion"
            canonicalPromoterName("Antonio Garcia Einzelunternehmer") shouldBe "Antonio Garcia"
            canonicalPromoterName("Kaenguruh Production Konzertagentur") shouldBe "Känguruh Production"
            canonicalPromoterName("Känguruh Production Konzertagentur") shouldBe "Känguruh Production"
            // A broadcaster, not a legal form: "International" is deliberately not stripped.
            canonicalPromoterName("Radio France International") shouldBe "Radio France Internationale"
        }
    }

    @Test
    fun `folds the remaining staging pairs onto one spelling`() {
        assertSoftly {
            canonicalPromoterName("All Room") shouldBe "All Rooms"
            canonicalPromoterName("Atok Berlin") shouldBe "ATOK Berlin"
            canonicalPromoterName("ATOK") shouldBe "ATOK Berlin"
            canonicalPromoterName("Audiolith International") shouldBe "Audiolith"
            canonicalPromoterName("Streetlife") shouldBe "Streetlife International"
            canonicalPromoterName("Streetlife International") shouldBe "Streetlife International"
            canonicalPromoterName("listenagency") shouldBe "Friendly Reminder"
            canonicalPromoterName("GreyZone Concerts") shouldBe "Greyzone Concerts"
            canonicalPromoterName("Greyzone") shouldBe "Greyzone Concerts"
            canonicalPromoterName("Greyzone Concerts & Promotion Grey & von Bronikowski") shouldBe "Greyzone Concerts"
            canonicalPromoterName("Messed!Up Magazine") shouldBe "Messed!Up Magazine"
            canonicalPromoterName("MessedUp! Magazine") shouldBe "Messed!Up Magazine"
            canonicalPromoterName("MusikBlog.de") shouldBe "MusikBlog"
            canonicalPromoterName("Musikblog") shouldBe "MusikBlog"
            canonicalPromoterName("Prk Dreamhouse") shouldBe "PRK DreamHaus"
            canonicalPromoterName("PRK DreamHaus") shouldBe "PRK DreamHaus"
            canonicalPromoterName("Rausgeganger") shouldBe "Rausgegangen"
            canonicalPromoterName("Punkfilmfestival Berlin") shouldBe "punkfilmfest berlin"
            // "Listen" is Listen Collective, a different company from listenagency, and stays.
            canonicalPromoterName("Listen") shouldBe "Listen"
        }
    }

    // #328: the spelling on the promoter's own site, checked in docs/promoters/REVIEWED.tsv.
    @Test
    fun `pins the spelling each promoter uses itself`() {
        assertSoftly {
            canonicalPromoterName("Semmel Concerts Entertainment GmbH") shouldBe "Semmel Concerts"
            canonicalPromoterName("Semmel") shouldBe "Semmel Concerts"
            canonicalPromoterName("Headline") shouldBe "Headline Concerts"
            canonicalPromoterName("Powerline Agency") shouldBe "Powerline Agency"
            canonicalPromoterName("powerline") shouldBe "Powerline Agency"
            canonicalPromoterName("MCT Agentur GmbH") shouldBe "MCT Agentur"
            canonicalPromoterName("Mct") shouldBe "MCT Agentur"
            canonicalPromoterName("MAWI Concert") shouldBe "MAWI Concert"
            canonicalPromoterName("Zart") shouldBe "Z|ART Agency"
            canonicalPromoterName("Z|ART Agency") shouldBe "Z|ART Agency"
            canonicalPromoterName("New Berlin Konzerte & Events GmbH") shouldBe "New Berlin Konzerte"
            canonicalPromoterName("Berlinkonzerte") shouldBe "New Berlin Konzerte"
            canonicalPromoterName("Dlf") shouldBe "Deutschlandfunk"
            canonicalPromoterName("Ibb") shouldBe "IBB Booking"
            canonicalPromoterName("Ox-Fancine") shouldBe "Ox-Fanzine"
            canonicalPromoterName("Radio Bob") shouldBe "RADIO BOB!"
            canonicalPromoterName("Rockitsessions,") shouldBe "Rockitsessions"
            canonicalPromoterName("TouringTunes Sp. z o. o.") shouldBe "TouringTunes"
            canonicalPromoterName("Rudelsingen - Das Original aus Münster") shouldBe "Rudelsingen"
            canonicalPromoterName("Aok. Die Gesundheitskasse") shouldBe "AOK"
            canonicalPromoterName("Unreleased") shouldBe "Unreleased Berlin"
            canonicalPromoterName("Kulturalarm") shouldBe "kulturALARM"
            canonicalPromoterName("Metal.de") shouldBe "metal.de"
        }
    }

    // #307: a two-letter initial in front of a stripped descriptor came out title-cased.
    @Test
    fun `restores the trading name behind an initialism the strip exposed`() {
        assertSoftly {
            canonicalPromoterName("JB Freie Musik") shouldBe "JB Freie Musik"
            canonicalPromoterName("Jb Freie") shouldBe "JB Freie Musik"
            canonicalPromoterName("JM Audio Entertainment") shouldBe "JM Audio Entertainment"
            canonicalPromoterName("Jm Audio") shouldBe "JM Audio Entertainment"
            // The venue itself writes "We Artists", so that one was right all along.
            canonicalPromoterName("We Artists") shouldBe "We Artists"
        }
    }

    // #1361: a descriptor stays on a three-letter initialism, a legal form still comes off, and
    // the rows #1318 stored under the stripped word resolve to the whole name.
    @Test
    fun `keeps a descriptor on a short initialism and restores the credits the strip had reduced`() {
        assertSoftly {
            canonicalPromoterName("MFP Concerts") shouldBe "MFP Concerts"
            canonicalPromoterName("ITD Events") shouldBe "ITD Events"
            canonicalPromoterName("ACT agency") shouldBe "ACT Agency"
            canonicalPromoterName("KKT GmbH") shouldBe "KKT"
            canonicalPromoterName("Channel Music") shouldBe "Channel Music"
            canonicalPromoterName("Spirit Events") shouldBe "Spirit Events"
            canonicalPromoterName("LEASING&RENT OÜ") shouldBe "LEASING&RENT OÜ"
            canonicalPromoterName("Mfp") shouldBe "MFP Concerts"
            canonicalPromoterName("Itd") shouldBe "ITD Events"
            canonicalPromoterName("Act") shouldBe "ACT Agency"
            canonicalPromoterName("Leasing&rent") shouldBe "LEASING&RENT OÜ"
        }
    }

    @Test
    fun `resolves Lido's Atoc Live credit to the company behind it`() {
        assertSoftly {
            canonicalPromoterName("Atoc Live") shouldBe "ATOC Soundlab"
            canonicalPromoterName("Atoc") shouldBe "ATOC Soundlab"
            canonicalPromoterName("ATOC Soundlab") shouldBe "ATOC Soundlab"
        }
    }
}
