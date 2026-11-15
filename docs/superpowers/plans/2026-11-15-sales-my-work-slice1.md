# Sales "My work" + "Team overview" — Slice 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give reps a calm "My work" landing (quiet deals + 5-column S-code board) and give sales_manager/CEO a
team overview (per-rep table + stale list), with deals filled and advanced automatically from approved quotations.

**Architecture:** A new read-model package `th.co.glr.hr.salesboard` serves two endpoints built on the existing
`sales.ticket` / `sales.deal_activity` / `sales.ticket_event` / deal-quotation tables; the 15 `DealStage`s stay and
are grouped by a pure mapping in `DealStage`. The existing `isStale` is replaced by one shared "quiet" rule. The
frontend replaces the bodies of the existing `SalesOverview` / `ManagerOverview` landings and adds a team section to
`CeoOverview`.

**Tech Stack:** Spring Boot + NamedParameterJdbcTemplate + Flyway (Postgres), JUnit 5 +
`AbstractPostgresIntegrationTest`; React + Tailwind 4 + Vitest + Testing Library; `mockApi.js` mirror.

**Spec:** `docs/superpowers/specs/2026-11-15-sales-flow-redesign-design.md` (approved 2026-11-15).

**Scope of this plan:** spec §7.1–7.5 and the slice-1 line of §7.10. Visits (slice 2), expected payments + Express
upload (slice 3, blocked on a sample export) and the pipeline import (slice 4) get their own plans.

## Global Constraints

- Branch off `origin/develop` (`git town hack feat/sales-my-work-slice1`); never `main`.
- Test-first (CLAUDE.md 2026-09-30): each task's test is written and seen red for the right reason before code.
- Payroll, tax, SSO and commission math are untouched.
- Schema change is forward-only: one new migration, numbered **one above the highest `V` on `origin/develop` at
  execution time** (this checkout's uncommitted branch already uses V200/V201/V204 — check, don't guess).
- Every authz decision (who sees the team overview, whose deals a rep sees) ships a real-DB integration test with
  wrong-way-round cases and a mutation check.
- Every workflow change is stated in the PR body: (a) DEAL_DIRECT approval now advances the stage, (b) quotation
  approval fills empty party names, (c) the 7-day `isStale` becomes the 14-day quiet rule, (d) new endpoints.
- `mockApi.js` mirrors every new `hrApi.js` method with the same arity and a `// Mirrors <JavaClass>` header; its
  authz is not evidence.
- Quiet threshold: **14 days**, read from `app.sales.quiet-days` (default 14). *Deviation from spec §7.5 "CEO
  settings": there is no generic settings store today; a config property is the YAGNI version. A CEO-settings UI can
  follow.*
- UI copy is Thai, exactly as below; Tailwind utilities only, no new CSS file; tokens from `frontend/src/index.css`.

## Review Focus

1. **A deal with no activity, no quotation and no stage event** — quiet is measured from `ticket.created_at`, so a
   brand-new deal is not quiet on day 1 and is on day 15. (Task 2 test `brandNewDealIsNotQuiet_untilDay15`.)
2. **Lost / cancelled / on-hold / dormant deals** never appear as quiet and never on the board. (Task 2
   `onlyActiveDealsCanBeQuiet`, Task 4 `myWorkExcludesNonActiveDeals`.)
3. **A rep asking for another rep's deals** — `/api/sales/my-work` takes no owner parameter; a sales user only ever
   gets rows they created. (Task 4 `repCannotSeeAnotherRepsDeals`.)
4. **A rep-typed designer name** is never overwritten by quotation approval. (Task 3
   `approvalDoesNotOverwriteTypedDesigner`.)
5. **Approving a quotation on a deal already past the target stage** does not move it backwards. (Task 3
   `approvalNeverRegressesStage`.)

---

### Task 1: S-code mapping on `DealStage` and the stage-meta endpoint

**Files:**
- Modify: `backend/src/main/java/th/co/glr/hr/ticket/DealStage.java`
- Modify: `backend/src/main/java/th/co/glr/hr/ticket/DealStageMetaController.java` (record `DealStageMetaDto`)
- Modify: `frontend/src/api/mockApi.js` (`dealStages` fixture gains the two fields)
- Test: `backend/src/test/java/th/co/glr/hr/ticket/DealStageTest.java`

**Interfaces:**
- Produces: `public static String DealStage.repCodeOf(String stage)` → `"S1"|"S2"|"S3"|"S5"|"S6"|"S7"|"S8"|"S9"`
  or `null` for unknown; `public static int DealStage.boardColumnOf(String stage)` → `1..5` or `0`.
  `DealStageMetaDto` gains `String repCode, int boardColumn` (appended last).

- [ ] **Step 1: Write the failing tests** in `DealStageTest`:
  - `repCodeOf_matchesRepWorkbookCodes` — asserts the full table:
    LEAD_APPROACH,PRESENTATION→S1; SPEC_APPROVED→S2; QUOTE_DESIGN_SIDE,QUOTE_OWNER,OWNER_SIGNOFF→S3;
    AWAITING_BUYER→S5; QUOTE_BUYER,NEGOTIATION,ORDER_RECEIVED→S6; DEPOSIT_RECEIVED,PROCUREMENT→S7;
    DELIVERY_SCHEDULING→S8; DELIVERED,CLOSED_PAID→S9.
  - `boardColumnOf_groupsIntoFive` — S1→1, S2→2, S3/S5→3, S6→4, S7/S8/S9→5.
  - `everyStageHasARepCodeAndColumn` — every entry of `DealStage.ORDER` maps to non-null code and column 1..5.
  - `boardColumnIsMonotonicInPipelineOrder` — column never decreases along `ORDER`.
  - `unknownStage_returnsNullAndZero`.
- [ ] **Step 2: Run** `cd backend && ./mvnw -B -q test -Dtest=DealStageTest` — expect FAIL: cannot find symbol
  `repCodeOf` (compile error is acceptable *only* here because the method is the missing behaviour; add stubs
  returning `null`/`0` first if you want an assertion-level red).
- [ ] **Step 3: Implement** both methods as `Map.ofEntries` lookups beside `BUSINESS_CODE`; append `repCode`,
  `boardColumn` to `DealStageMetaDto` and its construction; mirror in mockApi's deal-stage fixture.
- [ ] **Step 4: Run** the same test — PASS; run `cd frontend && npx vitest run src/api/contract.test.js` — PASS.
- [ ] **Step 5: Commit** `feat(sales): map deal stages to reps' S1–S9 codes and five board columns`

### Task 2: One "quiet" rule replaces `isStale`

**Files:**
- Create: `backend/src/main/java/th/co/glr/hr/salesboard/QuietRule.java`
- Modify: `backend/src/main/java/th/co/glr/hr/ticket/TicketRepository.java:2102-2115` (`isStale` delegates)
- Modify: `backend/src/main/resources/application.yml` (`app.sales.quiet-days: 14`)
- Modify: `backend/src/main/java/th/co/glr/hr/ticket/TicketSummaryDto.java:70` (Javadoc only: 14-day rule)
- Test: `backend/src/test/java/th/co/glr/hr/salesboard/QuietRuleIntegrationTest.java`

**Interfaces:**
- Produces: `@Component QuietRule` with `int quietDays()`, `String lastTouchedSql(String ticketAlias)` (a SQL
  expression yielding the deal's last-touched `timestamptz`), and `boolean isQuiet(long ticketId)`.
  Last touched = `GREATEST(ticket.created_at, max(deal_activity.created_at), max(ticket_event.created_at) where
  kind IN ('STAGE_CHANGED','QUOTATION_ISSUED'))`. Quiet = lifecycle `ACTIVE` **and** `boardColumnOf(stage) < 5`
  **and** last touched `< now() - quietDays`.

- [ ] **Step 1: Write the failing integration test** (extends `AbstractPostgresIntegrationTest`, annotated itself —
  the gate is not inherited):
  - `brandNewDealIsNotQuiet_untilDay15` — created_at now-13d → false; now-15d → true.
  - `activityWithin14DaysClearsQuiet` — created 60d ago, activity 3d ago → false.
  - `stageChangeEventClearsQuiet` and `quotationIssuedEventClearsQuiet` — event 3d ago → false.
  - `otherEventKindsDoNotClearQuiet` — a `COMMENTED`-kind event 3d ago, nothing else → true.
  - `onlyActiveDealsCanBeQuiet` — LOST / ON_HOLD / DORMANT deal untouched 60d → false.
  - `deliverAndCollectColumnIsNeverQuiet` — stage DELIVERED untouched 60d → false.
  - `ticketRepositoryIsStaleUsesTheSameRule` — `TicketRepository.isStale` agrees with `QuietRule.isQuiet` on the
    60d/3d cases.
- [ ] **Step 2: Run** `./mvnw -B -q test -Dtest=QuietRuleIntegrationTest -Dtest.fork.count=1` — expect FAIL on the
  7-day-vs-14-day and event cases (the old rule flags at day 8 and ignores events).
- [ ] **Step 3: Implement** `QuietRule` (reads `app.sales.quiet-days`); `TicketRepository.isStale` calls
  `quietRule.isQuiet(ticketId)`.
- [ ] **Step 4: Run** the test — PASS; also `-Dtest='Ticket*Test'` — PASS (fix any test pinned to 7 days by
  updating its expectation and naming it in the PR body).
- [ ] **Step 5: Commit** `feat(sales): replace 7-day isStale with the 14-day quiet rule`

### Task 3: DEAL_DIRECT approval advances the stage and fills empty party names

**Files:**
- Modify: `backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationService.java` (`approve`, DEAL_DIRECT
  branch, after the `QUOTATION_ISSUED` event ~line 1075)
- Modify: `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java` (new method beside
  `advanceStageForCustomerQuotationIssue`, ~line 1746)
- Modify: `backend/src/main/java/th/co/glr/hr/ticket/TicketRepository.java` (one `UPDATE … SET x = COALESCE(x, :v)`)
- Test: `backend/src/test/java/th/co/glr/hr/dealquotation/DealDirectApprovalFillsDealIntegrationTest.java`

**Interfaces:**
- Produces: `TicketService.applyApprovedQuotation(long ticketId, String recipientType, String recipientLabel,
  UserPrincipal actor)` — calls the existing `advanceStageForCustomerQuotationIssue`, then writes `recipientLabel`
  into `designer_name` / `owner_name` / `buyer_name` (by `recipientType`) **only if that column is null or blank**.
  `TicketRepository.fillPartyNameIfBlank(long ticketId, String column, String value)` with `column` restricted to
  those three names (switch, not string concatenation from input).

- [ ] **Step 1: Write the failing integration test:**
  - `approvingDesignerQuotationAdvancesToQuoteDesignSide` — deal at SPEC_APPROVED → QUOTE_DESIGN_SIDE.
  - `approvalNeverRegressesStage` — deal at NEGOTIATION, approve DESIGNER quotation → still NEGOTIATION.
  - `approvalFillsBlankDesignerName` — designer_name null, recipient_label "คุณเอ" → "คุณเอ".
  - `approvalDoesNotOverwriteTypedDesigner` — designer_name "คุณบี" → stays "คุณบี".
  - `ownerAndBuyerRecipientsFillTheirOwnColumn`.
  - `lostDealIsNotAdvancedOrFilled`.
- [ ] **Step 2: Run** `-Dtest=DealDirectApprovalFillsDealIntegrationTest` — FAIL: stage unchanged (today the
  DEAL_DIRECT hook is intentionally empty).
- [ ] **Step 3: Implement** the two methods; call `ticketService.applyApprovedQuotation(approved.ticketId(),
  approved.recipientType(), approved.recipientLabel(), actor)` in the DEAL_DIRECT branch, inside the same
  transaction. Replace the "Intentionally empty here" comments at ~262 and ~756 with a pointer to this method.
- [ ] **Step 4: Run** the test plus `-Dtest='DealQuotation*Test,PricingRequestQuotation*Test'` — PASS.
- [ ] **Step 5: Commit** `feat(sales): approved quotations advance and fill their deal`

### Task 4: `GET /api/sales/my-work` (rep) and `GET /api/sales/team-overview` (manager/CEO)

**Files:**
- Create: `backend/src/main/java/th/co/glr/hr/salesboard/SalesBoardController.java`
- Create: `backend/src/main/java/th/co/glr/hr/salesboard/SalesBoardService.java`
- Create: `backend/src/main/java/th/co/glr/hr/salesboard/SalesBoardRepository.java`
- Create: `backend/src/main/java/th/co/glr/hr/salesboard/SalesBoardDtos.java`
- Create: `backend/src/main/resources/db/migration/V<next>__ticket_expected_quarter.sql`
- Modify: `backend/src/main/java/th/co/glr/hr/ticket/TicketController.java` + `TicketService` (`PUT
  /{id}/tracking` accepts `expectedQuarter`)
- Modify: `docs/api/response-fields.json` (new DTOs, if the contract script requires it)
- Test: `backend/src/test/java/th/co/glr/hr/salesboard/SalesBoardScopeIntegrationTest.java`,
  `backend/src/test/java/th/co/glr/hr/salesboard/SalesBoardServiceTest.java`

**Interfaces:**
- Consumes: `DealStage.repCodeOf/boardColumnOf` (Task 1), `QuietRule.lastTouchedSql/quietDays` (Task 2).
- Produces:
  - Migration: `ALTER TABLE sales.ticket ADD COLUMN expected_quarter TEXT CHECK (expected_quarter IS NULL OR
    expected_quarter ~ '^\d{4}-Q[1-4]$');`
  - `record BoardDeal(long ticketId, String title, long ownerId, String ownerName, String salesStage, String repCode,
    int boardColumn, BigDecimal value, String designerName, Integer winProbability, String expectedQuarter,
    Instant lastTouchedAt, int daysQuiet, boolean quiet)` — `value` = `grandTotal` of the deal's latest APPROVED or
    ISSUED quotation, else null.
  - `record MyWork(List<BoardDeal> followUp, List<BoardDeal> board, int quietDays)` — `followUp` = quiet deals
    ordered by `daysQuiet * coalesce(value,0) DESC, daysQuiet DESC`, max 5; `board` = all ACTIVE deals.
  - `record RepRow(long employeeId, String name, int openDeals, BigDecimal openValue, int quietDeals, Instant
    lastUpdateAt)`; `record TeamOverview(List<RepRow> reps, List<BoardDeal> stale, int quietDays)` — `stale` ordered
    as `followUp`, max 20.
  - `GET /api/sales/my-work` → `MyWork` for the caller's own deals (`created_by_id = actor.id()`), any role in
    `{sales, sales_manager, ceo}`; 403 otherwise.
  - `GET /api/sales/team-overview` → `TeamOverview`; `{sales_manager, ceo}` only; 403 for every other role.

- [ ] **Step 1: Write the failing tests.** Unit (`SalesBoardServiceTest`): role decision — `sales` → own scope,
  `account`/`import`/`hr` → 403 for my-work; only `sales_manager`/`ceo` pass team-overview. Integration
  (`SalesBoardScopeIntegrationTest`, real Postgres, two reps A and B with deals each):
  - `repCannotSeeAnotherRepsDeals` — A's my-work contains zero of B's ticket ids.
  - `salesRoleIsRefusedTeamOverview` — A → 403.
  - `accountAndImportAreRefusedBoth`.
  - `managerTeamOverviewHasOneRowPerRepWithCorrectCounts`.
  - `myWorkExcludesNonActiveDeals` — LOST/CANCELLED not on board or followUp.
  - `followUpIsCappedAtFiveAndOrderedByValueTimesDays`.
  - `valueIsLatestApprovedQuotationTotal_nullWhenNone`.
  - `expectedQuarterRejectsMalformedValue` — `"2026Q4"` → 400 via tracking update.
- [ ] **Step 2: Run** `-Dtest='SalesBoard*Test'` — FAIL: 404 on `/api/sales/my-work`.
- [ ] **Step 3: Implement** controller → service (role decision) → repository (one SQL per endpoint, scope in the
  `WHERE`, not filtered in Java).
- [ ] **Step 4: Run** — PASS. **Mutation check:** drop the `created_by_id = :actorId` predicate → only
  `repCannotSeeAnotherRepsDeals` goes red; allow `sales` into team-overview → only `salesRoleIsRefusedTeamOverview`
  goes red; revert to an empty diff. Record both in the PR body.
- [ ] **Step 5: Commit** `feat(sales): my-work and team-overview endpoints`

### Task 5: Frontend API + mock mirror

**Files:**
- Modify: `frontend/src/api/hrApi.js` (new `salesBoard` namespace; `API_ROUTES.salesBoard`)
- Modify: `frontend/src/api/mockApi.js` (`salesBoard` namespace, `// Mirrors SalesBoardService`)
- Modify: `frontend/src/api/queryKeys.js`
- Test: `frontend/src/api/mockApi.salesBoard.test.js`

**Interfaces:**
- Produces: `hrApi.salesBoard.myWork()` → `MyWork`; `hrApi.salesBoard.teamOverview()` → `TeamOverview`;
  `queryKeys.salesBoard.myWork`, `queryKeys.salesBoard.team`. Field names exactly as Task 4's records (camelCase).

- [ ] **Step 1: Write the failing test** — mock `myWork()` as a sales user returns only that user's deals, followUp
  ≤ 5 with the Task 4 ordering, each deal has `repCode`/`boardColumn`; `teamOverview()` as sales rejects with 403
  shape. (States in a comment that mock authz is not evidence.)
- [ ] **Step 2: Run** `cd frontend && npx vitest run src/api/mockApi.salesBoard.test.js src/api/contract.test.js` —
  FAIL (contract test flags the missing mirror).
- [ ] **Step 3: Implement** both sides; mock computes quiet with the same 14-day rule from fixture timestamps.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(sales): salesBoard API client and mock mirror`

### Task 6: Rep landing — "งานของฉัน"

**Files:**
- Create: `frontend/src/features/salesBoard/MyWork.jsx`, `FollowUpList.jsx`, `DealBoard.jsx`
- Modify: `frontend/src/features/dashboard/SalesOverview.jsx` (renders `<MyWork />` as its body)
- Test: `frontend/src/features/salesBoard/MyWork.test.jsx`, `DealBoard.test.jsx`

**Interfaces:**
- Consumes: Task 5 client; existing `hrApi.tickets.addActivity(id, {kind:'CALL', activityDate, note})` and the
  existing `MarkLostModal`.
- Produces: `<DealBoard deals={BoardDeal[]} />` (reused by Task 7 is not required — keep it rep-only).

Copy (exact): page heading `งานของฉัน`; section `ควรตามต่อ` with sub-line `ดีลที่เงียบเกิน {quietDays} วัน`; empty
state `ไม่มีดีลค้าง — ทุกดีลมีความเคลื่อนไหวใน {quietDays} วันที่ผ่านมา`; buttons `โทรแล้ว`, `ปิดดีลนี้`; board
columns `เสนอสินค้า · S1`, `สเปคผ่าน · S2`, `เสนอราคาแล้ว · S3–5`, `รอมัดจำ · S6`, `ส่งของ · เก็บเงิน · S7–9`; a
small `อัปเดตอัตโนมัติ` tag on columns 3–5. "Book a visit" is **not** in this slice (slice 2).

- [ ] **Step 1: Write the failing tests:** renders quiet deals with value and `เงียบ {n} วัน`; `โทรแล้ว` calls
  `addActivity` with kind `CALL` and the deal leaves the list after refetch; `ปิดดีลนี้` opens `MarkLostModal`
  (reason still required); empty state copy; board puts a QUOTE_OWNER deal in column 3 and DELIVERED in 5; card
  shows designer, `expectedQuarter`, `winProbability%` when present and omits them when null; no horizontal overflow
  class at 375px (columns stack/scroll inside their own container).
- [ ] **Step 2: Run** `npx vitest run src/features/salesBoard` — FAIL (module not found → then assertion red after
  empty component stubs).
- [ ] **Step 3: Implement** with Tailwind; reuse the card/button patterns `SalesOverview.jsx` already uses (no shared component library exists).
- [ ] **Step 4: Run** — PASS; update `SalesOverview.test.jsx` expectations to the new body.
- [ ] **Step 5: Commit** `feat(sales): rep My work landing`

### Task 7: Manager / CEO — "ภาพรวมทีมขาย"

**Files:**
- Create: `frontend/src/features/salesBoard/TeamOverview.jsx`
- Modify: `frontend/src/features/dashboard/ManagerOverview.jsx` (body → `<TeamOverview />`, keep any existing
  approval-queue block below it)
- Modify: `frontend/src/features/dashboard/CeoOverview.jsx` (add `<TeamOverview />` as the first section when
  `SALES_ENABLED`)
- Test: `frontend/src/features/salesBoard/TeamOverview.test.jsx`

Copy (exact): heading `ภาพรวมทีมขาย`; tiles `ดีลที่ถืออยู่`, `ดีลเงียบเกิน {quietDays} วัน`; table columns `พนักงานขาย`,
`ดีล`, `มูลค่า`, `ดีลเงียบ`, `อัปเดตล่าสุด`; list heading `ดีลที่ควรโทรตาม`. Cash and visit tiles are **not** in this
slice. Remove the old `฿0.00` team-sales tile and the blanket "ต้องติดตาม" flag from `ManagerOverview`.

- [ ] **Step 1: Write the failing tests:** one row per rep with counts from the fixture; stale list ordered as served;
  rep name links to `/tickets?owner=…` is **not** added (no such filter exists) — row is plain text; the ฿0.00 tile
  is gone; numbers use `th-TH` grouping with `฿`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS; update `ManagerOverview.test.jsx` / `CeoOverview` tests.
- [ ] **Step 5: Commit** `feat(sales): team overview for sales manager and CEO`

### Task 8: Pre-PR verification and PR

- [ ] **Step 1:** `cd frontend && npm run lint && npm test && npm run build` — all pass.
- [ ] **Step 2:** `cd backend && ./mvnw -B clean verify` (local toolchain: `-Dtest.fork.count=1`) — pass; state
  whether integration tests ran.
- [ ] **Step 3:** Run `frontend-mock` and screenshot `/` as sales and as sales_manager at 375 and 1440; compare with
  the approved mockup artifact.
- [ ] **Step 4:** Open the PR into `develop` with the six CLAUDE.md sections; list the four workflow changes from
  Global Constraints, the config-property deviation, and both mutation-check results.

---

## Self-review notes

- Spec coverage: §7.1 → T1; §7.2 → T3; §7.3 items 1 & 4 → T6 (items 2–3 deferred to slices 2–3 by design);
  §7.4 items 1 (partial), 2, 3 → T4/T7 (item 4 and cash/visit tiles deferred); §7.5 → T2; §9 readiness gate —
  `autoAdvanceStage` already bypasses it, nothing to change; lost-reason kept → T6.
- Not in this slice: visits, expected payments, Express upload, import, CEO-settings UI for the threshold.

---

## PR notes (final review)

State these in the PR body; each is an intended contract change, not an accident (CLAUDE.md, sales flow redesign).

**Merge with a MERGE COMMIT, not a squash.** Three child branches (`feat/sales-visits`, `feat/sales-pipeline-import`
and, through visits, `feat/sales-cash`) are stacked on this one; squashing orphans them. The history also carries
intermediate commits that are red on their own (see "Bisect" below), which a squash would hide and a rebase-merge
would not make any safer.

**Endpoints (new, additive)**
- `GET /api/sales/my-work` — `sales`, `sales_manager`, `ceo`; the caller's own ACTIVE deals only.
- `GET /api/sales/team-overview` — `sales_manager`, `ceo`; every rep. Enforced in `SalesBoardService`, proven by the
  real-DB `SalesBoardScopeIntegrationTest` (not by the mock).
- `PUT /api/tickets/{id}/tracking` accepts `expectedQuarter` (`YYYY-Qn`).
- `TicketSummaryDto.stale` now means the 14-day QuietRule (was the 7-day `isStale`); no field added or removed.

**Schema**
- V209: `sales.ticket.expected_quarter` (nullable, CHECK `YYYY-Qn`).
- V210: DATA migration, needs the owner's deploy approval. Moves ACTIVE deals whose latest customer-facing quotation is
  UNSPECIFIED to QUOTE_BUYER (else nearest on-route stage before it), forward-only, skipping deals with a manual
  backward move; writes a STAGE_CHANGED event stamped at the approval time. Expected volume on production:
  123 deals moved and 100 designer names filled from the designer directory. Owner approval of the rule: 2026-11-15.
  V210 is unmerged and applied only on test databases, so it was edited in place during review (including the
  tested two-statement rollback in its header). `buyer_name` is deliberately never written: the BUYER party V204
  created for UNSPECIFIED quotations is the bill-to customer, never a name source.

**Workflow**
- UNSPECIFIED-recipient approvals/issues advance like BUYER (QUOTE_BUYER) — an ongoing rule, not only the backfill.
- Quiet rule 7 -> 14 days (`app.sales.quiet-days`, default 14); the old 7-day `isStale` is gone.
- Approving/issuing a customer-facing quotation advances the deal forward-only and fills a BLANK `designer_name`
  (from the quotation's D.Co.); `buyer_name` only for a BUYER-addressed quotation whose party is a real bidder;
  `owner_name` never. A typed name is never overwritten.
- Quiet ranking weights only THB values (non-THB deals rank by days alone).

**Bisect.** `bdd7da87`, `fc7d32e6` and possibly `c26d4bf1` are red on their own (the API client / landing / endpoints
land before the pieces they reference). Do not bisect through them without the later fixes.

**Not verified here.** The mock's authorization is not evidence; the role gates are covered by
`SalesBoardScopeIntegrationTest`. V210 must be dry-run on production (the `target` CTE as a SELECT) before the deploy.
