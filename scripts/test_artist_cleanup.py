#!/usr/bin/env python3
"""Asserts that artist-cleanup.py flags the names that are not acts, and leaves out what the orphan sweep deletes.

Usage:
    python3 scripts/test_artist_cleanup.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failed run.
validate-python.yml runs it. Reaches no network and writes nothing.
"""

import argparse
import datetime
import importlib.util
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
TODAY = datetime.date(2026, 10, 13)


def load():
    # The hyphen in the file name rules out a plain import.
    spec = importlib.util.spec_from_file_location("artist_cleanup", HERE / "artist-cleanup.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def artist(artist_id, name, **fields):
    return {"id": artist_id, "name": name, "slug": name.lower().replace(" ", "-"), "musicbrainzMatch": "NONE", **fields}


def event(event_id, date, artist_ids, promoter_ids=()):
    return {
        "id": event_id,
        "eventDate": date,
        "title": f"Night {event_id}",
        "venueId": 1,
        "sourceId": f"tresor:{event_id}",
        "sourceUrl": f"https://example.org/{event_id}",
        "artists": [{"artistId": i} for i in artist_ids],
        "promoterIds": list(promoter_ids),
    }


def main() -> int:
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    cleanup = load()
    checks = 0

    def check(condition, message):
        nonlocal checks
        if not condition:
            print(f"FAIL: {message}")
            sys.exit(1)
        checks += 1

    venues = [{"id": 1, "name": "Tresor"}]
    artists = [
        artist(1, "Tresor.291 - Dreamy Harbour"),
        artist(2, "Tresor048X Infiniti Collection"),
        artist(3, "Skudge"),
        artist(4, "DJ Gus"),
        artist(5, "Gus"),
        # Unbilled: the sweep deletes the first, keeps the second for its EXACT match and the third for its link.
        artist(6, "Release Party"),
        artist(7, "Saturday Night", musicbrainzMatch="EXACT"),
        artist(8, "Some Night", soundcloudUrl="https://soundcloud.com/x"),
    ]
    events = [event(10, "2026-10-14", [1, 3, 4]), event(11, "2026-09-01", [2, 5])]
    rows = {r["name"]: r for r in cleanup.review_rows(artists, events, venues, "artists", TODAY)}

    check("catalogue" in rows["Tresor.291 - Dreamy Harbour"]["flags"], "a dotted catalogue number is flagged")
    check("catalogue" in rows["Tresor048X Infiniti Collection"]["flags"], "a fused catalogue number is flagged")
    check(
        "venue-name" in rows["Tresor048X Infiniti Collection"]["flags"],
        "the billing venue's name inside a name is flagged",
    )
    check("Skudge" not in rows, "a plain name is not flagged")
    check(rows["DJ Gus"]["near_duplicates"] == ["gus"], "a DJ prefix folds onto the bare name")
    check(
        rows["DJ Gus"]["events"][0]["billed_with"] == ["Tresor.291 - Dreamy Harbour", "Skudge"],
        "co-billed names are listed",
    )
    check(rows["Tresor.291 - Dreamy Harbour"]["upcoming"] == 1, "an event from today on counts as upcoming")
    check(rows["Tresor048X Infiniti Collection"]["upcoming"] == 0, "a past event does not count as upcoming")
    check(list(rows)[0] != "Tresor048X Infiniti Collection", "a row billed on an upcoming event sorts first")
    check("Release Party" not in rows, "an unbilled row the sweep deletes is left out")
    check("unbilled-kept" in rows["Saturday Night"]["flags"], "an unbilled EXACT match is flagged unbilled-kept")
    check(
        "unbilled-kept" in rows["Some Night"]["flags"], "an unbilled row with a profile link is flagged unbilled-kept"
    )

    promoters = [
        artist(20, "Präsentiert von Tresor"),
        artist(21, "Tresor Records", description="A label."),
        artist(22, "Tresor Party"),
    ]
    promoted = cleanup.review_rows(promoters, [event(12, "2026-10-14", [], [20])], venues, "promoters", TODAY)
    names = [r["name"] for r in promoted]
    check(names[0] == "Präsentiert von Tresor", "promoters are joined through promoterIds")
    check(
        "unbilled-kept" in promoted[1]["flags"],
        "an unbilled promoter with a description is kept, as its sweep keeps it",
    )
    check("Tresor Party" not in names, "an unbilled promoter the sweep deletes is left out")

    print(f"artist-cleanup: {checks} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
