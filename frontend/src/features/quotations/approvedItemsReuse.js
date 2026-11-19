import { rowFromServerItem } from './QuotationItemRow.jsx';
import { lineTypeOf, newLocationGroupId } from './quotationMeta.js';

/** An add-on's picked line: the approved item as an editor row, price fields as approved, quantity left blank. The
 * source's location heading and note carry over (the rule needs them equal); `groupId` is the group carrying that label. */
export function rowFromApprovedItem(item, priceMode, groupId) {
  const row = rowFromServerItem({ ...item, id: null }, priceMode);
  const plain = lineTypeOf(row) === 'PLAIN';
  return {
    ...row,
    groupId,
    locationLabel: item.locationLabel ?? '',
    itemNotes: item.itemNotes ?? '',
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
 * payment term) equal the first order's, each line's lead time, stock source, location heading and note equal its source's, no explicit-date validity and no customer notes. The server decides.
 */
export function reusesApprovedPricesOnly({
  items, adjustments, depositPercent, sourceDepositPercent, sourceTerms, terms = {}, reusable, docSettings,
  locationOf = (it) => it.locationLabel,
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
      // Free text nobody approved must not ride a rule-approved quotation (server: DealQuotationService#sameIdentity).
      && blank(locationOf(it)) === blank(source.locationLabel) && blank(it.itemNotes) === blank(source.itemNotes)
      && (docSettings.priceMode ?? 'NET') === (source.documentPriceMode ?? 'NET')
      && (docSettings.documentLanguage ?? 'TH') === (source.documentLanguage ?? 'TH');
  });
}

/**
 * The group a picked line goes into: the existing group with the source's label, else the last group when it is blank
 * and empty (it gets named), else a new group. Blank label: the last group, as before. Returns `{ groups, groupId }`.
 */
export function groupForPickedLabel(groups, items, label) {
  const wanted = blank(label);
  const last = groups[groups.length - 1];
  if (wanted == null) return { groups, groupId: last?.groupId ?? null };
  const same = groups.find((g) => blank(g.label) === wanted);
  if (same) return { groups, groupId: same.groupId };
  if (last && blank(last.label) == null && !items.some((it) => it.groupId === last.groupId)) {
    return { groups: groups.map((g) => (g === last ? { ...g, label: wanted } : g)), groupId: last.groupId };
  }
  const groupId = newLocationGroupId();
  return { groups: [...groups, { groupId, label: wanted }], groupId };
}

/** True when a picked line's location heading or note differs from its source's: the one reason the server's rule
 * (DealQuotationService#sameIdentity) refuses that this caption can name. */
export function locationOrNoteDiffers({ items, reusable, locationOf = (it) => it.locationLabel }) {
  return items.some((it) => {
    const source = reusable.find((r) => r.sourceQuotationItemId === it.sourceQuotationItemId);
    return Boolean(source) && (blank(locationOf(it)) !== blank(source.locationLabel) || blank(it.itemNotes) !== blank(source.itemNotes));
  });
}
