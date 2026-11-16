# Adversarial review: slice A, commit 47b6ebed (feat/all-deals-board)

**Verdict: FIX BEFORE MERGE.** The authorization side holds up. I found no permission defect, and every gate mutation I tried was killed. There are four things to fix or rule on before merge: a default filter that can show an empty board, a release notice rewritten to describe a UI production does not have, a deploy-order trap, and three behaviours with no test.

Worktree: `.claude/worktrees/agent-aecc97000f10a7044`, detached at 47b6ebed. All scratch changes are reverted, and `git status --short` is empty at the end. The implementer's report was present and read; I treated it as claims to check, not as facts.

## Authorization (proven)

### Gate placement
- `SalesBoardService.teamBoard` (SalesBoardService.java:190-195) checks `TEAM_ROLES` before any repository call. It is the same gate `teamOverview` uses.
- `SecurityConfig` only requires authentication; role checks live in the services. Nothing in this slice changes that.

### Roles probed
- sales, account, import, hr, employee, warehouse, qc and division_manager are all refused with 403. Unit and real-Postgres integration tests both cover this.
- A sales user with a quotation grant is still role `sales`, and the gate checks role only, so they are refused.
- A null role makes `Set.of(..).contains(null)` throw NPE, so the caller gets a 500 rather than a 403, but still no rows. `teamOverview` has the same behaviour already. Low.
- An inactive employee with a live session is handled by the session layer, which is unchanged and shared with every endpoint. Not slice-specific; not tested here.

### Fields exposed
`BoardDeal` carries value, designer, quarter, win %, last activity note and owner. It has no cost, margin, commission or customer contact fields.
- The same `BoardDeal` shape already reaches these roles through `team-overview.stale` (up to 20 rows) and `my-work`.
- Managers and the CEO already read every ticket unfiltered (`TicketService` sets no createdBy filter for non-sales roles).
- I found no new field exposure (read code).

### Mock
`mockApi.salesBoard.teamBoard` uses the same `['sales_manager','ceo']` gate, so it is not more permissive than Java.

### Backend mutations
Run against real Postgres (`glr_it_wn`, `-Dtest.fork.count=1`), on `SalesBoardServiceTest` and `TeamBoardScopeIntegrationTest`.

| Mutation | Result |
|---|---|
| Baseline | green |
| M1: role check removed | KILLED (8 unit tests, 2 integration tests) |
| M2: allow `sales` | KILLED (unit, plus integration `aSalesRepIsRefused…`) |
| M3: check after the repository read | KILLED by the unit test's `verifyNoInteractions` only. The integration test cannot see this ordering, which is expected. |
| M4: empty 200 instead of 403 | KILLED (unit, plus both integration refusal tests) |
| M5: allow `division_manager` | KILLED (unit, plus `everyNonManagerRoleIsRefused…`) |
| M6: sales gets their own rows from this endpoint | KILLED |

Every backend mutation was killed. Logs are in `scratchpad/adv1/be-*.log`.

## Findings

### MEDIUM 1: the default "ไตรมาสนี้" chip can hide every open deal, and the subtitle then says "0 งานที่เปิดอยู่" (proven)
- **Cause:** `defaultBoardFilter` turns on ไตรมาสนี้ when at least half the deals carry *any* expectedQuarter. The filter itself then keeps only the *current* quarter (AllDealsPage.jsx:189-193; boardFormat.js:79-83).
- **Probe:** a manager with 3 open deals, quarters 2026-Q2, 2026-Q3 and null.
- **Result:** the chip is pressed, 0 cards render, and the subtitle reads "0 งานที่เปิดอยู่ · มูลค่ารวม ฿0".
- **Why it matters here:** stale or future quarters are normal on a whole-team board.
- **The drawing cuts the other way:** its ไตรมาสนี้-on board shows Q4/70, Q1/70 and Q2/70 cards, none of them the current quarter. Under this implementation that exact data would render empty.
- **Fix:** base the default on current-quarter deals. Separately, decide whether the subtitle's "งานที่เปิดอยู่" should count the filtered subset or all open deals; it currently counts the subset. This needs an owner ruling.

### MEDIUM 2: the 2026-11-16 release notice now describes a UI production does not have (proven)
- **What changed:** `releases/2026-11-16.js` `deal-list` and `deal-page*` entries now say "งานขาย › งานทั้งหมด", and tell the reader to press "ตาราง".
- **Production state:** `origin/main` (b06057ee) already contains this release with "รายการดีล", built from diff 3ccf9c5f..fdb8b35c. The file's own header says it describes only that diff.
- **Screenshot conflict:** `public/whats-new/2026-11-16/deal-list-1.png` still shows the "รายการดีล" page and the "สร้างดีลใหม่" button, contradicting the edited step text.
- **Possible notice drift:** if the release was already published or emailed, the notice titles stored with it (the publish payload's `changes[].title`) now differ from the page. I could not verify this; it needs a prod DB read.
- **Test enforcing the rewrite:** the new test "no notice names the retired รายการดีล" forces past releases to be rewritten.
- **Fix:** describe this UI in a new release entry or module, and leave 2026-11-16 as shipped.

### MEDIUM 3: deploy-order trap for managers and the CEO (proven by probe; risk inferred)
- **Board default:** managers and the CEO now open on the board by default, which calls `/api/sales/team-board`.
- **Old backend:** against a backend without that endpoint, the page shows "โหลดงานไม่สำเร็จ" plus a retry. The subtitle stays stuck on "กำลังโหลดงาน…". The "ตาราง" toggle still works, so it fails soft but broken.
- **Deploy order:** the frontend deploys on merge to main; the backend needs an image. The backend image must be live first.

### MEDIUM 4: three page behaviours have no test (proven by mutation)
- **F13b, the role gate on the board:** removing `if (!BOARD_ROLES.has(role)) return <TicketListPage…>` SURVIVES. No test renders AllDealsPage as account, import or another non-board role. Without the gate, those roles would get a board that calls myWork, gets a 403 and shows an error.
- **F17, the board error state:** deleting it SURVIVES. No test covers a failed board load, which is exactly the old-backend case in Medium 3.
- **F6d, the quiet-chip count:** counting quiet deals over all deals instead of the searched ones SURVIVES. The chip count can disagree with the cards shown after a search.

### LOW
- **F8c:** the subtitle dropping other-currency totals SURVIVES. No non-THB case on the page.
- **F18:** the "ไม่พบงานที่ตรงกับ…" empty-search message can be deleted with no test noticing.
- **Closed-deal counts use a capped list:** the counts come from `/api/tickets` (default and max 500 rows, ORDER BY created_at DESC), while the board is uncapped.
  - Above 500 tickets the counts undercount. They do match the equally capped table.
  - The board also fetches up to 500 ticket rows just to compute 4 counts.
  - Suspected only: I did not check the real ticket volume (read code).
- **A rep's board and table differ:** the board uses my-work (`created_by` only), while the table uses created_by OR keyed_by. A deal the rep keyed for someone else is on the table but not the board (read code).
- **Owner tag is first name only:** two reps with the same first name are indistinguishable except via the title tooltip.
- **Null title and customer:** the card link has an empty accessible name. This is pre-existing DealBoard behaviour, not from this slice.
- **Table-view header on phone for managers and the CEO:** a 3-column grid with 2 children leaves an empty cell. Suspected; I did not render it.
- **User switch without remount:** `stored` view state is read once per mount, so switching user without a remount would keep user A's choice. Suspected; normally logout unmounts the page.

## Fidelity to `a-board`

**Matches:**
- Title.
- Subtitle pattern.
- ตาราง (list icon) and งานใหม่ buttons.
- Search placeholder.
- The three chips, with ไตรมาสนี้ first.
- The "พักไว้ · เสียงาน · ยกเลิก ›" hint.
- The five column names and S-codes.
- The อัปเดตอัตโนมัติ tag on S3–5, S6 and S7–9.
- Card layout: designer · Qn/yy, plus the owner tag for managers only, money, and a % chip.

**Differences:**

| Difference | Judgement |
|---|---|
| Totals in full baht, not "฿18.6 ล้าน" | Acceptable, as admitted |
| Quiet chip reads "เงียบ 19 วัน" and also shows % (the drawing shows only "19 วัน") | Acceptable |
| No QT number or status chips (นัดรับ, ได้มัดจำ, ครบกำหนด); no S5/S8 sub-lines; no ผู้รับเหมา/ผู้ซื้อ line | Gap, but data-limited: `BoardDeal` lacks the fields. Should be tracked. |
| "พักยาว n" appears when there are DORMANT deals | Acceptable addition |
| No งานใหม่ for managers or the CEO, although the drawing (with owner tags) shows it | Acceptable: `canCreateTickets` is sales-only and should not change as a side effect |
| Nav icon is still briefcase, where the drawing uses list; nav order not adopted | Minor, as admitted |
| ไตรมาสนี้ semantics conflict with the drawing's own sample data | Owner ruling needed (Medium 1) |

## Wording
- **Page and menu:** no "ดีล" remains in AllDealsPage, the menu entry, the board, or MyWork's touched strings.
- **Table view under งานทั้งหมด:** about 24 "ดีล" strings remain, as admitted. Examples:
  - the search label "ค้นหาดีล";
  - the "n ดีลเงียบ" badge;
  - empty states;
  - the toast "สร้างดีลเรียบร้อย", which is inconsistent with the board's "สร้างงานเรียบร้อย";
  - the column header "ดีล / ลูกค้า / โครงการ".
- **Identifiers:** "งาน" was not applied to any identifier, route, API field, `life` value or document code. Good.

## Regressions
- **TicketListPage behind the toggle:** behaviour unchanged. Test edits are renames only; I found no assertion deleted. One weakening: `/สร้างดีลใหม่/` became `/งานใหม่/`, a broader regex, but its absence checks still hold.
- **Dashboard (MyWork, DealBoard):** existing tests are green. DealBoard's `showOwner` defaults to false.
- **`smoke.spec.js`:** it now goes to `/tickets?view=table` and still pins /api/tickets → DEMO-TKT- in the DOM, so it is equivalent to before. Not run (real e2e). The board path itself has no real-browser coverage beyond route-coverage's page-load sweep.
- **`docs/api` diffs:** only `+GET /api/sales/team-board`. Consistent.
- **Touched frontend tests:** 21 files, 312 tests, all green at baseline.

## Frontend mutations (`scratchpad/adv1/fe-mut.txt`)
- **Killed:**
  - F1 (default view)
  - F2, F2b, F2c (param precedence)
  - F3, F3b, F3c (per-user storage)
  - F4, F4b, F4c (sort)
  - F5a–d (search fields and case)
  - F6a–c (chips)
  - F7a–c (owner tag)
  - F8a, F8b (subtitle)
  - F9 (quiet chip rule)
  - F10a–d (closed-link targets and counts)
  - F12, F13 (role data source)
  - F14 (mock gate)
  - F15 (card overlay; killed only by a class-name assertion)
  - F16 (tap-target class; killed only by a class-name assertion)
  - F19 (wording)
- **Survived:** F6d, F8c, F13b, F17, F18.
- **Class-name-only assertions:** the whole-card tap target and the 44px tap targets are checked only by class names. The real layout was verified only by the implementer's mock-mode browser run, which I did not re-run.

## Not verified
- The real-browser e2e run.
- Phone layout at 320–375px (I did not render it).
- Prod release-notice publish state.
- Real ticket volume against the 500-row cap.
