#!/usr/bin/env python3
"""Measure how many of our artist rows Discogs knows, under the rule `musicbrainz-match.py` uses (#1549).

    python3 scripts/discogs-match.py                              # every artist on the forwarded importer
    python3 scripts/discogs-match.py --control                    # only the names the closed defects settled
    python3 scripts/discogs-match.py --limit 50                   # a taste, before the four-hour run
    python3 scripts/discogs-match.py --resume                     # carry on where a killed run stopped
    python3 scripts/discogs-match.py --compare temp/musicbrainz-match.tsv   # cross-table against MusicBrainz

Read-only on both sides: it reads the artists through the admin API and asks Discogs' database
search, and it writes one TSV row per artist. The question is whether Discogs recovers the rows
MusicBrainz reports as `none` -- DJs and producers with releases but no MusicBrainz entry -- so the
cross-table against the MusicBrainz TSV is the number that matters, and with `--compare` the
MusicBrainz `none` rows are queried first.

The match rule is rule 1 of the MusicBrainz spike, on what the search returns: a candidate counts
only when its folded title equals the folded query. Discogs' search is fuzzy (`Boris Brejcha` also
returns `Luke Mandala`), so the search's own ranking decides nothing. Discogs disambiguates homonyms
with a numeric suffix -- `Hanzel (2)` -- which is stripped before folding, so every homonym is a
candidate and the row is `ambiguous`. There is no country to tie-break on and no aliases in a search
result; both need `/artists/{id}`, one more request per candidate, which the spike does not spend.
The head pass for dashed or coloned names is the same as MusicBrainz's.

Discogs throttles by address: 25 requests a minute unauthenticated, 60 with a personal token
(`DISCOGS_TOKEN`, from Settings > Developers on any account), and wants a User-Agent. A 429 is
slept off. Standard library only; every row is flushed and `--resume` skips the names already done.
"""

import argparse
import csv
import json
import os
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

SEARCH = "https://api.discogs.com/database/search"
USER_AGENT = "event-junkie-spike/0.1 +https://github.com/enorm-labs/event-junkie"
TOKEN = os.environ.get("DISCOGS_TOKEN", "")
PAUSE_SECONDS = 1.05 if TOKEN else 2.5
RATE_LIMITED_SLEEP = 65
MAX_ATTEMPTS = 6
PAGE_SIZE = 100
MAX_PAGES = 200
CANDIDATES = 25

CONTROL = [
    "Kein Bock auf Nazis",  # 1110
    "DLTLLY",  # 1135
    "Vinyl Reduction",  # 1134
    "Sadtember",  # 1132
    "Sonic Morgue",  # 302
    "Current 93 – Sonic Morgue",  # 302, the head pass should find Current 93
    "Drone Art Show: Harry Potter",  # 306
    "Taschenlampenweihnachtskonzert",  # 306
    "Corrupted Blood Club Show",  # 350
]

HEAD_SEPARATOR = re.compile(r"\s+[-–—]\s+|:\s+")
HOMONYM_SUFFIX = re.compile(r"\s*\(\d+\)$")
FIELDS = [
    "name",
    "folded",
    "state",
    "discogs_id",
    "discogs_name",
    "candidates",
    "head",
    "head_state",
    "head_discogs_id",
    "head_discogs_name",
]


def fold(s):
    """Casefold, strip accents, collapse whitespace, and read `&` and `and` as one word."""
    n = unicodedata.normalize("NFKD", s)
    n = "".join(c for c in n if not unicodedata.combining(c)).casefold()
    n = re.sub(r"\s+(?:and|und|&)\s+", " & ", n)
    return re.sub(r"\s+", " ", n).strip(" .,!")


def get_json(url):
    headers = {"Accept": "application/json", "User-Agent": USER_AGENT}
    if TOKEN:
        headers["Authorization"] = f"Discogs token={TOKEN}"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def search(name):
    """The artist candidates Discogs returns for one name, after the pause the rate limit asks for."""
    url = SEARCH + "?" + urllib.parse.urlencode({"q": name, "type": "artist", "per_page": CANDIDATES})
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            body = get_json(url)
            time.sleep(PAUSE_SECONDS)
            return [r for r in body.get("results", []) if r.get("type") == "artist"]
        except urllib.error.HTTPError as error:
            if error.code == 429:
                time.sleep(RATE_LIMITED_SLEEP)
                continue
            if error.code < 500 or attempt == MAX_ATTEMPTS:
                raise
        except urllib.error.URLError:
            if attempt == MAX_ATTEMPTS:
                raise
        time.sleep(5 * attempt)
    return []


def title_of(candidate):
    return HOMONYM_SUFFIX.sub("", candidate.get("title", ""))


def decide(name, candidates):
    """Rule 1 on titles. Returns (state, chosen or None)."""
    wanted = fold(name)
    equal = [c for c in candidates if fold(title_of(c)) == wanted]
    if not equal:
        return "none", None
    if len(equal) == 1:
        return "exact", equal[0]
    return "ambiguous", None


def head_of(name):
    parts = HEAD_SEPARATOR.split(name, maxsplit=1)
    if len(parts) == 2 and parts[0].strip() and parts[1].strip():
        return parts[0].strip()
    return ""


def match(name):
    candidates = search(name)
    state, chosen = decide(name, candidates)
    row = {
        "name": name,
        "folded": fold(name),
        "state": state,
        "discogs_id": chosen.get("id", "") if chosen else "",
        "discogs_name": chosen.get("title", "") if chosen else "",
        "candidates": " | ".join(c.get("title", "") for c in candidates),
        "head": "",
        "head_state": "",
        "head_discogs_id": "",
        "head_discogs_name": "",
    }
    head = head_of(name) if state == "none" else ""
    if head:
        head_state, head_chosen = decide(head, search(head))
        row.update(
            head=head,
            head_state=head_state,
            head_discogs_id=head_chosen.get("id", "") if head_chosen else "",
            head_discogs_name=head_chosen.get("title", "") if head_chosen else "",
        )
    return row


def fetch_artist_names(host):
    names = []
    for page in range(MAX_PAGES):
        body = get_json(f"{host}/api/admin/artists?page={page}&size={PAGE_SIZE}&sort=name,asc")
        names.extend(a["name"] for a in body.get("content", []))
        if page + 1 >= body.get("totalPages", 0):
            break
    return names


def read_rows(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def summarize(path, compare):
    rows = read_rows(path)
    total = len(rows)
    print(f"rows {total}")
    for state in ("exact", "ambiguous", "none"):
        count = sum(1 for r in rows if r["state"] == state)
        print(f"  {state:<10}{count:>6}  {100 * count / total if total else 0:5.1f}%")
    heads = [r for r in rows if r["head"]]
    print(f"head pass on {len(heads)} dashed or coloned names that matched nothing:")
    for state in ("exact", "ambiguous", "none"):
        print(f"  {state:<10}{sum(1 for r in heads if r['head_state'] == state):>6}")
    if not compare:
        return
    other = {r["name"]: r["state"] for r in read_rows(compare)}
    both = [(r["state"], other[r["name"]]) for r in rows if r["name"] in other]
    print(f"cross-table over {len(both)} rows in both files (discogs down, musicbrainz across):")
    states = ("exact", "ambiguous", "none")
    print(f"  {'':<10}" + "".join(f"{s:>10}" for s in states))
    for d in states:
        print(f"  {d:<10}" + "".join(f"{sum(1 for x, y in both if x == d and y == m):>10}" for m in states))


def main():
    parser = argparse.ArgumentParser(description="Match every artist row against Discogs and count the outcome.")
    parser.add_argument(
        "--host", default="http://localhost:18081", help="the importer's admin API (default: staging's forward)"
    )
    parser.add_argument("--out", default="temp/discogs-match.tsv", help="the TSV to write (default: %(default)s)")
    parser.add_argument("--limit", type=int, help="stop after this many artists")
    parser.add_argument(
        "--control", action="store_true", help="run only the closed-defect names, as the false-positive check"
    )
    parser.add_argument("--resume", action="store_true", help="skip names already in --out and append")
    parser.add_argument("--compare", help="the MusicBrainz TSV: query its `none` rows first and print the cross-table")
    args = parser.parse_args()

    names = CONTROL if args.control else fetch_artist_names(args.host)
    names = [n for n in names if fold(n)]
    if args.compare:
        other = {r["name"]: r["state"] for r in read_rows(args.compare)}
        names.sort(key=lambda n: other.get(n) != "none")
    done = {r["name"] for r in read_rows(args.out)} if args.resume else set()
    todo = [n for n in names if n not in done]
    if args.limit:
        todo = todo[: args.limit]
    print(f"{len(names)} names, {len(done)} done, {len(todo)} to query at ~{PAUSE_SECONDS}s each", file=sys.stderr)

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    mode = "a" if args.resume and done else "w"
    with open(args.out, mode, encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS, delimiter="\t")
        if mode == "w":
            writer.writeheader()
        for index, name in enumerate(todo, 1):
            row = match(name)
            writer.writerow(row)
            handle.flush()
            verdict = row["state"] + (f" (head {row['head_state']})" if row["head"] else "")
            print(f"{index}/{len(todo)}  {name!r} -> {verdict}", file=sys.stderr)
    summarize(args.out, args.compare)


if __name__ == "__main__":
    main()
