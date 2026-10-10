#!/usr/bin/env python3
"""Review the AMBIGUOUS MusicBrainz matches of artists who play soon, and apply the ones a person chose.

    python3 scripts/musicbrainz-review.py collect production     # temp/musicbrainz-review-production-<date>.tsv
    python3 scripts/musicbrainz-review.py collect staging --days 30
    python3 scripts/musicbrainz-review.py apply production temp/musicbrainz-review-production-<date>.tsv
    python3 scripts/musicbrainz-review.py apply production <tsv> --apply   # writes

`collect` is read-only. It reads the events of the next `--days` from the BFF and every artist from
the admin API, keeps the AMBIGUOUS ones the billing names, and asks MusicBrainz for the candidates
whose name, sort name or alias equals ours (the rule of musicbrainz-match.py, at its one request a
second). The TSV has an empty `decision` and `mbid` column per artist; the JSON beside it holds the
same rows unflattened, for a reader that judges them.

`apply` reads the rows marked `PROPOSE` with an `mbid`, and is a dry run unless given `--apply`.
Both read each artist's match first and skip a row no longer AMBIGUOUS, because someone settled it
since the file was written; a skip does not fail the run. For each other row, `--apply` sends one
`PUT /api/admin/artists/{id}/musicbrainz-id` with the lowercase MBID, which stores it as the EXACT
match and changes no other field. The read only decides the skip: nothing from it goes into the write.
A row the importer refuses (404 for an unknown id, 400 for an MBID that is not a UUID) fails the run.

Both talk to the forwards `scripts/ej.sh up <env>` starts, and `apply` refuses to write unless a
`kubectl --context event-junkie-<env>` port-forward holds the importer port.

Exit 0 on success, 1 when `apply` saw a failed write, 2 when it could not run.
"""

import argparse
import csv
import datetime
import importlib.util
import json
import pathlib
import subprocess
import sys
import urllib.error
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
PORTS = {"staging": (18081, 18080), "production": (28081, 28080)}
PAGE_SIZE = 100
MAX_PAGES = 200
EXIT_FAILED = 1
EXIT_CANNOT_RUN = 2
COLUMNS = ["decision", "artist_id", "slug", "name", "mbid", "candidates", "events"]


def parse_args(argv=None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Review AMBIGUOUS MusicBrainz matches, then apply the chosen ones.")
    sub = parser.add_subparsers(dest="command", required=True)
    collect = sub.add_parser("collect", help="write the review file; read-only")
    collect.add_argument("env", choices=sorted(PORTS))
    collect.add_argument("--days", type=int, default=14, help="how far ahead the billing is read (default 14)")
    collect.add_argument("--out", type=pathlib.Path, help="the TSV path; the JSON goes beside it")
    apply = sub.add_parser("apply", help="store the PROPOSE rows' MBIDs; dry run without --apply")
    apply.add_argument("env", choices=sorted(PORTS))
    apply.add_argument("tsv", type=pathlib.Path)
    apply.add_argument("--apply", action="store_true", help="write; without it nothing is written")
    return parser.parse_args(argv)


def matcher():
    # The hyphen in the file name rules out a plain import.
    spec = importlib.util.spec_from_file_location("musicbrainz_match", REPO / "scripts" / "musicbrainz-match.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def http(method: str, url: str, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, method=method, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
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


def billed_ambiguous(events: list, artists: list) -> list:
    """AMBIGUOUS artists the billing names, each with its events. Matched on the name, as the BFF gives no slug."""
    by_name = {}
    for event in events:
        for name in event.get("artistNames") or []:
            by_name.setdefault(name.casefold(), []).append(event)
    rows = []
    for artist in artists:
        if artist["musicbrainzMatch"] != "AMBIGUOUS" or artist["name"].casefold() not in by_name:
            continue
        rows.append(
            {
                "artist_id": artist["id"],
                "slug": artist["slug"],
                "name": artist["name"],
                "events": [
                    {
                        "slug": e["slug"],
                        "date": e["eventDate"],
                        "type": e.get("eventType"),
                        "venue": (e.get("venue") or {}).get("name"),
                        "title": e.get("title"),
                        "billing": e.get("artistNames"),
                        "genres": [g.get("name") if isinstance(g, dict) else g for g in e.get("genreTags") or []],
                    }
                    for e in by_name[artist["name"].casefold()]
                ],
            }
        )
    return sorted(rows, key=lambda r: (min(e["date"] for e in r["events"]), r["slug"]))


def candidates(mb, name: str) -> list:
    wanted = mb.fold(name)
    return [
        {
            "mbid": c["id"],
            "name": c.get("name"),
            "type": c.get("type"),
            "country": c.get("country"),
            "area": (c.get("area") or {}).get("name"),
            "disambiguation": c.get("disambiguation"),
            "begin": (c.get("life-span") or {}).get("begin"),
            "ended": (c.get("life-span") or {}).get("ended"),
            "tags": [t["name"] for t in sorted(c.get("tags", []), key=lambda t: -t.get("count", 0))][:6],
        }
        for c in mb.search(name)
        if any(mb.fold(n) == wanted for n in mb.names_of(c) if n)
    ]


def flat_candidate(c: dict) -> str:
    parts = [c["mbid"], c.get("type"), c.get("country") or c.get("area"), c.get("disambiguation"), c.get("begin")]
    return " · ".join(str(p) for p in parts if p) + (f" [{', '.join(c['tags'])}]" if c.get("tags") else "")


def flat_event(e: dict) -> str:
    return f"{e['date']} {e['venue']}: {e['title']}"


def collect(args) -> int:
    importer, bff = (f"http://localhost:{port}" for port in PORTS[args.env])
    today = datetime.date.today()
    get = lambda url: http("GET", url)  # noqa: E731
    try:
        events = pages(get, f"{bff}/api/events?from={today}&to={today + datetime.timedelta(days=args.days)}")
        artists = pages(get, f"{importer}/api/admin/artists")
    except (urllib.error.URLError, OSError) as error:
        print(f"musicbrainz-review: the {args.env} forwards do not answer ({error}) — run scripts/ej.sh up {args.env}")
        return EXIT_CANNOT_RUN

    rows = billed_ambiguous(events, artists)
    print(f"{len(events)} events in {args.days} days, {len(rows)} AMBIGUOUS artists billed; asking MusicBrainz")
    mb = matcher()
    for row in rows:
        row["candidates"] = candidates(mb, row["name"])

    out = args.out or REPO / "temp" / f"musicbrainz-review-{args.env}-{today}.tsv"
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=COLUMNS, delimiter="\t")
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    "decision": "",
                    "artist_id": row["artist_id"],
                    "slug": row["slug"],
                    "name": row["name"],
                    "mbid": "",
                    "candidates": " ;; ".join(flat_candidate(c) for c in row["candidates"]),
                    "events": " ;; ".join(flat_event(e) for e in row["events"]),
                }
            )
    out.with_suffix(".json").write_text(json.dumps(rows, indent=1, ensure_ascii=False))
    print(f"wrote {out} and {out.with_suffix('.json').name}")
    return 0


def forward_is_ours(env: str, port: int) -> bool:
    listing = subprocess.run(["ps", "-eo", "args"], capture_output=True, text=True).stdout
    return any(
        "port-forward" in line and f"event-junkie-{env}" in line and f"{port}:8081" in line
        for line in listing.splitlines()
    )


def proposals(tsv: pathlib.Path) -> list:
    with tsv.open(newline="") as f:
        return [
            (int(r["artist_id"]), r["mbid"].strip())
            for r in csv.DictReader(f, delimiter="\t")
            if r["decision"].strip().upper() == "PROPOSE" and r["mbid"].strip()
        ]


def apply_rows(rows: list, get, put, write: bool) -> int:
    """Stores each (artist id, MBID) still AMBIGUOUS; returns how many rows failed. A skipped row is no failure."""
    bad = skipped = 0
    for artist_id, mbid in rows:
        mbid = mbid.lower()
        try:
            current = get(artist_id)
            match = current["musicbrainzMatch"]
            if match != "AMBIGUOUS":
                skipped += 1
                print(f"skipped {artist_id}: no longer AMBIGUOUS ({match})")
                continue
            if not write:
                print(f"{artist_id} {current['name']}: would store {mbid}")
                continue
            after = put(artist_id, {"musicbrainzId": mbid})
        except urllib.error.HTTPError as error:
            bad += 1
            print(f"{artist_id}: NOT stored {mbid}, the importer answered {error.code}")
            continue
        stored = after.get("musicbrainzMatch") == "EXACT" and after.get("musicbrainzId") == mbid
        if not stored:
            bad += 1
        state = "stored" if stored else f"NOT stored ({after.get('musicbrainzMatch')})"
        print(f"{artist_id} {after['name']}: {state} {mbid}")
    print(f"{len(rows)} rows: {skipped} skipped, {bad} failed")
    return bad


def apply(args) -> int:
    port = PORTS[args.env][0]
    if args.apply and not forward_is_ours(args.env, port):
        print(f"musicbrainz-review: no event-junkie-{args.env} forward holds {port} — run scripts/ej.sh up {args.env}")
        return EXIT_CANNOT_RUN
    rows = proposals(args.tsv)
    print(f"{len(rows)} PROPOSE rows in {args.tsv}" + ("" if args.apply else " — dry run, nothing is written"))
    base = f"http://localhost:{port}/api/admin/artists/"
    try:
        bad = apply_rows(
            rows,
            lambda i: http("GET", f"{base}{i}"),
            lambda i, b: http("PUT", f"{base}{i}/musicbrainz-id", b),
            args.apply,
        )
    except (urllib.error.URLError, OSError) as error:
        print(f"musicbrainz-review: the importer on {port} does not answer ({error})")
        return EXIT_CANNOT_RUN
    return EXIT_FAILED if bad else 0


def main() -> int:
    args = parse_args()
    return collect(args) if args.command == "collect" else apply(args)


if __name__ == "__main__":
    sys.exit(main())
