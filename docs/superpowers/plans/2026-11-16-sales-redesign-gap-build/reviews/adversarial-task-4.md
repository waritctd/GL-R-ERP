# Adversarial review — task 4 (`/visits` + phone "วันนี้ของฉัน"), commit 1511cbb8

**Verdict: FIX BEFORE MERGE** — no permission hole and no desktop regression found; one real phone regression (unticked/overdue visits vanish from the phone home) plus a few smaller losses. Everything below was run in worktree `agent-a02cc4dbe5f0d813a` detached at 1511cbb8; final `git status --short` is clean.

Baseline: `npx vitest run src/features/salesBoard/ src/components/layout/ src/app/` → 20 files / 280 tests pass. `TZ=UTC` and `TZ=Pacific/Honolulu` runs of `src/features/salesBoard/ src/app/` → 230/230 pass (date maths is pure-UTC ISO strings; no machine-TZ dependence).

## Findings

### MEDIUM (proven) — unticked visits disappear from the phone home; card claims nothing is left
- **What:** On the base commit a rep's phone showed (a) this week's card with earlier-day visits flagged "เลยกำหนด" and (b) `CarriedOverVisits` (unticked visits from the 31 days before Monday), each tickable. The new default phone screen shows neither. `nextVisit` excludes them by design, and the card then reads "ไม่มีนัดที่เหลือสัปดาห์นี้" — while the rep has an unticked Tuesday visit. Both are only reachable via "ดูหน้าเต็ม" (or `/visits` + ‹ for last week; `/visits` has no carried-over section).
- **Input:** Thu 12 Nov 10:00 BKK; `visitsThisWeek=[PLANNED 2026-11-10]`; or `salesVisits.list` returning a PLANNED 2026-11-05 visit. Phone screen text contains neither contact.
- **Where:** `TodayOnPhone.jsx:198` (only `nextVisit`), `MyWork.jsx:189-203` (carriedQuery still fetched at :94 but never shown in phone mode), `visitWeek.js` `nextVisit`.
- **How:** scratch probes (deleted) failed with the phone text shown in full; read of base `MyWork.jsx`.
- **Fix:** a "ค้าง n นัด" line/link on the phone card (data already loaded) to the full page or the tick dialog.

### LOW–MEDIUM (proven) — myWork failure removes the only log button on the phone
- When `GET /api/sales/my-work` fails, `TodayOnPhone` renders only "โหลดไม่สำเร็จ" + the way-out row; บันทึกเข้าพบ and the quick row are gone (`TodayOnPhone.jsx:190-226`). Base page kept its sticky บันทึกเข้าพบ regardless. Logging a new visit needs nothing from that payload. Probe confirmed no button.

### LOW (proven) — crossing the 720px breakpoint drops the call dialog
- `CallPicker`/`CallModal` state lives in `TodayOnPhone` (`:172-173`), which unmounts when `useIsMobile` flips (phone rotated to landscape > 720px, common). Chosen outcome / typed note lost. Plan/log/tick dialogs survive because MyWork owns them (probe confirmed plan dialog survives). Fix: hoist `picking/calling` into MyWork like the other dialogs.
- No first-paint flash: `useMediaQuery` reads `matchMedia` synchronously in the initializer.

### LOW (read code) — other phone losses/judgment calls
- SalesOverview's `before` (money band incl. commission estimate, the next-action worklist) is behind "ดูหน้าเต็ม"; its queries still fire on the phone (wasted requests). Follow-up row actions นัดเข้าพบ/ปิดงาน per deal, cancel-visit, board filters likewise one extra tap. Brief permits this; nothing is unreachable.
- "ดูหน้าเต็ม" is plain component state: every return to `/` lands on the phone screen again. Acceptable, not persisted.
- Sunday (or when nothing remains this week): a Monday visit is never "next" — card says nothing left. Data limitation of `visitsThisWeek`; wording is literally true.
- Past-time unticked visit today stays "นัดถัดไป · 08:00" at 15:00 ahead of a 14:00 one (documented rule; confirmed by probe).
- `CallModal` subtitle always reads "เงียบ N วัน", now also for non-quiet deals picked from "งานอื่นของฉัน" (`FollowUpList.jsx:27`).
- Wording: the phone picker says "งาน", but dialogs opened from the new screens (PlanVisitModal "ดีล / ไม่มีดีล", "แม้ยังไม่มีดีล", VisitDoneModal hint, MyWork's 'สร้างดีลเรียบร้อย' toast from the phone's งานใหม่) still say ดีล. The tests' "no ดีล" checks strip dialogs (`ownText`), so they cannot see this. Admitted in the report as shared copy.

### Cash card (proven by probes)
- `cash=null` → absent. All-zero → "ยังไม่มีเงินที่คาดว่าจะเข้าเดือนนี้". Expected money only in past weeks with forecast>0 → amount + "จากที่คาด …" with no รอบถัดไป (correct). Only divergence from CashCard: forecast=0 & received=0 but a past week with money → compact says "nothing expected" while desktop shows bars (inconsistent data, unlikely). Month-boundary: at Mon 30 Nov the current week belongs to December's weeks, so รอบถัดไป is omitted — mirrors CashCard's month model. No misleading figure found.

## Permissions (attack 1) — no disagreement found
- Java: `VisitService.VISIT_ROLES={sales,sales_manager,ceo}` (:43, checked :70/95/140/164), `TEAM_ROLES={sales_manager,ceo}` (:44/:76), `SalesBoardService.MY_WORK_ROLES` (:32/:168) and `TEAM_ROLES` (:33/:177) identical. Frontend `canViewVisits`/`canViewTeamVisits` (`routes.js`) match exactly; route guard `permissions.js` `/visits`, nav `AppShell.jsx` same predicate, page branch on `canViewTeamVisits`. Grant holders (qc+canCreateQuotation), division managers mapped to `employee`, account, import, hr: refused by server, guard and menu alike. Frontend never more permissive.
- Lock: sales/sales_manager/ceo/hr are lock-exempt; hr refused by the guard; locked roles refused twice. Same treatment as `/tickets`; `/visits` not on the allowlist.
- Week range always 7 days (< 31-day cap); query keys include from/to, so no stale cross-week data.
- **Authz verified on mocks/unit only** — no real-backend run; no backend change in the slice.

## Regressions (attack 8)
- `MyWork.jsx`: all hooks remain unconditional at top level; only JSX is branched. Desktop path renders the identical subtree (+ the `isPhone`-only back button). No query added or duplicated (VisitsPage `thisWeekQuery`/`weekQuery` share a key at offset 0). Existing MyWork tests run desktop (jsdom has no matchMedia → false).
- `WeekNav`/`CallModal`: export only, no behaviour change. `visitWeek.js`: additive (plus a `={` spacing nit).
- AppShell: one new item after `/quotations` for sales/sales_manager/ceo; other roles' lists unchanged (test asserts the predecessor is `/quotations`). Drawing's NAV gives managers no visits item; the brief overrides it.
- `route-coverage.spec.js`: one line added, nothing removed. No existing assertion deleted or weakened (only diff `-` line is an import list reformat).
- `.design/` is an existing convention (11 sibling folders on base); the new IA file fits it.

## Fidelity (attack 7)
Matches: date line, title, นัดถัดไป · HH:MM card with who/what, one large บันทึกเข้าพบ, โทรแล้ว/ทำใบเสนอราคา, ควรตามต่อ (n) with day chips, เงินเข้าเดือนนี้ with amount / จากที่คาด / รอบถัดไป. Deviations: "ส่งตัวอย่าง" absent (justified: no activity kind); "รอบถัดไป ถึง 13 พ.ย." vs drawing's "รอบถัดไป 13 พ.ย." (minor); extra way-out row (required by brief); follow-up and next-visit "ตามต่อ" chips not built (endpoint lacks follow-up date — verified claim plausible, not re-read in Java). Team grid reused as-is.

## Accessibility (attack 11)
Tiles 52px, primary 56px, list rows `min-h-11`, Button base `mobile:min-h-[44px]`, WeekNav sm buttons inherit 44px on mobile. One บันทึกเข้าพบ in phone mode (test + M12). Empty-state "นัดเข้าพบ" button vs "นัดเข้าพบทั้งสัปดาห์" link: distinct names. Safe-area padding on a non-fixed section is harmless but does nothing useful now no control is fixed. No new animation. Real layout not re-verified in a browser (not run by me).

## Mutations (37 + 2 re-run; all restored)
Killed: M1 guard removed; M2 +account; M2b +hr; M3 nav to all; M4 swap views; M4b sales gets team; M5 Sunday start; M5b 8-day range; M5c log dialog from shown week; M6a overdue included; M6b today skipped; M6c DONE/CANCELLED included; M6d untimed first; M6e no sort; M6f isToday always; M6g last instead of first; M7 quote always; M7b never; M7c wrong href; M8 phone at desktop (38 red); M9 remove ดูหน้าเต็ม; M9b remove กลับไปหน้าวันนี้; M10 cash when absent; M10b past weeks; M10c zero weeks; M11 wrong deal logged; M11b call with no deals; M11c picker drops others; M12 second log button; M13 safe-area; M15 empty-state plan dead; M16 follow-up link; M17 /visits link; M18 cancelled in planned; M19 rep fires extra query.
**Survived:** M3b nav ignores `SALES_ENABLED` (same gap as every other sales item; low); M6h same-time visits ordered by id desc (tie-break untested).
Not mutated: nav order (read: asserted at `AppShell.test.jsx:114`).

## Proven vs suspected
Proven by run: overdue/carried-over loss, failure hides log button, rotation drops call dialog, cash cases, all mutations. Read-only: permission parity, hooks/regression, wording in dialogs, worklist hidden. Unverified: real-backend authz, real-device layout/rotation.
