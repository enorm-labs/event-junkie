package de.norm.events.scraper

/**
 * Enumeration of known event import sources.
 *
 * Each value corresponds to a venue-specific [EventImporter] implementation
 * that knows how to fetch and parse events from that venue's website.
 * Using an enum instead of a String key provides compile-time safety —
 * every registered source must have a matching importer bean.
 *
 * The entries below describe only the **venue**. How its programme is published, which pages or
 * APIs are read, and every parsing quirk and accepted limitation are documented on that venue's
 * importer and scrapers in `scraper/<venue>/`, next to the code they govern.
 */
enum class EventSource {
    /** ÆDEN Berlin – a techno club on the Spree in Kreuzberg with two floors and a garden. */
    AEDEN,

    /** Admiralspalast Berlin – the Friedrichstraße variety theatre: musicals, comedy, concerts and dance in a 1910 revue house. */
    ADMIRALSPALAST,

    /** AMT Club Berlin – a techno club in the S-Bahn arches near Alexanderplatz. */
    AMT,

    /** Alte Kantine Berlin – a club and concert space in the Kulturbrauerei, mixing live gigs, club nights and themed parties. */
    ALTE_KANTINE,

    /** Arcanoa Berlin – a tiny Kreuzberg bar running since 1988: independent live acts at the weekend, open stages and jam sessions midweek. */
    ARCANOA,

    /** arkaoda Berlin – the Neukölln outpost of the Istanbul bar and club, programming experimental concerts, DJ nights and label showcases. */
    ARKAODA,

    /** Astra Kulturhaus Berlin – a large concert venue on the RAW-Gelände hosting touring rock, pop, indie and electronic acts. */
    ASTRA,

    /** Bar jeder Vernunft Berlin – a 1912 mirror tent (Spiegelzelt) in Wilmersdorf staging cabaret, chanson, musical revues and variety shows. */
    BAR_JEDER_VERNUNFT,

    /**
     * Berghain Berlin – the techno institution in a former power plant near Ostbahnhof.
     *
     * Two source rows share this value: the club's own floors (Berghain, Panorama Bar, Säule,
     * Halle) and the adjacent Kantine am Berghain concert hall, which is served by the same
     * importer off an identical page template.
     */
    BERGHAIN,

    /** Badehaus Berlin – an intimate live-music venue and club on the RAW-Gelände. */
    BADEHAUS,

    /** Bi Nuu Berlin – a live-music club under the U-Bahn viaduct at Schlesisches Tor. */
    BINUU,

    /** Cassiopeia Berlin – a multi-room club and cultural venue on the RAW-Gelände, from reggae and drum & bass to indie and electronic nights. */
    CASSIOPEIA,

    /** Clash Berlin – a punk, ska and rock'n'roll bar and concert spot near Mehringdamm. */
    CLASH,

    /** Club der Visionäre Berlin – the open-air shack and terrace on the Flutgraben, a summer institution for house and minimal techno. */
    CLUB_DER_VISIONAERE,

    /** Club OST Berlin – a techno club on the Rummelsburger Bucht in Alt-Stralau, programming raves and club nights. */
    CLUB_OST,

    /** Colosseum Berlin – a former Prenzlauer Berg cinema on Gleimstraße, run as an event house for readings, book premieres, talks and live podcasts. */
    COLOSSEUM,

    /** Columbia Theater Berlin – a mid-sized concert hall at Tempelhofer Feld, next door to the Columbiahalle. */
    COLUMBIA_THEATER,

    /** Columbiahalle Berlin – the 3,500-capacity hall at Tempelhofer Feld, hosting touring rock, metal, hip hop and pop acts. */
    COLUMBIAHALLE,

    /** Cosmic Comedy Berlin – one of the city's longest-running English-language stand-up clubs. */
    COSMIC_COMEDY,

    /** Crack Bellmer Berlin – a RAW-Gelände microclub and dance bar mixing house and techno nights with drag shows, swing parties and live jazz. */
    CRACK_BELLMER,

    /** Der Weiße Hase Berlin – a techno club in the RAW-Gelände arches on Revaler Straße, running raves and DJ nights. */
    DER_WEISSE_HASE,

    /** Duncker Club Berlin – a long-running goth, wave and indie club in Prenzlauer Berg. */
    DUNCKER,

    /** Eschschloraque Rümschrümp Berlin – a Künstlerclub in a Mitte backyard off Rosenthaler Straße, mixing DJ nights, live sets and variety evenings. */
    ESCHSCHLORAQUE,

    /** Festsaal Kreuzberg Berlin – a concert hall and event space by the Flutgraben, with a wide-ranging concert and club programme. */
    FESTSAAL,

    /** Frannz Club Berlin – a club and concert venue in the Kulturbrauerei, across pop, indie, hip-hop and electronic music. */
    FRANNZ,

    /** Gärten der Welt Berlin – the Marzahn landscape park of international themed gardens, whose Arena stages open-air concerts and park festivals. */
    GAERTEN_DER_WELT,

    /** gART.n Berlin – an open-air garden club on the Rummelsburger Bucht, running daytime techno and house parties at the weekend. */
    GARTN,

    /** Golden Gate Berlin – a small techno club in the S-Bahn arches at Jannowitzbrücke, open Thursday to Saturday with door-only entry. */
    GOLDEN_GATE,

    /** Gretchen Berlin – a club in the vaulted former stables of a 19th-century Kreuzberg barracks, known for bass-driven electronic nights. */
    GRETCHEN,

    /** Havanna Berlin – a multi-floor Latin dance club in Schöneberg running the same resident nights every week, with lessons before the party. */
    HAVANNA,

    /** Heideglühen Berlin – an open-air techno party in a former nursery off Beusselstraße, one long Saturday-into-Sunday session a week. */
    HEIDEGLUEHEN,

    /** Heimathafen Neukölln Berlin – a Saal and Studio venue in a historic ballroom: concerts, theatre, comedy, readings and its own productions. */
    HEIMATHAFEN,

    /** Hole 44 Berlin – a Neukölln concert hall geared towards touring rock, metal, punk and alternative bands. */
    HOLE44,

    /** Humboldthain Club Berlin – a techno club in Wedding with an indoor floor and a large garden, running collective-booked nights. */
    HUMBOLDTHAIN,

    /** Huxleys Neue Welt Berlin – a 1,600-capacity concert hall in a former ballroom on the Hasenheide. */
    HUXLEYS,

    /** Kulturhaus Insel Berlin – the concert house on the Insel der Jugend, a Spree island in Treptower Park, with a hall and a summer garden. */
    INSEL,

    /**
     * Junction Bar Berlin – a basement live-music and DJ bar near Mehringdamm.
     *
     * The venue runs two independent programmes — nightly concerts and DJ sets — which are merged
     * into this one source.
     */
    JUNCTION_BAR,

    /** Kater Berlin – the techno club and garden on the Spree at Holzmarkt, formerly Kater Blau, with several floors and weekend-spanning parties. */
    KATER,

    /** Klunkerkranich Berlin – the rooftop culture garden above the Neukölln Arcaden: a bar, stage and club running a nightly programme. */
    KLUNKERKRANICH,

    /** LARK Berlin – a live-music club in the railway arches on Holzmarktstraße, programming indie, folk, pop and singer-songwriter shows. */
    LARK,

    /** Lido Berlin – a former 1950s cinema on the Spree staging touring indie, rock and electronic acts as well as club nights. */
    LIDO,

    /** Loge Berlin – an intimate, collectively run bar and events space in Friedrichshain. */
    LOGE,

    /** MAAYA Berlin – an Afro-diasporic cultural venue on the RAW-Gelände built around a heated open-air pool, with a gallery, garden and market. */
    MAAYA,

    /** Madame Claude Berlin – a cosy upside-down-themed bar and live venue near Schlesisches Tor, with free-entry concerts and DJ nights. */
    MADAME_CLAUDE,

    /** Matrix Berlin – a large multi-floor mainstream club in the arches at Warschauer Straße, open every night with resident DJs. */
    MATRIX,

    /** Max-Schmeling-Halle Berlin – a multi-purpose arena at Falkplatz, one of the three halls Velomax runs and programmes together. */
    MAX_SCHMELING_HALLE,

    /** MAXXIM Berlin – a party club off the Ku'damm, open nightly with a 90s/2000s, pop and house DJ programme. */
    MAXXIM,

    /** Metropol Berlin – the historic concert hall at Nollendorfplatz, programming touring concerts alongside occasional club parties. */
    METROPOL,

    /** migas Berlin – a Wedding listening bar where booked selectors play records to a seated audience, alongside full-album playback nights. */
    MIGAS,

    /** Mikropol Berlin – a small underground club in Schöneberg spanning rock, metal, electronic and alternative styles. */
    MIKROPOL,

    /** Modus Berlin – a club and concert room on the Ritter Butzke grounds in Kreuzberg, programming touring acts and spoken-word nights. */
    MODUS,

    /** Monarch Berlin – a bar and club overlooking Kottbusser Tor, hosting DJ nights and live sets across electronic, indie and experimental music. */
    MONARCH,

    /** Monster Ronson's Ichiban Karaoke Berlin – the Friedrichshain karaoke bar with a big stage and private boxes, hosted nightly by drag and cabaret MCs. */
    MONSTER_RONSONS,

    /** Morphine Raum Berlin – the Kreuzberg backyard room of the Morphine Records label, staging experimental, improvised and non-Western concerts. */
    MORPHINE,

    /** MS Hoppetosse Berlin – the moored Spree salon boat that is Club der Visionäre's winter location, with a club floor and an upper-deck bar. */
    MS_HOPPETOSSE,

    /** Neue Zukunft Berlin – a cultural venue and club on the Stralau peninsula, blending concerts, club nights and arts events. */
    NEUE_ZUKUNFT,

    /** OHM Berlin – a small bass and techno club inside the Tresor power-station complex. */
    OHM,

    /** Panke Culture Berlin – a club, café and gallery in a Wedding backyard: club nights, live music, markets and exhibitions. */
    PANKE,

    /** Kulturhaus Peter Edel Berlin – the Weißensee Bildungs- und Kulturzentrum, mixing concerts, comedy, theatre, readings and dance teas. */
    PETER_EDEL,

    /** Privatclub Berlin – a club below the Markthalle near Schlesisches Tor, across soul, funk, indie, hip-hop and electronic music. */
    PRIVATCLUB,

    /** Quasimodo Berlin – the city's oldest jazz cellar, off the Ku'damm, programming jazz, blues and soul concerts plus themed DJ nights. */
    QUASIMODO,

    /** Renate (Wilde Renate) Berlin – a warren of a techno club in a derelict Friedrichshain apartment house, with several floors and a summer garden. */
    RENATE,

    /** Ritter Butzke Berlin – a long-running Kreuzberg techno club in a former factory, running several floors a night. */
    RITTER_BUTZKE,

    /** Roadrunner's Paradise Berlin – a courtyard rock'n'roll club in Prenzlauer Berg: rockabilly, psychobilly, punk and garage. */
    ROADRUNNER,

    /** ROSA Berlin – a sex-positive club and cultural venue programming queer party nights. */
    ROSA,

    /** Säälchen Berlin – the concert hall on the Holzmarkt riverside grounds beside the Spree. */
    SAALCHEN,

    /** Schokoladen Mitte Berlin – a collectively run cultural venue with roots in the post-Wende squat scene: intimate concerts, readings and club nights. */
    SCHOKOLADEN,

    /** silent green Berlin – a Wedding cultural quarter in a 1911 crematorium: experimental concerts, exhibitions, film and talks across its halls. */
    SILENT_GREEN,

    /** SO36 Berlin – the Oranienstraße club central to Berlin's punk and new-wave history, today mixing punk, rock, queer parties and club nights. */
    SO36,

    /** Soda Club Berlin – a large five-floor discotheque in the Kulturbrauerei running resident nights, salsa evenings and summer open airs. */
    SODA,

    /** Sonnenraum Berlin – the indoor concert room next to Club der Visionäre, with a resident Monday live-band night. */
    SONNENRAUM,

    /** Supamolly Berlin – a former squat turned collectively run venue near Traveplatz: punk, ska and hardcore concerts, theatre, film and socials. */
    SUPAMOLLY,

    /** Tempodrom Berlin – the tented concert hall by the former Anhalter Bahnhof, hosting concerts, comedy, shows and congresses. */
    TEMPODROM,

    /** Theater im Delphi Berlin – a 1929 silent-cinema building in Weißensee, run as a theatre and concert hall for dance, music theatre and talks. */
    THEATER_IM_DELPHI,

    /** Tresor Berlin – the techno institution in a disused power plant on Köpenicker Straße, with the Tresor vault, the Globus floor and the Aurora Bar. */
    TRESOR,

    /** Uber Arena Berlin – the city's largest indoor arena, on the Spree in Friedrichshain, hosting touring concerts, shows and comedy. */
    UBER_ARENA,

    /** Uber Eats Music Hall Berlin – the arena's smaller neighbour, programming touring bands, comedy and staged shows. */
    UBER_EATS_MUSIC_HALL,

    /** UFO im Velodrom Berlin – the smaller hall configured inside the Velodrom, listed as its own venue on the shared Velomax programme. */
    UFO_IM_VELODROM,

    /** Urania Berlin – the Schöneberg science and culture house, programming lectures, panel discussions and talks across two halls. */
    URANIA,

    /** Urban Spree Berlin – a RAW-Gelände artist space combining gallery, shop and beer garden with a concert hall for post-punk, dark wave and metal. */
    URBAN_SPREE,

    /** Velodrom Berlin – the cycling arena at Landsberger Allee that doubles as one of the city's larger concert venues. */
    VELODROM,

    /** VOID Club Berlin – a two-room techno club in a Lichtenberg backyard, programming drum & bass, hardtechno, trance and bass nights. */
    VOID_CLUB,

    /** Wild at Heart Berlin – a rock'n'roll bar and live club near Schlesisches Tor: rockabilly, punk, garage and surf. */
    WILD_AT_HEART,

    /** Parkbühne Wuhlheide Berlin – a large open-air amphitheatre in the Wuhlheide park, running a seasonal summer concert programme. */
    WUHLHEIDE,

    /** Zenner Berlin – a riverside venue in Treptower Park with a historic Saal, a club, a beer garden and a wine garden. */
    ZENNER,

    /** Zitadelle Spandau – the Renaissance fortress whose courtyard hosts the Citadel Music Festival, an open-air concert series each summer. */
    ZITADELLE;

    /**
     * Prefix for `sourceId` values, derived from the enum name in lowercase.
     *
     * Used by scrapers to build sourceId strings (e.g. `"cassiopeia:some-event-slug"`).
     * This avoids hard-coding the prefix string in scraper classes.
     */
    val sourceIdPrefix: String get() = "${name.lowercase()}:"
}
