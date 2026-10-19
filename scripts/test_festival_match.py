#!/usr/bin/env python3
"""Asserts what festival_match.py proposes from a saved programme page, and that it only reads.

Usage:
    python3 scripts/test_festival_match.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failed one.
validate-python.yml runs it. Reaches no network; writes only a TSV into a temporary directory.
"""

import argparse
import csv
import datetime
import pathlib
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import festival_match  # noqa: E402

FIXTURE = HERE / "festival-match-fixture.html"
PROGRAMME = "https://tagderclubkultur.berlin/programm/"
FESTIVAL = {"id": 7, "slug": "tag-der-clubkultur-2026", "firstDay": "2026-10-02", "lastDay": "2026-10-11"}
VENUES = [
    {"id": 1, "name": "KitKat Club", "address": "Köpenicker Str. 76"},
    {"id": 2, "name": "Goldengate", "address": "Schillingbrücke 3"},
    {"id": 3, "name": "Kulturhaus Mitte", "address": "Chausseestr. 131"},
    {"id": 4, "name": "Madame Claude", "address": "Lübbener Str. 19"},
    {"id": 5, "name": "://about blank", "address": "Markgrafendamm 24c"},
    {"id": 6, "name": "SO36", "address": "Oranienstr. 190"},
]
EVENTS = {
    "2026-10-02": [
        {"id": 10, "venueId": 1, "title": "Four Play", "eventDate": "2026-10-02", "startTime": "20:00"},
        {"id": 11, "venueId": 6, "title": "Elsewhere", "eventDate": "2026-10-02", "startTime": None},
    ],
    "2026-10-03": [
        {"id": 21, "venueId": 2, "title": "Nachspielzeit", "eventDate": "2026-10-03", "startTime": "12:00"},
        {"id": 20, "venueId": 2, "title": "Wohnzimmer030", "eventDate": "2026-10-03", "festivalId": 7},
        {"id": 22, "venueId": 5, "title": "Tanzflächen", "eventDate": "2026-10-03", "startTime": "17:00"},
    ],
    "2026-10-10": [{"id": 30, "venueId": 3, "title": "The Night of Many", "eventDate": "2026-10-10"}],
}


def fake_get(seen):
    def get(url):
        seen.append(url)
        path, _, query = url.partition("?")
        params = dict(p.split("=", 1) for p in query.split("&"))
        if path.endswith("/api/admin/festivals"):
            content = [FESTIVAL]
        elif path.endswith("/api/admin/venues"):
            content = VENUES
        elif path.endswith("/api/admin/events"):
            content = EVENTS.get(params["date"], [])
        else:
            raise AssertionError(f"unexpected read {url}")
        return {"content": content, "totalPages": 1}

    return get


def main() -> int:
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    checks = 0
    first, last = datetime.date(2026, 10, 2), datetime.date(2026, 10, 11)

    entries = festival_match.read_programme(FIXTURE.read_text(encoding="utf-8"), PROGRAMME, first, last)
    assert len(entries) == 8, f"one entry per thumbnail, the nav link left out: {len(entries)}"
    assert entries[2]["title"] == "J. Mahon’s SPOOKFEST", "the twice-escaped entity is read as its character"
    assert entries[1]["url"] == "https://tagderclubkultur.berlin/subverted-klem/", "a relative link joins the page URL"
    assert entries[3]["place"] == "Goldengate", "the place loses its trailing space"
    assert entries[6]["date"] == datetime.date(2026, 10, 3), "an opening line gives the first date"
    assert entries[5]["date"] is None, "tba is no date"
    checks += 6

    assert festival_match.entry_date("Do, 30.12.", datetime.date(2026, 12, 28), datetime.date(2027, 1, 3)) == (
        datetime.date(2026, 12, 30)
    ), "a December date takes the first day's year"
    assert festival_match.entry_date("Fr, 01.01.", datetime.date(2026, 12, 28), datetime.date(2027, 1, 3)) == (
        datetime.date(2027, 1, 1)
    ), "a January date in a festival over New Year takes the next year"
    assert festival_match.key("Kit Kat Club") == festival_match.key("KitKat Club"), "spaces do not part two names"
    assert festival_match.key("bulbul Berlin") == festival_match.key("bulbul"), "a trailing Berlin does not either"
    assert festival_match.key("Chausseestraße 131") == festival_match.key("Chausseestr. 131"), "nor Straße against Str."
    checks += 5

    seen = []
    with tempfile.TemporaryDirectory() as tmp:
        out = pathlib.Path(tmp) / "out.tsv"
        args = festival_match.parse_args([FESTIVAL["slug"], PROGRAMME, "--html", str(FIXTURE), "--out", str(out)])
        assert festival_match.run(args, get=fake_get(seen), fetch=None) == 0, "the run succeeds"
        with out.open(encoding="utf-8") as handle:
            rows = list(csv.DictReader(handle, delimiter="\t"))
    checks += 1

    assert all("localhost:28081/api/admin/" in url for url in seen), "it reads the production forward's admin API only"
    proposed = [(r["verdict"], r["event_id"], r["entry_title"]) for r in rows if r["verdict"] != "UNMATCHED"]
    assert proposed == [
        ("MATCH", "10", "Four Play"),
        ("CHOOSE", "20", "Wohnzimmer030 Nachspielzeit"),
        ("CHOOSE", "21", "Wohnzimmer030 Nachspielzeit"),
        ("MATCH", "30", "The Night of Many"),
        ("MATCH", "22", "Tanzflächen der Wiedervereinigung"),
    ], f"one match, both events at a venue with two, an address match: {proposed}"
    unmatched = [(r["entry_title"], r["reason"]) for r in rows if r["verdict"] == "UNMATCHED"]
    assert unmatched == [
        ("SUBVERTED x KLEM. Dark Ambient Listening", "unknown venue"),
        ("J. Mahon’s SPOOKFEST", "no event at the venue that day"),
        ("sonic vigil", "no date"),
        ("Late", "outside the festival's days"),
    ], f"unmatched entries follow, in programme order: {unmatched}"
    assert [r["verdict"] for r in rows][-4:] == ["UNMATCHED"] * 4, "the unmatched rows come last"
    linked = {r["event_id"]: r["festival_now"] for r in rows if r["event_id"]}
    assert linked["20"] == "7" and linked["21"] == "", "a row shows the festival the event already has"
    checks += 5

    print(f"test_festival_match: {checks} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
