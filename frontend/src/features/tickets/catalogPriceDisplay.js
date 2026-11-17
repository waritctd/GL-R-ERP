// Catalog price display helpers — plain presentation, deliberately NOT pricing math. What a rep actually asked
// for is the far simpler thing this module does: show the catalog's own price in the currency it is quoted in,
// plus a companion figure in baht.

/** Thai-locale 2dp money string, shared by every display site so formatting stays identical. */
export function formatThb(value) {
  return Number(value).toLocaleString('th-TH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** Converts `amount` from `currency` into THB using the supplied rate table. Returns `null` — never a number — whenever the conversion cannot be made honestly: no amount, a non-finite/negative amount, or no usable rate for that currency.
 * @param {number|string|null} amount
 * @param {string|null} currency   ISO code as the catalog stores it ('EUR', 'USD', 'THB'…)
 * @param {Record<string, number>} fxRatesByCurrency  rate table, THB-relative (see sales.fx_rates)
 * @returns {number|null} the THB value, or null when not convertible */
export function convertToThb(amount, currency, fxRatesByCurrency) {
  if (amount == null || amount === '') return null;
  const value = Number(amount);
  if (!Number.isFinite(value) || value < 0) return null;

  const code = currency || 'THB';
  if (code === 'THB') return value;

  const rate = fxRatesByCurrency?.[code];
  if (rate == null || !(Number(rate) > 0)) return null;
  return value * Number(rate);
}

/** Thai label for a catalog `price_unit` value, so "9.00 USD" reads as "9.00 USD/ตร.ม.". Returns null for an
 * unknown/absent unit rather than inventing one — the figure then renders without a unit suffix instead of
 * claiming the wrong basis. */
function priceUnitLabel(priceUnit) {
  switch (priceUnit) {
    case 'per_sqm':      return 'ตร.ม.';
    case 'per_piece':    return 'แผ่น';
    case 'per_box':      return 'กล่อง';
    case 'per_linear_m': return 'ม.';
    default:             return null;
  }
}

/** One-call formatter for a catalog price: the original figure, its unit, and the baht companion.
 * @returns {{ original: string, thb: string|null }} `thb` is null when no rate was available. */
export function formatCatalogPrice(amount, currency, priceUnit, fxRatesByCurrency) {
  const unit = priceUnitLabel(priceUnit);
  const suffix = unit ? `/${unit}` : '';
  const original = `${formatThb(amount)} ${currency || ''}${suffix}`.trim();

  const thbValue = currency && currency !== 'THB' ? convertToThb(amount, currency, fxRatesByCurrency) : null;
  return {
    original,
    thb: thbValue == null ? null : `≈ ${formatThb(thbValue)} บาท${suffix}`,
  };
}
