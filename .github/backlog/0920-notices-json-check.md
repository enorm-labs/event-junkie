---
slug: notices-json-check
title: Nothing checks that notices.json is current
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:ci", "area:legal", "size:M"]
priority: P1
status: Ready
---

**How the gap was found:** `notices.json` was missing `vue-i18n` — a direct production dependency,
shipped in the bundle, absent from the attribution page for as long as localisation has been in.

Regenerating it is a manual step in the `/update-dependencies` skill, and manual steps get skipped.

**Now unblocked.** The file used to be platform-dependent, so a check would have failed for the
wrong reason. `PLATFORM_SPECIFIC` in `events-frontend/scripts/generate-notices.mjs` fixed that, and
a check can now regenerate and fail on a non-empty diff.

**The one wrinkle left.** The file merges *both* ecosystems, so the check needs Gradle
(`generateLicenseReport`) as well as npm — which means it belongs in the backend workflow, or in one
of its own. That is the only real decision here.
