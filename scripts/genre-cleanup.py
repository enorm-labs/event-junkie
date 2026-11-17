#!/usr/bin/env python3
"""Flag the stored genre tags that are not genres, or one genre spelled twice, for a person to judge.

    python3 scripts/genre-cleanup.py collect production                     # temp/genre-cleanup-production-<date>.json
    python3 scripts/genre-cleanup.py collect staging --only no-family,format-word
    python3 scripts/genre-cleanup.py wikidata production    # temp/genre-cleanup-wikidata-production-<date>.json

Read-only. It reads every genre tag, every event and every venue from the admin API and joins each tag to the events
whose `genreTags` name it. A row carries the tag's family, its flags, its sources, its counts and up to three events
with the venue's raw `genre` text. Every linked tag is a row, flagged rows first: a genre list is short enough to judge
whole. A tag no event links is left out, because the nightly orphan sweep deletes it.

The JSON goes to `temp/`, beside a TSV with an empty `decision` and `reason` column per row. The `previous` column holds
the verdict the newest earlier TSV of that environment gave the slug, for the reader to confirm or overturn. Nothing is
written to a cluster: a fix is a normalizer change plus a `V` migration, and `/genre-cleanup` drives that.

`wikidata` lists the Wikidata genre items (P136) stored on EXACT artists that neither `WIKIDATA_GENRES` nor
`WIKIDATA_NO_TAG` in `WikidataGenres.kt` names (ADR-060, #5252). A row carries the item's English label, the acts
that hold it and its reach: the upcoming concerts, parties and festivals with no genre tag whose non-support acts
hold it. The labels come from the Wikidata API, the only call that leaves the forward.

It talks to the importer forward `scripts/ej.sh up <env>` starts. Exit 0 on success, 2 when it could not run.
"""

import argparse
import csv
import datetime
import json
import pathlib
import re
import sys
import unicodedata
import urllib.error
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
PORTS = {"staging": 18081, "production": 28081}
PAGE_SIZE = 100
MAX_PAGES = 200
SAMPLE_EVENTS = 3
EXIT_CANNOT_RUN = 2
COLUMNS = [
    "decision",
    "reason",
    "previous",
    "id",
    "slug",
    "name",
    "family",
    "flags",
    "near_duplicates",
    "sources",
    "events",
    "upcoming",
    "sample",
]
FLAG_NAMES = ["no-family", "near-duplicate", "format-word", "one-source"]
WIKIDATA_COLUMNS = ["decision", "reason", "previous", "item", "label", "reach", "acts", "sample_acts"]
WIKIDATA_GENRES_KT = REPO / "events-importer/src/main/kotlin/de/norm/events/genretag/WikidataGenres.kt"
WIKIDATA_API = "https://www.wikidata.org/w/api.php"
WIKIDATA_BATCH = 50
USER_AGENT = "event-junkie-spike/0.1 ( https://github.com/enorm-labs/event-junkie )"
MUSIC_TYPES = {"CONCERT", "PARTY", "FESTIVAL"}
SAMPLE_ACTS = 3

# Words that name a format, an event type, an audience or a place more often than a style. Over-matches on purpose.
FORMAT_WORDS = set(
    "abend ausstellung brunch burlesque comedy dj djs drag edition exhibition family festival film flinta "
    "gesprach jam kabarett karaoke kids kinder kino konzert lecture lesung lesungen live market markt musical "
    "nacht night open panel party performance poetry queer quiz reading release revue screening session set sets "
    "show slam special talk theater tour vortrag workshop".split()
)
# The same words at the end of a German compound: `Kneipenquiz`, `Improtheater`.
COMPOUND_TAILS = tuple("quiz theater show party workshop lesung lesungen abend nacht markt konzert".split())


def parse_args(argv=None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Flag stored genre tags that are not genres, or one genre twice.")
    sub = parser.add_subparsers(dest="command", required=True)
    collect = sub.add_parser("collect", help="write the review file; read-only")
    collect.add_argument("env", choices=sorted(PORTS))
    collect.add_argument("--only", help=f"comma-separated flags to keep, of {', '.join(FLAG_NAMES)}; default all rows")
    collect.add_argument("--out", type=pathlib.Path, help="the JSON path; the TSV goes beside it")
    wikidata = sub.add_parser("wikidata", help="list the stored Wikidata genres the map lacks; read-only")
    wikidata.add_argument("env", choices=sorted(PORTS))
    wikidata.add_argument("--out", type=pathlib.Path, help="the JSON path; the TSV goes beside it")
    return parser.parse_args(argv)


def http_get(url: str):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def pages(get, url: str) -> list:
    rows, page = [], 0
    while page < MAX_PAGES:
        body = get(f"{url}{'&' if '?' in url else '?'}size={PAGE_SIZE}&page={page}")
        rows += body["content"]
        page += 1
        if page >= body["totalPages"]:
            return rows
    raise RuntimeError(f"{url} has more than {MAX_PAGES} pages")


def plain(name: str) -> str:
    """Casefolded, with accents dropped: `Gespräch` is `gesprach`."""
    text = unicodedata.normalize("NFKD", name.casefold())
    return "".join(c for c in text if not unicodedata.combining(c))


def fold(name: str) -> str:
    """The near-duplicate key: `&`, `and` and `'n'` become `n`, every non-alphanumeric goes.

    `Rock'n Roll`, `RocknRoll` and `Rock and Roll` share `rocknroll`; `Drum & Bass` and `Drum'n'Bass` share `drumnbass`.
    """
    text = re.sub(r"\s*&\s*|\band\b", "n", plain(name))
    return re.sub(r"[\W_]+", "", text)


def is_format_word(name: str) -> bool:
    words = [w for w in re.split(r"[\W_]+", plain(name)) if w]
    return any(w in FORMAT_WORDS for w in words) or any(w.endswith(COMPOUND_TAILS) for w in words)


def flags_of(tag: dict, sources: list, duplicates: dict) -> list:
    found = []
    if not tag.get("family"):
        found.append("no-family")
    if len(duplicates.get(fold(tag["name"]), ())) > 1:
        found.append("near-duplicate")
    if is_format_word(tag["name"]):
        found.append("format-word")
    if len(sources) == 1:
        found.append("one-source")
    return found


def review_rows(tags: list, events: list, venues: list, today: datetime.date, previous: dict) -> tuple:
    """Every linked tag with its flags, sources and events, and the count of unlinked tags left out.

    Rows a flag other than `one-source` hits come first, most flags first.

    The admin event list names its tags, not their ids, so the join is by name, casefolded.
    """
    venue_by_id = {v["id"]: v["name"] for v in venues}
    by_name = {t["name"].casefold(): t for t in tags}
    duplicates = {}
    for t in tags:
        duplicates.setdefault(fold(t["name"]), []).append(t["slug"])
    linked = {}
    for e in events:
        for name in e.get("genreTags") or []:
            tag = by_name.get(name.casefold())
            if tag:
                linked.setdefault(tag["id"], []).append(e)

    out, unlinked = [], 0
    for t in tags:
        evs = sorted(linked.get(t["id"], []), key=lambda e: e["eventDate"], reverse=True)
        if not evs:
            unlinked += 1
            continue
        sources = sorted({e["sourceId"].split(":", 1)[0] for e in evs})
        out.append(
            {
                "id": t["id"],
                "slug": t["slug"],
                "name": t["name"],
                "family": t.get("family"),
                "flags": flags_of(t, sources, duplicates),
                "near_duplicates": [s for s in duplicates[fold(t["name"])] if s != t["slug"]],
                "previous": previous.get(t["slug"], ""),
                "sources": sources,
                "event_count": len(evs),
                "upcoming": sum(1 for e in evs if e["eventDate"] >= today.isoformat()),
                "events": [
                    {
                        "date": e["eventDate"],
                        "venue": venue_by_id.get(e["venueId"]),
                        "title": e["title"],
                        "event_type": e.get("eventType"),
                        "genre": e.get("genre"),
                        "source_id": e["sourceId"],
                        "source_url": e.get("sourceUrl"),
                    }
                    for e in evs[:SAMPLE_EVENTS]
                ],
            }
        )
    # `one-source` alone is the weakest reason to look: most real genres reach us from one venue.
    rows = sorted(
        out, key=lambda r: (not set(r["flags"]) - {"one-source"}, -len(r["flags"]), -r["upcoming"], r["slug"])
    )
    return rows, unlinked


def previous_verdicts(env: str, out: pathlib.Path) -> dict:
    """`DECISION: reason` per slug from the newest earlier `temp/genre-cleanup-<env>-*.tsv`, or nothing."""
    earlier = sorted(p for p in (REPO / "temp").glob(f"genre-cleanup-{env}-*.tsv") if p != out.with_suffix(".tsv"))
    if not earlier:
        return {}
    with earlier[-1].open(newline="") as f:
        return {
            r["slug"]: f"{r['decision']}: {r['reason']}" for r in csv.DictReader(f, delimiter="\t") if r.get("decision")
        }


def write_tsv(path: pathlib.Path, rows: list) -> None:
    with path.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=COLUMNS, delimiter="\t")
        writer.writeheader()
        for r in rows:
            sample = r["events"][0] if r["events"] else None
            writer.writerow(
                {
                    "decision": "",
                    "reason": "",
                    "previous": r["previous"],
                    "id": r["id"],
                    "slug": r["slug"],
                    "name": r["name"],
                    "family": r["family"] or "",
                    "flags": ",".join(r["flags"]),
                    "near_duplicates": ",".join(r["near_duplicates"]),
                    "sources": ",".join(r["sources"]),
                    "events": r["event_count"],
                    "upcoming": r["upcoming"],
                    "sample": f"{sample['date']} {sample['venue']}: {sample['title']} [genre: {sample['genre']}]"
                    if sample
                    else "",
                }
            )


def collect(args) -> int:
    base = f"http://localhost:{PORTS[args.env]}/api/admin"
    try:
        tags = pages(http_get, f"{base}/genre-tags")
        events = pages(http_get, f"{base}/events")
        venues = pages(http_get, f"{base}/venues")
    except (urllib.error.URLError, OSError) as error:
        print(f"genre-cleanup: the {args.env} importer does not answer ({error}) — run scripts/ej.sh up {args.env}")
        return EXIT_CANNOT_RUN

    today = datetime.date.today()
    out = args.out or REPO / "temp" / f"genre-cleanup-{args.env}-{today}.json"
    rows, unlinked = review_rows(tags, events, venues, today, previous_verdicts(args.env, out))
    if args.only:
        wanted = set(args.only.split(","))
        rows = [r for r in rows if wanted & set(r["flags"])]

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rows, indent=1, ensure_ascii=False))
    write_tsv(out.with_suffix(".tsv"), rows)

    counts = {}
    for r in rows:
        for flag in r["flags"]:
            counts[flag] = counts.get(flag, 0) + 1
    flagged = sum(1 for r in rows if r["flags"])
    carried = sum(1 for r in rows if r["previous"])
    print(f"{len(tags)} genre tags, {len(events)} events: {len(rows)} rows, {flagged} flagged, {carried} judged before")
    if unlinked:
        print(f"  {unlinked} unlinked tags left out: the nightly orphan sweep deletes them")
    print("  " + ", ".join(f"{flag} {n}" for flag, n in sorted(counts.items(), key=lambda kv: -kv[1])))
    print(f"wrote {out} and {out.with_suffix('.tsv').name}")
    return 0


def judged_items(source: str) -> set:
    """The item ids `WikidataGenres.kt` already decides: the map's keys and `WIKIDATA_NO_TAG`."""
    return set(re.findall(r'"(Q\d+)"', source))


def unmapped_rows(artists: list, events: list, judged: set, today: datetime.date, previous: dict) -> list:
    """One row per stored item `judged` lacks, the widest reach first, then the most acts."""
    holders = {}
    for a in artists:
        for item in a["wikidataGenres"]:
            if item not in judged:
                holders.setdefault(item, []).append(a)
    by_artist = {a["id"]: a["wikidataGenres"] for a in artists}
    reach = {}
    for e in events:
        if e.get("eventType") not in MUSIC_TYPES or e.get("genreTags") or e["eventDate"] < today.isoformat():
            continue
        items = {
            i for b in e.get("artists") or [] if b.get("role") != "SUPPORT" for i in by_artist.get(b["artistId"], ())
        }
        for item in items & holders.keys():
            reach[item] = reach.get(item, 0) + 1
    rows = [
        {
            "item": item,
            "label": "",
            "reach": reach.get(item, 0),
            "acts": len(acts),
            "sample_acts": [a["name"] for a in acts[:SAMPLE_ACTS]],
            "previous": previous.get(item, ""),
        }
        for item, acts in holders.items()
    ]
    return sorted(rows, key=lambda r: (-r["reach"], -r["acts"], int(r["item"][1:])))


def wikidata_labels(items: list, get) -> dict:
    """The English label of each item, by `wbgetentities` in batches; an item with none keeps no label."""
    labels = {}
    for start in range(0, len(items), WIKIDATA_BATCH):
        ids = "|".join(items[start : start + WIKIDATA_BATCH])
        body = get(f"{WIKIDATA_API}?action=wbgetentities&ids={ids}&props=labels&languages=en&format=json")
        for item, entity in (body.get("entities") or {}).items():
            labels[item] = ((entity.get("labels") or {}).get("en") or {}).get("value", "")
    return labels


def wikidata_get(url: str):
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def previous_wikidata_verdicts(env: str, out: pathlib.Path) -> dict:
    """`DECISION: reason` per item from the newest earlier `temp/genre-cleanup-wikidata-<env>-*.tsv`, or nothing."""
    earlier = sorted(
        p for p in (REPO / "temp").glob(f"genre-cleanup-wikidata-{env}-*.tsv") if p != out.with_suffix(".tsv")
    )
    if not earlier:
        return {}
    with earlier[-1].open(newline="") as f:
        return {
            r["item"]: f"{r['decision']}: {r['reason']}" for r in csv.DictReader(f, delimiter="\t") if r.get("decision")
        }


def wikidata(args) -> int:
    base = f"http://localhost:{PORTS[args.env]}/api/admin"
    try:
        artists = http_get(f"{base}/artists/wikidata-genres")
        events = pages(http_get, f"{base}/events")
    except (urllib.error.URLError, OSError) as error:
        print(f"genre-cleanup: the {args.env} importer does not answer ({error}) — run scripts/ej.sh up {args.env}")
        return EXIT_CANNOT_RUN

    today = datetime.date.today()
    out = args.out or REPO / "temp" / f"genre-cleanup-wikidata-{args.env}-{today}.json"
    judged = judged_items(WIKIDATA_GENRES_KT.read_text())
    rows = unmapped_rows(artists, events, judged, today, previous_wikidata_verdicts(args.env, out))
    try:
        labels = wikidata_labels([r["item"] for r in rows], wikidata_get)
    except (urllib.error.URLError, OSError) as error:
        print(f"genre-cleanup: Wikidata does not answer ({error}); the rows keep no label")
        labels = {}
    for r in rows:
        r["label"] = labels.get(r["item"], "")

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rows, indent=1, ensure_ascii=False))
    with out.with_suffix(".tsv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=WIKIDATA_COLUMNS, delimiter="\t")
        writer.writeheader()
        for r in rows:
            writer.writerow({**r, "decision": "", "reason": "", "sample_acts": ", ".join(r["sample_acts"])})

    stored = sum(len(a["wikidataGenres"]) for a in artists)
    print(
        f"{len(artists)} artists with Wikidata genres, {stored} items stored, {len(judged)} judged in WikidataGenres.kt"
    )
    print(f"  {len(rows)} unmapped items, {sum(1 for r in rows if r['reach'])} of them on an untagged upcoming night")
    print(f"wrote {out} and {out.with_suffix('.tsv').name}")
    return 0


def main() -> int:
    args = parse_args()
    return wikidata(args) if args.command == "wikidata" else collect(args)


if __name__ == "__main__":
    sys.exit(main())
