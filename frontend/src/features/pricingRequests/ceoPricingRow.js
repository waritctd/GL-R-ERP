// Pure money/preview math for one row of the CEO pricing workspace (no React, no JSX).
// The server stays authoritative; everything here is a preview or a dirty-vs-saved comparison.

export function round2(n) {
  return Math.round((Number(n) + Number.EPSILON) * 100) / 100;
}

export function cleanNumber(value) {
  if (value === '' || value == null) return null;
  return Number(value);
}

// Margin is TYPED and SHOWN as a percent (30) in the CEO workspace but STORED as a fraction (0.30):
// these two are the only conversion points. toFixed(6) strips float noise (0.29 * 100 = 28.999999999999996).
export function pctToFraction(value) {
  const n = cleanNumber(value);
  return n == null ? null : n / 100;
}
export function fractionToPct(value) {
  if (value == null || value === '') return '';
  return String(Number((Number(value) * 100).toFixed(6)));
}

/**
 * The margin bound, mirroring PricingDecisionService#requireValidMargin exactly: a markup fraction
 * must be > -1 (the selling price is never negative) AND <= MAX_MARGIN_PCT (the server's own raw
 * upper guard; deriveListAndNet then also rejects a computed price past the NUMERIC column, which a
 * margin this far below that ceiling never reaches on a real cost). One helper so the legacy per-line
 * field and the new-form NET input cannot drift on either the bound or the message. */
const MAX_MARGIN_PCT = 999.999999;
export function marginOutOfRange(n) {
  return n == null || Number.isNaN(n) || n <= -1 || n > MAX_MARGIN_PCT;
}

/**
 * Realized per-unit margin for a decision line (owner request 2026-10-21): the NET price the
 * customer actually pays (in the decision's currency) minus the frozen LANDED cost (THB), converted
 * to THB with the decision's own FX rate. Returns null when either side is missing (an uncostable
 * line, or a line with no price yet). {@code pct} is of COST (the same markup basis as the
 * อัตรากำไร input).
 */
export function lineProfitThb(costThb, netPriceInCurrency, fxRate) {
  if (costThb == null || netPriceInCurrency == null || netPriceInCurrency === '') return null;
  const netThb = Number(netPriceInCurrency) * (Number(fxRate) || 1);
  const cost = Number(costThb);
  if (!Number.isFinite(netThb) || !Number.isFinite(cost)) return null;
  const profitPerUnit = netThb - cost;
  return { profitPerUnit, pct: cost > 0 ? (profitPerUnit / cost) * 100 : null };
}

/**
 * ราคาพิเศษ บาท/ตร.ม. (รวม VAT) that reproduces the formula price per แผ่น — the INVERSE of
 * WastageCalculator#netPerPieceFromSpecialSqm (net = round2((special / 1.07) / round2(1 / sqmPerPiece))),
 * using the same 2dp ตร.ม.->แผ่น reciprocal, so saving it gives back the formula price. 7% = the Thai
 * document's VAT. null when either input is missing.
 */
function specialPriceSqmFromFormula(pricePerPiece, sqmPerPiece) {
  const price = Number(pricePerPiece);
  const sqm = Number(sqmPerPiece);
  if (!(price > 0) || !(sqm > 0)) return null;
  return round2(price * round2(1 / sqm) * 1.07);
}

/**
 * A CLIENT-SIDE PREVIEW ONLY — the server (PricingDecisionService#computeNetUnitPrice, reusing
 * WastageCalculator) is authoritative and recomputes on every save; this never feeds a stored
 * value. NET's `round2(list × (1 − pct/100))` has no rounding-order subtlety, so it is safe to
 * mirror. SPECIAL_SQM's net-per-piece derivation (`WastageCalculator#netPerPieceFromSpecialSqm`) is
 * deliberately NOT mirrored here — its two-step rounding order IS the algorithm — so this returns
 * `null` for it. The UI shows "คำนวณเมื่อบันทึก" for that mode instead of a number.
 */
export function previewCeoNetUnitPrice(priceMode, { listUnitPrice, discountPct, directNetPrice } = {}) {
  if (priceMode === 'NET') {
    if (listUnitPrice === '' || listUnitPrice == null) return null;
    const discount = discountPct === '' || discountPct == null ? 0 : Number(discountPct);
    return round2(Number(listUnitPrice) * (1 - discount / 100));
  }
  if (priceMode === 'DIRECT_NET') {
    if (directNetPrice === '' || directNetPrice == null) return null;
    return round2(Number(directNetPrice));
  }
  return null;
}

// What the server has saved for each user-typed draft key, in the units the input is typed in.
const SAVED_FOR_DRAFT_KEY = {
  marginPct: (item) => cleanNumber(fractionToPct(item.proposedMarginPct)),
  discountPct: (item) => cleanNumber(item.discountPct),
  specialPriceSqm: (item) => cleanNumber(item.specialPriceSqm),
  directNetPrice: (item) => cleanNumber(item.directNetPrice),
};
// A typed draft counts only if it differs from the saved value (typing the saved value back is no edit).
function draftKeyChanged(item, key, value) {
  const savedOf = SAVED_FOR_DRAFT_KEY[key];
  return savedOf == null || cleanNumber(value) !== savedOf(item);
}

/**
 * Every figure a CEO row shows, derived from the decision item + the CEO's unsaved drafts.
 * `sellingBuffer` is the formula config's buffer (NaN/0 until it loads: no margin-driven preview).
 */
export function ceoRowFigures({ decision, item, rawDraft = {}, cost, editable, sellingBuffer }) {
  const isStockLine = item.stockSource != null;
  const hasPriceOverride = item.manualSellingPricePerRequestedUnit != null;
  const currentList = hasPriceOverride ? item.manualSellingPricePerRequestedUnit : item.listUnitPrice;
  const marginDraftRaw = (!isStockLine && decision.priceMode === 'NET') ? rawDraft.marginPct : undefined;
  const marginDraftFraction = pctToFraction(marginDraftRaw);
  const marginDrives = marginDraftRaw != null && marginDraftRaw !== '' && !hasPriceOverride && cost != null;
  const marginInvalid = marginDrives && marginOutOfRange(marginDraftFraction);
  const formulaFromMargin = marginDrives && !marginInvalid && sellingBuffer > 0
    ? round2(round2(cost * (1 + marginDraftFraction) * sellingBuffer)
      / (decision.currency === 'THB' ? 1 : (Number(decision.fxRateUsed) || 1)))
    : null;
  const formulaPerPiece = formulaFromMargin ?? item.proposedSellingPricePerRequestedUnit;
  // Until a ราคาพิเศษ is saved (or typed), the formula price pre-fills it.
  const autoSpecial = decision.priceMode === 'SPECIAL_SQM' && !isStockLine && editable
    && item.specialPriceSqm == null && decision.currency === 'THB'
    ? specialPriceSqmFromFormula(formulaPerPiece, item.sqmPerPiece)
    : null;
  const draft = { ...(autoSpecial != null ? { specialPriceSqm: autoSpecial } : {}), ...rawDraft };
  const listDraft = draft.listUnitPrice;
  const listDraftNumber = cleanNumber(listDraft);
  // Java rejects a non-positive ราคาตั้ง; blank is NOT invalid (on an override line it means "clear").
  const listInvalid = !marginDrives && listDraft != null && listDraft !== '' && !(listDraftNumber > 0);
  const previewList = formulaFromMargin != null ? formulaFromMargin
    : (listDraftNumber != null && !listInvalid ? listDraftNumber
      : (listDraft === '' && hasPriceOverride && !isStockLine) ? item.listUnitPrice
        : currentList);
  const previewNet = previewCeoNetUnitPrice(decision.priceMode, {
    listUnitPrice: previewList,
    discountPct: draft.discountPct ?? item.discountPct,
    directNetPrice: draft.directNetPrice ?? item.directNetPrice,
  });
  // The formula prefill is a value that must be saved to price the line, so it counts as pending.
  const autoSpecialPending = autoSpecial != null && rawDraft.specialPriceSqm === undefined;
  const otherDraftDirty = autoSpecialPending
    || Object.entries(rawDraft).some(([k, v]) => k !== 'listUnitPrice' && draftKeyChanged(item, k, v));
  // A pending draft always wins over the last-saved net; a ราคาตั้ง draft only moves the net under NET.
  const hasPendingDraft = otherDraftDirty || (decision.priceMode === 'NET' && 'listUnitPrice' in draft);
  const net = hasPendingDraft ? previewNet : item.netUnitPrice;
  // SPECIAL_SQM's net is never previewed client-side (two-step rounding IS the algorithm).
  const netPending = hasPendingDraft && net == null && decision.priceMode === 'SPECIAL_SQM';
  const qty = Number(item.requestedQuantity);
  const total = net != null ? round2(qty * net) : null;
  const profit = netPending ? null : lineProfitThb(cost, net ?? item.netUnitPrice, decision.fxRateUsed);
  const listChanged = listDraft !== undefined && !listInvalid && (listDraft === ''
    ? (!isStockLine && hasPriceOverride)
    : currentList == null || listDraftNumber !== Number(currentList));
  const invalid = listInvalid || marginInvalid;
  const dirty = invalid || otherDraftDirty || listChanged;
  return {
    isStockLine, hasPriceOverride, currentList, marginDraftRaw, marginDrives, marginInvalid, formulaFromMargin,
    formulaPerPiece, draft, autoSpecialPending, listDraft, listInvalid, net, netPending, total, profit, qty, invalid, dirty,
  };
}
