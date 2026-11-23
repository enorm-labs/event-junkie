# Vendored skill

Upstream: <https://github.com/danyuchn/asd-ste100-skill>, MIT, © Dustin Yuchen Teng — `LICENSE` is kept
verbatim beside this file.

Vendored at commit `65c8ab6ef5fcb3619c8e6daca43e68627f3d7b4a` (2026-11-20).

`scripts/ste-lint.py` is upstream's linter, which `SKILL.md` tells the agent to run. It is not
`scripts/ste_lint.py` at the repository root, which is this repository's own docs gate. `ruff.toml`
excludes this directory. Upstream's `.claude-plugin/` is left out: it is marketplace metadata, and this
copy is loaded as a project skill, not installed as a plugin.

**Why it is in the repository rather than left to each contributor's global install.**
[`/compact-comments`](../../../.github/prompts/compact-comments.prompt.md) invokes this skill by name for the one bucket that gets rewritten.
A skill only present on one machine makes that instruction silently do nothing for everybody else — the same failure as a convention nothing enforces.

**Updating** — re-copy from a fresh clone and record the new commit here; nothing in this directory is
locally modified except this file:

```bash
git clone --depth 1 https://github.com/danyuchn/asd-ste100-skill /tmp/ste100
rsync -a --exclude '.git' --exclude '.claude-plugin' --exclude VENDORED.md /tmp/ste100/ .claude/skills/asd-ste100/
```
