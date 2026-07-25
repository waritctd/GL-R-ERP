# Phase 4A Playwright QA Results

## Run

- Inventory: `docs/ui-repair/04-production-repair/PHASE_4A_QA_INVENTORY.md`.
- Browser evidence: `/private/tmp/glr-phase4a-qa/`.
- Summary JSON: `/private/tmp/glr-phase4a-qa/summary.json`.
- Summary text: `/private/tmp/glr-phase4a-qa/summary.txt`.
- Started: `2026-07-25T07:16:21.835Z`.
- Finished: `2026-07-25T07:17:35.056Z`.
- App: Vite mock app at `http://127.0.0.1:5250`.
- Driver: `/private/tmp/glr-phase4a-qa/phase4a-qa.mjs`.

## Exact Result

- Runs: 25 role/viewport combinations.
- Screenshots: 143 PNG files.
- Failures: 0.
- Blockers: 0.
- Not applicable (out of role scope by design): 10.
- Warnings: 3.
- Console warnings: 0.
- Console errors: 25.
- Failed requests: 25.
- Bad responses: 25.

The 25 console errors, failed requests, and bad responses are the known mock
quick-login backend fallback noise: `POST /api/auth/login` returns `502 Bad
Gateway` / `net::ERR_ABORTED` because the Vite mock app has no backend proxy
target. No React warning, nested interactive warning, or `validateDOMNesting`
warning was recorded.

## Passed Workflows

- `sales`, `sales_manager`, and `ceo` completed `/tickets` workflows at
  `390x844`, `768x1024`, `1024x768`, `1366x768`, and `1440x900`.
- The rerun followed the 768 x 1024 tablet table repair; allowed ticket-list
  roles still passed at that viewport.
- Search for `PR-2026-0001`, phase 2 filtering, explicit open action, browser
  back, and URL/UI state preservation passed for all allowed ticket-list roles.
- Sales create-modal regression passed: open modal, close with Escape, focus
  restored to `สร้างดีลใหม่`.
- Sales Manager and CEO pipeline/tracking information remained visible where
  intended.
- Long Thai search for `ก้าวหน้า` rendered without page-level overflow or text
  overflow findings.
- Filtered-to-empty was captured for Sales at `390x844` and `1366x768`.
- Keyboard focus sequences recorded visible focus rings.
- Mobile navigation drawer Escape close and focus restoration passed.
- No page-level horizontal overflow, malformed nested interactive controls, or
  unauthorized cost/margin exposure was detected.

## Not Applicable — Intentional Route Scope

These 10 role/viewport runs were previously filed as "blockers". That was a
mis-classification and is corrected here: no check is obstructed, the route is
simply out of scope for those roles by design, so there is nothing to unblock
and no follow-up is owed.

- `import` is redirected from bare `/tickets` to `/` at every viewport.
- `account` is redirected from bare `/tickets` to `/` at every viewport.
- This is the intended route guard, not a defect: `/tickets` is the deal
  pipeline browser, gated on `canViewDealPipeline`
  (`['sales', 'sales_manager', 'ceo']` — `frontend/src/api/routes.js:291`),
  while `import`/`account` retain ticket-detail access (`canViewTickets`) and
  use role-specific overview/worklist entry points. Asserted in
  `frontend/src/app/permissions.test.js:36-37`.
- Consequently the Phase 4A worklist changes are not observable for these two
  roles, and their captures are landing-surface evidence rather than `/tickets`
  evidence.
- Related-record checks from the real role landings passed for both roles:
  Import opened a related ticket/work item, Account opened a money-related
  ticket/work item, and browser back returned to the landing state.

## Warnings

- At `390x844`, Escape did not close the ticket filter sheet for `sales`,
  `sales_manager`, or `ceo`. Closing through the visible
  `ปิดตัวกรองเพิ่มเติม` button restored focus to the filter trigger.
  **Resolved** in the acceptance pass — the root cause was the
  `moreFiltersOpen || hasActiveMoreFilters` openness derivation, which made the
  sheet undismissable by any means (Escape, scrim, or close button) whenever a
  lifecycle/flag filter was applied. Escape now closes the sheet at every width
  and restores focus, with a mobile-only focus trap and an inert background. See
  `PHASE_4A_IMPLEMENTATION.md` → "Mobile Filter Sheet Modal Contract" for the
  contract and the test coverage.

## Not Safely Reproduced

- True-empty ticket list: mock seed data is non-empty; mutating it in browser QA
  would not be a user-reachable workflow.
- Ticket-list error/retry: `api.tickets.list` is served by the in-memory mock
  module, not a network request that can be safely failed with Playwright
  routing.
- Live-page render of the DataTable expansion panel: both `renderExpanded`
  callers need seeded rows the mock does not produce — commission records require
  invoices (the list is empty for every month reachable in the mock, including
  `2026-06`), and the attendance toggle only renders for days with
  `punch_count > 0`. The shrink-to-fit regression fixed in the acceptance pass is
  therefore asserted against a synthetic table that reproduces DataTable's exact
  structure and the real stylesheet
  (`phase4a-acceptance.spec.js` → "expanded detail panel spans the full row
  width"), and mutation-checked. The measurement is of the real mechanism; the
  live page render is **not** claimed as verified.
