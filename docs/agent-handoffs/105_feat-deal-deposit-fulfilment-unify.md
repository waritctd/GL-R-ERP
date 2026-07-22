# Agent Handoff

## Task
Phase 3 Slice S3 (DEPOSIT UI unification) on `feat/deal-deposit-fulfilment-unify`: compose the
existing deposit-related actions scattered across `TicketDetailPage.jsx` and `DealStagePanel.jsx`
into one coherent, role-shaped "มัดจำ" section on the Deal Workspace, matching Phase 2's
`DealQuotationPanel` pattern. Frontend-presentation only — no backend Java, no schema, no new
endpoints; every mutation reuses an existing `hrApi` method verbatim.

## Branch
`feat/deal-deposit-fulfilment-unify` (stacked on Phase 1 commission/weekly-report work and Phase 2
Slices S1/S2 — deal-workspace-unification's "engine collapse" + state header/quotation-panel pull-in
— both already committed on this branch, untouched by this slice).

## Base Commit
`d9e07f49452d5d20c9a4da1ba69e2981b38e3c63` (tip of the branch when this session started; working
tree was clean).

## Current Commit
Not committed — per instructions, this session left the changes on disk uncommitted.

## Agent / Model Used
Claude Sonnet 5 (Claude Code)

## Scope

### In Scope
- New `DealDepositPanel.jsx`: one "มัดจำ" section, three ordered role-owned steps — policy
  (account/CEO) → notice (sales) → payment confirmation (account).
- Mount it in `TicketDetailPage.jsx`; remove the deposit-policy control from `DealStagePanel.jsx`
  and the scattered deposit doc/payment bits from `TicketDetailPage.jsx`.
- `salesViewScope.js` doc-comment update (the `depositNotice` section id now gates the whole panel,
  not just a doc-row link) — no behavioural change, `SECTION_IDS` keys unchanged.
- Test coverage: `TicketDetailPage.test.jsx` (`describe('deal deposit panel')`),
  `DealStagePanel.test.jsx` (stale prop removed).
- Live mock self-check (`VITE_USE_MOCKS=true`).

### Out of Scope
- Backend Java, schema, endpoints (none touched or invented).
- `DealQuotationPanel.jsx` (Phase 2 quotation panel) — explicitly not edited, per instructions,
  even though it creates a small documented overlap (see "Decisions Made").
- Fulfilment/procurement UI — that is Slice S4, next.
- Commission, Track-B `deal_activity`.

## Files Changed
- `frontend/src/features/tickets/DealDepositPanel.jsx` (new) — the "มัดจำ" panel. Three steps:
  1. **นโยบายมัดจำ** (`account`/`ceo`): shows the current policy (`depositPolicyLabel`) + reason;
     `hasAction('WAIVE_DEPOSIT')`-gated "เปลี่ยนนโยบายมัดจำ…" button opens a `Modal` (moved verbatim
     from `DealStagePanel`) calling `api.tickets.setDepositPolicy(ticketId, { policy, reason })`.
     Same three settable values as before (`WAIVED`/`NOT_REQUIRED`/`CREDIT_CUSTOMER` — `REQUIRED`
     was never a settable value even before this slice; the mock's `setDepositPolicy` 400s on it).
  2. **ใบแจ้งยอดมัดจำ** (`sales`): reads `api.depositNotices.listByTicket(ticketId)`, resolves the
     current doc (DRAFT, else latest ISSUED — same derivation `DepositNoticePage.jsx` uses). If a
     doc exists: docNumber/status/deposit%, an always-available "ตัวอย่าง" (`depositNotices.preview`,
     rendered in a `srcDoc` iframe like `DepositNoticePage`), PDF/Excel download when ISSUED
     (`depositNotices.downloadXlsx/downloadPdf`), or an "ออกเอกสาร" button when DRAFT and the
     viewer is the owning sales rep (`depositNotices.issue`). If no doc exists: offers
     `pricingRequests.createDepositNoticeFromQuotation` when `canCreateDepositNoticeFromQuotation`
     is true (mirrors `DealQuotationPanel`'s own gate — see overlap note below), or a legacy
     dual-track "ออกใบแจ้งยอดมัดจำ" link (mirrors the old `can.generateDocument` gate verbatim:
     `ISSUE_DEPOSIT_NOTICE` + `quotation_issued`/`CUSTOMER_CONFIRMED` + owning sales rep) that
     navigates to `DepositNoticePage` to create the draft manually, or a neutral "ยังไม่ถึงขั้นตอน…"
     line. Always shows a "ไปที่ใบแจ้งยอดเงินรับมัดจำ (แก้ไขรายการแบบเต็ม) →" link to
     `/tickets/{id}/deposit` (full line-item editing stays on `DepositNoticePage` — duplicating its
     ~300-line editable form here would double the diff and create two divergent implementations of
     the same `update` mutation, the same reasoning `DealQuotationPanel`'s own doc comment gives for
     not duplicating the customer-quotation editor).
  3. **รับชำระมัดจำ** (`account`): `hasAction('DEPOSIT_PAID')`-gated "ยืนยันรับมัดจำ" button calling
     `api.tickets.confirmDepositPaid(ticketId)` once `paymentStatus === 'DEPOSIT_NOTICE_ISSUED'`;
     a read-only "รับมัดจำแล้ว" badge once paid; a waiting line otherwise.
  When the current deposit policy bypasses the notice (`WAIVED`/`NOT_REQUIRED`/`CREDIT_CUSTOMER`),
  steps 2 and 3 render greyed (`opacity-60`) with a short "ข้ามขั้นตอนนี้ — นโยบายมัดจำคือ … (เหตุผล)"
  line instead of their normal content — verified live (see below). Each step's header carries a
  `StepRoleTag` naming its owning role(s), highlighted (`bg-info-bg text-info`) when it matches the
  viewer's own role, muted otherwise — "emphasize the step that belongs to the viewer, show the
  others as context." Every mutation is self-contained in this panel (its own `useMutation`s,
  invalidating `depositNotices`/`ticketDetail`/`ticketActions`/`ticketPayments`/`tickets.list`) —
  the same "own mutations, not the parent's shared `actionMutation`" pattern `DealQuotationPanel`
  established in Slice S2, not `TicketDetailPage`'s `doAction`/`can.*` machinery.
- `frontend/src/features/tickets/DealStagePanel.jsx` — removed `depositOpen`/`depositPolicy`/
  `depositReason` state, `canDepositPolicy`, `submitDepositPolicy`, the `onSetDepositPolicy` prop,
  the "นโยบายมัดจำ…" button (was in the `canTender || canDepositPolicy` row, now just `canTender`),
  and its `Modal`. **Kept**: the read-only "นโยบายมัดจำ:" status chip inside the pipeline's
  "inner journey" strip (`depositBypassesNotice`/`depositPolicyLabel`, lines ~286-296) — a read-only
  status display, not a control, consistent with how the pricing/payment/import substep chips also
  stay in `DealStagePanel` while their own live actions moved to dedicated panels. This is a
  deliberate, documented duplication (see "Decisions Made").
- `frontend/src/features/tickets/TicketDetailPage.jsx`:
  - Removed `can.generateDocument` and `can.confirmDepositPaid`; removed their two
    `NEXT_ACTION_STEPS` entries and the `can.confirmDepositPaid` branch of `primaryAction`
    (`confirmCustomer` now falls through directly to `markIrSent`).
  - Removed the `depositNoticeIssued` const and its two `docActions` JSX blocks ("ออกใบแจ้งยอดมัดจำ"
    / "ดูใบแจ้งยอดมัดจำ" buttons); `docActions`'s gating condition dropped
    `can.generateDocument`/`(sections.depositNotice && depositNoticeIssued)`.
  - Removed `onSetDepositPolicy` from the `<DealStagePanel>` call.
  - Mounted `<DealDepositPanel>` right after `<DealQuotationPanel>`, gated on
    `sections.depositNotice` with a `<SectionPeek title="มัดจำ" .../>` fallback (same pattern as the
    `payment`/`delivery` sections).
  - Removed the now-fully-unused `onOpenDocument` prop (its only two call sites were the two
    removed doc-row buttons) from the component signature.
- `frontend/src/App.jsx` — removed the now-dead `onOpenDocument={(ticketId) => navigate(...)}` prop
  passed into `TicketDetailRoute`'s `<TicketDetailPage>` (its only consumer). Minimal, justified
  follow-through of the consolidation, not a route/contract change.
- `frontend/src/features/tickets/salesViewScope.js` — updated the `depositNotice` section id's
  inline comment to describe its new scope (gates `DealDepositPanel`, not just a doc-row link).
  `SECTION_IDS` list, `visibleSections()` logic, and every existing assertion in
  `salesViewScope.test.js` are byte-for-byte unchanged — `depositNotice` already resolved to
  `true` for `sales`/`account`/`ceo`/`sales_manager` and `false` for `import`, which is exactly the
  gate this panel needed.
- `frontend/src/features/tickets/DealStagePanel.test.jsx` — removed the now-nonexistent
  `onSetDepositPolicy: vi.fn()` from the shared `noopHandlers`.
- `frontend/src/features/tickets/TicketDetailPage.test.jsx`:
  - Added `tickets.setDepositPolicy`/`tickets.confirmDepositPaid` and a new `depositNotices`
    namespace (`listByTicket`/`issue`/`preview`/`downloadXlsx`/`downloadPdf`) to the mocked `api`.
  - `beforeEach`: `api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] })` —
    `DealDepositPanel` mounts for every role but `import`, so every existing test needed this
    default or it would hit "not a function"/unhandled-rejection noise.
  - Removed the stale `onOpenDocument={vi.fn()}` prop from the render helper.
  - New `describe('deal deposit panel')` (5 tests): renders all three steps for the default
    REQUIRED policy; account changes the policy via `api.tickets.setDepositPolicy`; a WAIVED policy
    renders steps 2/3 as skipped with the reason (asserts exactly 2 "ข้ามขั้นตอนนี้" matches, scoped
    with `within()` on the panel's own `<section>` to avoid colliding with `DealStagePanel`'s
    read-only duplicate of the same policy label — see "Decisions Made"); account confirms deposit
    paid via `api.tickets.confirmDepositPaid`; import never sees the section (`SectionPeek` only,
    `depositNotices.listByTicket` never called).

## Commands Run
```bash
cd frontend
npx eslint src/features/tickets/DealDepositPanel.jsx src/features/tickets/DealStagePanel.jsx \
  src/features/tickets/TicketDetailPage.jsx src/features/tickets/salesViewScope.js src/App.jsx \
  src/features/tickets/TicketDetailPage.test.jsx src/features/tickets/DealStagePanel.test.jsx
npx vitest run src/features/tickets/TicketDetailPage.test.jsx src/features/tickets/DealStagePanel.test.jsx \
  src/features/tickets/salesViewScope.test.js src/api/contract.test.js
npm run lint
npm test -- --run
npm run build
```

## Test / Build Results
- `npm run lint`: **PASS** — 0 errors, 1 pre-existing warning unrelated to this slice
  (`PayrollPage.jsx` exhaustive-deps, same one noted in handoff 104).
- `npm test -- --run`: **PASS** — 48 test files, 425 tests, all green (was 420 at the start of this
  slice; net +5 from the new `deal deposit panel` describe block).
- `npm run build`: **PASS** — `vite build` succeeds, no errors (`TicketDetailPage` chunk
  125.52 kB / 25.61 kB gzip).

## Live Mock Self-Check (VITE_USE_MOCKS=true)
Ran a fresh `VITE_USE_MOCKS=true npm run dev -- --port 5202 --strictPort` (port 5200 from
`.claude/launch.json`'s `frontend-mock` config was occupied by a non-preview process — used a fresh
port instead, same workaround handoff 104 used) and drove it through the Browser pane:
- **Account**, deal `PR-2026-0013` (Mega Bangna Retail, no PCR, already past deposit into
  procurement/delivery): "มัดจำ" section renders all three steps — step 1 shows "ต้องเก็บมัดจำ" +
  "เปลี่ยนนโยบายมัดจำ…"; step 2 shows "ยังไม่ถึงขั้นตอนออกใบแจ้งยอดมัดจำ" (correct — no PCR exists so
  neither create path qualifies, and a notice already isn't needed since deposit was already
  confirmed through the legacy dual-track flow) plus the link to `/tickets/13/deposit`; step 3 shows
  "รับมัดจำแล้ว" (read-only, since `confirmDepositPaid` isn't currently reachable — deposit already
  paid). Opened the policy modal (`WAIVED`/`NOT_REQUIRED`/`CREDIT_CUSTOMER` only, matching the old
  modal exactly), filled a reason, submitted — `api.tickets.setDepositPolicy` fired, the panel
  re-rendered with **step 1 → "ยกเว้นมัดจำ" + reason, steps 2 and 3 → both show
  `ข้ามขั้นตอนนี้ — นโยบายมัดจำคือ "ยกเว้นมัดจำ" (…reason…)` greyed out**, and the event history
  logged `deposit_policy → WAIVED — …`. No console errors. This is the exact "waived-policy deal
  renders cleanly" self-check the task asked for.
- **Sales** (owner, `คุณสมหมาย ขายดี`), same deal after a fresh reload (mock state resets on
  reload — expected, not a bug; the WAIVED write above did not persist into this pass, so the
  policy read REQUIRED again): confirmed steps 1 and 3 render read-only for this role (no
  "เปลี่ยนนโยบายมัดจำ…" button, no "ยืนยันรับมัดจำ" button — both correctly account/CEO-only), and
  clicked "ไปที่ใบแจ้งยอดเงินรับมัดจำ…" → landed on `DepositNoticePage` at `/tickets/13/deposit`
  showing its own "ยังไม่มีใบแจ้งยอดเงินรับมัดจำ / สร้างเอกสารฉบับร่าง" empty state, confirming the
  full page is still reachable and unaltered.
- **Not verified live**: the `canCreateDepositNoticeFromQuotation` create-from-quotation path (no
  seed deal reaches `QUOTATION_ACCEPTED` + `orderConfirmedAt` with no deposit notice yet) and the
  `DEPOSIT_PAID`-actionable state (no seed deal sits at exactly `DEPOSIT_NOTICE_ISSUED`). Both are
  covered by the automated `TicketDetailPage.test.jsx` tests instead (mocked `availableActions` +
  `summary` fixtures exercise `confirmDepositPaid` and the policy-change mutation end to end).
- Import role's peek-only behaviour was **not** re-verified live in this session (time-boxed); it is
  covered by the automated test (`import never sees it, only a one-line peek`), which asserts
  `api.depositNotices.listByTicket` is never called for that role.

## Authz Evidence
**No authorization/role-gate change in this slice.** Every predicate `DealDepositPanel` uses is
either imported verbatim (`canCreateDepositNoticeFromQuotation` from `pricingRequestMeta.js`) or
copied byte-for-byte from where it already lived (`hasAction('WAIVE_DEPOSIT')` from
`DealStagePanel`; `hasAction('ISSUE_DEPOSIT_NOTICE')`/`hasAction('DEPOSIT_PAID')` +
`ROLE_PERMISSIONS.canCreateTickets`/`canConfirmPayments` + `isOwner` from `TicketDetailPage`'s old
`can.generateDocument`/`can.confirmDepositPaid`). None were added, edited, or newly composed.
`salesViewScope.js`'s `depositNotice` section id is presentation-only (per that file's own doc
comment, "never a security boundary") and its role visibility was not changed — only its scope
(what it now gates) was widened in the comment, matching what was already true of its boolean
value. Verification here ran under `VITE_USE_MOCKS=true` only — no authz claim beyond that.

## Decisions Made
- **`DealQuotationPanel.jsx` was left completely untouched, per explicit instruction**, even though
  it creates one documented, real overlap: `DealQuotationPanel`'s own "ยืนยันคำสั่งซื้อและออกใบแจ้งยอด
  เงินรับมัดจำ" box already offers `pricingRequests.createDepositNoticeFromQuotation` for exactly the
  same `canCreateDepositNoticeFromQuotation(user, pr)` condition `DealDepositPanel`'s step 2 checks.
  Both panels can therefore show a "create the deposit notice" control at once, for a sales rep
  viewing a deal whose PCR just reached `QUOTATION_ACCEPTED` with `orderConfirmedAt` set and no
  notice yet. This was a genuine tension between the task's item 1 (explicitly requires
  `DealDepositPanel` to compose `createDepositNoticeFromQuotation`) and its "DO NOT touch the
  Phase-2 quotation panel" instruction. Resolved by implementing the create action for real in
  `DealDepositPanel` (so the panel is functionally complete and the "มัดจำ" section is a coherent,
  self-sufficient home for the whole deposit lifecycle) and leaving `DealQuotationPanel`'s own copy
  in place rather than silently dropping either requirement. Flagging this explicitly rather than
  claiming the duplication doesn't exist — a reviewer with authority to touch `DealQuotationPanel`
  could remove its half in a follow-up once this panel is confirmed to be the canonical home.
- **The legacy dual-track "ออกใบแจ้งยอดมัดจำ" affordance (`ISSUE_DEPOSIT_NOTICE`) became a link to
  `DepositNoticePage`, not an inline action**, unlike the old `TicketDetailPage` button (which also
  just navigated via `onOpenDocument`) — behaviourally identical, just re-expressed as a `Link` in
  the new panel instead of a callback prop threaded through three components.
- **`DealStagePanel` keeps a read-only duplicate of the policy label** (in its existing "inner
  journey" chip strip) rather than having it removed alongside the control. This mirrors the
  existing precedent on that same panel (payment/import/pricing substep chips stay there as
  read-only summaries while their live actions moved to dedicated panels in earlier slices) and
  avoids ripping out an unrelated, still-useful glance-strip line. It does mean the exact policy
  label/reason text can appear twice on the page (once in each panel) — tests account for this by
  scoping queries to `DealDepositPanel`'s own `<section>` via `within()`.
- **Full deposit-notice line-item editing was deliberately not folded inline** — only issue/
  preview/download plus a link to the full `DepositNoticePage`, for the same reason
  `DealQuotationPanel` doesn't duplicate the customer-quotation item editor.
- **`tickets.downloadRemainingInvoice`/`can.downloadRemainingInvoice` were left untouched** in
  `TicketDetailPage.jsx` — despite living under the same `depositNotices.*` backend route namespace,
  it is functionally the *final-payment* remaining-balance invoice (gated on
  `fs === 'GOODS_RECEIVED'`), not part of the deposit step this slice covers. Moving it would have
  been scope creep into S4 (fulfilment/final-payment) territory.

## Assumptions
- The task's phrase "depositNotices.{get,update,preview,issue,file,remainingInvoiceFile}" was read
  as the full set of *available* methods, not a mandate to call every one from this panel —
  `get`/`update`/`remainingInvoiceFile` genuinely don't fit this panel's read-only-summary-plus-link
  design (see "Decisions Made" above for `remainingInvoiceFile` specifically); `listByTicket` (used
  instead of per-id `get`) is the existing method that already returns the full doc list a summary
  panel needs in one call, matching `DepositNoticePage`'s own read pattern.
- "keep every SECTION_ID key present" (task item 3) was read as: do not remove or rename any key in
  the `SECTION_IDS` array/`visibleSections()` output — satisfied by reusing the existing
  `depositNotice` id rather than adding a parallel `dealDeposit` id, which would have left the old
  id orphaned.

## Known Risks
- The `DealQuotationPanel`/`DealDepositPanel` create-deposit-notice overlap described above is real
  and live in the app today, not just theoretical — a sales rep on a deal with an accepted quotation
  and a confirmed order but no deposit notice yet will see the same "สร้างใบแจ้งยอดเงินรับมัดจำ"
  capability offered in two different sections of the same page. Both call the identical
  `pricingRequests.createDepositNoticeFromQuotation(pr.id, { depositPercent })`, so clicking either
  produces the same correct result — this is a UX/duplication risk, not a correctness or authz risk.
- `DealStagePanel`'s and `DealDepositPanel`'s read-only policy-label duplication (see "Decisions
  Made") means the exact same status text can appear twice; low risk (both always agree, since both
  read `summary.depositPolicy`/`depositPolicyReason` from the same query), flagging for visual
  awareness only.
- The `canCreateDepositNoticeFromQuotation` and `DEPOSIT_PAID`-actionable paths were verified only
  via automated tests, not live-clicked end to end (see "Live Mock Self-Check" above) — no seed deal
  in the mock reaches either exact state today.

## Things Not Finished
None for this slice's stated scope. S4 (`DealFulfilmentPanel` — procurement/fulfilment UI
unification, currently scattered across `TicketDetailPage`'s "การส่งมอบสินค้า" section and
`DealStagePanel`'s import substep chips/doc actions) is the next planned slice on this branch.

## Recommended Next Agent
Claude Sonnet (implementation) for Slice S4, frontend-focused.

## Exact Next Prompt
```
Phase 3 Slice S4 (FULFILMENT UI unification) in GL-R-ERP, on branch
feat/deal-deposit-fulfilment-unify (Phase 1 + Phase 2 + Phase 3 Slice S3 all already committed
here — do NOT revert). FRONTEND-PRESENTATION ONLY: no backend Java, no schema, no endpoints.

Read CLAUDE.md, then docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md (this
handoff) and 104_feat-deal-workspace-unification.md, then:
frontend/src/features/tickets/DealDepositPanel.jsx (THE pattern to match, just shipped — a
self-contained, three-role-step panel mounted in TicketDetailPage), DealQuotationPanel.jsx
(the earlier Phase 2 pattern), TicketDetailPage.jsx (find the scattered fulfilment bits — the
"การส่งมอบสินค้า" section, can.issueImportRequest/markIrSent/markShipping/markGoodsReceived/
reserveStock/recordDelivery/completeDelivery, the docActions IR button, the delivery/stock
modals), DealStagePanel.jsx (the "การนำเข้า:" PROCUREMENT_SUBSTEPS chip strip + deliveryProgress
bar), salesViewScope.js (+test, the 'delivery' section id), and api/routes.js+hrApi.js+mockApi.js
(tickets deliveries/reserveStock/recordDelivery/completeDelivery/issueImportRequest/markIrSent/
markShipping/markGoodsReceived blocks) + contract.test.js.

Compose the existing hrApi methods verbatim into one coherent, role-shaped "การนำเข้า/ส่งมอบ"
section (import owns IR/shipping/goods-received/delivery-recording; sales/CEO see progress
read-only) on the Deal Workspace, matching DealDepositPanel's three-step, role-tagged structure.
Consolidate the scattered fulfilment bits out of TicketDetailPage into the new panel; keep
DealStagePanel's PROCUREMENT_SUBSTEPS chip strip as the read-only glance summary (same precedent
DealDepositPanel just established for the deposit-policy chip). Run
cd frontend && npm run lint && npm test && npm run build, self-check in the mock app, and write
handoff 106.
```

---

# S4 Update — FULFILMENT UI unification + DealQuotationPanel de-duplication

## Task
Phase 3 Slice S4 (the slice planned above) plus a small, separately-requested de-duplication of
`DealQuotationPanel`'s terminal action, both on `feat/deal-deposit-fulfilment-unify`. Frontend-
presentation only — no backend Java, no schema, no new endpoints; every mutation reuses an
existing `hrApi` method verbatim. `DealDepositPanel.jsx` (S3) was explicitly out of scope to edit.

## Current Commit
Not committed — left on disk uncommitted, per instructions.

## Agent / Model Used
Claude Sonnet 5 (Claude Code)

## Scope

### In Scope
- New `DealFulfilmentPanel.jsx`: one "การส่งมอบ / นำเข้า" section — the deal-level IR → IR-sent →
  shipping → goods-received chain (or the from-stock path), a delivery-recording step, and an
  optional per-factory PO detail block.
- Mount it in `TicketDetailPage.jsx`; remove the "การส่งมอบสินค้า" panel, the docActions Import
  Request button, the `can.issueImportRequest`/`markIrSent`/`markShipping`/`markGoodsReceived`/
  `reserveStock`/`recordDelivery`/`completeDelivery` flags, and the delivery/stock-reservation
  modals from `TicketDetailPage.jsx`.
- `salesViewScope.js`: reuse the existing `delivery` section id (no new id — same reasoning S3
  gave for reusing `depositNotice`); widen it so `account` sees the section too (read-only),
  matching the task's explicit "import + account + ceo see it" instruction.
- De-duplicate `DealQuotationPanel.jsx`'s deposit-notice-from-quotation control (now solely owned
  by `DealDepositPanel`'s "ใบแจ้งยอดมัดจำ" step, S3) — terminal action becomes confirm-order only.
- Test coverage: `TicketDetailPage.test.jsx` (`describe('deal fulfilment panel')`, rewritten
  delivery-modal validation test), `salesViewScope.test.js` (account's `delivery` flag flip).
- Live mock self-check (`VITE_USE_MOCKS=true`), including a real end-to-end delivery-recording
  click.

### Out of Scope
- Backend Java, schema, endpoints (none touched or invented).
- `DealDepositPanel.jsx` — explicitly not edited, per instructions, even though its own doc
  comment now describes an overlap ("Known overlap... not fixed here") that Task 2 of this slice
  already fixed on the `DealQuotationPanel` side — see "Known Risks" below.
- Commission, Track-B `deal_activity`.

## Files Changed

- **`frontend/src/features/tickets/DealFulfilmentPanel.jsx` (new)** — the "การส่งมอบ / นำเข้า"
  panel, following `DealDepositPanel`'s established shape (own `useQuery`/`useMutation`s, own
  `StepRoleTag`/`StepNumber` helpers duplicated rather than imported, since neither panel exports
  them):
  1. **นำเข้าสินค้า** (`import`/`ceo`): a compact `PROCUREMENT_SUBSTEPS` chip strip (imported from
     `stageMeta.js`, the same constant `DealStagePanel`'s own read-only strip already uses — this
     is the "reuse where sensible" the task asked for) plus the single next action for the deal's
     current `fulfillmentStatus`: `issueImportRequest` (fs `null`) → `markIrSent` (`IR_ISSUED`) →
     `markShipping` (`IR_SENT`) → `markGoodsReceived` (`SHIPPING`), each moved out of
     `TicketDetailPage` byte-for-byte (`can.*` gates unchanged: `hasAction(...) && st ===
     'quotation_issued' && fs === ... && isFulfilment`). `reserveStock` (`จองสินค้าจากสต็อก`) sits
     alongside it, independent of the IR sub-status, opening a modal moved verbatim from
     `TicketDetailPage`.
  2. **ส่งมอบสินค้า** (`import`/`ceo`): the delivered/ordered progress bar + per-item breakdown +
     delivery history, all moved from `TicketDetailPage`'s old "การส่งมอบสินค้า" panel (now owns
     its own `ticketDeliveries` query instead of the parent fetching it),
     `recordDelivery`/`completeDelivery` buttons and the record-delivery modal (also moved
     verbatim, with the same `source: fs === 'FROM_STOCK' ? 'STOCK' : 'WAREHOUSE'` prefill logic).
  3. **ใบสั่งซื้อโรงงาน (Factory PO)** (`import`/`ceo` only — visible to no other role,
     independent of `sections.delivery`'s wider audience): resolves the deal's order-confirmed
     `PricingRequest` via `pickAcceptedPricingRequest` (a copy of `DealDepositPanel`'s own
     `pickAcceptedPricingRequest`, same `QUOTATION_ACCEPTED` filter — also the only status
     `ProcurementService`/mock `procurement.create` accepts, so it is the correct id to resolve
     against), then calls `api.procurement.listForPricingRequest(pr.id)` — **gated to
     `import`/`ceo` only**, mirroring the mock's own `hasRole('import', 'ceo')` guard on that
     method (`api/mockApi.js` — calling it as `account` would 403). Renders each PO's
     proforma/shipping/goods-received summary with a `factoryPurchaseOrderStatusLabel` badge and a
     `Link` to `/factory-purchase-orders/{id}` (`ProcurementDetailPage`, unedited, still owns full
     editing). When `pr` is `null` (no order-confirmed PCR yet — the common case today), shows a
     neutral note instead of attempting the query; when the query resolves an empty list (the
     actual production state — 0 factory POs exist), renders `EmptyState` rather than an empty
     table or an error, per the task's explicit instruction.
  Every mutation invalidates the same query set (`ticketDetail`/`ticketActions`/
  `ticketDeliveries`/`tickets.list`/`dashboardSummary`/`notifications`) via one
  `invalidateAfterFulfilmentChange()` helper — the same "own mutations, invalidate, no shared
  `actionMutation`" pattern `DealDepositPanel`/`DealQuotationPanel` established.
- **`frontend/src/features/tickets/TicketDetailPage.jsx`**:
  - Removed the entire "การส่งมอบสินค้า" `<section>` (progress bar, per-item table, delivery
    history) and its `SectionPeek` fallback, replaced by a comment pointing at
    `DealFulfilmentPanel`'s new mount site.
  - Removed `deliveriesQuery`/`deliveryRecords` (moved into the panel); kept
    `totalOrdered`/`totalDelivered` (still needed for `DealStagePanel`'s own read-only
    `deliveryProgress` prop) but dropped the now-unused `deliveryProgress` percentage constant.
  - Removed `deliveryModal`/`deliveryDraft`/`stockModal`/`stockDraft` state, `handleRecordDelivery`/
    `handleReserveStock`/`openDeliveryModal`/`closeDeliveryModal`/`openStockModal`, and the two
    `<Modal>` blocks (moved verbatim into the new panel); trimmed the corresponding lines out of
    `resetActionDrafts` and the `fieldErrors` key doc-comment (the `delivery.lines` key no longer
    exists here — the new panel reports its one group-level guard via `showToast` instead, the
    same simpler convention `DealDepositPanel`/`DealQuotationPanel` already use, not
    `TicketDetailPage`'s aria-invalid/fieldRefs apparatus).
  - Removed `isImport`/`isFulfilment` and the seven fulfilment `can.*` flags
    (`issueImportRequest`/`markIrSent`/`markShipping`/`markGoodsReceived`/`reserveStock`/
    `recordDelivery`/`completeDelivery`) from the `can` object — moved into the panel
    byte-for-byte, same status+role checks, no longer routed through `doAction`/`actionMutation`.
  - Trimmed the four fulfilment entries out of `NEXT_ACTION_STEPS` and the three
    `can.markIrSent`/`markShipping`/`markGoodsReceived` branches out of the `primaryAction`
    ternary (confirmCustomer now falls through directly to confirmFinalPayment).
  - Removed the docActions "ออก Import Request (IR)" button and `can.issueImportRequest` from its
    guard condition (quotation-download link + remaining-invoice button unchanged).
  - Mounted `<DealFulfilmentPanel>` right after `<DealDepositPanel>`, gated on `sections.delivery`
    with a `<SectionPeek title="การส่งมอบ / นำเข้า" .../>` fallback — same position/pattern as
    `DealQuotationPanel`/`DealDepositPanel`.
  - Removed the now-unused `fulfilmentStatusLabel` import.
- **`frontend/src/features/tickets/DealQuotationPanel.jsx`** (de-duplication): removed the
  `createDepositNotice` mutation, the `depositPercentInput` state, the now-unused `useNavigate`
  import/`navigate` call, and the `canCreateDepositNoticeFromQuotation` import + its render
  branch. The `pr.status === 'QUOTATION_ACCEPTED'` box's header changed from "ยืนยันคำสั่งซื้อและ
  ออกใบแจ้งยอดเงินรับมัดจำ" to just "ยืนยันคำสั่งซื้อ"; the confirm-order button/flow is
  byte-for-byte unchanged; the post-confirm message gained a one-line pointer ("...ออกใบแจ้งยอด
  เงินรับมัดจำได้ที่ส่วน "มัดจำ" ด้านล่าง") instead of silently dropping the capability.
- **`frontend/src/features/tickets/salesViewScope.js`**: `SECTION_IDS`'s `delivery` doc-comment
  updated to name `DealFulfilmentPanel`/S4; `account`'s section map no longer sets `delivery:
  false` (so it now inherits `true` from `allTrue()`), with a doc-comment explaining why (deal
  fulfilment progress correlates with when the final-payment confirmation account is waiting on
  becomes due) — the panel itself stays read-only for account since none of its `can.*` flags ever
  resolve true for that role, and its Factory-PO step 3 is separately gated to `import`/`ceo`
  inside the panel, not by this section id.
- **`frontend/src/features/tickets/salesViewScope.test.js`**: split the old combined "account does
  NOT see pricing-request/procurement" test into "account does NOT see the pricing-request panel"
  (unchanged expectation) and a new "account DOES see the fulfilment/delivery section" test
  (`sections.delivery === true`), documenting the deliberate behaviour change.
- **`frontend/src/features/tickets/TicketDetailPage.test.jsx`**:
  - Added `api.tickets.issueImportRequest`/`markIrSent`/`markShipping`/`markGoodsReceived` and a
    new `api.procurement.listForPricingRequest` to the mocked `api`; `beforeEach` gained a default
    `api.procurement.listForPricingRequest.mockResolvedValue({ factoryPurchaseOrders: [] })` (same
    reasoning as S3's `depositNotices.listByTicket` default — `DealFulfilmentPanel` now mounts for
    every role but the never-reached `hr`).
  - Rewrote `'delivery modal: submitting with no line quantities...'`: the old assertions checked
    `TicketDetailPage`'s aria-invalid/`delivery-lines-panel` apparatus, which no longer exists;
    the new version asserts the same guard now reports via `showToast('error', ...)` and that a
    fixed quantity lets `api.tickets.recordDelivery` through.
  - `'renders delivery progress and hides record delivery without the action'` needed **no
    change** — its assertions (`getAllByText`, absent button) all still hold against the new
    panel's rendering, confirmed by the green run.
  - New `describe('deal fulfilment panel')` (4 tests): import issues an IR via
    `api.tickets.issueImportRequest`; account sees the section fully read-only (a deliberately
    unrealistic `availableActions` payload proves the role gate, not just action absence) with no
    Factory-PO subsection and `procurement.listForPricingRequest` never called; import sees the
    empty-PO first-run state once the deal has an order-confirmed PCR; import sees a real PO row
    with a working link to `/factory-purchase-orders/{id}`.

## Commands Run
```bash
cd frontend
npx eslint src/features/tickets/DealFulfilmentPanel.jsx src/features/tickets/DealQuotationPanel.jsx \
  src/features/tickets/TicketDetailPage.jsx src/features/tickets/salesViewScope.js \
  src/features/tickets/salesViewScope.test.js src/features/tickets/TicketDetailPage.test.jsx
npx vitest run src/features/tickets/TicketDetailPage.test.jsx src/features/tickets/DealStagePanel.test.jsx \
  src/features/tickets/salesViewScope.test.js src/api/contract.test.js
npm run lint
npm test -- --run
npm run build
VITE_USE_MOCKS=true npm run dev -- --port 5202 --strictPort   # live self-check
```

## Test / Build Results
- `npm run lint`: **PASS** — 0 errors, 1 pre-existing warning unrelated to this slice
  (`PayrollPage.jsx` exhaustive-deps, same one noted for S3/handoff 104).
- `npm test -- --run`: **PASS** — 48 test files, **430 tests**, all green (was 425 at the start of
  this slice; net +5 from the new `deal fulfilment panel` describe block, same test-file count as
  no new test file was created — `DealFulfilmentPanel.jsx` has no standalone `.test.jsx`, exactly
  like `DealQuotationPanel.jsx`, both covered only through `TicketDetailPage.test.jsx`).
- `npm run build`: **PASS** — `vite build` succeeds, no errors (`TicketDetailPage` chunk
  129.06 kB / 26.65 kB gzip, up from S3's 125.52 kB / 25.61 kB — expected, given the new
  import/mount plus the removed delivery-section code roughly offsetting each other).

## Live Mock Self-Check (VITE_USE_MOCKS=true)
Ran `VITE_USE_MOCKS=true npm run dev -- --port 5202 --strictPort` (same fresh-port workaround S3
used — port 5200 from `.claude/launch.json`'s `frontend-mock` config was occupied by an unrelated
process) and drove it through the Browser pane:
- **Sales Manager**, deal `PR-2026-0012` (Terminal 21 Property, no PCR, stage 11 "จัดซื้อและ
  นำเข้าสินค้า"): "การส่งมอบ / นำเข้า" renders both steps read-only (no action buttons — correct,
  sales_manager is neither import nor ceo), step 1 shows "ออก IR แล้ว" + the full substep chip
  strip, step 2 shows "0 / 1,000" / 0% / the per-item row / "ยังไม่มีรายการส่งมอบ"; step 3 (Factory
  PO) correctly absent (role gate). `มัดจำ` renders its own three DealDepositPanel steps
  unaffected — confirms the panel composes cleanly alongside S3's work.
- **Import**, deal `PR-2026-0006` (EU Trading Co., fulfilment status `FROM_STOCK`, no PCR): all
  three steps render with live actions — step 1 shows "สินค้าจากสต็อก" + a "จองสินค้าจากสต็อก"
  button; step 2 shows "0 / 200" with working "บันทึกการส่งสินค้า"/"ส่งมอบครบ" buttons; step 3
  shows the exact empty-first-run state the task asked for: "ยังไม่มีใบขอราคาที่ลูกค้ายืนยันคำสั่ง
  ซื้อสำหรับดีลนี้ — ยังสร้างใบสั่งซื้อโรงงานไม่ได้" (no crash, no broken table).
  **Clicked "บันทึกการส่งสินค้า" end to end**: the modal opened pre-filled with `source: STOCK`
  (matching `fs === 'FROM_STOCK'`), entered qty 50, submitted — `api.tickets.recordDelivery` fired,
  and the whole page re-rendered live: the state header's "การนำเข้า" chip flipped to "ส่งมอบบาง
  ส่วน", the pipeline's "ส่งมอบ:" badge and the panel's own progress bar both updated to "50 / 200"
  / 25%, the per-item row updated to "คงเหลือ 150", a new delivery-history row appeared
  ("22 ก.ค. 2569 · STOCK · 9: 50 · คุณนำเข้า พานิช"), and a matching "บันทึกการส่งสินค้า" event
  appeared in ประวัติการดำเนินการ — a real, live, multi-query-invalidation round trip, not just a
  static render. No console errors (`read_console_messages` came back empty) at any point.
- **Account**, the same deal `PR-2026-0006` after the delivery above: confirmed the section
  renders fully read-only (no "จองสินค้าจากสต็อก"/"บันทึกการส่งสินค้า"/"ส่งมอบครบ" buttons anywhere
  in the page text dump) and the Factory-PO step 3 is entirely absent — exactly the intended
  `import + account + ceo see it (read-only for account)` behaviour from `salesViewScope.js`'s new
  gate.
- **Not verified live**: the `DealQuotationPanel` de-duplication's actual `QUOTATION_ACCEPTED` +
  `orderConfirmedAt`-set render (no seed deal in the mock reaches that exact state without walking
  a fresh PricingRequest through its full chain — same limitation S3's own handoff already
  documented for the analogous `canCreateDepositNoticeFromQuotation` path). Covered instead by:
  (a) the code path being simple and directly inspected (the removed branch's only replacement is
  a static string), (b) the existing `TicketDetailPage.test.jsx` `'deal quotation panel'` describe
  block staying green unmodified (it never exercised the removed branch, confirming no regression
  to what it did cover), and (c) `DealDepositPanel`'s own already-live-verified (S3) copy of the
  exact same `createDepositNoticeFromQuotation` call remaining the sole implementation.
- The optional Factory-PO detail with actual PO rows populated (not just the empty state) was
  verified only via the new automated test (`api.procurement.listForPricingRequest` mocked to
  return one row) — not live-clicked, since no seed PO exists in the mock today (confirmed: 0
  factory purchase orders in the seed data, matching the task's own "unused in prod today" note).

## Authz Evidence
**No authorization/role-gate change in this slice** beyond the one presentation-level widening
already described: `salesViewScope.js`'s `delivery` section id now also resolves `true` for
`account` (was `false`). Per that file's own doc comment ("presentation only... never a security
boundary... hiding a section here never grants or removes a real permission"), this is a **UI
visibility change only** — it does not touch any `ROLE_PERMISSIONS` entry, any `hasAction`/`can.*`
predicate, or any backend/mock authorization check. Every `can.*` flag inside `DealFulfilmentPanel`
is copied verbatim from `TicketDetailPage`'s pre-existing gates (`isFulfilment = isImport || role
=== 'ceo'`) — account was already incapable of triggering any fulfilment action before this slice
(it could not even see the buttons) and remains incapable now (it can see the panel, but every
`can.*` flag still resolves `false` for its role, verified live above and by the new
`'account sees the section read-only'` test, which deliberately hands the panel a payload that
lists every fulfilment action as available and confirms none of the corresponding buttons render).
The Factory-PO step 3's `import`/`ceo`-only gate is new *code* (a fresh `['import', 'ceo'].includes(
role)` check inside the panel) but not a new *authorization* decision — it mirrors an
authorization boundary that already exists and is already enforced server-side (mocked as
`hasRole('import', 'ceo')` in `api/mockApi.js`'s `procurement.listForPricingRequest`); the
frontend check exists only to avoid firing a request that would 403, not to gate anything the
backend doesn't already gate. Verification here ran under `VITE_USE_MOCKS=true` only — no authz
claim beyond that, consistent with CLAUDE.md's standing rule.

## Decisions Made
- **`DealDepositPanel.jsx` was left completely untouched, per explicit instruction** — including
  its own doc comment, which still describes the `DealQuotationPanel` deposit-notice overlap as
  "documented, not fixed here." That overlap **is now fixed** (Task 2 of this slice), on the
  `DealQuotationPanel` side only, so `DealDepositPanel`'s comment is now slightly stale (it
  describes a still-live risk that no longer exists). Flagging this explicitly rather than
  silently leaving inaccurate documentation in place — see "Known Risks."
- **The optional Factory-PO block was made a numbered "step 3" of the same panel**, not a
  separate section, so the panel reads as one coherent fulfilment story even though step 3 is
  role-gated more narrowly than steps 1-2 (`import`/`ceo` only, vs. `sections.delivery`'s wider
  `import`/`account`/`ceo`/`sales`/`sales_manager` audience) — the same "one section, uneven
  internal visibility" shape `DealDepositPanel` already established (its steps 1/3 are
  account/CEO-owned, step 2 is sales-owned, all inside one `มัดจำ` section).
- **The Factory-PO query is gated on `role`, not on a `can.viewProcurement`-style computed flag**
  purely because there is no existing predicate for it anywhere in the codebase (unlike every
  other gate in this slice, which reuses an existing one) — a fresh, narrowly-scoped role check
  was the smallest addition that avoids the 403, consistent with "avoid inventing new authz
  surface" while still being honest that it is new code.
- **`totalOrdered`/`totalDelivered` stayed in `TicketDetailPage.jsx`** (not fully moved into the
  panel) because `DealStagePanel`'s own `deliveryProgress` prop still needs them — duplicating the
  `items.reduce(...)` computation inside `DealFulfilmentPanel` too (rather than threading it down
  as a prop) was judged the smaller coupling, matching how `DealDepositPanel`/`DealQuotationPanel`
  already each compute their own derived values from raw props instead of receiving pre-computed
  ones from the parent.

## Assumptions
- The task's "role-scoped (import + account + ceo see it; keep ceo/sales_manager pass-through)"
  was read as: `sections.delivery` (renamed in intent, kept in id) must resolve `true` for
  `import`, `account`, and `ceo` — already true for `import`/`ceo` (and `sales`/`sales_manager` via
  the unconditional pass-through, unaffected), so the only actual code change needed was dropping
  `account`'s `delivery: false` override. Not read as "make it false for sales" (sales already saw
  it before this slice, via the pass-through, and the task never asked to narrow sales's view).
- "reuse the substep logic already in DealStagePanel/stageMeta.js (PROCUREMENT_SUBSTEPS) where
  sensible" was read as: import the same `PROCUREMENT_SUBSTEPS` constant (not duplicate the labels
  as literals) so the two panels' chip strips can never drift on wording, while still rendering the
  strip locally in each panel (no shared `SubstepChips` component exists to import — same
  duplication precedent `StepRoleTag`/`StepNumber` already set between `DealDepositPanel` and this
  new panel).

## Known Risks
- **`DealDepositPanel.jsx`'s own doc comment is now stale** (see "Decisions Made" above): it
  describes the `DealQuotationPanel` deposit-notice-creation overlap as an unresolved, documented
  risk, but this slice resolved it (removed `DealQuotationPanel`'s copy). This is a harmless
  doc-only inconsistency — the code behavior itself is correct and duplication-free — but a future
  agent reading `DealDepositPanel.jsx` in isolation would get a stale impression. Left as-is
  because touching `DealDepositPanel.jsx` was explicitly out of scope for this session; flagging
  for a trivial follow-up doc fix rather than silently leaving it unmentioned.
- The Factory-PO detail block is **exercised only by the new automated test**, not by any real
  production data (0 factory purchase orders exist in the live/demo environment per the task's own
  framing) — if `ProcurementService`'s real DTO shape ever drifts from what `buildFactoryPurchaseOrderView`
  mocks, this panel's rendering of it would only be caught by `contract.test.js`'s method-surface
  check, not by a shape-level test, the same latent gap `ProcurementListPage`/`ProcurementDetailPage`
  already carry.
- `account` seeing `DealFulfilmentPanel` read-only is a **new cross-team visibility surface** (it
  did not exist before this slice) — low risk since every action gate still resolves `false` for
  that role (verified both live and by test), but worth a reviewer's explicit sign-off given it's
  the one deliberate widening in an otherwise consolidation-only slice.

## Things Not Finished
None for this slice's stated scope. Both Task 1 (`DealFulfilmentPanel`) and Task 2 (the
`DealQuotationPanel` de-duplication) are complete, tested, built, and live-checked. The only
carried-forward item is the pre-existing `DealDepositPanel.jsx` doc-comment staleness noted above,
left for a future trivial doc-only follow-up per the explicit "do not touch DealDepositPanel"
instruction for this session.

## Recommended Next Agent
None specified by this session's instructions — Slices S1-S4 of Phase 3 are now all complete on
this branch. A reviewer agent (not an implementation agent) is the natural next step before this
branch is proposed for merge, per CLAUDE.md's "Reviewer agents do not implement" convention.
```
