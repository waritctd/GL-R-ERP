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

/** "฿265.00 / ตร.ม." — the approved price a picked line keeps, in the unit it was typed in. */
export function approvedPriceText(item, formatMoney) {
  if (item.listPriceSqm != null && item.listPriceSqm !== '') return `${formatMoney(item.listPriceSqm)} / ตร.ม.`;
  return `${formatMoney(item.netUnitPrice)} / ${item.unit || 'แผ่น'}`;
}

/**
 * Mirror of the server's rule (DealQuotationService#reusesApprovedPricesOnly), only to word the button honestly:
 * at least one line, every line picked from an approved item (its price is locked, so it cannot differ), no
 * adjustment line, and the deposit % is the first order's. The server decides.
 */
export function reusesApprovedPricesOnly({ items, adjustments, depositPercent, sourceDepositPercent, reusable, docSettings }) {
  if (!items.length || adjustments.length || sourceDepositPercent == null) return false;
  if (Number(depositPercent) !== Number(sourceDepositPercent) || depositPercent === '') return false;
  return items.every((it) => {
    const source = reusable.find((r) => r.sourceQuotationItemId === it.sourceQuotationItemId);
    return Boolean(source) && (docSettings.priceMode ?? 'NET') === (source.documentPriceMode ?? 'NET')
      && (docSettings.documentLanguage ?? 'TH') === (source.documentLanguage ?? 'TH');
  });
}
