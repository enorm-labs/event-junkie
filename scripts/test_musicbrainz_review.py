#!/usr/bin/env python3
"""Asserts that musicbrainz-review.py keeps billed AMBIGUOUS artists and writes only the MBID, by the narrow PUT.

Usage:
    python3 scripts/test_musicbrainz_review.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failed run.
validate-python.yml runs it. Reaches no network and writes nothing.
"""

import argparse
import importlib.util
import pathlib
import sys
import urllib.error

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
    gets, puts = [], []

    def get(i):
        gets.append(i)
        return dict(store[i])

    def put(i, body):
        puts.append((i, body))
        return {**store[i], **body, "musicbrainzMatch": "EXACT", "updatedAt": "now"}

    assert review.apply_rows([(1, "mbid-1"), (2, "mbid-2")], get, put, write=False) == 0, "a dry run fails no row"
    assert puts == [] and gets == [1, 2], "a dry run reads each artist and writes nothing"
    checks += 2

    gets.clear()
    store[3] = artist(3, "Cassius", imageUrl="https://img.test/c.jpg")
    rows = [(1, "06E3BCE0-C612-4A5F-B095-9FFED1E4A656"), (3, "8b8a38a9-a290-4560-84f6-3d4466e8d791")]
    assert review.apply_rows(rows, get, put, write=True) == 0, "a clean write fails no row"
    assert gets == [1, 3], "the write reads each artist's match once before it writes"
    assert puts == [
        (1, {"musicbrainzId": "06e3bce0-c612-4a5f-b095-9ffed1e4a656"}),
        (3, {"musicbrainzId": "8b8a38a9-a290-4560-84f6-3d4466e8d791"}),
    ], "one PUT per AMBIGUOUS row, with the lowercase MBID and nothing from the read"
    checks += 3

    gets.clear()
    puts.clear()
    assert review.apply_rows([(2, "mbid-2"), (1, "mbid-1")], get, put, write=True) == 0, "a skip fails no row"
    assert gets == [2, 1] and [i for i, _ in puts] == [1], "a row settled since the dry run is read and not written"
    checks += 2

    def get_not_found(i):
        if i == 9:
            raise urllib.error.HTTPError(f"http://importer/{i}", 404, "Not Found", None, None)
        return get(i)

    puts.clear()
    assert review.apply_rows([(9, "mbid-9"), (1, "mbid-1")], get_not_found, put, write=True) == 1, (
        "a 404 on the read fails only its row"
    )
    assert [i for i, _ in puts] == [1], "the row after a failed read is still written"
    checks += 2

    def put_not_found(i, body):
        raise urllib.error.HTTPError(f"http://importer/{i}/musicbrainz-id", 404, "Not Found", None, None)

    assert review.apply_rows([(1, "mbid-1"), (3, "mbid-3")], get, put_not_found, write=True) == 2, (
        "a refused write fails its row, and the run goes on"
    )
    checks += 1

    def put_not_stored(i, body):
        return {**store[i], "musicbrainzMatch": "AMBIGUOUS"}

    assert review.apply_rows([(1, "mbid-1")], get, put_not_stored, write=True) == 1, "an id not stored fails its row"
    checks += 1

    print(f"musicbrainz-review: {checks} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
