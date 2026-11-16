# Task 2 report — deal detail page redesign (slice B)

**Status: DONE_WITH_CONCERNS** (concerns listed under "Attack first").

- Worktree: `C:\Users\user\Documents\GitHub\GL-R-ERP\.claude\worktrees\deal-page-redesign`
- Branch: `feat/deal-page-redesign`, cut from `origin/develop` at `997c7e4d` (fetched first).
- **Nothing committed and nothing staged.** One mistake to disclose: while deleting four dead files I ran
  `git rm --cached`, which staged their removal; I immediately ran `git restore --staged` on exactly those four paths,
  then deleted them from the working tree with plain `rm`. `git diff --cached` is empty (verified). No stash, reset,
  push or commit.
- Frontend only. No backend, schema, API-contract or permission change. `hrApi.js` / `mockApi.js` untouched. Maven not run.

## What changed (files)

New:
- `frontend/src/features/tickets/dealSteps.js` (+ test) — `REP_STEPS` (S1,S2,S3,S5,S6,S7,S8,S9, drawn labels, mover
  `sales` for S1–S2 / `auto` from S3 per spec §7.1 and the drawing) and `repStepIndex(catalog, stage)` reading the
  **served catalog's `repCode`** (DealStageMetaController → DealStage.REP_CODE). No local stage→S table.
- `nextStep.js` (+ test) — the ขั้นต่อไป table: `nextStepFor({salesStage, lifecycle})` → `{kind,title,hint,tab}`;
  kinds step/done/resume/reopen/closed/none. It names the step only; it never decides who may act.
- `dealTimeline.js` (+ test) — `TIMELINE_FILTERS`, `timelineRowMatches`, `latestPricedQuotation`.
- `DealHeader.jsx` (replaces DealStateHeader on this page; keeps the sticky/condense contract and testids),
  `DealStepper.jsx`, `NextStepCard.jsx`, `DealQuickActions.jsx`, `DealInfoCard.jsx` (+ test, replaces
  DealTrackingPanel and the วันสำคัญ/ผู้เกี่ยวข้อง blocks), `DealQuietLinks.jsx`, `DealItemsPanel.jsx` (the items table +
  edit mode moved **verbatim** out of the page with a marker-checked Node script; only `doAction(`→`saveItems(` and one
  ดีล→งาน string changed), `DealLatestQuotationItems.jsx`, `DealTabBodies.jsx` (five layout-only tab bodies).
- `salesBoard/CallModal.jsx` — the m-call dialog extracted from FollowUpList (not duplicated); subtitle omits
  "เงียบ N วัน" when the caller has no `daysQuiet` (the deal page).
- `TicketDetailPage.redesign.test.jsx` — the page-level redesign tests (53).

Modified:
- `TicketDetailPage.jsx` — 2628 → 2155 lines. New header/card wiring, five tab bodies, quiet links, danger zone
  removed, wording, `doAction` now resolves true/false (DealItemsPanel closes edit mode on success), jump targets
  remapped (`runOnTab('items'|'documents')`→`quote`, `fulfilment`→`ship`).
- `ticketDetailTabs.js` — five tabs `overview, quote, money, ship, files` (labels ภาพรวม · ใบเสนอราคา · เงิน · ส่งของ ·
  ไฟล์), same gates as the tabs they absorbed; `LEGACY_TICKET_DETAIL_TAB_IDS` maps deal/history→overview,
  items→quote, documents→files, fulfilment→ship (money unchanged); `resolveTicketDetailTab` applies the map.
- `DealStagePanel.jsx` — the "สถานะดีล (Pipeline)" Panel shell, numbered pip, PhaseSummary-on-default and the
  paused/lost banners are gone; it is now a slim always-mounted strip (ถัดไป line, guided advance, tender select,
  docActions, route line + ดูขั้นตอนทั้งหมด disclosure that now also holds stage no./headline/owner/phase summary) and
  still owns every modal. Ref gains `openResume()` and `reopen()` (same gates the banners had). `data-stage-no`
  attribute added for the e2e suite.
- `DealHistoryPanel.jsx` — title ประวัติ, `data-testid="deal-timeline"`, filters ทั้งหมด/เข้าพบ/เอกสาร, composer
  (comment + any-kind activity form, each with its old gate) behind "+ บันทึก"; event labels ดีล→งาน.
- `DealDocumentRegister.jsx` — `part` prop ('quotations' → "ใบเสนอราคาของงานนี้" card with `quotationsAction`;
  'files' → everything else; default 'all' unchanged). Each family keeps its own gate; empty-state wording ดีล→งาน.
- `DealPaymentSection.jsx` — KPI tiles มูลค่างาน / รับแล้ว (with the paid bar) / คงค้าง, then รับแล้ว (same timeline
  rows incl. the Express/บันทึกเอง chip) beside เอกสาร (same three steps + stage/overdue badges). Same data/controls.
- `DealFulfilmentPanel.jsx` — delivery list rendered as the table รายการ/สั่ง/ส่งแล้ว/เหลือ/ที่มา (reserve-stock checkbox
  kept in the ที่มา cell, same gate); three empty/loading strings ดีล→งาน.
- `ImportRequestFactoryCard.jsx` — "ดูอย่างเดียว" chip on an ISSUED (read-only) tracker; the hint already existed.
  Shared with ImportDealPage (import's own page), where the tracker is also `editable={false}`, so the chip is
  truthful there too. One draft-hint string ดีล→งาน (also shown on ImportDealPage).
- `stageMeta.js` — AUTO_STAGE_HINT "ของดีลนี้"→"ของงานนี้" (only DealStagePanel reads it).
- `DealPartiesPanel.jsx`, `DealQuotationPanel.jsx`, `PricingRequestPanel.jsx` — visible strings ดีล→งาน and stale tab
  names (แท็บ "การเงิน"→"เงิน", "ทะเบียนเอกสารของแท็บนี้"→แท็บ "ไฟล์", แท็บสินค้าและราคา→ใบเสนอราคา).
- `salesBoard/FollowUpList.jsx` — imports CallModal instead of its private copy.
- `e2e-real/write-quote-stage-gates.spec.js` — `stageIs` reads `data-stage-no`; tab clicks /ประวัติ/→/ภาพรวม/,
  /เอกสาร/→/ใบเสนอราคา/. **Not run** (needs the real backend; Maven forbidden here).

Deleted (dead after the change): `DealStateHeader.jsx` + test (the five chips are gone by design), `DealTrackingPanel.jsx`
+ test (merged into DealInfoCard; its two tests ported to `DealInfoCard.test.jsx`).

## Action inventory — before → after (visibility condition unchanged in every row)

| Action | Condition (unchanged) | Before | After |
|---|---|---|---|
| Primary workflow button (create_pcr, เสนอราคา<party>, issue/confirm/revise quotation, deposit notice, remaining invoice, จัดซื้อสินค้า, IR, record outcome, เลื่อนไป, jump/navigate actions) | workState + OWNER_ONLY + server actions, unchanged code | sticky header action bar | ขั้นต่อไป card (same element; condensed header row while scrolled) |
| ลูกค้ายืนยัน / ยืนยันชำระครบ / ยืนยันพร้อมปิดงาน / ตรวจสอบและปิดงาน | can.* (server actions) | header action bar | ขั้นต่อไป card |
| บันทึกผลประมูล | canEditParties && ACTIVE && bidding | header action bar | ขั้นต่อไป card |
| ⋯ เลื่อนไป / แก้ไขสถานะ… / พัก dormant (ACTIVE) / record outcome / แก้ใบเสนอราคา / ยืนยันคำสั่งซื้อ (direct) / ขอแก้ไข | unchanged | header ⋯ | header ⋯ |
| พักดีลไว้ (hold) | ACTIVE && PLACE_ON_HOLD | header ⋯ | quiet link "พักงาน" (ภาพรวม foot), same dialog |
| เสียงาน | ACTIVE && MARK_LOST && !lost && !CLOSED_PAID | red danger zone (every tab) | quiet link "ปิดงาน (เสียงาน)", same MarkLostModal |
| ยกเลิก | CANCEL && !terminal && isOwner | red danger zone | quiet link "ยกเลิกงาน", same CancelDealModal |
| ดำเนินการต่อ | ON_HOLD/DORMANT && RESUME | DealStagePanel paused banner | ขั้นต่อไป card (opens the same note dialog via ref) |
| พัก dormant on a paused deal | ON_HOLD && MARK_DORMANT | paused banner | header ⋯ |
| เปิดดีลอีกครั้ง | lost && REOPEN | lost banner | ขั้นต่อไป card "เปิดงานอีกครั้ง" (same onReopen) |
| Guided advance opener, ประมูล select, docActions (legacy QT PDF, remaining invoice) | unchanged | DealStagePanel body | DealStagePanel strip under the header |
| ดูขั้นตอนทั้งหมด + แก้ช่องทาง remedy | unchanged | DealStagePanel header | DealStagePanel strip |
| Edit tracking (win%, follow-up, designer, owner) | canViewDealTracking && (owner/sm/ceo) | DealTrackingPanel "แก้ไขข้อมูลติดตาม" (ดีล tab) | ข้อมูลงาน "แก้ไข" (ภาพรวม) |
| Parties add/edit/remove/เสนอราคา | canViewDealTracking; canEditParties && ACTIVE | ดีล tab | ภาพรวม right column |
| Comment | !terminal | ประวัติ tab, always open | ภาพรวม timeline, "+ บันทึก" |
| Add activity (any kind, any date) | canViewDealTracking && (owner/sm/ceo) | ประวัติ tab, always open | ภาพรวม timeline, "+ บันทึก" |
| Edit items | can.editItems | สินค้าและราคา | ใบเสนอราคา (DealItemsPanel) |
| คำขอราคา panel actions | canViewPricingRequests && sections.pricingRequest | สินค้าและราคา | ใบเสนอราคา |
| DealQuotationPanel (issue/outcome/confirm order) | sections.dealQuotation && canViewPricingRequests | เอกสาร | ใบเสนอราคา |
| Legacy quotation downloads | sections.quotation | เอกสาร | ใบเสนอราคา |
| Register quotation rows + downloads | register's canViewQuotations | เอกสาร | ใบเสนอราคา ("ใบเสนอราคาของงานนี้") |
| Register deposit / remaining invoice / IR / attachment rows | register's own gates | เอกสาร | ไฟล์ |
| Payment steps (create/preview/issue deposit notice, remaining invoice, downloads) | DealPaymentSection gates | การเงิน | เงิน |
| เงินที่คาดว่าจะเข้า | ceo / sales_manager / owner sales | การเงิน | เงิน |
| ยกเลิกการยืนยันปิดงาน | can.revokeCloseConfirm | การเงิน | เงิน |
| Fulfilment chain, IR cards, record/complete delivery, reserve stock, weights | DealFulfilmentPanel gates | จัดซื้อ-ส่งมอบ | ส่งของ |
| Upload / delete attachment | canViewDocumentsTab; canUpload | ประวัติ | ไฟล์ |
| รีเฟรช | always | header | header |
| **New placements of existing capabilities** | | | |
| โทรแล้ว (CALL activity) | = add-activity gate (TicketService#addActivity requireDealOwnership) | — | header |
| นัดเข้าพบ (salesVisits.create on this deal) | add-activity gate && ACTIVE (VisitService#create: requireDealWriteAccess + ACTIVE) | — (only on งานของฉัน) | header |
| + สร้างใบเสนอราคา (link to `/quotations/new?ticket=`) | ACTIVE && canCreateDealQuotation (DealQuotationService.create) && no live คำขอราคา (server 409s otherwise) && request list read | — (old route buttons removed earlier; capability exists via /quotations/new picker) | ใบเสนอราคา card header |

Nothing became unreachable; no control became visible to a role that could not use it (see the per-action tests and
mutation checks below). **Permission aspect: unverified against the Java service** — every check here is mock/fixture
driven (CLAUDE.md). No gate's predicate was changed, only where the control renders.

## Test-first coverage list

Red runs (recorded before implementation; assertion failures, not import errors — stubs exported empty behaviour):
- `dealSteps.test.js`, `nextStep.test.js`, `dealTimeline.test.js`, `ticketDetailTabs.test.js`: **58 failed / 26 passed**
  against stubs (the 26 were structural/vacuous: e.g. "S4 is not a step" on an empty list, "covers all 15 stages").
- `TicketDetailPage.redesign.test.jsx` against the unchanged page: **48 failed / 5 passed** (the 5 were wrong-way-round
  "cannot see" cases that hold trivially on the old page); 1 fixture error fixed (expectedPayments.list shape).
- `DealInfoCard.test.jsx` was written before the component, but its red is the page-level ข้อมูลงาน cases above (not run
  red in isolation). `DealStagePanel` openResume/reopen tests were added after the ref methods (not red-first) — they
  are covered red-first by the page-level resume/reopen tests.

What each new test pins:
- dealSteps: the 8 drawn steps/labels; S4 absent; movers; all 15 stages → correct S step; unknown/empty catalog → -1;
  mapping read from the served catalog (a changed repCode moves the index).
- nextStep: every one of the 15 stages (ACTIVE and lifecycle-absent), CLOSED_PAID done, unknown stage none, each tab is
  one of the five; ON_HOLD/DORMANT resume, CLOSED_LOST reopen, CANCELLED/COMPLETED closed whatever the stage; no ดีล.
- ticketDetailTabs: 5 tabs/order/labels, no helper, default ภาพรวม, visibility per role incl. wrong-way (import no เงิน;
  unknown role no money/ship), each old key → new tab, hidden new tab still falls back.
- dealTimeline: filter set, เข้าพบ (SITE_VISIT, MEETING only), เอกสาร (15 document event kinds, nothing else), unknown
  filter keeps all; latestPricedQuotation priced>newer draft, live fallback, never dead rows.
- Page (redesign file): five tabs in order; each old `?tab=` key (6 cases); no ประวัติ tab; breadcrumb งานทั้งหมด; header
  title/sub; stepper 8 steps, one aria-current, done/todo states, movers; pipeline panel + pip gone; ขั้นต่อไป title and
  exactly one primary; resume opens the existing dialog; no resume without RESUME; reopen calls the API and toasts in
  งาน; no button for lost-without-REOPEN and cancelled; โทรแล้ว logs CALL via the board dialog; นัดเข้าพบ preselects the
  deal and posts the visit; sm/ceo get both; non-owner rep gets neither; paused deal keeps โทรแล้ว not นัดเข้าพบ;
  ข้อมูลงาน + ประวัติ present, old tracking/history panels absent; filters narrow the timeline both ways; composer
  closed until "+ บันทึก"; per relocated action with wrong-way: พักงาน (and none without PLACE_ON_HOLD / on paused),
  ปิดงาน (none without MARK_LOST / at CLOSED_PAID), ยกเลิกงาน (owner-only even with CANCEL for ceo/sm/other rep), no
  danger zone, พัก dormant in ⋯ on paused (none without MARK_DORMANT), any-kind activity under + บันทึก (other rep
  comments only; nothing on cancelled for a non-writer), แก้ไข on ข้อมูลงาน for owner/sm/ceo (not other rep), upload on
  ไฟล์ (not for a non-participant); ใบเสนอราคา order + create link href + latest items from the newest approved (not
  the newer draft) + items + คำขอราคา; create link absent for other rep and with a live request; เงิน KPIs/รับแล้ว with
  Express chip/เอกสาร/expected payments; ส่งของ table headers and two rows (คลัง/นำเข้า); ดูอย่างเดียว chip + hint;
  ไฟล์ without the quotation family; no ดีล on each of the five tabs (dialogs excluded, see allowlist below).
- `DealInfoCard.test.jsx` (5), `DealStagePanel.test.jsx` openResume/reopen (2).

Mutation checks (each guard broken, its test red, file restored byte-identical — `cmp` verified):
M1 ยกเลิกงาน not owner-only; M2 โทรแล้ว for any tracking viewer; M3 resume without RESUME; M4 create link despite a
live request; M5 นัดเข้าพบ on a paused deal; M6 dormant-on-paused without MARK_DORMANT; M7 reopen without REOPEN;
M8 พักงาน on a paused deal. **8/8 red.**

Deliberately not covered: anything permission-shaped against the real Java services (no Maven, mock only); the e2e-real
spec edits (not run); visual fidelity to the drawing (only a mock-mode DOM check at 320px, below); condensed-header
visuals in a real scroller (the existing sentinel test still passes).

## Existing tests adapted (each reason: structure legitimately changed; no assertion weakened to get green)
- `ticketDetailTabs.test.js` — rewritten for the five tabs (old 6-tab expectations no longer describe the code).
- `TicketDetailPage.test.jsx` — tab names (สินค้าและราคา→ใบเสนอราคา, จัดซื้อ-ส่งมอบ→ส่งของ, การเงิน→^เงิน, ดีล→^ภาพรวม,
  เอกสาร→ใบเสนอราคา or ไฟล์ by content, ประวัติ→ภาพรวม/+บันทึก or ไฟล์); h1 is the project/title (drawn) instead of the
  customer; chip tests replaced by header sub-line tests that keep the role wrong-way-rounds (account: value not
  chance; import: neither) and the QA-22 stored-IR read gate (incl. non-owner no request); การชำระเงิน heading/tiles →
  KPI tiles; banner reads moved from ticket-action-bar to next-step-card (one test strengthened the same way); "พัก
  dormant not duplicated" → exactly one copy, in ⋯; the old "no /quotations/new link on เอกสาร" guard → exactly one
  "+ สร้างใบเสนอราคา" for the owner, zero for account and CEO, still no ใบเสนอราคาตรง/helper; DealQuotationPanel copy
  pointers updated; "3 recent comments roll-up is gone" now asserts the comment appears exactly once, in the timeline.
  Removed (removed UI): the header การนำเข้า chip text checks (3 positive cases; the read-gate half kept) and the
  "ยังไม่ได้จัดซื้อ" chip line in one IR test; refresh `mobile:absolute` replaced by a 44px tap-target check.
- `DealPaymentSection.test.jsx` — h2 การชำระเงิน / balance labels → KPI tiles and the two cards; same figures, bar and
  red-only-above-0 assertions.
- `DealStagePanel.test.jsx` — headline/owner/phase-summary assertions now open ดูขั้นตอนทั้งหมด first; headline read via
  `deal-stage-headline` (the open stepper lists every label); the "default view shows one เฟส summary" test now pins
  that the default strip shows none and the disclosure shows exactly one; ON_HOLD wording test now reads the headline.
- `DealHistoryPanel.test.jsx` — event labels งาน; date test opens "+ บันทึก" first.
- `DealDocumentRegister.test.jsx`, `PricingRequestPanel.test.jsx`, `stageMeta.test.js` — ดีล→งาน string literals.
- `ticketItemFields.test.jsx` — the shared-import pin now reads `DealItemsPanel.jsx` (where the editor moved).
- `ticketWorkspaceLayout.test.js` — mobile-fixed action bar source guard now reads `NextStepCard.jsx`.
- Deleted with their components: `DealStateHeader.test.jsx`, `DealTrackingPanel.test.jsx` (latter ported).

## Commands and results
- `npx vitest run` targeted files while iterating (all green at the end).
- Full suite before the last two class-only edits: `npm test` → **302 files, 6536 tests passed**.
- Final, after every edit: `npm run lint` → exit 0; `npm test` → **302 files, 6536 tests passed**, exit 0;
  `npm run build` → built, exit 0. (Log: scratchpad `final.txt`.) Maven not run (instructed).
- Mutation script: 8/8 red, restore verified.
- On-screen (VITE_USE_MOCKS=true, port 5247, server stopped afterwards; **not evidence about permissions**): sales demo
  login, `/tickets/1`: header, stepper, ขั้นต่อไป ("ติดตามผลใบเสนอราคา" + "ติดตามลูกค้า"), timeline, ข้อมูลงาน, quiet
  links render; at 320px no horizontal overflow on any of the five tabs (scrollWidth = clientWidth = 320) after one fix
  (header action cluster now wraps); นัดเข้าพบ opens with the deal preselected. Screenshots timed out in the pane, so
  checks were DOM-based. Remaining sub-44px targets are pre-existing shared ones: the breadcrumb link and
  DealFulfilmentPanel's "+ กำหนดวันที่ต้องการ".

## Drawn elements not built, and why
- **คาดปิด (expected quarter)** in ข้อมูลงาน and the header sub line: TicketSummaryDto has no expectedQuarter (only the
  board endpoint serves it). Left out.
- **Planned visits in the timeline** ("นัดเข้าพบ · นัดไว้"): visits are not served per deal (only own/team lists by date
  range). Only ticked visits (SITE_VISIT activities) appear. Left out.
- **Tab counts** (ใบเสนอราคา 2, ไฟล์ 4): not built — they would change every tab's accessible name; trivial to add.
- **"ใบเสนอราคาของงานนี้" as a five-column table**: kept the register's grouped rows (number, recipient, status, total,
  date, PDF/Excel, version history) rather than a flat table, so version history and downloads are not lost.
- **ส่งของ "สั่ง" units (ตร.ม./ชิ้น)**: plain numbers, as the list it replaced showed them (qty has no unit per item here).
- **ที่มา for a not-yet-decided source**: shows นำเข้า whenever not reserved from stock (the two sources are stock and
  import); before the procurement decision this is an assumption — see Attack first.
- Drawn deal-a/deal-b sample data (e.g. "ส่งถึงผู้ออกแบบ 3 พ.ย.") is not reproduced; the hint is the stage-level
  sentence from nextStep.js plus the page's existing work-state line.

## Judgment calls
1. ขั้นต่อไป button = the page's existing resolved primary (unchanged logic), else resume/reopen per lifecycle, else a
   secondary "ไปที่<tab>" switch to where the stage's work lives. The pure table names steps; it never picks an action
   (a stage-only action table would offer actions the server does not allow for this viewer).
2. Sticky/condensing header kept (existing contract and tests); DealStagePanel stays outside the sticky chrome because
   its modals are not portalled (inside the collapsing region they would be clipped/hidden).
3. Mover labels: S1–S2 เซลส์ย้ายเอง, S3+ อัตโนมัติ (drawing + spec §7.1), although the catalog flags AWAITING_BUYER,
   DELIVERY_SCHEDULING, DELIVERED as not auto.
4. Old `?tab=documents` → ไฟล์ (audit: "แค่ย้ายเข้าแท็บ ไฟล์"); its quotation content is on ใบเสนอราคา.
5. "+ บันทึก" opens the old composer (comment + any-kind activity) so EMAIL/MESSAGE/backdated logging stays reachable.
6. เข้าพบ filter = SITE_VISIT + MEETING; เอกสาร filter = 15 document event kinds (list in dealTimeline.js).
7. h1 = project name (else title, else customer), per the drawing; customer moves to the sub line.
8. Wording: page chrome, new components, toasts, and visible strings of panels rendered on this page → งาน; dialogs,
   server messages, the stored delivery note, and the entry-channel enum label "ผู้ออกแบบนำดีล" untouched (the latter is
   explicitly allowlisted in the wording test). Some changed strings are also visible elsewhere: EVENT_KIND_LABEL
   (DealPaymentSection), ImportRequestFactoryCard's draft hint and chip (ImportDealPage).
9. Removed DealStateHeader's chips' extra readouts (PCR status, payment stage, import progress) from the header per
   the drawing; they remain on their tabs.

## Attack first
1. **"+ สร้างใบเสนอราคา" re-adds a deal-page entry to the direct quotation editor** that an earlier change had removed
   (the old guard test asserted no `/quotations/new` link on the deal page). The brief and drawing ask for it; gate is
   canCreateDealQuotation + ACTIVE + no live คำขอราคา. Owner should confirm this is wanted and that the editor handles
   `?ticket=` without `recipient`.
2. ที่มา column labels un-reserved items "นำเข้า" even before the procurement source is decided.
3. Permission claims are mock-only; e2e-real edits not run.
4. DealItemsPanel no longer has its edit mode reset by *other* page actions' success (only by its own save).
5. The tab label "เงิน" and new keys (`overview/quote/ship/files`) change URLs written by the page (`?tab=quote`).
