#!/usr/bin/env python3
"""Asserts how manual-collisions.py folds titles, matches a manual row and pages a listing (#3824).

Usage:
    python3 scripts/test_manual_collisions.py

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
    spec = importlib.util.spec_from_file_location("manual_collisions", HERE / "manual-collisions.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def event(slug, date, title, source="kulturhaus_spandau:1"):
    return {"id": 1, "slug": slug, "eventDate": date, "title": title, "sourceId": source, "where": "staging"}


def listing(items, total=None):
    total = len(items) if total is None else total

    def fetch(base, path):
        page = int(path.split("page=")[1].split("&")[0])
        return {"content": items[page * 100 : (page + 1) * 100], "totalElements": total}

    return fetch


def main():
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    module = load()
    imported = [
        event("2026-11-02-bei-urlaub-mord", "2026-11-02", "Bei Urlaub Mord"),
        event("2026-11-05-ausser-kontrolle", "2026-11-05", "AUSSER  KONTROLLE"),
    ]
    checks = []

    def verdict(slug, date, title):
        return module.classify(event(slug, date, title, "manual:kulturhaus-spandau:x"), imported)[0]

    checks.append(("ß folds to ss", module.fold("Außer Kontrolle") == module.fold("AUSSER  KONTROLLE")))
    checks.append(("same slug", verdict("2026-11-02-bei-urlaub-mord", "2026-11-09", "Anything") == "SLUG"))
    checks.append(("same title, same night", verdict("x", "2026-11-05", "Außer Kontrolle") == "TITLE"))
    checks.append(("same title, other night", verdict("x", "2026-11-06", "Außer Kontrolle") == "KEEP"))
    elektra = verdict("x", "2026-11-02", "Theater Elektra: Bei Urlaub Mord")
    checks.append(("other title, same night", elektra == "NIGHT"))
    checks.append(("nothing that night", verdict("x", "2026-11-20", "Lesung") == "KEEP"))
    checks.append(("manual sourceId", module.is_manual(event("x", "d", "t", "manual:a:b"))))
    checks.append(("imported sourceId", not module.is_manual(event("x", "d", "t", "interkosmos:1"))))

    items = [{"n": n} for n in range(150)]
    checks.append(("150 items over two pages", len(module.paged("http://x", "/p?a=1", fetch=listing(items))) == 150))
    try:
        module.paged("http://x", "/p", fetch=listing(items[:100], total=150))
        refused = False
    except SystemExit:
        refused = True
    checks.append(("a partial listing is refused", refused))

    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(f"{'ok  ' if ok else 'FAIL'} {name}")
    print(f"{len(checks) - len(failed)} of {len(checks)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
