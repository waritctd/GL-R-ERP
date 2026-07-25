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
- Blockers: 10.
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

## Blockers

- `import` is redirected from bare `/tickets` to `/` at every viewport.
- `account` is redirected from bare `/tickets` to `/` at every viewport.
- This matches the current route guard: `/tickets` is still gated to
  `canViewDealPipeline` (`sales`, `sales_manager`, `ceo`), while
  `import`/`account` retain ticket-detail access and use role-specific
  overview/worklist entry points.
- Related-record checks from the real role landings passed for both roles:
  Import opened a related ticket/work item, Account opened a money-related
  ticket/work item, and browser back returned to the landing state.

## Warnings

- At `390x844`, Escape did not close the ticket filter sheet for `sales`,
  `sales_manager`, or `ceo`. Closing through the visible
  `ปิดตัวกรองเพิ่มเติม` button restored focus to the filter trigger.

## Not Safely Reproduced

- True-empty ticket list: mock seed data is non-empty; mutating it in browser QA
  would not be a user-reachable workflow.
- Ticket-list error/retry: `api.tickets.list` is served by the in-memory mock
  module, not a network request that can be safely failed with Playwright
  routing.
