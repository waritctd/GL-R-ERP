# Phase 4A Visual QA Results

Date: 2026-07-25
Branch: `feat/leave-subday-and-contact-fields`
Evidence root: `docs/ui-repair/evidence/proposed/phase-4a-ticket-worklist/`

## Overall Result

Visual signoff: **conditional pass / route-blocked**.

The requested screenshot set was recaptured after the tablet repair. The
previous 768 x 1024 ticket-list clipping failure is resolved: the tablet shell is
readable, the explicit open action is visible, and the stage/work-reason column
wraps long Thai labels instead of clipping them into fragments.

The remaining blocker is route-based, not visual layout: Import and Account role
captures are not true `/tickets` captures because the app currently redirects
those roles away from that route.

## Captured Evidence

Role captures:

- `sales/desktop-1366x768.png`
- `sales/mobile-390x844.png`
- `sales-manager/desktop-1366x768.png`
- `sales-manager/mobile-390x844.png`
- `import/desktop-1366x768.png`
- `import/mobile-390x844.png`
- `account/desktop-1366x768.png`
- `account/mobile-390x844.png`
- `ceo/desktop-1366x768.png`
- `ceo/mobile-390x844.png`

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

1. `import/desktop-1366x768.png`, `import/mobile-390x844.png`,
   `account/desktop-1366x768.png`, and `account/mobile-390x844.png` are blocked
   as `/tickets` evidence. The current app redirects Import and Account users
   away from `/tickets` to their role landing/worklist surfaces. Do not resolve
   this by changing permissions, route visibility, or navigation destinations in
   Phase 4A.

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
- Fix mobile filter Escape behavior in a later pass if it becomes part of the
  acceptance gate. The visible close button currently restores focus.
- Re-run full validation if any further layout, route, or permission work is
  authorized.
