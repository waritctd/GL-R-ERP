// Deal-create modal's "ราคาตั้ง (ประมาณการ)" pure math. Deliberately kept out of JSX/component
// state so it is trivially unit-testable and cannot accidentally read component state by mistake
// — see TicketCreateModal.jsx (renderItemEditor / renderItemsView / renderReviewView) for where
// this feeds the UI.
//
// Mirrors the unit-basis lesson backend/src/main/java/th/co/glr/hr/pricingcosting/
// PricingCostingService.java:190-243 documents (Finding B, financial-integrity review): a
// factory's quoted price and the requested quantity can each be expressed in a DIFFERENT unit
// basis, and multiplying them without first putting both on the same basis silently produces the
// wrong number — that file's worked example is 1,000 THB/box, 20 pieces/box, 10 boxes requested
// computing as 500 instead of 10,000 when the code treated the requested qty as if it were
// already priced per-piece. This helper takes the conservative side of that lesson: rather than
// attempt a conversion for every possible catalog `priceUnit`, it computes ONLY the two bases it
// can convert exactly against data this modal actually has —
//   - per_sqm   against the item's own total ตร.ม. (item.qtySqm)
//   - per_piece against the item's own piece count (item.qty)
// and returns null for everything else (per_box, per_linear_m, unknown, no priceUnit at all —
// converting those needs a pieces-per-box / linear-metres-per-piece factor this modal does not
// collect). A confidently wrong number that a rep reads out to a customer is worse than an honest
// "ยังคำนวณไม่ได้" here.
//
// This is a coarse, CEO-configurable DISPLAY multiplier on top of the raw catalog/supplier price
// (backend/src/main/resources/db/migration/V112__deal_estimate_markup.sql) — it is NOT
// sales.price_calc_config.margin_pct, the real margin policy, and the number it produces is NOT
// the real selling price either (that still comes out of the pricing-request → CEO costing
// chain — see the existing "ราคาอ้างอิง (แคตตาล็อก)" caveat text this sits next to). Callers must
// never fall back to markupMultiplier = 1 when the CEO's configured value is unavailable: an
// un-marked-up supplier cost displayed as "ราคาตั้ง" is exactly the number the product owner said
// must never appear on screen.

/**
 * Every "not computable" reason this helper can return, mapped to the UI copy that explains it
 * (see TicketCreateModal.jsx). Keep this list and the switch below in sync — an unmapped reason
 * falls back to a generic message rather than throwing, but a rep deserves the specific one.
 * @typedef {'NOT_CATALOG'|'NO_MARKUP'|'NO_CATALOG_PRICE'|'NO_FX_RATE'|'UNIT_BASIS_MISMATCH'|'UNSUPPORTED_UNIT'|'MISSING_QUANTITY'} EstimateNotComputableReason
 */

/**
 * @param {object} item a TicketCreateModal item row (see emptyItem()/applyCatalogItem()).
 * @param {{ fxRatesByCurrency: Record<string, number>, markupMultiplier: number|null }} context
 * @returns {{ ok: true, unitThb: number, total: number } | { ok: false, reason: EstimateNotComputableReason }}
 *   `ok: false` means "not computable" — read `reason`, never infer a cause from a numeric 0.
 */
export function computeItemEstimateThb(item, { fxRatesByCurrency, markupMultiplier } = {}) {
  if (!item || item.source !== 'catalog') return { ok: false, reason: 'NOT_CATALOG' };
  if (markupMultiplier == null || !(Number(markupMultiplier) > 0)) return { ok: false, reason: 'NO_MARKUP' };
  if (item.catalogPrice == null || item.catalogPrice === '') return { ok: false, reason: 'NO_CATALOG_PRICE' };

  const rawPrice = Number(item.catalogPrice);
  if (!Number.isFinite(rawPrice) || rawPrice < 0) return { ok: false, reason: 'NO_CATALOG_PRICE' };

  const currency = item.catalogCurrency || 'THB';
  const fxRate = currency === 'THB' ? 1 : fxRatesByCurrency?.[currency];
  if (fxRate == null || !(Number(fxRate) > 0)) return { ok: false, reason: 'NO_FX_RATE' };

  // unitBasis says which quantity field the user is actually maintaining right now (the other
  // one may be stale — e.g. still emptyItem()'s qty: 1 — unless sqmPerPiece lets
  // TicketCreateModal's updateItem() cross-fill both in lockstep). If catalogPriceUnit needs the
  // field the user is NOT maintaining and there is no exact conversion available, that stale
  // value must not be trusted into the math — see PricingCostingService.java:190-243 lesson in
  // the module doc above.
  const basis = item.unitBasis || 'PIECE';
  let qty;
  if (item.catalogPriceUnit === 'per_sqm') {
    if (basis === 'PIECE' && !item.sqmPerPiece) return { ok: false, reason: 'UNIT_BASIS_MISMATCH' };
    qty = Number(item.qtySqm);
  } else if (item.catalogPriceUnit === 'per_piece') {
    if (basis === 'SQM' && !item.sqmPerPiece) return { ok: false, reason: 'UNIT_BASIS_MISMATCH' };
    qty = Number(item.qty);
  } else {
    // per_box / per_linear_m / 'unknown' / null — no exact conversion available here.
    return { ok: false, reason: 'UNSUPPORTED_UNIT' };
  }
  if (!Number.isFinite(qty) || qty <= 0) return { ok: false, reason: 'MISSING_QUANTITY' };

  const unitThb = rawPrice * Number(fxRate) * Number(markupMultiplier);
  const total = unitThb * qty;
  return { ok: true, unitThb, total };
}

/**
 * Rolls the per-item results up for the items list / review grand total. `total` only ever sums
 * the lines that ARE computable; `uncomputableCount` is how many items in `items` were not
 * (custom items with no catalog price included), so the UI can say so next to the number instead
 * of silently under-totalling — see the module doc above. `total` is `null` (never 0) whenever
 * NOTHING computed — an empty cart or an all-uncomputable one — so a caller cannot render a bold
 * "0.00 บาท" that looks like a real, computed answer.
 */
export function summarizeItemsEstimate(items, context) {
  const list = items ?? [];
  let total = 0;
  let uncomputableCount = 0;
  const perItem = list.map((item) => {
    const result = computeItemEstimateThb(item, context);
    if (result.ok) {
      total += result.total;
    } else {
      uncomputableCount += 1;
    }
    return result;
  });
  const nothingComputed = list.length === 0 || uncomputableCount === list.length;
  return { perItem, total: nothingComputed ? null : total, uncomputableCount };
}

/** Thai-locale 2dp money string, shared by every display site so formatting stays identical. */
export function formatThb(value) {
  return Number(value).toLocaleString('th-TH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
