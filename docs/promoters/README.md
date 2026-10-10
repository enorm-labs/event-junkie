# Promoters — the review behind the names and websites

`REVIEWED.tsv` holds one row per promoter the site has (#328). The rows were read on staging on
2026-09-11, after the merges in `V023__merge_duplicate_promoters.sql`. Each row records what a
person decided about that promoter.

| Column           | What it holds                                                                                                                                                                                                                                                                                               |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `stored_name`    | The display name the row had on staging before V023                                                                                                                                                                                                                                                         |
| `slug`           | The row's slug after V023                                                                                                                                                                                                                                                                                   |
| `kind`           | `promoter`, `media` (a magazine or station that presents), `venue` (a venue crediting itself), `sponsor`, `party` (a series with no company behind it), `artist` (an act credited as its own promoter), `junk` (a fragment a scraper put in the slot), or `unverified` (a single credit nothing else names) |
| `website`        | The promoter's own site, opened and read. `none` where the promoter has no site; the script then clears the stored link. Empty keeps what is stored                                                                                                                                                         |
| `name`           | The spelling that site uses. Where it differs from `stored_name`, `PromoterNormalizer` pins it and a data migration (V023 onward) renames the row                                                                                                                                                           |
| `description_de` | One to three sentences on the promoter, in German: since when, what it books, where in Berlin. Our own prose, from the site's about page                                                                                                                                                                    |
| `description_en` | The same text in English, written by hand                                                                                                                                                                                                                                                                   |
| `events`         | Events behind the row on staging when it was read, all dates                                                                                                                                                                                                                                                |
| `note`           | What decided the row                                                                                                                                                                                                                                                                                        |

**What reads it.** `scripts/promoter-websites.py` writes the `website` and the two description
columns onto the promoters through the admin API. It also stamps `reviewed_at` on every row it
finds under a matching name, because a row in this table is a review (#1336). The importer never
sets that column. So `GET /api/admin/promoters?reviewed=false` and
`promoter-duplicates.py --unreviewed` list what an import minted since. Nothing else is written. German goes in as the
description and English as the alternate, because German is the site's authoritative language
(ADR-013). An empty `website` keeps the stored link, and `none` clears it: the script sends
`websiteUrl: null` (#2601). Replacing or clearing a stored link needs `--force`. The `name` column reaches the database through the data migrations (V023, V025,
V027, V029, V030) and the normalizer. A rename through the API changes the slug, and the next import would then mint
the old row again.

**What was checked, and how.** For every `promoter`, `media`, `venue` and `sponsor` row with a
website, that site was opened. Its title, logo text or imprint gave the spelling. The `note` says
where the site and the credit disagree. A `party`, `artist` or `junk` row was read from its events
and its venue, not from a site. An `unverified` row had one search that found nothing to cite. It
stays as the venue wrote it.

**The descriptions are written for the promoters with the most events first.** The first forty
were drafted from each promoter's own about page and read by a person before they were added
(#328). A media partner or a venue that presents shows gets a shorter one. A reader lands on its
page from an event all the same. A row without one shows no description, which is better than a sentence that says
nothing.

**Logos: two sites offer one, none states who may use it.** Every site in the table was read for
a press, brand or partner page (#328). FluxFM (`fluxfm.de/presse`) and Karsten Jahnke
(`kj.de/presse.html`) offer a logo download on a press page and say nothing about its use. The
other press pages take accreditation requests or sit behind a login (rbb). No promoter publishes a
usage grant, so this pass writes no `image_url`. The two offers are in the `note` column, for the
day the image decision in #328 is taken.

**Kinds that are not promoters stay in the table.** A magazine that presents a show is a credit
the venue prints and a reader may search for. The kind is what a later enrichment reads to decide
whether a row gets a description at all. Only `isNonPromoterName` refuses a credit for its kind. metal.de is the one
media partner on its list (#2653).

**A row that no event credits is deleted.** `OrphanPromoterSweep` deletes it each night once the row is a day old.
It keeps a row with a description or an image, because a person wrote that.

**Resident Advisor was checked too.** Every row was searched on ra.co (promoter index, Berlin).
31 of 246 are there, 19 under the same name. RA is an electronic-music index, and most of this
table is not. Seven rows gained a site from it. Four of those are a Facebook or Instagram page,
because that is all the promoter has. No name disagreed. The `note` says which.

**Bi Nuu, Lido and Astra link each promoter credit to its site.** The importer reads that link
and writes it onto a promoter row that has none (#1319). A reviewed site in this table is never
replaced by it, and a row reviewed as `none` stays without one: the importer skips every row with
`reviewed_at` set (#3027).
