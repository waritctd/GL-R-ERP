# Agent Handoff

## Task
Fix five frontend UX seams from the 2026-07-16 sales-ticket-flow audit (user-approved P2 tier),
scoped to `frontend/src/` only, explicitly excluding pricing-related mock methods
(`calculatePrices`/`overrideItemPrice`/`editItems`/fx — owned by a parallel branch):

1. Timeline shows raw English enums for post-quotation dual-track events.
2. `DepositNoticePage` auto-creates a server-side draft as a navigation side effect.
3. Dashboard queue cards drop users on the unfiltered ticket list; filters reset on
   back-navigation.
4. Download handling picks wrong file extensions and has no busy state.
5. `TicketCreateModal` renders duplicate autocomplete dropdowns and allows double-submit on
   create-project/create-contact.

## Branch
`fix/ticket-frontend-seams` (branched from `origin/main` at `fd60b68`, which includes PR #216
— the deposit-notice unification)

## Base Commit
`fd60b68` (fix(deposit): unify the deposit-notice flow — issuing the document drives the
payment track (#216))

## Current Commit
(set after commit — see `git log -1`)

## Agent / Model Used
Claude Sonnet 5 (implementation agent in the Sonnet-implements/Opus-reviews loop)

## Scope

### In Scope
- `frontend/src/features/tickets/TicketDetailPage.jsx` — `EVENT_KIND_LABEL`, `eventDotClass`,
  download handlers/buttons
- `frontend/src/features/deposits/DepositNoticePage.jsx` — mount effect, empty/error states,
  download handlers/buttons
- `frontend/src/features/dashboard/TicketDashboard.jsx` — queue card navigation
- `frontend/src/features/tickets/TicketListPage.jsx` — URL-synced filter/search, cancelled tab
- `frontend/src/features/tickets/TicketCreateModal.jsx` — catalog dropdown focus tracking,
  create-project/create-contact guards
- `frontend/src/components/common/DataTable.jsx` — optional controlled search (backward
  compatible; needed to sync search text into the URL)
- `frontend/src/App.jsx` — one-line `onBack` wiring change (`navigate(-1)` instead of a fixed
  `/tickets`) so the list's URL-carried filter survives detail→back
- `frontend/src/utils/download.js` — new shared download helper
- `frontend/src/styles.css` — one new CSS rule (`.event-dot.success`), reusing existing tokens

### Out of Scope
- Any pricing-related mock methods (`calculatePrices`, `overrideItemPrice`, `editItems`, fx) —
  explicitly reserved for a parallel branch, untouched.
- Backend changes — none needed; all five items were frontend-only seams.
- The ~90 pre-existing inline-hex-color findings the design-audit hook flagged across every
  file I touched (e.g. `#fca5a5`, `#fbbf24`, `#ef4444` in `TicketDetailPage.jsx`,
  `TicketCreateModal.jsx`). All predate this branch, are far from my edits, and fixing them is
  a separate large Tailwind-migration effort per CLAUDE.md's own styling-direction section —
  left unchanged. One `.timeline-list` side-tab-border finding in `styles.css` (line ~1411) is
  also pre-existing and unrelated to the one-line `.event-dot.success` rule I added.

## Files Changed
- `frontend/src/features/tickets/TicketDetailPage.jsx` — added 10 missing dual-track Thai
  labels to `EVENT_KIND_LABEL` (`QUOTATION_ISSUED`, `CUSTOMER_CONFIRMED`,
  `DEPOSIT_NOTICE_ISSUED`, `DEPOSIT_PAID`, `IR_ISSUED`, `IR_SENT`, `SHIPPING`,
  `GOODS_RECEIVED`, `AWAITING_FINAL_PAYMENT`, `FULLY_PAID`); `eventDotClass` now returns a
  success-toned dot for Track P (payment) kinds and an info-toned dot for Track F
  (fulfillment) kinds; download handlers (`handleDownloadQuotation`,
  `handleDownloadRemainingInvoice`) now use the shared `downloadBlob` helper and track a
  per-button busy state (`downloadingQuotationKey`, `downloadingInvoice`) that disables the
  clicked button and swaps its label to "กำลังดาวน์โหลด..."
- `frontend/src/features/deposits/DepositNoticePage.jsx` — mount effect (`loadDocs`) now only
  lists+loads (DRAFT if present, else the latest ISSUED doc read-only, else `doc = null`);
  document creation moved to a new `handleCreateDraft`, fired only by a click on the new empty
  state's "สร้างเอกสารฉบับร่าง" button (disabled while `creatingDraft`); a `loadError` state
  renders a distinct error empty-state with a "ลองใหม่" retry button; บันทึก/Preview/ออกเอกสาร
  now render only when `doc` exists (`doc && !isIssued`), so there's no more dead button that
  silently no-ops; `download()` uses the shared helper and tracks `downloading` for a
  per-button busy state on the two download buttons
- `frontend/src/features/dashboard/TicketDashboard.jsx` — the three `ActionQueue` queue cards
  now navigate to `/tickets?status=submitted` / `?status=price_proposed` / `?status=approved`
  instead of the bare unfiltered `/tickets`
- `frontend/src/features/tickets/TicketListPage.jsx` — `statusFilter` and search text now live
  in `useSearchParams` (`?status=`, `?q=`) instead of local `useState`, written back with
  `replace: true`; added a `cancelled` tab (`ยกเลิกแล้ว`, tone `danger`) to `STATUS_TABS` —
  previously only reachable under "ทั้งหมด"; wired the new controlled `searchValue`/
  `onSearchChange` into `DataTable`
- `frontend/src/App.jsx` — `TicketDetailRoute`'s `onBack` changed from
  `() => navigate('/tickets')` to `() => navigate(-1)` so the list's URL-carried filter/search
  survives a list→detail→back round trip (a fixed target would always reset to "ทั้งหมด")
- `frontend/src/features/tickets/TicketCreateModal.jsx` — replaced `catalogFocusIdx` (row
  index only) with `catalogFocus` (`{ index, field }`), so the brand and model inputs on the
  same item row no longer both render the same `catalogResults` dropdown simultaneously;
  `handleCreateProject`/`handleCreateContact` wrapped in try/catch (surfacing failures via the
  existing `error`/`.form-error` state, the modal's own error-display mechanism — it has no
  `showToast` prop) and gated by `creatingProject`/`creatingContact`, which also disable their
  respective buttons and swap the label to "กำลังเพิ่ม..."
- `frontend/src/components/common/DataTable.jsx` — added optional `searchValue`/
  `onSearchChange` props; when `searchValue !== undefined` the component defers to the caller
  instead of owning an internal `search` state — every other current caller (Attendance,
  Commission, EmployeeList, Payroll) omits both props and is unaffected
- `frontend/src/utils/download.js` — **new file**. `extensionForBlob(blob, requestedFormat)`
  maps MIME type → extension (`application/pdf`→pdf, the xlsx MIME→xlsx, `text/html`→html,
  `text/plain`→txt, else falls back to `requestedFormat`); `downloadBlob(blob, filenameBase,
  requestedFormat)` triggers the actual browser download. In mock mode every document endpoint
  returns a demo placeholder blob (`text/plain` for "xlsx" requests, `text/html` for "pdf"
  requests — see `mockDocPlaceholderBlob`/`buildMockQuotationHtml` in `mockApi.js`), so a
  `.txt`/`.html` file is the **correct, expected** result there, not a bug
- `frontend/src/styles.css` — added `.event-dot.success { background: var(--color-success); }`
  next to the existing `.event-dot.transition/.comment/.created` rules

## Commands Run
```bash
git fetch origin && git switch -c fix/ticket-frontend-seams origin/main
cd frontend && npm ci
npx eslint <each touched file>          # spot checks during implementation
cd frontend && npm run lint && npm test && npm run build
```

## Test / Build Results
- **Lint**: `npm run lint` — 0 errors, 7 warnings, all pre-existing `react-hooks/exhaustive-deps`
  warnings in files I touched or elsewhere in the repo (none introduced by this branch; I
  confirmed each one predates my edits).
- **Tests**: `npm test` — **94/94 passed** (19 test files), including `contract.test.js`
  (mockApi/hrApi surface parity — untouched) and `DataTable.test.jsx` (confirms the new
  optional controlled-search prop doesn't break existing uncontrolled usage).
- **Build**: `npm run build` — succeeds, 274 modules transformed, new `download-*.js` chunk
  emitted for the shared util.
- **Backend**: not touched, not run (task was frontend-only).

## Decisions Made
- Payment-track dual-track events get the new `.event-dot.success` (green) tone; fulfillment-
  track events reuse the existing `.event-dot.transition` (blue/info) tone, since that class
  was already info-colored — no new class needed for Track F.
- `DepositNoticePage`'s document-picking priority is DRAFT first, then the highest-`version`
  ISSUED doc, else `null`. SUPERSEDED docs are never surfaced (matches the mock's own
  supersede-on-reissue behavior).
- Scoped the dashboard-queue-card fix to the three `ActionQueue` items only (the literal
  "~lines 93-95" the task named), not the `StatCard` grid tiles below them, which have the
  same unfiltered-`/tickets` pattern but weren't named in scope. Flagging this as a
  follow-up candidate below rather than expanding scope unasked.
- `App.jsx`'s `onBack` now uses `navigate(-1)` rather than a fixed route. This is the only way
  to make "list→detail→back preserves filters" true given `onBack` is wired at the route level,
  not inside `TicketListPage`/`TicketDetailPage` themselves — necessary to fully satisfy item 3,
  not just a nice-to-have.
- `TicketCreateModal.jsx` has no `showToast` prop; its own error-surfacing mechanism is local
  `error` state rendered in a `.form-error` div (see `submit()`). Used that same mechanism for
  the new create-project/create-contact try/catch rather than inventing a toast call the
  component can't make.

## Assumptions
- "Existing status-badge/design-token classes only" (item 1) was satisfied by adding one new
  CSS rule (`.event-dot.success`) using an existing `--color-success` token, not by inventing a
  new ad-hoc inline color.
- Item 3's "each card" refers specifically to the `ActionQueue` queue items (the literal
  ~93-95 line range named in the task), not the `StatCard` grid below them.

## Known Risks
1. `navigate(-1)` in `App.jsx`'s `TicketDetailRoute.onBack` falls back to whatever the browser
   history stack holds. If a user lands directly on a `/tickets/:id` deep link (e.g. a shared
   URL, or a fresh tab) with no prior `/tickets` entry in history, "กลับ" will navigate outside
   the app (or no-op at the history boundary) instead of landing on the ticket list. This is a
   standard SPA trade-off for preserving filter state and matches the pattern already used by
   `DepositNoticeRoute`'s sibling wiring in spirit, but is worth a second look.
2. The `StatCard` tiles on `TicketDashboard.jsx` (เปิดอยู่ทั้งหมด / กำลังดำเนินการ / etc.) still
   navigate to unfiltered `/tickets`, same seam as the queue cards but out of the task's named
   scope — flagged, not fixed.
3. I could not visually drive the mock UI in this session's Browser pane: `preview_list` showed
   the running dev server's cwd bound to a **different** worktree
   (`.claude/worktrees/charming-gauss-f118ad`), not this branch's worktree
   (`agent-a47d71de5eab98631`) — a fixed session-level binding, not something fixable from
   here. I did not copy changes into the other worktree to force a match. Verification instead
   relied on: full manual diff re-reads of every changed code path, `npm run lint`, `npm test`
   (94/94), and `npm run build`, all green. The next reviewer should drive the actual UI
   (frontend-mock, ticket with dual-track history, a fresh deposit-notice page, the dashboard
   queue cards, and the create-modal's brand/model autocomplete) since that step is still
   outstanding.
4. `TicketCreateModal`'s create-project/create-contact errors now surface via the modal's
   existing `error`/`.form-error` state (shared with the main submit-validation errors) rather
   than a dedicated inline error next to each mini-form — acceptable given the component has no
   toast, but a future pass could scope the error message closer to the field that failed.

## Things Not Finished
- Live browser verification (see Known Risk 3) — recommend the reviewer run
  `frontend-mock` and manually check: dual-track ticket timeline Thai labels + dot colors,
  deposit-notice page not creating a draft on mere visit, dashboard card → filtered list,
  filter surviving list→detail→back, and the create-modal's brand/model dropdowns no longer
  stacking.

## Recommended Next Agent
Claude Opus review (per this repo's standing Sonnet-implements/Opus-reviews loop) — should
additionally attempt live browser verification if it has working preview-tool access to this
branch's actual worktree.

## Exact Next Prompt
```text
Repo GL-R-ERP, branch fix/ticket-frontend-seams (based on origin/main at fd60b68, includes PR
#216). Read CLAUDE.md and docs/agent-handoffs/57_fix-ticket-frontend-seams.md — it documents 5
frontend UX-seam fixes (dual-track timeline labels, deposit-notice draft-on-visit bug, dashboard
filter passthrough + URL-synced list filters, download extension/busy-state fix, ticket-create
modal duplicate-dropdown + double-submit fix). Known risk: I could not visually verify in the
Browser pane because its preview server was bound to a different, stale worktree
(charming-gauss-f118ad) rather than this branch's worktree — if your environment can launch
`frontend-mock` against THIS worktree, drive it and confirm: (1) a dual-track ticket's timeline
shows Thai labels with success/info dot colors, (2) visiting DepositNoticePage for a ticket with
no existing document creates NO draft until "สร้างเอกสารฉบับร่าง" is clicked, (3) a dashboard
queue card lands on a pre-filtered ticket list and the filter survives list→detail→back, (4)
downloads in mock mode save as .txt/.html (expected placeholder behavior, not a bug) with a
busy/disabled state per button, (5) typing in the create-modal's brand field no longer also
opens the model field's dropdown. Also sanity-check the two decisions flagged as risks: `navigate(-1)`
for TicketDetailRoute's onBack (deep-link edge case), and that StatCard tiles on
TicketDashboard.jsx still don't filter (out of scope, left as-is — confirm agreement or file a
follow-up). Review the diff, then merge on the user's say-so.
```
