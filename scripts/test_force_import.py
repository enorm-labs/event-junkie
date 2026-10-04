#!/usr/bin/env python3
"""Asserts that force-import.py reads every source across pages, and refuses a partial listing (#2595).

Usage:
    python3 scripts/test_force_import.py

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
    spec = importlib.util.spec_from_file_location("force_import", HERE / "force-import.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def listing(slugs, total=None, page_size=100):
    """A fake importer: serves [slugs] in pages, reporting [total] as totalElements."""
    total = len(slugs) if total is None else total

    def fetch(base, env, path):
        page = int(path.split("page=")[1].split("&")[0])
        content = [{"slug": s, "status": "IDLE"} for s in slugs[page * page_size : (page + 1) * page_size]]
        return {"content": content, "totalElements": total}

    return fetch


def main():
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    module = load()
    slugs = [f"source-{n:03d}" for n in range(111)]
    checks = []

    got = module.sources("http://x", "staging", fetch=listing(slugs))
    checks.append(("111 sources over two pages", len(got) == 111 and "source-110" in got))

    got = module.sources("http://x", "staging", fetch=listing(slugs[:40]))
    checks.append(("one short page", len(got) == 40))

    got = module.sources("http://x", "staging", fetch=listing([]))
    checks.append(("an empty listing", got == {}))

    try:
        module.sources("http://x", "staging", fetch=listing(slugs[:100], total=111))
        refused = False
    except SystemExit:
        refused = True
    checks.append(("a listing short of totalElements is refused", refused))

    failed = [name for name, ok in checks if not ok]
    for name in failed:
        print(f"FAIL {name}")
    print(f"{len(checks) - len(failed)} of {len(checks)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
