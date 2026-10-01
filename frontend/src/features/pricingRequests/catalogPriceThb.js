/**
 * The catalogue price of a pricing-request item in Thai baht, for the quantity that was requested.
 *
 * Only a per-ตร.ม. or per-แผ่น catalogue price is converted. A per-box / per-metre / unknown-unit
 * price cannot be turned into a total without guessing, so it returns null and the caller shows
 * nothing rather than a wrong figure. Also null when there is no FX rate, no sqmPerPiece (needed to
 * move between แผ่น and ตร.ม.) or no requested quantity.
 *
 * Raw price x FX x quantity only — no duty, freight or margin; that is the costing's job.
 */
const positive = (value) => (Number(value) > 0 ? Number(value) : null);

export function catalogPriceInThb(item, fxRates) {
  const unit = item?.catalogPriceUnit;
  if (unit !== 'per_sqm' && unit !== 'per_piece') return null;
  const price = positive(item.catalogBasePrice);
  const sqmPerPiece = positive(item.sqmPerPiece);
  const rate = (fxRates ?? []).find((fx) => fx.currency === item.catalogCurrency)?.rateToThb;
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
