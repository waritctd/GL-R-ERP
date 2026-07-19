# Agent Handoff

## Task
Repair the HR employee list (`พนักงานทั้งหมด`): collapse two overlapping status filters into one, default the view to currently-employed staff, fix the inconsistent search matching, and make the mobile cards substantially more compact. Sorting had to stay reachable but collapsed on both mobile and desktop, because the filter bar itself was consuming too much vertical space.

## Branch
`fix/employee-list-search-and-layout`

Worked in a separate git worktree at `/Users/ploy_warit/Desktop/GL-R-ERP-employees` because another live session was actively committing on `feat/sales-pricing-request-foundation` in the primary checkout. That session committed `042f9cb` mid-setup — a plain `git checkout -b` would have landed on top of its uncommitted work.

## Base Commit
`7771782` (`merge: UX/UI audit remediation Phases A + B`, = `origin/main` at branch time)

## Current Commit
`7a8c917` — `db904ef` (the work) merged with `cc384aa` (PR #236, profile avatar dropdown) which
landed on `main` mid-review. PR: https://github.com/waritctd/GL-R-ERP/pull/237

The merge was clean but overlapped in two places worth knowing about:
- `styles.css` — PR #236 also touched avatar styling. No conflict; `.avatar-xs` sits alongside the
  existing `sm`/`md`/`lg`/`xl` steps and no existing rule was altered by either side.
- `utils/format.js` — PR #236 changed it. `formatMoney` output is unaffected, and the new
  employee-list test asserts through `formatMoney()` rather than a hardcoded string, so it tracks
  any future format change automatically.
- PR #236 **deleted** `MyRequestsPage.jsx` and folded it into `ProfilePage.jsx`. The pages still
  depending on the shared `.reflow-cards` CSS are therefore: `AttendancePage`, `CommissionPage`,
  `LeavePage`, `OvertimePage`, `ProfilePage`. Still 5 — do not delete that rule.

## Agent / Model Used
Claude Opus 4.8

## Scope

### In Scope
- `EmployeeListPage`: filter consolidation, URL-backed filter state, search fix, collapsible filter+sort disclosure, compact mobile card.
- `DataTable`: additive optional controlled-sort props.
- `HrDashboard`: two stat-card link targets + one label correction.
- A new `avatar-xs` size in the global avatar scale.

### Out of Scope
- Backend, mock API, permissions, DB schema — untouched. Filtering is entirely client-side over the list `useHrData` already fetches with no params.
- `EmployeeDetailPage`'s back button still drops filter context. Pre-existing (filters used to be local `useState`), now *fixable* thanks to URL state, deliberately left for a separate diff.

## Files Changed

- `frontend/src/features/employees/EmployeeListPage.jsx` — rewritten filter layer. `useState` filter object replaced by `useSearchParams` (`q`/`div`/`dept`/`status`/`sort`, each omitted at its default). The two status selects collapsed into one (`ปฏิบัติงานอยู่` / `ทำงานปกติ` / `ทดลองงาน` / `ลาออก` / `ทั้งหมด`) keyed on `statusId` alone. Search now normalises all four accessors with the same trimmed+lowercased term. New collapsible `ตัวกรอง` disclosure holding สถานะ/ฝ่าย/แผนก/เรียงตาม, with active-filter chips shown when collapsed. New `EmployeeCard` mobile renderer. `reflow-cards` dropped from `gridClassName`.
- `frontend/src/components/common/DataTable.jsx` — added optional `sort` / `onSortChange`, mirroring the controlled-search idiom already in the file. When `sort` is `undefined`, the existing `initialSort` + internal `useState` path is byte-for-byte the previous behaviour.
- `frontend/src/components/common/DataTable.test.jsx` — one new case covering the controlled-sort contract (renders caller order, reports header clicks upward, does not self-mutate).
- `frontend/src/features/employees/EmployeeListPage.test.jsx` — **new**, 16 cases.
- `frontend/src/features/dashboard/HrDashboard.jsx` — `พนักงานทั้งหมด` card now links to `/employees?status=all`; `ทำงานปกติ` card relabelled `ปฏิบัติงานอยู่`.
- `frontend/src/styles.css` — new `.avatar-xs` (28px) in the avatar scale. Additive only; no existing rule changed.

## Commands Run

```bash
git worktree add -b fix/employee-list-search-and-layout /Users/ploy_warit/Desktop/GL-R-ERP-employees origin/main
cd frontend && npm ci
npm run lint && npm test && npm run build
# browser verification against a worktree-local mock dev server on :5201
```

## Test / Build Results
- **Lint: pass** — 0 errors. 4 warnings, all pre-existing `react-hooks/exhaustive-deps` in `AttendancePage`/`CommissionPage`/`PayrollPage`, none in changed files.
- **Frontend tests: pass** — post-merge, 36 files / **214 tests** (pre-merge on this branch alone: 35 files / 207, up from 190 on base; +16 employee-list, +1 DataTable). `DataTable`'s original 9 and the two `App.test.jsx` cases that render `/employees` all still green.
- **Build: pass.**
- **Backend: not run.** No backend files were touched.

⚠️ **Flaky test observed.** One post-merge local run reported 5 failing files (incl.
`TicketDetailPage > delivery modal …` at 4.4s) while the machine was loaded by a dev server and
other processes; the suite took 53s instead of ~9s. Two subsequent clean runs were fully green
(36/36, 214/214), and CI was green. These look like timeout-sensitive tests under CPU contention,
not a regression from this branch — but they are worth watching, since a loaded CI runner could
reproduce them.

## Browser Verification (mock, `VITE_USE_MOCKS=true`)

Dataset: 30 employees — 28 currently-employed (ACT+PRB), 2 resigned.

| Check | Result |
|---|---|
| Default `/employees` | 28 rows, zero `ลาออก`, URL stays clean (no params) |
| `?status=all` | 30 rows |
| `?status=RSG` | 2 rows |
| Desktop header click on เงินเดือน | writes `?sort=salary.asc`, เรียงตาม select syncs, rows reorder (฿15,000 → ฿16,500) |
| Deep link `?status=all&sort=salary.desc` | restores both selects, 30 rows, ฿156,000 first |
| 375px | cards **113px** each (from ~266px), no horizontal overflow, panel collapsed, chip visible, salary right-aligned |
| 375px panel open | all four controls stack, `role="region"` + `aria-labelledby` wired |
| 768px | renders the table (not cards); panel scrolls internally 900px-in-616px while the **page** does not overflow (768=768) |
| 1280px | panel open by default, four-across grid |
| Dashboard | `30 พนักงานทั้งหมด` → `?status=all` → 30 rows; `28 ปฏิบัติงานอยู่` → default → 28 rows |
| Console | no errors at any breakpoint |

**Mock verification is incomplete for anything permission-shaped.** Nothing here changes permissions — `canManageEmployees` gating is untouched — but this was not verified against the Java service and no such claim is made.

## Decisions Made
- **Default = ACT + PRB**, not ACT alone. `EmployeeStatus.active(id)` is `!"RSG".equals(id)`, so probation already counted as active; excluding it would have hidden new hires and desynced the list from `HrDashboard`'s `activeCount`.
- **`ปฏิบัติงานอยู่` for the default option**, deliberately not `ทำงานปกติ` (that is ACT's own label, meaning *not on probation*) and not `ใช้งานอยู่` (the retired select's label, which reads as account activation).
- **Salary kept on the mobile card**, per explicit user decision. Tradeoff recorded under Risks.
- **Sort moved into the disclosure** rather than deferred. The three already-migrated pages accepted losing mobile sort; the user asked for it to be reachable but compact, so `DataTable` gained controlled-sort props instead.
- **Status chip renders even at the default.** It is the only on-screen signal that resigned staff are hidden.
- **`ล้าง` keyed off URL params, not chip count** — otherwise an untouched page offers a clear button that clears nothing.
- **`avatar-xs` added** rather than accepting a 38px avatar setting card height. Cards went 123px → 113px.
- **`CollapsibleSection` not reused** — it renders a titled `<section>` with a full-width header button and legacy CSS, which would turn a compact inline toggle into a titled block. Its a11y contract (`aria-expanded`/`aria-controls`/`role="region"`) was copied.

## Assumptions
- `statusId` is authoritative for employment state. The dirty-data case (`is_active = TRUE` with a `ลาออก`-matching name) resolves to `RSG` and is now hidden by default — the correct HR reading.
- Thai has no case, so applying `.toLowerCase()` uniformly is a no-op there; the real fix was the missing `.trim()`.

## Known Risks
- **Resigned staff hidden by default.** Any workflow that relied on landing on `/employees` and seeing everyone now needs `?status=all`. Belongs in release notes.
- **`ล้าง` semantics changed** — reset returns to currently-employed, not everyone.
- **Desktop default sort order changed** to name-ascending; the list previously rendered in raw backend order.
- **Salary is visible over-the-shoulder** on a phone for every listed employee. Accepted by the user in favour of mobile parity for payroll work.
- **Discoverability of collapsed filters.** Mitigated by the count badge and chips; if HR still misses it, the fallback is persisting last-open state.
- **`DataTable` is shared by 7 pages.** The new props are additive and optional and the existing suite is green, but this is the one cross-page risk in the diff.
- **`q` is not debounced** — every keystroke refilters the client-side list. Identical to today and to `TicketListPage`; pre-existing.

## Things Not Finished
- Not committed and not pushed.
- `EmployeeDetailPage` back button could now preserve filter state — deliberately deferred.
- The design hook flags `styles.css:1347` (`.timeline-list > div` left border). Pre-existing, not in this diff, and a legitimate timeline rail rather than a decorative card accent — left as-is, not suppressed.

## Recommended Next Agent
Claude Opus review — verify the `DataTable` controlled-sort contract against the other 6 callers, and sanity-check the Thai labels with a native reader before merge.

## Exact Next Prompt

```
Review branch fix/employee-list-search-and-layout in the worktree
/Users/ploy_warit/Desktop/GL-R-ERP-employees (base 7771782, docs/agent-handoffs/86_*.md).

Focus on:
1. DataTable.jsx controlled sort — confirm `sort === undefined` leaves the initialSort
   path behaviourally identical for AttendancePage, CommissionPage, TicketListPage
   (the only initialSort caller, line ~329), PriceImportPage, PayrollPage,
   CatalogSearchPage. Check the setSorting updater handles TanStack's function form.
2. The status semantics: default is ACT+PRB. Verify against
   backend/.../employee/EmployeeStatus.java that this matches `active(statusId)`.
3. Thai labels: ปฏิบัติงานอยู่ vs ทำงานปกติ vs ทั้งหมด — is the distinction clear to a
   native reader in the select and in the chip?
4. Whether the HrDashboard relabel (ทำงานปกติ → ปฏิบัติงานอยู่) belongs in this diff
   or should be split.

Do not implement beyond tiny safe fixes. Report findings.
```
