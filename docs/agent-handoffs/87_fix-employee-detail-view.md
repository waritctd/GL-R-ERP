# 87 — fix/employee-detail-view

**Branch:** `fix/employee-detail-view` (off `origin/main` @ `4750fe8`)
**Agent:** Claude (Opus 4.8)
**Date:** 2026-07-20
**Scope:** HR-core UI only. No business logic, no API contract, no permissions, no DB schema.

## Goal

Three UI defects on `/employees/:id`, reported by the user:

1. Every content group was a collapsible accordion; four defaulted to *closed*, hiding data behind
   a click on a read-only record view.
2. Cards were oversized on desktop and mobile.
3. `ค่าตอบแทน` (compensation) rendered blank.

## What #3 actually was

Not a data bug. Measured in the browser before the fix:

```
moneyText: "฿149,500"            moneyColor: rgb(255,255,255)   ← white
secBg (.collapsible-section):    rgb(255,255,255)               ← white
panelBg (.highlight-panel):      rgb(15,118,110)                ← teal, painted over
panelH: 326px  vs  secH: 145px                                  ← 180px teal slab below the card
```

`.highlight-panel` was authored as a standalone dark panel. A `CollapsibleSection` — which
hard-sets `background: var(--color-surface)` — was later nested inside it. The white section covered
the teal, the `text-surface` salary text went white-on-white, and the surviving teal showed only as
dead space below the card. It was also a nested card, which `DESIGN.md` bans.

## Design decisions (validated with the user against a rendered comparison)

- **Compact static cards**, not full de-carding — grouping stays legible in the 2-column Thai layout.
- **Scoped to the employee detail page.** `PayrollPage` keeps its accordions; collapsing a long edit
  form is genuinely useful there.
- **Salary on a plain surface with an ink number.** `DESIGN.md`'s Rationed Teal Rule reserves teal
  for "live" state; a salary figure is not state.

## Files changed

| File | Change |
|---|---|
| `frontend/src/components/common/CollapsibleSection.jsx` | New `collapsible` prop (default `true`). When `false`: renders an `<h2>` instead of a `<button>`, no chevron, no `aria-expanded`/`aria-controls`, body always rendered, root gets `is-static`. |
| `frontend/src/components/common/CollapsibleSection.test.jsx` | +1 test: static variant renders a heading, no button, and always shows children. |
| `frontend/src/features/employees/EmployeeDetailPage.jsx` | `collapsible={false}` on all 9 sections; every `defaultOpen={false}` removed; `items-start` on the three `InfoGrid`s so cards size to content instead of stretching to row height; `.highlight-panel` and `.sensitive-panel` wrapper `<div>`s deleted; salary now `text-text text-3xl font-extrabold` with `text-text-secondary` subline and `text-text-muted` permission fallback. |
| `frontend/src/styles.css` | Added `.collapsible-header-static` + `.collapsible-section.is-static` rules (header padding 16→12, title `--text-lg`→`--text-base`, body padding tightened). Deleted `.highlight-panel` (now unused) and `.sensitive-panel` (dead — it set only `border-color` with no `border-style`, so it never rendered). Added a `.salary-history > div > span` column rule. |

### Note on the salary-history rule

Making `ประวัติเงินเดือน` visible by default surfaced a latent bug: its `<strong>` amount and
`<small>` date are inline siblings with no separation, rendering as `฿104,6501 ม.ค. 2555`. Fixed by
stacking them. This is the one change beyond the stated scope — it was invisible only because the
section used to default to collapsed.

## Results

| Check | Result |
|---|---|
| `npm run lint` | **Pass** — 0 errors, 4 pre-existing `react-hooks/exhaustive-deps` warnings (attendance, commissions, payroll; all untouched by this branch). |
| `npm test` | **Pass** — 36 files, 215/215 tests. |
| `npm run build` | **Pass** — built in 177ms. |
| `./mvnw verify` | **Not run — not required.** Backend is untouched by this branch. |

### Browser verification (mock, HR persona, 1280×900)

Post-fix DOM probe:

```
highlightPanelsInDom: 0      sensitivePanelsInDom: 0
disclosureButtons:    0      allStatic: true
compBg:  rgb(255,255,255)    compH: 113px   (was 326px — 65% shorter)
moneyText: "฿149,500"        moneyColor: rgb(15,23,42)    ← ink, ~16:1 on white
subColor:  rgb(51,65,85)     ← ink-secondary
```

`read_page` confirms the only buttons left on the page are nav, tabs, and real actions — no section
disclosure controls. All four tabs walked; every field renders on load.

Per CLAUDE.md: mock authz is **not** authoritative. No permission behaviour was changed on this
branch, so mock verification is sufficient here — but nothing about permissions was verified.

## Known risks / not done

1. **Mobile (375×812) was not visually verified**, nor was the `.salary-history` fix — see the
   incident below. Both need a browser pass before merge.
2. `CollapsibleSection` is shared with `PayrollPage`. Payroll passes no `collapsible` prop so it
   keeps the default and the `is-static` CSS cannot reach it, but payroll was not visually
   re-checked.
3. `FieldList` / `InfoGrid` were left untouched (the plan floated tightening `FieldList`'s `mt-4`
   globally; not done, since `ProfilePage` shares it and was not in scope).
4. The `impeccable` design hook flags `side-tab` at `.timeline-list > div { border-left: 3px }`.
   **Left unchanged, classified as a false positive:** that border draws the timeline spine, which is
   the component's actual content, not a decorative card accent. It is also pre-existing and outside
   this branch's scope.

## ⚠️ Incident — concurrent sessions collided in the shared checkout

Mid-task, another Claude session operating in the same primary worktree switched the branch back to
`feat/sales-pricing-request-foundation`. Before doing so it committed this branch's work as
`53b2aa8` and stashed the remaining dirty file. Nothing was lost, but the working tree was
swapped out from under this session mid-verification.

Recovery: `git worktree add .claude/worktrees/employee-detail-view fix/employee-detail-view`, then
`git stash apply stash@{0}`. All checks above were run in that worktree.

**State right now:**
- `fix/employee-detail-view` @ `53b2aa8` — the component/page/styles/test work, committed.
- The `.salary-history` fix — **uncommitted** in the worktree (also preserved as `stash@{0}`).
- The primary checkout is back on `feat/sales-pricing-request-foundation` with its own pricing work.
- `stash@{1}` = `wip: pricing request v59`, stashed by this session at the start. **Verify with the
  user before dropping it** — the pricing branch's tree currently has similar modifications, so it
  may or may not be redundant.

Do not run two implementation agents against this checkout at once. Use the worktree.

## Next prompt

> Work in `.claude/worktrees/employee-detail-view` (branch `fix/employee-detail-view`). The
> `.salary-history` change in `frontend/src/styles.css` is uncommitted — review and commit it.
> Then start the mock dev server **from that worktree** and verify at `/employees/1` as HR:
> (a) mobile 375×812 — single-column reflow, no card taller than its content;
> (b) the ประวัติ tab — `ประวัติเงินเดือน` amounts must sit on their own line above the date
> (`฿104,650` then `1 ม.ค. 2555 · เริ่มงาน`), not run together;
> (c) `/payroll` — its accordions must still collapse.
> Capture before/after screenshots, append them here, then open the PR against `main`.
