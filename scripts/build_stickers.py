#!/usr/bin/env python3
"""Build the print-ready launch stickers in docs/branding/stickers/ (#481).

Three Flyeralarm products, each built to its own datasheet:

  main     Outdoor-Aufkleber, rectangle 85 x 55 mm, white film, 4/0. Four motifs: English and German, ink and night.
  neon     Neon-Aufkleber, 85 x 55 mm, red fluorescent paper, black only (1/0). The main layout; the paper glows in UV.
  glow     Leuchtaufkleber, 85 x 55 mm, glow-in-the-dark film, 4/0. The night layout with every light element left
           unprinted, so in the dark only the stamp, the type and the QR panel glow.
  mini     Outdoor-Aufkleber, square 50 x 50 mm, white film, 4/0. The stamp badge, a QR code and the URL; ink and night.
  clear    Outdoor-Aufkleber, rectangle 105 x 35 mm, 90 um clear film, 4/0. A series of eight caption lines. The film
           takes no white ink, so the ink is translucent and the QR code reads on light surfaces only.
  diecut   Selbstklebefolie Freiform, one DIN A3 sheet with fifteen stamps, each cut along its tilted frame.

Each design is composed as an SVG in millimetres, rendered to an RGB PDF by rsvg-convert, then rewritten as DeviceCMYK
with set ink values. A converter would turn #111111 into a four-ink grey, and small type and QR modules would then
depend on register. Every RGB value in the artwork is therefore a marker for exactly one ink, and an unmapped one fails
the build. The die-cut sheet also gets its cut paths, in the CutContour spot colour the Freiform datasheet asks for.

Run it through build-stickers.sh, which pins the packages this imports.
"""

import argparse
import math
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BRANDING = REPO / "docs/branding"
GEIST = REPO / "events-frontend/node_modules/@fontsource-variable/geist/files/geist-latin-wght-normal.woff2"

sys.path.insert(0, str(REPO / "scripts"))
from outline_text import outline  # noqa: E402

PT = 72 / 25.4
BASE_URL = "https://event-junkie.de/?s="

INK, PAPER, INK_TINT, NIGHT = "#111111", "#ffffff", "#333333", "#121212"
VIOLET_LIGHT, VIOLET_DARK = "#823feb", "#b085ff"
TILE = "#d6d8dc"  # preview only: the light surface a clear sticker is meant for
NEON_RED, GLOW_FILM, GLOW_LIT, DARK = "#ff4a3d", "#eef2c4", "#c6ff6e", "#050505"  # preview only: stock and glow

CMYK = {
    PAPER: (0, 0, 0, 0),  # the white film is the white
    INK: (0, 0, 0, 1),
    INK_TINT: (0, 0, 0, 0.8),
    NIGHT: (0.4, 0.3, 0.3, 1),  # rich black; white type knocks out of it
    VIOLET_LIGHT: (0.65, 0.8, 0, 0),  # outside the CMYK gamut, so it prints duller than on screen
    VIOLET_DARK: (0.4, 0.6, 0, 0),
}


@dataclass(frozen=True)
class Sheet:
    """A product's trim size and the bleed and safe zone its Flyeralarm datasheet sets."""

    w: float
    h: float
    bleed: float
    safe: float
    corner: float = 0.0


MAIN = Sheet(85, 55, 3, 3, corner=2)
CLEAR = Sheet(105, 35, 3, 3, corner=2)
MINI = Sheet(50, 50, 1, 4, corner=2)
A3 = Sheet(297, 420, 5, 5)

EN_STAMP, DE_STAMP = "lockup-club-stamp-tagline-en.svg", "lockup-club-stamp-tagline-de.svg"
EN = (EN_STAMP, "What’s on tonight?", "No ads. No tracking.")
DE = (DE_STAMP, "Was geht heute?", "Keine Werbung. Kein Tracking.")
INK_WAY = {"ground": PAPER, "ink": INK, "sub": INK_TINT, "accent": VIOLET_LIGHT, "qr_panel": False}
NIGHT_WAY = {"ground": NIGHT, "ink": PAPER, "sub": PAPER, "accent": VIOLET_DARK, "qr_panel": True}
# The stock is the colour: nothing prints but black, and the preview shows the paper underneath.
NEON_WAY = {"ground": None, "ink": INK, "sub": INK, "accent": INK, "qr_panel": False, "stock": NEON_RED}
# Unprinted film is what glows, so every light element is PAPER (no ink) on a rich-black ground.
GLOW_WAY = {"ground": NIGHT, "ink": PAPER, "sub": PAPER, "accent": PAPER, "qr_panel": True, "glow": True}

# Every QR code differs, so the nginx access log can count scans per design (#1126).
MAIN_MOTIFS = {
    "en-ink": ("sei", EN, INK_WAY),
    "en-night": ("sen", EN, NIGHT_WAY),
    "de-ink": ("sdi", DE, INK_WAY),
    "de-night": ("sdn", DE, NIGHT_WAY),
    "neon-en": ("ne", EN, NEON_WAY),
    "neon-de": ("nd", DE, NEON_WAY),
    "glow-en": ("ge", EN, GLOW_WAY),
    "glow-de": ("gd", DE, GLOW_WAY),
}
CLEAR_SERIES = {
    "en": ["FEED THE HABIT", "HIGHLY ADDICTIVE", "NEVER MISS A HIT", "NOTHING ON TONIGHT? UNLIKELY."],
    "de": ["MACHT SÜCHTIG", "NIE WIEDER FOMO", "HEUTE NIX LOS? IN BERLIN?", "KEINE PARTY VERPASSEN"],
}
DIECUT_WAYS = [("black", INK, PAPER), ("violet", VIOLET_LIGHT, PAPER), ("night", PAPER, NIGHT)]


def text_path(text, size, x, baseline, weight, anchor="start"):
    d, _, _ = outline(str(GEIST), text, size, x, baseline, 0.0, anchor, weight, 3)
    return d


def stamp_source(lockup, caption=None):
    """The stamp artwork, optionally with its caption re-set at the adopted settings (Geist 700, 10, tracking 3.4)."""
    src = re.sub(r"<!--.*?-->", "", (BRANDING / lockup).read_text(), flags=re.S)
    if caption is not None:
        old = re.findall(r'<path d="([^"]+)" fill="currentColor" />', src)[1]
        d, width, _ = outline(str(GEIST), caption, 10, 160, 84, 3.4, "middle", 700, 2)
        if width > 270:
            sys.exit(f"error: caption {caption!r} is {width:.0f} units wide, the inner frame takes 270")
        src = src.replace(old, d)
    return src


def nest(src, x, y, width, color, suffix):
    """A brand file inlined as a nested <svg>; its ids get a suffix so two placements cannot collide."""
    view_box = re.search(r'viewBox="([^"]+)"', src).group(1)
    vw, vh = map(float, view_box.split()[2:])
    body = src[src.index(">", src.index("<svg")) + 1 : src.rindex("</svg>")]
    for old in set(re.findall(r'id="([^"]+)"', body)):
        body = body.replace(f'id="{old}"', f'id="{old}-{suffix}"').replace(f"url(#{old})", f"url(#{old}-{suffix})")
    frame = f'x="{x}" y="{y}" width="{width}" height="{width * vh / vw:.3f}" viewBox="{view_box}"'
    return f'<svg {frame} style="color:{color}">{body}</svg>'


def qr_path(url, x, y, size):
    import segno

    qr = segno.make(url, error="q", micro=False)
    m = size / qr.symbol_size(border=0)[0]
    cells = (
        f"M{x + c * m:.3f} {y + r * m:.3f}h{m:.3f}v{m:.3f}h-{m:.3f}z"
        for r, row in enumerate(qr.matrix)
        for c, dark in enumerate(row)
        if dark
    )
    return "".join(cells), m


def document(sheet, inner):
    b = sheet.bleed
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{sheet.w + 2 * b}mm" height="{sheet.h + 2 * b}mm" '
        f'viewBox="{-b} {-b} {sheet.w + 2 * b} {sheet.h + 2 * b}">{inner}</svg>'
    )


def trimmed(sheet, inner, surface=None, pad=0.0):
    """The sticker as it comes off the sheet, on an optional surface for the preview."""
    s = sheet
    under = f'<rect x="{-pad}" y="{-pad}" width="{s.w + 2 * pad}" height="{s.h + 2 * pad}" fill="{surface}"/>'
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{s.w + 2 * pad}mm" height="{s.h + 2 * pad}mm" '
        f'viewBox="{-pad} {-pad} {s.w + 2 * pad} {s.h + 2 * pad}">{under if surface else ""}'
        f'<clipPath id="trim"><rect width="{s.w}" height="{s.h}" rx="{s.corner}"/></clipPath>'
        f'<g clip-path="url(#trim)">{inner}</g></svg>'
    )


def ground(sheet, colour):
    b = sheet.bleed
    return f'<rect x="{-b}" y="{-b}" width="{sheet.w + 2 * b}" height="{sheet.h + 2 * b}" fill="{colour}"/>'


def main_motif(name, code, language, way):
    s, qs = MAIN, 19.5
    lockup, cta, sub = language
    qx, qy = s.w - s.safe - 1 - qs, s.h - s.safe - qs
    qr, module = qr_path(BASE_URL + code, qx, qy, qs)
    panel = ""
    if way["qr_panel"]:
        quiet = 4 * module  # the quiet zone the QR specification asks for
        box = f'x="{qx - quiet:.3f}" y="{qy - quiet:.3f}" width="{qs + 2 * quiet:.3f}" height="{qs + 2 * quiet:.3f}"'
        panel = f'<rect {box} rx="1.5" fill="{PAPER}"/>'
    x = s.safe + 1
    art = (
        (ground(s, way["ground"]) if way["ground"] else "")
        + nest(stamp_source(lockup), x, s.safe, s.w - 2 * x, way["ink"], name)
        + f'<path d="{text_path(cta, 5.0, x, 37.0, 680)}" fill="{way["accent"]}"/>'
        + f'<path d="{text_path(sub, 3.4, x, 42.4, 420)}" fill="{way["sub"]}"/>'
        + f'<path d="{text_path("event-junkie.de", 4.2, x, s.h - s.safe - 0.6, 600)}" fill="{way["ink"]}"/>'
        + f'{panel}<path d="{qr}" fill="{INK}"/>'
    )
    if "stock" in way:
        preview = trimmed(s, ground(s, way["stock"]) + art)
    elif way.get("glow"):
        preview = side_by_side(s, glow_preview(art, lit=False), glow_preview(art, lit=True))
    else:
        preview = trimmed(s, art)
    return document(s, art), preview, BASE_URL + code


def glow_preview(art, lit):
    """By day the unprinted film is pale yellow; in the dark it is the only thing that shows."""
    colours = {PAPER: GLOW_LIT, NIGHT: DARK, INK: DARK} if lit else {PAPER: GLOW_FILM}
    for marker, shown in colours.items():
        art = art.replace(marker, shown)
    return art


def side_by_side(sheet, left, right, gap=6.0):
    s = sheet
    clip = f'<clipPath id="trim"><rect width="{s.w}" height="{s.h}" rx="{s.corner}"/></clipPath>'
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{2 * s.w + gap}mm" height="{s.h}mm" '
        f'viewBox="0 0 {2 * s.w + gap} {s.h}">{clip}<g clip-path="url(#trim)">{left}</g>'
        f'<g transform="translate({s.w + gap} 0)"><g clip-path="url(#trim)">{right}</g></g></svg>'
    )


def mini_motif(name, code, way):
    """The stamp badge beside a QR code, the URL beneath. For the gaps between other stickers."""
    s, badge, qs, gap = MINI, 18.0, 18.5, 4.5
    top = 10.5
    x = s.safe
    qx = x + badge + gap
    qr, module = qr_path(BASE_URL + code, qx, top, qs)
    panel = ""
    if way["qr_panel"]:
        quiet = 4 * module
        box = f'x="{qx - quiet:.3f}" y="{top - quiet:.3f}" width="{qs + 2 * quiet:.3f}" height="{qs + 2 * quiet:.3f}"'
        panel = f'<rect {box} rx="1" fill="{PAPER}"/>'
    art = (
        ground(s, way["ground"])
        + nest(stamp_source("mark-ej-badge-stamp.svg"), x, top + (qs - badge) / 2, badge, way["ink"], name)
        + f'{panel}<path d="{qr}" fill="{INK}"/>'
        + f'<path d="{text_path("event-junkie.de", 4.6, s.w / 2, top + qs + 8.5, 600, "middle")}" fill="{way["ink"]}"/>'
    )
    return document(s, art), trimmed(s, art), BASE_URL + code


def clear_motif(name, code, lockup, caption):
    """Violet ink alone. The film is clear and takes no white, so the QR modules sit straight on the surface."""
    s, qs = CLEAR, 22.0
    qx, qy = s.w - s.safe - 1 - qs, (s.h - qs) / 2
    qr, _ = qr_path(BASE_URL + code, qx, qy, qs)
    stamp_w = qx - 4 - (s.safe + 1)
    x = s.safe + 1
    art = (
        nest(stamp_source(lockup, caption), x, s.safe, stamp_w, VIOLET_LIGHT, name)
        + f'<path d="{text_path("event-junkie.de", 3.4, x + stamp_w / 2, s.h - s.safe - 0.4, 600, "middle")}" '
        f'fill="{VIOLET_LIGHT}"/><path d="{qr}" fill="{VIOLET_LIGHT}"/>'
    )
    # Clear-film ink is translucent: multiply is how it meets the surface in the preview.
    preview = trimmed(s, f'<g style="mix-blend-mode:multiply">{art}</g>', TILE, pad=4)
    return document(s, art), preview, BASE_URL + code


def tilted_rect(cx, cy, hw, hh, r, angle):
    """A rounded rectangle about (cx, cy), rotated by angle degrees as SVG's rotate() does, as SVG path data."""
    k = 0.5523 * r  # the cubic handle length that approximates a quarter circle
    a = math.radians(angle)

    def p(x, y):
        return f"{cx + x * math.cos(a) - y * math.sin(a):.3f} {cy + x * math.sin(a) + y * math.cos(a):.3f}"

    w, h = hw, hh
    return (
        f"M{p(-w + r, -h)}L{p(w - r, -h)}C{p(w - r + k, -h)} {p(w, -h + r - k)} {p(w, -h + r)}"
        f"L{p(w, h - r)}C{p(w, h - r + k)} {p(w - r + k, h)} {p(w - r, h)}"
        f"L{p(-w + r, h)}C{p(-w + r - k, h)} {p(-w, h - r + k)} {p(-w, h - r)}"
        f"L{p(-w, -h + r)}C{p(-w, -h + r - k)} {p(-w + r - k, -h)} {p(-w + r, -h)}Z"
    )


def diecut_sheet():
    """Fifteen stamps on one A3 sheet, five per colourway, each cut 2.5 mm outside its tilted outer frame.

    The datasheet's rules decide the numbers: artwork 4 mm past each cut, 10 mm between cuts, cuts 5 mm inside the
    trim, at most fifteen closed cuts, none thinner than 5 mm.
    """
    s, stamp_w, cols, rows = A3, 80.0, 3, 5
    scale = stamp_w / 320
    stamp_h = 110 * scale
    # The outer frame edge spans 5.5..314.5 x 7.5..102.5 in stamp units, tilted -2.5 degrees about (160, 55).
    half_w, half_h = 154.5 * scale + 2.5, 47.5 * scale + 2.5
    tilt = math.radians(2.5)
    box_w = 2 * (half_w * math.cos(tilt) + half_h * math.sin(tilt))
    box_h = 2 * (half_w * math.sin(tilt) + half_h * math.cos(tilt))
    gap_x = (s.w - cols * box_w) / (cols + 1)
    gap_y = (s.h - rows * box_h) / (rows + 1)
    if min(gap_x, gap_y) < 10:
        sys.exit(f"error: die-cut layout leaves {min(gap_x, gap_y):.1f} mm between cuts, the datasheet asks for 10")
    src = stamp_source(EN_STAMP, "EVENT-JUNKIE.DE")
    art, cuts, guides = ground(s, PAPER), [], ""
    for i in range(cols * rows):
        col, row = i % cols, i // cols
        name, ink, paper = DIECUT_WAYS[col]
        cx = gap_x + box_w / 2 + col * (box_w + gap_x)
        cy = gap_y + box_h / 2 + row * (box_h + gap_y)
        art += f'<path d="{tilted_rect(cx, cy, half_w + 4, half_h + 4, 5.5, -2.5)}" fill="{paper}"/>'
        art += nest(src, cx - stamp_w / 2, cy - stamp_h / 2, stamp_w, ink, f"dc{i}")
        cut = tilted_rect(cx, cy, half_w, half_h, 1.5, -2.5)
        cuts.append(cut)
        guides += f'<path d="{cut}" fill="none" stroke="#e5007d" stroke-width="0.35"/>'
    return document(s, art), document(s, art + guides), cuts


def rgb_key(r, g, b):
    return tuple(round(float(v), 3) for v in (r, g, b))


INKS = {rgb_key(*(int(h[i : i + 2], 16) / 255 for i in (1, 3, 5))): cmyk for h, cmyk in CMYK.items()}
COLOUR_OP = re.compile(rb"(-?[\d.]+) (-?[\d.]+) (-?[\d.]+) (rg|RG)\b")


def svg_path_to_pdf(d, sheet):
    """SVG path data in sheet millimetres (M, L, C, Z only) as PDF operators in points, y flipped."""
    out = []
    for op, args in re.findall(r"([MLCZ])([^MLCZ]*)", d):
        nums = [float(v) for v in args.split()]
        pts = [
            f"{(x + sheet.bleed) * PT:.3f} {(sheet.h + sheet.bleed - y) * PT:.3f}"
            for x, y in zip(nums[::2], nums[1::2], strict=True)
        ]
        out.append("h" if op == "Z" else f"{' '.join(pts)} {dict(M='m', L='l', C='c')[op]}")
    return " ".join(out)


def to_cmyk(src, dst, sheet, cuts=()):
    """Rewrite rsvg's RGB operators as CMYK ones and set the boxes; add the cut paths as overprinting CutContour."""
    import pikepdf

    def swap(match):
        key = rgb_key(*match.groups()[:3])
        if key not in INKS:
            sys.exit(f"error: {dst.name}: no CMYK value for rgb {key}; add the colour to CMYK")
        op = b" k" if match.group(4) == b"rg" else b" K"
        return b" ".join(f"{v:g}".encode() for v in INKS[key]) + op

    pdf = pikepdf.open(src)
    # Soft-mask forms hold mask values, not ink, and stay as they are.
    dicts = (o for o in pdf.objects if isinstance(o, pikepdf.Dictionary))
    masks = {o.G.objgen for o in dicts if o.get("/Type") == "/Mask" and "/G" in o}
    page = pdf.pages[0]
    contents = page.obj.Contents
    streams = list(contents) if isinstance(contents, pikepdf.Array) else [contents]
    pending, seen = list(page.Resources.get("/XObject", {}).values()), set()
    while pending:
        form = pending.pop()
        if form.objgen in seen or form.objgen in masks:
            continue
        seen.add(form.objgen)
        streams.append(form)
        if "/Group" in form:
            form.Group.CS = pikepdf.Name.DeviceCMYK
        pending.extend(form.get("/Resources", {}).get("/XObject", {}).values())
    for stream in streams:
        stream.write(COLOUR_OP.sub(swap, stream.read_bytes()))
    if "/Group" in page.obj:
        page.obj.Group.CS = pikepdf.Name.DeviceCMYK
    if cuts:
        tint = pdf.make_indirect(
            pikepdf.Dictionary(FunctionType=2, Domain=[0, 1], C0=[0, 0, 0, 0], C1=[0, 1, 0, 0], N=1)
        )
        res = page.Resources
        res.ColorSpace = res.get("/ColorSpace", pikepdf.Dictionary())
        res.ColorSpace.CSCut = pikepdf.Array(
            [pikepdf.Name.Separation, pikepdf.Name("/CutContour"), pikepdf.Name.DeviceCMYK, tint]
        )
        res.ExtGState = res.get("/ExtGState", pikepdf.Dictionary())
        res.ExtGState.GSCut = pikepdf.Dictionary(Type=pikepdf.Name.ExtGState, OP=True, op=True, OPM=1)
        paths = "\n".join(f"{svg_path_to_pdf(c, sheet)} S" for c in cuts)
        ops = f"Q q /GSCut gs /CSCut CS 1 SCN 1 w\n{paths}\nQ".encode()
        # rsvg leaves its y-flip in force at the end of the page, so its content is wrapped before the cuts are drawn.
        own = streams[: len(streams) - len(seen)]
        page.obj.Contents = pikepdf.Array([pdf.make_stream(b"q"), *own, pdf.make_stream(ops)])
    x0, y0, x1, y1 = (float(v) for v in page.MediaBox)
    b = sheet.bleed * PT
    page.obj.BleedBox = pikepdf.Array([x0, y0, x1, y1])
    page.obj.TrimBox = pikepdf.Array([x0 + b, y0 + b, x1 - b, y1 - b])
    # rsvg stamps a creation date; without it, and with a fixed id, a rebuild on the same pins is byte-identical.
    if "/Info" in pdf.trailer:
        del pdf.trailer["/Info"]
    pdf.save(dst, deterministic_id=True)


def check_qr(png, url):
    """Decode the preview at full size and at a third of it, the closest this gets to a phone at arm's length."""
    import zxingcpp
    from PIL import Image

    image = Image.open(png).convert("RGB")
    for scale in (1, 3):
        small = image.resize((image.width // scale, image.height // scale))
        found = [b.text for b in zxingcpp.read_barcodes(small)]
        if not found or any(text != url for text in found):
            sys.exit(f"error: {png.name} at 1/{scale} decodes as {found}, expected {url}")


def designs():
    """Every design: (file stem, sheet, print SVG, preview SVG, QR URL or None, cut paths)."""
    for name, (code, language, way) in MAIN_MOTIFS.items():
        bled, preview, url = main_motif(name, code, language, way)
        yield f"sticker-{name}-85x55", MAIN, bled, preview, url, ()
    for name, code, way in (("ink", "mi", INK_WAY), ("night", "mn", NIGHT_WAY)):
        bled, preview, url = mini_motif(f"mini-{name}", code, way)
        yield f"sticker-mini-{name}-50x50", MINI, bled, preview, url, ()
    for lang, captions in CLEAR_SERIES.items():
        lockup = EN_STAMP if lang == "en" else DE_STAMP
        for i, caption in enumerate(captions, 1):
            code = f"c{lang[0]}{i}"
            bled, preview, url = clear_motif(f"clear-{lang}-{i}", code, lockup, caption)
            yield f"sticker-clear-{lang}-{i}-105x35", CLEAR, bled, preview, url, ()
    bled, preview, cuts = diecut_sheet()
    yield "sticker-diecut-sheet-a3", A3, bled, preview, None, cuts


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=BRANDING / "stickers", help="output directory")
    args = parser.parse_args()

    if not GEIST.is_file():
        sys.exit(f"error: no Geist at {GEIST.relative_to(REPO)} — run 'npm ci' in events-frontend/")
    version = subprocess.run(["rsvg-convert", "--version"], capture_output=True, text=True, check=True).stdout
    print(f"build-stickers: {version.splitlines()[0]}", file=sys.stderr)
    args.out.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        for stem, sheet, bled, preview, url, cuts in designs():
            (work / f"{stem}.svg").write_text(bled)
            (work / f"{stem}-preview.svg").write_text(preview)
            pdf, png = args.out / f"{stem}-cmyk.pdf", args.out / f"{stem}-preview.png"
            zoom = "1" if sheet is A3 else "3"
            run = ["rsvg-convert", "--output"]
            subprocess.run([*run, str(work / f"{stem}.pdf"), "--format", "pdf", str(work / f"{stem}.svg")], check=True)
            subprocess.run([*run, str(png), "--zoom", zoom, str(work / f"{stem}-preview.svg")], check=True)
            to_cmyk(work / f"{stem}.pdf", pdf, sheet, cuts)
            if url:
                check_qr(png, url)
            shown = pdf.relative_to(REPO) if pdf.is_relative_to(REPO) else pdf
            print(f"{shown}  {url or f'{len(cuts)} cut contours'}")


if __name__ == "__main__":
    main()
