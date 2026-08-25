# Vendored skill

Upstream: <https://github.com/danyuchn/asd-ste100-skill>, MIT, © Dustin Yuchen Teng — `LICENSE` is kept
verbatim beside this file.

Vendored at commit `d5ce157870cf9c41efd1d6e836706a2be3c7b9da`.

**Why it is in the repository rather than left to each contributor's global install.**
[`/compact-comments`](../../../.github/prompts/compact-comments.prompt.md) invokes this skill by name for the one bucket that gets rewritten.
A skill only present on one machine makes that instruction silently do nothing for everybody else — the same failure as a convention nothing enforces.

**Updating** — re-copy from a fresh clone and record the new commit here; nothing in this directory is
locally modified except this file:

```bash
git clone --depth 1 https://github.com/danyuchn/asd-ste100-skill /tmp/ste100
rsync -a --exclude '.git' --exclude VENDORED.md /tmp/ste100/ .claude/skills/asd-ste100/
```
