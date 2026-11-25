#!/usr/bin/env python3
"""Asserts which nginx versions nginx-advisories.py calls affected, and that a page it cannot read is
exit 2 rather than clean (#5514).

Usage:
    python3 scripts/test_nginx_advisories.py

A plain script, not pytest: it counts its checks and exits non-zero on a failed run.
validate-python.yml runs it. Reaches no network; nginx-advisories-fixture.html is the page of 2026-11-25.
"""

import argparse
import contextlib
import importlib.util
import io
import pathlib
import sys
import tempfile
import urllib.error

HERE = pathlib.Path(__file__).resolve().parent
FIXTURE = HERE / "nginx-advisories-fixture.html"


def load():
    # The hyphen in the file name rules out a plain import.
    spec = importlib.util.spec_from_file_location("nginx_advisories", HERE / "nginx-advisories.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter).parse_args()
    na = load()
    page = FIXTURE.read_text(encoding="utf-8")
    entries = na.parse_page(page)
    failures = []
    checks = 0

    def expect(name, actual, expected):
        nonlocal checks
        checks += 1
        if actual != expected:
            failures.append(f"{name}: expected {expected!r}, got {actual!r}")

    def ids(version):
        return {i for entry in na.affecting(na.parse_version(version), entries) for i in entry["ids"]}

    def run(content, version="1.31.10"):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "page.html"
            path.write_text(content, encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return na.main(["--page", str(path), "--version", version])

    five = {"CVE-2026-123007", "CVE-2026-123633", "CVE-2026-121810", "CVE-2026-123634", "CVE-2026-113425"}
    expect("1.31.7 carries all of CERT-EU 2026-022", five <= ids("1.31.7"), True)
    expect("1.31.10 is clean", ids("1.31.10"), set())
    expect("1.31.9 carries only the later one", ids("1.31.9"), {"CVE-2026-132779"})
    expect("a later branch is clean", ids("1.32.0"), set())
    expect("1.30.6 is affected", five <= ids("1.30.6"), True)
    expect("1.30.7 is fixed inside the range 1.9.5-1.31.8", five & ids("1.30.7"), set())
    expect("1.30.8 is clean of the later one", "CVE-2026-132779" in ids("1.30.8"), False)
    expect("a stable fix does not cover an old branch", "CVE-2026-123007" in ids("1.29.9"), True)
    expect("the ignored advisory never reports", "CVE-2009-4487" in ids("1.31.7"), False)
    expect("a Windows advisory never reports", "CVE-2011-4963" in ids("1.2.0"), False)
    expect("a single version", na.parse_ranges("1.25.3"), [((1, 25, 3), (1, 25, 3))])

    expect("exit 0 when clean", run(page), 0)
    expect("exit 1 when affected", run(page, "1.31.7"), 1)
    expect("exit 2 on a version that is none", run(page, "latest"), 2)
    expect("exit 2 on an empty page", run(""), 2)
    expect("exit 2 on a short page", run(page[: page.index("CVE-2026-97205")]), 2)
    expect("exit 2 on changed markup", run(page.replace("<li><p>", "<li><div>")), 2)
    expect("exit 2 on one advisory in other markup", run(page.replace("<li><p>", "<li><div>", 1)), 2)
    expect("exit 2 on an unreadable range", run(page.replace("1.9.5-1.31.8", "before 1.31.9")), 2)
    expect("exit 2 on an unreadable fix", run(page.replace("1.31.9+, 1.30.7+", "1.31.9 and later")), 2)
    expect("exit 2 on a missing file", na.main(["--page", "/nonexistent", "--version", "1.31.10"]), 2)

    def refuse(url):
        raise urllib.error.URLError("no route")

    na.fetch = refuse
    expect("exit 2 when the page cannot be fetched", na.main(["--version", "1.31.10"]), 2)

    image = "ghcr.io/nginx/nginx-unprivileged:1.31-alpine@sha256:abc"
    expect("the FROM pin", na.pinned_image(f"# FROM nothing\nFROM {image}\nCOPY --from=x /a /b\n"), image)
    expect("this repository's Dockerfile has one", bool(na.pinned_image(na.DOCKERFILE.read_text())), True)

    for failure in failures:
        print(f"FAIL {failure}", file=sys.stderr)
    print(f"{checks - len(failures)}/{checks} checks passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
