# UX/UI Audit — read me first

Open **`UX_UI_AUDIT_REPORT.html`** in a browser from this directory.

## The screenshots are not in this repository

This audit was produced with **188 screenshots** (~18 MB) as evidence. They are
deliberately **not committed** — `docs/ux-ui-audit/screenshots/` is gitignored.

That means: **if you cloned this repo, the report's images will not load.** Every
other part of the report — findings, evidence descriptions, route coverage, the
deal-flow walkthrough, the modal coverage matrix, the remediation status — is
complete and self-contained in the HTML and in `data/findings.json`.

`.gitattributes` already carries a Git LFS rule for
`docs/ux-ui-audit/screenshots/**/*.png`, so if the screenshots are added later
they will go to LFS rather than into regular git objects. To do that: enable LFS
on the remote, remove the ignore line from `.gitignore`, then `git add` them.

## Regenerating the evidence

The screenshots were captured by driving the mock frontend
(`VITE_USE_MOCKS=true`, port 5200) with Playwright as each of the seven seeded
personas. The capture approach — and the traps in it — are documented in the
methodology section (§3) of the report, including a correction notice: one P1
finding (UX-01) was **withdrawn as a false positive** caused by the capture
method itself. Read that before trusting any full-page screenshot in this repo
as evidence of content height.

## Contents

| File | What it is |
|---|---|
| `UX_UI_AUDIT_REPORT.html` | The main report — start here |
| `ROUTE_INVENTORY.html` | Every route, its modals, forms, states and role access |
| `AUDIT_CHECKLIST.html` | Completion checklist, including what could not be tested |
| `appendices/*.html` | Design system, accessibility, responsive results |
| `data/findings.json` | Machine-readable findings, incl. fix status and commits |
| `data/routes.json`, `data/screenshot-index.json` | Coverage data |

## Status

Remediation Phases A and B are complete (see §1b in the report). Phases C and D
remain. Handoffs: `docs/agent-handoffs/79_ux-audit-phase-1.md` and
`80_ux-audit-phase-2.md`.
