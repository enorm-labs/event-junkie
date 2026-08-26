# Write ADR

Turn a decision that has been **made** into the record of why. Eighteen ADRs exist and their shape is regular enough that the format should not be re-derived
each time — but the format is the cheap half. What this prompt is really for is the two questions an ADR is easy to get wrong on: whether one is warranted at
all, and what the number is.

## Important

- **An ADR records a decision, it does not make one.** If the choice is still open, the artefact is a `needs-decision` issue, not a document in `docs/adr/`.
  Writing the ADR first turns an argument into an accepted-looking record, which is the one failure this format cannot survive.
- **This is deliberately not an agentic workflow, and that is the point.** Every other judgement-heavy prompt here has one; this one must not. An agent can
  draft the Context and the Comparison from evidence it gathered, but the Decision line is a human's, and a scheduled job that writes Decision lines is a
  scheduled job that invents them.
- **The number is claimed by writing the file, never by planning one.** A document saying _"needs ADR-0NN"_ is a reservation the numbering scheme does not
  honour: the next ADR actually written takes that number and the reference silently starts pointing at an unrelated decision. **This has already happened twice
  to the same planned ADR** (AGENTS.md § Agent Instructions). Refer to a future ADR by title only, and take the next free number at the moment you create it.
- **Never rewrite an existing ADR to match the present.** A decision that was later changed is recorded by a **Status** line on the old one and a new ADR that
  supersedes it. Rewriting the body destroys the only surviving account of why the original choice was reasonable, which is the thing a reader came for.
- This writes under `docs/`, so [documentation.instructions.md](../instructions/documentation.instructions.md) applies in full: ≤25-word sentences, active
  voice, simple tenses, **no semicolons**, and every hedge at its original strength.

## Usage

```
/write-adr <title or issue number>     # e.g. /write-adr 473, or /write-adr "AI-assisted data quality"
```

## Step 1 — Establish that there is a decision, and that it needs an ADR

Most choices are not ADRs. The bar is **whether a future reader would otherwise re-litigate it**, and the honest test is the third question below.

- **Is it decided?** If the answer is "we are leaning towards", stop. Say so, and offer to sharpen the issue instead.
- **Does it constrain something outside the file it lives in?** A local implementation choice is a comment. A decision the chart, the CI gates or another module
  has to respect is an ADR.
- **Would reversing it be expensive, or is the reasoning non-obvious enough that someone will propose reversing it?** ADR-017 exists because the reasoning
  generalises past the image it chose. ADR-018 exists because the wrong probe semantics look correct.

The decision usually already exists as an issue — `gh issue list --label needs-decision` — and that issue's body is the raw material for Context. Read it, and
read every issue and ADR it names.

## Step 2 — Take the number and the file name

```sh
ls docs/adr/            # the next free number is one past the highest, and there are no gaps
```

`docs/adr/ADR-0NN_SCREAMING_SNAKE_TITLE.md`. Flat and numbered — the numbering is the structure, and there is no index file to update inside `docs/adr/`.

## Step 3 — Write it

The canonical sections, in this order. Four are always present and the rest earn their place:

| Section                | Always?                                       | What it is                                                                                         |
| ---------------------- | --------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `## Status`            | **Yes**                                       | The decision itself, in bold, with the date. See below — this is the part most often written badly |
| `## Context`           | **Yes**                                       | What forced the decision, and the constraints any candidate had to satisfy                         |
| `## Candidate options` | When more than one was seriously considered   | Each with what it would have cost                                                                  |
| `## Comparison`        | When the options differ on more than two axes | A table. `ADR-012` also carries a separate pricing table                                           |
| `## Decision`          | **Yes**                                       | What was chosen and the reason that actually settled it, not every reason                          |
| `## Consequences`      | **Yes**                                       | What this now obliges, including the unwelcome half                                                |
| `## When to revisit`   | When the decision has a visible expiry        | `ADR-017` names the Java upgrade                                                                   |
| `## References`        | **Yes**                                       | Issues, PRs, upstream documents                                                                    |

### The Status line carries the whole decision

`scripts/ste-lint.sh` checks an ADR differently from every other document for exactly this reason: **its summary is its Status line.** A bare _"Accepted"_ tells
a reader nothing, and repeating the decision under a second heading is duplication that drifts.

```markdown
**Accepted (2026-08-17) — `bellsoft/liberica-openjre-alpine:25` as the runtime base for both backend images. Temurin remains the build JDK.**
```

Then, still under Status:

- **Whether it is implemented**, and where. `**Implemented in #492 on 2026-08-17**` — an accepted decision nobody has built is a different state from a live one.
- **What it supersedes**, always, even when the answer is nothing. `Does not supersede anything.` followed by the neighbouring ADRs and what each of them did
  _not_ decide is more useful than silence, because it is the question the next reader has.

### Context is where the evidence goes

The strongest ADRs here lead with **what forced the decision** rather than with background. ADR-017 opens with a waiver growing from two entries to eight in
three days, and a table with dates and triggers. That table is why the decision reads as inevitable rather than as taste.

Then **the constraints any candidate had to satisfy** — the ones inherited from decisions already made, named with the file that fixes them. A constraint
without its source is an assertion.

### Consequences must include what you would rather not write

A Consequences section listing only benefits is a sales document. Name the new obligation, the thing that is now harder, and the maintenance this creates.

## Step 4 — Register it

- **A row in the reference table in `AGENTS.md`.** Every ADR has one, and this step is the one that gets skipped.
- **Close or update the `needs-decision` issue**, and say the ADR is where the answer now lives.
- **Any document that referred to the decision by title** now refers to it by number. Grep for the title before you finish.

## Step 5 — Verify

```sh
scripts/format-markdown.sh check
scripts/ste-lint.sh check          # docs/adr is its own baseline area, and it is at zero
```

Then [`/open-pr`](open-pr.prompt.md). An ADR lands on its own, not inside the change that implements it — the document is reviewed for whether the reasoning
holds, and a diff full of code buries that.

## Output

- **The file, the number, and why that number.**
- **Which optional sections you included and why**, since that is the judgement the format leaves open.
- **What you could not source.** A Context claim with no issue, commit or measurement behind it is the thing to flag rather than to phrase confidently.
