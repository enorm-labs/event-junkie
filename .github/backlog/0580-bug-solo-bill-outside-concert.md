---
slug: bug-solo-bill-outside-concert
title: Only concerts get an artist, so a solo bill outside `CONCERT` loses its performer
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:M"]
priority: P1
status: Ready
---

**What happens.** `buildArtistsForEventType` mints a headliner from the title for a `CONCERT` and
stays silent otherwise.

That is **right** for a production title (`DIE KLIMA-MONOLOGE`) and **wrong** for the
`"<performer> – <show>"` idiom every variety and comedy house uses.

**Blast radius.** Admiralspalast stores an artist for 66 of 201 events and loses `Bülent Ceylan`
from *Bülent Ceylan – Diktatürk*. Heimathafen stores one for 30 of 95.

**The fix already exists in one place.** Cosmic Comedy derives the act from exactly that idiom for
its `Comedy Special` nights — so the rule can be **shared rather than reinvented per venue**.

**Needs a `--full` re-seed?** Yes.
