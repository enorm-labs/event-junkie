#!/usr/bin/env python3
"""Scan Markdown for the structural ASD-STE100 rules and print one finding per line.

Run it through `ste-lint.sh`, which owns the verbs, the baseline and the ratchet.
This half only measures. It takes file paths as arguments, needs nothing outside
the standard library, and writes `<area>\\t<file>:<line>\\t<type>\\t<detail>` to
stdout.

Only the structural rules are here. The lexical half of the standard is defined
by an approved dictionary of ~900 words that this repository cannot carry, so
nothing printed here implies dictionary compliance.
"""

import bisect
import os
import re
import sys

MAX_SENTENCE = int(os.environ.get("STE_LINT_MAX_SENTENCE", "25"))
MAX_STEP_SENTENCE = int(os.environ.get("STE_LINT_MAX_STEP_SENTENCE", "20"))
MIN_LINES_FOR_SUMMARY = int(os.environ.get("STE_LINT_MIN_LINES_FOR_SUMMARY", "150"))

EXEMPT = {"docs/BRANDING.md", "docs/LOGO_IDEAS.md"}
SHORT_VERSION = "## The short version"
# A rendered document is not editable prose: the generator would overwrite the fix, and the words
# come from wherever it reads. docs/data-quality/ACCEPTED_LIMITATIONS.md is the first of these.
GENERATED = re.compile(r"<!--[^>]*do not edit", re.IGNORECASE)

FENCE = re.compile(r"^\s*(`{3,}|~{3,})(.*)$")
HEADING = re.compile(r"^\s*#{1,6}\s")
AMENDMENT = re.compile(r"^\s*#{1,6}\s+(Amendment|Update)\b", re.IGNORECASE)
LIST_ITEM = re.compile(r"^(\s*)(?:[-*+]|(\d+)\.)\s+(.*)$")
QUOTE = re.compile(r"^(\s*)>[ \t]?")
ALLOW = re.compile(r"<!--\s*ste-lint:\s*allow(.*?)-->", re.IGNORECASE | re.DOTALL)

# Emphasis and links are already stripped by the time this runs, so a sentence can
# only open with a letter or a bracket.
BOUNDARY = re.compile(r"(?<=[.!?])[\"')\]]*\s+(?=[A-Z(\[\u00a7])")

# Case-insensitive, so "No." over-protects "no." and under-splits rather than over-splits.
# That is the safer direction: an over-split sentence measures short and passes.
ABBREVIATIONS = """e.g i.e etc vs cf approx no nos fig eq resp incl excl
    Mr Mrs Ms Dr Prof St Inc Ltd Co Ch Sec al ca
    Jan Feb Mar Apr Jun Jul Aug Sep Sept Oct Nov Dec
    a.m p.m U.S U.K min max sec hr yr vol""".split()

PARTICIPLE = r"[a-z]+(?:ed|en)"
IRREGULAR = (
    r"been|done|gone|made|put|kept|held|come|become|run|written|seen|taken|given"
    r"|known|shown|grown|drawn|thrown|built|sent|spent|left|lost|meant|dealt|felt"
    r"|got|gotten|had|read|said|set|cut|hit|let|shut|split|spread"
)
PERFECT = re.compile(
    r"\b(has|have|had)\s+(?:(?:not|never|already|just|also|only|since|now|always)\s+)*"
    r"(" + PARTICIPLE + r"|" + IRREGULAR + r")\b"
)
# Adverbs that end in -en and would otherwise be read as the participle.
NOT_PARTICIPLES = {"often", "even", "then", "when", "again", "open", "seven", "ten", "between"}


def area(path):
    if path.startswith("docs/adr/"):
        return "docs/adr"
    if path.startswith("docs/ops/"):
        return "docs/ops"
    return "docs"


def clean(text):
    """Strip everything that is markup rather than prose, and is not a sentence."""
    text = re.sub(r"!\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"\[([^\]]*)\]\[[^\]]*\]", r"\1", text)
    # A placeholder has to survive both halves of the split. A single capital ("X") is what protect()
    # reads as an initial, so it hides the full stop before it; a lowercase word cannot open a
    # sentence, so it hides the full stop after it. A capitalised word is neither.
    text = re.sub(r"`[^`]*`", "Code", text)
    text = re.sub(r"<https?://[^>]*>", "Link", text)
    text = re.sub(r"https?://\S+", "Link", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"&[a-zA-Z#0-9]+;", " ", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"\1", text)
    text = re.sub(r"\*([^*]+)\*", r"\1", text)
    text = re.sub(r"(?<![\w`])_([^_]+)_(?![\w`])", r"\1", text)
    text = re.sub(r"~~([^~]+)~~", r"\1", text)
    # Prose is wrapped where the author put the newline, so a bold span often opens on one line and
    # closes on the next, and neither half pairs up. What survives matters: a sentence ending in
    # `.**` has no boundary after the full stop, so it silently absorbs the sentence after it.
    text = text.replace("**", "")
    return text


def protect(text):
    """Hide the periods that are not full stops, so the split does not fire on them."""
    text = re.sub(r"(\d)\.(\d)", "\\1\x00\\2", text)
    text = text.replace("...", "\x01")
    text = re.sub(r"\b([A-Z])\.", "\\1\x00", text)
    for word in ABBREVIATIONS:
        stem = re.escape(word)
        text = re.sub(r"(?<![\w.])" + stem + r"\.", word + "\x00", text, flags=re.IGNORECASE)
    return text


def restore(text):
    return text.replace("\x00", ".").replace("\x01", "...")


class Block:
    """One paragraph or one list item, with the source line of every character."""

    def __init__(self, start, is_step):
        self.start = start
        self.is_step = is_step
        self.text = ""
        self.offsets = []
        self.lines = []

    def add(self, lineno, text):
        if self.text:
            self.text += " "
        self.offsets.append(len(self.text))
        self.lines.append(lineno)
        self.text += text

    def line_at(self, offset):
        return self.lines[max(0, bisect.bisect_right(self.offsets, offset) - 1)]


def blocks_of(lines, findings, path):
    """Walk the file, yielding prose blocks and reporting what is not prose-shaped."""
    current = None
    fence = None
    allow = False
    pending_allow = False

    def close():
        nonlocal current, allow, pending_allow
        if current is not None and current.text.strip():
            yielded = (current, allow)
            current = None
            allow = pending_allow
            pending_allow = False
            return yielded
        current = None
        allow = pending_allow
        pending_allow = False
        return None

    for lineno, raw in enumerate(lines, 1):
        # A blockquote here is a callout, not a quotation — the warnings a reader most needs to
        # parse correctly. Strip the marker and read what is inside it as ordinary prose.
        raw = QUOTE.sub("", raw, count=1)

        if fence is not None:
            if raw.strip().startswith(fence):
                fence = None
            continue

        match = FENCE.match(raw)
        if match:
            done = close()
            if done:
                yield done
            fence = match.group(1)[0] * 3
            continue

        directive = ALLOW.search(raw)
        if directive:
            reason = directive.group(1).strip(" :\t")
            if not reason:
                findings.append((path, lineno, "bare-suppression", "no reason given"))
            pending_allow = True
            continue

        if AMENDMENT.match(raw):
            findings.append(
                (path, lineno, "amendment", "a document states the rule, not its history")
            )

        stripped = raw.strip()
        if not stripped or HEADING.match(raw) or stripped.startswith(("|", "<!--")):
            done = close()
            if done:
                yield done
            continue

        item = LIST_ITEM.match(raw)
        if item:
            done = close()
            if done:
                yield done
            current = Block(lineno, item.group(2) is not None)
            current.add(lineno, clean(item.group(3)))
            continue

        if current is None:
            current = Block(lineno, False)
        current.add(lineno, clean(stripped))

    done = close()
    if done:
        yield done


def check_block(block, path, findings):
    text = block.text
    for match in re.finditer(r";", text):
        findings.append(
            (path, block.line_at(match.start()), "semicolon", "STE bans the mark outright")
        )

    for match in PERFECT.finditer(text):
        if match.group(2).lower() in NOT_PARTICIPLES:
            continue
        findings.append(
            (
                path,
                block.line_at(match.start()),
                "present-perfect",
                '"%s" — use the simple tense' % match.group(0),
            )
        )

    cap = MAX_STEP_SENTENCE if block.is_step else MAX_SENTENCE
    offset = 0
    counted = 0
    for part in BOUNDARY.split(protect(text)):
        sentence = restore(part)
        words = len(sentence.split())
        if words < 3:
            offset += len(part) + 1
            continue
        counted += 1
        if words > cap:
            findings.append(
                (
                    path,
                    block.line_at(offset),
                    "long-sentence",
                    "%d words, cap is %d" % (words, cap),
                )
            )
        offset += len(part) + 1
    return counted


def scan(path):
    findings = []
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")

    if any(GENERATED.search(line) for line in lines[:10]):
        return findings, 0

    if len(lines) > MIN_LINES_FOR_SUMMARY and SHORT_VERSION not in "\n".join(lines):
        findings.append(
            (
                path,
                1,
                "no-short-version",
                "%d lines and no %s" % (len(lines), SHORT_VERSION),
            )
        )

    sentences = 0
    for block, allow in blocks_of(lines, findings, path):
        if not allow:
            sentences += check_block(block, path, findings)
    return findings, sentences


def main(argv):
    stats = "--stats" in argv
    paths = [arg for arg in argv if not arg.startswith("--")]

    out = []
    totals = {}
    for path in paths:
        if path in EXEMPT:
            continue
        findings, sentences = scan(path)
        out.extend(findings)
        counts = totals.setdefault(area(path), [0, 0])
        counts[0] += sentences
        counts[1] += sum(1 for f in findings if f[2] == "long-sentence")

    if stats:
        for name in sorted(totals):
            print("%s\t%d\t%d" % (name, totals[name][0], totals[name][1]))
        return

    out.sort(key=lambda f: (f[0], f[1]))
    for path, lineno, kind, detail in out:
        print("%s\t%s:%d\t%s\t%s" % (area(path), path, lineno, kind, detail))


if __name__ == "__main__":
    main(sys.argv[1:])
