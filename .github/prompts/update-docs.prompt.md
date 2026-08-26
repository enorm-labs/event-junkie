# Update Docs

Find documentation that has stopped being true and fix it — by correcting it, deleting it, or leaving it alone with a reason. The counterpart to
[`/update-dependencies`](update-dependencies.prompt.md) for prose: that one starts from "what is out of date" in `gradle.properties`, this one starts from the
same question asked of `docs/`.

## Important

- **This command mutates.** Invoking it **is** the permission to edit documents and open a pull request. Do not invoke it on your own initiative.
- **Staleness is a claim that can be checked, not a feeling about tone.** Every edit here answers "this sentence says X, and X is no longer true" with the
  command that proves it. A paragraph you would have written differently is not stale.
- **This is the workload [#387](https://github.com/enorm-labs/event-junkie/issues/387) warns about, in its own words:** an agent that "tidies" prose "can quietly
  delete the reasoning while leaving the sentence, which is the single worst failure mode available here." A wrong answer is a plausible-looking paragraph that
  nobody notices for months, which is why detection comes first and rewriting comes last.
- **Modality is content.** [documentation.instructions.md](../instructions/documentation.instructions.md) is binding: _may have failed_ never becomes _failed_,
  _is likely to_ never becomes _will_. The same holds for a scope qualifier, a safety condition or a number. A shorter sentence that upgrades a hedge is a
  different claim, not a simplification.
- **Shorter is not the goal.** Stop when a sentence is unambiguous, not when it is shortest. There is no word-count target here on purpose.

## Usage

```
/update-docs                # every document — the default
/update-docs <path>         # one file or directory
/update-docs --report       # detect and report, change nothing
```

## What "stale" means here, in the order worth checking

Each row is mechanical. Start at the top: the findings get less certain as you go down, and the last two are where judgement is required.

| #   | Staleness                                                              | How to prove it                                                                    |
| --- | ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| 1   | **A path that no longer exists**                                       | The document names a file, directory, function or flag. Resolve every one of them. |
| 2   | **A command that fails**                                               | Run it, where running it is safe and reaches nothing outside this repository.      |
| 3   | **A number with a source of truth elsewhere**                          | Compare against the source. This repository has several such pairs, listed below.  |
| 4   | **A closed issue described as open**, or an open one described as done | `gh issue view <n> --json state`                                                   |
| 5   | **An intermediate state that shipped**                                 | "will be", "planned", "once X lands" — where X has landed. git log is the check.   |
| 6   | **A statement a later ADR contradicts**                                | Read the ADR. The ADR wins, and see the ADR rule below.                            |

### The duplication pairs this repository keeps deliberately

Each is documented as "these must move together", so each is a place where drift is a real defect rather than a style preference. Check them explicitly:

- **`docs/CREDENTIALS.md` §2 ↔ the `CREDENTIALS` table in `credential-expiry-reminder.yml`** — the expiry dates.
- **`HELM_VERSION` and `HELM_UNITTEST_VERSION`** — `validate-chart.yml` and `release.yml`.
- **`SHELLCHECK_VERSION`** — `validate-scripts.yml` and `validate-infra.yml`.
- **The required-check list in [ci-cd.instructions.md](../instructions/ci-cd.instructions.md) ↔ the `main` ruleset** — `gh api repos/:owner/:repo/rulesets`.
- **The skills list in `CLAUDE.md` ↔ `.claude/skills/` ↔ `.claude/commands/`** — `scripts/skill-parity.sh` already checks this, so run it rather than reading.

Where a script already checks a pair, run the script. A check that exists and was not run is the reason the pair drifted.

## What must not be touched

- **`docs/adr/` is a record of decisions, not a description of the system.** An ADR describing something that is no longer true is not stale — it is a decision
  that was later changed, and the fix is a **Status** line, never a rewrite of the argument. `## Status` carries `**Accepted (date) — what was decided**`; a
  superseded one says so and names the ADR that replaced it. Rewriting the body destroys the only record of why the old choice was made.
- **`docs/BRANDING.md` and `docs/LOGO_IDEAS.md`** — voice-carrying copy, exempt by name in
  [documentation.instructions.md](../instructions/documentation.instructions.md). They argue a case and hold a tone, which is what the standard says it is not
  for.
- **`docs/data-quality/ACCEPTED_LIMITATIONS.md`** — generated from `AcceptedLimitations.kt` by its test. Its header says so. An edit here is overwritten by the
  next run, and the real change is in the Kotlin.
- **A hedge, a qualifier, a safety condition or a number**, unless the change is the finding itself.
- **A long sentence that is long because the subject is.** `scripts/ste-lint.sh` measures sentence length and its baseline moves down on its own schedule. That
  is a separate sweep, and folding it into this one is how a currency fix becomes an unreviewable rewrite.

## Steps

1. **Detect first, and write the list down before editing anything.** The six rows above, in order. This step is the whole value of the command, and it is the
   one an author skips.
2. **Classify each finding**: `CORRECT` (the fact changed, the sentence must follow) · `DELETE` (the thing it documents is gone) · `LEAVE` (still true, or true
   and merely ugly). Deletion is a real answer — a section describing a removed feature is not improved by rewording.
3. **Fix, one document per commit.** A documentation sweep that touches fourteen files in one commit cannot be reviewed and cannot be reverted in part.
4. **Simplify only what you already touched, and only if it stays as strong.** This is the last step and the optional one. Invoke the `asd-ste100` skill on a
   sentence that has to stay: ≤25 words, active voice, simple tenses, no semicolons, no phrasal verbs. Then read it against the original and confirm no hedge,
   qualifier or number moved.
5. **Verify.** `scripts/format-markdown.sh check`, `scripts/ste-lint.sh check`, and `scripts/rules-parity.sh` plus `scripts/skill-parity.sh` when the change
   touches the rules or skills lists. A `.md`-only change does not need the backend gate.
6. **Ship** with [`/open-pr`](open-pr.prompt.md).

## Running unattended

[`agent-docs.yml`](../workflows/agent-docs.yml) invokes this prompt as `/update-docs --unattended`. #387 is explicit that this workload comes last and starts
narrow: _"start with detecting staleness and reporting it, before letting anything rewrite prose."_ That is what the fence encodes.

- **Rows 1 to 4 only.** A path that does not resolve, a command that fails, a number that disagrees with its named source of truth, an issue whose state is
  wrong. Each is provable in one command and checkable by a reviewer in one glance.
- **Rows 5 and 6 are reported, never applied.** "This shipped" and "a later ADR contradicts this" both need a judgement about what the author meant. Report the
  sentence, the evidence and the proposed wording, and let a human write it.
- **Step 4 does not run at all.** No simplification, no rewrite for length, no `asd-ste100` pass. The unattended run corrects facts and nothing else. A prose
  improvement nobody watched being made is indistinguishable from a prose regression.
- **Never delete a paragraph.** `DELETE` is reported unattended, not applied — deleting the record of why something exists is the failure mode #387 names, and
  it is invisible once merged.
- **`--dry-run`** on top of it opens no pull request and writes the report to the job summary. Use it first, and after any change to this section.

**Your final message is the report, and there is no second turn.** The run ends the moment you stop calling tools, so a closing line like _"I'll compile the
report once the checks finish"_ ends it with that sentence as the whole deliverable — and the job still reports success. There is nobody to hand off to and
nothing to wait for: no reviewer reads the transcript, no follow-up prompt arrives, and any work you plan but do not do in this turn is simply lost. Finish the
work, then write the Output section below as your last message. This has already happened once, on a `--all` sweep that ended waiting for classification agents
it had no tool to spawn.

**Every count in the report carries the command that produced it.** A bucket line reading `DELETE 0` with nothing behind it is an assertion, and an assertion is
exactly what cannot be checked after the fact. Show the command and its output — a `git grep -c`, a script's summary line, a test name — so a reviewer, or the
next run, can re-run it and get the same number. This is the rule [`/codebase-audit`](codebase-audit.prompt.md) already applies: every claim backed by a
concrete file, count or command output.

**A zero needs its evidence, and so does every other number.** "Nothing to do here" is the finding nobody checks and the one that ends the run early. But the
rule is not "prove the zeros" — a run that carried a command for each of its zeros and none for its one non-zero count reported three candidates where the tree
held fifty-seven, and the count with no command behind it was the only one that was wrong. **A number you did not produce with a command is a guess, whatever
its size**, and a guess in a section headed _"reported for a human"_ is the one a human acts on.

Two runs of this prompt minutes apart once disagreed about whether a pattern still existed at all. Both reports were confident, well formatted, and one of them
was wrong. The command output is the only part of a report that cannot be plausible and false at the same time.

## Output

Lead with what stopped being true, not with a file list:

```
CORRECT   4   two dead paths, one renamed script, one closed issue called open
DELETE    1   a section describing the removed dev-seed profile
LEAVE     3   true, and long because the subject is
──────────────
5 documents changed, 0 arguments rewritten

pairs checked   5 of 5 in step, skill-parity 20/20, rules-parity 7/7
ste-lint        at or below baseline, unchanged
```

Then, in order of what a reviewer needs:

- **Every finding not acted on, with the reason.** Out of scope, needs a judgement, reported for a human. This is the half that makes the report trustworthy —
  a sweep that quietly fixed the four easy findings and said nothing about the two real ones reads exactly like a clean tree.
- **Any sentence whose wording changed**, quoted before and after, with the fact that forced it. If a change cannot be paired with a changed fact, it should not
  have shipped.
