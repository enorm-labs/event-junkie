#!/usr/bin/env python3
"""Asserts that admin_api.pages() reads every page, and refuses a partial listing (#810, #5642).

Usage:
    python3 scripts/test_admin_api.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failure.
validate-python.yml runs it. Reaches no network.
"""

import argparse
import pathlib
import sys
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import admin_api  # noqa: E402


def listing(rows, total=None, asked=None):
    """A fake paged list of [rows] that reports [total] as totalElements, 100 to a page."""

    def get(url):
        if asked is not None:
            asked.append(url)
        page = int(url.split("page=")[1].split("&")[0])
        return {"content": rows[page * 100 : (page + 1) * 100], "totalElements": len(rows) if total is None else total}

    return get


def refusal(get, **kwargs):
    try:
        admin_api.pages(get, "http://x/api/admin/venues", **kwargs)
    except admin_api.PartialListing as error:
        return str(error)
    return None


def main() -> int:
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    checks = 0

    def check(condition, message):
        nonlocal checks
        if not condition:
            print(f"FAIL: {message}")
            sys.exit(1)
        checks += 1

    rows = list(range(250))
    asked = []
    check(admin_api.pages(listing(rows, asked=asked), "http://x/a") == rows, "every row of three pages, in order")
    check(len(asked) == 3, "the reading stops at totalElements")
    check(asked[0] == "http://x/a?page=0&size=100", "a URL without a query gets one")

    asked = []
    admin_api.pages(listing([1], asked=asked), "http://x/a?sort=name,asc")
    check(asked == ["http://x/a?sort=name,asc&page=0&size=100"], "a URL with a query gets the page parameters appended")

    check(admin_api.pages(listing([]), "http://x/a") == [], "an empty list is read as empty")

    message = refusal(listing(rows[:150], total=250))
    check(message == "Read 150 of 250 from http://x/api/admin/venues. Refusing to act on a partial listing.", "short")
    check(refusal(listing(rows, total=150)) is not None, "a read longer than the total is refused too")

    message = refusal(listing(rows), max_pages=2)
    check(message is not None and message.startswith("Stopped after 2 pages"), "the page cap is a refusal")

    check(issubclass(admin_api.PartialListing, SystemExit), "uncaught, a refusal exits instead of printing a traceback")

    with mock.patch.object(admin_api.time, "sleep") as sleep:
        admin_api.pages(listing(rows), "http://x/a", pause=0.5)
    check(sleep.call_count == 2 and sleep.call_args.args == (0.5,), "the pause falls between pages, not after the last")

    print(f"admin-api: {checks} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
