# Accepted limitations

<!-- Generated from AcceptedLimitations.kt by AcceptedLimitationsTest. Do not edit by hand. -->

What each venue's source does not publish, declared next to its parser (#715). A data-quality finding matching a row here is
**known and accepted**, not a defect — see [`/data-quality-audit`](../../.github/prompts/data-quality-audit.prompt.md).

A declaration says the source is silent, not that the column is always null: where the parser derives a value anyway, the reason
says so.

| Source                 | Aspect             | Why the source is silent                                                                                                                         | Issue |
| ---------------------- | ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------ | ----- |
| `A_TRANE`              | `DOORS_TIME`       | the site prints one start time per night                                                                                                         | —     |
| `A_TRANE`              | `END_TIME`         | the end time is a calendar default, 23:50 on most nights                                                                                         | —     |
| `A_TRANE`              | `PRICE_BOX_OFFICE` | the site sells one price online and holds reserved seats at that price                                                                           | —     |
| `A_TRANE`              | `TICKET_URL`       | tickets sell in a shop inside the venue's own event card                                                                                         | —     |
| `A_TRANE`              | `PROMOTERS`        | the club presents every night itself                                                                                                             | —     |
| `A_TRANE`              | `SOLD_OUT`         | the sold-out line is printed on every night, booked out or not                                                                                   | —     |
| `ABSTAND`              | `ARTISTS`          | the bands are named only in free prose of no fixed shape                                                                                         | —     |
| `ABSTAND`              | `IMAGE`            | radar serves its poster files behind an anti-bot wall, which we do not pass                                                                      | —     |
| `ABSTAND`              | `TICKET_URL`       | the bar sells no tickets online                                                                                                                  | —     |
| `ABSTAND`              | `DOORS_TIME`       | radar gives one time per night                                                                                                                   | —     |
| `AEDEN`                | `PRICE`            | the month page carries no prices                                                                                                                 | —     |
| `AEDEN`                | `PER_EVENT_PAGE`   | the month page links no page per night                                                                                                           | —     |
| `ADMIRALSPALAST`       | `GENRE`            | the house classifies by staging format (Konzert, Lesung) and names no musical style anywhere                                                     | —     |
| `ADMIRALSPALAST`       | `PRICE`            | the house prints no figure; tickets are sold through an Eventim link                                                                             | —     |
| `AMT`                  | `ARTISTS`          | the DJ line separates names with spaces and nothing else, so it cannot be split apart reliably                                                   | —     |
| `ALTE_KANTINE`         | `PRICE`            | some nights leave the Eintritt field empty ("Eintritt: €"), so no figure is published                                                            | —     |
| `ARCANOA`              | `PER_EVENT_PAGE`   | the whole programme is one hand-coded page                                                                                                       | —     |
| `ARCANOA`              | `PRICE`            | a night is one line — a date, the act and a genre string — and the page prints no figure anywhere                                                | —     |
| `ARCANOA`              | `TICKET_URL`       | entry is paid at the door, and the page's only links point at partner sites                                                                      | —     |
| `ARCANOA`              | `GENRE`            | the style tail runs genre words together with support acts and notes, so only the words the vocabulary knows become the genre                    | —     |
| `ARCANOA`              | `IMAGE`            | the page carries no image element at all                                                                                                         | —     |
| `ARCANOA`              | `DESCRIPTION`      | the one line per night is the whole entry, with no blurb after it                                                                                | —     |
| `ARCANOA`              | `ARTISTS`          | a night is one line with no separator between the act and the night's name, so a billing like `Arcana A Night Of Flow` cannot be split           | —     |
| `ART_STALKER`          | `PAGINATION`       | robots.txt disallows the shop's paged listing, so only the first 25 events are read                                                              | —     |
| `ART_STALKER`          | `GENRE`            | the style is only a free-text tagline after the act's name, so only the genre words in it become the genre                                       | —     |
| `ART_STALKER`          | `PROMOTERS`        | the venue presents every night itself                                                                                                            | —     |
| `ASTRA`                | `GENRE`            | the event page carries no genre field                                                                                                            | —     |
| `BAR_JEDER_VERNUNFT`   | `DOORS_TIME`       | the calendar and the show pages state one Beginn time and never an Einlass                                                                       | —     |
| `BERGHAIN`             | `GENRE`            | the Kantine and Halle pages name only the room and have no genre field, and the concerts there vary                                              | —     |
| `BADEHAUS`             | `ARTISTS`          | the venue publishes no roster; for a concert the title is taken as the act and a Support: subtitle as the rest                                   | —     |
| `BADEHAUS`             | `PRICE`            | the venue prints no figure, and where it names money at all it is a donation range the model has no field for, kept verbatim as the note         | —     |
| `BINUU`                | `EVENT_TYPE`       | the SvelteKit payload carries no category field, and neither does anywhere else on the site                                                      | —     |
| `BINUU`                | `PRICE`            | the payload carries no price field and the pages print no figure; tickets are sold through outside shops                                         | —     |
| `BINUU`                | `GENRE`            | the payload and JSON-LD carry no genre; style appears only in the description                                                                    | —     |
| `CASSIOPEIA`           | `PRICE`            | the venue prints no figure on its listing or its event pages                                                                                     | —     |
| `CLASH`                | `PER_EVENT_PAGE`   | the `event` post type is not exposed over the WordPress REST API and the numeric permalinks 404                                                  | —     |
| `CLASH`                | `GENRE`            | only an occasional DJ night names its styles in the prose; every other music night takes the house's Punk                                        | —     |
| `CLASH`                | `PROMOTERS`        | the homepage listing is the whole source and names no promoter                                                                                   | —     |
| `CLASH`                | `EVENT_TYPE`       | the site has no category field; the type is inferred from the title, defaulting to a concert                                                     | —     |
| `CLUB_DER_VISIONAERE`  | `START_TIME`       | the listing prints one only where an act line carries a slot time; the homepage's NEXT box prints the rest, for the ten nights it shows          | —     |
| `CLUB_DER_VISIONAERE`  | `EVENT_TYPE`       | the venue publishes no category of its own; every listing is a club night                                                                        | —     |
| `CLUB_DER_VISIONAERE`  | `PER_EVENT_PAGE`   | the programme page is the source for every night                                                                                                 | —     |
| `CLUB_DER_VISIONAERE`  | `PRICE`            | the programme lists times and the line-up only, never an admission price                                                                         | —     |
| `CLUB_DER_VISIONAERE`  | `IMAGE`            | the programme is text only; the site's only images are its logos                                                                                 | —     |
| `CLUB_OST`             | `DESCRIPTION`      | the venue programmes through Resident Advisor and leaves the CMS description empty on every event                                                | —     |
| `CLUB_OST`             | `EVENT_TYPE`       | the listing carries no category; every card is a flyer, a title, a start time and a ticket link                                                  | —     |
| `CLUB_OST`             | `GENRE`            | the listing carries no genre; every night takes the club's Techno default                                                                        | —     |
| `CLUB_OST`             | `PRICE`            | the listing carries no price; tickets are sold on Resident Advisor                                                                               | —     |
| `CLUB_OST`             | `ARTISTS`          | the listing carries no lineup, though the CMS holds an empty div where one would go                                                              | —     |
| `CLUB_OST`             | `DOORS_TIME`       | the listing carries one time per night and no doors time                                                                                         | —     |
| `COLOSSEUM`            | `EVENT_TYPE`       | `categories` is empty on every event, so the type is inferred from the title and subtitle                                                        | —     |
| `COLOSSEUM`            | `DOORS_TIME`       | an event whose own page states no Einlass line keeps the listing's single time as the start, and gets no doors                                   | —     |
| `COLOSSEUM`            | `GENRE`            | the house names no musical style anywhere                                                                                                        | —     |
| `COLOSSEUM`            | `ARTISTS`          | no support-act convention exists in the subtitles, and a title is as often an event name as a performer's                                        | —     |
| `COLOSSEUM`            | `DESCRIPTION`      | each event is cloned from an old one and its about text is never rewritten, so it is another act's biography, not the event's                    | —     |
| `COLUMBIA_THEATER`     | `PRICE`            | the venue prints no figure; tickets are sold through an Eventim link                                                                             | —     |
| `COLUMBIA_THEATER`     | `GENRE`            | the venue publishes no genre or category; style appears only in the description                                                                  | —     |
| `COLUMBIAHALLE`        | `PER_EVENT_PAGE`   | the venue's own iCal export keys the event on the same Contao id and points back at the listing anchor                                           | —     |
| `COLUMBIAHALLE`        | `GENRE`            | the listing names support, promoter, times and prices, and no musical style                                                                      | —     |
| `COMEDY_CAFE`          | `ARTISTS`          | an improv night bills a team or a format, and its performers appear only in prose                                                                | —     |
| `COMEDY_CAFE`          | `TICKET_URL`       | the API carries no ticket link; the shop is a widget on each event page                                                                          | —     |
| `COMEDY_CAFE`          | `PRICE_BOX_OFFICE` | the bar sells leftover tickets at a surcharge the site states once for every show                                                                | —     |
| `COMEDY_CAFE`          | `PROMOTERS`        | the club organises every show itself                                                                                                             | —     |
| `COSMIC_COMEDY`        | `PRICE`            | `cost` and `cost_details` are empty on every event                                                                                               | —     |
| `CRACK_BELLMER`        | `EVENT_TYPE`       | the venue emits no category at all; the type is read from the title and then the genre line                                                      | —     |
| `CRACK_BELLMER`        | `DOORS_TIME`       | the venue publishes no doors time                                                                                                                | —     |
| `CRACK_BELLMER`        | `PRICE`            | the venue publishes no prices                                                                                                                    | —     |
| `CRACK_BELLMER`        | `TICKET_URL`       | the venue links no ticket shop                                                                                                                   | —     |
| `DER_WEISSE_HASE`      | `PRICE`            | the club publishes no price figure; some nights carry a conditional free-entry line, stored as the price note                                    | —     |
| `DER_WEISSE_HASE`      | `GENRE`            | the club publishes no genre; every night takes the club's Techno default                                                                         | —     |
| `DER_WEISSE_HASE`      | `DOORS_TIME`       | the club publishes no doors time                                                                                                                 | —     |
| `DER_WEISSE_HASE`      | `EVENT_TYPE`       | the club states no category anywhere and programmes nothing but DJ nights, so the type is fixed rather than inferred                             | —     |
| `DER_WEISSE_HASE`      | `PER_EVENT_PAGE`   | the club sells through Resident Advisor and the listing links off-site                                                                           | —     |
| `DER_WEISSE_HASE`      | `CANCELLATION`     | a cancelled night is taken off the page rather than labelled                                                                                     | —     |
| `DRUGSTORE`            | `ARTISTS`          | the bands are named only in free prose of no fixed shape                                                                                         | —     |
| `DRUGSTORE`            | `GENRE`            | radar files the nights under concert or party, and the style is in prose                                                                         | —     |
| `DRUGSTORE`            | `IMAGE`            | radar serves its poster files behind an anti-bot wall, which we do not pass                                                                      | —     |
| `DRUGSTORE`            | `TICKET_URL`       | the youth centre sells no tickets online                                                                                                         | —     |
| `DRUGSTORE`            | `DOORS_TIME`       | radar gives one time per night, and the prose names the doors where it differs                                                                   | —     |
| `DUNCKER`              | `PER_EVENT_PAGE`   | the whole programme is one hand-coded page                                                                                                       | —     |
| `DUNCKER`              | `DOORS_TIME`       | the time cell is the night's opening hours, stored as start and end, and no doors time is printed                                                | —     |
| `DUNCKER`              | `PRICE`            | the listing gives a night, a genre string and an hour range, never a figure                                                                      | —     |
| `ERREICHBAR`           | `ARTISTS`          | the punk bar night plays records and bills no one                                                                                                | —     |
| `ERREICHBAR`           | `IMAGE`            | the bar posts no image to radar                                                                                                                  | —     |
| `ERREICHBAR`           | `TICKET_URL`       | the bar sells no tickets                                                                                                                         | —     |
| `ERREICHBAR`           | `DOORS_TIME`       | radar gives one time per night                                                                                                                   | —     |
| `ERREICHBAR`           | `PRICE`            | the bar names no entry price                                                                                                                     | —     |
| `ESCHSCHLORAQUE`       | `PRICE`            | entry is settled at the door and the venue names no figure                                                                                       | —     |
| `ESCHSCHLORAQUE`       | `TICKET_URL`       | the venue runs no ticket shop                                                                                                                    | —     |
| `ESCHSCHLORAQUE`       | `SOLD_OUT`         | the venue flags nothing sold out                                                                                                                 | —     |
| `ESCHSCHLORAQUE`       | `CANCELLATION`     | the venue flags nothing cancelled, so every event stays scheduled                                                                                | —     |
| `ESCHSCHLORAQUE`       | `EVENT_TYPE`       | the programme mixes DJ nights, live sets, bingo and theatre with no kind field anywhere                                                          | —     |
| `ESCHSCHLORAQUE`       | `DOORS_TIME`       | the date field carries one ab-HH-Uhr time; a doors time exists only where the prose labels a pair, which is read                                 | —     |
| `FESTSAAL`             | `EVENT_TYPE`       | the API exposes no category field; its `genre` node is a musical genre, and only an event kind filed there (Festival) types the night            | —     |
| `FESTSAAL`             | `PRICE`            | the API names no figure; tickets are sold through a vvk.link shop                                                                                | —     |
| `FITZROY`              | `START_TIME`       | the venue renders its one time as Doors and publishes no separate start time                                                                     | —     |
| `FITZROY`              | `PRICE`            | the venue prints no figure; tickets link to Resident Advisor                                                                                     | —     |
| `FITZROY`              | `GENRE`            | the venue leaves its genre fields empty on every event; style appears only in the description                                                    | —     |
| `FITZROY`              | `EVENT_TYPE`       | the venue types every event Party, a live show included                                                                                          | —     |
| `FITZROY`              | `PROMOTERS`        | the organiser field is empty on every event; a collective is named only in the title                                                             | —     |
| `FRANNZ`               | `PER_EVENT_PAGE`   | nothing on the site links a `/events/<slug>/` page                                                                                               | —     |
| `FRANNZ`               | `PRICE`            | most nights name the ticket seller instead of a figure; only the venue's own party nights carry a structured Abendkasse item, which is read      | —     |
| `FRANNZ`               | `GENRE`            | the venue tags each event only with a type (Konzert, Party, Lesung), never a genre                                                               | —     |
| `GAERTEN_DER_WELT`     | `GENRE`            | the park's only classification is the format category the event type is already built from; it names no musical style, not even in prose         | —     |
| `GARTN`                | `PRICE`            | the venue publishes no prices                                                                                                                    | —     |
| `GARTN`                | `GENRE`            | the venue publishes no genre; every night takes the club's Techno default                                                                        | —     |
| `GARTN`                | `IMAGE`            | the venue publishes no per-event image                                                                                                           | —     |
| `GARTN`                | `DESCRIPTION`      | the venue publishes no per-event text                                                                                                            | —     |
| `GARTN`                | `PER_EVENT_PAGE`   | the Carrd page emits no per-event URL, and removes an event once it has passed                                                                   | —     |
| `GARTN`                | `DOORS_TIME`       | the venue states one time per night and no separate doors time                                                                                   | —     |
| `GARTN`                | `EVENT_TYPE`       | the venue states no category; every night here is a DJ party                                                                                     | —     |
| `GOLDEN_GATE`          | `EVENT_TYPE`       | the club emits no category at all and programmes nothing but DJ nights, so the type is fixed rather than inferred                                | —     |
| `GOLDEN_GATE`          | `PER_EVENT_PAGE`   | there is no custom `event` post type in the WordPress REST API and no structured data; the single rendered page is the source                    | —     |
| `GOLDEN_GATE`          | `PRICE`            | the club sells at the door and prints no figure on its programme                                                                                 | —     |
| `GRETCHEN`             | `DESCRIPTION`      | the text is only on each night's detail page; the import reads the homepage and the ticket popups, not those pages                               | —     |
| `GRETCHEN`             | `PRICE`            | club nights print no price, and a cancelled show loses its line; presale concerts list theirs                                                    | —     |
| `HAVANNA`              | `EVENT_DATE`       | the venue publishes no dated programme: its three resident nights carry only a weekday, so occurrences are generated from the weekly schedule    | —     |
| `HEIDEGLUEHEN`         | `PER_EVENT_PAGE`   | the site has no per-event pages and no archive; one rich-text block lists the month's Saturdays and is replaced wholesale                        | —     |
| `HEIDEGLUEHEN`         | `PRICE`            | the club sells at the door and prints no figure on its programme                                                                                 | —     |
| `HOLE44`               | `PRICE`            | the venue prints no figure; tickets are sold through an Eventim button                                                                           | —     |
| `HUMBOLDTHAIN`         | `PER_EVENT_PAGE`   | the calendar widget exposes no per-event URLs                                                                                                    | —     |
| `HUMBOLDTHAIN`         | `PRICE`            | prices appear only in the prose, in too many spellings to parse                                                                                  | —     |
| `HUMBOLDTHAIN`         | `SOLD_OUT`         | nothing in the payload marks a night sold out                                                                                                    | —     |
| `HUMBOLDTHAIN`         | `CANCELLATION`     | nothing in the payload marks a night cancelled or moved                                                                                          | —     |
| `HUXLEYS`              | `PRICE`            | most shows sell through Eventim and print no price at all — one of eleven sampled pages carried one                                              | —     |
| `INSEL`                | `PRICE`            | the venue names no prices anywhere; only an Eintritt-frei note on the free Sunday matinées                                                       | —     |
| `INSEL`                | `GENRE`            | the venue publishes no genre                                                                                                                     | —     |
| `INSEL`                | `START_TIME`       | a night billed with a doors time only, as `Doors 19:00`, names no start, so it stores the doors time and no start                                | —     |
| `INSEL`                | `CANCELLATION`     | a dropped show is removed from the CMS rather than flagged                                                                                       | —     |
| `INSEL`                | `PER_EVENT_PAGE`   | every event points at the programme page and takes its identity from its date plus its title                                                     | —     |
| `INSEL`                | `EVENT_TYPE`       | the venue publishes no category, so a title that is an event name rather than an act is minted as a concert                                      | —     |
| `INSEL`                | `ARTISTS`          | a support act billed without a colon reads as prose, so only a colon or a line-leading support marker is followed                                | —     |
| `JUNCTION_BAR`         | `PER_EVENT_PAGE`   | the programme is one page per month; a live night's only page of its own is its ticket-shop entry, kept as the ticket link                       | —     |
| `KATER`                | `EVENT_TYPE`       | the club has no category field; only an unambiguous title keyword overrides the party default                                                    | —     |
| `KATER`                | `PER_EVENT_PAGE`   | the per-event page carries nothing the homepage listing lacks                                                                                    | —     |
| `KATER`                | `PRICE`            | the club sells at the door and prints no figure; a night is flagged free only when its title or blurb says so                                    | —     |
| `KATER`                | `IMAGE`            | the venue prints a flyer on almost no night; the programme is text with a Resident Advisor link                                                  | —     |
| `KLUNKERKRANICH`       | `EVENT_TYPE`       | the venue publishes no category, so every night is stored as a party — which mislabels the occasional concert                                    | —     |
| `KLUNKERKRANICH`       | `DOORS_TIME`       | the venue states when the roof opens, not when a show starts                                                                                     | —     |
| `KLUNKERKRANICH`       | `GENRE`            | nothing on the site names a genre; every night takes the house's House                                                                           | —     |
| `KLUNKERKRANICH`       | `TICKET_URL`       | entry is paid at the door; an occasional advance-RSVP link is written into a blurb rather than published as a field                              | —     |
| `KLUNKERKRANICH`       | `SOLD_OUT`         | nothing flags a night sold out                                                                                                                   | —     |
| `KLUNKERKRANICH`       | `CANCELLATION`     | nothing flags a night cancelled                                                                                                                  | —     |
| `KLUNKERKRANICH`       | `ARTISTS`          | a billing joined by `&` is split into two acts, the venue billing a duo and a pair of separate acts the same way                                 | —     |
| `KLUNKERKRANICH`       | `PRICE`            | entry is a time-banded range (`5-9€`) the model has no field for, so the wording is kept verbatim as the note and only a lone figure is stored   | —     |
| `KOEPI`                | `IMAGE`            | radar serves its poster files behind an anti-bot wall, which we do not pass                                                                      | —     |
| `KOEPI`                | `TICKET_URL`       | the venue sells no tickets online                                                                                                                | —     |
| `KOEPI`                | `PRICE`            | radar carries no price for its concerts                                                                                                          | —     |
| `KOEPI`                | `DOORS_TIME`       | radar gives one time per night                                                                                                                   | —     |
| `LARK`                 | `START_TIME`       | the venue renders its one time as Doors and publishes no separate start time                                                                     | —     |
| `LARK`                 | `PRICE`            | the venue prints no figure; tickets link to hum-berlin.com                                                                                       | —     |
| `LARK`                 | `GENRE`            | the venue leaves its genre fields empty on every event; style appears only in the description                                                    | —     |
| `LIDO`                 | `GENRE`            | the venue labels each event only Concert or Party; style is described only in the prose                                                          | —     |
| `LOGE`                 | `EVENT_TYPE`       | the venue has no category field; a live-music venue, so an unmarked title defaults to a concert                                                  | —     |
| `LOGE`                 | `ARTISTS`          | a title without a + separator can be a band or an event name, so no act is derived from one                                                      | —     |
| `MAAYA`                | `PER_EVENT_PAGE`   | the programme is one hand-built section of the WordPress home page                                                                               | —     |
| `MAAYA`                | `DESCRIPTION`      | the programme is one hand-built section of the home page and carries no detail text                                                              | —     |
| `MAAYA`                | `PRICE`            | the venue publishes an entry note in words and no numeric price                                                                                  | —     |
| `MAAYA`                | `ARTISTS`          | there is no lineup field, and the titles are series and party names rather than acts                                                             | —     |
| `MAAYA`                | `DOORS_TIME`       | the venue publishes no doors time                                                                                                                | —     |
| `MAAYA`                | `EVENT_TYPE`       | the programme carries a name, a date and a time and no category; the type comes from a title keyword, else OTHER                                 | —     |
| `MAAYA`                | `GENRE`            | the venue publishes no genre; every music night takes the house's Afrobeats, Latin                                                               | —     |
| `MAX_SCHMELING_HALLE`  | `PRICE`            | the listing and the event pages print no figure; tickets are sold through outside shops                                                          | —     |
| `MAXXIM`               | `EVENT_TYPE`       | the club publishes no categories; every night is a DJ dance party                                                                                | —     |
| `MEHRINGHOF`           | `DOORS_TIME`       | the programme states one time per performance                                                                                                    | —     |
| `MEHRINGHOF`           | `END_TIME`         | the ticket shop gives every performance the same 6 a.m. end                                                                                      | —     |
| `MEHRINGHOF`           | `EVENT_TYPE`       | the programme names no format; Kabarett, comedy, readings and song evenings share one table                                                      | —     |
| `MEHRINGHOF`           | `GENRE`            | the programme names no genre                                                                                                                     | —     |
| `MEHRINGHOF`           | `PROMOTERS`        | the theatre presents every performance itself                                                                                                    | —     |
| `METROPOL`             | `PRICE`            | the venue prints no figure; tickets are sold through an Eventim link                                                                             | —     |
| `METROPOL`             | `GENRE`            | the detail page's TAGS field is empty, and its one category names the event type (Konzert, Party), not a style                                   | —     |
| `MIGAS`                | `PRICE`            | entry arrangements are not stated on the site at all                                                                                             | —     |
| `MIGAS`                | `TICKET_URL`       | entry arrangements are not stated on the site at all                                                                                             | —     |
| `MIGAS`                | `DOORS_TIME`       | the listing carries no door time                                                                                                                 | —     |
| `MIGAS`                | `SOLD_OUT`         | the listing carries no sold-out badge                                                                                                            | —     |
| `MIGAS`                | `CANCELLATION`     | the listing carries no cancellation badge                                                                                                        | —     |
| `MIKROPOL`             | `GENRE`            | the site names no musical style; its only category is Konzert or Club                                                                            | —     |
| `MIKROPOL`             | `PRICE`            | the venue prints no figure; tickets are sold through a Dice link                                                                                 | —     |
| `MODUS`                | `PRICE`            | the event page prints no figure and sends buyers to the ticket shop                                                                              | —     |
| `MONARCH`              | `PER_EVENT_PAGE`   | the site is hand-coded PHP with no per-event URLs                                                                                                | —     |
| `MONARCH`              | `PRICE`            | the page prints a Ticket Vorverkauf link, never an amount                                                                                        | —     |
| `MONARCH`              | `GENRE`            | the page lists only date, title and ticket link; a "(KONZERT)" marker is its only classification                                                 | —     |
| `MONARCH`              | `IMAGE`            | the venue prints no image per night, only one monthly programme poster                                                                           | —     |
| `MONSTER_RONSONS`      | `DOORS_TIME`       | the venue states one time per night, which is taken as the start                                                                                 | —     |
| `MONSTER_RONSONS`      | `PRICE`            | the price lives in prose and is often a time-banded tariff, which the model has no field for                                                     | —     |
| `MONSTER_RONSONS`      | `GENRE`            | the venue publishes no genre; every night takes the house's Karaoke                                                                              | —     |
| `MONSTER_RONSONS`      | `ARTISTS`          | the venue bills no lineup beyond the host named in the title                                                                                     | —     |
| `MORPHINE`             | `GENRE`            | the venue publishes no genre                                                                                                                     | —     |
| `MORPHINE`             | `SOLD_OUT`         | the venue flags nothing sold out                                                                                                                 | —     |
| `MORPHINE`             | `CANCELLATION`     | a dropped night is removed from the listing rather than flagged                                                                                  | —     |
| `MORPHINE`             | `TICKET_URL`       | the advance-sale button posts to PayPal rather than linking anywhere                                                                             | —     |
| `MORPHINE`             | `PRICE`            | nearly every night is priced as a sliding scale or donation range, which the model has no field for, so the wording is kept verbatim as the note | —     |
| `MS_HOPPETOSSE`        | `START_TIME`       | the listing prints one only where an act line carries a slot time; the homepage's NEXT box prints the rest, for the ten nights it shows          | —     |
| `MS_HOPPETOSSE`        | `EVENT_TYPE`       | the venue publishes no category of its own; every listing is a club night                                                                        | —     |
| `MS_HOPPETOSSE`        | `PER_EVENT_PAGE`   | the programme page is the source for every night                                                                                                 | —     |
| `MS_HOPPETOSSE`        | `PRICE`            | the programme lists times and the line-up only, never an admission price                                                                         | —     |
| `MS_HOPPETOSSE`        | `IMAGE`            | the programme is text only; the site's only images are its logos                                                                                 | —     |
| `NEUE_ZUKUNFT`         | `PER_EVENT_PAGE`   | the calendar widget exposes no per-event URLs                                                                                                    | —     |
| `NEUE_ZUKUNFT`         | `PRICE`            | the calendar widget prints no figure; each show links out to an external ticket shop                                                             | —     |
| `NEUE_ZUKUNFT`         | `GENRE`            | the calendar's categories are rooms (Saal, Garage, Jazzbar) and its tags are blank; every music night takes the house's Psychedelic              | —     |
| `NEUE_ZUKUNFT`         | `IMAGE`            | the calendar widget sets no cover image on upcoming shows                                                                                        | —     |
| `OHM`                  | `PER_EVENT_PAGE`   | the venue's whole programme is one page                                                                                                          | —     |
| `OHM`                  | `PRICE`            | the programme page carries no price                                                                                                              | —     |
| `OHM`                  | `TICKET_URL`       | the programme page links no ticket shop                                                                                                          | —     |
| `OHM`                  | `EVENT_TYPE`       | the venue publishes no categories; every night is a DJ programme                                                                                 | —     |
| `ORANIA`               | `DOORS_TIME`       | the venue states one time per concert, which is taken as the start                                                                               | —     |
| `ORANIA`               | `END_TIME`         | every concert is billed open end                                                                                                                 | —     |
| `ORANIA`               | `PRICE`            | entry is free to every concert                                                                                                                   | —     |
| `ORANIA`               | `TICKET_URL`       | entry is free and the venue sells no tickets                                                                                                     | —     |
| `ORANIA`               | `GENRE`            | the venue tags a series such as piano or grooves, never a genre; every concert takes the house's jazz                                            | —     |
| `PANKE`                | `PER_EVENT_PAGE`   | the venue expands each event's full text inline and publishes no page per event                                                                  | —     |
| `PANKE`                | `EVENT_TYPE`       | no category is published and titles name a series; a format word or live show in the title, or a Resident Advisor lineup, types an event         | —     |
| `PANKE`                | `DOORS_TIME`       | only an event whose body prints a `Doors … · Concert …` line states two clocks; for the rest the venue publishes one and calls it the start      | —     |
| `PETER_EDEL`           | `EVENT_TYPE`       | the venue publishes no event category at all, across a programme spanning concerts, comedy, readings and dance teas                              | —     |
| `PETER_EDEL`           | `ARTISTS`          | without a category nothing confirms that a title is a performer rather than a format, so an act is taken only when a support act is billed       | —     |
| `PETER_EDEL`           | `GENRE`            | the venue publishes no genre                                                                                                                     | —     |
| `PETER_EDEL`           | `PER_EVENT_PAGE`   | the title links straight to the ticket shop                                                                                                      | —     |
| `PRIVATCLUB`           | `PRICE`            | about half the nights print a genre line and a start time but no price                                                                           | —     |
| `RENATE`               | `EVENT_TYPE`       | the club states no category; its `.cat-btn` names the spaces in use, not a kind of event                                                         | —     |
| `RENATE`               | `PER_EVENT_PAGE`   | every night points at the programme page                                                                                                         | —     |
| `RENATE`               | `START_TIME`       | the club prints a time for its GARDEN and GREEN rooms inside the floor heading and none for a CLUB-only night                                    | —     |
| `RENATE`               | `PRICE`            | the club sells through Resident Advisor and prints no figure; a night is flagged free only when its blurb says so                                | —     |
| `RENATE`               | `IMAGE`            | the programme rows carry no flyer, only icons and a Resident Advisor ticket link                                                                 | —     |
| `RITTER_BUTZKE`        | `EVENT_TYPE`       | the club publishes no categories; every night is a DJ programme                                                                                  | —     |
| `RITTER_BUTZKE`        | `PRICE`            | the club sells through a third party and prints no figure; a night is flagged free only when its title says so                                   | —     |
| `ROADRUNNER`           | `PER_EVENT_PAGE`   | the whole programme lives on one hand-coded page                                                                                                 | —     |
| `ROADRUNNER`           | `EVENT_TYPE`       | the retro programme carries no category field; a live-music venue, so an unmarked title defaults to a concert                                    | —     |
| `ROADRUNNER`           | `ARTISTS`          | a night with no line-up label names only its title, which is as often a band battle as an act with its tour run on                               | —     |
| `ROSA`                 | `SUBTITLE`         | the site states one title per night and no second line                                                                                           | —     |
| `ROSA`                 | `DOORS_TIME`       | the site publishes an opening range, not a doors time                                                                                            | —     |
| `ROSA`                 | `GENRE`            | the venue names no musical style anywhere; every night takes the club's Techno default                                                           | —     |
| `ROSA`                 | `PRICE`            | the venue sells through Resident Advisor and prints no price                                                                                     | —     |
| `ROSA`                 | `ARTISTS`          | the site names the party series, never who plays it                                                                                              | —     |
| `ROSA`                 | `PROMOTERS`        | the site credits no promoter beside the party name                                                                                               | —     |
| `ROSA`                 | `SOLD_OUT`         | the site links to the ticket shop rather than stating a status                                                                                   | —     |
| `ROSA`                 | `CANCELLATION`     | the site drops a cancelled night instead of marking it                                                                                           | —     |
| `ROSA`                 | `PER_EVENT_PAGE`   | the whole programme is one page with an anchor per night                                                                                         | —     |
| `SAALCHEN`             | `GENRE`            | the venue publishes no genre field of its own                                                                                                    | —     |
| `SCHOKOLADEN`          | `PRICE`            | the venue prints doors, show time and a ticket link, never a figure                                                                              | —     |
| `SILENT_GREEN`         | `PRICE`            | the venue names no prices anywhere — an event either links out to a ticket shop or says nothing                                                  | —     |
| `SILENT_GREEN`         | `GENRE`            | the venue publishes no genre                                                                                                                     | —     |
| `SILENT_GREEN`         | `START_TIME`       | a multi-day festival entry prints its date span only and names no time                                                                           | —     |
| `SILENT_GREEN`         | `PROMOTERS`        | the venue credits itself as the organiser on its own nights, so the stored promoter is the venue                                                 | —     |
| `SISYPHOS`             | `EVENT_TYPE`       | the calendar files nights with no category; each is stored as a party, the market and the open day included                                      | —     |
| `SISYPHOS`             | `DOORS_TIME`       | the calendar gives the opening, stored as the start, and no separate doors time                                                                  | —     |
| `SISYPHOS`             | `START_TIME`       | a night the calendar does not list yet comes from the shop alone, whose product names a day and never a time                                     | —     |
| `SISYPHOS`             | `ARTISTS`          | the calendar and the shop name no DJ; the line-up comes from sisy.fan, which posts a weekend on Friday night or Saturday and is read only then   | —     |
| `SISYPHOS`             | `GENRE`            | neither the calendar nor the shop names a musical style; every night takes the club's Techno, House default                                      | —     |
| `SISYPHOS`             | `PRICE_BOX_OFFICE` | neither the calendar nor the shop states a door price                                                                                            | —     |
| `SISYPHOS`             | `TICKET_URL`       | the shop sells few nights, and its bot protection answers the importer 429 from a hosting address; we do not disguise the client                 | —     |
| `SISYPHOS`             | `PRICE_PRESALE`    | the shop sells few nights, and its bot protection answers the importer 429 from a hosting address; we do not disguise the client                 | —     |
| `SISYPHOS`             | `CANCELLATION`     | neither the calendar nor the shop marks a cancelled night                                                                                        | —     |
| `SO36`                 | `PRICE`            | the shop exposes prices only as ticket categories, so a door-only event without an Abendkasse category carries no figure                         | —     |
| `SO36`                 | `SOLD_OUT`         | the JSON-LD offer reports `SoldOut` for the external shops most events sell through, even when those shops still have tickets, so it is not read | —     |
| `SO36`                 | `GENRE`            | the shop tags each event only as Konzert, Party or Event, never a genre                                                                          | —     |
| `SODA`                 | `DOORS_TIME`       | the Einlass info box states an age limit, not a doors time                                                                                       | —     |
| `SODA`                 | `ARTISTS`          | the JSON-LD performer is the placeholder Unbekannt on every night                                                                                | —     |
| `SODA`                 | `PROMOTERS`        | the JSON-LD `organizer` is the venue itself on every night                                                                                       | —     |
| `SODA`                 | `IMAGE`            | flyers are hosted on soda.disco2app.com, whose robots.txt forbids fetching, so none can be cached                                                | —     |
| `SONNENRAUM`           | `START_TIME`       | the listing prints one only where an act line carries a slot time; the homepage's NEXT box prints the rest, for the ten nights it shows          | —     |
| `SONNENRAUM`           | `EVENT_TYPE`       | the venue publishes no category of its own; every listing is a club night                                                                        | —     |
| `SONNENRAUM`           | `PER_EVENT_PAGE`   | the programme page is the source for every night                                                                                                 | —     |
| `SONNENRAUM`           | `PRICE`            | the programme lists times and the line-up only, never an admission price                                                                         | —     |
| `SONNENRAUM`           | `IMAGE`            | the programme is text only; the site's only images are its logos                                                                                 | —     |
| `SUPAMOLLY`            | `PRICE`            | the venue publishes no prices                                                                                                                    | —     |
| `SUPAMOLLY`            | `TICKET_URL`       | the venue runs no ticket shop                                                                                                                    | —     |
| `THE_WALL`             | `DOORS_TIME`       | each producer states one time per show, doors for some and the start for others                                                                  | —     |
| `THE_WALL`             | `PRICE`            | most shows are pay-what-you-want, and a fixed price appears only in the prose                                                                    | —     |
| `THE_WALL`             | `TICKET_URL`       | reservations run through the club's own Spotagig pages, which the event page is                                                                  | —     |
| `THE_WALL`             | `ARTISTS`          | the showcases name no comedians, and a headliner appears only in the title                                                                       | —     |
| `TIFFANY_CLUB`         | `EVENT_TYPE`       | the site names no category; the type is read from the title and defaults to a party                                                              | —     |
| `TIFFANY_CLUB`         | `DOORS_TIME`       | the site prints one start time per night                                                                                                         | —     |
| `TIFFANY_CLUB`         | `GENRE`            | the site names no musical style                                                                                                                  | —     |
| `TIFFANY_CLUB`         | `PRICE`            | the site prints no ticket price, only a guest-list discount inside a form                                                                        | —     |
| `TIFFANY_CLUB`         | `PROMOTERS`        | the site credits no promoter beside the night's name                                                                                             | —     |
| `TIFFANY_CLUB`         | `SOLD_OUT`         | the site states no ticket status                                                                                                                 | —     |
| `TIFFANY_CLUB`         | `CANCELLATION`     | the site has no cancelled marker for a night                                                                                                     | —     |
| `TRESOR`               | `DOORS_TIME`       | the venue states no doors or start time; the night's opening set is the only clock it gives, and that is stored as the start                     | —     |
| `TRESOR`               | `EVENT_TYPE`       | the club states no category; every listing is a club night                                                                                       | —     |
| `TRESOR`               | `PRICE`            | the club sells at the door and prints no figure on its programme                                                                                 | —     |
| `UBER_ARENA`           | `PRICE`            | the listing shows a from-price only once presale opens; cancelled, moved and unannounced shows have none                                         | —     |
| `UBER_ARENA`           | `GENRE`            | the platform files events only as Konzert, Comedy, Show or Sport, never a genre                                                                  | —     |
| `UBER_EATS_MUSIC_HALL` | `PRICE`            | the listing shows a from-price only once presale opens; cancelled, moved and unannounced shows have none                                         | —     |
| `UBER_EATS_MUSIC_HALL` | `GENRE`            | the platform files events only as Konzert, Comedy, Show or Sport, never a genre                                                                  | —     |
| `UFA_FABRIK`           | `PAGINATION`       | the calendar is one page per month, and only this month and the next are read                                                                    | —     |
| `UFA_FABRIK`           | `DOORS_TIME`       | the house publishes one time per show                                                                                                            | —     |
| `UFA_FABRIK`           | `PROMOTERS`        | the house names no promoter                                                                                                                      | —     |
| `UFO_IM_VELODROM`      | `PRICE`            | the listing and the event pages print no figure; tickets are sold through outside shops                                                          | —     |
| `URBAN_SPREE`          | `PROMOTERS`        | the venue credits itself as the organiser on its own nights, so the stored promoter is the venue                                                 | —     |
| `VELODROM`             | `PRICE`            | the listing and the event pages print no figure; tickets are sold through outside shops                                                          | —     |
| `VOID_CLUB`            | `START_TIME`       | the venue publishes no times; every night stores a bare date                                                                                     | —     |
| `VOID_CLUB`            | `DOORS_TIME`       | the venue publishes no times; every night stores a bare date                                                                                     | —     |
| `VOID_CLUB`            | `PRICE`            | the venue publishes no prices                                                                                                                    | —     |
| `VOID_CLUB`            | `DESCRIPTION`      | the venue publishes no per-event text                                                                                                            | —     |
| `VOID_CLUB`            | `PER_EVENT_PAGE`   | every night points at the programme page                                                                                                         | —     |
| `VOID_CLUB`            | `EVENT_TYPE`       | the club states no category; `.void-event-genre` names the music and `.void-event-venue` the rooms in use, neither of which is a kind of event   | —     |
| `VOID_CLUB`            | `IMAGE`            | event cards carry no image; the hero slider shows other events than the listed nights                                                            | —     |
| `WILD_AT_HEART`        | `PER_EVENT_PAGE`   | the whole programme is one hand-coded page                                                                                                       | —     |
| `WILD_AT_HEART`        | `EVENT_TYPE`       | the retro page has no category field; a live-music venue, so an unmarked title defaults to a concert                                             | —     |
| `WILD_AT_HEART`        | `START_TIME`       | a start appears only inside a banner (Beginn 21:00, ab 14 Uhr); a row without one stores the house doors from info.htm (20:00), no start         | —     |
| `WILD_AT_HEART`        | `PRICE`            | the venue prints no ticket price; it marks only free-entry nights and links a few shows to a shop                                                | —     |
| `WUEHLMAEUSE`          | `DESCRIPTION`      | the ticket products carry no show text                                                                                                           | —     |
| `WUEHLMAEUSE`          | `EVENT_TYPE`       | the shop files every show under its act and names no format; Kabarett, comedy and music share one stage                                          | —     |
| `WUEHLMAEUSE`          | `GENRE`            | the shop names no genre                                                                                                                          | —     |
| `WUEHLMAEUSE`          | `PRICE_BOX_OFFICE` | the shop sells one price per seat category and states no box-office price                                                                        | —     |
| `WUEHLMAEUSE`          | `PROMOTERS`        | the shop names no promoter                                                                                                                       | —     |
| `WUHLHEIDE`            | `CANCELLATION`     | the venue publishes no cancellations; its one badge, Ausverkauft, is a sold-out flag                                                             | —     |
| `ZENNER`               | `PRICE`            | the venue publishes no prices                                                                                                                    | —     |
| `ZENNER`               | `DOORS_TIME`       | the venue publishes no doors times                                                                                                               | —     |
| `ZENNER`               | `SOLD_OUT`         | the venue publishes no sold-out state                                                                                                            | —     |
| `ZENNER`               | `PER_EVENT_PAGE`   | the venue publishes no per-event pages                                                                                                           | —     |
| `ZIG_ZAG_HALL`         | `END_TIME`         | the end time is a calendar default, 23:59 on most nights                                                                                         | —     |
| `ZIG_ZAG_HALL`         | `PROMOTERS`        | the club presents every night itself, in both houses                                                                                             | —     |
| `ZIG_ZAG_HALL`         | `SOLD_OUT`         | the site states no ticket status                                                                                                                 | —     |
| `ZIG_ZAG_HALL`         | `CANCELLATION`     | the site has no cancelled marker for a night                                                                                                     | —     |
| `ZIG_ZAG_JAZZ_CLUB`    | `END_TIME`         | the end time is a calendar default, 23:59 on most nights                                                                                         | —     |
| `ZIG_ZAG_JAZZ_CLUB`    | `PROMOTERS`        | the club presents every night itself, in both houses                                                                                             | —     |
| `ZIG_ZAG_JAZZ_CLUB`    | `SOLD_OUT`         | the site states no ticket status                                                                                                                 | —     |
| `ZIG_ZAG_JAZZ_CLUB`    | `CANCELLATION`     | the site has no cancelled marker for a night                                                                                                     | —     |
| `ZUR_KLAPPE`           | `SUBTITLE`         | the site states one title per night and no second line                                                                                           | —     |
| `ZUR_KLAPPE`           | `DOORS_TIME`       | the site publishes an opening time, not a separate doors time                                                                                    | —     |
| `ZUR_KLAPPE`           | `GENRE`            | the site names no musical style                                                                                                                  | —     |
| `ZUR_KLAPPE`           | `PRICE`            | the site prints no price for a night                                                                                                             | —     |
| `ZUR_KLAPPE`           | `PROMOTERS`        | the site credits no promoter beside the party name                                                                                               | —     |
| `ZUR_KLAPPE`           | `SOLD_OUT`         | the site states no ticket status                                                                                                                 | —     |
| `ZUR_KLAPPE`           | `CANCELLATION`     | the site has no cancelled marker for a night                                                                                                     | —     |
| `ZUR_KLAPPE`           | `IMAGE`            | the venue sets no cover image; its only image is a generated title card for link previews                                                        | —     |

## Sources with nothing declared

These publish everything the model stores, as of the last review:

`HEIMATHAFEN`, `MADAME_CLAUDE`, `MATRIX`, `QUASIMODO`, `TEMPODROM`, `THEATER_IM_DELPHI`, `URANIA`, `ZITADELLE`
