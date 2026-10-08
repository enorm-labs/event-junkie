#!/usr/bin/env python3
"""Ask Openverse whether an openly licensed photograph exists for a venue that has none (#1285).

    python3 scripts/openverse-search.py                       # every venue still without a picture
    python3 scripts/openverse-search.py --venue Supamolly     # one venue
    python3 scripts/openverse-search.py --out results.json    # keep the candidates, not just the count

Openverse aggregates Flickr, Europeana, Smithsonian and more behind one API, so one pass answers
"is there supply outside Wikimedia Commons". A Commons hit is not new supply, because
`docs/venue-images/README.md` records Commons as exhausted, so results are split by source.

The venue list comes from `docs/venue-images/REVIEWED.tsv`: every row that is not CONFIRMED. That
includes a WITHDRAWN row, whose picture an archive removed after a person confirmed it.

**This proposes, it never decides.** A title match is not a photograph of the venue -- the first run
scored 19 hits for `LARK` and every one was a hashtag. A person reads the candidates and writes the
verdict into `REVIEWED.tsv`, and `scripts/venue-images.py` writes the confirmed rows onto the venues.

Standard library only, and no key: the Openverse API needs none. Anonymous callers get 20 requests a
minute and 200 a day, which is why the pause below is not tunable.
"""

import argparse
import csv
import json
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

REVIEWED = "docs/venue-images/REVIEWED.tsv"
API = "https://api.openverse.org/v1/images/"
AGENT = "event-junkie/1.0 (https://github.com/enorm-labs/event-junkie)"

# What the site can render a credit for. `by-nc` and `by-nd` are absent on purpose. `nd` because a
# derivative is exactly what the site serves. `nc` because #480 has not decided how the running
# costs get covered, and a picture usable only while the answer stays non-commercial is a picture
# that expires on a decision nobody has taken yet.
LICENCES = "by,by-sa,cc0,pdm"

COMMONS_SOURCES = frozenset({"wikimedia", "wikimedia_commons"})
PAGE_SIZE = 20

# Openverse allows 20 requests a minute to an anonymous caller. One venue is one request.
PAUSE_SECONDS = 3.5

# Words every second Berlin venue carries, so a title matching only these has matched nothing.
STOPWORDS = frozenset({"club", "berlin", "der", "die", "das", "im", "the", "neue", "culture", "raum", "bar"})


def fold(text):
    """Casefold and strip accents, so `Parkbühne` matches `Parkbuhne` in a title."""
    normalized = unicodedata.normalize("NFKD", text.lower())
    stripped = "".join(c for c in normalized if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", " ", stripped).strip()


def identifying_words(name):
    words = [w for w in fold(name).split() if len(w) > 2]
    strong = [w for w in words if w not in STOPWORDS]
    return strong or words


def search(query):
    url = API + "?" + urllib.parse.urlencode({"q": query, "license": LICENCES, "page_size": PAGE_SIZE})
    request = urllib.request.Request(url, headers={"User-Agent": AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def probe(name):
    """Every result whose title or tags carry the venue's identifying words."""
    needed = identifying_words(name)
    data = search(f'"{name}" Berlin')
    hits = []
    for row in data.get("results", []):
        tags = [t.get("name", "") for t in row.get("tags") or []]
        haystack = fold(" ".join([row.get("title") or "", *tags]))
        if not all(word in haystack for word in needed):
            continue
        hits.append(
            {
                "title": row.get("title"),
                "source": row.get("source"),
                "creator": row.get("creator"),
                "licence": f"{row.get('license')} {row.get('license_version')}".strip(),
                "landscape": (row.get("width") or 0) > (row.get("height") or 0),
                "page": row.get("foreign_landing_url"),
                "url": row.get("url"),
            }
        )
    return {"venue": name, "total": data.get("result_count", 0), "hits": hits}


def venues_without_a_picture(only=None):
    with open(REVIEWED, encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE))
    names = [r["venue"] for r in rows if r["decision"] != "CONFIRMED"]
    if only:
        names = [n for n in names if fold(n) == fold(only)]
    return names


def main():
    parser = argparse.ArgumentParser(description="Search Openverse for a venue photograph.")
    parser.add_argument("--venue", help="only this venue, by name")
    parser.add_argument("--out", help="write the candidates to this file as JSON")
    args = parser.parse_args()

    names = venues_without_a_picture(args.venue)
    if not names:
        print(f"no venue without a picture in {REVIEWED}" + (f" for {args.venue!r}" if args.venue else ""))
        return 1
    print(f"{len(names)} venue(s) without a picture\n")

    results, elsewhere = [], 0
    for index, name in enumerate(names, 1):
        try:
            result = probe(name)
        except (urllib.error.URLError, OSError) as error:
            result = {"venue": name, "total": 0, "hits": [], "error": str(error)}
        results.append(result)

        new = [h for h in result["hits"] if h["source"] not in COMMONS_SOURCES]
        elsewhere += len(new)
        problem = f" FAILED {result['error']}" if result.get("error") else ""
        print(
            f"  {index:3}/{len(names)} {name:<34} raw={result['total']:>4} "
            f"named={len(result['hits']):>2} not-commons={len(new):>2}{problem}",
            flush=True,
        )
        if index < len(names):
            time.sleep(PAUSE_SECONDS)

    print(f"\n{elsewhere} candidate(s) outside Commons, across {len(names)} venue(s)")
    print("A title match is not a photograph of the venue. Somebody has to look.")
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump(results, handle, ensure_ascii=False, indent=2)
        print(f"candidates written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
