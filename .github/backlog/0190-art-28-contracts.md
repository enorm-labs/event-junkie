---
slug: art-28-contracts
title: Conclude the Art. 28 processor contracts
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:legal", "size:S", "blocked"]
priority: P0
status: Blocked
blocked-by: [accept-adr-012]
---

Hetzner's AVV (offered in their console) and Cloudflare's DPA, plus naming the transfer mechanism
actually in force in the privacy notice — replacing the placeholder sentence there now.

> *A notice naming processors without a DPA in place is worse than one naming none.*
> — [LEGAL.md §14](../../docs/LEGAL.md)

Which contracts are needed depends on which platform ADR-012 lands on, which is why this is blocked
rather than merely unstarted.

**Done when**

- [ ] AVV concluded with the hosting provider actually chosen
- [ ] Cloudflare DPA concluded, if Cloudflare is in the path
- [ ] The privacy notice names the real transfer mechanism, not the placeholder
