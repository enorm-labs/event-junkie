# Squash Commit Message Generator

Generate a single commit message for squashing all commits on the current feature branch, following the
[Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) specification.

## Important

Always run git commands with the pager disabled (`git --no-pager ...`) to prevent hanging on interactive output.

**Deviation from common convention**: Body lines are NOT limited to 72 characters. Lines can be as long as needed — use line breaks/wraps where it feels natural
for readability, not at a fixed column width.

## Instructions

1. **Identify the branch commits** — list all commits on the current branch that are not on `main`:
    ```bash
    git --no-pager log --oneline --no-merges main..HEAD
    ```
    If `main..HEAD` produces no output, find the merge base first:
    ```bash
    git --no-pager log --oneline --no-merges $(git merge-base main HEAD)..HEAD
    ```
2. **Read every commit message** — use `git --no-pager log --format='%B' main..HEAD` (or equivalent) to get the full messages including bodies, not just the
   subject lines.
3. **Analyze the combined changeset** — consider all commits together to determine the single most appropriate Conventional Commits _type_ and optional _scope_:
    - If the branch introduces a new feature (even alongside docs, CI, refactoring), use `feat`.
    - If the branch is purely a bug fix, use `fix`.
    - If no feature or fix is present, pick the most prominent type (`ci`, `docs`, `build`, `chore`, `refactor`, etc.).
    - Choose a _scope_ that reflects the primary area of change (e.g., `importer`, `bff`, `frontend`). Omit the scope if changes span the entire project
      broadly.
4. **Write the squash commit message** with this structure:
    ```
    <type>[optional scope]: <concise summary of the entire branch>

    <body: organized summary of all changes, grouped logically>
    ```
5. **Body guidelines**:
    - Group related changes under logical themes (e.g., "Feature implementation", "CI/CD", "Documentation").
    - Use bullet points (`-`) for individual items within each group.
    - Deduplicate — if multiple commits touched the same area, summarize the final state, not the incremental steps.
    - Omit intermediate fixes, typo corrections, and fixup commits — only describe the end result.
    - Include _why_ decisions were made when the commit messages contain that context.
6. **Do NOT**:
    - Simply concatenate the original commit messages.
    - Include commit hashes, dates, or author info in the output.
    - Add footers like `Refs:` or `Reviewed-by:` unless explicitly requested.

## Output

Return **only** the final commit message as a fenced code block — no preamble, no explanation outside the block.
