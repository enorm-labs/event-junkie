# Promoters — the review behind the names and websites

`REVIEWED.tsv` holds one row per promoter the site has (#328). The rows were read on staging on
2026-09-11, after the merges in `V023__merge_duplicate_promoters.sql`. Each row records what a
person decided about that promoter. A row whose `note` starts with `2026-11-08 scan` comes from a
search over the promoters with upcoming events on production. A person confirmed each result. A row whose `note` starts with `2026-11-16 kinds` has only a `kind`.
That kind comes from the row's upcoming events, their titles, venues and lineups. Nobody searched for its site.

| Column           | What it holds                                                                                                                                                                                                                                                                                                                                                                                                                     |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `stored_name`    | The display name the row had on staging before V023                                                                                                                                                                                                                                                                                                                                                                               |
| `slug`           | The row's slug after V023                                                                                                                                                                                                                                                                                                                                                                                                         |
| `kind`           | `promoter` (a business that books other acts), `collective` (DJs or artists who host their own nights), `label` (a record label), `media` (a magazine or station that presents), `venue` (a venue crediting itself), `sponsor`, `party` (a series with no company behind it), `artist` (an act credited as its own promoter), `junk` (a fragment a scraper put in the slot), or `unverified` (a single credit nothing else names) |
| `website`        | The promoter's own site, opened and read. `none` where the promoter has no site; the script then clears the stored link. Empty keeps what is stored                                                                                                                                                                                                                                                                               |
| `instagram`      | The promoter's Instagram profile, copied from a link on its own site or a search result, never fetched (#356). `none` clears the stored link. Empty keeps what is stored                                                                                                                                                                                                                                                          |
| `name`           | The spelling that site uses. Where it differs from `stored_name`, `PromoterNormalizer` pins it and a data migration (V023 onward) renames the row                                                                                                                                                                                                                                                                                 |
| `description_de` | One to three sentences on the promoter, in German: since when, what it books, where in Berlin. Our own prose, from the site's about page                                                                                                                                                                                                                                                                                          |
| `description_en` | The same text in English, written by hand                                                                                                                                                                                                                                                                                                                                                                                         |
| `events`         | Events behind the row on staging when it was read, all dates                                                                                                                                                                                                                                                                                                                                                                      |
| `note`           | What decided the row                                                                                                                                                                                                                                                                                                                                                                                                              |

**What reads it.** `scripts/promoter-websites.py` writes the `kind`, the `website`, the `instagram` and the two description
columns onto the promoters through the admin API. The `kind` becomes `promoter.kind`, which the promoter page shows for a
collective, a label and a party series (#5080). `unverified` and `junk` leave the stored kind as it is. It also stamps `reviewed_at` on every row it
finds under a matching name, because a row in this table is a review (#1336). The importer never
sets that column. So `GET /api/admin/promoters?reviewed=false` and
`promoter-duplicates.py --unreviewed` list what an import minted since. Nothing else is written. German goes in as the
description and English as the alternate, because German is the site's authoritative language
(ADR-013). An empty `website` keeps the stored link, and `none` clears it: the script sends
`websiteUrl: null` (#2601). Replacing or clearing a stored link needs `--force`. The `name` column reaches the database through the data migrations (V023, V025,
V027, V029, V030) and the normalizer. A rename through the API changes the slug, and the next import would then mint
the old row again.

**`SEARCHED.tsv` records the searches that settled nothing.** Each row is a promoter a scan searched for. `result` is `nothing`
when no own site turned up. It is `medium` when a site turned up with some doubt, and `candidate` holds that site. `scripts/enrichment-coverage.py --next` skips these rows, so
`/enrich` does not search them again. No script writes this file to a cluster. A row there is not a review, so the import may still copy a venue's credit
link onto the promoter (#3027). A row moves to `REVIEWED.tsv` when a person decides it.

**A collective plays, a promoter books.** Decide in this order: a row that releases music is a `label`. A row whose
members are DJs who play their own nights is a `collective`. A row that names a night, not a group of people, is a `party`.
A business that books other people's acts is a `promoter`.

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

**Logos come from Wikimedia Commons, never from a promoter's own site.** `IMAGES.tsv` holds one row per logo:
the promoter's `slug`, its `wikidata` item, the Commons `file` and the `licence_at_review`. A person matched each item
to the promoter, because a name alone also matches a river or a Greek nymph. `scripts/promoter-images.py` reads the
licence and the author from Commons when it runs. It stops a row whose licence changed. A public-domain logo with no
author credits the promoter by name. The promoter page draws the logo whole, on a light plate (#5177).

No promoter's own site grants use of its logo (#328). FluxFM and Karsten Jahnke offer a download on a press page and
say nothing about its use.

**Kinds that are not promoters stay in the table.** A magazine that presents a show is a credit
the venue prints and a reader may search for. The kind is what a later enrichment reads to decide
whether a row gets a description at all. Only `isNonPromoterName` refuses a credit for its kind. metal.de is the one
media partner on its list (#2653).

**Ten club sources credit the crew a title names** (#5080): `hosted by`, `presents`, `invites`, `meets`, and a few forms of one club
only. Each crew arrives with no kind and no `reviewed_at`, so `promoter-duplicates.py --unreviewed` lists it. A person then gives it
a row here, usually as a `collective`.

**A row that no event credits is deleted.** `OrphanPromoterSweep` deletes it each night once the row is a day old.
It keeps a row with a description or an image, because a person wrote that.

**Resident Advisor was checked too.** Every row was searched on ra.co (promoter index, Berlin).
31 of 246 are there, 19 under the same name. RA is an electronic-music index, and most of this
table is not. Seven rows gained a site from it. Four of those are a Facebook or Instagram page,
because that is all the promoter has. No name disagreed. The `note` says which.

**Bi Nuu, Lido and Astra link each promoter credit to its site.** The importer reads that link
and writes it onto a promoter row that has none (#1319). A reviewed site in this table is never
replaced by it. A row reviewed as `none` stays without a site too: the importer skips every row
with `reviewed_at` set (#3027).
