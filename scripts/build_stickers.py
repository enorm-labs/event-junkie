#!/usr/bin/env python3
"""Build the print-ready launch stickers in docs/branding/stickers/ (#481).

Five Flyeralarm products, each built to its own datasheet:

  main     Outdoor-Aufkleber, rectangle 85 x 55 mm, white film, 4/0. Four motifs: English and German, ink and night.
  neon     Neon-Aufkleber, 85 x 55 mm, red fluorescent paper, black only (1/0). The main layout; the paper glows in UV.
  glow     Leuchtaufkleber, 85 x 55 mm, glow-in-the-dark film, 4/0. The night layout with every light element left
           unprinted, so in the dark only the stamp, the type and the QR panel glow.
  mini     Outdoor-Aufkleber, square 50 x 50 mm, white film, 4/0. The stamp badge, a QR code and the URL; ink and night.
  clear    Outdoor-Aufkleber, rectangle 105 x 35 mm, 90 um clear film, 4/0. A series of eight caption lines. The film
           takes no white ink, so the ink is translucent and the QR code reads on light surfaces only.
  diecut   Selbstklebefolie Freiform, one DIN A3 sheet with fifteen stamps, each cut along its tilted frame.

The stamp figure, the club stamp drawn as a grumpy bouncer who lets everyone in, carries a second set:

  weekcard Outdoor-Aufkleber, 85 x 55 mm, white film, 4/0. A stamp card for the week; QR code to /week.
  leaflet  Outdoor-Aufkleber, 85 x 55 mm, white film, 4/0. A medicine-leaflet parody; dose and side effects.
  night    Outdoor-Aufkleber, square 50 x 50 mm, white film, 4/0. Three hours of the night, one pose each.
  pin      Selbstklebefolie Freiform, one DIN A3 sheet per language with twelve map pins; QR code to /map.
  72h      Outdoor-Aufkleber, 85 x 55 mm, white film, 4/0. The 72-hour hot take, English only, no figure.

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
SITE = "https://event-junkie.de/"
BASE_URL = SITE + "?s="

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
# (stamp, call to action, subline, page): the QR code and the printed URL both open the page.
EN = (EN_STAMP, "What’s on tonight?", "No ads. No tracking.", "")
DE = (DE_STAMP, "Was geht heute?", "Keine Werbung. Kein Tracking.", "")
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

# The stamp figure: the club stamp as a grumpy bouncer who lets everyone in. Its copy is the stamp's job ("Gestempelt.")
# or the door's ("Gästeliste: alle."), never a feature list.
WEEK_CARD = {
    "de": (
        "WOCHENKARTE",
        ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"],
        "Jeden Abend ein Stempel.",
        "Was diese Woche läuft:",
    ),
    "en": ("WEEK CARD", ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"], "One stamp a night.", "What’s on this week:"),
}
WEEK_CARD_STAMPED = (True, False, True, True, True, True, False)
LEAFLET = {
    "de": (
        "Wirkstoff: Berlin. Über 250 Locations.",
        [
            ("Dosierung:", " 1 × täglich, abends."),
            ("", "Bei Bedarf bis zum Morgen."),
            ("Nebenwirkungen:", " Tinnitus,"),
            ("", "Muskelkater, neue Freunde."),
        ],
        ["Zu Risiken und Nebenwirkungen scannen Sie", "den Code oder fragen Sie den Türsteher."],
    ),
    "en": (
        "Active ingredient: Berlin. 250+ venues.",
        [
            ("Dose:", " once a night."),
            ("", "More if needed, until morning."),
            ("Side effects:", " tinnitus,"),
            ("", "sore legs, new friends."),
        ],
        ["For risks and side effects, scan the code", "or ask the bouncer."],
    ),
}
# The night in three poses, for the gaps between other stickers. Funnier side by side, so they are collected.
NIGHT_SERIES = {
    "2000": ("cross", "20:00", {"de": ["Noch zu", "früh."], "en": ["Too", "early."]}),
    "0200": ("dance", "02:00", {"de": ["Gästeliste:", "alle."], "en": ["Guest list:", "everyone."]}),
    "0800": ("sleep", "08:00", {"de": ["Gestempelt."], "en": ["Stamped."]}),
}
PIN = {"de": ("DU BIST HIER.", "Die Party nicht."), "en": ("YOU ARE HERE.", "The party isn’t.")}


def text_path(text, size, x, baseline, weight, anchor="start", room=None):
    d, width, _ = outline(str(GEIST), text, size, x, baseline, 0.0, anchor, weight, 3)
    if room is not None and width > room:
        sys.exit(f"error: {text!r} is {width:.1f} mm wide, the layout leaves {room:.1f}")
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
    lockup, cta, sub, page = language
    url = f"{SITE}{page}?s={code}"
    shown_url = "event-junkie.de" + (f"/{page}" if page else "")
    qx, qy = s.w - s.safe - 1 - qs, s.h - s.safe - qs
    qr, module = qr_path(url, qx, qy, qs)
    panel = ""
    if way["qr_panel"]:
        quiet = 4 * module  # the quiet zone the QR specification asks for
        box = f'x="{qx - quiet:.3f}" y="{qy - quiet:.3f}" width="{qs + 2 * quiet:.3f}" height="{qs + 2 * quiet:.3f}"'
        panel = f'<rect {box} rx="1.5" fill="{PAPER}"/>'
    x = s.safe + 1
    room = qx - 2 - x  # the text column ends 2 mm before the QR code
    art = (
        (ground(s, way["ground"]) if way["ground"] else "")
        + nest(stamp_source(lockup), x, s.safe, s.w - 2 * x, way["ink"], name)
        + f'<path d="{text_path(cta, 5.0, x, 37.0, 680, room=room)}" fill="{way["accent"]}"/>'
        + f'<path d="{text_path(sub, 3.4, x, 42.4, 420, room=room)}" fill="{way["sub"]}"/>'
        + f'<path d="{text_path(shown_url, 4.2, x, s.h - s.safe - 0.6, 600)}" fill="{way["ink"]}"/>'
        + f'{panel}<path d="{qr}" fill="{INK}"/>'
    )
    if "stock" in way:
        preview = trimmed(s, ground(s, way["stock"]) + art)
    elif way.get("glow"):
        preview = side_by_side(s, glow_preview(art, lit=False), glow_preview(art, lit=True))
    else:
        preview = trimmed(s, art)
    return document(s, art), preview, url


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


def stamp_figure(x, y, w, pose="stand", body=VIOLET_LIGHT, ink=INK, angle=0.0):
    """The stamp as a character: knob, neck, body with a face, the rubber base, arms and legs. 200 x 240 units."""
    k = w / 200
    line = f'stroke="{ink}" stroke-width="7" stroke-linecap="round" fill="none"'
    brows = "M66 92 L86 100 M134 92 L114 100"
    eyes = f'<circle cx="78" cy="112" r="8" fill="{ink}"/><circle cx="122" cy="112" r="8" fill="{ink}"/>'
    mouth = "M86 136 H114"
    arms = "M36 110 L12 132 M164 110 L188 132"
    if pose == "cross":
        arms = "M36 104 L120 134 M164 104 L80 134"
        mouth = "M88 140 H112"
    elif pose == "dance":
        brows, mouth, arms = "", "M84 132 C92 146 108 146 116 132", "M36 104 L10 60 M164 104 L190 60"
    elif pose == "sleep":
        brows, eyes, mouth = "", "", "M90 138 C96 132 104 132 110 138"
        eyes = f'<path d="M66 112 C72 120 84 120 90 112 M110 112 C116 120 128 120 134 112" {line}/>'
    elif pose == "wave":
        brows, mouth, arms = "", "M86 132 C94 142 106 142 114 132", "M36 110 L12 132 M164 110 L190 68"
    legs = "M72 172 L66 220 M128 172 L134 220"
    face = (f'<path d="{brows}" {line}/>' if brows else "") + eyes + f'<path d="{mouth}" {line}/>'
    art = (
        f'<path d="M80 26 C80 15 89 6 100 6 C111 6 120 15 120 26 C120 37 111 46 100 46 C89 46 80 37 80 26 Z" '
        f'fill="{ink}"/>'
        f'<path d="M88 42 H112 V68 H88 Z" fill="{ink}"/>'
        f'<path d="{arms} {legs}" {line}/>'
        f'<path d="M46 66 H154 C160 66 164 70 164 76 V144 C164 150 160 154 154 154 H46 C40 154 36 150 36 144 V76 '
        f'C36 70 40 66 46 66 Z" fill="{body}" stroke="{ink}" stroke-width="7"/>'
        f'<path d="M30 152 H170 V172 H30 Z" fill="{ink}"/>'
        f'<path d="M44 224 C44 220 50 217 58 217 C66 217 72 220 72 224 C72 228 66 231 58 231 C50 231 44 228 44 224 Z '
        f"M128 224 C128 220 134 217 142 217 C150 217 156 220 156 224 "
        f'C156 228 150 231 142 231 C134 231 128 228 128 224 Z" '
        f'fill="{ink}"/>' + face
    )
    group = f'<g transform="translate({x:.3f} {y:.3f}) scale({k:.5f})">{art}</g>'
    if angle:
        group = f'<g transform="rotate({angle} {x + w / 2:.3f} {y + 120 * k:.3f})">{group}</g>'
    return group


def qr_block(url, x, y, size):
    qr, module = qr_path(url, x, y, size)
    return f'<path d="{qr}" fill="{INK}"/>', module


def week_card_motif(lang, code, figure=True):
    """A stamp card for the week: seven fields, most of them stamped, the figure about to stamp the next."""
    s, qs = MAIN, 19.5
    title, days, line, lead = WEEK_CARD[lang]
    url = f"{SITE}week?s={code}"
    x = s.safe + 1
    qx, qy = s.w - s.safe - 1 - qs, s.h - s.safe - qs
    qr, _ = qr_block(url, qx, qy, qs)
    box, gap, top = 8.6, 1.5, 23.0
    art = ground(s, PAPER) + f'<path d="{text_path(title, 6.5, x, 11.0, 800, room=58)}" fill="{INK}"/>'
    badge = stamp_source("mark-ej-badge-stamp.svg")
    for i, day in enumerate(days):
        bx = x + i * (box + gap)
        art += f'<path d="{text_path(day, 2.6, bx + box / 2, top - 1.4, 600, "middle")}" fill="{INK}"/>'
        art += (
            f'<path d="M{bx + 1} {top} H{bx + box - 1} C{bx + box - 0.45} {top} {bx + box} {top + 0.45} {bx + box} '
            f"{top + 1} V{top + box - 1} C{bx + box} {top + box - 0.45} {bx + box - 0.45} {top + box} {bx + box - 1} "
            f"{top + box} H{bx + 1} C{bx + 0.45} {top + box} {bx} {top + box - 0.45} {bx} {top + box - 1} V{top + 1} "
            f'C{bx} {top + 0.45} {bx + 0.45} {top} {bx + 1} {top} Z" fill="none" stroke="{INK}" stroke-width="0.4"/>'
        )
        if WEEK_CARD_STAMPED[i]:
            tilt = (-14, 9, -4, 16, -10, 6, 0)[i]
            inner = nest(badge, bx + 0.9, top + 0.9, box - 1.8, VIOLET_LIGHT, f"wk{lang}{i}")
            art += f'<g transform="rotate({tilt} {bx + box / 2:.3f} {top + box / 2:.3f})">{inner}</g>'
    if figure:
        art += stamp_figure(s.w - s.safe - 13.5, 3.5, 12.5, "stand", VIOLET_LIGHT, INK, -8)
    art += (
        f'<path d="{text_path(line, 3.8, x, 40.0, 700, room=qx - 2 - x)}" fill="{INK}"/>'
        f'<path d="{text_path(lead, 3.0, x, 45.0, 420, room=qx - 2 - x)}" fill="{INK_TINT}"/>'
        f'<path d="{text_path("event-junkie.de/week", 3.4, x, 50.6, 600, room=qx - 2 - x)}" fill="{INK}"/>' + qr
    )
    return document(s, art), trimmed(s, art), url


def leaflet_motif(lang, code, figure=True):
    """The medicine-leaflet parody: a violet band, the dose and the side effects, the figure as the brand mark."""
    s, qs = MAIN, 19.5
    claim, rows, small = LEAFLET[lang]
    url = f"{SITE}?s={code}"
    x = 8.0
    qx, qy = s.w - s.safe - 1 - qs, 23.5
    qr, _ = qr_block(url, qx, qy, qs)
    room = qx - 2.5 - x
    art = (
        ground(s, PAPER)
        + f'<path d="M{-s.bleed} {-s.bleed} H5 V{s.h + s.bleed} H{-s.bleed} Z" fill="{VIOLET_LIGHT}"/>'
        + f'<path d="{text_path("Event Junkie", 7.5, x, 11.0, 800)}" fill="{INK}"/>'
        + f'<path d="{text_path(claim, 3.2, x, 17.0, 500, room=s.w - s.safe - 13 - x)}" fill="{INK}"/>'
        + f'<path d="M{x} 19.6 H{s.w - s.safe} V19.85 H{x} Z" fill="{INK}"/>'
    )
    if figure:
        art += stamp_figure(s.w - s.safe - 11.5, 3.2, 10.5, "stand", VIOLET_LIGHT, INK, 10)
    for i, (label, rest) in enumerate(rows):
        base = 25.0 + i * 4.4 + (1.2 if i >= 2 else 0)
        width = 0.0
        if label:
            d, width, _ = outline(str(GEIST), label, 2.9, x, base, 0.0, "start", 700, 3)
            art += f'<path d="{d}" fill="{INK}"/>'
        art += f'<path d="{text_path(rest, 2.9, x + width, base, 420, room=room - width)}" fill="{INK}"/>'
    for i, text in enumerate(small):
        art += f'<path d="{text_path(text, 2.35, x, 46.8 + i * 3.3, 420, room=room)}" fill="{INK_TINT}"/>'
    art += qr + (
        f'<path d="{text_path("event-junkie.de", 2.6, qx + qs / 2, qy + qs + 4.2, 600, "middle")}" fill="{INK}"/>'
    )
    return document(s, art), trimmed(s, art), url


def night_motif(slug, lang, code):
    """One hour of the night: the figure in that hour's pose, the hour, one line, a QR code."""
    s, qs = MINI, 17.0
    pose, hour, lines = NIGHT_SERIES[slug]
    url = f"{SITE}?s={code}"
    x = s.safe
    qx, qy = s.w - s.safe - qs, s.h - s.safe - qs
    qr, _ = qr_block(url, qx, qy, qs)
    art = ground(s, PAPER) + stamp_figure(x - 0.5, x - 0.5, 16.5, pose, VIOLET_LIGHT, INK)
    tx = x + 17.5
    art += f'<path d="{text_path(hour, 6.2, tx, 11.5, 800, room=s.w - s.safe - tx)}" fill="{INK}"/>'
    for i, text in enumerate(lines[lang]):
        art += f'<path d="{text_path(text, 3.2, tx, 16.8 + i * 4.0, 600, room=s.w - s.safe - tx)}" fill="{INK}"/>'
    if pose == "sleep":
        art += f'<path d="{text_path("z z", 3.0, x + 13.5, x + 2.6, 700)}" fill="{INK_TINT}"/>'
    art += (
        qr
        + f'<path d="{text_path("event-junkie.de", 2.5, x, s.h - s.safe - 0.4, 600, room=qx - 1 - x)}" fill="{INK}"/>'
    )
    return document(s, art), trimmed(s, art), url


def balaclava(x, y, h, angle=-13.0):
    """A two-tone stencil portrait of a raver in a balaclava, side-eyeing. Drawn, not traced. 100 x 140 units."""
    k = h / 140
    art = (
        f'<path d="M50 4 C74 4 89 22 89 46 C89 62 85 76 80 87 C76 96 70 102 67 106 C68 112 72 119 75 126 '
        f'L29 126 C31 117 32 108 32 100 C21 89 13 70 13 50 C13 24 29 4 50 4 Z" fill="{INK}"/>'
        f'<path d="M-6 146 L-6 132 C4 124 17 120 29 118 C40 124 62 124 75 117 C88 120 100 126 106 134 L106 146 Z" '
        f'fill="{PAPER}" stroke="{INK}" stroke-width="3" stroke-linejoin="round"/>'
        f'<path d="M31 122 C41 128 62 128 73 121" fill="none" stroke="{INK}" stroke-width="2" '
        f'stroke-linecap="round"/>'
        f'<path d="M64 11 C77 15 85 27 85 39 C80 30 73 19 64 11 Z" fill="{VIOLET_LIGHT}"/>'
        f'<path d="M84 58 C84 69 80 79 75 88 C77 78 80 68 84 58 Z" fill="{VIOLET_LIGHT}"/>'
        f'<path d="M34 49 C34 40 45 37 54 42 C58 44 61 44 64 42 C72 37 82 40 82 47 C82 55 73 58 65 55 '
        f'C61 53 58 53 55 55 C47 59 34 57 34 49 Z" fill="{VIOLET_LIGHT}"/>'
        f'<path d="M38 49 C41 45 50 44 54 48 C50 52 42 53 38 49 Z M60 47 C64 43 73 42 78 46 '
        f'C74 50 65 51 60 47 Z" fill="{PAPER}"/>'
        f'<circle cx="42" cy="48.4" r="3.2" fill="{INK}"/><circle cx="64" cy="46.6" r="3.2" fill="{INK}"/>'
        f'<path d="M37 47 C42 42 50 42 55 46 M59 45 C65 40 73 40 79 44" fill="none" stroke="{INK}" '
        f'stroke-width="2.4" stroke-linecap="round"/>'
        f'<path d="M53 74 C53 68 71 68 71 74 C71 80 53 80 53 74 Z" fill="{VIOLET_LIGHT}"/>'
        f'<path d="M57 75 C59 72 65 72 67 75 C65 77 59 77 57 75 Z" fill="{INK}"/>'
    )
    turn = f"rotate({angle} {x + 50 * k:.3f} {y + 70 * k:.3f})"
    return f'<g transform="{turn} translate({x:.3f} {y:.3f}) scale({k:.5f})">{art}</g>'


def hot_take_motif(code):
    """The 72-hour hot take from a Berlin club meme, under a stencil portrait in the Che-poster manner. English only."""
    s, qs = MAIN, 14.0
    url = f"{SITE}?s={code}"
    x = s.safe + 1
    room = 45 - x
    qx, qy = x + 1.2, s.h - s.safe - qs
    qr, _ = qr_block(url, qx, qy, qs)
    art = ground(s, VIOLET_LIGHT) + balaclava(45.0, 3.5, 55.0)
    lines = [("It’s actually good", PAPER), ("for you to party", PAPER), ("for 72 hours.*", INK)]
    for i, (text, colour) in enumerate(lines):
        art += f'<path d="{text_path(text, 4.6, x, 9.5 + i * 5.8, 800, room=room)}" fill="{colour}"/>'
    # The guest's answers from the meme, as the footnote.
    footnote = ["*I didn’t say it’s fun.", "Someone has to do it.", "To keep the club culture alive."]
    for i, line in enumerate(footnote):
        art += f'<path d="{text_path(line, 2.6, x, 26.4 + i * 3.4, 500, room=room)}" fill="{PAPER}"/>'
    panel = f'<rect x="{qx - 1.2}" y="{qy - 1.2}" width="{qs + 2.4}" height="{qs + 2.4}" rx="1" fill="{PAPER}"/>'
    tx = qx + qs + 2.6
    url_text = text_path("event-junkie.de", 2.9, tx, s.h - s.safe - 0.4, 600, room=45 - tx)
    art += panel + qr + f'<path d="{url_text}" fill="{PAPER}"/>'
    return document(s, art), trimmed(s, art), url


def pin_outline(cx, cy, r):
    """A map pin about the centre of its round head: four cubic curves, which svg_path_to_pdf can carry as a cut."""
    h, c = 1.55 * r, 0.552 * r

    def p(px, py):
        return f"{cx + px:.3f} {cy + py:.3f}"

    return (
        f"M{p(0, h)}C{p(-0.35 * r, 0.75 * h)} {p(-r, 0.55 * r)} {p(-r, 0)}"
        f"C{p(-r, -c)} {p(-c, -r)} {p(0, -r)}C{p(c, -r)} {p(r, -c)} {p(r, 0)}"
        f"C{p(r, 0.55 * r)} {p(0.35 * r, 0.75 * h)} {p(0, h)}Z"
    )


def pin_sheet(lang, code, figure=True):
    """Twelve map pins on one A3 sheet, each cut along its outline. The figure stands in the head and waves."""
    s, r, cols, rows = A3, 28.0, 4, 3
    w, h = 2 * r, 2.55 * r
    gap_x, gap_y = (s.w - cols * w) / (cols + 1), (s.h - rows * h) / (rows + 1)
    if min(gap_x, gap_y) < 10:
        sys.exit(f"error: pin layout leaves {min(gap_x, gap_y):.1f} mm between cuts, the datasheet asks for 10")
    url = f"{SITE}map?s={code}"
    here, party = PIN[lang]
    art, cuts, guides = ground(s, PAPER), [], ""
    qs = 15.5
    for i in range(cols * rows):
        col, row = i % cols, i // cols
        cx = gap_x + r + col * (w + gap_x)
        cy = gap_y + r + row * (h + gap_y)
        art += f'<path d="{pin_outline(cx, cy, r + 4)}" fill="{VIOLET_LIGHT}"/>'
        if figure:
            art += stamp_figure(cx - 5.0, cy - r + 3.0, 10.0, "wave", PAPER, INK)
        # Without the figure the head has room, so the line sits higher and larger.
        here_size, here_base = (4.0, cy - 8.6) if figure else (4.6, cy - 11.5)
        art += f'<path d="{text_path(here, here_size, cx, here_base, 800, "middle", room=2 * r - 9)}" fill="{PAPER}"/>'
        qr, module = qr_block(url, cx - qs / 2, cy - 5.0, qs)
        quiet = 4 * module
        art += (
            f'<path d="M{cx - qs / 2 - quiet:.3f} {cy - 5 - quiet:.3f} h{qs + 2 * quiet:.3f} v{qs + 2 * quiet:.3f} '
            f'h-{qs + 2 * quiet:.3f} Z" fill="{PAPER}"/>' + qr
        )
        art += f'<path d="{text_path(party, 3.4, cx, cy + 20.2, 700, "middle", room=2 * r - 12)}" fill="{PAPER}"/>'
        art += f'<path d="{text_path("event-junkie.de/map", 2.3, cx, cy + 24.6, 600, "middle")}" fill="{PAPER}"/>'
        cut = pin_outline(cx, cy, r)
        cuts.append(cut)
        guides += f'<path d="{cut}" fill="none" stroke="#e5007d" stroke-width="0.35"/>'
    return document(s, art), document(s, art + guides), cuts, url


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
    bled, preview, url = hot_take_motif("h72")
    yield "sticker-72h-en-85x55", MAIN, bled, preview, url, ()
    for lang in ("de", "en"):
        bled, preview, url = week_card_motif(lang, f"k{lang[0]}")
        yield f"sticker-weekcard-{lang}-85x55", MAIN, bled, preview, url, ()
        bled, preview, url = leaflet_motif(lang, f"b{lang[0]}")
        yield f"sticker-leaflet-{lang}-85x55", MAIN, bled, preview, url, ()
        for slug in NIGHT_SERIES:
            bled, preview, url = night_motif(slug, lang, f"n{slug[:2]}{lang[0]}")
            yield f"sticker-night-{slug}-{lang}-50x50", MINI, bled, preview, url, ()
        bled, preview, cuts, url = pin_sheet(lang, f"p{lang[0]}")
        yield f"sticker-pin-sheet-{lang}-a3", A3, bled, preview, url, cuts
        # The same three designs without the figure, so they can be ordered on their own.
        bled, preview, url = week_card_motif(lang, f"k{lang[0]}2", figure=False)
        yield f"sticker-weekcard-plain-{lang}-85x55", MAIN, bled, preview, url, ()
        bled, preview, url = leaflet_motif(lang, f"b{lang[0]}2", figure=False)
        yield f"sticker-leaflet-plain-{lang}-85x55", MAIN, bled, preview, url, ()
        bled, preview, cuts, url = pin_sheet(lang, f"p{lang[0]}2", figure=False)
        yield f"sticker-pin-sheet-plain-{lang}-a3", A3, bled, preview, url, cuts


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
            zoom = ("2" if url else "1") if sheet is A3 else "3"
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
