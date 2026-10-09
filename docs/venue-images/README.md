# Venue images

Which of the 92 venues has a photograph, where it came from, and who said so.

`REVIEWED.tsv` is the record. `scripts/venue-images.py` reads it and writes the confirmed rows through the admin API. Everything below is why the file
exists rather than what it contains.

## The short version

- **A person judged every row.** No heuristic decided any of these. A file that a search returned, a coordinate agreed with and a name matched is still, very
  often, the building next door.
- **`CONFIRMED` means somebody looked at the picture and recognised the venue.** `REJECTED` means they looked and did not. Both are results. The second is what
  stops a search re-proposing the same wrong file.
- **`WITHDRAWN` means the archive removed a picture a person confirmed.** It is not a verdict, and `REJECTED` would read as one. One row holds it today:
  `Schokoladen`, whose Flickr page answered 404 the first time the fetcher asked. The row keeps the file it named, so a later search knows the picture existed
  and where.
- **The licence is not taken from this file.** The script reads it from the Commons API on every run. `licence_at_review` is here so a file relicensed since the
  review stops that venue instead of being written with a stale credit.

## Where the candidates came from

Two rounds. Wikimedia Commons ran first, over all 86 venues. Openverse ran second, over the 46 Commons could not picture.

**Round 1 — Wikimedia Commons.** Three searches, each weaker than the one before, and the hit rates say by how much.

| Found by         | Method                                                            | Reviewed | Confirmed | Hit rate |
| ---------------- | ----------------------------------------------------------------- | -------- | --------- | -------- |
| `wikidata-p18`   | The venue's Wikidata item, property `P18`                         | 22       | 17        | **77%**  |
| `commons-title`  | A Commons file whose title names the venue, constrained to Berlin | 42       | 21        | **50%**  |
| `commons-nearby` | A Commons file photographed within 150 m of the venue             | 22       | 2         | **9%**   |
|                  |                                                                   | **86**   | **40**    | 47%      |

**Proximity is worth about a ninth of a name.** That is the finding worth keeping. A coordinate is a weak way to find a picture of a building, and that
argues against a coordinate-only archive next.

**Round 2 — Openverse.** One search over the 46 venues round 1 left empty. Openverse aggregates Flickr, Europeana, Smithsonian and more behind one API,
so it answers "is there supply outside Commons" in a single pass.

| Found by           | Method                                                       | Reviewed | Confirmed | Usable | Hit rate |
| ------------------ | ------------------------------------------------------------ | -------- | --------- | ------ | -------- |
| `openverse-flickr` | An Openverse file whose title names the venue, plus `Berlin` | 46       | 4         | 3      | **7%**   |

**Confirmed and usable differ by one.** Flickr withdrew the `Schokoladen` picture after the review. The section below says what happened.

**Flickr is not empty, but it is thin.** The probe found a candidate for 13 of the 46, and 127 candidates in total. A person kept 4. Most of the rest are
gig photographs. A band on the stage answers a different question than a venue card asks.

**Both denominators are the venues a round reviewed, not what `found_by` now counts.** Four venues were rejected in round 1 and confirmed in round 2, so their
rows moved to `openverse-flickr`. The column records where the picture in use came from, not every candidate a venue had. `Tresor` moved the same way
inside round 1. It had a `P18` image, Commons names no author for it, and a title search found the file used instead.

**Commons is exhausted.** All three searches it supports ran, and a person read every result. A fourth pass returns what these three rejected.

## What the columns hold

| Column              | Meaning                                                                                                                     |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `venue`             | The venue name as `http/importer/dev-seed.http` seeds it                                                                    |
| `decision`          | `CONFIRMED` or `REJECTED`. Every venue has one                                                                              |
| `file`              | How the archive names the picture: a Commons file name without the `File:` prefix, a Flickr photo id. Empty for a rejection |
| `licence_at_review` | The licence as the archive stated it, worded the way the `SPDX` map in the script keys on                                   |
| `file_page`         | The file's description page, which the rendered credit links to                                                             |
| `found_by`          | Which search proposed it, or `own-photograph` for a picture we took                                                         |

## Our own photographs

`found_by` is `own-photograph` for a picture we took ourselves ([ADR-028](../adr/ADR-028_OWN_PHOTOGRAPH_HOSTING.md)). Three columns then mean something
else, because no archive stands behind the file:

| Column              | For an own photograph                                                                                         |
| ------------------- | ------------------------------------------------------------------------------------------------------------- |
| `file`              | The path of the image on the disk of whoever runs the script. Under 8 MiB                                     |
| `licence_at_review` | The licence chosen for this photograph, worded the way the `SPDX` map keys on. It is the licence, not a check |
| `file_page`         | The Photographs section of the About page, which states who took the picture and under what licence           |

The script uploads the file to the public `event-junkie-images-own` bucket and stores that URL. The object name is the venue and a hash of the bytes. A new photograph of a venue gets a new URL, and an upload of the same file writes the same object again. The credit is the operator's name and no archive. A dry run prints the upload. Only `--apply` uploads, through the `aws` CLI with the credentials in `infra/.envrc`:

```sh
direnv exec infra python3 scripts/venue-images.py --venue Loge --apply
```

The bucket keeps an object nobody references any more. Delete it by hand when a row changes its file.

## Asking the archive is what finds a dead picture

The script reads the licence from the archive on every run, never from this file. That check was
written for a relicensing. The first Flickr run showed what else it catches.

`Schokoladen` was confirmed in the review. Its Flickr page answered **404** when the fetcher asked.
Flickr deleted it, or made it private, in the weeks between. Openverse still indexes it, and the
image file still serves from Flickr's CDN. Only Flickr itself says the picture is gone. The row is
`WITHDRAWN`.

Ask the source, not the index. The number says it better than the principle: one Flickr picture of
four, on the first run.

## What is still open

- **A thumbnail is a copy, not a modification.** 19 images said so before FAL asked. `FAL` reached us on `Admiralspalast` and `Parkbühne Wuhlheide`. It has
  no clause like CC 4.0 § 2(a)(4), which says a format change never produces adapted material. Neither has any CC licence older than 4.0, and 19 of the
  live images are on one. Treating FAL differently would be one rule for two pictures and another for nineteen. Its article 4 also keeps the site out of FAL,
  because each image is reachable on its own. Settled in [#1281](https://github.com/enorm-labs/event-junkie/issues/1281).
- **One archive answers at 1024 px.** Commons renders a thumbnail to order, and the script asks for 1600. Flickr publishes a fixed ladder of sizes and
  everything above 1024 needs a signed secret, so a Flickr picture is stored at 1024. The 1536 px derivative is then wider than its original.
- **One Flickr picture carries the Public Domain Mark.** `Klunkerkranich`. The mark says a work is out of copyright, which a 2015 rooftop photograph is not, so
  the uploader more probably meant "take it". The credit names the photographer and links the source either way, which is why it is recorded rather than
  refused.
- **42 venues have nothing.** They are the hard half — a room in a courtyard, a bar with no frontage, a rooftop over a car park. Few people photograph them,
  and two archives say so. Asking the venues is [#808](https://github.com/enorm-labs/event-junkie/issues/808).
