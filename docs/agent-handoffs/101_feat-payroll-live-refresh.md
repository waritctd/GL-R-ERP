# 101 — feat/payroll-live-refresh

## Goal
Make the payroll page's **รีเฟรช (Refresh)** button pull *all* current payroll data into the view
(commission, overtime, leave, director remuneration, tax/SSO) **without committing/locking the
month**. HR wanted an "update to latest" action that is not "Process Payroll".

## Root cause
`PayrollPage` had two actions that looked similar but weren't:
- **รีเฟรช** → `load()` → `api.payroll.current` → backend `PayrollService.currentOrPreview`, which for an
  already-**PROCESSED** month returns the **saved snapshot** (figures frozen at process time). So after
  data changed (e.g. the commission month-shift), refreshing showed stale numbers.
- **Preview** → `api.payroll.preview` → backend `PayrollService.preview`, a **live recompute** of every
  system-derived figure, committing nothing.

So the live "refresh everything" already existed — on the wrong button. Fix: point Refresh at the
live recompute.

## Files changed
1. `frontend/src/features/payroll/PayrollPage.jsx`
   - Added `refresh()` — live recompute via `api.payroll.preview(payload())`, preserves HR's on-screen
     inputs, toasts `อัปเดตข้อมูลล่าสุดแล้ว (ยังไม่ได้ประมวลผล)`. Nothing is committed.
   - Repointed the รีเฟรช button `onClick={load}` → `onClick={refresh}`, added a Thai `title` tooltip.
2. `frontend/src/features/payroll/PayrollPage.test.jsx`
   - New test: "Refresh recomputes a processed month live (Preview), never committing" — asserts
     clicking รีเฟรช on a PROCESSED period calls `preview` and never `process`.

No backend change. No API-contract change (reuses the existing `preview` endpoint). **No authz change.**

## Behaviour notes / tradeoffs
- After a Refresh the on-screen period is an **uncommitted PREVIEW** (`period.id` null), so the
  export/payslip buttons (which need a saved period id) disable until you **Process**. Intended:
  you don't export un-processed numbers.
- Initial page load still shows the official saved snapshot for a processed month (so HR sees what was
  actually paid); Refresh is the explicit "show me live" action.
- Refresh recomputes backend-derived values (commission, OT, director pay, leave refund, tax/SSO) and
  keeps HR-typed inputs. It does **not** re-pull carry-forward suggestions (would clobber typed
  special-pay/กยศ values), so the leave-days *pre-fill* isn't re-fetched on refresh of a saved month.

## Commands run / results
- `cd frontend && npm run lint` — 0 errors (1 pre-existing `useEffect`/`load` warning, not from this change).
- `npx vitest run src/features/payroll` — 15/15.
- `npm test` — **546/546** (incl. `contract.test.js`).
- `npm run build` — ✓.
- Browser smoke skipped: mock port 5200 held by a concurrent session, and mock data wouldn't exercise
  the real commission recompute. Wiring proven by the unit test.

## Known risks
- Low. Pure frontend button-handler repoint + one test. The export-disabled-after-refresh behaviour is
  the only UX change to explain to HR.

## Next prompt
"Optionally consolidate: the separate 'Preview' button is now redundant with รีเฟรช (both live-recompute).
Decide whether to drop Preview and update the two tests that click it, or keep both. Then commit
feat/payroll-live-refresh and open a PR (rebased on latest origin/main)."
