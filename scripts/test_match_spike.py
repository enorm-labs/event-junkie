#!/usr/bin/env python3
"""Asserts what match_spike.py splits, pages and writes for the two match scripts.

Usage:
    python3 scripts/test_match_spike.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failed run.
validate-python.yml runs it. Reaches no network, and writes only under a temporary directory.
"""

import argparse
import csv
import io
import pathlib
import sys
import tempfile
from contextlib import redirect_stderr

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import match_spike  # noqa: E402


def main() -> int:
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    checks = 0

    def check(condition, message):
        nonlocal checks
        if not condition:
            print(f"FAIL: {message}")
            sys.exit(1)
        checks += 1

    check(match_spike.head_of("Current 93 – Sonic Morgue") == "Current 93", "a spaced en dash splits off the head")
    check(match_spike.head_of("Drone Art Show: Harry Potter") == "Drone Art Show", "a colon and a space split")
    check(match_spike.head_of("Jay-Z") == "", "an unspaced hyphen is part of the name")
    check(match_spike.head_of("Sonic Morgue") == "", "a name with no separator has no head")
    check(match_spike.head_of("Act - ") == "", "a separator with nothing after it is no split")

    check(match_spike.fold("Motörhead") == match_spike.fold("MOTORHEAD"), "case and accents fold away")
    check(match_spike.fold("Simon and Garfunkel") == "simon & garfunkel", "`and` reads as `&`")
    check(match_spike.fold("Rock und and Roll") == "rock & & roll", "two conjunctions in a row both fold")
    check(match_spike.fold("Mike and ") == "mike and", "a trailing conjunction is the last word, not `&`")
    check(match_spike.fold("  Sadtember!  ") == "sadtember", "padding and trailing punctuation go")

    asked = []

    def get(url):
        asked.append(url)
        page = int(url.split("page=")[1].split("&")[0])
        return {"content": [{"name": f"Act {page}"}] * (100 - 50 * page), "totalElements": 150}

    names = match_spike.fetch_artist_names(get, "http://localhost:18081")
    check(names == ["Act 0"] * 100 + ["Act 1"] * 50, "every page's names are read, in order")
    check(len(asked) == 2, "the reading stops at totalElements")
    check(asked[0] == "http://localhost:18081/api/admin/artists?sort=name,asc&page=0&size=100", "the list URL")

    def match(name):
        return {"name": name, "state": "none", "head": "", "head_state": ""}

    with tempfile.TemporaryDirectory() as tmp:
        out = str(pathlib.Path(tmp) / "nested" / "match.tsv")
        check(match_spike.done_names(out) == set(), "a missing file has no names done")
        fields = ["name", "state", "head", "head_state"]
        with redirect_stderr(io.StringIO()) as progress:
            match_spike.write_rows(out, fields, ["A", "B"], match, append=False)
            match_spike.write_rows(out, fields, ["C"], match, append=True)
        with open(out, encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle, delimiter="\t"))
        check([r["name"] for r in rows] == ["A", "B", "C"], "a fresh run writes a header, a resumed one appends")
        check(match_spike.done_names(out) == {"A", "B", "C"}, "the names written are the names done")
        check("1/2  'A' -> none" in progress.getvalue(), "each row reports its verdict")

    print(f"match-spike: {checks} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
