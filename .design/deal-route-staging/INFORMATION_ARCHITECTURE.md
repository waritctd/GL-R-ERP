# IA / UX flow: route-aware deal staging

**Status:** DESIGN — awaiting rulings R1–R9. Nothing implemented.
**Ask (Ploy, 2026-09-30):** when a rep creates a deal and says who they are in contact with
(ผู้ออกแบบ / ผู้รับเหมา / เจ้าของ …), the deal should follow *that party's* route through the
stages — including re-wording S3 per party — instead of every deal walking one fixed 15-stage line.
**Sibling docs (do not duplicate):** `.design/quotation-deal-link/INFORMATION_ARCHITECTURE.md`
(D1–D7, approved 2026-09-30) and `SLICE-2-FLOW-A.md` (S2-B1…B5). Those move the stage from the
**quotation recipient**; this doc moves it from the **deal's entry channel**. §7 reconciles them.
**No test-first coverage list here:** docs-only, no testable behaviour. The coverage list belongs to
the implementation slice and is sketched in §9 so it is not forgotten.

---

## 1. What already exists (build on this, do not reinvent)

| Thing | Where | State |
|---|---|---|
| `entry_channel` on the deal | `sales.ticket.entry_channel`, V51 → V144 | **Already exists, already required in `TicketCreateModal`.** Values `DESIGNER_LED` / `OWNER_DIRECT` / `BUYER_DIRECT` / `UNSPECIFIED` (stored-only) |
| The 15 stages | `DealStage.java` (`ORDER`), `chk_ticket_sales_stage`, `dealStageCatalog.js` mirror | S1–S20 business numbering; `PROCUREMENT` covers S12–S17 |
| Thai labels | `DEAL_STAGE_LABELS` in `frontend/src/utils/format.js` | **static per code** — one label, no route awareness |
| Panel headline override | `STAGE_HEADLINE` in `stageMeta.js` | precedent exists: exactly one entry (`ORDER_RECEIVED`), panel-only |
| Six real routes | `DealStage.java` Javadoc (A–D) + `SalesRouteWalkIntegrationTest` (A–E, G) | **routes are already a first-class idea in the code** — they are just not *data* |
| Forward-only auto-advance | `TicketService.autoAdvanceStage` | monotonic on `DealStage.ORDER` index; the one safe write path |
| The only hard refusals | `requireStageFactsHold` (S10/S11/S19/S20) | plus the readiness gate (follow-up date + an activity) |

**Two written rulings this ask reverses — flagged, not assumed:**
1. **V144 line 27:** *"NOT a behavioural flag: nothing reads entry_channel to decide anything
   (DealStage in particular does not consult it). It stays purely descriptive by owner ruling."*
2. **`SalesRouteWalkIntegrationTest.routeB_…`** asserts `SPEC_APPROVED` (S3) **never happens** on an
   owner-direct deal. Ploy's route *keeps* S3 and re-words it. That test's defining assertion changes.

**One thing GLA-156 (merged `dd514c27`) just removed:** ช่องทางรับงาน is no longer displayed or
editable on the deal page; `POST /api/tickets/{id}/entry-channel` is now `UNREACHABLE_FROM_UI`. If the
channel decides the route, it has to become visible and correctable again — see R6.

---

## 2. The case space — three independent axes, only one of them is this ask

The six tested routes are not six alternatives; they are combinations of three axes:

| Axis | Decided by | Routes | In scope? |
|---|---|---|---|
| **A — party** (S1–S9): who specs, who approves, who buys | `entry_channel` | designer-led / owner-direct / buyer-direct | **YES — this ask** |
| **B — fulfilment** (S12–S17) | line-item stock coverage, a *fact* | import / all-stock (D) / partial (E) | no — works, fact-driven |
| **C — payment** (S11, S20) | payment terms, a *fact* | deposit / credit-after-delivery (G) | no — works, fact-driven |

**Scoping consequence:** axes B and C are discovered from recorded facts and must stay that way —
they are not declared up front and the channel says nothing about them. So "route" in this document
means **the party route only (S1–S9)**. A deal has one party route *and* independently one fulfilment
and one payment shape. Case F (paid in full before delivery) stays out of scope per existing ruling.

---

## 3. The party routes

Written as the rep experiences them. **`S3` is the pivot:** it is not inherently a designer stage —
it means *"the party who specifies has agreed the spec"*, and only its wording is party-specific.
That is the insight in Ploy's brief and it is what makes one stage list serve all three routes.

### R-D · ผู้ออกแบบนำดีล (`DESIGNER_LED`) — the full spine
```
S1 → S2 → S4 → S3 → S5 → S6 → S7 → S8 → S9 → S10 → …
         ╰─ quote the designer, then they approve the spec (the one routine backward pair)
```
- Visits every S1–S9 stage. Identical to documented **Case A**. Unchanged by this design.
- S3 label: **"ผู้ออกแบบอนุมัติสเปค"** (today's wording).

### R-O · เจ้าของติดต่อโดยตรง (`OWNER_DIRECT`)
```
S1 → S2 → S3* → S5 → S6 → ┬→ S7 → S8 → S9 → …   (O-a: owner tenders it out / appoints a buyer)
                           ╰→ S9 → …              (O-b: the owner buys themselves)
```
- **S3 is KEPT, re-worded:** **"เจ้าของตกลงตามสเปคแล้ว"**. (Documented Case B skips S3 — superseded.)
- Off-route: **S4** (no designer to quote).
- **Two legitimate continuations after S6**, per Ploy: the owner may still put procurement out to a
  contractor (O-a, S7/S8 on-route) or buy directly (O-b, straight to S9). Neither is an exception —
  so the UI must not present O-b as a "skip" needing a reason. Both are the happy path.

### R-B · ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง (`BUYER_DIRECT`)
```
S1 → S2 → S3* → S8 → S9 → …
```
- **S3 kept, re-worded:** **"ผู้ซื้อ/ผู้รับเหมาตกลงตามสเปคแล้ว"**.
- Off-route: **S4** (no designer), **S5**, **S6** (the project owner is not our counterparty).
- **S7 — needs a ruling (R2).** Two readings: (i) off-route, because the buyer is already in hand so
  there is no bid result to wait for; (ii) on-route, because a contractor who is *themselves* bidding
  keeps us waiting. Documented Case C opens straight at S8 and skips S1–S7 entirely, which is a
  *third* reading — and it conflicts with Ploy's "start from S1" for all three routes (R1).

### R-U · ยังไม่ระบุช่องทาง (`UNSPECIFIED`, and legacy rows)
Not a route — an **absence**. It must resolve to something because ~every pre-V144 row reads
`DESIGNER_LED` whether chosen or defaulted (V144 deliberately did not backfill) and GLA-156 left
`UNSPECIFIED` deals uncorrectable in-portal.
**Proposed:** `UNSPECIFIED` ⇒ **all 15 stages on-route, no re-wording, no next-step opinion** — the
widest, least opinionated lens, identical to today's behaviour. A missing channel then degrades the
UI gracefully instead of asserting a route nobody chose. (R3)

---

## 4. The load-bearing decision — RULED: the route is a GATE

⚠️ **This section was written recommending a LENS. Ploy ruled for a GATE on 2026-09-30 (R10).**
The reasoning below is kept because it names the risks the gate now has to mitigate — see §13 for
how. It is not an argument to re-open; it is the list of things that must be got right.

**Ruled: moving into an off-route stage is refused (409) until the deal's ช่องทางดีล is corrected,
and the refusal offers that correction inline.**

**What was proposed instead (not adopted): the route never refuses a stage.** It would change what the
rep is *shown and advised*, never what they are *allowed* to do.

The codebase already argued this case and won it. `TicketService.requireStageFactsHold`'s Javadoc
rejects a transition table because *"the business's real routes branch heavily… a table faithful to
them has to permit almost every forward edge"* — and `DealStage.MANDATORY` exists precisely so that
skipping a *route-dependent* stage costs nothing while skipping a *universal* one costs a sentence.
A route that gated would re-introduce the table that reasoning rejected, and would break the day a
designer turns up on an owner-direct deal.

**This exact mistake has already been made and reverted once.** `stageMeta.js:15-18` records a
client-side `distance > 1` skip test that was deleted because it *"wrongly demanded reasons for three
of the business's four normal routes (an owner buying direct, a contractor arriving with a BOQ at S8,
an all-from-stock deal skipping procurement)"* — i.e. a naive route rule punished three of the four
real routes. That comment is the frontend's only surviving trace of the route concept, and it is a
direct warning against gating.

| | Route as LENS (proposed) | Route as GATE (rejected) |
|---|---|---|
| Off-route stage | reachable, de-emphasised | 409 |
| Rep discovers a designer late | move to S4; route widens | blocked until channel edited |
| Wrong channel at creation | cosmetic until corrected | wrong data blocks real work |
| Legacy `UNSPECIFIED` | widest lens, no harm | must guess a route to function |
| New route discovered | a label + a list | a migration + a table |

So the route drives exactly four things: **(1)** S3's wording, **(2)** which stages read as
*on this deal's path*, **(3)** the "ถัดไป" suggestion, **(4)** the progress denominator.

---

## 5. User flows

### Flow 1 — Create an owner-direct deal (happy path)
1. Rep opens สร้างดีล, fills ลูกค้า + โครงการ, reaches **ผู้ติดต่อ & ช่องทางดีล** (already required).
2. Picks **เจ้าของติดต่อโดยตรง**. → helper copy becomes route-specific: *"ดีลนี้จะข้าม
   'เสนอราคาผู้ออกแบบ' และใช้ 'เจ้าของตกลงตามสเปคแล้ว' เป็นขั้นอนุมัติสเปค"* — the consequence is
   stated **at the point of choice**, not discovered later.
3. Submits. Deal is created at **S1** (unchanged — no jump; see R4).
4. Deal page: stage panel reads **S1**, ถัดไป reads **S2 นำเสนอสินค้า**, progress **"ขั้นที่ 1 จาก 7"**
   (the route's length, not 15), and a **route chip** shows *เจ้าของตรง*.
5. Rep advances S1 → S2 → **S3 "เจ้าของตกลงตามสเปคแล้ว"** → S5 → S6. No note demanded anywhere:
   every stage crossed is route-dependent, none is in `MANDATORY`.
6. After S6 the panel offers **both** continuations as equals — *"เจ้าของซื้อเอง → เจรจา (S9)"* and
   *"ให้ผู้รับเหมาซื้อ → รอผู้ซื้อ (S7)"*. Neither is framed as a skip.

### Flow 2 — Designer-led deal (happy path, unchanged)
Exactly today's behaviour: S1 → S2 → S4 → S3 → S5 → S6 → S7 → S8 → S9. The S4→S3 pair stays the
allowlisted routine backward move. **This flow must not regress** — it is the majority route.

### Flow 3 — Buyer/contractor-direct (happy path)
S1 → S2 → **S3 "ผู้ซื้อ/ผู้รับเหมาตกลงตามสเปคแล้ว"** → S8 → S9. S7's membership pending R2.

### Flow 4 (non-happy) — The channel was wrong
1. Deal is owner-direct at S5. A designer turns up and must be quoted.
2. Rep opens **ดูขั้นตอนทั้งหมด**; S4 is present but de-emphasised as *ไม่อยู่ในเส้นทางนี้*.
3. Moving to S4 is a **backward** move (S4 < S5 in `ORDER`) → today's rules demand a reason, and the
   S4→S3 routine-pair allowlist does not cover it. **The rep gets a refusal-shaped prompt for
   something legitimate.** → **R5**: either allowlist "into an off-route stage behind the current
   one" as routine, or (better) make correcting the channel the primary affordance here and let the
   widened route make S4 a normal on-route stage.
4. Rep corrects the channel to `DESIGNER_LED`. Route widens; the stepper re-renders; **stage does not
   move** (forward-only). History keeps what happened.

### Flow 5 (non-happy) — Channel corrected *backwards*
Deal is designer-led at S6; rep corrects it to owner-direct. S4 is now off-route **but already
visited**. The stepper must show S4 as **visited** (a fact) *and* off-route (a lens) — a visited
off-route stage is normal after a correction, not an error. Never hide a stage the deal actually
entered; history is the source of truth.

### Flow 6 (non-happy) — Stage is already ahead of the whole route
A quotation-first deal promoted to **S10** (GLA-136) never walked S1–S9. Lens still applies: all of
S1–S9 render as *not visited*, progress reads honestly, and no back-fill is invented (IA §7 of the
sibling doc already rules: no data migration invents a stage).

### Flow 7 (non-happy) — `UNSPECIFIED` / legacy deal
Widest lens (R3): 15 stages on-route, today's labels, no route chip, no next-step opinion. Plus a
quiet **"ระบุช่องทางดีล"** affordance, because GLA-156 removed the only way to fix it (R6).

### Flow 8 (non-happy) — Lifecycle overrides
`ON_HOLD` / `DORMANT` / `CLOSED_LOST` already take over the panel headline. Route-aware labels must
render inside those branches too — the GLA-156 session flagged that those branches call
`dealStageLabel()` directly, which is exactly why R7 asks for **one** label function rather than
patched call sites.

### Flow 9 (non-happy) — Fact-gated and readiness refusals are unchanged
S10/S11/S19/S20 still refuse without their fact; a forward move still needs a follow-up date and an
activity since the last stage change. The route must not appear to promise a stage these will refuse
— the "ถัดไป" suggestion has to keep consuming the existing server-side `stageDecisions` verdicts.

### Flow 10 (non-happy) — Route vs. quotation recipient (agreed with the sibling session)
**Precedence, as proposed by the quotation↔deal-link session and adopted here:** the channel sets the
**floor**; the quotation recipient may only move the stage **forward**; both go through the single
`autoAdvanceStage` so neither can regress the other.
Worked case: owner-direct deal sitting at **S5**; a `DESIGNER` direct quotation is created (S2-B2
targets S4). `autoAdvanceStage` is a **no-op** (S4 < S5) — correct. Reverse: a deal at S3 with a
`BUYER` quotation jumps to **S8** — also correct; at quotation time the recipient is the more
specific signal. To be pinned on this side: *"owner-direct deal at `QUOTE_OWNER`, then a `DESIGNER`
direct quotation → stage stays `QUOTE_OWNER`"*; that session pins the mirror.

---

## 6. Content hierarchy — the deal stage panel, post-GLA-156

1. **Current stage headline** — route-worded. Highest priority; it is the answer to "where is this deal".
2. **Route chip** (*ผู้ออกแบบนำ / เจ้าของตรง / ผู้ซื้อ-ผู้รับเหมาตรง*) — second, because it is now the
   thing that *explains* the headline and the skips. Reverses GLA-156's removal (R6).
3. **ถัดไป** — the route's next stage, still filtered by `stageDecisions`.
4. **Branch choice** where the route genuinely forks (R-O after S6) — two peer actions, not a skip.
5. **ดูขั้นตอนทั้งหมด** (expander) — full stepper, on-route emphasised, off-route de-emphasised.
6. Owner / follow-up / readiness hints — unchanged.

---

## 7. Where the route lives — one definition, server-side

The route is **sales workflow logic**, so per CLAUDE.md it is defined and enforced in Java and
verified by a real-DB integration test, never inferred from `mockApi.js`.

- **Definition:** a `DealRoute` concept beside `DealStage` — for each channel, the ordered on-route
  stage list, the S3 label key, and the fork points. One source of truth, mirrored nowhere.
- **Served:** extend `GET /api/meta/deal-stages` (or the per-deal `…/actions` payload, which already
  carries per-stage verdicts) so the client is **told** the route rather than deriving it. This keeps
  the existing no-Thai-text-in-the-catalog rule; the Thai stays in `format.js`.
- **Frontend anchors — the structure already exists; use it rather than inventing:**
  - **`stagesInPhase(catalog, phaseId)`** (`stageCatalog.js:91`) is the **single chokepoint** every
    stage list flows through (`DealStageStepper.jsx:51`, `:153`, `:188`). One natural insertion point
    for on-route/off-route emphasis.
  - **`procurementPath(currentCode, fromStock)`** (`stageMeta.js:109-120`) is the **working precedent**
    for branch-aware step rendering, including an honest "branch unknowable → narrow to the shared
    tail" fallback (which is exactly R-U's widest-lens behaviour). Its own comment prescribes the
    durable form: *the backend serves the path, as `PaymentTrack.path(policy)` already does for
    payments.* **`DealRoute.path(entryChannel)` should follow that shape.**
  - **`stageDecisions`** (`GET …/actions`) already carries per-deal, per-user verdicts with
    server-authored Thai prose, and `UpdateStageModal` already renders blocked stages **with reasons
    rather than hiding them** (`:107-144`) — the established pattern for "visible but not now". A
    sibling field (`onRoute` / `routeNote`) rides the same channel. `stageCatalog.js:17` and
    `stageMeta.js:20` both **forbid re-deriving this client-side**.
  - **`dealStageLabel(code)` is single-argument with 11 call sites** across 7 files (header chip, panel
    headline, ถัดไป chip, stepper step, modal `<option>`, modal blocked row, list cell, mobile card,
    overflow menu, duplicate-deal warning, commission page). Parameterising it means touching all of
    them or threading the channel through context — decide once (R7), not per site.
  - **`nextStageIn`** becomes route-aware; **`PhaseSummary`'s denominator** becomes the route's length,
    not `catalog.stages.length` (today `(stageIndex+1)/15`, which a skipping route makes wrong).
  - Precedent for the wording problem: `LEAD_APPROACH` (`เข้าถึงเจ้าของ/ผู้ออกแบบโครงการ`) and
    `QUOTE_BUYER` (`เสนอราคาผู้ซื้อ/ผู้รับเหมา`) **already hedge with slashes** because one label serves
    several routes. Route-aware wording is what those slashes are working around.
- **Sequencing constraint:** `TicketService` / `TicketRepository`, `TicketDetailPage.jsx`,
  `salesActions.js`, `workState.js`, `DealHistoryPanel.jsx`, `DealCustomerCard.jsx` and `mockApi.js`
  are **in flight** on the quotation↔deal-link slices. `DealStage.java` and
  `SalesRouteWalkIntegrationTest` are free. Implementation waits for slice 1/2 to land.

---

## 8. Naming

| Concept | Label in UI | Notes |
|---|---|---|
| `entry_channel` | **ช่องทางดีล** | `TicketCreateModal`'s wording. The quotation editor says ช่องทางรับงาน — **pick one** (R8) |
| The party route | **เส้นทางดีล** | new; the chip |
| On-route stage | (emphasised, no label) | absence of decoration is the signal |
| Off-route stage | **ไม่อยู่ในเส้นทางนี้** | de-emphasised, still reachable — never "ห้าม" |
| S3, designer-led | ผู้ออกแบบอนุมัติสเปค | today's wording, unchanged |
| S3, owner-direct | **เจ้าของตกลงตามสเปคแล้ว** | Ploy's wording, verbatim |
| S3, buyer-direct | **ผู้ซื้อ/ผู้รับเหมาตกลงตามสเปคแล้ว** | by analogy — confirm (R9) |

**⚠️ Hard layout constraint on any re-wording.** `DealStateHeader.jsx:238-282` records *measured*
Thai stage-label widths for the header chip grid: the column is ~**174px** at ≥1041px, the widest
current label (`นัดส่งสินค้า / นัดรับเงินส่วนที่เหลือ`) is **190px**, and wrapping is only tolerable because
`[&_.status-badge]:whitespace-normal` is scoped to that `<dl>`. Both new S3 wordings must be measured
in that chip before they are accepted — `ผู้ซื้อ/ผู้รับเหมาตกลงตามสเปคแล้ว` is longer than every label
currently in the map. Do not hand-wave this; it is a documented past regression surface.

No new routes or URLs: this is entirely within the existing deal detail page and create modal.

---

## 9. Rulings needed before implementation

| # | Question | Recommendation |
|---|---|---|
| **R1** | Ploy says all routes **start at S1**. Documented Case C opens at **S8** and `routeC_…` pins it. Keep Case C as a legitimate fast-open, or retire it? | **Keep both**: S1 is the default start; opening at S8 stays legal because the route is a lens, not a gate. No test needs to change. |
| **R2** | Is **S7** on-route for buyer-direct? | ✅ **RULED (Ploy, 2026-09-30): off-route.** The buyer is already in hand. Still reachable if they are themselves bidding. |
| **R3** | Route for `UNSPECIFIED` / legacy rows? | **Widest lens** — all 15 on-route, today's labels, no opinion. |
| **R4** | Does the deal still **start at S1**, or jump on creation? | **Start at S1.** Your brief replaced the jump with a route; a jump would also assert milestones that have not happened. |
| **R5** | Moving into an off-route stage **behind** the current one (Flow 4) — routine, or reason-required? | Make **correcting the channel** the primary affordance; leave the reason requirement alone. |
| **R6** | GLA-156 removed the channel from the deal page. Restore it? | ✅ **RULED (Ploy, 2026-09-30): restore as a chip + an edit affordance.** Needed for Flows 4, 5, 7. |
| **R7** | One `dealStageLabel(code, channel)`, or panel-only override via `STAGE_HEADLINE`? | **One function.** A panel-only override leaves lists, history and modals contradicting the panel. |
| **R8** | **Three** vocabularies for one field: `TicketCreateModal` (ช่องทางดีล · ผู้ออกแบบนำ/เจ้าของตรง/ผู้ซื้อตรง), `format.js` `entryChannelLabel` (ผู้ออกแบบนำดีล/เจ้าของติดต่อโดยตรง/ผู้ซื้อ-ผู้รับเหมาติดต่อโดยตรง), `DealCustomerCard` (ช่องทางรับงาน, and the only list that offers `UNSPECIFIED`) | **Collapse to one** — recommend ช่องทางดีล + `format.js`'s long labels everywhere. A field that now decides the route cannot have three names. |
| **R9** | Confirm the buyer-direct S3 wording. | ผู้ซื้อ/ผู้รับเหมาตกลงตามสเปคแล้ว |
| **R10** | **What does moving into an OFF-ROUTE stage cost?** | ✅ **RULED (Ploy, 2026-09-30): REFUSED (409), with an inline แก้ช่องทางดีล remedy.** Chosen over the recommended written-reason option; the concern I raised and the mitigations it forces are in §13. |

**Then, and only then**, the implementation slice — test-first, with: route definition unit tests;
a real-DB integration test per route through the real `TicketService`; the wrong-way-round cases
(off-route stage still reachable; forward-only holds when the channel changes); the Flow 10
precedence test; a mutation check per guard; and the `routeB_…` assertion updated to expect S3.

---

## 10. Scope expansion reported mid-design — needs Ploy's confirmation

The quotation↔deal-link session reports that **Ploy has ruled this session owns the deal-stage rule
outright**, and asked that it also cover **direct-quotation creation**. That session is accordingly
**dropping S2-B2** (its recipient→stage move) so there is one owner and one rule, and will leave a
hook at the end of `DealQuotationService.create`.

✅ **CONFIRMED by Ploy directly, 2026-09-30.** This session owns the single deal-stage rule,
including the direct-quotation trigger. (Recorded here as confirmed-by-the-user, not
confirmed-by-the-peer — the peer's report alone would not have been enough.)

If confirmed, the rule has **two triggers writing one column**:

| Trigger | Signal | Target |
|---|---|---|
| Deal creation / channel correction | `entry_channel` | the route's **floor** (S1; the route decides the path, not a jump — R4) |
| Direct-quotation creation, and recipient change while `DRAFT` | `sales.quotation.recipient_type` | `DESIGNER`→S4, `OWNER`→S5, `BUYER`→S8 |

Both go through the single forward-only `autoAdvanceStage`, so the §5 Flow 10 precedence holds
unchanged: **channel sets the floor, recipient may only move forward, neither can regress the other.**

⚠️ **This touches approved decision D1.** `.design/quotation-deal-link/INFORMATION_ARCHITECTURE.md`
D1 says *"stage moves at quotation creation, by recipient"*. That survives intact here — it simply
moves into this rule's ownership. What is genuinely new is that a deal now also has a route **before**
any quotation exists, which D1 did not contemplate. **Ploy should be told that explicitly**, per that
session's own request.

Also inherited: the quotation editor's *"ดีลจะอยู่ที่ขั้น {stage}"* preview line under the recipient
pills is being **removed** from slice 2 (it would promise a rule that session no longer owns).
Restoring it — now accurate, and route-aware — belongs to this work, in `DealCustomerCard.jsx`,
**after** slice 2 lands.

---

## 11. Risks

1. **Designer-led is the majority route and must not regress.** Every change is additive to R-D.
2. **Two reversed rulings** (V144 descriptive-only; `routeB_…` skips S3). Both are deliberate and
   stated; neither should be discovered later in a diff.
3. **`UNSPECIFIED` is unreachable from the UI** after GLA-156, and legacy rows read `DESIGNER_LED`
   whether chosen or defaulted (V144 did not backfill). Without R3 + R6 those deals get a route
   nobody picked. **No migration should invent a channel** — the sibling IA's §7 already rules that
   way for stages.
4. **Authorization:** a route changes sales workflow logic, so per CLAUDE.md the enforcement needs a
   **real-DB integration test through the real `TicketService`**, written wrong-way-round, with a
   mutation check. `mockApi.js` is not evidence here.
5. **File contention:** `TicketService` / `TicketRepository` and most of the sales frontend are in
   flight on slices 1–2. This work is **sequenced after them**; `DealStage.java` and
   `SalesRouteWalkIntegrationTest` are free now.
6. **Label width** (§8) is a real past regression surface, not a detail.

---

## 12. The full transition case space

Enumerated at Ploy's instruction: *"it's not just you can jump from one stage to any stage — analyse
fully how many cases there can be."* She is right, and the enumeration proves it: **even with no route
at all, only 42 of 210 transitions are free.** The system was already heavily disciplined.

### 12.1 The precedence ladder — which check fires first

A move is one pass down this ladder. **Order matters**: it decides which message the rep reads. Every
rung already exists except rung 6.

| # | Check | Outcome | Where it lives today |
|---|---|---|---|
| 1 | `DealStage.isValid(target)` | 400 | `TicketService.updateStage` |
| 2 | Actor's role vs the target's **gate** (sales / account / import / ceo) | 403 | `requireStageWriteAccess` |
| 3 | Lifecycle not `ACTIVE` (`ON_HOLD` / `DORMANT` / `CLOSED_LOST`) | 409 | `requireActive`, CLOSED_LOST branch |
| 4 | `target == current` | 409 | `requireStageMoveAllowed` |
| 5 | **Fact gate** — S10 order, S11 deposit, S19 delivered, S20 paid | 409 until the fact exists | `requireStageFactsHold` |
| 6 | **Route — is the TARGET off this deal's route?** | **409, remedy = แก้ช่องทางดีล** | ⬅ **new (R10)** |
| 7 | Crosses a `MANDATORY` stage (S9, S10, S18, S19, S20) | needs a reason | `DealStage.requiresJustification` |
| 8 | Backward, and not the allowlisted `S4→S3` pair | needs a reason | `isRoutineBackwardMove` |
| 9 | Forward **readiness** — a follow-up date **and** an activity since the last stage change | 400 | `requireStageAdvanceReadiness` |
| — | otherwise | free, one click | |

**Rung 6 is the entire proposal.** It sits with rung 5 as the second hard refusal — deliberately
*after* the fact gate, so a deal missing its deposit says so rather than blaming the route.

⚠️ **Rung 6 tests the TARGET only, never the current stage.** A deal may legitimately be *sitting on*
an off-route stage — it was visited before the channel was corrected (Flow 5). Gating the current stage
would strand it with no legal move at all. This is the single most important implementation detail in
this document.

### 12.2 Every ordered (from → to) pair, classified

15 stages ⇒ 210 ordered pairs where `from ≠ to`, per route; **630 (route, from, to) triples** in all.

| Case class | Verdict | R-D designer-led | R-O owner-direct | R-B buyer-direct |
|---|---|---|---|---|
| FORWARD adjacent | free | 10 | 9 | 6 |
| FORWARD skip (route-dependent only) | free | 31 | 29 | 17 |
| FORWARD skips a MANDATORY | note | 18 | 18 | 18 |
| BACKWARD routine (allowlisted) | free | 1 | 1 | 1 |
| BACKWARD needs a reason | note | 94 | 83 | 56 |
| FACT-GATED target | refused until the fact exists | 56 | 56 | 56 |
| OFF-ROUTE forward | **REFUSED** — correct the channel first | 0 | 3 | 18 |
| OFF-ROUTE backward | **REFUSED** — correct the channel first | 0 | 11 | 38 |
| **Total ordered pairs (c≠t)** | | **210** | **210** | **210** |

- **R-D designer-led** — on-route 15 of 15: S1 → S2 → S3 → S4 → S5 → S6 → S7 → S8 → S9 → S10 → S11 → S12-17 → S18 → S19 → S20  ·  off-route: none
- **R-O owner-direct** — on-route 14 of 15: S1 → S2 → S3 → S5 → S6 → S7 → S8 → S9 → S10 → S11 → S12-17 → S18 → S19 → S20  ·  off-route: S4
- **R-B buyer-direct** — on-route 11 of 15: S1 → S2 → S3 → S8 → S9 → S10 → S11 → S12-17 → S18 → S19 → S20  ·  off-route: S4, S5, S6, S7

| Rolled up | R-D designer-led | R-O owner-direct | R-B buyer-direct |
|---|---|---|---|
| Free (one click, no reason) | 42 | 39 | 24 |
| Allowed but needs a written reason | 112 | 101 | 74 |
| **Refused** (fact gate + off-route) | **56** | **70** | **112** |

### 12.3 What this shows

1. **"Any stage to any stage" was never true.** On the widest route only **42/210 = 20%** of moves are
   free; **56** were already hard-refused and **112** already demanded a written reason.
2. **The route roughly DOUBLES the refusals on the narrowest route.** Refused rises **56 → 70 → 112**;
   for buyer-direct, **112 of 210 — 53% — of all transitions are now refused outright**, and free
   moves fall 42 → 24. This is a substantial tightening. It is what was asked for; it is also why §13's
   escape hatch is not optional.
3. **Facts are route-blind** — the 56 fact-gated pairs are identical across all three routes. Correct:
   a deposit exists or it does not, whoever the counterparty is.
4. **`MANDATORY` is route-blind too** (18 everywhere) — exactly its purpose: skipping a *universal*
   milestone costs a sentence, skipping a *route-dependent* one costs nothing.
5. **Designer-led is completely unaffected — 0 off-route pairs.** The majority route sees no
   behavioural change at all, which also makes the rollout risk near zero (§13.2).

### 12.4 Orthogonal multipliers — not part of the 630

These gate every one of the 630 identically, which is why they belong on the ladder and not in the
matrix: **4** lifecycle states × **2** readiness states × **2** fact states (for the 4 fact-gated
targets) × the actor's role-vs-gate verdict. None of them is re-litigated by this design.

---

## 13. Consequences of R10 = refuse

A hard gate is only safe if the escape hatch is excellent, because correcting ช่องทางดีล becomes the
**only** way to reach an off-route stage. Ploy chose the variant that shows that remedy inline, so the
model is coherent — but three things stop being optional.

### 13.1 Three things now load-bearing

| # | Requirement | Why it is now mandatory |
|---|---|---|
| **13a** | **R6's channel chip + edit must ship in the SAME slice as the gate.** | GLA-156 removed the control and put `POST /api/tickets/{id}/entry-channel` in `UNREACHABLE_FROM_UI`. Shipping the gate first would refuse moves while offering **no way to fix the cause** — a dead end in the product. These two changes are one slice, not two. |
| **13b** | **The owning rep must be able to correct the channel** — not CEO-only. | Otherwise every wrong channel becomes an escalation. The endpoint already requires a note to change a stated channel, which is the right audit trail; keep that, widen nothing else. |
| **13c** | **`UNSPECIFIED` must gate NOTHING** (R3's widest lens). | ~Every pre-V144 row and every quotation-first ghost can carry it. If `UNSPECIFIED` gated, those deals would brick with no in-portal remedy. The widest lens stops being mere graceful degradation and becomes a safety requirement. |

### 13.2 Why the rollout risk is nonetheless low

**`DESIGNER_LED` has zero off-route stages, and it is what every un-chosen legacy row already reads**
(V144 deliberately did not backfill, so a defaulted row and a chosen one are indistinguishable). The
gate is therefore a **no-op for the entire back catalogue** and for the majority route going forward.
The tightening lands only on deals a rep explicitly marked เจ้าของตรง or ผู้ซื้อ/ผู้รับเหมาตรง.
This is a genuinely lucky property of the existing default — worth not breaking.

### 13.3 The concern I raised, recorded

I recommended a written reason over a refusal. Ploy ruled for the refusal; that is her call and the
design implements it in full. The concern, for the record, so a future reader knows it was weighed:

- `requireStageFactsHold`'s own Javadoc rejects a transition table because *"the business's real routes
  branch heavily… a table faithful to them has to permit almost every forward edge."* Rung 6 is a
  narrow, per-channel table — much smaller than the one that reasoning rejected, and it refuses only
  **one** stage on owner-direct — but it is the same shape.
- `stageMeta.js:15-18` records a deleted client-side rule that *"wrongly demanded reasons for three of
  the business's four normal routes."* That rule only nagged; this one refuses.
- **Mitigation is 13a.** If the remedy is one click from the refusal, the objection largely dissolves:
  the rep is not blocked, they are asked to fix the deal's record first — which is arguably better data
  hygiene than a free-text note. **If 13a ever slips out of the slice, this ruling should be revisited.**

### 13.4 Flow 4, rewritten under the gate

1. Owner-direct deal at S5. A designer turns up and must be quoted → rep targets **S4**.
2. **409.** The modal shows it in the blocked list with the server's own prose:
   *ดีลนี้เป็นเจ้าของติดต่อโดยตรง — ขั้นนี้ไปไม่ได้ — แก้ช่องทางดีลก่อน* — plus a **แก้ช่องทางดีล** button.
   This reuses `UpdateStageModal`'s existing blocked-list pattern (`:107-144`), which already renders
   `blockedReason` verbatim — **no new UI primitive**, only a new reason string and one action.
3. Rep clicks it, sets `DESIGNER_LED`, writes the required note. Route widens to all 15.
4. S4 is now on-route. It is still a **backward** move (S4 < S5), so rung 8 asks for a reason as it does
   today — unchanged, and no longer a route matter. R5 dissolves: it was only a problem under the lens.

### 13.5 Coverage list for the implementation slice (test-first)

Written before any production code, run red first, per CLAUDE.md. Authorization is touched (rung 6
decides who may write what), so a **real-DB integration test through the real `TicketService`** is
required — `mockApi.js` is not evidence.

- **Unit (`DealRouteTest`)** — the on-route set per channel; `UNSPECIFIED` ⇒ all 15; the three S3 label
  keys; and that `DESIGNER_LED`'s off-route set is **empty** (13.2's safety property, pinned).
- **Integration, per route, through the real service** — the happy walk of R-D / R-O / R-B; R-O's fork
  after S6 (both S7 and S9 succeed, neither demands a note).
- **Wrong-way-round (the cases that matter):** owner-direct → S4 is **refused** with the route reason;
  buyer-direct → S5 / S6 / S7 each refused; `UNSPECIFIED` → **every** stage still reachable (13c);
  `DESIGNER_LED` → nothing refused by rung 6 (13.2).
- **The stranding case (12.1):** a deal sitting *on* an off-route stage after a channel correction can
  still move forward. Assert it is not trapped.
- **Precedence (§5 Flow 10):** owner-direct deal at `QUOTE_OWNER`, then a `DESIGNER` direct quotation
  ⇒ stage **stays** `QUOTE_OWNER` (forward-only). The sibling session mirrors this.
- **Ladder order:** a deal missing its deposit targeting S11 reports the **fact** gate, not the route.
- **Mutation checks:** (a) empty the off-route set ⇒ the three refusal tests go red and nothing else;
  (b) gate the current stage instead of the target ⇒ the stranding test goes red; (c) make
  `UNSPECIFIED` gate ⇒ 13c's test goes red. Revert to an empty diff after each. ⚠️ Per
  `mutation-restore-stale-class`: always `clean` before re-running, or stale `.class` files produce
  false reds.
- **Frontend:** `dealStageLabel(code, channel)` returns the right S3 wording per channel and is
  unchanged for every other code; the stepper marks off-route stages; `PhaseSummary`'s denominator is
  the route's length; the channel chip renders and its edit affordance appears. Plus the measured
  label-width check from §8.
- **Not covered, deliberately:** axes B and C (fulfilment, payment) — already covered by
  `SalesRouteWalkIntegrationTest` routes D/E/G and untouched here. Case F stays out of scope.
- **Existing test that must change:** `SalesRouteWalkIntegrationTest.routeB_ownerBuysDirect_…` asserts
  `SPEC_APPROVED` never happens on an owner-direct deal. Under R-O, S3 **does** happen (re-worded), and
  S4 is the only skip. Update its defining assertion — and say so in the PR body, since it is a
  deliberate reversal of a pinned business rule, not a broken test.
