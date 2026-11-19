import { rowFromServerItem } from './QuotationItemRow.jsx';
import { lineTypeOf } from './quotationMeta.js';

/** An add-on's picked line: the approved item as an editor row, price fields as approved, quantity left blank. */
export function rowFromApprovedItem(item, priceMode, groupId) {
  const row = rowFromServerItem({ ...item, id: null }, priceMode);
  const plain = lineTypeOf(row) === 'PLAIN';
  return {
    ...row,
    groupId,
    locationLabel: '',
    sourceQuotationItemId: item.sourceQuotationItemId,
    ...(plain ? { quantity: '' } : { quantityMode: 'AREA', areaSqm: '', piecesInput: '' }),
  };
}

/** The locked NET price a picked line keeps ("฿212.00 / ตร.ม."): per ตร.ม. when the approved quotation priced per ตร.ม. */
export function approvedPriceText(item, formatMoney) {
  const discount = Number(item.discountPct) > 0;
  const perSqm = item.listPriceSqm != null && item.listPriceSqm !== '';
  if (perSqm) {
    const net = discount ? Number(item.listPriceSqm) * (1 - Number(item.discountPct) / 100) : Number(item.listPriceSqm);
    return `${formatMoney(net)} / ตร.ม.`;
  }
  return `${formatMoney(item.netUnitPrice)} / ${item.unit || 'แผ่น'}`;
}

/** The secondary line when the approved price carries a discount: the list price and the percent. */
export function discountText(item, formatMoney) {
  if (!(Number(item.discountPct) > 0)) return null;
  const list = item.listPriceSqm != null && item.listPriceSqm !== '' ? `${formatMoney(item.listPriceSqm)} / ตร.ม.` : formatMoney(item.unitPrice);
  return `ราคาตั้ง ${list} ลด ${Number(item.discountPct)}%`;
}

const blank = (v) => (v == null || String(v).trim() === '' ? null : String(v).trim());

/**
 * Mirror of the server's rule (DealQuotationService#reusesApprovedPricesOnly), only to word the caption honestly: at
 * least one line, every line picked from an approved item (its price and identity are locked), no adjustment line, the
 * document's price mode / language equal the source's, the commercial terms (deposit %, remainder, credit days, full
 * payment term) equal the first order's, each line's lead time and stock source equal its source's, no explicit-date validity and no customer notes. The server decides.
 */
export function reusesApprovedPricesOnly({
  items, adjustments, depositPercent, sourceDepositPercent, sourceTerms, terms = {}, reusable, docSettings,
}) {
  if (!items.length || adjustments.length || sourceDepositPercent == null) return false;
  if (depositPercent === '' || Number(depositPercent) !== Number(sourceDepositPercent)) return false;
  if (sourceTerms) {
    // The server compares all three whatever the deposit is (blank = null), so the caption does too.
    if (blank(terms.remainderMode) !== blank(sourceTerms.remainderMode)
      || (terms.creditDays === '' || terms.creditDays == null ? null : Number(terms.creditDays)) !== (sourceTerms.creditDays ?? null)
      || blank(terms.fullPaymentTerm) !== blank(sourceTerms.fullPaymentTerm)) return false;
  }
  if (terms.validityMode === 'DATE' || blank(terms.customerNotes)) return false;
  return items.every((it) => {
    const source = reusable.find((r) => r.sourceQuotationItemId === it.sourceQuotationItemId);
    // The printed delivery promise is part of what is sold: a changed lead time or stock source needs approval.
    return Boolean(source) && (it.leadTimeMinDays ?? null) === (source.leadTimeMinDays ?? null)
      && (it.leadTimeMaxDays ?? null) === (source.leadTimeMaxDays ?? null) && blank(it.stockSource) === blank(source.stockSource)
      && (docSettings.priceMode ?? 'NET') === (source.documentPriceMode ?? 'NET')
      && (docSettings.documentLanguage ?? 'TH') === (source.documentLanguage ?? 'TH');
  });
}
