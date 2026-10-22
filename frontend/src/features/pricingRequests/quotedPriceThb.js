/**
 * Import's quoted factory price for one item, in Thai baht, for the quantity that was requested.
 *
 * `quoteItem` is the item on the CURRENT factory quote (rawUnitPrice / currency / unitBasis typed by
 * Import); `requestItem` is Sales' line (quantities, sqmPerPiece). Only a per-ตร.ม. or per-แผ่น
 * price is converted — a per-box / per-metre price cannot become a total without guessing, so it
 * returns null and the caller shows nothing. Also null when there is no price yet, no FX rate, no
 * ตร.ม./แผ่น to convert between แผ่น and ตร.ม., or no requested quantity.
 *
 * Raw price x FX x quantity only — no duty, freight or margin; that is the costing's job.
 */
const positive = (value) => (Number(value) > 0 ? Number(value) : null);

export function quotedPriceInThb(quoteItem, requestItem, fxRates) {
  const basis = quoteItem?.unitBasis;
  if (basis !== 'PER_SQM' && basis !== 'PER_PIECE') return null;
  const unit = basis === 'PER_SQM' ? 'per_sqm' : 'per_piece';
  const price = positive(quoteItem.rawUnitPrice);
  const sqmPerPiece = positive(quoteItem.sqmPerUnit) ?? positive(requestItem?.sqmPerPiece);
  const item = requestItem ?? {};
  const rate = (fxRates ?? []).find((fx) => fx.currency === quoteItem.currency)?.rateToThb;
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
