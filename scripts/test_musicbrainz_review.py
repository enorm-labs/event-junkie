#!/usr/bin/env python3
"""Asserts that musicbrainz-review.py keeps billed AMBIGUOUS artists, keeps a row whose lookup failed, and writes
only the MBID, by the narrow PUT.

Usage:
    python3 scripts/test_musicbrainz_review.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failed run.
validate-python.yml runs it. Reaches no network, and writes only to a temporary directory.
"""

import argparse
import contextlib
import csv
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import types
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


class StubMatcher:
    """musicbrainz-match.py's three functions; `search` raises the error mapped to a name, as six 503s end in."""

    def __init__(self, errors):
        self.errors = errors

    def fold(self, s):
        return s.casefold()

    def names_of(self, candidate):
        yield candidate["name"]

    def search(self, name):
        if name in self.errors:
            raise self.errors[name]
        return [{"id": f"mbid-{name.lower()}", "name": name}]


def collect_with(review, events, artists, errors, out):
    """Runs `collect` against stubbed forwards and a stubbed matcher; returns the exit code and stderr."""
    by_path = {"/api/events": events, "/api/admin/artists": artists}

    def http(method, url, body=None):
        path = url.split("//", 1)[1].split("/", 1)[1].split("?")[0]
        return {"content": by_path["/" + path], "totalPages": 1}

    review.http, review.matcher = http, lambda: StubMatcher(errors)
    stderr = io.StringIO()
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(stderr):
        code = review.collect(types.SimpleNamespace(env="staging", days=14, out=out))
    return code, stderr.getvalue()


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

    with tempfile.TemporaryDirectory() as tmp:
        out = pathlib.Path(tmp) / "review.tsv"
        events = [event("x", "2026-10-10", ["Polaris", "Gore", "Cassius"])]
        artists = [artist(1, "Polaris"), artist(2, "Gore"), artist(3, "Cassius")]
        busy = urllib.error.HTTPError("https://musicbrainz.test", 503, "Service Temporarily Unavailable", None, None)
        code, stderr = collect_with(review, events, artists, {"Gore": busy}, out)
        assert code == review.EXIT_FAILED, "a failed lookup makes collect exit non-zero"
        assert "skipped Gore: HTTP 503" in stderr and "1 of 3 lookups failed" in stderr, "stderr names the failure"
        with out.open(newline="") as f:
            tsv = {r["name"]: r for r in csv.DictReader(f, delimiter="\t")}
        assert sorted(tsv) == ["Cassius", "Gore", "Polaris"], "the TSV holds every row, the failed one too"
        assert tsv["Gore"]["candidates"] == "lookup failed: HTTP 503", "the failed row is marked in the TSV"
        assert tsv["Cassius"]["candidates"] == "mbid-cassius", "the row after the failure is still looked up"
        json_rows = {r["name"]: r for r in json.loads(out.with_suffix(".json").read_text())}
        assert sorted(json_rows) == ["Cassius", "Gore", "Polaris"], "the JSON holds every row"
        assert json_rows["Gore"]["candidates"] == [] and json_rows["Gore"]["lookup_failed"] == "HTTP 503", (
            "the failed row has no candidates and is marked in the JSON"
        )
        checks += 7

        with out.open(newline="") as f:
            edited = list(csv.DictReader(f, delimiter="\t"))
        for row in edited:
            row["decision"], row["mbid"] = "PROPOSE", f"mbid-{row['name'].lower()}"
        with out.open("w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=review.COLUMNS, delimiter="\t")
            writer.writeheader()
            writer.writerows(edited)
        assert sorted(i for i, _ in review.proposals(out)) == [1, 3], "apply never takes a row whose lookup failed"
        checks += 1

        assert collect_with(review, events, artists, {}, out)[0] == 0, "a run with no failed lookup exits 0"
        checks += 1

    print(f"musicbrainz-review: {checks} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
