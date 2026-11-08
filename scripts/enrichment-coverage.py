#!/usr/bin/env python3
"""How many promoters and venues with upcoming events carry a website, an Instagram link and a description.

Read-only. `/daily-check` prints the summary, and `/enrich` takes its next batch from `--next`.

    python3 scripts/enrichment-coverage.py                       # the summary, production
    python3 scripts/enrichment-coverage.py --next 35             # also the next 35 promoters to research, as TSV
    python3 scripts/enrichment-coverage.py --host http://localhost:18081 --site http://localhost:8080

The upcoming counts come from the public read API (`--site`). The Instagram links and the venue fields come from the
admin API (`--host`, the `ej.sh` forward); without it the summary leaves those columns out and says so.

`--next` lists the promoters with upcoming events that lack a website or a description, the most events first. It
skips a row `docs/promoters/REVIEWED.tsv` already settled: a website or `none`, a description, or a kind that gets no
description (`junk`, `artist`, `party`, `unverified`). It also skips a row `docs/promoters/SEARCHED.tsv` lists,
because a search already found nothing or only a doubtful site.
"""

import argparse
import csv
import json
import sys
import time
import urllib.error
import urllib.request

USER_AGENT = "event-junkie-spike/0.1 ( https://github.com/enorm-labs/event-junkie )"
REVIEWED = "docs/promoters/REVIEWED.tsv"
SEARCHED = "docs/promoters/SEARCHED.tsv"
SITE = "https://event-junkie.de"
HOST = "http://localhost:28081"
PAGE_SIZE = 100
MAX_PAGES = 100
PAUSE = 0.3
NO_DESCRIPTION_KINDS = {"junk", "artist", "party", "unverified"}


def get_json(url):
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def every_page(url):
    """Every item behind a paged list route. [url] carries its own query, so the page parameters append."""
    items = []
    for page in range(MAX_PAGES):
        body = get_json(f"{url}&page={page}&size={PAGE_SIZE}")
        items.extend(body.get("content", []))
        if page + 1 >= body.get("totalPages", 0):
            break
        time.sleep(PAUSE)
    return items


def share(rows, field):
    return sum(1 for r in rows if r.get(field)) * 100 // len(rows) if rows else 0


def settled(row):
    """True when a person already decided this promoter, so a search would repeat it."""
    return bool(row.get("website") or row.get("description_de")) or row.get("kind") in NO_DESCRIPTION_KINDS


def read_table(path):
    try:
        with open(path, encoding="utf-8", newline="") as handle:
            return {r["slug"]: r for r in csv.DictReader(handle, delimiter="\t")}
    except OSError:
        return {}


def main():
    parser = argparse.ArgumentParser(description="Coverage of websites, Instagram links and descriptions.")
    parser.add_argument("--site", default=SITE, help=f"public read API (default {SITE})")
    parser.add_argument("--host", default=HOST, help=f"importer admin API (default {HOST}, the production forward)")
    parser.add_argument("--next", type=int, default=0, metavar="N", help="also list the next N promoters to research")
    args = parser.parse_args()

    try:
        promoters = [
            p for p in every_page(f"{args.site}/api/promoters?sort=upcomingEvents,desc") if p.get("upcomingEventCount")
        ]
        venues = [v for v in every_page(f"{args.site}/api/venues?sort=name") if v.get("upcomingEventCount")]
    except (urllib.error.URLError, OSError) as error:
        print(f"cannot read {args.site}: {error}")
        return 1

    try:
        admin_promoters = {p["slug"]: p for p in every_page(f"{args.host}/api/admin/promoters?sort=name,asc")}
        admin_venues = {v["slug"]: v for v in every_page(f"{args.host}/api/admin/venues?sort=name,asc")}
    except (urllib.error.URLError, OSError):
        admin_promoters = admin_venues = None

    line = f"promoters with upcoming events: {len(promoters)} — website {share(promoters, 'websiteUrl')}%"
    if admin_promoters is not None:
        stored = [admin_promoters.get(p["slug"], {}) for p in promoters]
        line += f", Instagram {share(stored, 'instagramUrl')}%"
    line += f", description {share(promoters, 'description')}%"
    print(line)
    if admin_venues is None:
        print(f"venues with upcoming events: {len(venues)} — not read, {args.host} unreachable")
    else:
        stored = [admin_venues.get(v["slug"], {}) for v in venues]
        print(
            f"venues with upcoming events: {len(venues)} — website {share(stored, 'websiteUrl')}%, "
            f"Instagram {share(stored, 'instagramUrl')}%, description {share(stored, 'description')}%, "
            f"image {share(venues, 'imageUrl')}%"
        )

    if args.next:
        reviewed = read_table(REVIEWED)
        searched = read_table(SEARCHED)
        todo = [
            p
            for p in promoters
            if not (p.get("websiteUrl") and p.get("description"))
            and not settled(reviewed.get(p["slug"], {}))
            and p["slug"] not in searched
        ]
        print(f"\n{len(todo)} promoters left to research; the next {min(args.next, len(todo))}:")
        print("slug\tname\tupcoming\twebsite\tdescription")
        for p in todo[: args.next]:
            print(
                f"{p['slug']}\t{p['name']}\t{p['upcomingEventCount']}\t"
                f"{p.get('websiteUrl') or ''}\t{'yes' if p.get('description') else ''}"
            )
    return 0


if __name__ == "__main__":
    sys.exit(main())
