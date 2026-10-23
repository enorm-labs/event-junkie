#!/usr/bin/env python3
"""List a venue's upcoming hand-entered events that a new source's import collides with, and propose their deletion.

Read-only: it prints the `DELETE` calls it proposes and runs none.

    python3 scripts/manual-collisions.py production 800a-bar-cabaret interkosmos

A hand-entered row has a `manual:<venue>:…` sourceId. When a source is enabled for that venue, its import
collides with the row (#3824): a row that holds the slug makes the importer skip its own event, and a row
with another title shows the night twice. Each upcoming manual row on <env> is matched against the
imported rows of the same venue on staging and on <env>:

    SLUG   an imported row has the same slug              -> propose the deletion
    TITLE  an imported row on the same night has the same title, case and `ß` folded -> propose the deletion
    NIGHT  an imported row on the same night has another title -> the operator compares the two
    KEEP   no imported row that night: the source does not publish it, and deleting it removes the night

Staging is the reference because it imports a release first and has no manual rows. After the
deletions, force-import the source, so the events it skipped are written.

It reads the importer through the forwards `scripts/ej.sh up <env>` starts (18081 staging, 28081
production) and refuses a port no `event-junkie-<env>` port-forward holds.

Exit 0 when no row needs a decision, 1 when a row is SLUG, TITLE or NIGHT, 2 when it could not run.
"""

import argparse
import datetime
import json
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zoneinfo

PORTS = {"staging": 18081, "production": 28081}
REFERENCE = "staging"
MANUAL_PREFIX = "manual:"
EXIT_DECISION = 1
EXIT_CANNOT_RUN = 2
PAGE_SIZE = 100
MAX_PAGES = 50
PROPOSED = ("SLUG", "TITLE")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="List a venue's upcoming manual events that a new source collides with. Writes nothing."
    )
    parser.add_argument("env", choices=sorted(PORTS), help="the cluster whose manual rows to list")
    parser.add_argument("venues", nargs="+", help="venue slugs, as the files in http/importer/seed/venues/ name them")
    return parser.parse_args()


def forward_is_ours(env: str, port: int) -> bool:
    """Whether a port-forward to the importer on [port] runs under this environment's kube context."""
    listing = subprocess.run(["ps", "-eo", "args"], capture_output=True, text=True).stdout
    return any(
        "port-forward" in line and f"event-junkie-{env}" in line and f"{port}:8081" in line
        for line in listing.splitlines()
    )


def call(base: str, path: str):
    try:
        with urllib.request.urlopen(base + path, timeout=30) as response:
            return json.load(response)
    except (urllib.error.URLError, ConnectionError, TimeoutError) as error:
        raise SystemExit(f"GET {base}{path}: {error}") from error


def paged(base: str, path: str, fetch=call) -> list:
    """Every item of a paged admin listing. The API caps a page at 100; a partial read is refused."""
    out, page = [], 0
    separator = "&" if "?" in path else "?"
    while True:
        body = fetch(base, f"{path}{separator}page={page}&size={PAGE_SIZE}")
        out.extend(body["content"])
        if not body["content"] or len(out) >= body["totalElements"]:
            break
        page += 1
        if page >= MAX_PAGES:
            raise SystemExit(f"Stopped after {MAX_PAGES} pages of {path}. The listing is not terminating.")
    if len(out) != body["totalElements"]:
        raise SystemExit(f"Read {len(out)} of {body['totalElements']} from {path}. Refusing a partial listing.")
    return out


def fold(title: str) -> str:
    """The title compared: `casefold` maps `ß` to `ss`, and runs of whitespace count as one space."""
    return " ".join(title.casefold().split())


def is_manual(event: dict) -> bool:
    return event["sourceId"].startswith(MANUAL_PREFIX)


def classify(row: dict, imported: list) -> tuple:
    """The verdict for one manual [row] against the [imported] rows, and the rows it rests on."""
    same_slug = [e for e in imported if e["slug"] == row["slug"]]
    if same_slug:
        return "SLUG", same_slug
    same_night = [e for e in imported if e["eventDate"] == row["eventDate"]]
    same_title = [e for e in same_night if fold(e["title"]) == fold(row["title"])]
    if same_title:
        return "TITLE", same_title
    if same_night:
        return "NIGHT", same_night
    return "KEEP", []


def venue_ids(base: str, fetch=call) -> dict:
    return {v["slug"]: v["id"] for v in paged(base, "/api/admin/venues?sort=name,asc", fetch)}


def upcoming(base: str, venue_id: int, today: str, fetch=call) -> list:
    query = urllib.parse.urlencode({"venueId": venue_id, "from": today, "sort": "eventDate,asc"})
    return paged(base, f"/api/admin/events?{query}", fetch)


def report(env: str, slug: str, rows: list, verdicts: list) -> None:
    print(f"\n{slug} on {env}: {len(rows)} upcoming manual row(s)")
    for row, (verdict, matches) in zip(rows, verdicts, strict=True):
        print(f"  {verdict:5} id={row['id']} {row['eventDate']} {row['title']!r} slug={row['slug']}")
        for match in matches:
            print(f"        {match['where']}: {match['title']!r} slug={match['slug']} source={match['sourceId']}")


def main() -> int:
    args = parse_args()
    envs = sorted({args.env, REFERENCE})
    bases = {env: f"http://localhost:{PORTS[env]}" for env in envs}
    for env in envs:
        if not forward_is_ours(env, PORTS[env]):
            hint = f"Run: scripts/ej.sh up {env}"
            print(f"No event-junkie-{env} forward holds port {PORTS[env]}. {hint}", file=sys.stderr)
            return EXIT_CANNOT_RUN

    today = datetime.datetime.now(zoneinfo.ZoneInfo("Europe/Berlin")).date().isoformat()
    ids = {env: venue_ids(bases[env]) for env in envs}
    unknown = sorted(set(args.venues) - set(ids[args.env]))
    if unknown:
        print(f"Not a venue on {args.env}: {', '.join(unknown)}", file=sys.stderr)
        return EXIT_CANNOT_RUN

    proposed, undecided = [], 0
    for slug in args.venues:
        imported, rows = [], []
        for env in envs:
            if slug not in ids[env]:
                print(f"{slug} is not a venue on {env}; nothing to compare there.")
                continue
            events = upcoming(bases[env], ids[env][slug], today)
            imported += [{**e, "where": env} for e in events if not is_manual(e)]
            if env == args.env:
                rows = [e for e in events if is_manual(e)]
        verdicts = [classify(row, imported) for row in rows]
        report(args.env, slug, rows, verdicts)
        proposed += [row for row, (verdict, _) in zip(rows, verdicts, strict=True) if verdict in PROPOSED]
        undecided += sum(1 for verdict, _ in verdicts if verdict == "NIGHT")

    print(f"\nProposed for deletion on {args.env}: {len(proposed)}. Same night, other title: {undecided}.")
    for row in proposed:
        url = f"{bases[args.env]}/api/admin/events/{row['id']}"
        print(f"  curl -fsS -X DELETE {url}   # {row['eventDate']} {row['title']}")
    if proposed or undecided:
        print("Nothing deleted. Delete on the operator's word, then force-import the venues' sources.")
    return EXIT_DECISION if proposed or undecided else 0


if __name__ == "__main__":
    sys.exit(main())
