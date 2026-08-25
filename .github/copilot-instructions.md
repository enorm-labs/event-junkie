# Copilot Instructions

This repository keeps its conventions in `AGENTS.md` files. Always read and follow these before generating code:

- **Backend** (Kotlin/Spring Boot): [`/AGENTS.md`](../AGENTS.md)
- **Frontend** (Vue 3/TypeScript): [`/events-frontend/AGENTS.md`](../events-frontend/AGENTS.md)

Conventions that apply to one kind of file rather than to everything live in [`.github/instructions/`](instructions), one file per topic, each declaring its
own `applyTo` globs. Copilot loads them for matching files by itself; the same files are what Claude Code reads through `.claude/rules/`, so there is one copy
of each rule and not two.
