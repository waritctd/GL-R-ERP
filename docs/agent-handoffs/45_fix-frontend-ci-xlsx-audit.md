# Agent Handoff

## Task
Fix the failing Frontend CI run on `main` (run id 29403916615, "fix: resolve merge validation
issues", 2026-07-15T09:17:01Z — failure after 17s).

## Branch
fix/frontend-ci-xlsx-audit

## Base Commit
43ce2d5 (main, tip)

## Agent / Model Used
Claude Opus 4.8 (diagnosis, fix, verification)

## What happened

`.github/workflows/frontend-ci.yml` runs `npm audit --audit-level=moderate` as its second step,
right after `npm ci`. That step exited 1 on:

```
xlsx  *
Severity: high
Prototype Pollution in sheetJS - GHSA-4r6h-8v6p-xvw6
SheetJS Regular Expression Denial of Service (ReDoS) - GHSA-5pgg-2g8v-p4x9
No fix available
```

The 17s runtime was the job dying at the audit gate, before lint/test/build ever ran — which is why
`npm run lint`, `npm test` and `npm run build` all passed locally while CI stayed red.

## Root cause — a regression, not a new advisory

This advisory is not newly published, and this is the **second** time it has turned main red.
`8d4ccf4` ("security(sales): drop xlsx dep, stub mock-mode doc downloads to placeholder blobs",
2026-07-13, PR #163) already removed `xlsx` for exactly this reason when the sales post-quotation
flow was forward-ported to main.

The `yang/catalouge` merge (`a92573b`, merging `d9bedef`) brought the **pre-fix** version of
`frontend/src/api/mockApi.js` back and re-added `"xlsx": "^0.18.5"` to `frontend/package.json`,
silently reverting `8d4ccf4`. `8d4ccf4` is still an ancestor of `main` — git recorded the merge as
resolved, so nothing flagged the reintroduction. `d9bedef` is the only commit that has ever added
xlsx to package.json.

Note for future merges from `yang/*`: those branches still carry the xlsx-based `mockApi.js`. Any
future merge from them will re-break this the same way unless the drop is re-applied.

## Fix

Cherry-picked `8d4ccf4` onto the merged tree (`git cherry-pick -n 8d4ccf4`) — applied cleanly, no
conflicts. This removes `xlsx` from package.json + lockfile and replaces the three SheetJS-based
mock download producers with `mockDocPlaceholderBlob`, a `text/plain` placeholder.

This is demo-only and does not touch business logic. `xlsx` was used solely by `mockApi.js` to
render quotation / remaining-invoice / deposit-notice files client-side in mock/demo mode
(`VITE_USE_MOCKS=true`); the real flow downloads the backend Apache POI-rendered file over
`/api/.../file?format=xlsx`. Each producer keeps its signature, existence checks and Blob return
type, so callers are unaffected.

Verified the catalog branch's ~177 new lines in `mockApi.js` do not use XLSX — after the
cherry-pick, the only remaining `XLSX` matches in `frontend/src` are two section comments. The
`.xlsx` strings elsewhere (`PriceImportPage` file-input `accept`, download filenames, API route
`format=xlsx`) are backend-served paths and are unrelated to the dependency.

## Files changed
1. `frontend/package.json` — removed the `xlsx` dependency.
2. `frontend/package-lock.json` — regenerated without xlsx (104 lines removed).
3. `frontend/src/api/mockApi.js` — dropped the `xlsx` import and the SheetJS helpers
   (`mockSetCell` / `loadXlsxTemplate` / `xlsxBlob`); quotation, remaining-invoice and
   deposit-notice mock producers now return `mockDocPlaceholderBlob(...)`.
4. `docs/agent-handoffs/45_fix-frontend-ci-xlsx-audit.md` — this file.

## Commands run
- `gh run view 29403916615 --log-failed`
- `git cherry-pick -n 8d4ccf4`
- `cd frontend && rm -rf node_modules && npm ci`
- `npm audit --audit-level=moderate` / `npm run lint` / `npm test` / `npm run build`

## Tests / build results
Ran the exact CI sequence locally after a clean `npm ci` (Node 22):

| Step | Result |
| --- | --- |
| `npm ci` | pass — 0 vulnerabilities reported at install |
| `npm audit --audit-level=moderate` | **pass, exit 0** — found 0 vulnerabilities (was exit 1) |
| `npm run lint` | pass, exit 0 — 0 errors, 10 pre-existing warnings |
| `npm test` | pass — 18 files / 88 tests, 3.4s |
| `npm run build` | pass — built in 136ms |

Backend untouched, so `./mvnw verify` was not run.

## Known risks
- **Low.** Demo/mock-mode only. In `VITE_USE_MOCKS=true` builds, clicking download on a quotation /
  remaining invoice / deposit notice yields a `text/plain` placeholder summarizing the doc rather
  than a real spreadsheet. Real (non-mock) downloads are unchanged — they come from the backend.
  This is the same accepted trade-off already merged in PR #163.
- The underlying advisory has no fix on the npm registry. If a real client-side xlsx render is ever
  needed, it must use a different library — re-adding `xlsx` will turn CI red again.
- `yang/*` branches still carry the xlsx-based `mockApi.js` (see root-cause note above).

## Next prompt for the next agent
> Frontend CI on `main` is green again (xlsx re-dropped in PR from `fix/frontend-ci-xlsx-audit`;
> see `docs/agent-handoffs/45_fix-frontend-ci-xlsx-audit.md`). The underlying problem is that a
> merge from `yang/catalouge` silently reverted a merged security fix (`8d4ccf4`) and nothing
> caught it. Consider a cheap permanent guard, in the spirit of the regression test added in
> `38_fix-v32-migration-collision.md` — e.g. a lint/CI check or test asserting `xlsx` is absent
> from `frontend/package.json`, so a future merge from `yang/*` fails on the PR rather than on
> `main` after merge. Keep it small; do not add ERP features.
