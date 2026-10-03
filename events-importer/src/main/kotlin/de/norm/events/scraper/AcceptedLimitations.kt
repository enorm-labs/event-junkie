package de.norm.events.scraper

import de.norm.events.event.EventType
import de.norm.events.genretag.genreFamily
import de.norm.events.genretag.normalizeGenre
import de.norm.events.scraper.abstand.ABSTAND_LIMITATIONS
import de.norm.events.scraper.admiralspalast.ADMIRALSPALAST_LIMITATIONS
import de.norm.events.scraper.aeden.AEDEN_LIMITATIONS
import de.norm.events.scraper.aeg.AEG_LIMITATIONS
import de.norm.events.scraper.altekantine.ALTE_KANTINE_LIMITATIONS
import de.norm.events.scraper.amt.AMT_LIMITATIONS
import de.norm.events.scraper.arcanoa.ARCANOA_LIMITATIONS
import de.norm.events.scraper.artstalker.ART_STALKER_LIMITATIONS
import de.norm.events.scraper.astra.ASTRA_LIMITATIONS
import de.norm.events.scraper.atrane.A_TRANE_LIMITATIONS
import de.norm.events.scraper.badehaus.BADEHAUS_LIMITATIONS
import de.norm.events.scraper.barjedervernunft.BAR_JEDER_VERNUNFT_LIMITATIONS
import de.norm.events.scraper.berghain.BERGHAIN_LIMITATIONS
import de.norm.events.scraper.binuu.BINUU_LIMITATIONS
import de.norm.events.scraper.bogen47.FITZROY_LIMITATIONS
import de.norm.events.scraper.bogen47.LARK_LIMITATIONS
import de.norm.events.scraper.cassiopeia.CASSIOPEIA_LIMITATIONS
import de.norm.events.scraper.clash.CLASH_LIMITATIONS
import de.norm.events.scraper.clubdervisionaere.CLUB_DER_VISIONAERE_LIMITATIONS
import de.norm.events.scraper.clubost.CLUB_OST_LIMITATIONS
import de.norm.events.scraper.colosseum.COLOSSEUM_LIMITATIONS
import de.norm.events.scraper.columbiahalle.COLUMBIAHALLE_LIMITATIONS
import de.norm.events.scraper.columbiatheater.COLUMBIA_THEATER_LIMITATIONS
import de.norm.events.scraper.comedycafe.COMEDY_CAFE_LIMITATIONS
import de.norm.events.scraper.cosmiccomedy.COSMIC_COMEDY_LIMITATIONS
import de.norm.events.scraper.crackbellmer.CRACK_BELLMER_LIMITATIONS
import de.norm.events.scraper.delphi.THEATER_IM_DELPHI_LIMITATIONS
import de.norm.events.scraper.derweissehase.DER_WEISSE_HASE_LIMITATIONS
import de.norm.events.scraper.drugstore.DRUGSTORE_LIMITATIONS
import de.norm.events.scraper.duncker.DUNCKER_LIMITATIONS
import de.norm.events.scraper.erreichbar.ERREICHBAR_LIMITATIONS
import de.norm.events.scraper.eschschloraque.ESCHSCHLORAQUE_LIMITATIONS
import de.norm.events.scraper.festsaal.FESTSAAL_LIMITATIONS
import de.norm.events.scraper.frannz.FRANNZ_LIMITATIONS
import de.norm.events.scraper.gaertenderwelt.GAERTEN_DER_WELT_LIMITATIONS
import de.norm.events.scraper.gartn.GARTN_LIMITATIONS
import de.norm.events.scraper.goldengate.GOLDEN_GATE_LIMITATIONS
import de.norm.events.scraper.gretchen.GRETCHEN_LIMITATIONS
import de.norm.events.scraper.havanna.HAVANNA_LIMITATIONS
import de.norm.events.scraper.heidegluehen.HEIDEGLUEHEN_LIMITATIONS
import de.norm.events.scraper.heimathafen.HEIMATHAFEN_LIMITATIONS
import de.norm.events.scraper.hole44.HOLE44_LIMITATIONS
import de.norm.events.scraper.humboldthain.HUMBOLDTHAIN_LIMITATIONS
import de.norm.events.scraper.huxleys.HUXLEYS_LIMITATIONS
import de.norm.events.scraper.insel.INSEL_LIMITATIONS
import de.norm.events.scraper.junctionbar.JUNCTION_BAR_LIMITATIONS
import de.norm.events.scraper.kater.KATER_LIMITATIONS
import de.norm.events.scraper.klunkerkranich.KLUNKERKRANICH_LIMITATIONS
import de.norm.events.scraper.koepi.KOEPI_LIMITATIONS
import de.norm.events.scraper.lido.LIDO_LIMITATIONS
import de.norm.events.scraper.loge.LOGE_LIMITATIONS
import de.norm.events.scraper.maaya.MAAYA_LIMITATIONS
import de.norm.events.scraper.madameclaude.MADAME_CLAUDE_LIMITATIONS
import de.norm.events.scraper.matrix.MATRIX_LIMITATIONS
import de.norm.events.scraper.maxxim.MAXXIM_LIMITATIONS
import de.norm.events.scraper.mehringhof.MEHRINGHOF_LIMITATIONS
import de.norm.events.scraper.metropol.METROPOL_LIMITATIONS
import de.norm.events.scraper.migas.MIGAS_LIMITATIONS
import de.norm.events.scraper.mikropol.MIKROPOL_LIMITATIONS
import de.norm.events.scraper.modus.MODUS_LIMITATIONS
import de.norm.events.scraper.monarch.MONARCH_LIMITATIONS
import de.norm.events.scraper.monsterronsons.MONSTER_RONSONS_LIMITATIONS
import de.norm.events.scraper.morphine.MORPHINE_LIMITATIONS
import de.norm.events.scraper.neuezukunft.NEUE_ZUKUNFT_LIMITATIONS
import de.norm.events.scraper.ohm.OHM_LIMITATIONS
import de.norm.events.scraper.orania.ORANIA_LIMITATIONS
import de.norm.events.scraper.panke.PANKE_LIMITATIONS
import de.norm.events.scraper.peteredel.PETER_EDEL_LIMITATIONS
import de.norm.events.scraper.privatclub.PRIVATCLUB_LIMITATIONS
import de.norm.events.scraper.punchline.PUNCHLINE_LIMITATIONS
import de.norm.events.scraper.quasimodo.QUASIMODO_LIMITATIONS
import de.norm.events.scraper.renate.RENATE_LIMITATIONS
import de.norm.events.scraper.ritterbutzke.RITTER_BUTZKE_LIMITATIONS
import de.norm.events.scraper.roadrunner.ROADRUNNER_LIMITATIONS
import de.norm.events.scraper.rosa.ROSA_LIMITATIONS
import de.norm.events.scraper.saalchen.SAALCHEN_LIMITATIONS
import de.norm.events.scraper.schokoladen.SCHOKOLADEN_LIMITATIONS
import de.norm.events.scraper.silentgreen.SILENT_GREEN_LIMITATIONS
import de.norm.events.scraper.sisyphos.SISYPHOS_LIMITATIONS
import de.norm.events.scraper.so36.SO36_LIMITATIONS
import de.norm.events.scraper.soda.SODA_LIMITATIONS
import de.norm.events.scraper.supamolly.SUPAMOLLY_LIMITATIONS
import de.norm.events.scraper.tempodrom.TEMPODROM_LIMITATIONS
import de.norm.events.scraper.thewall.THE_WALL_LIMITATIONS
import de.norm.events.scraper.tiffanyclub.TIFFANY_CLUB_LIMITATIONS
import de.norm.events.scraper.tresor.TRESOR_LIMITATIONS
import de.norm.events.scraper.ufafabrik.UFA_FABRIK_LIMITATIONS
import de.norm.events.scraper.urania.URANIA_LIMITATIONS
import de.norm.events.scraper.urbanspree.URBAN_SPREE_LIMITATIONS
import de.norm.events.scraper.velomax.VELOMAX_LIMITATIONS
import de.norm.events.scraper.voidclub.VOID_CLUB_LIMITATIONS
import de.norm.events.scraper.wildatheart.WILD_AT_HEART_LIMITATIONS
import de.norm.events.scraper.wuehlmaeuse.WUEHLMAEUSE_LIMITATIONS
import de.norm.events.scraper.wuhlheide.WUHLHEIDE_LIMITATIONS
import de.norm.events.scraper.zenner.ZENNER_LIMITATIONS
import de.norm.events.scraper.zigzag.ZIG_ZAG_LIMITATIONS
import de.norm.events.scraper.zitadelle.ZITADELLE_LIMITATIONS
import de.norm.events.scraper.zurklappe.ZUR_KLAPPE_LIMITATIONS
import de.norm.events.slug.SlugGenerator

/**
 * Every venue's [VenueLimitations] in one place, and the lookups the data-quality audit runs (#715).
 *
 * The list is assembled by hand, and that is the point: a venue missing from it is a venue the audit
 * has never heard of, so `AcceptedLimitationsTest` asserts that every [EventSource] appears exactly
 * once. Add the import and the entry, or the build names the source you skipped.
 */
object AcceptedLimitations {
    val declarations: List<VenueLimitations> =
        listOf(
            A_TRANE_LIMITATIONS,
            ABSTAND_LIMITATIONS,
            ADMIRALSPALAST_LIMITATIONS,
            AEDEN_LIMITATIONS,
            AEG_LIMITATIONS,
            ALTE_KANTINE_LIMITATIONS,
            AMT_LIMITATIONS,
            ARCANOA_LIMITATIONS,
            ART_STALKER_LIMITATIONS,
            ASTRA_LIMITATIONS,
            BADEHAUS_LIMITATIONS,
            BAR_JEDER_VERNUNFT_LIMITATIONS,
            BERGHAIN_LIMITATIONS,
            BINUU_LIMITATIONS,
            CASSIOPEIA_LIMITATIONS,
            CLASH_LIMITATIONS,
            CLUB_DER_VISIONAERE_LIMITATIONS,
            CLUB_OST_LIMITATIONS,
            COLOSSEUM_LIMITATIONS,
            COLUMBIAHALLE_LIMITATIONS,
            COLUMBIA_THEATER_LIMITATIONS,
            COMEDY_CAFE_LIMITATIONS,
            COSMIC_COMEDY_LIMITATIONS,
            CRACK_BELLMER_LIMITATIONS,
            DER_WEISSE_HASE_LIMITATIONS,
            DRUGSTORE_LIMITATIONS,
            DUNCKER_LIMITATIONS,
            ERREICHBAR_LIMITATIONS,
            ESCHSCHLORAQUE_LIMITATIONS,
            FESTSAAL_LIMITATIONS,
            FITZROY_LIMITATIONS,
            FRANNZ_LIMITATIONS,
            GAERTEN_DER_WELT_LIMITATIONS,
            GARTN_LIMITATIONS,
            GOLDEN_GATE_LIMITATIONS,
            GRETCHEN_LIMITATIONS,
            HAVANNA_LIMITATIONS,
            HEIDEGLUEHEN_LIMITATIONS,
            HEIMATHAFEN_LIMITATIONS,
            HOLE44_LIMITATIONS,
            HUMBOLDTHAIN_LIMITATIONS,
            HUXLEYS_LIMITATIONS,
            INSEL_LIMITATIONS,
            JUNCTION_BAR_LIMITATIONS,
            KATER_LIMITATIONS,
            KLUNKERKRANICH_LIMITATIONS,
            KOEPI_LIMITATIONS,
            LARK_LIMITATIONS,
            LIDO_LIMITATIONS,
            LOGE_LIMITATIONS,
            MAAYA_LIMITATIONS,
            MADAME_CLAUDE_LIMITATIONS,
            MATRIX_LIMITATIONS,
            MAXXIM_LIMITATIONS,
            MEHRINGHOF_LIMITATIONS,
            METROPOL_LIMITATIONS,
            MIGAS_LIMITATIONS,
            MIKROPOL_LIMITATIONS,
            MODUS_LIMITATIONS,
            MONARCH_LIMITATIONS,
            MONSTER_RONSONS_LIMITATIONS,
            MORPHINE_LIMITATIONS,
            NEUE_ZUKUNFT_LIMITATIONS,
            OHM_LIMITATIONS,
            ORANIA_LIMITATIONS,
            PANKE_LIMITATIONS,
            PETER_EDEL_LIMITATIONS,
            PRIVATCLUB_LIMITATIONS,
            PUNCHLINE_LIMITATIONS,
            QUASIMODO_LIMITATIONS,
            RENATE_LIMITATIONS,
            RITTER_BUTZKE_LIMITATIONS,
            ROADRUNNER_LIMITATIONS,
            ROSA_LIMITATIONS,
            SAALCHEN_LIMITATIONS,
            SCHOKOLADEN_LIMITATIONS,
            SILENT_GREEN_LIMITATIONS,
            SISYPHOS_LIMITATIONS,
            SO36_LIMITATIONS,
            SODA_LIMITATIONS,
            SUPAMOLLY_LIMITATIONS,
            TEMPODROM_LIMITATIONS,
            THEATER_IM_DELPHI_LIMITATIONS,
            THE_WALL_LIMITATIONS,
            TIFFANY_CLUB_LIMITATIONS,
            TRESOR_LIMITATIONS,
            UFA_FABRIK_LIMITATIONS,
            URANIA_LIMITATIONS,
            URBAN_SPREE_LIMITATIONS,
            VELOMAX_LIMITATIONS,
            VOID_CLUB_LIMITATIONS,
            WILD_AT_HEART_LIMITATIONS,
            WUEHLMAEUSE_LIMITATIONS,
            WUHLHEIDE_LIMITATIONS,
            ZENNER_LIMITATIONS,
            ZIG_ZAG_LIMITATIONS,
            ZITADELLE_LIMITATIONS,
            ZUR_KLAPPE_LIMITATIONS
        )

    /** What [source] does not publish. Empty for a source with nothing to declare. */
    fun forSource(source: EventSource): List<AcceptedLimitation> = declarations.filter { source in it.sources }.flatMap { it.limitations }

    /**
     * [source]'s [VenueLimitations.houseGenre] for an event of [eventType], or null. A screening,
     * an exhibition, a reading, a quiz or a comedy night at a techno club is not a techno night, so it gets none.
     */
    fun houseGenre(
        source: EventSource,
        eventType: String?
    ): String? =
        declarations
            .firstOrNull { source in it.sources }
            ?.houseGenre
            ?.takeIf { eventType != null && eventType !in NON_MUSIC_TYPES }

    /** [events] with [source]'s house genre filled in where an event names no genre of its own. */
    fun withHouseGenre(
        source: EventSource,
        events: List<ScrapedEvent>
    ): List<ScrapedEvent> = events.map { event -> event.genre?.let { event } ?: houseGenre(source, event.eventType)?.let { event.copy(genre = it) } ?: event }

    /**
     * The genre families each source's house genre maps to, keyed by [EventSource] name (`event_source.source_type`).
     * `VenueProgrammeStore` keeps these families without the event thresholds: the house genre is already a
     * judgement about the whole programme, and a short season can leave too few tagged events to show it (#2389).
     */
    val houseFamilies: Map<String, List<String>> by lazy {
        declarations
            .flatMap { declaration ->
                val families = normalizeGenre(declaration.houseGenre).mapNotNull { genreFamily(SlugGenerator.slugify(it))?.slug }.distinct()
                if (families.isEmpty()) emptyList() else declaration.sources.map { it.name to families }
            }.toMap()
    }

    private val NON_MUSIC_TYPES = setOf(EventType.SCREENING, EventType.EXHIBITION, EventType.READING, EventType.QUIZ, EventType.COMEDY).map { it.name }

    /** Whether [source] declares [aspect] — the lookup the audit runs per finding. */
    fun declares(
        source: EventSource,
        aspect: LimitedAspect
    ): Boolean = forSource(source).any { it.aspect == aspect }
}
