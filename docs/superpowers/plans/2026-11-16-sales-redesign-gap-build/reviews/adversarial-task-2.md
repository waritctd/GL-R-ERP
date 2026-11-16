# Adversarial review — task 2, deal page redesign (`f29cd4cd` on `feat/deal-page-redesign`, base `997c7e4d`)

## Verdict: **FIX BEFORE MERGE**

Nothing here hands a role a server capability it lacked: every gate I traced is either the old predicate moved verbatim or a
new placement of a capability the server already grants. But several defects are real and some guards cannot fail:

- the restored "+ สร้างใบเสนอราคา" link drops a guard the previous version of the same link had (live direct quotation),
  and is offered on already-ordered deals — a button that leads the rep into an editor the server 409s;
- the ⋯ menu moved from the bottom action bar to the top of the header but still opens **upwards** on a phone (with 5+
  items, the first ones go off the top of the screen);
- mobile bottom padding no longer matches the fixed action bar that now appears for resume / reopen / "ไปที่…";
- lost reason and lost date are no longer shown anywhere on the page;
- unsaved item edits are now silently discarded by any tab switch;
- the stepper labels S5/S8/S9 "อัตโนมัติ" against the backend's own explicit statement (drawing-faithful, owner call);
- several of the new guards survive mutation (list in §Mutations).

Permission aspect: **unverified against the Java services** — everything below is code reading plus mock/jsdom runs. Where I
cite the server, I read `TicketService` / `VisitService` / `DealQuotationService` source; I did not run Maven.

---

## Findings

### HIGH

**H1. "+ สร้างใบเสนอราคา" is offered while the deal already has a live direct quotation, and on ordered deals — the guard the
previous version of this exact link had was dropped.**
- State/role: owning sales rep (or sales_manager, or a `canCreateQuotation` grant holder) on an ACTIVE deal whose
  `summary.liveDirectQuotation` is DRAFT/PENDING_APPROVAL/APPROVED; also on an ACTIVE deal past the order
  (e.g. DEPOSIT_RECEIVED, `paymentStatus` set) with no live pricing request.
- Where: `TicketDetailPage.jsx` ~1388 (`canStartQuotation = isActiveLifecycle && canCreateDealQuotation(user, summary) &&
  !requestListUnread && !hasLivePricingRequest`).
- History: the link was first a "ใบเสนอราคาตรง" button (Slice 2 §D). Its gate at `5560009a^` was
  `canCreateDealQuotation && ACTIVE && !isLiveDirectQuotation(summary.liveDirectQuotation) && !pricingRequestsPending &&
  !hasLivePricingRequest`, with the comment "a second one would be refused anyway (N6)". Commit `5560009a` ("remove
  deal-page quotation panel", PR A) then removed the panel and its route buttons entirely and added the guard test "no
  `a[href*="/quotations/new"]` on the deal page" for owner / account / CEO, reasoning: quotations stay listed in the
  register, and "the one way to start a pricing request is the sticky CTA" (direct quotations to a party are reached from
  the primary button's route choice, `/quotations/new?ticket=&recipient=`). Earlier still, GLA-136 (owner ruling
  2026-09-30) had removed the create link from the panel header for the same "one pricing route per deal" reason.
- The new link restores the entry but **without** `!isLiveDirectQuotation`. `DealQuotationService.create` →
  `DirectQuotationLocks.requireNoLiveDirectQuotationForCreate` 409s on any live direct quotation (and, once
  `hasPromotionFromQuotationEvent`, on every live one). It has no lifecycle-stage/ordered check of its own, so on an ordered
  deal whose order came from a direct quotation, the live APPROVED row is what blocks — after the rep has filled the editor.
- Each state: (a) no pricing request, no live direct QT, pre-order → works (editor preselects recipient from stage/channel or
  asks); (b) pending/any live pricing request → hidden (correct); (c) live direct QT → **shown, dead end (409 on save)**;
  (d) already-ordered deal → **shown** (probe P2), dead end or a stray post-order quotation; (e) read-only viewer
  (other rep, account, CEO without grant) → hidden (correct). Also: `requestListUnread` guard and the ACTIVE guard are
  untested (M01, M02 survive).
- The redesign test's own fixture asserts the link visible next to a live DRAFT direct quotation (`draft` row 214 in
  `TicketDetailPage.redesign.test.jsx` "leads with ใบเสนอราคาของงานนี้…"), i.e. the test encodes the dead-end state.
- Established: probe test (scratch, deleted) — P1 `liveDirectQuotation: {docStatus:'DRAFT'}` → 1 link; P2 DEPOSIT_RECEIVED +
  `paymentStatus: 'DEPOSIT_PAID'` → 1 link. Server behaviour: read code, not run.
- Fix: restore `!isLiveDirectQuotation(summary.liveDirectQuotation)` and an order-confirmed guard
  (`ps == null` / `!orderConfirmationOf(events)`), and get an explicit owner yes on re-adding a stage-agnostic direct
  entry beside the primary's route choice.

### MEDIUM

**M0 (was H2 before the browser check). On a phone the ⋯ menu opens upward from the top of the page.**
- `DealHeader.jsx` ~55: `<OverflowMenu … mobilePlacement="up">`. In the old page the trigger lived in the fixed **bottom**
  action bar, where "up" was right. It now sits in the identity row at the top of the (static on mobile) header.
  `OverflowMenu.computeMenuPosition` with `flipUp` sets `bottom: viewportHeight - rect.top + 4` on a `position: fixed`
  portal: the menu grows upward from a trigger ~100–150px below the top of the viewport, so a menu of 3+ items extends above
  the viewport. `max-h-[calc(100dvh-7rem)]` caps the height, not the top edge.
- What it hides on a phone: เลื่อนไป (the rep's only manual S1→S2 advance), แก้ไขสถานะ…, พัก dormant (incl. the paused-deal
  copy that replaced the banner button), บันทึกผลใบเสนอราคา, แก้ใบเสนอราคา, ยืนยันคำสั่งซื้อ (direct), ขอแก้ไข.
- Established: read code (`OverflowMenu.jsx` 14–22, 52), then measured in a browser (mock mode, 375×700, sales demo,
  `/tickets/1`): with 3 items the menu spans y=56–198, sitting over the app top bar, bottom edge just above the trigger.
  About 200px is available above the trigger at scroll-top, so 4 items fit and 5 or more do not. Example: an owner at
  QUOTE_OWNER with แก้ไขสถานะ, พัก dormant, บันทึกผล, แก้ใบเสนอราคา, ขอแก้ไข has 5 items, and the first is pushed off the top.
  This is conditional, so it is MEDIUM, not HIGH. Fix: `mobilePlacement="down"` now that the trigger is at the top.

**M1. Mobile: the fixed bottom bar can cover the end of the page.** `TicketDetailPage.jsx` ~1577 adds `mobile:pb-36` only when
`bannerText || stickyPrimaryAction || overflowItems.length > 0`. The fixed bar (`NextStepCard.jsx`, `ticket-action-bar`
`mobile:fixed … bottom-0`) now renders for `cardAction`, which also covers resume / reopen / "ไปที่<tab>". On a DORMANT deal
(server gives RESUME only → overflow empty, no banner, no sticky primary) or a lost deal with REOPEN, the bar is fixed over
the last ~80px of content with no padding — on ภาพรวม that is where the quiet links / parties sit. Read code; old page's
padding condition was exactly the bar's render condition.

**M2. Lost reason and lost date are gone.** The old lost banner showed "เสียงาน · <reason> / ปิดเมื่อ <date> — … ขั้นที่ N
ยังอยู่". Nothing renders `summary.lostReason` / `lostAt` now (grep: only DealStagePanel comment and MarkLostModal). Probe P3
(lost, PRICE, 2026-11-01): page text has neither. The card says only "เสียงานแล้ว". The paused banner's
"ขั้นเดิมยังอยู่ที่ N. <label>" is partly covered by the stepper (S-code only, not the 15-stage label).

**M3. Unsaved item edits are discarded by any tab switch.** Edit-mode state moved from the page into `DealItemsPanel`, which is
mounted inside the ใบเสนอราคา `TabPanel`; `Tabs.jsx` `TabPanel` returns null when inactive. Old page kept
`editMode/editDraft/editNote` in page state, so a tab round-trip (or a primary button's `runOnTab`, or the card's
"ไปที่…") preserved the draft. Now it is silently lost. (The report admits only the reverse: edit mode no longer closes on
other actions.) Read code.

**M4. Stepper says "อัตโนมัติ" for steps the backend says must not be presented as automatic.** `dealSteps.js` marks S3–S9
`auto`. `DealStage.java` 165–169: "DELIVERY_SCHEDULING and DELIVERED are deliberately absent [from AUTO_ADVANCED] … the rep
also reaches both by hand … presenting them as 'the system does this for you' would be wrong." AWAITING_BUYER (S5) has no
auto-advance path at all (grep: no `autoAdvanceStage(…AWAITING_BUYER)`), it is reached only by the rep's เลื่อนไป. The
drawing does label S3+ อัตโนมัติ (renderer: `i>=2 ? อัตโนมัติ : เซลส์ย้ายเอง`), so this is drawing-faithful — but the
drawing contradicts the system; owner decision needed. Catalog `auto` flags were available and ignored (report judgment 3).

**M5. Wording sweep incomplete — "ดีล" still on this page's own surfaces.** The wording test renders one fixture with nothing
opened, so it misses: cancel success toast `'ยกเลิกดีลแล้ว'` (`TicketDetailPage.jsx` ~2073; the brief names toasts
explicitly); `DealStageStepper` "เส้นทางของดีลนี้ (N ขั้น)", "ดีลอยู่ขั้นนี้", "ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน" and
`EntryChannelFix` "แก้ช่องทางดีล" (all behind ดูขั้นตอนทั้งหมด, not dialogs); `DealFulfilmentPanel` ~1156 weight-section text
"…สำหรับดีลนี้เท่านั้น". Dialog strings ("พักดีลไว้" modal title etc.) are exempt per brief. Leaks the other way: the changed
`ImportRequestFactoryCard` FIRST_DRAFT_HINT ("เจ้าของงาน … ทั้งงาน") and the new ดูอย่างเดียว chip also render on import's own
`ImportDealPage`, which did not ask for the rename; `PricingRequestPanel` toast now names "แท็บใบเสนอราคา". No identifier,
route, enum, API value or printed/email text was changed (checked the diff: only display strings; `completeDelivery` note
"ส่งมอบครบจากหน้าดีล" is stored data and untouched — correct).

**M6. ที่มา "นำเข้า" before the source is decided is a false statement.** `DealFulfilmentPanel.jsx` ~1018: any item with
`qtyFromStock` 0 shows "นำเข้า". `decideProcurementSource` writes `reserveStock`, so before the decision every item reads
นำเข้า. The panel already receives `procurementSource` (`pending`), so "ยังไม่กำหนด" was available. Read code.

### LOW

- **L1. Title/button disagreement in the ขั้นต่อไป card.** The title comes from the stage table, the button from workState; they
  can name different things (report's own on-screen: "ติดตามผลใบเสนอราคา" + "ติดตามลูกค้า"; probe P7: DEPOSIT_RECEIVED titled
  "จัดซื้อสินค้า" with button "สร้างคำขอราคา" for a non-owner rep — the create_pcr-for-non-owner primary is **pre-existing**,
  `OWNER_ONLY_ACTIONS` never covered it, but the card now frames it as "the next step").
- **L2. ORDER_RECEIVED hint is wrong for deposit-bypass deals** (`depositBypassed`: NOT_REQUIRED/WAIVED/CREDIT_CUSTOMER): "ออกใบแจ้ง
  มัดจำ… ฝ่ายบัญชียืนยันรับมัดจำแล้วงานย้ายไป S7" — those deals move on their first payment (`TicketService` ~2166–2174).
- **L3. Two "what's next" statements.** DealStagePanel's "ถัดไป: …" strip still renders between the tab bar and every tab's content,
  under the ขั้นต่อไป card; not in the drawing, and the two can disagree.
- **L4. Empty `<h1>`** when projectName, title and customerName are all null (probe P5) — pre-existing shape, kept.
- **L5. ดูอย่างเดียว chip** is on every ISSUED card, including where the same card offers ออกฉบับแก้ไข / order e-mail / lead time
  (CEO, import on ImportDealPage). It is true of the tracker only; reads as the whole card.
- **L6. Quotation register "part=quotations" drops `quotationsAction` in its empty branch** (`DealDocumentRegister` `!hasAnyVisibleSection`):
  a grant holder without `canViewPricingRequests` would get no create link. Edge case.

### Not defects (attacked, held)
- Danger zone → quiet links: `canLostDeal`, `canHoldDeal`, `can.cancel` passed verbatim; same dialogs (`openMarkLost`,
  `openHold`, `setConfirm cancelTicket`). Old zone rendered on **every** tab, links only on ภาพรวม (MOVED, still reachable).
  Server only advertises CANCEL/MARK_LOST/PLACE_ON_HOLD on ACTIVE deals (`buildActions`), so no lifecycle widening.
- Paused / lost banners → card: the card prefers `stickyPrimaryAction` and only then offers resume/reopen. I checked whether a
  paused/lost deal can have a sticky primary: `buildActions` adds operational actions only when ACTIVE and `resolveWorkState`
  returns null for non-ACTIVE, and bid-outcome requires ACTIVE — so resume/reopen cannot be displaced. The banner's second
  button (พัก dormant on ON_HOLD) moved to ⋯ with the same predicate (`ON_HOLD && MARK_DORMANT`). Both still reachable
  (subject to M0 on phones).
- `canResumeDeal` / `canReopenDeal` equal DealStagePanel's banner gates; the ref openers re-check.
- โทรแล้ว = old add-activity gate = `requireDealOwnership` (owner/keyer sales, sales_manager, ceo); addActivity is allowed on
  inactive deals server-side, so showing โทรแล้ว on paused/lost is consistent. นัดเข้าพบ = same + ACTIVE
  (`VisitService.create`: VISIT_ROLES sales/sm/ceo, `requireDealWriteAccess`, ACTIVE). New placement, not new permission.
- Tabs: visibility rules equal the absorbed tabs'. Header value (`sections.payment`) and chance (`canViewDealTracking`) match
  the old chip/panel gates. ข้อมูลงาน shows nothing to account/import they did not already see in วันสำคัญ/ผู้เกี่ยวข้อง/header.
- Money tiles: same `amountPayable / amountPaid / amountOutstanding` server figures as the old band, no recomputation, so
  partial receipts / credit notes / currency behave exactly as before (whatever the server sums). Labels changed
  ("ยอดที่ต้องชำระ"→"มูลค่างาน"); the "รับแล้ว" card also lists non-receipt money events (subtitle says so).
- Timeline filters: "ทั้งหมด" returns every row, so nothing disappears under every filter; stage moves, comments and calls
  appear only under ทั้งหมด (by design).
- S-code mapping: read from the served catalog `repCode` (`DealStage.REP_CODE`), the same source the board uses — no
  divergence possible; matches spec §7.1 table. CLOSED_PAID → S9.
- Deep links: only `?tab=money` is used by any link in the repo (e2e-real cash-and-ops, tests); no email/notification/
  What's-new builder emits a deal `?tab=`. `documents`→ไฟล์ therefore strands no in-app link; a bookmark that wanted the
  quotation list lands one tab off (judgment call, not a defect). `ImportFulfilmentPage.jsx:692` still tells users to record
  delivery "ที่แท็บ จัดซื้อ-ส่งมอบ" — stale tab name (Low).
- DealItemsPanel move: diffed against the removed block; gates (`can.editItems`, price-column role reads) unchanged.

---

## Before/after action table (my own, from `997c7e4d`)

| Action (old place) | Old condition | New place | New condition | Verdict |
|---|---|---|---|---|
| Primary workflow button (header action bar) | stickyPrimaryAction (unchanged code) | ขั้นต่อไป card / condensed row | same | MOVED |
| ⋯ เลื่อนไป / แก้ไขสถานะ… / record outcome / แก้ใบเสนอราคา / ยืนยันคำสั่งซื้อ direct / ขอแก้ไข | unchanged | header ⋯ (top) | same | MOVED (phone: M0) |
| ⋯ พักดีลไว้ | ACTIVE && PLACE_ON_HOLD | quiet link พักงาน (ภาพรวม only) | same | MOVED |
| ⋯ พัก dormant | ACTIVE && MARK_DORMANT | ⋯ | same ‖ ON_HOLD && MARK_DORMANT | SAME (+ banner copy merged) |
| Danger zone เสียงาน (every tab) | ACTIVE && MARK_LOST && !lost && !CLOSED_PAID | quiet link (ภาพรวม) | same | MOVED |
| Danger zone ยกเลิก (every tab) | CANCEL && !terminal && isOwner | quiet link (ภาพรวม) | same | MOVED |
| Paused banner ดำเนินการต่อ | ON_HOLD/DORMANT && RESUME | card | same (+ no sticky primary, never true when paused) | MOVED |
| Paused banner พัก dormant | ON_HOLD && MARK_DORMANT | ⋯ | same | MOVED |
| Lost banner เปิดดีลอีกครั้ง | CLOSED_LOST && REOPEN | card | same | MOVED |
| Lost banner reason/date (info) | lost | — | — | **LOST** (M2) |
| DealStagePanel guided advance, tender select, docActions, ดูขั้นตอนทั้งหมด, แก้ช่องทาง | unchanged | stage strip | same | SAME |
| DealTrackingPanel แก้ไขข้อมูลติดตาม | canViewDealTracking && (owner/sm/ceo) | ข้อมูลงาน แก้ไข | same | MOVED |
| Parties add/edit/remove | canViewDealTracking; edit canEditParties && ACTIVE | ภาพรวม right column | same | MOVED |
| Comment box (ประวัติ, always open) | !TERMINAL(st) | "+ บันทึก" composer | same | MOVED |
| Add activity form (ประวัติ, always open) | canViewDealTracking && (owner/sm/ceo) | "+ บันทึก" composer | same | MOVED |
| Edit items (สินค้าและราคา) | can.editItems | ใบเสนอราคา tab | same | MOVED (draft lost on tab switch, M3) |
| PricingRequestPanel | canViewPricingRequests && sections.pricingRequest | ใบเสนอราคา tab | same | MOVED |
| DealQuotationPanel | sections.dealQuotation && canViewPricingRequests | ใบเสนอราคา tab | same | MOVED |
| Legacy quotation downloads | sections.quotation | ใบเสนอราคา tab | same | MOVED |
| Register quotation rows | canViewQuotations | ใบเสนอราคา card | same | MOVED |
| Register deposit/remaining/IR/attachment rows | own gates | ไฟล์ | same | MOVED |
| Payment steps, downloads | DealPaymentSection gates | เงิน | same | MOVED |
| เงินที่คาดว่าจะเข้า | ceo/sm/owner-keyer sales | เงิน | same | SAME |
| ยกเลิกการยืนยันปิดงาน | REVOKE_CLOSE_CONFIRM | เงิน | same | SAME |
| Fulfilment chain, reserve stock, weights | panel gates | ส่งของ | same | MOVED |
| Upload/delete attachment (ประวัติ) | canViewDocumentsTab; canUpload | ไฟล์ | same | MOVED |
| รีเฟรช | always | header | same | SAME |
| Header chips: PR status, payment stage, import progress, created-by/at, assigned import | role-gated | partly ข้อมูลงาน (assigned import, updatedAt); rest on tabs | — | MOVED/partly dropped (created-at) |
| โทรแล้ว | — | header | add-activity gate | NEW placement (server-backed) |
| นัดเข้าพบ | — (งานของฉัน only) | header | add-activity gate && ACTIVE | NEW placement (server-backed) |
| + สร้างใบเสนอราคา | removed in `5560009a` | ใบเสนอราคา card | ACTIVE && canCreateDealQuotation && !unread && !livePR | **WIDENED vs the last version of this link** (H1: lost `!liveDirectQuotation`) |

---

## Tests

### Existing assertions rewritten where the old one was a deliberate guard
1. `TicketDetailPage.test.jsx` "the เอกสาร tab has no ใบเสนอราคา panel, route buttons or helper line" —
   old `expect(document.querySelector('a[href*="/quotations/new"]')).toBeNull()` (owner, account, CEO) → new: exactly
   `links` (1 for owner, 0 others) with text "+ สร้างใบเสนอราคา". This inverts the PR-A guard (see H1).
2. "does not duplicate พัก dormant when ON_HOLD" — old: `queryByRole('menuitem', {name:'พัก dormant'})` null (banner owned
   it) → new: exactly one, in ⋯. Legitimate structural move.
3. Header chip wrong-way-rounds (account never sees คำขอราคา chip; import never sees การชำระเงิน/มูลค่าดีล) → value/chance
   only. The PR-status wrong-way-round is gone with its chip; the ข้อมูลงาน "ผู้รับเรื่องคำขอราคา" row is the remaining
   PR readout and is not asserted for account in the new tests.
4. Refresh `mobile:absolute` placement → 44px check; DealStagePanel phase summary "ONE on default view" → "none on default,
   one behind disclosure"; ON_HOLD S3 wording read via headline. Structural, not weakened.
5. `ticketWorkspaceLayout.test.js` fixed-bar source guard now reads NextStepCard.jsx — still a string-presence test; it does
   not check the matching page padding (which is how M1 slipped through).
No assertion was deleted to hide a failing behaviour other than (1).

### Mutations
45 mutations, 30 killed and 15 survived. Each file was restored and byte-compared, and the tree is clean.

| # | Mutation | Result | Judgement |
|---|---|---|---|
| M01 | create link: drop ACTIVE guard | **SURVIVED** | real gap: no test of the link on a paused/lost deal |
| M02 | create link: drop request-list-unread guard | **SURVIVED** | real gap: the "DRAFT may be hiding" wait is untested |
| M03 | create link: drop live-PR guard | killed | |
| M04 | canAddActivity: drop canViewDealTracking | **SURVIVED** | equivalent in practice (isOwner/sm/ceo already implies a tracking role) |
| M05 | canAddActivity → any tracking viewer | killed | |
| M06 | นัดเข้าพบ without ACTIVE | killed | |
| M07 | resume without RESUME | killed | |
| M08 | reopen without REOPEN | killed | |
| M09 | resume even when a sticky primary exists | **SURVIVED** | equivalent: no operational action is ever served on a paused deal |
| M10 | "ไปที่<tab>" without visibleTabIds check | **SURVIVED** | real gap: a role whose tab is hidden would get a button to a tab it cannot see; untested |
| M11 | tab fallback on closed/none kinds | **SURVIVED** | equivalent (visibleTabIds blocks a null tab) |
| M12 | dormant-on-paused widened to DORMANT | **SURVIVED** | equivalent given the server (MARK_DORMANT only on ON_HOLD), but the predicate is unpinned |
| M13 | dormant-on-paused without MARK_DORMANT | killed | |
| M14 | ยกเลิกงาน not owner-only | killed | |
| M15 | ปิดงาน at CLOSED_PAID | killed | |
| M16 | พักงาน without ACTIVE | killed | |
| M17 | header chance for every role | killed | |
| M18 | header value for every role | killed | |
| M19 | ข้อมูลงาน tracking rows for every role | killed | |
| M20 | ข้อมูลงาน edit for any tracking viewer | killed | |
| M21 | เงิน tab for every role | killed | |
| M22 | drop old key documents | killed | |
| M23 | drop old key history | killed | |
| M24 | swap tab labels | killed | |
| M25 | legacy key bypasses role visibility | killed | |
| M26 | nextStep ORDER_RECEIVED tab | killed | |
| M27 | DORMANT → closed | killed | |
| M28 | S3 mover → sales | killed | |
| M29 | swap S7/S8 labels | killed | |
| M30 | CALL counts as เข้าพบ | killed | |
| M31 | drop DEPOSIT_NOTICE_ISSUED from เอกสาร | killed | |
| M32 | remove motion-reduce on header | **SURVIVED** | the brief's reduced-motion requirement has no test |
| M33 | remove 44px on the tab strip | **SURVIVED** | 44px claim untested |
| M34 | remove 44px on quiet links | **SURVIVED** | 44px claim untested |
| M35 | remove stepper overflow wrapper | **SURVIVED** | the 320px no-page-scroll requirement has no test |
| M36 | ไฟล์ shows quotations again | killed | |
| M37 | ที่มา never คลัง | killed | |
| M38 | ดูอย่างเดียว on drafts too | **SURVIVED** | chip only tested positively |
| M39 | openResume drops lifecycle re-check | killed | |
| M40 | reopen drops REOPEN re-check | killed | |
| M41 | โทรแล้ว logs MEETING | killed | |
| M42 | ขั้นต่อไป card rendered while condensed | **SURVIVED** | condensed layout untested |
| M43 | activity form ignores canAddActivity | killed | |
| M44 | drafts count as priced | killed | |
| M45 | repStepIndex off by one | killed | |

The permission-shaped gates (quiet links, resume/reopen, quick actions, value/chance, tabs) are well pinned. The gaps are in
the new link (M01, M02, and H1's missing live-direct-quotation case, which no mutation could catch because the guard is
absent), the tab fallback (M10), and every responsive/motion/tap-target requirement (M32–M35, M42). Those requirements are
claimed in the report but not tested.

## On-screen
Run in mock mode only (`VITE_USE_MOCKS=true`, own dev server on port 5263, stopped afterwards, own browser tab, closed).
**This is not evidence about permissions.** Sales demo quick-login, `/tickets/1`:
- At 375×700, the ⋯ menu opens upward over the app bar; measurements are under M0.
- At 320×560 there is no horizontal page scroll (scrollWidth = clientWidth = 320). The stepper and tab strip scroll inside
  their own wrappers. The header is static on a phone (461px tall) and scrolls away, so it does not trap scrolling.
- The fixed bottom bar shows the primary "ติดตามลูกค้า". The ขั้นต่อไป card's title ("ติดตามผลใบเสนอราคา") and its button
  ("ติดตามลูกค้า") name different things (L1).
- The "ถัดไป: 5. เสนอราคาเจ้าของโครงการ — อัปเดตอัตโนมัติ…" strip renders directly under the tab bar, a second
  "what's next" line (L3).
- Not checked in a browser: the sticky desktop header on a short viewport, and the M1 padding gap (I had no paused or lost
  mock deal). M1 is established by reading the code.

## Commands run
- `git checkout --detach f29cd4cd`; `npm ci` (frontend).
- `npx vitest run src/features/tickets/ src/features/salesBoard/ src/features/pricingRequests/` → 60 files, 2483 tests passed.
- Scratch probe test `zz_probe.test.jsx` (8 cases, P1–P8), deleted.
- Mutation runner (scratch `mutate.mjs`), M01–M04 against the three directories, M05–M45 against
  `vitest related <mutated file>` plus the four source-reading test files (equivalent coverage: no test in the three dirs
  imports these files outside the related graph; the source-reading tests were added explicitly). Each file restored and
  byte-compared.
- Final `git status --short`: clean.
