# ขั้น 4/5/8 come from a quotation — and a second quotation is a version

Owner rulings, 2026-10-16 (Ploy). Implemented on `feat/quote-stage-gates-and-versioning` (f8b98dc4, 2026-10-17); D-5 step 2 ruled 2026-10-17 (a revision keeps its parent's recipient).

## Intent

A sales rep on ขั้น 3 (SPEC_APPROVED) can today press `เลื่อนไป: 4. เสนอราคาผู้ออกแบบ` and move the
deal into the quote stage by hand, having priced nothing and quoted nobody. That is the bypass this
work closes: **the quote stages are reached by quoting someone, never by clicking.**

The same ruling has a second half. Once the only way into ขั้น 4 is a quotation, the quotation
document itself has to carry versions properly — because every later conversation with that same
recipient ("the customer wants more tiles") is a new version of the same offer, not a new offer.
Before this work those landed as unrelated documents with unrelated numbers.

## Why now

Both halves were already half-built and then left:

- The recipient → stage rule is the owner's own **D1** (`.design/quotation-deal-link`, approved
  2026-09-30). Its two hook points were deliberately left empty when stage ownership was handed to
  the `deal-route-staging` work, which never filled them; this work filled them.
- Versioning was believed switched **off** for quotations that come out of a คำขอราคา
  (the guard then called `requireStatusMachineEnabled`, now `requireReorderAllowedForOrigin`: "จะเปิดใช้งานในระยะถัดไป"). **Corrected 2026-10-17, see "D-5 as
  verified against the code" below:** `createRevision` no longer calls
  that guard at all; a PRICING_REQUEST-origin quotation can already be revised,
  and is already numbered deal-scoped. What remained undone was narrower, and one piece of it
  contradicts behaviour the code implements on purpose.

## Goals

1. No manual move into QUOTE_DESIGN_SIDE (ขั้น 4), QUOTE_OWNER (ขั้น 5) or QUOTE_BUYER (ขั้น 8),
   from any surface, including a direct API call.
2. Creating a quotation addressed to a recipient moves the deal to that recipient's stage, at
   **creation** time, forward-only.
3. A second quotation to a recipient already quoted is the **next version of the same base number**
   (`QT-2026-0014-2`), not a new document, and it leaves the stage where it is.
4. Every version is approved before it goes out — ผจก.ขาย or CEO, on the quotation page.

## Non-goals

- Changing who sets the price. The CEO still sets ราคาตั้ง / the approved selling price through the
  คำขอราคา; `DiscountApprovalService` stays CEO-only for below-minimum discounts.
- The all-stock route. Already built and shipped (V194): an all-สต็อกในคลัง คำขอราคา goes
  `SUBMITTED → READY_FOR_CEO_REVIEW` at submit and `pickup` refuses it. **No stock exception is
  needed anywhere in this work** — an all-stock deal still has a คำขอราคา, a CEO price and a
  quotation, and that quotation is what moves the stage.
- `createReorder` ("สั่งเหมือนเดิม") for PCR-origin quotations. Stays refused.
- The stock-lines slices held at V200. Untouched.

## Anti-goals

Things that would make this a failure even if it worked:

- Breaking the automatic advance. `autoAdvanceStage` must keep moving deals; only the **manual**
  path is being closed.
- A deal that can no longer reach ขั้น 4 at all, or one parked on ขั้น 4 with no quotation.
- Version 2 going out to a customer without an approval.
- Sales acquiring a way to move a customer-facing **price** without the CEO. The 2026-08-13
  reissue-through-CEO-chain ruling stands.

## Decisions

### D-1 — Approval split: CEO owns the price, ผจก.ขาย owns the document

Confirmed against the code, not assumed:

- `DealQuotationService.APPROVE_ROLES = {sales_manager, ceo}` — approving/rejecting the quotation.
- `DiscountApprovalService.CEO_ROLES = {ceo}` — a below-minimum discount.

So a version 2 that adds quantity at the CEO's already-approved unit price is approved by ผจก.ขาย
and needs **no new CEO pricing decision**. Cutting the unit price is a concession and still routes
to the CEO through the existing discount gate. This is what reconciles the new ruling with
2026-08-13: that ruling protects the *price*, and the price is not what a quantity change moves.

### D-2 — The stage moves at CREATION, not approval

Owner's correction, 2026-10-16, superseding their own earlier answer in the same conversation. D1
stands as originally approved.

Consequence, stated rather than hidden: a **DEAL_DIRECT** quotation addressed to ผู้ออกแบบ will move
a deal to ขั้น 4 with no CEO price anywhere in the picture. That is the กรอกราคาเอง route the owner
restored in D2, and `DirectQuotationLocks.requireNoLivePricingRequest` keeps the two routes mutually
exclusive per deal, so it is a second approved route rather than a hole in the first one.
**Owner confirmed this explicitly, 2026-10-16:** a quotation written directly on the quotation page
may start or move the deal to its recipient's stage.

"Start" is the second half of that, and it is a real case, not a rewording of "move": a deal
**created from** a quotation (`/quotations/new` → สร้างดีลใหม่) lands directly on the recipient's
stage instead of at ขั้น 1. No extra mechanism is needed — a brand-new deal sits at index 0, so the
recipient's stage is forward of it and the same forward-only advance applies. This supersedes
#1083's "promote lands at S10": the deal is visible at ขั้น 4/5/8 immediately, and ยืนยันคำสั่งซื้อ
remains the separate action that takes it to S10 (D3).

**The edge case this creates.** `autoAdvanceStage` silently returns when the target is off-route for
the deal's `entryChannel`, and `DealRoute` has `OWNER_DIRECT` skipping `QUOTE_DESIGN_SIDE`. So a
designer-addressed quotation on an owner-direct deal moves nothing, and the deal sits at ขั้น 1 with
a quotation on it. That silent no-op is the existing designed behaviour — `autoAdvanceStage`'s own
comment says an off-route target must never throw, because throwing would fail the quotation
creation that triggered it — and this work does not change it. A deal created from a quotation
carries no channel yet (`UNSPECIFIED` has no route, so every stage is reachable), which is the
common path; the collision needs an already-channelled deal. Pinned by a test either way so the
behaviour is a decision on record rather than an accident.

### D-3 — A fact gate, not a permission change

The quote stages stay in `DealStage.SALES_TARGET_STAGES`. The refusal goes in
`TicketService.requireStageFactsHold`, alongside the four that are already there, and reads "this
deal has no quotation addressed to that recipient."

Why this and not removing them from the write-gate set: a 403 says "you may not", which is wrong —
the rep may, once they have quoted someone. A 409 fact gate says "there is nothing to be on this
stage for", which is the truth, and it matches the shape of the four existing gates
(`ORDER_RECEIVED`, `DEPOSIT_RECEIVED`, `DELIVERED`, `CLOSED_PAID`).

It is also provably safe for the automatic path: `autoAdvanceStage` writes straight through
`tickets.updateSalesStage` and never calls `requireStageMoveAllowed`, so the gate cannot reach it.

**What counts as "a quotation addressed to that recipient"** (builder's call, settled here): a
quotation on this deal whose `recipient_type` maps to the target stage, in any `doc_status` except
`CANCELLED`. A cancelled-only history must not hold a deal on a quote stage. A `DRAFT` does count —
under D-2 a draft is exactly what moved the deal there in the first place, so excluding it would
make the gate contradict the advance.

**Owner ruling 2026-10-17 — confirmed, after the consequence was spelled out.** Excluding `CANCELLED`
means cancelling erases the fact, so this sequence ends in a refusal:

1. quote the designer → ขั้น 4; 2. cancel that quotation; 3. quote the owner → ขั้น 5;
4. try to correct the stage back to ขั้น 4 → **409 ยังไม่มีใบเสนอราคาถึงผู้ออกแบบ**, even though the
designer was quoted and the customer saw that document.

The owner confirmed this is intended: a cancelled document is not evidence, and the way back is to
write a new quotation. Recorded because **this decision's justification is narrower than the rule it
justifies** — the paragraph above argues for a 409 over a 403 on the grounds that "once the document
exists the rep may still correct the stage by hand", and that is true only while the document is
uncancelled. Anyone revisiting this should know the narrowing was seen and accepted, not missed.

The two alternatives considered and declined: counting cancelled rows for the gate only (reverses
part of this ruling), and counting them only when the deal is already on or past that stage (precise,
but more logic than the problem earns).

**The rep is not stranded on ขั้น 3.** The way forward is a document, and both routes are already on
the page: `สร้างคำขอราคา` is the ขั้น-3 CTA (`ef9b17dc`), and D2's `สร้างใบเสนอราคา` offers the
explicit ผ่านคำขอราคา / กรอกราคาเอง choice. Nothing in this work removes a way out of ขั้น 3; it
removes the way that skipped the document.

### D-4 — One backend change removes the CTA from four screens

`DealStage.AUTO_ADVANCED` names the stages the system advances into, and since this ruling also the
fact-gated quote stages (see the `DealStage` Javadoc: membership and the `requireStageFactsHold`
refusal are two halves of one rule). It is served to the client. The frontend's `canAdvance` already
requires `!next.auto`, and `DealStagePanel` reads every gate off the server's `stageDecisions` rather
than recomputing it. So adding the three quote stages to `AUTO_ADVANCED` withdraws the one-click
advance from the surfaces that ride on `!next.auto`; the verified breakdown is below.

#### Verified 2026-10-17, with two corrections to the original claims

The conclusion holds — all four surfaces do stand down from the one `AUTO_ADVANCED` edit — but two
of the sentences justifying it were wrong, and one surface stands down for a different reason than
this decision claims.

**Correction 1: `auto` is NOT served on `stageDecisions`.** `StageDecisionDto` is
`(stage, no, allowed, requiresReason, blockedReason, onRoute)` — no `auto` field. The flag reaches
the client only on the stage *catalog* (`DealStageMetaController.DealStageMetaDto`), and
`stageCatalog.js`'s `nextOnRoute`/`findStage` merge it onto the `next` object the components read.
Nothing breaks, but "it also appears in `stageDecisions`" was not a fact to reason from.

**Correction 2: `UpdateStageModal` never reads `auto` at all.** `UpdateStageModal.jsx:31` is
`stageDecisions.filter((d) => d.allowed)`. So the stage picker drops the quote stages because
**D-3's fact gate** sets `allowed: false`, not because of D-4. The two decisions each cover two of
the four surfaces; neither covers all four alone. That matters for anyone who later reverts one of
them thinking the other still holds the line.

The three that genuinely ride on `!next.auto`, all re-checked in the source:

| Surface | Real gate |
| --- | --- |
| Guided-advance opener (the plain inline button had already moved to the ⋯ menu) | `DealStagePanel.jsx:133` `showGuidedOpener` → `:129` `readinessBlocked` → `!next.auto` |
| ⋯ menu item | assembled at `TicketDetailPage.jsx:1435` on `canAdvance` (`:1160`); the click re-checks `DealStagePanel.jsx:122`'s own `canAdvance` (`:192`) — gated twice |
| Sticky primary CTA | `TicketDetailPage.jsx:1298`, on `canAdvance` (`:1160`) or `advanceAwaitsReadiness` (`:1164`) |

`salesActions.js` is not an independent authority: it emits `ADVANCE_STAGE` only for
`PRE_SPEC_STAGES` (`LEAD_APPROACH`, `PRESENTATION`), neither of which is auto, and the CTA branch it
feeds is re-gated by `canAdvance` before rendering.

#### The coverage this decision permanently destroys

Stated here because it is a consequence of the ruling, not of any test rewrite, and a future reader
will otherwise read the surviving tests as a deliberate weakening.

`TicketDetailPage.test.jsx`'s `overflow เลื่อนไป follows the deal route` block existed to prove that
menu names the route's next stage rather than the pipeline's. **It can no longer distinguish
route-aware from route-blind, and no fixture can.** The route-variable stages are S4–S8
(`DealRoute.ROUTE_VARIABLE`); D-4 makes S4, S5 and S8 auto, leaving only S6 and S7 manual; and every
channel that skips anything lands on an auto stage:

- `OWNER_DIRECT` skips S4, so from S3 its next on-route stage is S5 — auto.
- `BUYER_DIRECT` skips S4–S7, so from S3 its next on-route stage is S8 — auto.
- `DESIGNER_LED` skips nothing at all.

S6 and S7 are manual, but they are stages `BUYER_DIRECT` *skips*, never lands on. So there is no
(channel, stage) pair left where the menu offers a route-skipping advance, and the assertion has
nothing to bite on. Those tests are now an `it.each` asserting the item is **absent** across all
four channels, plus one positive control at S5 → S6.

The underlying behaviour is still guarded, just not there: `ถัดไป follows the route` reads
`deal-stage-next`, which renders the route's next stage regardless of `auto`. **If route-awareness
ever regresses, that is the test that must catch it** — do not restore the menu assertions by
marking a quote stage manual again.

### D-5 — One revise door; the system routes the approval

Owner chose this shape over "fix both doors", 2026-10-16.

**The problem being designed away.** Two revise doors at two different levels — Door A
(`createRevision`, on the quotation) and Door B (`createCustomerChangeRevision`, on the คำขอราคา) —
where Door B minted a fresh base and so cost the version number. Per-deal numbering removed that
cost: see `VERSIONING.md` (B-2), which also records why the two doors are now harmless and are not
collapsed into one.

**The redesign.** The quotation becomes the spine. One action — `สร้างฉบับแก้ไข` — on any issued
quotation, available to the owning rep:

1. Mints the next version immediately: `{base}-{n+1}`, DRAFT, `parent_quotation_id` = source,
   numbered through the **deal-scoped** `nextRevisionNo(ticketId, base)`.
2. Recipient is locked to the parent's, mirroring the GLA-102 guard that already forces this on a
   request child. Same recipient → same pipeline index → the forward-only advance leaves the stage
   alone, which is goal 3 for free.
3. Lines are copied verbatim, discount included. **Owner ruling 2026-10-17: the discount is CARRIED
   FORWARD** (`insertCopyOf`/`toNewItemFromDto`: "a copy, not a recalculation"). See the ruling note
   at the end of this decision.
4. The rep edits. Nothing is declared up front.
5. On submit the approval is routed by **what actually changed**, not by which door was opened:
   - document fields, terms, validity, contact, or a **quantity change at the approved unit price**
     → PENDING_APPROVAL → ผจก.ขาย or CEO approves → ISSUED. (D-1.)
   - a unit price below the approved minimum → the existing `DiscountApprovalService` CEO gate,
     already wired through `raiseDiscountApprovalsIfNeeded`. No new mechanism.
6. The parent is superseded only once the new version is **issued** — the existing ancestor walk
   already does this.

**The signal the classifier runs on already exists.** `withDecisionLink` stores
`pricingRequestItemId` / `pricingDecisionItemId` on every quotation item created from a pricing
decision, so "this line has a CEO-approved price" is a column, not an inference.

**Scope cut, deliberate** (builder's call, flag for the owner to override): a version that adds a
line with **no CEO-approved price at all** — a product never costed on this deal — is **refused**
with a message telling the rep to raise a new คำขอราคา. It is not silently allowed and it does not
get a wrong number.

Why cut it: the owner's own example is "ลูกค้าอยากได้กระเบื้องเพิ่ม", a quantity change on a line
already priced, which routes through 5(i). Supporting a never-priced line properly means the version
has to wait on a pricing round, which needs a child คำขอราคา attached to an already-numbered DRAFT
version and the draft's `pricing_request_id` / `pricing_decision_id` re-pointed when the CEO
approves. That is buildable without a migration — both columns are nullable (V74) — and it needs no
new `doc_status` either, since the waiting state is derivable from the linked request's own status.
But it is a second feature, and shipping it inside this one would bury the stage-gate work.

`createReorder` stays refused for PCR origin (non-goal).

#### D-5 as verified against the code, 2026-10-17

Read before planning this slice. D-5 was written against a picture of the code that has moved, and
the difference changes the size of the work and raises one question only the owner can answer.

**Already built — do not rebuild:**

- **PCR-origin quotations are already revisable.** `createRevision`
  does not call the reorder guard; it has an explicit "PR B" branch admitting
  ISSUED/REVISION_REQUESTED PRICING_REQUEST rows, gated to the **owning rep** via
  `requireOutcomeAccess` — deliberately narrower than `requireEditAccessForQuotation`, on the
  reasoning that the manager's job is to approve a revision, not author it. The only remaining
  caller of the guard — now named `requireReorderAllowedForOrigin` — is `createReorder`, which is exactly where this
  design wants it.
- **Deal-scoped numbering** — `insertCopyOf` (see the corrected constraint above).
- **The legacy bare-number base case** — `DealQuotationRepository.baseNumber` handles both eras with
  one formula; a bare-numbered source already yields `{bare}-2`.
- **The CEO-price item links survive a revision.** `insertCopyOf` re-attaches
  `pricingRequestItemId`/`pricingDecisionItemId` by item id, with a comment noting that dropping
  them would silently switch off the `priceChangedFromCeo` comparison. So D-5's "the signal the
  classifier runs on already exists" is true *and* already propagates to the child.
- **The parent is superseded on the child's ISSUE, not its creation** — the ancestor walk, plus the
  same ruling already enforced on the pricing-request side ("the already issued quotation STAYS
  ISSUED and valid while the replacement chain runs").
- **Recipient is copied verbatim**, and re-addressing is already refused for PRICING_REQUEST origin
  by `update`'s own origin rule (409 "ผู้รับของใบเสนอราคาจากคำขอราคามาจากคำขอราคา").

**Genuinely remaining:**

1. ~~A recipient lock as a revision rule~~ DONE 2026-10-17 (see the RULED block below).
2. Routing the approval by **what actually changed** (D-5 step 5). Nothing does this yet.
3. Refusing a version that adds a line with **no CEO-approved price** (the deliberate scope cut).
4. Collapsing the two doors into one `สร้างฉบับแก้ไข`. Door B still exists and is still the only
   path that re-opens pricing.

**✅ RULED 2026-10-17 — the discount is carried forward. D-5 step 3 is struck; the code is correct
as it stands and needs no change.**

Pinned by `DealQuotationPricingRequestRevisionIntegrationTest
#createRevision_carriesTheCeoApprovedDiscountForward_notZero`, which states the rule directly
rather than leaving it inferable from the `priceChangedFromCeo` flag.

**The mutation check turned up an argument nobody had made, and it is decisive.** Zeroing the
discount in `toNewItemFromDto` fails **nine** tests, not one — and eight of them fail with
`มีรายการที่เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ`. A zeroed discount reads as *changed from the
CEO's price*, because the comparison baseline is the CEO decision item. So D-5 step 3 as written
would have made **every** revision require CEO approval, and ผจก.ขาย could never have approved one —
destroying D-1, the approval split this very design is built on ("CEO owns the price, ผจก.ขาย owns
the document"). The two halves of D-5 were in direct contradiction and the doc did not notice.

Carry-forward also cannot ratchet, which was step 3's stated worry: the baseline is
`pricing_decision_item.discount_pct` (via `DealQuotationRepository#priceChangedFromCeo`'s `ceo_*`
columns), never the parent quotation. An already-approved discount reads unchanged; deepening it
reads changed and meets `requireCeoApprovalIfChanged`'s 403. Version 5 cannot hold a discount nobody
approved. The one place with no CEO baseline at all is a **DEAL_DIRECT** row (unlinked items, so the
flag is never raised) — that is the กรอกราคาเอง route D-2 already records as a second approved route,
not a new hole.

**✅ RULED 2026-10-17 (Ploy, option 1): a revision keeps its parent's recipient.** Re-addressing a
revision draft (either origin) is 409 (`ฉบับแก้ไขต้องส่งถึงผู้รับเดิม (<ผู้รับเดิม>) — หากจะเสนอราคาให้ผู้รับรายอื่น ให้สร้างใบเสนอราคาใหม่บนดีลนี้`);
only a CHANGE is refused, because the editor re-sends the stored recipient on every save. The way to
quote another party is a new quotation on the deal (the next suffix). Goal 3 is now a rule, and the
register's base+recipient grouping and the `parentQuotationId` lineage can no longer disagree.
Implemented in `DealQuotationService#update` (after the 400 validation), mirrored in `mockApi`, and the
editor shows the recipient read-only with the same hint. The three PR-B tests were inverted/replaced:

- `revisionDraft_mayChangeRecipientToOwner_parentKeepsItsOwn` -> `revisionDraft_cannotBeReaddressed_409_andNothingChanges`
- `issuingARevisionAddressedToOwner_...` and `issuingARevisionAddressedToBuyer_...` -> one
  `issuingARevision_leavesTheStageWhereTheParentPutIt`
- added `revisionDraft_resavingTheSameRecipient_isAllowed` and (DEAL_DIRECT)
  `DealQuotationIntegrationTest#createRevision_draftCannotBeReaddressed_409`

## Constraints

- `sales.quotation.number` is UNIQUE (V6); `nextRevisionNo` (MAX over every row that ever existed
  under a base) is the only safe way to pick the next suffix. Do not re-derive it by hand.
- **Scope trap, the one most likely to bite:** the two engines scope `nextRevisionNo` differently —
  `CustomerQuotationRepository.nextRevisionNo(pricingRequestId, base)` by request,
  `DealQuotationRepository.nextRevisionNo(ticketId, base)` by deal. A customer-change revision child
  is a *different* คำขอราคา from its parent, so anything scoped by `pricingRequestId` cannot see the
  parent's quotations and will restart the family at `-1`. The continuation must go through the
  deal-scoped (`ticketId`) one. The trap does not apply to the
  door D-5 builds on: `DealQuotationService.insertCopyOf` — shared by `createRevision`,
  `createReorder` and the resubmit-after-ตีกลับ path — already calls the deal-scoped
  `quotations.nextRevisionNo(source.ticketId(), baseNumber)`, and its own comment explains that it
  chose that over `source.revisionNo() + 1` for this exact duplicate-key reason. The request-scoped
  counter belongs to the OLD `customerquotation` engine, which is what Door B's eventual quotation
  goes through — a reason not to route the continuation through Door B.
- Legacy quotations carry a bare number with no `-1`. `QuotationNumbering.baseNumber` has the
  fallback; revising one yields `{bare}-2`.
- `sales.factory_quote_item.pricing_request_item_id` is `ON DELETE RESTRICT`, which is why factory
  quotes are carried forward at submit and not at revision creation. Don't move that.
- No new migration is expected. If one becomes necessary, the next free Flyway version must be
  confirmed against both develop and the merge queue — V200 is already claimed by two branches.

## Already true — do not rebuild

Verified against develop while scoping this, so the builder does not re-derive it:

- `/quotations` already lists all three origins (`originPredicate`: `origin IS NULL` legacy
  read-only, `DEAL_DIRECT`, `PRICING_REQUEST`), role-gated by `canViewPricedOriginsOnList`. A
  quotation created under a deal already appears there.
- `/quotations/new` already opens on the ดีล step with เลือกดีลที่มีอยู่ / สร้างดีลใหม่ (#1090).
- `advanceStageForCustomerQuotationIssue` + `stageForQuotationRecipient` already map
  DESIGNER→S4, OWNER→S5, BUYER→S8, forward-only, keyed on the quotation's own recipient.
- The all-stock → CEO route (V194), as above.

## Settled by the builder

- One helper, `DealQuotationService#advanceDealToRecipientStage`, called from `create`,
  `createFromPricingRequest` and the recipient-change path.
- The approve-time `advanceStageForCustomerQuotationIssue` call is kept: it is idempotent under
  forward-only and still covers a recipient changed between draft and approval.
- Exact Thai wording for the `AUTO_STAGE_HINT` entries and the fact-gate 409 messages is presentation
  only, in the voice of the existing four.

## Test plan — written and failing before any production code

Per the owner's standing rule: tests first, the coverage list in chat before implementing, each test
red for the right reason (an assertion on missing behaviour, never a compile or fixture error).

**Backend — the fact gate (`TicketServiceTest` / stage integration test)**

- ขั้น 3, no quotation → manual `updateStage(QUOTE_DESIGN_SIDE)` is refused 409 with the fact
  message.
- Same for ขั้น 4 → QUOTE_OWNER and ขั้น 7 → QUOTE_BUYER.
- A deal **with** a designer quotation is already on ขั้น 4 via auto-advance, so the manual set
  reports "already in stage" — pinned so the gate can never be the thing that blocks a legitimate
  correction.
- The automatic path still advances with the gate in place (the regression this risks).
- An off-route stage and a non-forward target stay silent no-ops.

**Backend — creation moves the stage**

- Creating a PCR-origin quotation addressed to DESIGNER on a ขั้น 3 deal → deal is on ขั้น 4.
- OWNER → ขั้น 5; BUYER → ขั้น 8.
- Changing a DRAFT's recipient from DESIGNER to OWNER → ขั้น 5 (the recipient-change hook).
- A second quotation to the **same** recipient → stage unchanged.
- A backward recipient (OWNER-stage deal, new DESIGNER quotation) → stage unchanged.
- `UNSPECIFIED`/unknown recipient → no move, no throw.
- A non-ACTIVE deal → no move.
- A deal **created** with the quotation (`/quotations/new` → สร้างดีลใหม่) starts on the recipient's
  stage, not ขั้น 1.
- A DESIGNER quotation on an `OWNER_DIRECT` deal → no move, no throw, quotation still created (the
  off-route no-op above).

**Backend — versioning (D-5)**

- `สร้างฉบับแก้ไข` on a PCR-origin ISSUED quotation succeeds and is numbered `{base}-2`.
- A third version off the second is `{base}-3` — the version never restarts.
- A legacy bare-numbered source revises to `{bare}-2`, not `{bare}-1` and not a fresh code.
- The new version is numbered by the **deal-scoped** `nextRevisionNo(ticketId, base)`. Pinned with a
  sibling คำขอราคา present on the same deal, which is the case a request-scoped lookup gets wrong.
- Recipient is locked: a revision cannot be re-addressed to another party. DONE (owner ruling
  2026-10-17) — `revisionDraft_cannotBeReaddressed_409_andNothingChanges`,
  `revisionDraft_resavingTheSameRecipient_isAllowed`, `issuingARevision_leavesTheStageWhereTheParentPutIt`,
  `DealQuotationIntegrationTest#createRevision_draftCannotBeReaddressed_409`.
- Lines arrive carrying the parent's CEO-approved discount (**owner ruling 2026-10-17**), and `priceChangedFromCeo` stays false so ผจก.ขาย can still
  approve. DONE — `createRevision_carriesTheCeoApprovedDiscountForward_notZero`, mutation-checked
  (zeroing the discount fails it on `[row 0 discountPct]`, and fails eight sibling tests with the
  CEO-approval 403).
- A quantity change at the approved unit price → PENDING_APPROVAL, and ผจก.ขาย can approve it.
- A unit price below the approved minimum → a CEO discount approval is raised and the version cannot
  issue until it clears.
- A version that adds a line with no approved price → refused, with the message naming คำขอราคา as
  the next step (the D-5 scope cut).
- The new version starts at DRAFT and cannot be issued without an approval.
- The parent is superseded when the child is **issued**, not when it is created — so a deal never
  has zero readable current quotation.
- Two concurrent revisions of the same source: one wins, the other 409s, no duplicate-key crash on
  `sales.quotation.number`.
- `createReorder` on a PCR-origin quotation is still refused.

**Frontend**

- `salesActions`: ขั้น 3/4/7 never produce `ADVANCE_STAGE`.
- `TicketDetailPage`: no `เลื่อนไป: 4. …` sticky CTA on a ขั้น 3 deal; the ⋯ item is absent.
- `DealStagePanel`: the inline advance button is absent on ขั้น 3/4/7, and the auto-stage hint
  renders instead.
- `UpdateStageModal`: the three quote stages are not offered as targets.
- `mockApi` mirrors the fact gate, so the mock stack and the real backend agree (a mock that stays
  permissive is a drift with no guard).

**Deliberately not covered**

- The Thai copy strings themselves.
- `createReorder` beyond the "still refused" case.
- PDF output — nothing about the printed document changes.

## Delivery

One branch, test-first, implemented by Sonnet and reviewed by Opus per the standing rule (B-4 was
Opus-reviewed), clean-review applied 2026-10-17. D-5 step 2 (recipient lock on a revision) was ruled and
implemented 2026-10-17.
