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

// Half-up to 10dp: the scale WastageCalculator carries its intermediate figures at.
function round10(n) {
  return Math.round((Number(n) + Number.EPSILON) * 1e10) / 1e10;
}
/** Thai VAT on the document — the same 7% WastageCalculator strips from a ราคาพิเศษ. */
export const VAT_FACTOR = 1.07;

/**
 * ราคาพิเศษ บาท/ตร.ม. (รวม VAT) from the formula, per the owner's own worked rule (2026-11-06):
 *   ทุน/ตร.ม. × (1 + กำไร) × selling buffer × VAT 1.07 = ราคาพิเศษ/ตร.ม. รวม VAT
 * e.g. 953.72 × 1.3 × 1.07 = 1,326.63 with the selling buffer at 1.00. The formula price
 * (ราคาตั้ง) is BEFORE VAT — the quotation adds VAT to it — so VAT is applied here exactly once,
 * as the fixed 7% the server strips back off (WastageCalculator#netPerPieceFromSpecialSqm). The
 * selling buffer stays a buffer (CEO settings: "ไม่ใช่ VAT"), owner-set to 1.00 on 2026-11-06.
 * Worked per ตร.ม. straight from the cost so no per-แผ่น rounding creeps in. Falls back to the
 * formula price per แผ่น × แผ่น/ตร.ม. × VAT when there is no cost/margin/buffer.
 * null when there is nothing to derive it from.
 */
export function specialPriceSqmFromFormula({ cost, marginFraction, sellingBuffer, pricePerPiece, sqmPerPiece }) {
  const sqm = Number(sqmPerPiece);
  if (!(sqm > 0)) return null;
  if (cost != null && marginFraction != null && sellingBuffer > 0 && Number(cost) > 0) {
    return round2((Number(cost) / sqm) * (1 + Number(marginFraction)) * Number(sellingBuffer) * VAT_FACTOR);
  }
  const price = Number(pricePerPiece);
  if (pricePerPiece == null || !(price > 0)) return null;
  return round2(price * round2(1 / sqm) * VAT_FACTOR);
}

/**
 * Mirror of WastageCalculator#netPerPieceFromSpecialSqm, same rounding ORDER:
 *   piecesPerSqm = round2(round10(1 / sqmPerPiece))
 *   net          = round2( round10(special / 1.07) / piecesPerSqm )
 * A preview only — the server recomputes on save and is authoritative.
 */
export function netPerPieceFromSpecialSqm(specialPerSqmIncVat, sqmPerPiece) {
  const special = Number(specialPerSqmIncVat);
  const sqm = Number(sqmPerPiece);
  if (specialPerSqmIncVat === '' || specialPerSqmIncVat == null || !(special > 0) || !(sqm > 0)) return null;
  const piecesPerSqm = round2(round10(1 / sqm));
  if (!(piecesPerSqm > 0)) return null;
  return round2(round10(round10(special / VAT_FACTOR) / piecesPerSqm));
}

/**
 * A CLIENT-SIDE PREVIEW ONLY — the server (PricingDecisionService#computeNetUnitPrice, reusing
 * WastageCalculator) is authoritative and recomputes on every save; this never feeds a stored
 * value. SPECIAL_SQM mirrors WastageCalculator's two-step rounding (netPerPieceFromSpecialSqm).
 */
export function previewCeoNetUnitPrice(priceMode, { listUnitPrice, discountPct, directNetPrice, specialPriceSqm, sqmPerPiece } = {}) {
  if (priceMode === 'NET') {
    if (listUnitPrice === '' || listUnitPrice == null) return null;
    const discount = discountPct === '' || discountPct == null ? 0 : Number(discountPct);
    return round2(Number(listUnitPrice) * (1 - discount / 100));
  }
  if (priceMode === 'DIRECT_NET') {
    if (directNetPrice === '' || directNetPrice == null) return null;
    return round2(Number(directNetPrice));
  }
  if (priceMode === 'SPECIAL_SQM') return netPerPieceFromSpecialSqm(specialPriceSqm, sqmPerPiece);
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
  // กำไร % drives the price under NET (ราคาตั้ง) and SPECIAL_SQM (ราคาพิเศษ, THB only — it is บาท/ตร.ม.).
  const isSpecial = decision.priceMode === 'SPECIAL_SQM';
  const marginMode = decision.priceMode === 'NET' || (isSpecial && decision.currency === 'THB');
  const marginDraftRaw = (!isStockLine && marginMode) ? rawDraft.marginPct : undefined;
  const marginDraftFraction = pctToFraction(marginDraftRaw);
  const marginDrives = marginDraftRaw != null && marginDraftRaw !== '' && !hasPriceOverride && cost != null;
  const marginInvalid = marginDrives && marginOutOfRange(marginDraftFraction);
  const formulaFromMargin = marginDrives && !marginInvalid && sellingBuffer > 0
    ? round2(round2(cost * (1 + marginDraftFraction) * sellingBuffer)
      / (decision.currency === 'THB' ? 1 : (Number(decision.fxRateUsed) || 1)))
    : null;
  const formulaPerPiece = formulaFromMargin ?? item.proposedSellingPricePerRequestedUnit;
  // ราคาพิเศษ from the formula: a typed กำไร % always re-derives it; otherwise it pre-fills only
  // until a ราคาพิเศษ is saved. A typed ราคาพิเศษ (rawDraft) always wins — typing กำไร % drops it.
  const specialFromFormula = isSpecial && !isStockLine && editable && decision.currency === 'THB'
    && (marginDrives ? !marginInvalid : item.specialPriceSqm == null)
    ? specialPriceSqmFromFormula({
      cost,
      marginFraction: marginDrives ? marginDraftFraction : item.proposedMarginPct,
      sellingBuffer,
      pricePerPiece: marginDrives ? null : formulaPerPiece, // never a stale formula for a typed margin
      sqmPerPiece: item.sqmPerPiece,
    })
    : null;
  const autoSpecial = specialFromFormula != null
    && (item.specialPriceSqm == null || specialFromFormula !== Number(item.specialPriceSqm))
    ? specialFromFormula : null;
  // The reverse: a typed ราคาพิเศษ (and no typed กำไร %) implies the margin it is the formula price of.
  const typedSpecial = cleanNumber(rawDraft.specialPriceSqm);
  const sqmPerPiece = Number(item.sqmPerPiece);
  const impliedMarginFraction = isSpecial && !isStockLine && editable && decision.currency === 'THB'
    && !marginDrives && !hasPriceOverride && cost != null && Number(cost) > 0 && sellingBuffer > 0
    && sqmPerPiece > 0 && typedSpecial != null && typedSpecial > 0
    ? typedSpecial / ((Number(cost) / sqmPerPiece) * sellingBuffer * VAT_FACTOR) - 1
    : null;
  const impliedMarginPct = impliedMarginFraction != null ? (impliedMarginFraction * 100).toFixed(2) : null;
  const fmt2 = (v) => v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const impliedNote = impliedMarginFraction != null
    ? `= ${fmt2(typedSpecial)} ÷ (${fmt2(round2(Number(cost) / sqmPerPiece))}${
      sellingBuffer !== 1 ? ` × ${sellingBuffer}` : ''} × 1.07) − 1`
    : null;
  const impliedInvalid = impliedMarginFraction != null && marginOutOfRange(impliedMarginFraction);
  const draft = {
    ...(autoSpecial != null ? { specialPriceSqm: autoSpecial } : {}),
    ...(impliedMarginFraction != null && !impliedInvalid ? { marginPct: String(impliedMarginFraction * 100) } : {}),
    ...rawDraft,
  };
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
    specialPriceSqm: draft.specialPriceSqm ?? item.specialPriceSqm,
    sqmPerPiece: item.sqmPerPiece,
  });
  // The formula prefill is a value that must be saved to price the line, so it counts as pending.
  const autoSpecialPending = autoSpecial != null && rawDraft.specialPriceSqm === undefined;
  const otherDraftDirty = autoSpecialPending
    || Object.entries(rawDraft).some(([k, v]) => k !== 'listUnitPrice' && draftKeyChanged(item, k, v));
  // A pending draft always wins over the last-saved net; a ราคาตั้ง draft only moves the net under NET.
  const hasPendingDraft = otherDraftDirty || (decision.priceMode === 'NET' && 'listUnitPrice' in draft);
  const net = hasPendingDraft ? previewNet : item.netUnitPrice;
  // Only when there is no ราคาพิเศษ to preview from (e.g. blank, or no ตร.ม./แผ่น).
  const netPending = hasPendingDraft && net == null && isSpecial;
  const qty = Number(item.requestedQuantity);
  const total = net != null ? round2(qty * net) : null;
  const profit = netPending ? null : lineProfitThb(cost, net ?? item.netUnitPrice, decision.fxRateUsed);
  const listChanged = listDraft !== undefined && !listInvalid && (listDraft === ''
    ? (!isStockLine && hasPriceOverride)
    : currentList == null || listDraftNumber !== Number(currentList));
  const marginBad = marginInvalid || impliedInvalid;
  const invalid = listInvalid || marginBad;
  const dirty = invalid || otherDraftDirty || listChanged;
  return {
    isStockLine, hasPriceOverride, currentList, marginDraftRaw, marginDrives, marginInvalid: marginBad, formulaFromMargin,
    impliedMarginFraction, impliedMarginPct, impliedNote, impliedInvalid,
    formulaPerPiece, draft, autoSpecialPending, listDraft, listInvalid, net, netPending, total, profit, qty, invalid, dirty,
  };
}
