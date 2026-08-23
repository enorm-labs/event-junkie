#!/usr/bin/env python3
"""Lay out a string in a font and emit it as an SVG path.

Brand artwork in `docs/branding/` carries **outlined glyphs, not `<text>`**, and
this is what produced them. Outlining is not a nicety: a renderer substitutes a
missing font *silently*, so a `<text>` logo renders in a fallback face and looks
entirely fine while being the wrong typeface. That happened during #475 — a badge
drew in clean Helvetica and nobody could tell until the same string was rendered
with a deliberately nonexistent family name and the two files came out
byte-identical.

Run it through `outline-text.sh`, which pins fontTools. The pin matters for the
same reason `format-markdown.sh` pins oxfmt: this emits coordinates, and a
version that rounds differently would silently redraw committed artwork.

Prints the path data on stdout, and the metrics that decide whether it fits on
stderr — so `d=$(outline-text.sh ...)` captures only the path.

    scripts/outline-text.sh --font Rubik.ttf --text "EVENT JUNKIE" \
        --size 36 --x 160 --baseline 62

Fitting is the thing to check, not the thing to assume. The German caption
overflowed its frame at the English settings, and the advance width printed here
is what catches that before it reaches a screenshot.
"""

import argparse
import os
import sys

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.misc.transform import Transform
from fontTools.ttLib import TTFont


def outline(font_path, text, size, x, baseline, tracking, anchor, weight, precision):
    if not os.path.isfile(font_path):
        sys.exit(f"error: no such font: {font_path}")
    try:
        font = TTFont(font_path)
    except Exception as exc:  # noqa: BLE001 — the cause is what the user needs, not the class
        # A .woff2 raises here when brotli is missing, which is the one failure
        # worth naming: outline-text.sh installs it, running the .py directly does not.
        sys.exit(f"error: could not read {font_path}: {exc}")

    # A variable font has to be pinned to an instance first: drawing from one
    # without doing so silently gives you the default master, which for most
    # families is Regular even when Bold was asked for.
    if weight is not None and "fvar" in font:
        from fontTools.varLib.instancer import instantiateVariableFont

        font = instantiateVariableFont(font, {"wght": weight}, inplace=False)

    upm = font["head"].unitsPerEm
    glyphs, cmap, hmtx = font.getGlyphSet(), font.getBestCmap(), font["hmtx"]

    missing = sorted({c for c in text if ord(c) not in cmap})
    if missing:
        sys.exit(f"error: {font_path} has no glyph for {missing!r}")

    scale = size / upm
    advances = [hmtx[cmap[ord(c)]][0] * scale for c in text]
    width = sum(advances) + tracking * (len(text) - 1)

    # `middle` centres the ADVANCE width, which is what a text-anchor does. The
    # ink is usually a little narrower, so the bounds below are what to check
    # against a frame — not this.
    start = x - width / 2 if anchor == "middle" else x

    fmt = lambda v: f"{v:.{precision}f}"  # noqa: E731 — the pen wants a callable
    pen, bounds = SVGPathPen(glyphs, ntos=fmt), BoundsPen(glyphs)
    cursor = start
    for char, advance in zip(text, advances):
        glyph = glyphs[cmap[ord(char)]]
        # Negative y-scale because font space is y-up and SVG is y-down.
        transform = Transform(scale, 0, 0, -scale, cursor, baseline)
        glyph.draw(TransformPen(pen, transform))
        glyph.draw(TransformPen(bounds, transform))
        cursor += advance + tracking

    return pen.getCommands(), width, bounds.bounds


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--font", required=True, help="path to a .ttf, .otf or .woff2")
    p.add_argument("--text", required=True)
    p.add_argument("--size", type=float, required=True, help="font size in the target viewBox's units")
    p.add_argument("--x", type=float, default=0.0)
    p.add_argument("--baseline", type=float, required=True, help="y of the baseline, in SVG coordinates")
    p.add_argument("--tracking", type=float, default=0.0, help="extra space per gap, in viewBox units")
    p.add_argument("--anchor", choices=("start", "middle"), default="middle")
    p.add_argument("--weight", type=float, default=None, help="wght axis value for a variable font, e.g. 700")
    p.add_argument("--precision", type=int, default=2, help="decimal places; more is bigger, not better")
    p.add_argument("--fits", type=float, default=None, metavar="WIDTH", help="fail if the advance width exceeds this")
    args = p.parse_args()

    path, width, bounds = outline(
        args.font, args.text, args.size, args.x, args.baseline,
        args.tracking, args.anchor, args.weight, args.precision,
    )

    print(f"advance width : {width:.2f}", file=sys.stderr)
    if bounds:
        x0, y0, x1, y1 = bounds
        print(f"ink bounds    : x {x0:.2f}..{x1:.2f}  y {y0:.2f}..{y1:.2f}", file=sys.stderr)
        print(f"ink is centred on x {(x0 + x1) / 2:.2f}, y {(y0 + y1) / 2:.2f}", file=sys.stderr)
    print(f"path length   : {len(path)} chars", file=sys.stderr)

    if args.fits is not None and width > args.fits:
        sys.exit(f"error: advance width {width:.2f} exceeds --fits {args.fits:.2f}")

    print(path)


if __name__ == "__main__":
    main()
