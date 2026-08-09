# New Issue

Turn a description into a well-formed issue on the tracker: check it isn't already filed, pick the right form, draft it in house style, and set type, labels,
milestone and board fields.

## Important

- **Check for a duplicate first, and say what you found.** Roughly a third of what gets "discovered" during a smoke test or an audit is already filed — several
  of the importer defects are cross-cutting and were filed once for every venue they touch. Filing a second copy is worse than not filing: it splits the
  discussion and the eventual fix.
- The `gh` CLI is installed and authenticated.
- **Don't invent a milestone or a label.** Both vocabularies are closed (below). If nothing fits, say so and ask — a new label is a decision, not a detail.
- Creating an issue on a **public** repository is outward-facing. Draft it, show the user, and create it once they're happy — unless they've already said to
  just file it.

## Steps

1. **Search for an existing issue.** Two cheap passes, both worth doing:

   ```sh
   grep -in '<keyword>' docs/BACKLOG.md          # the local snapshot of every open issue
   gh issue list --search '<keyword>' --state all --limit 20
   ```

   `docs/BACKLOG.md` is a generated mirror — free to grep, no network. The `gh` search also finds *closed* issues, which matters: something closed as `wontfix`
   or already fixed is an answer, not a gap. If you find a match, report it and stop unless the user wants a separate issue anyway.

2. **Pick the form.** The template decides which questions the issue has to answer, so choose before drafting:

   | Form | For |
   |---|---|
   | `4-task.yml` 🛠 | The default. Infrastructure, docs, tooling, parser repairs — anything that isn't a user-facing feature or a decision. |
   | `5-feature.yml` ✨ | Something a visitor or operator will be able to *do*. Story format, and **only where there is a user** — "As a user I want Terraform" is a Task. |
   | `6-importer-defect.yml` 🔍 | We lose or mangle data the source *did* publish. Asks for the scraper, the source text, what we store, the code path, and **whether the fix needs a `--full` re-seed**. |
   | `7-decision.yml` ⚖️ | A choice that has to be made before work can start. Gets `needs-decision`. |
   | `8-epic.yml` 🧭 | A theme large enough to hold sub-issues. |

   **A finding that is *not* an issue:** an accepted limitation — a field the venue never publishes, a trade-off a parser makes deliberately — belongs in that
   scraper's KDoc, next to the code it constrains. See AGENTS.md.

3. **Draft the body.** Match the house style of the existing issues (`gh issue view 302` is a good example):
    - **Keep the specifics.** File and function names, real example strings from the source, the blast radius as a number. *"7 artist rows today, 5 of them VOID
      Club's"* is worth more than "several".
    - **Say what it costs and what it is blocked on**, not just what it is.
    - **Record what was decided against, and why.** A choice that isn't written down gets rediscovered as an oversight and "fixed".
    - Add a **Done when** checklist where the finish line isn't obvious.

4. **Choose type, labels and milestone.** All three vocabularies are closed:
    - **Type** (a GitHub issue type, *not* a label): `Task` · `Bug` · `Feature`. Never add a `type:` label.
    - **Area** (pick one or more): `area:data-quality` `area:bff` `area:frontend` `area:infra` `area:ci` `area:legal` `area:seo` `area:security` `area:agents` —
      plus `importer` and `documentation`, which double as area labels because release-note grouping already depends on them.
    - **Size** (exactly one): `size:S` under half a day · `size:M` one to two days · `size:L` about a week · `size:XL` too big, split it.
    - **State**, only if it applies: `blocked` (another issue) · `needs-decision` (a choice) · `needs-deployment` (a live origin).
    - **Milestone**: `v0.2 — Deployable` → `v0.3 — Launch-ready` → `v1.0 — Go-live` is the path to launch; `Phase 2 — Coverage & polish` is post-launch;
      `Phase 3` / `Phase 4` hold epics. **No milestone is a valid answer** — it means unscheduled.

5. **Create it.**

   ```sh
   gh issue create --title '…' --body-file - --type Task \
     --milestone 'Phase 2 — Coverage & polish' \
     --label importer --label 'area:data-quality' --label 'size:M' <<'EOF'
   …body…
   EOF
   ```

6. **Set the board fields.** Status and Priority are project fields, not labels:

   ```sh
   scripts/issue-board.sh status <n> Ready      # Backlog | Ready | In progress | In review | Blocked | Done
   scripts/issue-board.sh priority <n> P1       # P0 now · P1 next · P2 later
   ```

   `Ready` means understood and unblocked. Anything with a `blocked` / `needs-decision` / `needs-deployment` label should be `Blocked`, not `Backlog` — that is
   what the board's Blocked view filters on.

7. **Link it.** If it relates to, blocks or is blocked by an existing issue, say so in the body (`Related: #NNN`, `Blocked by: #NNN`). For a child of an epic,
   use a real sub-issue rather than a checklist item — sub-issues show progress and can be worked independently.

8. **Report** the issue number, its URL, and what you set.

## Notes

- **`docs/BACKLOG.md` is generated.** Never edit it to add the new issue; the snapshot workflow rewrites it on issue open.
- **Several issues at once** (an audit or smoke test that found five things): file them individually, but check for duplicates in one pass first, and mention
  any that turned out to be the same underlying defect in different venues — that is usually one issue, not five.
- **If it needs an ADR**, use the decision form and say so in it. The ADR is where the answer lands; the issue is where the thinking happens.
