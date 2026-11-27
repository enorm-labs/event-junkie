#!/usr/bin/env python3
"""What `musicbrainz-match.py` and `discogs-match.py` share: control names, head split, artist list, TSV run.

Usage:
    import match_spike    # from a sibling script, after sys.path.insert(0, <scripts/>)

Imported, not run: run alone it prints this help. Standard library only. Each script keeps its own `fold`, search and
rule, because the two indexes differ there.
"""

import argparse
import csv
import os
import re
import sys

PAGE_SIZE = 100
MAX_PAGES = 200

# Names the closed defects already settled as not artists. Every one must come back `none`; one that
# does not is a false positive of the rule, and the count of those is the second number #1549 wants.
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


def head_of(name):
    """The part before the first spaced dash or colon, or empty when the name has no such split."""
    parts = HEAD_SEPARATOR.split(name, maxsplit=1)
    if len(parts) == 2 and parts[0].strip() and parts[1].strip():
        return parts[0].strip()
    return ""


def fetch_artist_names(get, host):
    """Every artist name on the importer at `host`, by name. `get` is the calling script's GET."""
    names = []
    for page in range(MAX_PAGES):
        body = get(f"{host}/api/admin/artists?page={page}&size={PAGE_SIZE}&sort=name,asc")
        names.extend(a["name"] for a in body.get("content", []))
        if page + 1 >= body.get("totalPages", 0):
            break
    return names


def done_names(path):
    """The names an earlier run already wrote to `path`, for `--resume`."""
    if not os.path.exists(path):
        return set()
    with open(path, encoding="utf-8", newline="") as handle:
        return {row["name"] for row in csv.DictReader(handle, delimiter="\t")}


def write_rows(path, fields, todo, match, append):
    """Match each name and write its row, flushed one by one, so a killed run loses at most one row."""
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "a" if append else "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, delimiter="\t")
        if not append:
            writer.writeheader()
        for index, name in enumerate(todo, 1):
            row = match(name)
            writer.writerow(row)
            handle.flush()
            verdict = row["state"] + (f" (head {row['head_state']})" if row["head"] else "")
            print(f"{index}/{len(todo)}  {name!r} -> {verdict}", file=sys.stderr)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.parse_args()
    parser.print_help()
