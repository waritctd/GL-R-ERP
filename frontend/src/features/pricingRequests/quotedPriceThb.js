/** Import's quoted factory price for one item, in Thai baht, for the quantity that was requested. Only a
 * per-ตร.ม. or per-แผ่น price is converted — a per-box / per-metre price cannot become a total without
 * guessing, so it returns null and the caller shows nothing. */
const positive = (value) => (Number(value) > 0 ? Number(value) : null);

/** The FX row for a currency, THB being 1 by definition (no row needed). */
export function fxRateFor(currency, fxRates) {
  return currency === 'THB' ? { currency: 'THB', rateToThb: 1 } : (fxRates ?? []).find((fx) => fx.currency === currency) ?? null;
}

/** Mirrors FxResolver.MAX_RATE_AGE_DAYS: costing refuses a non-THB rate older than this. */
export const MAX_FX_RATE_AGE_DAYS = 7;

export function isFxRateStale(fx, today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bangkok' }).format(new Date())) {
  if (!fx || fx.currency === 'THB') return false;
  if (!fx.effectiveDate) return true;
  return (Date.parse(today) - Date.parse(fx.effectiveDate)) / 86_400_000 > MAX_FX_RATE_AGE_DAYS;
}

export function quotedPriceInThb(quoteItem, requestItem, fxRates) {
  const basis = quoteItem?.unitBasis;
  if (basis !== 'PER_SQM' && basis !== 'PER_PIECE') return null;
  const unit = basis === 'PER_SQM' ? 'per_sqm' : 'per_piece';
  const price = positive(quoteItem.rawUnitPrice);
  const sqmPerPiece = positive(quoteItem.sqmPerUnit) ?? positive(requestItem?.sqmPerPiece);
  const item = requestItem ?? {};
  const rate = fxRateFor(quoteItem.currency, fxRates)?.rateToThb;
  if (price == null || sqmPerPiece == null || positive(rate) == null) return null;

  const pricePerUnitThb = price * Number(rate);
  const perSqm = unit === 'per_sqm' ? pricePerUnitThb : pricePerUnitThb / sqmPerPiece;
  const perPiece = unit === 'per_piece' ? pricePerUnitThb : pricePerUnitThb * sqmPerPiece;

  const pieces = positive(item.piecesAfterWastage) ?? (
    item.requestedUnitBasis === 'PER_PIECE' ? positive(item.requestedQty) : null
  );
  const sqm = positive(item.requestedQtySqm) ?? (pieces != null ? pieces * sqmPerPiece : null);
  if (pieces == null && sqm == null) return null;

  const total = unit === 'per_sqm'
    ? perSqm * (sqm ?? pieces * sqmPerPiece)
    : perPiece * (pieces ?? sqm / sqmPerPiece);
  return { perSqm, perPiece, total, pieces: pieces ?? sqm / sqmPerPiece, sqm: sqm ?? pieces * sqmPerPiece };
}
