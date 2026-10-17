# The base number belongs to the deal

Design for the quotation versioning workflow. Supersedes **D-5** of
`INFORMATION_ARCHITECTURE.md` in this folder (see that file's "D-5 as verified against the code,
2026-10-17" note for the premises that went stale), and supersedes this document's own first draft,
which built an elaborate mechanism to solve a problem the owner then dissolved with one question.

Status: **implemented and committed on `feat/quote-stage-gates-and-versioning` (f8b98dc4, 2026-10-17); clean-review applied.**

## The rule

> **The base number belongs to the DEAL. The first quotation on a deal mints it; every later
> quotation on that deal continues it with the next suffix, in creation order.**

Owner ruling, 2026-10-17: one family per deal — not per recipient, not per chain. A revision, a
reorder clone, a replacement after a pricing round, and a wholly independent new document are all
just *the next document on this deal*, and they are numbered accordingly.

That is the whole design. Everything below is consequence.

## Intent, and why the first draft was wrong

Every later conversation about the same deal is a **version of the same offer** — the owner's own
words, 2026-09-20. The number family is the thread of that conversation, and the rep was losing it.

The first draft of this document diagnosed the pain correctly — "we want a different tile too"
forces the rep to abandon their version and start a fresh chain, costing the number — and then
proposed to fix it by keeping the draft version *alive* through a CEO pricing round: re-pointing
`pricing_request_id`, a derived "waiting on a price" state, a classifier at submit.

The owner asked: *can we just go by when the quotation was created?*

That dissolves it. If the suffix comes from the deal's family in creation order, then **a pricing
round is free to throw the draft away** — because whatever document comes out of it inherits the
family and is the next version. There is nothing to preserve across the round, so there is no
mechanism needed to preserve it.

Three things make this work, and all three already exist:

| Already true | Where |
| --- | --- |
| Superseding is **deal-wide — B-4**, `DealQuotationRepository#supersedeOtherLiveOnTicket`, origin-agnostic | `DealQuotationRepository#supersedeOtherLiveOnTicket`, `DealQuotationService#approve` / `#approveAndIssuePricingRequestOrigin` |
| The suffix counter is already **MAX+1 over the family on the deal** | `DealQuotationRepository#nextRevisionNo(ticketId, baseNumber)` |
| "Continue the family rather than mint a fresh base" is already a ruled-in behaviour, quoting the owner: *"it is a later version of the same offer"*, and explicitly noting that **only the printed NUMBER continues** — no chain linkage implied | `DealQuotationService#createFromPricingRequest`'s MAJOR-3 rule, 2026-09-20 |

## The one actual defect

That "continue the family" rule is gated two ways it does not need to be:

1. it fires **only after an expiry** (`prReadyForReplacementAfterExpiry`), and
2. it finds the base through `findLatestForPricingRequest(pricingRequestId)` — scoped to **one
   pricing request**.

Door B (`createCustomerChangeRevision`) creates a *child* pricing request. A child is a different
request, so the lookup sees none of the parent's quotations, falls through to the fresh-base branch,
and the family restarts at `-1`.

That is the entire reason the number is lost. Not the chain, not the engine, not the classifier.

## What to build

Small, and in this order.

### B-1 — "the deal's base number", as one lookup

A repository method answering: *what base number is this deal's family on?* Derived from any
existing quotation on the deal with `origin IN ('DEAL_DIRECT','PRICING_REQUEST')`, via
`QuotationNumbering.baseNumber(number, revisionNo)`; empty when the deal has none yet.

Legacy rows (`origin IS NULL`) do **not** seed the family, matching `nextRevisionNo`'s own scope —
otherwise a legacy import would capture the family of a deal it predates.

**Settled rule for a deal that holds several families:** nothing limits a deal to one pricing
request (`PricingRequestService#createDraft` refuses only a live *direct* quotation), and
`createFromPricingRequest` used to mint a fresh base for every request except the expiry path, so
multi-recipient deals and Door B deals can carry two or three bases. The lookup therefore takes the
**newest** family and never asserts a single base — an exception would turn the next create on those
deals into a 500.

**Prod audit, 2026-10-17 (read-only, Supabase `tdyzcqzxmhtxpbouewud`).** The builder was right about
the mechanism and wrong about the scale — measured, not assumed:

| Measure | Prod |
| --- | --- |
| Deals holding quotations | 199 (302 documents) |
| Deals with **one** base family | 198 |
| Deals with **two** base families | **1** — ticket 114, two DEAL_DIRECT documents created 21 seconds apart on 2026-09-23 (`QT-2026-0113-1` APPROVED, `QT-2026-0114-1` DRAFT), both `UNSPECIFIED` recipient. A double-create, not a multi-recipient deal. |
| Deals with more than one LIVE document | **0** (worst case 1) |
| Deepest version reached | `-6` |
| **PRICING_REQUEST-origin documents** | **0** |
| Legacy (`origin IS NULL`) documents | **0** |

Two consequences that reshape this slice:

1. **The per-deal family rule is already true in prod**, for 198 of 199 deals. So it needs no
   backfill and no migration — only the deterministic rule below for the one exception, and the
   hard assertion must not be used (it would 500 the next create on ticket 114).
2. **The pricing route has never produced a quotation in production.** Every one of the 302
   documents is `DEAL_DIRECT`. So the Door B number-loss defect this design exists to fix has never
   once happened to a real customer, and the B-4 retirement gap is entirely **latent**. Meanwhile
   versioning is in heavy real use on the direct route (~100 revisions, one chain six deep).


**The rule for a deal that already has several families** (owner to confirm the shape, but the data
makes it nearly moot): continue the base of the most recent existing document on the deal. Never
renumber a document already sent. For ticket 114 that means its DRAFT stays as it is and anything
new on that deal continues `QT-2026-0114`.

### B-2 — every create continues the family

Three call sites (the first two minted a fresh base before this slice):

| Call site | Before | After |
| --- | --- | --- |
| `createFromPricingRequest` | continues the family only after an expiry, request-scoped | always continues the deal's family (B-1); mints only when the deal has none |
| `create` (DEAL_DIRECT) | always `nextQuotationCode()` | continues the deal's family; mints only when the deal has none |
| `insertCopyOf` (revision / reorder) | already continues, deal-scoped | unchanged |

Once `createFromPricingRequest` continues the family, **Door B stops costing the number with no
change to Door B at all** — the document its round eventually produces simply arrives as the next
suffix.

### B-3 — keep `parent_quotation_id`

Keep the column and keep writing it: the DRAFT ancestor walk and `requireNoOpenRevision` use it.
Retirement no longer depends on it — B-4's deal-wide sweep covers the pricing route, which has no
parent link.

### B-4 — retirement on the pricing route (NEW)

**Owner ruling 2026-10-17: the unit of "one live offer" is the DEAL, not the recipient.** A deal
holds one live offer; approving any document retires every other live one on that deal, whatever
party it was addressed to and whatever origin it came from.

Why that is coherent rather than careless, stated because the old engine's own code argues the other
way (`supersedeSupersededChainQuotations` carries a `recipient_type` predicate added to stop "a
document the customer already holds, retired with no warning"): ขั้น 4 → ขั้น 5 → ขั้น 8 is **not
three parallel offers**. It is one offer progressing through the parties who approve it — the
designer specifies, the owner signs off, the buyer purchases. Once the owner has been quoted, the
designer's document has genuinely stopped being the live offer. The old engine's predicate was
protecting a different shape: genuinely parallel recipients, which this pipeline does not have.

The consequence to accept: a designer holding `QT-2026-0014-1` sees it superseded when the owner is
quoted at `-2`. That is the intended meaning of one family per deal.

**What was built:** the same-deal sweep used to run only on the DEAL_DIRECT branch of `approve`,
scoped `origin = 'DEAL_DIRECT' AND doc_status = 'APPROVED'`. It is now origin-agnostic and runs on the
pricing route too. Mind the status vocabularies: a DEAL_DIRECT document's live terminal state is
`APPROVED`, a PRICING_REQUEST one's is `ISSUED` — a sweep that only matched `APPROVED` would
silently skip every pricing-route document.

---

#### Why this slice was required (the gap it closes)

The design's goal 3 was not met on the pricing route, and the per-deal family would have made the
gap **customer-visible** rather than creating it:
**customer-visible** rather than creating it:

- Door B's document carries no `parent_quotation_id` (`createFromPricingRequest` passes null), so the
  ancestor walk retires nothing.
- `approveAndIssuePricingRequestOrigin` skips the same-ticket sweep by design — its own comment says
  this origin "has no 'one live document per ticket' sweep".
- The old engine's equivalent, `supersedeSupersededChainQuotations`, is only called from the old
  engine's `issue`.
After a Door B round the deal held **two live ISSUED documents and nothing retired the first**.
offers. Under the per-deal family rule they become `QT-2026-0014-1` and `QT-2026-0014-2`: same
family, both live, in front of the same customer. One of them would be a lie about which offer stands.
offers. Under the per-deal family rule they become `QT-2026-0014-1` and `QT-2026-0014-2`: same
family, both live, in front of the same customer. One of them is a lie about which offer stands.
This slice closed it. It was the one place where "just continue the family" was not free.
This slice must close it. It is the one place where "just continue the family" is not free.

## What this design deletes

Worth listing, because it is most of the first draft and a reader may come looking for it:

- Keeping a draft version alive across a pricing round.
- Re-pointing a draft's `pricing_request_id` / `pricing_decision_id` when a round approves.
- A derived "waiting on a price" state, and the screen work to show it.
- A submit-time classifier deciding whether to open a round.
- Collapsing the two revise doors into one.

That last one deserves a sentence, because "one revise door" was the original ask. **My reading: the
pain was never that there are two buttons — it was that one of them dead-ends and the way out costs
the number.** With per-deal families, two doors are harmless and the rep's model is honest:

- *"same products, different terms or quantity"* → `สร้างฉบับแก้ไข`, approved by ผจก.ขาย
- *"I need a different price, or a product we have not costed"* → a คำขอราคา round, which produces
  the next document

Neither dead-ends; neither costs the number. If the owner still wants one button, it becomes a thin
UI affordance over these two rather than a backend redesign — and that is a much later, much cheaper
decision. Flagged as my reading, not the owner's ruling.

## Goals

1. One number family per deal, continued by every document on it, in creation order.
2. No flow in which a rep loses the family because of what the customer asked for.
3. Nobody can reach an issued document carrying a price no CEO approved. Unchanged from today.

## Non-goals

- Changing who sets the price. The CEO still does, through a คำขอราคา.
  `DiscountApprovalService` stays CEO-only below minimum.
- Replacing the old `customerquotation` engine.
- `createReorder` for PCR-origin quotations — stays refused.
- The three quote stages. Done and green; this does not touch them.
- Printing anything other than `{base}-{n}`. Creation *order* supplies the suffix; creation *time* is
  only the ordering key. The customer-facing suffix stays, per the owner's 2026-09-11 feedback
  ("ใบแรกเป็น QT-2026-0014-1") — we are not printing timestamps.

## Anti-goals

- A deal already holding two families continues the NEWEST; it never throws (an exception would
  500 the next create on ticket 114).
- Reusing a suffix. `nextRevisionNo` is MAX over every row that ever existed under the base,
  including cancelled ones, and must stay that way — a reused suffix means two different documents
  were called the same thing in front of a customer.
- Breaking the case that is already smooth: a quantity change at the approved unit price must keep
  going to ผจก.ขาย and must never wait on the CEO. (It does not trip `priceChangedFromCeo`, which
  compares price fields only — verified.)
- A second numbering authority.

## Consequences the owner has accepted

Stated plainly so nobody is surprised on a customer call.

- **Suffixes interleave across recipients.** Designer gets `-1`, owner `-2`, buyer `-3`; a revision
  of the designer's document is then `-4`. This follows directly from "family per deal".
- **A recipient's first document is not `-1`.** If the designer was quoted first, the owner's first
  document is `QT-2026-0014-2`. The number identifies the *deal*; the suffix is only "which document
  on this deal". A rep asked "what was `-1`?" by the project owner should be able to answer it.
- **Cancelling and starting again advances the suffix.** Cancel `-1`, create again, you get `-2`.
  The family records what happened, not only what survived.

## Constraints

- `sales.quotation.number` is UNIQUE (V6). `nextRevisionNo(ticketId, base)` is the only safe way to
  pick a suffix. Do not re-derive it, and do not reach for
  `CustomerQuotationRepository.nextRevisionNo(pricingRequestId, base)` — request-scoped, and the
  reason the family restarts today.
- `QuotationNumbering.baseNumber` already handles both eras: a post-2026-09-11 `QT-2026-0014-1` and a
  legacy bare `QT-2026-0014`. A bare-numbered source continues as `{bare}-2`.
- Concurrency: two creates on one deal must not read the same MAX. `create` takes the deal's
  advisory lock (`lockTicket`), `insertCopyOf`'s callers lock before computing the suffix, and
  `createFromPricingRequest` does the same now that it continues the family.
- No migration expected. If one becomes necessary, confirm the next free Flyway version against both
  develop and the merge queue — V200 has been claimed by more than one branch before.

## Test plan

Tests first, coverage list in chat before implementing, each red for the right reason.

**The family is the deal's**

- Two quotations to **different recipients** on one deal are `-1` and `-2`, not two `-1`s. *(The
  ruling itself. Reverting it must turn this red.)*
- A quotation created after a Door B pricing round continues the family — `-3`, not a fresh base.
  **This is the defect; it must be red before the fix and green after.** Pinned with a child
  pricing request present, which is the case the request-scoped lookup gets wrong.
- A DEAL_DIRECT create on a deal that already has a PCR-origin quotation continues the family, and
  vice versa — the two origins share one counter.
- Cancel `-1`, create again → `-2`. A suffix is never reused.
- A legacy bare-numbered quotation does **not** seed the family: the first real document on that
  deal still mints its own base.
- A deal somehow holding two distinct bases continues the NEWEST and never throws
  (`create_onADealAlreadyHoldingTwoFamilies_continuesTheNewest`).

**Nothing else regressed**

- A quantity change at the approved price still goes to ผจก.ขาย, never the CEO. *(The
  already-smooth case. Named as an anti-goal; this is its guard.)*
- A price below the approved minimum still raises the CEO discount approval.
- Approving a document still supersedes every other live one on the deal, including one with no
  parent link at all — the sweep, not the chain.
- `createReorder` on a PCR-origin quotation is still refused.
- Concurrent creates on one deal produce two different suffixes and no duplicate-key crash.

**Deliberately not covered**

- Thai copy strings.
- PDF output — nothing about the printed document changes except which suffix appears.
- The DEAL_DIRECT pricing route's lack of a CEO baseline (non-goal; it is D-2's second approved
  route).

## Settled by the builder

- B-1 lives on `DealQuotationRepository` beside `nextRevisionNo`, folded into `nextNumberOnDeal`.
- **No "two distinct bases" assertion.** The claim that "the deals it would hit do not exist yet" was
  false — the prod audit found one (ticket 114), and an exception would have 500'd the next create on
  it. The newest family is taken instead.
- Exact Thai wording for any new refusal, and whether `/quotations` groups a deal's documents by
  family, are presentation only. (The DEAL page's register was NOT presentation-only — see below.)

## Shipped

- B-1 / B-2 → `DealQuotationRepository#nextNumberOnDeal`, used by `create` and
  `createFromPricingRequest` (test: `DealQuotationNumberFamilyIntegrationTest`).
- B-4 → `DealQuotationRepository#supersedeOtherLiveOnTicket`, origin-agnostic, plus the stale-round
  409 in `createFromPricingRequest` (owner ruling 2026-10-17 evening).
- `lockTicket` uses the two-int `pg_advisory_xact_lock(classId, id)` form, so its keyspace is
  separate from the single-bigint pricing-request locks.

## The deal document register — owner ruling 2026-10-17, DONE

One family per deal broke the deal page's register, which this document's first draft had not
considered: it called grouping "presentation only" and scoped the thought to `/quotations`.

`dealDocumentVersions.js` grouped direct quotations on the base number alone, and
`DealDocumentRegister.jsx` labels each row `ครั้งที่ N`. With every document on a deal now sharing
one base, that collapsed **different parties' offers into a single version chain**: the owner's
brand-new first quotation rendered as `ครั้งที่ 2`, nested beneath the designer's as though it were
a later version of it. Two false claims — and a rep opening the top row to see "the current version"
got a different party's offer.

**Ruled: group by base AND recipient** (`direct:{base}:{recipient}`). Implemented, with four tests
red first, in `dealDocumentVersions.test.js`. Recipient is what still identifies a document, because
a genuine revision copies its parent's recipient verbatim (`insertCopyOf`) — so real chains stay
grouped while separate offers separate. A row with no `recipientType` (an older backend omits it)
groups under `UNKNOWN` rather than becoming a singleton, and never merges with a row that states
one. `ครั้งที่ N` stays: within one recipient's chain it is accurate again.

**Interaction to watch — it couples to D-5 step 2.** A PRICING_REQUEST-origin revision can currently
be re-addressed to another party (PR B, three tests pin it). Under recipient grouping such a revision
**splits from its own parent** — correct by this grouping's logic, but it visually severs a real
parent-child chain. If step 2 is ruled "recipient locked" the case disappears; if it is ruled the
other way, this grouping and the `parentQuotationId` lineage will disagree for those documents.
