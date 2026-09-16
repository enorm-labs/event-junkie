---
applyTo: "**/*.py,ruff.toml"
paths:
    - "**/*.py"
    - "ruff.toml"
---

# Python

The scripts under `scripts/`, `deploy/alerts/` and `deploy/dashboards/`, plus `infra/check_user_data.py`, and nothing else. Not a Python project: no
`pyproject.toml`, no venv, no test runner. `scripts/README.md` § The short version carries the decisions; this is what they mean when you edit a `.py` file.

- **Standard library only, with `argparse`.** `ste_lint.py` and everything under `deploy/` are copied to a node and run under the `python3` there, where
  nothing is installed. `outline_text.py` is the one exception, and its wrapper builds the venv it needs. A CLI library (click, typer) is the same
  decision as a shell framework, and `scripts/README.md` records both as decided against.
- **`ruff check` and `ruff format`, from the root `ruff.toml`**: line length 120, `target-version = "py312"` (the `python3` on an Ubuntu 24.04 node), rules
  `E`, `F`, `W`, `B`, `I`. The pin is `RUFF_VERSION` in `validate-python.yml`; the commit hook uses the `ruff` on `$PATH` (`brew install ruff`), the
  way ShellCheck does. Without one, `uvx ruff@<RUFF_VERSION>` runs the pinned release. **Not selected on purpose:** `UP031`, because `%`-formatting is
  deliberate in code that runs under whatever `python3` a node has, and `PLW1510`, because every `subprocess.run` here reads its own `returncode`.
- **Tests are plain scripts, not pytest.** `deploy/alerts/test_diff_alerts.py` and `deploy/dashboards/test_lint_dashboard.py` count checks and exit
  non-zero; `validate-python.yml` runs both. Two files do not justify a runner. A third test follows the same shape, and goes into that workflow.
- **Importing a sibling from a test**: `sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))`, then the import with `# noqa: E402`. The
  files are run, not imported, so there is no package to make and no `__init__.py`.
- **`--help` before anything else.** Every script under `scripts/` answers `-h` or `--help` with exit 0 before it touches a tool, a file or the network,
  and `scripts/index-parity.sh` asserts it. `argparse` gives that for free as long as nothing runs at import time.
