#!/usr/bin/env python3
"""Asserts that genre-cleanup.py flags tags that are not genres or are one genre twice, and leaves out unlinked ones.

Usage:
    python3 scripts/test_genre_cleanup.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failed run.
validate-python.yml runs it. Reaches no network and writes nothing.
"""

import argparse
import datetime
import importlib.util
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
TODAY = datetime.date(2026, 11, 16)


def load():
    # The hyphen in the file name rules out a plain import.
    spec = importlib.util.spec_from_file_location("genre_cleanup", HERE / "genre-cleanup.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def tag(tag_id, name, slug, family=None):
    return {"id": tag_id, "name": name, "slug": slug, "family": family}


def event(event_id, date, source, genre_tags, genre=None):
    return {
        "id": event_id,
        "eventDate": date,
        "title": f"Night {event_id}",
        "eventType": "CONCERT",
        "venueId": 1,
        "sourceId": f"{source}:{event_id}",
        "sourceUrl": f"https://example.org/{event_id}",
        "genre": genre,
        "genreTags": genre_tags,
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

    check(cleanup.fold("Rock'n Roll") == cleanup.fold("RocknRoll"), "an apostrophe n folds onto the bare n")
    check(cleanup.fold("Rock and Roll") == cleanup.fold("Rock'n'Roll"), "`and` folds onto n")
    check(cleanup.fold("Drum & Bass") == cleanup.fold("Drum'n'Bass"), "`&` folds onto n")
    check(cleanup.fold("Dreampop") == cleanup.fold("Dream-Pop"), "a hyphen is dropped")
    check(cleanup.fold("Top 40") == cleanup.fold("Top40"), "a space is dropped")
    check(cleanup.fold("Lofi") == cleanup.fold("Lo Fi"), "a split word folds onto the joined one")
    check(cleanup.fold("Garage") != cleanup.fold("Grandmaster"), "folding `and` inside a word joins no unrelated tags")

    check(cleanup.is_format_word("Kneipenquiz"), "a compound ending in quiz is a format word")
    check(cleanup.is_format_word("Art Workshop"), "a format word in a two-word tag is found")
    check(cleanup.is_format_word("Gespräch"), "an umlaut format word is found without its accent")
    check(cleanup.is_format_word("DJ Sets"), "DJ Sets is a format word")
    check(not cleanup.is_format_word("Shoegaze"), "a style is not a format word")
    check(not cleanup.is_format_word("Techno"), "a plain genre is not a format word")

    venues = [{"id": 1, "name": "Club"}]
    tags = [
        tag(1, "Dreampop", "dreampop", "indie"),
        tag(2, "Dream-Pop", "dream-pop", "indie"),
        tag(3, "Kneipenquiz", "kneipenquiz"),
        tag(4, "Techno", "techno", "techno"),
        tag(5, "Unlinked", "unlinked"),
    ]
    events = [
        event(10, "2026-11-20", "a", ["Dreampop", "techno"], "Dreampop, Techno"),
        event(11, "2026-10-01", "b", ["Dream-Pop", "Techno"]),
        event(12, "2026-11-17", "a", ["Kneipenquiz"], "Kneipenquiz"),
        event(13, "2026-11-01", "a", ["Dreampop"]),
    ]
    rows, unlinked = cleanup.review_rows(tags, events, venues, TODAY, {"techno": "KEEP: a genre"})
    by_slug = {r["slug"]: r for r in rows}

    check(unlinked == 1 and "unlinked" not in by_slug, "a tag no event links is left out and counted")
    check(by_slug["techno"]["event_count"] == 2, "tags join events by name, in any case")
    check(by_slug["techno"]["flags"] == [], "a genre with a family and two sources is not flagged")
    check(by_slug["dreampop"]["near_duplicates"] == ["dream-pop"], "a near-duplicate names the other slug")
    check(
        by_slug["dreampop"]["flags"] == ["near-duplicate", "one-source"],
        "a one-source spelling variant carries both flags",
    )
    check(
        by_slug["kneipenquiz"]["flags"] == ["no-family", "format-word", "one-source"],
        "a format word without a family carries three flags",
    )
    check(by_slug["dreampop"]["upcoming"] == 1, "an event from today on counts as upcoming")
    check(by_slug["dreampop"]["events"][0]["genre"] == "Dreampop, Techno", "the sample carries the raw genre text")
    check(by_slug["techno"]["previous"] == "KEEP: a genre", "an earlier verdict is carried by slug")
    check(rows[-1]["slug"] == "techno", "an unflagged row sorts last")
    check(rows[0]["slug"] == "kneipenquiz", "the row with the most flags sorts first")
    one_source = cleanup.review_rows(
        [tag(6, "Shoegaze", "shoegaze", "indie"), tags[2]],
        events + [event(14, "2026-11-30", "c", ["Shoegaze"])],
        venues,
        TODAY,
        {},
    )[0]
    check(one_source[0]["slug"] == "kneipenquiz", "a row flagged only one-source sorts after a stronger flag")

    print(f"genre-cleanup: {checks} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
