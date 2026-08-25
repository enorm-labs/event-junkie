---
applyTo: "docs/**/*.md"
paths:
    - "docs/**/*.md"
---

# How a Document Is Written

What a document may **contain** is in [AGENTS.md](../../AGENTS.md) § Agent Instructions — present tense, replace never append, no intermediate states. How it is
**formatted** is in [markdown.instructions.md](markdown.instructions.md) — oxfmt, and nothing else touches Markdown. This file covers the third thing: the shape
of the sentences.

- **Write the prose in Simplified Technical English ([ASD-STE100](https://www.asd-ste100.org/)), in the standard's _STE-flavored_ mode.** These documents are
  read by an agent, by a contributor who is not a native English speaker, and by whoever is holding a broken cluster at the time. Each of the three pays for an
  ambiguous sentence, and none of them can ask the author what it meant. This is the rule
  [comments.instructions.md](comments.instructions.md) already applies to code, moved to the tree where the sentences are longer.
    - **Apply the structural rules. They are checkable from the sentence alone.** One idea per sentence · **25 words maximum**, and 20 for anything a reader
      follows as a procedure · active voice · simple tenses · no semicolons at all, which STE bans as a mark rather than as a clause join · no phrasal verbs
      (_start_, not _spin up_) · noun clusters of three words at most · a numbered list for three or more steps, never a sentence.
    - **Treat the lexical rules as a direction of travel, and do not claim more.** STE's word rules are defined by an approved dictionary of about 900 words.
      That dictionary is **not in this repository and cannot be** — ASD gives it away free and licenses redistribution to eight categories of organisation, none
      of which this project is in. So prefer the plainest word, use one word for one thing across a document, and prefer the verb to the noun form
      (_analyse the log_, not _perform an analysis of the log_). Never write that a document is STE-compliant.
    - **An em dash is not banned, and it is still a warning.** STE permits every punctuation mark except the semicolon. A dash usually marks the point where one
      sentence became two, so split there before reaching for it. `docs/` carries 1,671 of them today (#733) and most are joins.
    - **Modality is content. Keep every hedge at its original strength.** _may have failed_ never becomes _failed_, and _is likely to_ never becomes _will_. A
      length cap is exactly what tempts an author to cut a hedge, and a shorter sentence that upgrades one is not a simplification. It is a different claim.
      The same holds for a scope qualifier, a safety condition or a number: keep the longer phrasing and say why, rather than losing it.
    - **Shorter is not the goal.** Stop when a sentence is unambiguous, not when it is shortest. Splitting one 141-word sentence into eleven adds words, and that
      is the rewrite working. There is no word-count target here on purpose.
    - **[`asd-ste100`](../../.claude/skills/asd-ste100/SKILL.md) is the skill that does it**, and it is vendored into `.claude/skills/` (MIT, third party) so it
      is present for every contributor rather than only the ones who installed it globally. Invoke it by name. Its default output is the rewritten text alone.
      Ask for the rule table when you want to see which rule each edit answers. It is kept byte-identical to upstream: `VENDORED.md` beside it records the
      commit and the update command.
    - **Voice-carrying copy is exempt.** [BRANDING.md](../../docs/BRANDING.md) and [LOGO_IDEAS.md](../../docs/LOGO_IDEAS.md) argue a case and hold a tone, which
      is what the standard says it is not for. The glob covers them because it covers `docs/`; this sentence is the exemption. Everything else in `docs/` is in
      scope, and `docs/ops/` and `docs/adr/` are where it matters most.
    - **STE fixes the form, not the substance.** A paragraph that says nothing becomes a short, clean paragraph that says nothing. When a passage cannot be
      rewritten because there is nothing under it, delete it and say so in the pull request.
