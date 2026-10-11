// What the ใบเสนอราคา editor starts with, given a quotation DTO — in the two ways it can be given one:
//
//   editorStateFromQuotation   the document ITSELF, opened to be edited (/quotations/:id);
//   copyFromQuotation          the starting point of a brand-new one (/quotations/new?copyFrom=),
//                              "คัดลอกเป็นใบใหม่". The owner's words: "a duplicate button for the
//                              quotation (but its a new one) in case they want the exactly same
//                              details just change the customer".
//
// The second is the first plus ONE table (TERMS_IN_A_COPY) and one row rule (SOURCE_ROW_FIELDS).
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
      // quotation.deptCode is deliberately NOT read into the form: ฝ่าย has no field any more, and
      // a value an older draft still carries is cleared by the next save.
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
      // Owner-directed reversal of F2 (2026-09-26) — manual, optional ผู้สั่งซื้อ signature name;
      // null (every pre-V192 row, and any document nobody has typed one into) reads as blank, same
      // "no value stored" convention as every other field here.
      orderedByName: quotation.orderedByName ?? '',
      // V192.3 — null, or a DTO with no such key (a backend that predates the columns), reads as
      // blank.
      orderedByPhone: quotation.orderedByPhone ?? '',
      orderedByEmail: quotation.orderedByEmail ?? '',
      // Sales feedback #5 (V192.1) — a DTO with no such key (a row served by a backend that
      // predates the column) reads as false: print the project, exactly as it did.
      hideProjectName: quotation.hideProjectName === true,
    },
  };
}

// ── "คัดลอกเป็นใบใหม่" ────────────────────────────────────────────────────────────────────────────

const asInTheSource = (value) => value;
const leftForTheRep = () => '';

/**
 * THE TABLE: what a copy does with each thing the editor's `terms` holds. Every key is here, so a
 * term added to the editor later is not copied (or dropped) by accident — it is simply absent from
 * a copy until someone decides, and quotationCopy.test.jsx names every key for the same reason.
 *
 * The job, its terms and how the document is signed are the source's. Who it is for is not.
 */
const TERMS_IN_A_COPY = {
  unitCode: asInTheSource,            // หน่วยงาน / ผู้ออกแบบ — the project's designer, not the bidder's
  depositPercent: asInTheSource,
  depositPercentCustom: asInTheSource,
  noDeposit: asInTheSource,
  fullPaymentTerm: asInTheSource,
  remainderMode: asInTheSource,
  creditDays: asInTheSource,
  validityDays: asInTheSource,
  validityMode: asInTheSource,
  validityUntil: asInTheSource,       // a promotion's deadline is the same for every bidder
  customerNotes: asInTheSource,
  printedByDisplayId: asInTheSource,
  salesRepDisplayId: asInTheSource,
  hideProjectName: asInTheSource,
  // As on any new quotation. The date the quantities were received is today's question again…
  offerDate: () => bangkokTodayIso(),
  // …and the project is chosen with the customer: it is a record UNDER a customer, so the source's
  // name is offered there (suggestedProjectName) rather than written here, where the new-deal form
  // has no field that would show it.
  projectName: leftForTheRep,
  // ผู้ติดต่อ is a person at the SOURCE's customer.
  orderedByName: leftForTheRep,
  orderedByPhone: leftForTheRep,
  orderedByEmail: leftForTheRep,
};

/** What makes a row the SOURCE's row. A copied row has no server id until its own first save, and
 * a picture is stored against the source's row (this editor has no way to attach one), so the
 * copy has neither — and gets a key of its own, because the source's key IS its server id. */
const SOURCE_ROW_FIELDS = ['id', 'hasPicture', 'picturePlacement', 'pictureUrl'];

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
 * It reads its argument and changes nothing (the only thing it makes up is a key per row). Whether
 * the source MAY be copied is the caller's question (quotationMeta#canCopyDealQuotation), and so
 * is saying which quotation this came from — the source's number is deliberately not in here.
 *
 * @returns {{ groups: object[], items: object[], adjustments: object[], docSettings: object,
 *   terms: object, suggestedProjectName: string }}
 */
export function copyFromQuotation(quotation) {
  const source = editorStateFromQuotation(quotation);
  return {
    groups: source.groups,
    items: source.items.map(copiedRow),
    adjustments: source.adjustments.map(copiedRow),
    docSettings: source.docSettings,
    terms: Object.fromEntries(
      Object.entries(TERMS_IN_A_COPY).map(([field, inACopy]) => [field, inACopy(source.terms[field])]),
    ),
    suggestedProjectName: quotation.projectName ?? '',
  };
}
