# Phase 4A Visual QA Results

Date: 2026-07-25
Branch: `refactor/ui-phase-4-ticket-worklist`
Evidence root: `docs/ui-repair/evidence/proposed/phase-4a-ticket-worklist/`

> Correction (2026-07-25): an earlier revision of this file recorded the branch
> as `feat/leave-subday-and-contact-fields`. That was wrong — that branch is the
> unrelated leave/contact-fields work. All evidence in this file belongs to
> `refactor/ui-phase-4-ticket-worklist` and was recaptured on that branch's head
> after it was rebased onto `main` at `c198380`.

## Overall Result

Visual signoff: **PASS**.

Upgraded from the earlier "conditional pass / route-blocked" after the acceptance
pass closed every condition:

1. The mobile filter sheet now has a full modal contract (Escape, focus trap,
   inert background) — asserted in a real browser, not just jsdom.
2. The Import/Account "route blockers" were a mis-classification. `/tickets` is
   out of those roles' scope by design, so there is nothing to unblock and no
   follow-up is owed. Their captures are relabelled as landing-surface evidence.
3. Three collateral regressions found by independent review were fixed, two of
   them confirmed by measurement (see "Independent Review" below).
4. All evidence was recaptured on the exact branch head after those fixes, by
   `frontend/e2e/phase4a-acceptance.spec.js` with `CAPTURE_EVIDENCE=1`.

The screenshot set was recaptured after the tablet repair. The previous
768 x 1024 ticket-list clipping failure is resolved: the tablet shell is
readable, the explicit open action is visible, and the stage/work-reason column
wraps long Thai labels instead of clipping them into fragments.

## Captured Evidence

`/tickets` worklist captures — the roles that hold `canViewDealPipeline`:

- `sales/desktop-1366x768.png`
- `sales/mobile-390x844.png`
- `sales-manager/desktop-1366x768.png`
- `sales-manager/mobile-390x844.png`
- `ceo/desktop-1366x768.png`
- `ceo/mobile-390x844.png`

Role landing-surface captures — **not** `/tickets` evidence:

- `import/desktop-1366x768.png`
- `import/mobile-390x844.png`
- `account/desktop-1366x768.png`
- `account/mobile-390x844.png`

Import and Account do not hold `canViewDealPipeline`, so `/tickets` is not a
route they can reach; these four files show the role landing/worklist surface
they are routed to instead. They are recorded here for completeness of the role
sweep and must not be read as `/tickets` worklist evidence — the Phase 4A
worklist changes are unobservable on them. See "Out Of Scope" below.

Shared captures:

- `shared/tablet-768x1024.png`
- `shared/tablet-1024x768.png`
- `shared/active-filters.png`
- `shared/filtered-empty.png`
- `shared/long-thai-content.png`
- `shared/keyboard-focus.png`
- `shared/loading.png`
- `shared/error-retry.png`
- `shared/mobile-filter-surface.png`
- `shared/dense-table.png`
- `shared/mobile-record-card.png`

## Passed Visual Checks

- Sales desktop and mobile captures show a clear page title, role scope,
  reachable search/filter controls, a visible create action, and explicit record
  open actions.
- Sales Manager and CEO desktop captures preserve pipeline information without
  turning the phase filter into a dominant dashboard mosaic.
- `shared/tablet-768x1024.png` shows the deliberate tablet icon rail with no
  wrapped group-label fragments. The ticket table uses the tablet compact
  treatment, keeps required columns visible, and shows the explicit open action.
- `shared/tablet-1024x768.png` shows the deliberate tablet icon rail with no
  wrapped group-label fragments, clear active route, and visible open column.
- Active filters and filtered-empty evidence show distinct states with visible
  focus treatment and Thai-first labels.
- Mobile filter surface is a deliberate sheet, not compressed desktop controls.
- Loading skeletons are visually decorative and do not announce as record text in
  the rendered state.
- Error/retry evidence uses calm Thai copy and a retry action without raw
  exception details.

## Failed Or Blocked Checks

None.

## Out Of Scope — Intentional Route Scope (Not Applicable)

Import and Account `/tickets` checks are **not applicable**, not blocked. They
were previously logged as blockers, which was a mis-classification: nothing is
obstructing the check, the route is simply outside those roles' scope by design.

`/tickets` is the deal **pipeline browser** and is gated on
`canViewDealPipeline`, which is `['sales', 'sales_manager', 'ceo']`
(`frontend/src/api/routes.js:291`, enforced at
`frontend/src/app/permissions.js:42` and `:76`). Import and Account retain the
broader ticket-**detail** read (`canViewTickets`) and reach individual records
from their own role landing/worklist surfaces. This split is the finalized
role-scoped-views design, asserted in
`frontend/src/app/permissions.test.js:36-37`.

There is therefore no Phase 4A action, and no follow-up is owed. Do not "fix"
this by widening permissions, route visibility, or navigation destinations.

Related-record access from those landings was exercised and passed: Import
opened a related ticket/work item, Account opened a money-related ticket/work
item, and browser back returned to the landing state.

## Independent Review

An independent reviewer audited the shared-table (`DataTable.jsx`) and
`styles.css` diffs and returned FAIL on both, with 12 findings. Every claim was
re-verified before acting; two were real user-facing regressions, one was not
reproducible, and the rest were correctness or hygiene issues.

### Confirmed and fixed — collateral damage to pages outside this branch's scope

- **Shared `.ticket-table` was repurposed.** The worklist widened it from 4 to 6
  columns and added it to the `min-width: 900px` list in the `<=1040px` block
  (main never had it there). But `TicketDashboard.jsx` and
  `AccountFinancePage.jsx` also use that class and emit exactly **4** cells, and
  both render inside `overflow: hidden` panels with no scroller. Measured at
  768x1024: the Account finance row's CTA — the `account` role's primary action —
  sat at x=793 inside a panel ending at x=736, i.e. **clipped with no scrollbar
  to reveal it**. Fixed by giving the worklist its own `.ticket-worklist-table`
  and restoring `.ticket-table` to its 4-column, no-minimum contract.
- **Expansion panels collapsed to shrink-to-fit.** `.data-table-expanded-row` is
  a `<tr>` inside a `display: block` tbody with no display of its own, so the
  browser wrapped it in an anonymous (width: auto) table. The panel hugged the
  left edge at content width instead of spanning the row, on both
  `renderExpanded` callers (AttendancePage, CommissionPage) above 720px. Fixed by
  block-levelling the row and its cell.

Both fixes are **mutation-checked**: re-introducing each defect turns its
specific browser assertion red and nothing else.

### Confirmed and fixed — correctness / hygiene

- Nav badge counts were silenced for screen readers (`aria-label` replaces the
  accessible name, and the count `<b>` lives inside the link). Count folded into
  the label; the visual badge is now `aria-hidden`. Covered by `Sidebar.test.jsx`
  — no seeded mock role carries a badge, so this could not be a browser test.
- Dead CSS that could never apply, papered over with `!important`:
  `styles.css` is imported into `layer(legacy)` and Tailwind utilities into
  `layer(utilities)`, so `.ticket-filter-bar`'s gap/padding always lost to
  `FilterBar`'s own utilities. Removed and re-expressed at the call site with the
  Phase 3.4 `mobile:` variant, which also removed the `!important`.
- Dead selector `.ticket-filter-bar > .button-variant-text` (no such class) plus
  its live-but-brittle sibling `button[class*="text-primary"]`, which
  substring-matched a Tailwind utility. Replaced with an explicit
  `.ticket-filter-clear` hook.
- Filter sheet `z-index: 54/55` painted **above** `.modal-backdrop` (50). Moved
  to 44/45 — above the nav drawer (40), below dialogs.
- Scrim `rgba(15, 23, 42, 0.42)` was a third hand-rolled overlay opacity; now
  `var(--color-overlay-drawer)`.
- Four off-ramp font sizes (`10px` x3, `0.625rem`) moved onto `--text-2xs`.
- Duplicate `@media (min-width: 721px) and (max-width: 1040px)` blocks merged.
- Stale comment in `CommissionPage.jsx` documenting the removed `onRowClick`.
- Dead `data-table-list-panel` class hook removed.
- `Button` threw in production when `variant="icon"` lacked a name — a shared
  primitive taking down the page over a label typo. Now throws in dev, logs in
  production.

### Not reproducible — reported as a defect, is not one

- The review claimed `.sidebar-account span { display: none }` in the tablet rail
  blanked the logout icon by also matching Button's inner `<span>` wrapper, and
  reported measuring `display: none` / icon width 0. **That measurement is
  wrong.** With the descendant selector deliberately restored, the wrapper
  computes `display: flex` and the icon measures 18px, because `inline-flex` is
  in the `utilities` layer and the rule is in `legacy` — the same cascade
  mechanism the review itself identified elsewhere. The selector was still
  tightened to `> span` because that states the actual intent and does not depend
  on layer order, and a browser test now guards the icon.

### Accepted as-is, recorded rather than silently kept

- **~600 lines of page-specific `.ticket-*` CSS in the global sheet.** The review
  is right that this runs against the Tailwind-first direction, and that the
  hand-rolled bottom sheet duplicates the **Drawer** primitive already scoped in
  `COMPONENT_CONTRACTS.md`. Converting it is a genuine slice of work, not an
  acceptance fix, and doing it inside this branch would replace a reviewable
  repair with a blind rewrite of the surface under review — which
  `LEGACY_STYLE_MIGRATION.md` explicitly warns against. Left in place and
  recorded as the branch's known deviation; the Drawer extraction belongs to the
  slice that owns it.
- **`stickyHeader` is inert.** `.table-head.is-sticky` is a `position: sticky`
  `<tr>` whose `<thead>` is `display: block`, so it has zero travel. No page
  passes `stickyHeader`, so user impact is zero today; recorded rather than fixed
  because the fix belongs with the first real consumer.
- **The new global focus indicator reaches every page**, which is wider than this
  branch's stated scope. It is a real accessibility gain (previously only three
  components had one) and is kept, but it is called out here so a reviewer knows
  to expect focus-ring changes outside the ticket worklist.

## Recheck Warnings

- Sales Manager and CEO mobile first viewports make the first record visible, but
  the richer pipeline summary can push the record open action below the fold.
  Recheck if the mobile pipeline density changes again.
- The first-load skeleton capture temporarily shows zero counts while loading.
  This is visually stable, but background-refresh error behavior should continue
  to preserve already-loaded rows.
- During capture, the mock Vite server logged repeated `/api/auth/login` proxy
  `ECONNREFUSED` messages before the mock login fallback completed. This matches
  the existing QA warning and was not fixed silently.

## Required Next Checkpoints

- Keep Import/Account route blockers documented unless a later authorized phase
  changes route access or navigation. Do not silently alter role gates.
- ~~Fix mobile filter Escape behavior in a later pass~~ — done in this pass; it
  became part of the acceptance gate.
- Re-run full validation if any further layout, route, or permission work is
  authorized.
- Extract the **Drawer** primitive and migrate the hand-rolled
  `.ticket-filter-sheet` / `.ticket-filter-backdrop` onto it, per
  `COMPONENT_CONTRACTS.md`. Tracked, not done here.
- Fix `stickyHeader` (or delete it) when a page first needs a sticky table head.

## Validation Re-run On This Head

- `npm run lint` — 0 errors, 1 pre-existing unrelated warning
  (`PayrollPage.jsx:312`).
- `npm test` — 66 files / 612 tests passed.
- `npm run build` — clean.
- `npm run test:e2e` — 64 specs passed (25 pre-existing + 39 new in
  `phase4a-acceptance.spec.js`).
- `git diff --check` — clean.
- Evidence recaptured with `CAPTURE_EVIDENCE=1`: 9 role/shared captures updated,
  18 new `datatable-callers/` captures (9 surfaces x desktop+mobile).

## Authorization

**No authz change.** No role gate, scope filter, or read/write ownership rule was
touched. The Import/Account reclassification is a documentation correction only —
`canViewDealPipeline` and every route guard are byte-identical to `main`. Nothing
in this branch requires a real-DB integration test; equally, nothing here should
be read as verifying any permission rule, since all browser work ran under
`VITE_USE_MOCKS=true`.
