#!/usr/bin/env python3
"""Move the `verified` date of accepted limitations the plausibility re-check saw still silent (#4841).

Reads one `SOURCE/ASPECT YYYY-MM-DD` pair per line, from a file or stdin, and finds that row's
`AcceptedLimitation` inside the `VenueLimitations(...)` that serves the source. Dry run by default.

    python3 scripts/limitation-verified.py still-silent.txt             # what would move
    python3 scripts/limitation-verified.py still-silent.txt --apply     # write it
    echo 'A_TRANE/END_TIME 2026-11-11' | python3 scripts/limitation-verified.py --apply

A date only moves later: an older or equal one is `unchanged`, so running a month twice writes
nothing new. **All or nothing**: a pair that matches no row, or more than one, is refused, and then
no file is written at all. A source in a multi-source declaration moves the row its siblings share,
and the output says so.

After `--apply`, regenerate `docs/data-quality/ACCEPTED_LIMITATIONS.md` with the `cp` that
`AcceptedLimitationsTest` prints. `/maintain` runs this monthly.

Exit 0 when every pair resolved, 1 when one was refused, 2 on unreadable input.
"""

import argparse
import datetime
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRAPERS = ROOT / "events-importer/src/main/kotlin/de/norm/events/scraper"
PAIR = re.compile(r"^([A-Z0-9_]+)/([A-Z_]+)\s+(\d{4}-\d{2}-\d{2})$")
VERIFIED = re.compile(r'verified\s*=\s*"(\d{4}-\d{2}-\d{2})"')


def call_end(text, start):
    """The index of the `)` that closes the call whose `(` ends just before [start]."""
    i, depth = start, 1
    while depth:
        c = text[i]
        if c == '"':
            if text.startswith('"""', i):
                i = text.index('"""', i + 3) + 3
                continue
            i += 1
            while text[i] != '"':
                i += 2 if text[i] == "\\" else 1
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        i += 1
    return i - 1


def calls(text, name, lo=0, hi=None):
    """(start, end) of each `name(...)` call between [lo] and [hi], end at the closing paren."""
    hi = len(text) if hi is None else hi
    for m in re.finditer(rf"(?<![\w.]){name}\(", text[lo:hi]):
        start = lo + m.end()
        yield start, call_end(text, start)


def find(text, source, aspect):
    """Spans of `verified = "…"` in the rows declaring [aspect] for [source], and whether the declaration is shared."""
    found = []
    for lo, hi in calls(text, "VenueLimitations"):
        sources = set(re.findall(r"EventSource\.([A-Z0-9_]+)", text[lo:hi]))
        if source not in sources:
            continue
        for a, b in calls(text, "AcceptedLimitation", lo, hi):
            if re.match(rf"\s*(aspect\s*=\s*)?LimitedAspect\.{aspect}\b", text[a:b]):
                m = VERIFIED.search(text, a, b)
                if m:
                    found.append((m.start(1), m.end(1), len(sources) > 1))
    return found


def parse(lines):
    pairs, bad = [], []
    for raw in lines:
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        m = PAIR.match(line)
        if not m:
            bad.append(raw.rstrip("\n"))
            continue
        try:
            datetime.date.fromisoformat(m.group(3))
        except ValueError:
            bad.append(raw.rstrip("\n"))
            continue
        pairs.append(m.groups())
    return pairs, bad


def plan(texts, pairs):
    """Resolve every pair against {path: text}: (edits by path, report lines, refused lines)."""
    edits, report, refused = {}, [], []
    latest = {}
    for source, aspect, date in pairs:
        latest[(source, aspect)] = max(date, latest.get((source, aspect), date))
    for (source, aspect), date in latest.items():
        hits = [(path, s, e, shared) for path, text in texts.items() for s, e, shared in find(text, source, aspect)]
        label = f"{source}/{aspect}"
        if len(hits) != 1:
            refused.append(f"refused    {label}: {'no row declares' if not hits else f'{len(hits)} rows declare'} it")
            continue
        path, s, e, shared = hits[0]
        current = texts[path][s:e]
        note = " (shared declaration: its other sources move too)" if shared else ""
        if date <= current:
            report.append(f"unchanged  {label} {current}")
            continue
        previous = edits.setdefault(path, {}).get((s, e))
        if previous is None or date > previous:
            edits[path][(s, e)] = date
        report.append(f"moved      {label} {current} -> {date}{note}")
    return edits, report, refused


def apply(text, spans):
    for (s, e), date in sorted(spans.items(), reverse=True):
        text = text[:s] + date + text[e:]
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("file", nargs="?", help="pairs, one per line; stdin when omitted")
    parser.add_argument("--apply", action="store_true", help="write the dates; a dry run otherwise")
    parser.add_argument("--root", type=pathlib.Path, default=SCRAPERS, help=argparse.SUPPRESS)
    args = parser.parse_args()

    lines = (
        pathlib.Path(args.file).read_text(encoding="utf-8").splitlines() if args.file else sys.stdin.read().splitlines()
    )
    pairs, bad = parse(lines)
    if bad:
        for line in bad:
            print(f"unreadable {line!r}: want SOURCE/ASPECT YYYY-MM-DD", file=sys.stderr)
        return 2

    texts = {p: p.read_text(encoding="utf-8") for p in sorted(args.root.rglob("*.kt"))}
    texts = {p: t for p, t in texts.items() if "VenueLimitations(" in t}
    edits, report, refused = plan(texts, pairs)
    for line in report + refused:
        print(line)
    if refused:
        print(f"{len(refused)} refused: nothing written", file=sys.stderr)
        return 1
    if args.apply:
        for path, spans in edits.items():
            path.write_text(apply(texts[path], spans), encoding="utf-8")
    moved = sum(len(s) for s in edits.values())
    unchanged = sum(line.startswith("unchanged") for line in report)
    print(f"{moved} row(s) {'moved' if args.apply else 'would move (dry run)'}, {unchanged} unchanged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
