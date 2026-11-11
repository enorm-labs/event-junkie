#!/usr/bin/env python3
"""Asserts that limitation-verified.py moves only the row a pair names, only to a later date, and
refuses an unknown or ambiguous pair without writing anything (#4841).

Usage:
    python3 scripts/test_limitation_verified.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failed run.
validate-python.yml runs it. Reaches no network and no git; it writes only its own temporary files.
"""

import argparse
import importlib.util
import pathlib
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent

SINGLE = """val ONE_LIMITATIONS =
    VenueLimitations(
        EventSource.ONE,
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no price", verified = "2026-08-25"),
        AcceptedLimitation(
            LimitedAspect.GENRE,
            "the page says \\"(KONZERT)\\" and nothing else (ever)",
            verified = "2026-10-01"
        ),
    )
"""

SHARED = """val ARENA_LIMITATIONS =
    VenueLimitations(
        sources = setOf(EventSource.HALL_A, EventSource.HALL_B),
        limitations =
            listOf(
                AcceptedLimitation(LimitedAspect.PROMOTERS, "the arena page names no promoter", verified = "2026-09-30")
            )
    )
"""


def load():
    # The hyphen in the file name rules out a plain import.
    spec = importlib.util.spec_from_file_location("limitation_verified", HERE / "limitation-verified.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run(root, pairs, *flags):
    result = subprocess.run(
        [sys.executable, str(HERE / "limitation-verified.py"), "--root", str(root), *flags],
        input=pairs,
        capture_output=True,
        text=True,
    )
    return result.returncode, result.stdout


def tree():
    root = pathlib.Path(tempfile.mkdtemp())
    (root / "one").mkdir()
    (root / "one" / "OneImporter.kt").write_text(SINGLE, encoding="utf-8")
    (root / "Arena.kt").write_text(SHARED, encoding="utf-8")
    return root


def main():
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    module = load()
    checks = []

    root = tree()
    code, out = run(root, "ONE/PRICE 2026-11-11\n", "--apply")
    text = (root / "one" / "OneImporter.kt").read_text(encoding="utf-8")
    checks.append(
        (
            "a later date moves the named row",
            code == 0 and 'PRICE, "the site prints no price", verified = "2026-11-11"' in text,
        )
    )
    checks.append(("its sibling row stays", 'verified = "2026-10-01"' in text))

    root = tree()
    code, out = run(root, "ONE/PRICE 2026-01-01\n", "--apply")
    text = (root / "one" / "OneImporter.kt").read_text(encoding="utf-8")
    checks.append(
        ("an older date leaves the row", code == 0 and 'verified = "2026-08-25"' in text and "unchanged" in out)
    )

    root = tree()
    code, out = run(root, "ONE/GENRE 2026-11-11\n", "--apply")
    text = (root / "one" / "OneImporter.kt").read_text(encoding="utf-8")
    checks.append(
        ('a multi-line call with ( and \\" in its reason moves', code == 0 and 'verified = "2026-11-11"' in text)
    )

    root = tree()
    code, out = run(root, "ONE/PRICE 2026-11-11\nNOPE/PRICE 2026-11-11\n", "--apply")
    text = (root / "one" / "OneImporter.kt").read_text(encoding="utf-8")
    checks.append(
        ("an unknown pair is refused and nothing is written", code == 1 and 'verified = "2026-08-25"' in text)
    )

    root = tree()
    code, out = run(root, "HALL_B/PROMOTERS 2026-11-11\n", "--apply")
    text = (root / "Arena.kt").read_text(encoding="utf-8")
    checks.append(
        ("a shared declaration moves and says so", code == 0 and "2026-11-11" in text and "shared declaration" in out)
    )

    root = tree()
    code, out = run(root, "ONE/PRICE 2026-11-11\n")
    text = (root / "one" / "OneImporter.kt").read_text(encoding="utf-8")
    checks.append(("a dry run writes nothing", code == 0 and 'verified = "2026-08-25"' in text and "dry run" in out))

    root = tree()
    code, _ = run(root, "ONE/PRICE 11.11.2026\n")
    checks.append(("an unreadable line exits 2", code == 2))

    two = SINGLE + SINGLE.replace("ONE_LIMITATIONS", "TWO_LIMITATIONS")
    checks.append(("a source declared twice is ambiguous", len(module.find(two, "ONE", "PRICE")) == 2))

    failed = [name for name, ok in checks if not ok]
    for name in failed:
        print(f"FAIL {name}")
    print(f"{len(checks) - len(failed)} of {len(checks)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
