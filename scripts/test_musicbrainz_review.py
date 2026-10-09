#!/usr/bin/env python3
"""Asserts that musicbrainz-review.py keeps billed AMBIGUOUS artists and writes nothing but the MBID (#2947).

Usage:
    python3 scripts/test_musicbrainz_review.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failed run.
validate-python.yml runs it. Reaches no network and writes nothing.
"""

import argparse
import importlib.util
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent


def load():
    # The hyphen in the file name rules out a plain import.
    spec = importlib.util.spec_from_file_location("musicbrainz_review", HERE / "musicbrainz-review.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def artist(artist_id, name, match="AMBIGUOUS", **fields):
    base = {"id": artist_id, "name": name, "slug": name.lower(), "musicbrainzMatch": match, "musicbrainzId": None}
    return {**base, **fields}


def event(slug, date, names):
    return {"slug": slug, "eventDate": date, "title": slug, "artistNames": names, "venue": {"name": "Venue"}}


def main() -> int:
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    review = load()
    checks = 0

    events = [event("b", "2026-10-12", ["Gore", "Polaris"]), event("a", "2026-10-10", ["POLARIS"])]
    artists = [artist(1, "Polaris"), artist(2, "Gore", match="EXACT"), artist(3, "Unbilled")]
    rows = review.billed_ambiguous(events, artists)
    assert [r["artist_id"] for r in rows] == [1], "only billed AMBIGUOUS artists are kept"
    assert [e["slug"] for e in rows[0]["events"]] == ["b", "a"], "the billing matches the name in any letter case"
    checks += 2

    store = {1: artist(1, "Polaris", imageUrl="https://img.test/p.jpg"), 2: artist(2, "Gore", match="EXACT")}
    puts = []

    def get(i):
        return dict(store[i])

    def put(i, body):
        puts.append(body)
        return {**store[i], **body, "musicbrainzMatch": "EXACT", "updatedAt": "now"}

    assert review.apply_rows([(1, "mbid-1")], get, put, write=False) == 0 and puts == [], "a dry run writes nothing"
    checks += 1

    assert review.apply_rows([(1, "mbid-1"), (2, "mbid-2")], get, put, write=True) == 0, "a clean write is no drift"
    assert len(puts) == 1, "a row no longer AMBIGUOUS is skipped"
    assert puts[0]["imageUrl"] == "https://img.test/p.jpg" and puts[0]["musicbrainzId"] == "mbid-1", (
        "the PUT carries every request field it read, plus the MBID"
    )
    assert set(puts[0]) == {*review.REQUEST_FIELDS, "musicbrainzId"}, "the PUT sends no field the request rejects"
    checks += 4

    def put_losing_image(i, body):
        return {**store[i], **body, "imageUrl": None, "musicbrainzMatch": "EXACT"}

    assert review.apply_rows([(1, "mbid-1")], get, put_losing_image, write=True) == 1, "a field that moved is drift"
    checks += 1

    print(f"musicbrainz-review: {checks} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
