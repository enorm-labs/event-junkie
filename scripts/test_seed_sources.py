#!/usr/bin/env python3
"""Asserts that seed-sources.py --enable leaves the source of a closed venue off (#4410).

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


def source(slug, name, enabled):
    return {"slug": slug, "name": name, "enabled": enabled, "descriptionLicence": "ALLOWED", "imageLicence": "ALLOWED"}


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


def main():
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    module = load()
    seed = [
        ({"name": "Open Club"}, [{"name": "Open Club"}]),
        ({"name": "Closed Club", "closedOn": "2026-10-31"}, [{"name": "Closed Club"}]),
        ({"name": "Running Club"}, [{"name": "Running Club"}]),
    ]
    checks = []

    sources = [
        source("open-club", "Open Club", False),
        source("closed-club", "Closed Club", False),
        source("running-club", "Running Club", True),
    ]
    patched, out = run_enable(module, sources, seed)
    checks.append(("only the open venue's disabled source is enabled", patched == ["open-club"]))
    checks.append(
        ("the closed venue's source is named as skipped", "SKIPPED  closed-club (venue closed 2026-10-31)" in out)
    )
    checks.append(("the count leaves the skipped source out", "Enabled 1 of 1." in out))

    patched, out = run_enable(module, [source("closed-club", "Closed Club", False)], seed)
    checks.append(("a closed source alone enables nothing", patched == [] and "Nothing to do." in out))

    failed = [name for name, ok in checks if not ok]
    for name in failed:
        print(f"FAIL {name}")
    print(f"{len(checks) - len(failed)} of {len(checks)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
