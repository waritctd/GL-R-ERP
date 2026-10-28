// What the ใบเสนอราคา editor starts with, given a quotation DTO — in the two ways it can be given one:
//
//   editorStateFromQuotation   the document ITSELF, opened to be edited (/quotations/:id);
//   copyFromQuotation          the starting point of a brand-new one (/quotations/new?copyFrom=),
//                              "คัดลอกเป็นใบใหม่". The owner's words: "a duplicate button for the
//                              quotation (but its a new one) in case they want the exactly same
//                              details just change the customer".
//
// The second is the first plus two lists (COPIED, VALIDITY) and one row rule (SOURCE_ROW_FIELDS).
// There is deliberately no second DTO-to-row mapper: a copy that hydrated its rows any other way
// would drift from what the editor loads, and quotationCopy.test.jsx compares the two.
import { bangkokTodayIso } from '../../utils/format.js';
import { newItemClientId, rowFromServerItem } from './QuotationItemRow.jsx';
import {
  DEPOSIT_PERCENT_PRESETS, LINE_TYPE_ADJUSTMENT, lineTypeOf, locationGroupsFromItems, rowPriceMode,
} from './quotationMeta.js';

/**
 * A stored quotation as the editor's own state: `{ groups, items, adjustments, docSettings, terms }`.
 *
 * `items` is the flat, ordered list of TILE and PLAIN rows, each carrying the `groupId` of its
 * ตำแหน่งติดตั้ง; `adjustments` are the ส่วนลดพิเศษ rows, which belong to no group and always print
 * last. Every key of `terms` is set, so the editor's controlled inputs never see `undefined`.
 */
export function editorStateFromQuotation(quotation) {
  const priceMode = quotation.priceMode || 'NET';
  // F1: "loading rebuilds groups from consecutive equal labels" -- the flat items array the server
  // sends IS the document order, so the runs of equal `locationLabel` in it are exactly the
  // headings the renderer prints. Never re-sorted here.
  // V192.5: each row is read in the mode IT was priced in (the document's, or on a mixed document
  // the row's own) — that is what recovers a ราคาสุทธิ row's typed net.
  const rows = (quotation.items ?? []).map((item) => rowFromServerItem(item, rowPriceMode(priceMode, item)));
  const { groups, items } = locationGroupsFromItems(rows.filter((row) => lineTypeOf(row) !== LINE_TYPE_ADJUSTMENT));
  return {
    groups,
    items,
    adjustments: rows.filter((row) => lineTypeOf(row) === LINE_TYPE_ADJUSTMENT),
    docSettings: { priceMode, documentLanguage: quotation.documentLanguage || 'TH' },
    terms: {
      unitCode: quotation.unitCode ?? '',
      offerDate: quotation.offerDate ?? bangkokTodayIso(),
      depositPercent: quotation.depositPercent ?? '',
      depositPercentCustom: quotation.depositPercent != null && !DEPOSIT_PERCENT_PRESETS.includes(quotation.depositPercent),
      // Item 4 ("ไม่รับมัดจำ", V181) — depositPercent === 0 is the ONE signal the server stores
      // (see WastageCalculator's own comment); a null/other percentage is never "no deposit".
      noDeposit: quotation.depositPercent === 0,
      fullPaymentTerm: quotation.fullPaymentTerm ?? '',
      remainderMode: quotation.remainderMode ?? '',
      creditDays: quotation.creditDays ?? '',
      validityDays: quotation.validityDays ?? '',
      customerNotes: quotation.customerNotes ?? '',
      // V178: a stored NULL validityMode (every pre-V178 row) normalises to DAYS, same as the
      // server's own read-side default.
      validityMode: quotation.validityMode ?? 'DAYS',
      validityUntil: quotation.validityUntil ?? '',
      // V179: null (the DTO's own default) reads as "(ค่าเริ่มต้น)" — the select's own empty option.
      printedByDisplayId: quotation.printedByDisplayId ?? '',
      salesRepDisplayId: quotation.salesRepDisplayId ?? '',
      projectName: quotation.projectName ?? '',
      // V192.3 — the customer's phone and email. Null, or a DTO with no such key (a backend that
      // predates the columns), reads as blank. (No orderedByName: since 2026-10-18 the server
      // writes the signature name from the contact, and the editor neither shows nor sends it.)
      orderedByPhone: quotation.orderedByPhone ?? '',
      orderedByEmail: quotation.orderedByEmail ?? '',
      // Sales feedback #5 (V192.1) — a DTO with no such key (a row served by a backend that
      // predates the column) reads as false: print the project, exactly as it did.
      hideProjectName: quotation.hideProjectName === true,
    },
  };
}

// ── "คัดลอกเป็นใบใหม่" ────────────────────────────────────────────────────────────────────────────
//
// What a copy does with each thing the editor's `terms` holds. Every key is named, in one of the
// two lists below or in copyFromQuotation's own `terms`, so a term added to the editor later is
// not copied (or dropped) by accident — it is simply absent from a copy until someone decides, and
// quotationCopy.test.jsx names every key for the same reason.
//
// The job, its terms and how the document is signed are the source's. Who it is for is not.

/** Copied as in the source. */
const COPIED = [
  'unitCode',             // หน่วยงาน / ผู้ออกแบบ — the project's designer, not the bidder's
  'depositPercent', 'depositPercentCustom', 'noDeposit', 'fullPaymentTerm', 'remainderMode',
  'creditDays', 'customerNotes', 'printedByDisplayId', 'salesRepDisplayId', 'hideProjectName',
];

// Copied too, unless the ยืนราคา date has passed. A deadline that is still ahead is the job's, since
// a promotion ends on the same day for every bidder. One that has gone by is refused on the copy's
// first save (DealQuotationService#requireValidityUntilForMode), and by then that save has already
// created the copy's deal. So the three validity terms then start as on any new quotation, and the
// rep sets the validity again.
const VALIDITY = ['validityDays', 'validityMode', 'validityUntil'];

const pick = (from, keys) => Object.fromEntries(keys.map((key) => [key, from[key]]));

/** What makes a row the SOURCE's row. A copied row has no server id until its own first save, and
 * a picture is stored against the source's row (this editor has no way to attach one), so the
 * copy has neither — and gets a key of its own, because the source's key IS its server id. */
// stockSource: the server stamps it from the pricing-request line, so a direct copy has none.
const SOURCE_ROW_FIELDS = ['id', 'hasPicture', 'picturePlacement', 'pictureUrl', 'stockSource'];

function copiedRow(row) {
  const copy = { ...row, clientId: newItemClientId() };
  SOURCE_ROW_FIELDS.forEach((field) => { delete copy[field]; });
  return copy;
}

/**
 * The state `/quotations/new?copyFrom=<id>` opens with: the source's rows, document settings and
 * terms, with nothing that identifies the source — no id, no number, no status, no approval, no
 * customer, no contact. `suggestedProjectName` is the name to offer once the rep has chosen the
 * new customer (same job, different bidder).
 *
 * `newQuotationTerms` is what a brand-new quotation's terms start as (the editor's emptyTerms).
 * It is required: the three VALIDITY terms are read from it once the source's ยืนราคา date has
 * passed. `validityDatePassed` says that happened, the source's being a fixed date before today,
 * so the editor can say so.
 *
 * It reads its arguments and changes nothing (the only thing it makes up is a key per row). Whether
 * the source MAY be copied is the caller's question (quotationMeta#canCopyDealQuotation), and so
 * is saying which quotation this came from — the source's number is deliberately not in here.
 *
 * @returns {{ groups: object[], items: object[], adjustments: object[], docSettings: object,
 *   terms: object, suggestedProjectName: string, validityDatePassed: boolean }}
 */
export function copyFromQuotation(quotation, newQuotationTerms) {
  const source = editorStateFromQuotation(quotation);
  const { validityMode, validityUntil } = source.terms;
  // Before today is where the server draws it too: a quotation made today may be valid until
  // today. An empty string sorts before every date, so "no date typed" is ruled out first.
  const validityDatePassed = validityMode === 'DATE' && validityUntil !== '' && validityUntil < bangkokTodayIso();
  return {
    groups: source.groups,
    items: source.items.map(copiedRow),
    adjustments: source.adjustments.map(copiedRow),
    docSettings: source.docSettings,
    terms: {
      ...pick(source.terms, COPIED),
      ...pick(validityDatePassed ? newQuotationTerms : source.terms, VALIDITY),
      // As on any new quotation. The date the quantities were received is today's question again…
      offerDate: bangkokTodayIso(),
      // …and the project is chosen with the customer: it is a record UNDER a customer, so the
      // source's name is offered there (suggestedProjectName) rather than written here, where the
      // new-deal form has no field that would show it.
      projectName: '',
      // The SOURCE customer's phone and email. (Its ผู้ติดต่อ is not in terms; the editor drops it,
      // and with it the "ไม่เติม คุณ" choice made for that person.)
      orderedByPhone: '',
      orderedByEmail: '',
      omitContactHonorific: false,
    },
    suggestedProjectName: quotation.projectName ?? '',
    validityDatePassed,
  };
}
