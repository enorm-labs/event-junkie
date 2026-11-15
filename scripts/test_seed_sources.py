#!/usr/bin/env python3
"""Asserts that seed-sources.py --enable leaves two kinds of disabled source off, and what --site counts.

A closed venue's source stays off (#4410), and so does a source with an import history (#5062).
A served venue that only holds Christmas markets is not drift for --site (#5070).

Usage:
    python3 scripts/test_seed_sources.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failed run.
validate-python.yml runs it. Reaches no network and writes nothing.
"""

import argparse
import contextlib
import importlib.util
import io
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent


def load():
    # The hyphen in the file name rules out a plain import.
    spec = importlib.util.spec_from_file_location("seed_sources", HERE / "seed-sources.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def source(slug, name, enabled, last_import_at=None):
    return {
        "slug": slug,
        "name": name,
        "enabled": enabled,
        "lastImportAt": last_import_at,
        "descriptionLicence": "ALLOWED",
        "imageLicence": "ALLOWED",
    }


def run_enable(module, sources, seed):
    """Runs enable() against a fake importer; answers (the slugs it PATCHed, its output)."""
    patched = []

    def request(url, method="GET", body=None):
        patched.append(url.rsplit("/", 1)[1])
        return {"enabled": True}

    module.fetch_all = lambda host, path, query="": sources
    module.read_seed = lambda path: seed
    module.request = request
    args = argparse.Namespace(host="http://x", seed="seed", yes=True, allow_unreviewed=False)
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        module.enable(args)
    return patched, out.getvalue()


def run_site(module, served, events, seed):
    """Runs compare_site() against a fake public API; answers (its exit code, its output, the swept slugs).

    `events` maps a venue slug to (listed events, Christmas-market events)."""

    swept = set()

    def request(url, method="GET", body=None):
        query = url.split("?", 1)[1]
        params = dict(pair.split("=", 1) for pair in query.split("&"))
        swept.add(params["venue"])
        listed, markets = events.get(params["venue"], (0, 0))
        return {"totalElements": markets if "feature" in params else listed}

    module.fetch_all = lambda host, path, query="": [] if query else served
    module.read_seed = lambda path: seed
    module.request = request
    module.SWEEP_PAUSE_SECONDS = 0
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = module.compare_site(argparse.Namespace(site="http://x", seed="seed"))
    return code, out.getvalue(), swept


def site_checks(module):
    seed = [({"name": "Open Club"}, [{"name": "Open Club"}])]
    club = {"slug": "open-club", "name": "Open Club"}
    square = {"slug": "square", "name": "Square"}
    ended = {"slug": "ended", "name": "Ended Square"}
    hall = {"slug": "hall", "name": "Hall"}
    events = {"open-club": (5, 0), "square": (2, 2), "ended": (0, 0), "hall": (3, 1)}
    checks = []

    code, out, _ = run_site(module, [club, square, ended], events, seed)
    checks.append(("market-only venues are not drift", code == 0))
    checks.append(("a market-only venue is listed as such", "MARKET ONLY            Square" in out))
    checks.append(("a venue whose markets ended is market-only", "MARKET ONLY            Ended Square" in out))

    code, out, swept = run_site(module, [club, square, hall], events, seed)
    checks.append(("a venue with a market and another event is drift", code == module.EXIT_DRIFT))
    checks.append(("that venue is named as only on the site", "ONLY ON THE SITE       Hall" in out))
    checks.append(("a seeded venue is never swept", swept == {"square", "hall"}))

    code, out, _ = run_site(module, [square], events, seed)
    checks.append(("a seeded venue missing from the site is still drift", code == module.EXIT_DRIFT))
    checks.append(("it is named as missing", "MISSING FROM THE SITE  Open Club" in out))
    return checks


def main():
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    module = load()
    seed = [
        ({"name": "Open Club"}, [{"name": "Open Club"}]),
        ({"name": "Closed Club", "closedOn": "2026-10-31"}, [{"name": "Closed Club"}]),
        ({"name": "Running Club"}, [{"name": "Running Club"}]),
        ({"name": "Quiet Club"}, [{"name": "Quiet Club"}]),
    ]
    checks = []

    sources = [
        source("open-club", "Open Club", False),
        source("closed-club", "Closed Club", False),
        source("running-club", "Running Club", True),
        source("quiet-club", "Quiet Club", False, "2026-10-28T21:04:11.123Z"),
    ]
    patched, out = run_enable(module, sources, seed)
    checks.append(("only the open venue's disabled source is enabled", patched == ["open-club"]))
    checks.append(
        ("the closed venue's source is named as skipped", "SKIPPED  closed-club (venue closed 2026-10-31)" in out)
    )
    checks.append(
        (
            "a disabled source with an import history is named as kept off",
            "KEPT OFF  quiet-club (disabled after its last import, 2026-10-28)" in out,
        )
    )
    checks.append(("the count leaves the skipped and kept-off sources out", "Enabled 1 of 1." in out))

    patched, out = run_enable(module, [source("quiet-club", "Quiet Club", False, "2026-10-28T21:04:11Z")], seed)
    checks.append(("a kept-off source alone enables nothing", patched == [] and "Nothing to do." in out))

    patched, out = run_enable(module, [source("closed-club", "Closed Club", False, "2026-10-30T20:00:00Z")], seed)
    checks.append(
        (
            "a closed venue's source with a history is skipped, not kept off",
            patched == [] and "SKIPPED  closed-club" in out and "KEPT OFF" not in out,
        )
    )

    patched, out = run_enable(module, [source("closed-club", "Closed Club", False)], seed)
    checks.append(("a closed source alone enables nothing", patched == [] and "Nothing to do." in out))

    checks.extend(site_checks(load()))

    failed = [name for name, ok in checks if not ok]
    for name in failed:
        print(f"FAIL {name}")
    print(f"{len(checks) - len(failed)} of {len(checks)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
