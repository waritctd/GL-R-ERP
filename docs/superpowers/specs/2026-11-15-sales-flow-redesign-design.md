# Sales deal flow redesign — "My work" + "Team overview"

Status: draft for owner approval · 2026-11-15
Visual reference: before/after mockup (private artifact) https://claude.ai/artifact/LJbt6Q1QzW2B2W9VhSjt4u

## 1. Why

GL&R's reps use the quotation generator heavily but avoid the deal pipeline (`/tickets`). Their real CRM is a
weekly Excel workbook (pipeline sheet, visit calendar, activity logs, RV receipts sheet) plus a weekly email to the
sales manager whose body is a cash forecast by week. The office then rebuilds monthly commission from the RV sheets.

Today's pipeline is intimidating and duplicates that work instead of replacing it:

- 15 stages in 5 phases with names reps don't use; stages are moved by hand.
- Every deal shows the same orange "95 days" warning; 19 of 20 deals are flagged "needs attention" on the manager
  dashboard, so nothing stands out.
- The manager dashboard shows "team sales this month ฿0.00" because received cash lives in Express (Thai accounting
  software, no API), not in the app.
- No value, designer, expected quarter or % on the list; no visits; no expected cash.
- A survey of the sales team names the fear directly: a new system that is slower than the old way, and entering
  everything twice (web app + Excel).

## 2. Goals

1. **Remove manual work.** Anything the app can already infer (a quotation was made, a deposit arrived, goods were
   delivered, cash was received) moves the deal on its own.
2. **No more weekly report email.** The sales manager and CEO read the same facts live in the app.
3. **Not intimidating.** A rep's first screen is a short to-do list, not a 21-row table.
4. **Manager / CEO can see at a glance:**
   - which rep holds which deal (and its value),
   - stale deals that should be followed up,
   - visits done this week and visits planned,
   - expected payments vs received.

## 3. Non-goals

- No change to commission, payroll or tax math. Commission inputs may later be read from the app, but the formula
  is untouched.
- No rebuild of the deal data model (stages stay as they are in the database — see Approaches).
- No warehouse / stock-availability features (survey asked for them; separate project).
- No direct Express integration (there is no API).
- No change to the quotation generator's own flow — it is the part reps like.

## 4. Anti-goals (would be a failure even if it "works")

- Reps still keep the Excel workbook in parallel after rollout → we added work, not removed it.
- A rep has to open more than one screen to do their weekly update.
- Manager numbers that disagree with Express (wrong cash total is worse than no total).
- Every deal flagged again — "stale" must be rare enough to mean something.

## 5. Constraints

- Sales/CRM is unfrozen and its workflow/permissions/schema may change (CLAUDE.md, 2026-07-20), but every such
  change is stated in the PR body; schema changes are forward-only `Vnnn`; permission changes need a real-DB
  integration test through the Java service.
- The pricing-request redesign (Step 1, `PricingRequest` aggregate) is in flight; this work must not depend on its
  unfinished later steps.
- `main` is release-locked to self-service; work goes to `develop`. Backend deploys by image, not by merge.
- Test-first rule applies to every behaviour change.
- Reconciliation files with customer names are private — never committed; import tooling must not embed them.

## 6. Approaches considered

| | Approach | Trade-off |
|---|---|---|
| **1 (chosen)** | New screens over today's model: rep "My work" + manager "Team overview"; the 15 stages stay in the DB and are grouped into 5 display stages; deals fill from quotations and move on operational events; add visits, expected payments, Express upload. | Fastest, lowest risk, fits the pricing redesign. The extra stages stay underneath, hidden rather than removed. |
| 2 | Also rebuild the model: 5 stages in the DB, quotations without deals, products typed once, designer as directory link. | Cleanest, but stacks schema change on the in-flight pricing redesign and delays anything reps can see; risks breaking quoting. |
| 3 | Data first: import pipeline sheets + Express upload, screens later. | Trustworthy numbers early, but reps get nothing new for a while and the email can't stop. |

Owner picked **1** on 2026-11-15. Model cleanup from 2 may follow once usage shows which stages matter.

## 7. The design

### 7.1 Five display stages (over the existing 15)

Grouping is a pure display mapping on top of `DealStage.ORDER`; no stage is removed.

| Display stage | Existing stages | Who moves it |
|---|---|---|
| เสนอสินค้า (presenting) | LEAD_APPROACH, PRESENTATION | rep |
| สเปคผ่าน (spec agreed) | SPEC_APPROVED | rep |
| เสนอราคาแล้ว (quoted, S3–S5) | QUOTE_DESIGN_SIDE, QUOTE_OWNER, OWNER_SIGNOFF, AWAITING_BUYER | auto when a quotation is approved for that recipient; rep may still nudge |
| รอมัดจำ (buyer known, S6) | QUOTE_BUYER, NEGOTIATION, ORDER_RECEIVED | auto from buyer quotation / confirmCustomer; rep may nudge |
| ส่งของ · เก็บเงิน (deliver & collect, S7–S9) | DEPOSIT_RECEIVED, PROCUREMENT, DELIVERY_SCHEDULING, DELIVERED, CLOSED_PAID | auto from payment track, IR, delivery; CLOSED_PAID via existing CEO close |

Each display stage shows the reps' own workbook codes (owner decision). Per-stage mapping, used by the board and
by the one-time import:

| Rep code | Workbook meaning | Existing stages |
|---|---|---|
| S1 | อยู่ระหว่างเสนอสินค้า | LEAD_APPROACH, PRESENTATION |
| S2 | ผู้ออกแบบตกลงสเปคให้ | SPEC_APPROVED |
| S3 | ทำใบเสนอราคาแล้ว | QUOTE_DESIGN_SIDE, QUOTE_OWNER, OWNER_SIGNOFF |
| S5 | รอผลประมูล รอผู้ซื้อ | AWAITING_BUYER |
| S6 | ทราบผู้ซื้อแล้ว | QUOTE_BUYER, NEGOTIATION, ORDER_RECEIVED |
| S7 | ได้รับมัดจำแล้ว | DEPOSIT_RECEIVED, PROCUREMENT |
| S8 | อยู่ระหว่างรอส่งสินค้า | DELIVERY_SCHEDULING |
| S9 | ส่งสินค้าครบแล้ว | DELIVERED, CLOSED_PAID |

S4 appears in no rep workbook, so it is not shown (my call). The S1–S20 business codes stay in the API untouched.

### 7.2 Deals fill from quotations

The quotation generator already creates a deal silently. When a quotation is issued, the deal takes from it:
customer/project, value (latest issued total), designer/owner/buyer from the recipient, and the stage advance for
that recipient (existing recipient routing). The rep never re-types these on the deal.

### 7.3 Rep screen — "งานของฉัน" (My work), new default sales landing

Top to bottom (mobile-first; the mock's phone view is the reference):

1. **ควรตามต่อ (follow up)** — the rep's deals quiet longer than the stale threshold, oldest/largest first, max ~5
   shown. One-tap actions per deal: *log a call*, *book a visit*, *close this deal (lost)*. Each action writes a
   `deal_activity` row and so clears the "quiet" clock.
2. **นัดเข้าพบสัปดาห์นี้ (visits this week)** — Mon–Fri list of planned visits; tick to mark done (with optional
   note); add a visit (to a deal, or to a designer/owner with no deal yet).
3. **เงินที่จะเข้า (cash expected this month)** — the weekly email's five lines as one card: forecast, received so
   far, delivered awaiting payment, deliveries scheduled, deposits expected; stacked weekly bars.
4. **Board** — the rep's deals in the 5 display stages, with value, designer, expected quarter, %.

A large **บันทึกเข้าพบ (log visit)** button is always reachable on phone.

### 7.4 Manager / CEO screen — "ภาพรวมทีมขาย" (Team overview)

1. KPI tiles: cash received (from Express upload) vs forecast; open deals held; deals quiet > threshold;
   visits done / planned this week.
2. Per-rep table: deals, value, quiet deals, expected vs received, visit dots for the week, last update time.
3. Stale deals list across the team, ranked by value × days quiet.
4. Week visit calendar: reps × Mon–Fri, done / planned / cancelled.

Replaces the current manager dashboard's misleading "฿0.00" and blanket flags.

### 7.5 Stale ("quiet") rule

A deal is quiet when its latest activity, quotation issue, or stage change is older than **14 days** (configurable
in CEO settings), and it is not in the deliver & collect stage or closed. Replaces the current per-row "95 วัน"
warning.

Any `deal_activity` counts as a touch — including a call logged as **ไม่รับสาย** (no answer). Accepted in the round-2
review: a rep who keeps calling is still working the deal, and the outcome stays visible on the follow-up row
("ครั้งล่าสุด: …"). If unanswered calls should stop counting, that needs an outcome column on `deal_activity`
(today the outcome is only the note's first line), which is out of scope for slice 1.

### 7.6 Visits

`sales.deal_activity` today records only past activities, always tied to a deal. Visits need:

- a planned date vs done date (planned → done / cancelled),
- an optional deal: a visit may be to a designer or owner before any deal exists,
- the contact visited (free text initially).

Schema change (new `Vnnn`): either extend `deal_activity` (nullable `ticket_id`, `planned_date`, `status`) or add a
`sales.visit` table. Builder's call; state it in the PR.

### 7.7 Expected payments

Per deal, a small schedule of expected cash: kind (deposit / on delivery / balance), amount, expected week. Seeded
from the quotation's payment terms where present; editable by the rep. Feeds the "cash expected" card and the
manager forecast. New table, new `Vnnn`.

### 7.8 Express receipts upload

Account uploads the Express receipts export (RE/AI/IV) on whatever cadence they choose (weekly is enough).

- Match by invoice number, then quotation number, then customer + amount within a small tolerance.
- Matched receipts mark the expected payment received and drive deposit/closed-paid auto-advance through the
  existing payment track.
- Unmatched receipts go to a one-tap queue for the owning rep (or sales manager) to attach to a deal.
- Upload is idempotent (re-uploading the same file changes nothing).

### 7.9 One-time import of current pipelines

Import the reps' current pipeline sheets so the app starts with their real deals (project, designer, owner,
buyer, quotation no., value, %, expected quarter). A dry-run report lists what will be created/matched before
anything is written. Customer data in the import stays out of git.

### 7.10 Rollout order (each slice replaces part of the email)

1. My work: follow-up list + board + quiet rule; deals fill from quotations. Team overview: per-rep table + stale list.
2. Visits (plan, done, calendar).
3. Expected payments + Express upload + cash cards.
4. Pipeline import (can run alongside 2–3 once data shape is settled).

The weekly email is retired when slice 3 is live. Slice 5 (import & account screens, §7.11) follows slice 3.

### 7.11 Import and account (scope widened by the owner, 2026-11-15)

The owner asked that the redesign also cover the import and account screens, not only sales / sales manager / CEO.
Findings from a full capture of `develop` (333 screenshots, 5 roles):

- **Import** re-selects the factory currency and the price unit that sales already set (and that the request form
  labels "ฝ่ายนำเข้าเปลี่ยนไม่ได้"); the 6-step shipment tracker can be advanced from three places (`/fulfilment`,
  `/import/deals/:id`, the CEO's deal tab) with no confirmation and no undo; the landing splits one job across four
  tiles and three lists.
- **Account** types every payment amount, date and reference by hand although sales already issued the deposit
  notice / invoice with the amount; a second "บันทึกรับชำระเงิน" dialog exists on the sales deal page that account can
  never reach (it is redirected to `/finance/deals/:id`).

Design:

1. **Import landing "งานนำเข้า"** — one worklist grouped by next step: รอรับเรื่อง (inline รับเรื่อง), รอราคาโรงงาน,
   กำลังขนส่ง (step + ETA), ถึงโกดังแล้ว. Replaces the 4-tile overview and separate lists.
2. **Pricing workspace** — currency and price unit that sales set are shown as read-only chips ("ตามที่ฝ่ายขายระบุ");
   the per-factory currency select and per-line unit toggle appear only when the request did not set them.
3. **One place to advance a shipment** — the `/fulfilment` card. `/import/deals/:id` and the sales/CEO deal tab show
   the tracker read-only. An advance shows an undo snackbar (30 s) backed by a reversible event, instead of no
   confirmation. Shipment ETA feeds the rep's "deliveries scheduled" cash line.
4. **ติดต่อโรงงานแล้ว** defaults the date to today; **สร้างเมล** gains "คัดลอกทั้งหมด".
5. **Account landing "งานการเงิน"** — top card for the Express upload (§7.8) with the last upload's summary and the
   unmatched count, then the existing worklist.
6. **บันทึกรับชำระ** becomes the fallback for unmatched receipts: amount pre-filled with the outstanding amount of the
   open notice/invoice, kind pre-selected, note collapsed. **ยืนยันรับมัดจำ** happens on its own when an Express
   receipt matches the deposit notice; the manual confirm stays for unmatched cases.
7. Receipts show their source (Express / บันทึกเอง) on the finance deal page.
8. The unreachable sales-page payment dialog is removed (dead UI; account uses `/finance/deals/:id`).

Unchanged in this round: quotation editor and approval dialogs, commission pages, CEO settings, price import /
factory management, pricing approvals, IR / order email drafts, lead-time change.

Permission note: item 3 removes the advance control from `/import/deals/:id` and the CEO deal tab in the UI only; the
backend endpoint and its role gate are unchanged (no authz change). Item 8 removes UI only.

Rollout: slice 5 (import & account UI), after slice 3 because item 5–6 depend on the Express upload.

**Deferred from item 3 (slice 5 review, 2026-11-15, decided by the coordinator for the owner):**

- *"Shipment ETA feeds the rep's deliveries-scheduled cash line"* — DEFERRED. The rep's cash card reads the rep-entered
  expected payments (§7.7), whose week is chosen by the rep; tying it to a factory row's derived ETA needs a rule for
  which expected payment a shipment moves (per deal, per factory, partial deliveries), and slice 3 is still changing
  that table (V214 `received_amount`). It belongs with slice 3's follow-up, not this UI slice.
- *"An undo backed by a reversible event"* — DEFERRED in its full form. Slice 5 ships a 30-second undo of the
  actor's own latest advance (V216: the row remembers the stamp it replaced; the undo is logged as an
  `IMPORT_STEP_ADVANCED` event with a "เลิกทำ" message). A true reversible event — an event log the step is derived
  from, replayable and undoable at any depth — would change how every reader of `import_step` works and would add a
  `ticket_event.kind`, whose CHECK constraint is re-declared as a full list and so races concurrently numbered
  migrations. Revisit when the import step gets an event-sourced model of its own.

## 8. Decided by the owner

- Approach 1.
- No weekly email; manager and CEO track directly.
- Express has no API — receipts come in by file upload from account.
- Tracking focus: who holds which deal, stale deals, visible visits and visit planning, expected payments.
- UI must be calm and non-intimidating.
- **The Express upload replaces account's manual receipt entry** (`sales.payment_receipt`). Uploaded receipts are
  written as `payment_receipt` rows, deduplicated by Express document number, so cash is counted once. Manual entry
  remains only to resolve receipts the upload could not match. Until a sample export exists (below), manual entry
  continues unchanged.
- **No early release path.** The work lands on `develop` and reaches reps when sales is unlocked for them as part of
  the normal release; no partial unlock is built for it.
- **Screens show the reps' S1–S9 codes**, not the S1–S20 sheet numbers (mapping in §7.1).

## 9. My calls (owner can override)

- Stale threshold 14 days, configurable.
- "My work" becomes the default landing for the sales role; the old `/tickets` list stays reachable.
- Fictional names/numbers in the mockup are illustrative only.
- Expected payments are a new table rather than columns on the ticket.
- **"Quotation issued" trigger** = the quotation reaching its final approved state: `APPROVED` for `DEAL_DIRECT`
  (what the generator produces; `DealQuotationRepository` stops there) and `ISSUED` for `PRICING_REQUEST` origin.
  Deal fill writes only **empty** deal fields (never overwrites what the rep typed); deal value = total of the
  latest approved quotation on the deal.
- **Readiness gate**: system advances (quotation, payment, IR, delivery) skip the `next_follow_up_at` / "activity
  since last stage change" gate, as the existing auto-advanced stages already do; a rep's manual move keeps it. The
  existing 7-day activity-only `isStale` is replaced by the §7.5 rule everywhere, so the app has one meaning of
  "quiet". Both are workflow changes and go in the PR body.
- **"Close this deal (lost)"** stays one tap to open, but still asks for the `DealLostReason` (F1–F8) picker —
  lost-reason reporting is kept.
- **Forecast** = sum of expected-payment rows due in the month, unweighted by %, matching the weekly email's
  "forecast for the month" line (archive of weekly emails is the reference).

## 10. Left to the builder

- Visit storage shape (extend `deal_activity` vs new table).
- Exact Express export parsing (columns), once a sample file is supplied.
- Component structure, Tailwind classes, chart implementation (follow the dataviz palette already validated:
  `#4f46e5, #0284c7, #14b8a6` with labels and legend).
- Which permission checks gate the team overview (sales_manager + ceo at minimum) — enforced and integration-tested
  in the Java service.

## 11. Open questions

- **Express export sample (blocks slice 3 only).** No sample RE/AI/IV export exists yet. The matching rules in §7.8
  are provisional until one arrives; slices 1, 2 and 4 do not depend on it.
- **Imported-deal ownership.** Which app user owns each rep's workbook rows is settled when slice 4 is planned.
- **Auto-close on receipt (my call):** a matched receipt records the payment and can move a deal to
  DEPOSIT_RECEIVED through the existing payment track, but CLOSED_PAID still goes through the existing CEO close
  flow — no deal closes without a human step.
