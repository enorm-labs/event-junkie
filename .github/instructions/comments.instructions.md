---
applyTo: "**/*.kt,**/*.kts,**/*.ts,**/*.vue,**/*.js,**/*.tf,**/*.sh,**/*.py,**/*.yaml,**/*.yml"
paths:
    - "**/*.kt"
    - "**/*.kts"
    - "**/*.ts"
    - "**/*.vue"
    - "**/*.js"
    - "**/*.tf"
    - "**/*.sh"
    - "**/*.py"
    - "**/*.yaml"
    - "**/*.yml"
---

# Comments, in Every Language

Lint enforces the mechanical half of this; the rest is review.

- **Few, short, about _why_, present tense — in every language.** A comment is prose maintained like the code it sits on. Default to one or two sentences.
  **Before writing one, try to make it unnecessary**: a clearer name or an extracted function cannot go stale (#713).
    - **Explain _why_, not _what_**: a trade-off, an outside constraint, a failure this shape avoids. Self-explanatory code needs no comment.
    - **Rewrite, never append.** "It used to…", "since #540 it now…", "note: this also…" narrate a journey instead of a state.
    - **No history, no dates, no changelog** — git blame and the issue hold them. One exception: an abandoned approach that is a live trap, in one sentence.
    - **A KDoc example is not a test, and writing one suppresses the instinct to write the test** (#726). If a case is worth showing it is worth asserting.
    - **A fact another tool has to act on belongs in a record, not prose.** What a venue does not publish is a `VenueLimitations` declaration per importer,
      rendered to `docs/data-quality/ACCEPTED_LIMITATIONS.md` and asserted (#715); the KDoc keeps the reasoning — which selector, which trap, what the parser
      does instead.
    - **An issue or ADR reference is a pointer, not a summary**: `see #540`, and stop. Keep in the code only what constrains that code.
    - **No document structure inside a comment** — a markdown heading, bold section titles or multi-paragraph argument is a document in the wrong file.
    - **Don't restate the signature**; document a parameter only for what the type cannot say (units, a range, a format). **Lead with one summary sentence**;
      KDoc renders it alone.
    - **Assert behaviour in a test, don't promise it in a comment.** "callers must call `close()`" goes stale silently; a test fails loudly.
    - **No commented-out code and no `TODO`s.** Deleted code lives in git; work worth remembering is an issue.
    - **How to write the sentences** — six checkable [ASD-STE100](https://www.asd-ste100.org/) rules: one topic per sentence, **20 words maximum** · active
      voice, present tense · **one word, one meaning** · **three sentences maximum** per comment · direct statements · no rhetorical build-up. The
      `asd-ste100` skill applies them; it is vendored into `.claude/skills/asd-ste100/` (MIT) because `/compact-comments` invokes it by name, and kept
      byte-identical to upstream (`VENDORED.md` beside it; `.oxfmtrc.json` keeps the formatter off it).
- **[`/compact-comments`](../../.github/prompts/compact-comments.prompt.md) is how the backlog comes down**: DELETE → RENAME → EXTRACT → RELOCATE → KEEP, in
  that order, so deletion is the default. `scripts/comment-density.sh report --top 20` says where the lines are and gates nothing — a budget on the count of
  comment lines charged the same for deleting a stale paragraph as for adding a load-bearing one, so it was removed.
- **What lint enforces, per language.** Each rule names a defect in the comment itself, and none is a threshold to raise until nothing fires:
    - **Kotlin**: `LongComment` (a custom rule in `:detekt-rules`) caps a comment at 25 lines, counting a run of `//` as one; `CommentDensity` caps a file at
      70% comment once it carries 25 comment lines; `CommentSmell` reports a date, a markdown heading, a comment narrating its own history, or a `TODO`. **A
      date inside backticks, quotes or a fenced block is left alone** (parsers are documented with `"2026-05-16T20:00"` far more often than decisions are
      dated), test sources are excluded, and "used to" after a form of _be_ is the passive verb, not narration. Over the cap is `@Suppress(…)` **with a
      reason**. **Adding or editing a rule in `:detekt-rules` needs `./gradlew --stop`** — the daemon caches the plugin classloader, so a new rule is silently
      absent and an edited one keeps its old verdict; no `--rerun-tasks` clears it.
    - **TS and Vue**: `event-junkie/max-comment-lines` (15, measured from this tree), `comment-density` and `comment-smell` in `events-frontend/eslint-rules/`,
      the same semantics; `// eslint-disable-next-line` with a reason.
    - **Terraform, shell, YAML, Python**: `scripts/comment-lint.sh check` fails on **any** violation, no baseline. Density is **55% for the declarative
      formats, 70% for code**, floor 21 lines rather than 25 (#721) — in HCL and YAML one comment explains one assignment, and 70 was measured to flag two
      files where 55 flags seventeen. `# comment-lint: allow <reason>` on the line above a block, `# comment-lint: allow-file <reason>` for density; a bare
      directive is itself a violation.
    - **A block's length is its lines that carry something**: blank `*` and `#` separators do not count (#741, #750), so paragraphing is never penalised.
      Density counts a blank comment line in both halves of the ratio, deliberately, so paragraphing does not move it.
- **Length is welcome in one place — venue scraper KDoc**, which carries the shape of a hand-authored source: the markup sample, the trap, the counterexample.
  `@Suppress("LongComment")` with a reason is right there. Shorten how it is said; never delete what it says. What the venue does not publish is a
  `VenueLimitations` record, not prose. See the _Where a finding goes_ table in [AGENTS.md](../../AGENTS.md) § The Backlog.
