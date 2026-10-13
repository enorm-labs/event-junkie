#!/usr/bin/env python3
"""Flag the stored artists (or promoters) whose name does not look like an act, for a person to judge.

    python3 scripts/artist-cleanup.py collect production                    # temp/artist-cleanup-production-<date>.json
    python3 scripts/artist-cleanup.py collect staging --kind promoters
    python3 scripts/artist-cleanup.py collect production --only catalogue,format

Read-only. It reads every artist or promoter, every event and every venue from the admin API, joins them, and keeps
the rows at least one heuristic flags. Each row carries its flags, its sources and its events with the co-billed
names, so a reader can judge it without another query. The heuristics over-match on purpose: a flag is a reason to
look, not a verdict. Rows billed on an upcoming event come first, because those are the ones visitors see.

The JSON goes to `temp/`, beside a TSV with an empty `decision` and `reason` column per row. Nothing is written to a
cluster: a fix is a parser change plus a `V` migration (ADR-005 § Data fixes), and `/artist-cleanup` drives that.

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
COLUMNS = ["decision", "reason", "id", "slug", "name", "flags", "sources", "events", "upcoming", "sample"]

# Each flag names one shape that marks a non-act often enough to be worth a look.
FLAGS = {
    # A label's catalogue number: `Tresor.291`, `Tresor048X`.
    "catalogue": re.compile(r"\b[^\W\d_]{2,}\.\d{2,}|\b[^\W\d_]{3,}\d{3}[A-Za-z]?\b"),
    # A format, occasion or programme word that names the night rather than who plays it.
    "format": re.compile(
        r"\b(?:listening|session|collection|compilation|release|presents?|präsentiert|showcase|takeover|"
        r"night|nacht|party|tour|edition|special|label|records|recordings|invites?|hosted|lineup|line-up|"
        r"opening|closing|open\s+air|open\s+mic|jam|workshop|talk|lecture|screening|karaoke|quiz|lesung|reading|"
        r"exhibition|ausstellung|vernissage|konzert|concert|festival|fest|stage|floor|bühne|tickets?|einlass|"
        r"doors|eintritt|abendkasse|ausverkauft|sold\s+out|cancelled|abgesagt|verschoben|tba|tbc|guests?|"
        r"all\s+night|b2b|vs\.?|feat\.?|featuring|support|dj[\s-]?set|live[\s-]?set|und\s+freunde|and\s+friends)\b",
        re.IGNORECASE,
    ),
    # Punctuation a parser should have split on or stripped.
    "separator": re.compile(r"\s[-–—|]\s|:\s|//|\s/\s|\s\+\s|[\"„“”«»]|\(|\)|\[|\]"),
    # A clock time, a date, a weekday or a price.
    "date-or-price": re.compile(
        r"\b\d{1,2}[:.]\d{2}\b|\b\d{1,2}\.\d{1,2}\.|\b(?:mo|di|mi|do|fr|sa|so|mon|tue|wed|thu|fri|sat|sun)\.?,|"
        r"\b(?:montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|monday|tuesday|wednesday|thursday|"
        r"friday|saturday|sunday)\b|€|\beur\b|\beuro\b",
        re.IGNORECASE,
    ),
    "url": re.compile(r"https?://|www\.|@\w|\.(?:de|com|net|org|berlin)\b", re.IGNORECASE),
}
LONG_WORDS = 6
LONG_CHARS = 45
# What keeps an unbilled row from its nightly orphan sweep: UnbilledArtistStore and UncreditedPromoterStore.
PROMOTER_PROFILE_FIELDS = ["description", "imageUrl"]
PROFILE_FIELDS = [
    "description",
    "imageUrl",
    "websiteUrl",
    "facebookUrl",
    "instagramUrl",
    "youtubeUrl",
    "bandcampUrl",
    "soundcloudUrl",
    "discogsUrl",
    "wikidataUrl",
    "residentAdvisorUrl",
    "spotifyUrl",
]


def parse_args(argv=None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Flag stored artists or promoters whose name is not an act's.")
    sub = parser.add_subparsers(dest="command", required=True)
    collect = sub.add_parser("collect", help="write the review file; read-only")
    collect.add_argument("env", choices=sorted(PORTS))
    collect.add_argument("--kind", choices=["artists", "promoters"], default="artists")
    collect.add_argument("--only", help="comma-separated flags to keep; default all")
    collect.add_argument("--out", type=pathlib.Path, help="the JSON path; the TSV goes beside it")
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


def fold(name: str) -> str:
    """The name with case, accents, a leading `DJ`/`The` and every non-alphanumeric dropped: the near-duplicate key."""
    plain = unicodedata.normalize("NFKD", name.casefold())
    plain = "".join(c for c in plain if not unicodedata.combining(c))
    plain = re.sub(r"^(?:dj|the)\s+", "", plain)
    return re.sub(r"[\W_]+", "", plain)


def linked_ids(event: dict, kind: str) -> list:
    if kind == "promoters":
        return event.get("promoterIds") or []
    return [a["artistId"] for a in event.get("artists") or []]


def flags_of(name: str, venue_names: set, duplicates: dict) -> list:
    found = [flag for flag, pattern in FLAGS.items() if pattern.search(name)]
    if len(name.split()) >= LONG_WORDS or len(name) > LONG_CHARS:
        found.append("long")
    folded = fold(name)
    if any(v and (v == folded or (len(v) > 4 and v in folded)) for v in venue_names):
        found.append("venue-name")
    if len(duplicates.get(folded, ())) > 1:
        found.append("near-duplicate")
    return found


def swept(row: dict, kind: str) -> bool:
    """Whether the nightly sweep deletes this row once no event bills it."""
    if kind == "promoters":
        return not any(row.get(f) for f in PROMOTER_PROFILE_FIELDS)
    return row.get("musicbrainzMatch") != "EXACT" and not any(row.get(f) for f in PROFILE_FIELDS)


def review_rows(rows: list, events: list, venues: list, kind: str, today: datetime.date) -> list:
    """Every row a flag hits, with its sources, its events and the names billed beside it; upcoming rows first.

    An unbilled row the sweep deletes is left out. One the sweep keeps is flagged `unbilled-kept`, because nothing
    else ever removes it.
    """
    names = {r["id"]: r["name"] for r in rows}
    venue_by_id = {v["id"]: v["name"] for v in venues}
    venue_names = {fold(v["name"]) for v in venues}
    duplicates = {}
    for r in rows:
        duplicates.setdefault(fold(r["name"]), []).append(r["slug"])
    billed = {}
    for e in events:
        for i in linked_ids(e, kind):
            billed.setdefault(i, []).append(e)

    out = []
    for r in rows:
        evs = sorted(billed.get(r["id"], []), key=lambda e: e["eventDate"], reverse=True)
        if not evs and swept(r, kind):
            continue
        flags = flags_of(r["name"], venue_names, duplicates)
        if not flags:
            continue
        if not evs:
            flags.append("unbilled-kept")
        out.append(
            {
                "id": r["id"],
                "slug": r["slug"],
                "name": r["name"],
                "flags": flags,
                "near_duplicates": [s for s in duplicates[fold(r["name"])] if s != r["slug"]],
                "sources": sorted({e["sourceId"].split(":", 1)[0] for e in evs}),
                "event_count": len(evs),
                "upcoming": sum(1 for e in evs if e["eventDate"] >= today.isoformat()),
                "events": [
                    {
                        "date": e["eventDate"],
                        "venue": venue_by_id.get(e["venueId"]),
                        "title": e["title"],
                        "source_id": e["sourceId"],
                        "source_url": e["sourceUrl"],
                        "billed_with": [names.get(i, "?") for i in linked_ids(e, kind) if i != r["id"]],
                    }
                    for e in evs[:SAMPLE_EVENTS]
                ],
            }
        )
    return sorted(out, key=lambda r: (-r["upcoming"], -len(r["flags"]), r["slug"]))


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
                    "id": r["id"],
                    "slug": r["slug"],
                    "name": r["name"],
                    "flags": ",".join(r["flags"]),
                    "sources": ",".join(r["sources"]),
                    "events": r["event_count"],
                    "upcoming": r["upcoming"],
                    "sample": f"{sample['date']} {sample['venue']}: {sample['title']}" if sample else "",
                }
            )


def collect(args) -> int:
    base = f"http://localhost:{PORTS[args.env]}/api/admin"
    try:
        rows = pages(http_get, f"{base}/{args.kind}")
        events = pages(http_get, f"{base}/events")
        venues = pages(http_get, f"{base}/venues")
    except (urllib.error.URLError, OSError) as error:
        print(f"artist-cleanup: the {args.env} importer does not answer ({error}) — run scripts/ej.sh up {args.env}")
        return EXIT_CANNOT_RUN

    today = datetime.date.today()
    flagged = review_rows(rows, events, venues, args.kind, today)
    if args.only:
        wanted = set(args.only.split(","))
        flagged = [r for r in flagged if wanted & set(r["flags"])]

    out = args.out or REPO / "temp" / f"{args.kind[:-1]}-cleanup-{args.env}-{today}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(flagged, indent=1, ensure_ascii=False))
    write_tsv(out.with_suffix(".tsv"), flagged)

    counts = {}
    for r in flagged:
        for flag in r["flags"]:
            counts[flag] = counts.get(flag, 0) + 1
    billed_ids = {i for e in events for i in linked_ids(e, args.kind)}
    unbilled = sum(1 for r in rows if r["id"] not in billed_ids and swept(r, args.kind))
    upcoming = sum(1 for r in flagged if r["upcoming"])
    print(f"{len(rows)} {args.kind}, {len(events)} events: {len(flagged)} flagged, {upcoming} upcoming")
    if unbilled:
        print(f"  {unbilled} unbilled rows left out: the nightly orphan sweep deletes them")
    print("  " + ", ".join(f"{flag} {n}" for flag, n in sorted(counts.items(), key=lambda kv: -kv[1])))
    print(f"wrote {out} and {out.with_suffix('.tsv').name}")
    return 0


def main() -> int:
    return collect(parse_args())


if __name__ == "__main__":
    sys.exit(main())
