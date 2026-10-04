#!/usr/bin/env python3
"""Asserts the translation licence that apply-licence-review.py derives (ADR-027, #2561).

Usage:
    python3 scripts/test_apply_licence_review.py

A plain script, not pytest: it counts its checks and exits non-zero on the first failed run.
validate-python.yml runs it. Reaches no network and writes nothing.
"""

import argparse
import importlib.util
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent

# (description licence from RESULTS.tsv, stored translationLicence, expected value to send)
CASES = [
    # The 24 UNCLEAR sources of #2561: silence permits translation.
    ("UNCLEAR", None, "PERMITTED"),
    ("PERMITTED", None, "PERMITTED"),
    # A review row with no description verdict still displays, so it translates too.
    ("", None, "PERMITTED"),
    # The four PROHIBITED sources: never PERMITTED, and a stored grant is withdrawn.
    ("PROHIBITED", None, "PROHIBITED"),
    ("PROHIBITED", "PERMITTED", "PROHIBITED"),
    ("PROHIBITED", "UNCLEAR", "PROHIBITED"),
    # Already right: send nothing, so the stored value is not rewritten.
    ("PROHIBITED", "PROHIBITED", None),
    ("UNCLEAR", "PERMITTED", None),
    # A value set by hand outranks the default: a venue's #808 objection survives a re-run.
    ("UNCLEAR", "PROHIBITED", None),
    ("UNCLEAR", "UNCLEAR", None),
]


def load():
    # The hyphen in the file name rules out a plain import.
    spec = importlib.util.spec_from_file_location("apply_licence_review", HERE / "apply-licence-review.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    module = load()
    failed = 0
    for description, stored, expected in CASES:
        got = module.translation_licence(description, stored)
        if got != expected:
            failed += 1
            print(f"FAIL description={description!r} stored={stored!r}: expected {expected!r}, got {got!r}")
    print(f"{len(CASES) - failed} of {len(CASES)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
