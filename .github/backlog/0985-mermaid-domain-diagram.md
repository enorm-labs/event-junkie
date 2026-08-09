---
slug: mermaid-domain-diagram
title: Generate a Mermaid domain class diagram via Gradle
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["documentation", "size:M"]
priority: P2
status: Ready
---

[DATA_MODEL.md](../../docs/DATA_MODEL.md) describes the schema in prose. A generated diagram would
show the shape at a glance — and, being generated, would not drift.

**Generated is the whole point.** A hand-drawn diagram of a schema that changes is a diagram that is
wrong within a month, and a wrong diagram is worse than none because it is believed.

Worth checking whether the source should be the JPA/R2DBC entities or the Flyway migrations — they
can disagree, and which one is authoritative is itself worth recording.
