#!/usr/bin/env python3
"""Measure how many of our artist rows MusicBrainz knows, under one written-down match rule (#1549).

    python3 scripts/musicbrainz-match.py                          # every artist on the forwarded importer
    python3 scripts/musicbrainz-match.py --control                # only the names the closed defects settled
    python3 scripts/musicbrainz-match.py --limit 50               # a taste, before the two-hour run
    python3 scripts/musicbrainz-match.py --resume                 # carry on where a killed run stopped
    python3 scripts/musicbrainz-match.py --host http://localhost:28081 --out temp/mb-production.tsv

Read-only on both sides: it reads the artists through the admin API and asks MusicBrainz's search
endpoint, and it writes one TSV row per artist, nothing else. The decision in #1549 wants three
numbers -- `exact`, `ambiguous`, `none` -- and this is what produces them. It proposes nothing and
renames nothing.

The match rule, which the ADR quotes:

1. A candidate counts only when its folded name, sort name or an alias equals the folded query.
   The search's own score does not: `Pici` returns `Pici Mazzei` at score 100.
2. One candidate is `exact`. Several are narrowed to the German ones; exactly one left is `exact`
   too, and its row says so in `tiebreak`. Anything else is `ambiguous`.
3. A name with a dash or colon that matches nothing is queried again by its head alone, and that
   verdict is reported in `head_state`. `Current 93 - Sonic Morgue` is a series glued to an act
   (#302); the head pass is the detector, and its false-positive count is what says whether it is
   usable.

The importer runs this rule for real since #1567 (`MusicBrainzMatcher.kt`), with one tightening the
spike's numbers argued for: there, a sort-name or alias hit alone is `ambiguous`, never `exact`.

MusicBrainz allows one request a second per address and wants a User-Agent it can write to, so the
pause below is not tunable and a 503 is slept off rather than retried at once. Standard library only.
The run is long -- a second per row, and staging held 5,730 rows on 2026-09-17 -- so every row is
flushed as it is written and `--resume` skips the names already in the file.
"""

import argparse
import csv
import json
import pathlib
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from match_spike import CONTROL, done_names, fetch_artist_names, head_of, write_rows  # noqa: E402

MB_SEARCH = "https://musicbrainz.org/ws/2/artist/"
USER_AGENT = "event-junkie-spike/0.1 ( https://github.com/enorm-labs/event-junkie )"
PAUSE_SECONDS = 1.1
BACKOFF_SECONDS = 5
MAX_ATTEMPTS = 6
CANDIDATES = 10

FIELDS = [
    "name",
    "folded",
    "state",
    "tiebreak",
    "mbid",
    "mb_name",
    "type",
    "country",
    "score",
    "candidates",
    "head",
    "head_state",
    "head_mbid",
    "head_mb_name",
]


def fold(s):
    """Casefold, strip accents, collapse whitespace, and read `&` and `and` as one word."""
    n = unicodedata.normalize("NFKD", s)
    n = "".join(c for c in n if not unicodedata.combining(c)).casefold()
    n = re.sub(r"\s+", " ", n).strip(" ")
    # Runs on collapsed, trimmed text, so a name's last word is never a conjunction however it is padded.
    # The lookahead leaves the space after a conjunction for the next one: "a und and b" is "a & & b".
    n = re.sub(r" (?:and|und|&)(?= )", " &", n)
    return n.strip(" .,!")


def lucene_phrase(name):
    escaped = name.replace("\\", "\\\\").replace('"', '\\"')
    return f'artist:"{escaped}"'


def get_json(url):
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def search(name):
    """The candidates MusicBrainz returns for one name, after the pause the service asks for."""
    url = MB_SEARCH + "?" + urllib.parse.urlencode({"query": lucene_phrase(name), "fmt": "json", "limit": CANDIDATES})
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            body = get_json(url)
            time.sleep(PAUSE_SECONDS)
            return body.get("artists", [])
        except urllib.error.HTTPError as error:
            if error.code != 503 or attempt == MAX_ATTEMPTS:
                raise
        except urllib.error.URLError:
            if attempt == MAX_ATTEMPTS:
                raise
        time.sleep(BACKOFF_SECONDS * attempt)
    return []


def names_of(candidate):
    yield candidate.get("name", "")
    yield candidate.get("sort-name", "")
    for alias in candidate.get("aliases", []):
        yield alias.get("name", "")


def decide(name, candidates):
    """Apply rules 1 and 2. Returns (state, tiebreak, chosen or None)."""
    wanted = fold(name)
    equal = [c for c in candidates if any(fold(n) == wanted for n in names_of(c) if n)]
    if not equal:
        return "none", "", None
    if len(equal) == 1:
        return "exact", "", equal[0]
    german = [c for c in equal if c.get("country") == "DE"]
    if len(german) == 1:
        return "exact", "country=DE", german[0]
    return "ambiguous", "", None


def match(name):
    candidates = search(name)
    state, tiebreak, chosen = decide(name, candidates)
    row = {
        "name": name,
        "folded": fold(name),
        "state": state,
        "tiebreak": tiebreak,
        "mbid": chosen.get("id", "") if chosen else "",
        "mb_name": chosen.get("name", "") if chosen else "",
        "type": chosen.get("type", "") if chosen else "",
        "country": chosen.get("country", "") if chosen else "",
        "score": chosen.get("score", "") if chosen else "",
        "candidates": " | ".join(
            f"{c.get('name', '')} [{c.get('type', '?')}, {c.get('country', '?')}]" for c in candidates
        ),
        "head": "",
        "head_state": "",
        "head_mbid": "",
        "head_mb_name": "",
    }
    head = head_of(name) if state == "none" else ""
    if head:
        head_state, _, head_chosen = decide(head, search(head))
        row.update(
            head=head,
            head_state=head_state,
            head_mbid=head_chosen.get("id", "") if head_chosen else "",
            head_mb_name=head_chosen.get("name", "") if head_chosen else "",
        )
    return row


def summarize(path):
    with open(path, encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    total = len(rows)
    states = {s: sum(1 for r in rows if r["state"] == s) for s in ("exact", "ambiguous", "none")}
    tiebroken = sum(1 for r in rows if r["tiebreak"])
    heads = sum(1 for r in rows if r["head"])
    head_states = {
        s: sum(1 for r in rows if r["head"] and r["head_state"] == s) for s in ("exact", "ambiguous", "none")
    }
    print(f"rows {total}")
    for state, count in states.items():
        print(f"  {state:<10}{count:>6}  {100 * count / total if total else 0:5.1f}%")
    print(f"  of the exact, decided by country=DE: {tiebroken}")
    print(f"head pass on {heads} dashed or coloned names that matched nothing:")
    for state, count in head_states.items():
        print(f"  {state:<10}{count:>6}")


def main():
    parser = argparse.ArgumentParser(description="Match every artist row against MusicBrainz and count the outcome.")
    parser.add_argument(
        "--host", default="http://localhost:18081", help="the importer's admin API (default: staging's forward)"
    )
    parser.add_argument("--out", default="temp/musicbrainz-match.tsv", help="the TSV to write (default: %(default)s)")
    parser.add_argument("--limit", type=int, help="stop after this many artists")
    parser.add_argument(
        "--control", action="store_true", help="run only the closed-defect names, as the false-positive check"
    )
    parser.add_argument("--resume", action="store_true", help="skip names already in --out and append")
    args = parser.parse_args()

    names = CONTROL if args.control else fetch_artist_names(get_json, args.host)
    names = [n for n in names if fold(n)]
    done = done_names(args.out) if args.resume else set()
    todo = [n for n in names if n not in done]
    if args.limit:
        todo = todo[: args.limit]
    print(f"{len(names)} names, {len(done)} done, {len(todo)} to query at ~{PAUSE_SECONDS}s each", file=sys.stderr)

    write_rows(args.out, FIELDS, todo, match, append=bool(args.resume and done))
    summarize(args.out)


if __name__ == "__main__":
    main()
