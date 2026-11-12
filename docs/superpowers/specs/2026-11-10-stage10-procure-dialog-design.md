# Stage 10 "จัดซื้อสินค้า" — one button to choose import vs stock

Date: 2026-11-10 · Owner: Ploy · Status: draft for approval

## Why

At stage 10 (ORDER_RECEIVED, ได้รับใบสั่งซื้อ) the rep must decide, per line, whether the goods are
imported (issue an IR) or shipped from Thai stock. Today:

- The main button is "ออกใบขอซื้อ (IR)" (`salesActions.js` `importRequestAction`). Stock is a separate
  per-line "จากสต็อก" button on the จัดซื้อ-ส่งมอบ tab (`DealFulfilmentPanel` → `TicketService.reserveStock`).
  Nothing on the main button tells the rep stock is an option.
- Direct quotations gained a per-line จากสต็อก tick on 2026-11-07 (#1256, shipped in release #1260).
  When the order is confirmed, ticked lines are covered automatically (`coverSourcedFromStockLines`).
- Quotations issued **before** that release have no tick. "No tick" reads as "import", so an all-stock
  old deal still pushes the rep to issue an IR for goods already in the warehouse.
- Prod on 2026-11-10: 15 active deals at stage 10 with no fulfilment status yet. All are direct
  quotations issued before 2026-11-07, none has a stock line. 10 have no deposit (button live now),
  5 are waiting on a deposit.

`qty_from_stock` is also the rep's STOCK_BONUS commission input, and nothing corroborates it. That is
why, on a new deal, the quotation's split stands and the rep cannot change it here.

## Goals

- One main button, **"จัดซื้อสินค้า"**, at stage 10 on every deal, replacing "ออกใบขอซื้อ (IR)" as the
  main button. It opens one dialog where the rep confirms where each line comes from.
- **New deals** (quotation had the stock option): the dialog shows the quotation's split, read-only. The
  rep can only confirm it.
- **Old deals** (quotation predates the stock option): the rep chooses each line, and confirming takes
  effect straight away, including stock lines. The manager is notified, as today.
- **Locked once confirmed** (Ploy): after ยืนยัน the split cannot be changed by the rep.
- After confirming, the deal continues on the existing paths: IR for import lines, stock cover for the
  rest (all-stock → FROM_STOCK → DELIVERY_SCHEDULING, as `reserveStock` does now).

## Non-goals

- **Changing a new deal's split, and any approval or repricing step.** Deferred (see Follow-up).
- No stock ledger or availability check (deliberately out of scope; see `TicketService.reserveStock` javadoc).
- No change to how IRs are made: factory choice, one IR per factory, PDF download and the IR confirmation
  stay as they are on the จัดซื้อ-ส่งมอบ tab.
- No change to the deposit gate. The button appears exactly when the IR button would today
  (`importRequestAction`'s conditions, which already handle deposit-required vs not-required).
- Commission math is unchanged.

## Anti-goals (failures even if it "works")

- A rep changing a new deal's split, or changing any deal's split after ยืนยัน, through any control. That
  includes the existing per-line "จากสต็อก" button and a direct API call.
- An IR issued on an old deal before the rep has confirmed the split in the dialog.

## The dialog (approved mockups: `.superpowers/brainstorm/…/stage10-cta-v3.html` and `-v4.html`, local only)

Matches `components/common/Modal.jsx`: title "จัดซื้อสินค้า" in the header with **×** to close, and no
ยกเลิก button. **The product list is always shown**: one row per line with product, quantity and its source.
Stock means the whole line (`qty_from_stock = qty`), as the quotation tick does today. No partial quantities.

**New deal:** the quick buttons (สั่งนำเข้า / จากสต็อก / แยกรายการ) show the quotation's split, dimmed and
read-only. Each line shows a tag (นำเข้า or สต็อก), not a switch. A small line reads
"ตามใบเสนอราคา <number>". One button, **ยืนยัน**.

**Old deal:** the quick buttons are live. สั่งนำเข้า and จากสต็อก set every line in one click; switching
lines by hand so they differ selects แยกรายการ automatically. Each line has a นำเข้า | สต็อก switch, all
starting on นำเข้า. One button, **ยืนยัน**. Stock lines are covered with the same write and the same manager
notice as `reserveStock` today.

**ยืนยัน, on either kind of deal:** records that the split is decided, then:
- if any line is import → continue into the existing IR flow (the จัดซื้อ-ส่งมอบ tab's IR confirmation);
- if every line is stock → the deal moves to นัดส่งสินค้า, as `reserveStock` does today.

On a new deal, lines ticked as stock were already covered at order confirmation, so ยืนยัน writes no stock
change; it only records the decision.

## Locked once decided

After ยืนยัน, the main button moves on to the next step (the IR flow, or นัดส่งสินค้า), and the dialog does not
reopen. The backend refuses any further split change from the `sales` role, on old deals too. The rep's
per-line "จากสต็อก" button on the จัดซื้อ-ส่งมอบ tab is removed on every deal (the dialog is the only way in).
Only the CEO can still correct a line afterwards, through the existing per-line control (today's "CEO may
correct" rule).

## "Old" vs "new" deal

The data cannot tell an old quotation from a new all-import one: both have `stock_source` null on every
line. Rule: a deal is **old** when the direct quotation its order was confirmed from was issued (Bangkok
date) before 2026-11-07, when the stock option shipped. Everything else is **new**, including
pricing-request (PCR) deals, whose stock source comes from the pricing request. On PCR deals, IN_TRANSIT
lines count as import (นำเข้า), as they do at order confirmation today.

## Other text that changes

- Header note beside the button: "เลือกว่าสินค้าสั่งนำเข้าหรือมาจากสต็อก" (old deals) — new deals can use
  "ตรวจที่มาสินค้าตามใบเสนอราคา".
- Header chip under การนำเข้า before ยืนยัน: "ยังไม่ได้จัดซื้อ".
- Pipeline panel "ถัดไป" at stage 10 also names the stock path: "ถัดไป: 12. จัดซื้อและนำเข้าสินค้า เมื่อออกเลข
  ใบขอซื้อ (IR) · หรือ 13. นัดส่งสินค้า ถ้าสินค้ามาจากสต็อกทั้งหมด".

## Constraints

- Test first (standing rule): tests are written and shown failing before any implementation, including the
  "cannot reach" cases: a rep changing a new deal's split, a rep changing any split after ยืนยัน, a rep
  using the per-line stock endpoint, and an IR on an old deal before ยืนยัน.
- Authorization is checked against the Java services, not the mock. Any new endpoint needs both API digests
  regenerated, and `mockApi.js` kept in step.
- Backend changes reach prod only with a new image.
- Public repo: no customer names in tests, fixtures or docs.

## Left to the builder

- Where "split decided" is stored (a column on the ticket is enough), and its migration number.
- How the dialog hands off to the existing IR flow (open the tab's IR confirmation, or call the same mutation).
- Whether old-deal ยืนยัน reuses `reserveStock` or a new endpoint that writes the split and the decision together.

## Follow-up (not in this change)

Changing a new deal's split: the rep proposes a change, and the sales manager or CEO either reprices it,
issuing a revised quotation to the customer, or rejects it with a reason; the rep may then propose again.
This needs its own design, because a quotation that an order was confirmed from cannot be revised today
(`DealQuotationService.requireRevisableStatus`).
