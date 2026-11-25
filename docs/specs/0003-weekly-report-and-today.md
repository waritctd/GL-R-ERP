# 0003 Weekly report and the redesigned งานวันนี้

Scope feature 35 (weekly report replacement). Designs: the GL&R Portal Prototype canvas, boards `WR1`–`WR4` (weekly report, rep and team, desktop and phone) and `TD1`–`TD4` (งานวันนี้, rep and manager).

## Goal

Ploy, 2026-11-26: "make sales feel like they need to update their progress in the portal". The portal writes the weekly report the reps email by hand today (five money lines per week of the month, the ordered and expected job lists, the visit table). The dashboard shows each rep how complete their report data is and puts the update one tap away.

Rulings this spec follows:

- Money counts only for orders confirmed in the portal on or after 2026-11-16 (Ploy, 2026-11-19, "leave old money out"). Older deals show their stage only.
- No "งานไม่เคลื่อนไหว" / quiet-job measure on these screens, and no "the manager will see this" wording (Ploy, 2026-11-26).
- Thai copy is formal (รายการที่ควรติดตาม, รับชำระแล้ว, เลื่อนขั้น).
- A late expected payment is a slip, not an error.

## Decisions

Taken by this session while Ploy was away (Ploy: "any decision within yourself"). Each is reversible.

| # | Decision | Why |
|---|---|---|
| D1 | Reuse `SalesBoardService.cashForMonth` for the five money lines. Add one read endpoint, `GET /api/sales/weekly-report`, for what does not exist yet. No migration. | The five lines are already computed and reviewed; the gap is the order list, the week's counts and completeness. |
| D2 | The cutoff is a property, `app.sales.money-from` (default `2026-11-16`, empty = no cutoff). It applies in `CashRepository`, so the my-work and team-overview cash figures follow the same rule. | One rule for every money figure. A property keeps it reversible without a release. |
| D3 | An order's money counts when its confirmation time is on or after the cutoff (Bangkok midnight). The confirmation time is exactly what `TicketRepository.ORDER_CONFIRMED_AT_SQL` returns for the ticket; an add-on order with no confirmation event of its own uses its root's. No confirmation event means the money does not count (pre-portal orders, PRE_PORTAL deposits, receipts on unconfirmed deals): that is the ruling. | One definition of "order confirmed" in the codebase; no second rule to drift. |
| D4 | Money lines are cash, VAT included, as stored (receipts and expected payments are both VAT-inclusive cash). Job values use `amountPayable`, also VAT-inclusive. Screens label money "รวม VAT". | One basis on every money figure of the report. |
| D5 | A slip is an OPEN expected payment whose week is before the current Bangkok week (any month). The report's ledger is computed by the new endpoint, not by `CashMonth`: past weeks carry only money received; the current week carries its own OPEN rows plus every slip, each counted once; later weeks carry their own OPEN rows. Month total = received + still to come. There is no "moved N times": `expected_week` changes are not recorded. | `CashMonth.assemble` buckets by planned week and cannot carry a slip without double counting; no history exists without a migration. |
| D6 | Report completeness has two measures: ordered deals with a date for their next payment, and visits due this week with a result (DONE or CANCELLED). The billing-document measure in the design is dropped. | Whether an order needs a deposit notice depends on the deposit policy and the stage rules in `salesActions.js`; counting it on the server would fork that logic. The worklist still lists missing notices and invoices. |
| D7 | Weekly counts per rep: new deals, stage moves, quotations issued, orders confirmed, deals lost, visits done and planned, entries today, last entry. "Entry" = a stage change, a deal activity, a quotation issued or a visit marked done. | All exist as rows today. |
| D8 | A sales manager or the CEO sees the team; with `ownerId` they see that rep's report (scope `OWN`, no `today` list). A `sales` user always gets their own; an `ownerId` other than theirs is a 403; an unknown `ownerId` is a 404. Everything is attributed to the deal owner (`created_by`), as in `myWork` / `teamOverview`: a keyer's typing counts for the owner. Oversight readers (read-all grant) are not admitted, as for `teamOverview`. | Same scope as the existing board endpoints. |
| D9 | The dashboard gets a view switch (`?view=report`) for sales, sales_manager and ceo, beside the existing งานวันนี้ / วิเคราะห์ยอดขาย switch. | One page, as in the designs. |
| D10 | "Renovate the pieces": the redesigned งานวันนี้ reworks `SalesOverview` and `ManagerOverview` in place and reuses `nextSalesAction`, the visits API and the commission queue. MyWork's flows stay (call log, sample sent, visit tick, resume a paused deal); its ควรตามต่อ list and quiet filter leave the landing (Ploy's no-quiet ruling), and the full board stays reachable on the all-deals page. Removed pieces lose their tests; changed pieces have their tests revised. | Smallest complete change; nothing a rep uses today loses its home. |
| D12 | `orders` and every per-rep count are per order ticket (an add-on is its own row, owned by its own `created_by`), the same unit as the cash queries. The ยอดคาดหวัง table (before an order, no money) comes from `myWork().board` and stays folded per project. | Per-rep money and per-rep order counts reconcile. |
| D11 | Not built: the Excel download, the week stepper beyond the current month's weeks, warehouse and account rows in "รอฝ่ายอื่น". | Not in the data or not asked for. |

## API

`GET /api/sales/weekly-report?week=YYYY-MM-DD&ownerId=N`

- `week`: any date; the report uses the Monday of its ISO week (Bangkok). Default this week.
- Roles: `sales` (own only), `sales_manager`, `ceo`. Others 403.

Response:

```
{
  weekStart, weekEnd, moneyFrom,            // ISO dates; moneyFrom null when no cutoff
  scope: "OWN" | "TEAM",
  ledger: {                                 // the month holding weekStart's Thursday (same month rule as CashMonth)
    month,                                  // "2026-11"
    weeks: [ { weekStart, received, deliveredAwaiting, deliveriesScheduled, depositsExpected, slipped, isCurrent, isPast } ],
    received, deliveredAwaiting, deliveriesScheduled, depositsExpected, total   // month sums; total = line 1
  },                                        // the three OPEN buckets use CashRepository's existing stage/kind rules; slipped = part of the current week's buckets that came from earlier weeks
  orders: [ {                               // one row per order ticket: money-counted, lifecycle ACTIVE, or COMPLETED within the month
    ticketId, code, title, customerName, projectName, ownerId, ownerName, salesStage,
    orderConfirmedAt, value, received, outstanding,
    nextPayment: { id, kind, amount, week } | null,   // earliest OPEN expected payment, amount = still to come
    slipped                                  // nextPayment.week before weekStart
  } ],
  reps: [ {                                 // one row per rep in scope (one row for OWN)
    ownerId, name, newDeals, stageMoves, quotationsIssued, ordersConfirmed, lost,
    visitsDone, visitsPlanned, visitsDue, visitsDueWithResult,
    ordersOpen, ordersWithDate, entriesToday, lastEntryAt
  } ],
  today: [ { at, kind, label, ticketId, ticketTitle } ]   // the caller's own entries today, OWN scope only
}
```

## Slices

Each slice is end to end and shown running in the real app (demo profile, private local database) before the next starts.

1. **Rep weekly report.** Cutoff property in `CashRepository`; the endpoint (OWN scope); the dashboard switch for sales; `WeeklyReport` view (WR1 desktop, WR2 phone): money ledger by week, รายการที่ควรติดตาม, this week, the ordered table, the expected table (from `myWork().board`), visits by day.
2. **Team weekly report.** TEAM scope and `ownerId`; the switch for sales_manager and ceo; team view (WR3, WR4): team ledger, per-rep table, rows that open one rep's report, งานที่ขยับ, ยอดรับเงินที่เลื่อนกำหนด.
3. **Rep งานวันนี้** (TD1, TD2). Rework `SalesOverview`: รายงานสัปดาห์ของคุณ block (completeness + this week's entries), รายการที่ต้องอัปเดต grouped (money not in the report, customer waiting, due follow-up, other next steps), นัดหมายวันนี้, บันทึกแล้ววันนี้, รอฝ่ายอื่นดำเนินการ.
4. **Manager งานวันนี้** (TD3, TD4). Rework `ManagerOverview`: team completeness, รายการที่รอคุณดำเนินการ (quotations pending approval, commissions to review, slipped payments, a rep with no entries this week), team visits today, waiting on others (CEO pricing review, import), per-rep table.

## Acceptance

- AC1 A rep's ledger shows the five lines per week of the month; weeks sum to the month; a slipped OPEN payment appears in the current week tagged เลื่อนกำหนด.
- AC2 Money from a deal whose order was confirmed before 2026-11-16 is not counted anywhere (report, my-work cash, team-overview cash).
- AC3 A `sales` user cannot read another rep's report (403); manager and ceo can read the team and any rep.
- AC4 Completeness counts match the order and visit rows they summarise.
- AC5 Every row in รายการที่ต้องอัปเดต / รายการที่รอคุณดำเนินการ has a button that opens the action.
- AC6 Works at 390px and 1280px: no sideways scroll except inside the ledger and job tables, phone targets at least 44px.
- AC7 No screen in this spec shows a quiet-job count or says the manager will see something.

## Tests

API changes touch both sides: `hrApi.js` + `routes.js` + `queryKeys.js`, a `mockApi.js` twin naming the Java class it mirrors, and the digests (`api-surface`, `response-fields`, `ui-reachable`).

The cutoff property is empty in the test profile so existing cash tests keep their seeds; the new integration test sets it explicitly. The demo profile keeps the default, so the walk exercises the real rule.

Few, after the code. One integration test for the endpoint (scope, cutoff, slip, counts); revise the existing dashboard tests the rework breaks, and delete the ones for removed pieces. Contract digests regenerated.
