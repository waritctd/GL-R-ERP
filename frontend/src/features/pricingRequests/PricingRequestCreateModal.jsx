import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { RECIPIENT_OPTIONS, UNIT_BASIS_OPTIONS } from './pricingRequestMeta.js';
import { FormField } from '../../components/common/FormField.jsx';
import { QuotationItemRow, newItemClientId } from '../quotations/QuotationItemRow.jsx';
import { QuotationContactPicker } from '../quotations/QuotationContactPicker.jsx';
import { focusQuotationField } from '../quotations/QuotationChecklist.jsx';
import { ceilPieces, defaultLeadTimeForOrigin, originCountryFromCode, piecesPerSqmFromSqmPerPiece, recipientForEntryChannel, validateQuotationItem } from '../quotations/quotationMeta.js';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';

// The currency and price unit Sales fixes on each line; import cannot change them afterwards. The
// currency list is the trade currencies this business quotes in (same as the FX table); the unit
// list is UnitBasis's four codes.
const REQUEST_CURRENCY_OPTIONS = ['THB', 'EUR', 'USD', 'CNY'];

// price_catalog.price_unit -> UnitBasis. 'unknown' (or anything else) is NOT guessed: the unit stays
// blank and required, so a wrong basis can never be pre-filled silently.
const CATALOG_PRICE_UNIT_TO_BASIS = {
  per_sqm: 'PER_SQM',
  per_piece: 'PER_PIECE',
  per_box: 'PER_BOX',
  per_linear_m: 'PER_LINEAR_M',
};

/** The fields a catalogue pick auto-fills (sales may still change them before submit). */
function termsFromCatalog(cat) {
  return {
    requestedCurrency: typeof cat?.currency === 'string' ? cat.currency.trim().toUpperCase() : '',
    requestedPriceUnitBasis: CATALOG_PRICE_UNIT_TO_BASIS[cat?.priceUnit] ?? '',
  };
}

/**
 * What a catalogue pick sets on THIS form, over QuotationItemRow.pickCatalog's own patch. That one
 * keeps the row's origin (and so its lead time) and falls back to the row's thickness, which suits
 * a direct-deal rep's deliberate choice but not here: a PCR row's origin/thickness/box count were
 * seeded from the deal's previous product, and a pick means "the database has the information", so
 * the picked product's values replace them, blanks included.
 */
function catalogPickPatch(cat) {
  const originCountry = originCountryFromCode(cat.originCountryCode) || '';
  return {
    ...termsFromCatalog(cat),
    originCountry,
    originCountryOther: null,
    ...defaultLeadTimeForOrigin(originCountry),
    thicknessMm: cat.thicknessMm ?? null,
    piecesPerBox: cat.pcsPerBox ?? '',
  };
}

/**
 * On a hand-typed line the currency and unit default automatically if the database has the
 * information, otherwise sales fills them by hand. The database has it when the typed โรงงาน names
 * a catalogue factory: `items` are the rows `GET /api/catalog/prices?q=<brand>` returned (the
 * search also matches product fields, so only rows whose factory IS the typed name count). Returns
 * null when none match; a field the matching rows disagree on comes back '' so a wrong value is
 * never pre-filled silently.
 */
function termsFromFactoryMatches(brand, items = []) {
  const wanted = String(brand ?? '').trim().toLowerCase();
  if (!wanted) return null;
  const mine = items.filter((it) => String(it?.factoryName ?? '').trim().toLowerCase() === wanted);
  if (mine.length === 0) return null;
  const terms = mine.map(termsFromCatalog);
  const agree = (key) => (terms.every((t) => t[key] === terms[0][key]) ? terms[0][key] : '');
  return { requestedCurrency: agree('requestedCurrency'), requestedPriceUnitBasis: agree('requestedPriceUnitBasis') };
}
const BRAND_LOOKUP_DEBOUNCE_MS = 300;
const TERM_FIELDS = ['requestedCurrency', 'requestedPriceUnitBasis'];

/**
 * `autoTerms` (UI-only, never sent: pricingRequestItemInputFromRow names its fields) marks the terms the
 * โรงงาน lookup filled. A term the rep or a catalogue pick sets stops being the lookup's; a โรงงาน edit
 * blanks only the lookup's own, so the corrected name re-derives them and a rep's choice survives.
 */
function patchRow(item, patch) {
  const next = { ...item, ...patch, autoTerms: { ...item.autoTerms } };
  TERM_FIELDS.forEach((key) => {
    if (key in patch) delete next.autoTerms[key];
    else if ('brand' in patch && next.autoTerms[key]) { next[key] = ''; delete next.autoTerms[key]; }
  });
  return next;
}

// ลักษณะจำนวน / วันที่ต้องการส่งมอบ / สถานที่ส่งมอบ / ข้อกำหนดพิเศษ are not editable on this form:
// Sales does not have that information at คำขอราคา time. The BACKEND CONTRACT still needs them
// (PricingRequestItemRequest.quantityType is @NotBlank, the other three are nullable columns), so
// every payload keeps carrying all four:
//   - create mode sends this default plus three nulls;
//   - edit/revision mode PRESERVES what the persisted request holds (see itemFromExisting),
//     because buildPayload writes the FULL item and nulling a field would erase existing data.
const DEFAULT_QUANTITY_TYPE = 'ESTIMATE';

/**
 * The item form Sales fills is the SAME as the direct-deal quotation's TILE row: it reuses
 * `QuotationItemRow` (see `../quotations/QuotationItemRow.jsx`) with `hidePricing`, minus
 * price/discount (the CEO enters those later). Sales does not type a quantity/unit directly: the
 * server derives requestedQty/requestedUnit/requestedUnitBasis from the tile fields via the SAME
 * `WastageCalculator` the direct-deal quotation uses (`PricingRequestService#resolveItems`, see
 * its Javadoc).
 *
 * QuotationItemRow's รุ่น/ค้นหาแคตตาล็อก typeahead (live search against `GET /catalog/prices`)
 * is the only catalogue picker. A pick auto-fills brand from the catalogue's factory name, as on
 * the direct-deal form (see its `pickCatalog`).
 *
 * โรงงาน labelling: there is no separate free-text `factory` input; Sales does not type a
 * factory, and the `factory` column and Import's `SetItemFactoryRequest` gap-fill are untouched.
 * Instead the field the direct-deal form calls ยี่ห้อ (the `brand` column, catalogue-auto-filled)
 * is labelled โรงงาน here via `QuotationItemRow`'s `brandLabel` prop. Label only: the value is
 * still stored in `brand`, never in `factory`.
 *
 * รายละเอียดสินค้า: the direct-deal form has no such field. The หมายเหตุรายการ input
 * (QuotationItemRow's itemNotes) maps onto the existing `productDescription` column, so Import
 * still sees whatever notes Sales left.
 */
function emptyItemFromTicketItem(ticketItem) {
  // Mirrors TicketDetailPage's own qtyDisplay logic: an SQM-basis line quotes its sqm quantity in
  // AREA mode; a PIECE-basis line quotes its piece count in PIECES mode.
  const quantityMode = ticketItem?.unitBasis === 'SQM' ? 'AREA' : 'PIECES';
  // A catalogue-linked deal line brings the catalogue's thickness / pieces-per-box / factory
  // country along (TicketItemDto.catalog* via TicketRepository#findItemsByTicketId), and the origin
  // implies the same default lead time a catalogue pick gets (QuotationItemRow.pickCatalog). A
  // hand-typed deal line has none of it.
  const fromCatalog = Boolean(ticketItem?.catalogPriceId);
  const seededOrigin = fromCatalog ? (originCountryFromCode(ticketItem.catalogOriginCountryCode) || '') : '';
  const seededLeadTime = defaultLeadTimeForOrigin(seededOrigin);
  return {
    clientId: newItemClientId(),
    // V110: seed the catalog link the deal-creation catalog picker already resolved (see
    // TicketCreateModal.jsx's applyCatalogItem), so this row doesn't need a re-search. Null for a
    // hand-typed ("custom") deal line, or a line created before V110 existed.
    sourceTicketItemId: ticketItem?.id ?? null,
    catalogPriceId: ticketItem?.catalogPriceId ?? null,
    // Carried through unchanged: this row has no UI for either field, but a ticket_item that
    // already carries a variant pick or a factory (e.g. Import resolved one before Sales revises
    // the deal) must not be nulled when the row round-trips through
    // pricingRequestItemInputFromRow below.
    variantId: ticketItem?.variantId ?? null,
    factory: ticketItem?.factory ?? null,
    productCode: ticketItem?.catalogProductCode ?? '',
    brand: ticketItem?.brand ?? '',
    model: ticketItem?.model ?? '',
    color: ticketItem?.color ?? '',
    texture: ticketItem?.texture ?? '',
    sizeText: ticketItem?.size ?? '',
    thicknessMm: fromCatalog ? (ticketItem.catalogThicknessMm ?? null) : null,
    sqmPerPiece: ticketItem?.sqmPerPiece ?? null,
    piecesPerSqmDisplay: null,
    sqmPerPieceSource: ticketItem?.sqmPerPiece != null ? 'catalog' : null,
    catalogSqmPerPiece: null,
    catalogPriceUnit: ticketItem?.catalogPriceUnit ?? null,
    catalogSizeText: null,
    // Auto-filled only from a CATALOGUE line; a hand-typed line starts blank and required.
    requestedCurrency: ticketItem?.catalogPriceId ? termsFromCatalog({ currency: ticketItem.catalogCurrency, priceUnit: ticketItem.catalogPriceUnit }).requestedCurrency : '',
    requestedPriceUnitBasis: ticketItem?.catalogPriceId ? termsFromCatalog({ currency: ticketItem.catalogCurrency, priceUnit: ticketItem.catalogPriceUnit }).requestedPriceUnitBasis : '',
    sqmPerBox: null,
    quantityMode,
    areaSqm: quantityMode === 'AREA' ? (ticketItem?.qtySqm ?? '') : '',
    piecesInput: quantityMode === 'PIECES' ? (ticketItem?.qty ?? '') : '',
    wastageMode: 'PERCENT',
    wastageValue: 0,
    piecesPerBox: fromCatalog ? (ticketItem.catalogPcsPerBox ?? '') : '',
    // Same default as the direct-deal form: every new row rounds up to a full box unless the rep
    // opts out.
    roundToFullBox: true,
    originCountry: seededOrigin,
    // Sales flags stock per line, seeded from the deal line. IN_THAILAND is the only value this form sets.
    stockSource: ticketItem?.sourcedFromStock ? 'IN_THAILAND' : null,
    // Typed name when originCountry = "อื่นๆ" (see QuotationItemRow).
    originCountryOther: null,
    leadTimeMinDays: seededLeadTime.leadTimeMinDays,
    leadTimeMaxDays: seededLeadTime.leadTimeMaxDays,
    // Maps to the wire `productDescription` — see this file's own module Javadoc above.
    itemNotes: '',
    // No longer user-editable — see DEFAULT_QUANTITY_TYPE above. Carried so buildPayload keeps
    // satisfying PricingRequestItemRequest unchanged.
    quantityType: DEFAULT_QUANTITY_TYPE,
    targetDeliveryDate: '',
    deliveryLocation: '',
    specialRequirement: '',
  };
}

// A PCR item created with the legacy form has no quantityMode/areaSqm/piecesInput, only the
// requestedQty/requestedQtySqm/requestedUnitBasis it was persisted with. Without this helper,
// itemFromExisting's plain `quantityMode ?? 'AREA'` / `areaSqm ?? ''` defaults would open EVERY
// legacy line in AREA mode with an EMPTY จำนวน field, silently hiding the quantity requested.
//
// Recovers what it can:
//   - PER_SQM   -> AREA mode, seeded from requestedQtySqm (falling back to requestedQty, the SAME
//                  number under the old basis when no separate sqm figure was stored).
//   - PER_PIECE -> PIECES mode, seeded from requestedQty (1:1).
//   - PER_BOX / PER_LINEAR_M -> neither AREA nor PIECES is a lossless translation, so the field is
//     left for the rep to re-enter (every required field must be filled before saving), but the
//     ORIGINAL quantity + unit is shown read-only via `legacyQuantityNote` (QuotationItemRow's
//     `hint` on the จำนวน field).
//   - A row that already has a `quantityMode` is untouched; this only fires for the legacy shape.
function legacySeededQuantity(item) {
  if (item?.quantityMode) {
    return {
      quantityMode: item.quantityMode,
      areaSqm: item.areaSqm ?? '',
      piecesInput: item.piecesInput ?? '',
      legacyQuantityNote: null,
    };
  }
  const basis = item?.requestedUnitBasis;
  if (basis === 'PER_SQM' && (item.requestedQtySqm != null || item.requestedQty != null)) {
    const area = item.requestedQtySqm ?? item.requestedQty;
    return { quantityMode: 'AREA', areaSqm: area, piecesInput: '', legacyQuantityNote: null };
  }
  if (basis === 'PER_PIECE' && item.requestedQty != null) {
    return { quantityMode: 'PIECES', areaSqm: '', piecesInput: item.requestedQty, legacyQuantityNote: null };
  }
  if ((basis === 'PER_BOX' || basis === 'PER_LINEAR_M') && item?.requestedQty != null) {
    const unitLabel = basis === 'PER_BOX' ? 'กล่อง' : 'เมตร';
    return {
      quantityMode: 'AREA',
      areaSqm: '',
      piecesInput: '',
      legacyQuantityNote:
        `รายการเดิมระบุจำนวน ${item.requestedQty} ${item.requestedUnit || unitLabel} — กรุณาระบุจำนวนใหม่ (พื้นที่หรือจำนวนแผ่น)`,
    };
  }
  // No recognizable legacy basis/quantity at all: same blank-AREA default itemFromExisting uses.
  return { quantityMode: 'AREA', areaSqm: '', piecesInput: '', legacyQuantityNote: null };
}

// Edit mode seeds its rows from a persisted PricingRequestItemDto instead of a ticket_item.
// Existing catalog identity is preserved so editing a draft does not erase its product snapshot.
function itemFromExisting(item) {
  const quantitySeed = legacySeededQuantity(item);
  // A legacy PER_PIECE row has no sqmPerPiece either, but it can be reconstructed:
  // requestedQtySqm was requestedQty × sqmPerPiece when derived, so dividing back out recovers it
  // (mirrors WastageCalculator#sqmQuantityFromPieces in reverse).
  //
  // Must be PER_PIECE-ONLY. A PER_SQM row's requestedQtySqm IS the requested area (often equal to
  // requestedQty, see legacySeededQuantity), so dividing yields a meaningless ~1.0 "ตร.ม./แผ่น".
  // The server would then derive piecesFinal ≈ areaSqm (144 ตร.ม. -> "144 pieces" instead of ~400
  // for a ~0.36 ตร.ม./แผ่น tile) and order the wrong quantity. PER_SQM / PER_BOX / PER_LINEAR_M
  // legacy rows get NO derived sqmPerPiece; the rep fills ตร.ม./แผ่น by hand.
  const legacySqmPerPiece = item?.sqmPerPiece == null && item?.requestedUnitBasis === 'PER_PIECE'
    && item?.requestedQtySqm != null && Number(item.requestedQtySqm) > 0
    && item?.requestedQty != null && Number(item.requestedQty) > 0
    ? Number(item.requestedQtySqm) / Number(item.requestedQty)
    : null;
  return {
    clientId: item?.id ?? newItemClientId(),
    id: item?.id ?? null,
    sourceTicketItemId: item?.sourceTicketItemId ?? null,
    catalogPriceId: item?.productId ?? null,
    // As in emptyItemFromTicketItem: no editor UI writes either field, but the persisted request
    // may already carry one (Import's SetItemFactoryRequest sets `factory` directly in the DB), so
    // preserve it through the round-trip instead of letting pricingRequestItemInputFromRow send
    // an implicit null on every save.
    variantId: item?.variantId ?? null,
    factory: item?.factory ?? null,
    productCode: item?.productCode ?? '',
    brand: item?.brand ?? '',
    model: item?.model ?? '',
    color: item?.color ?? '',
    texture: item?.texture ?? '',
    sizeText: item?.size ?? '',
    thicknessMm: item?.thicknessMm ?? null,
    sqmPerPiece: item?.sqmPerPiece ?? legacySqmPerPiece,
    piecesPerSqmDisplay: null,
    // A reloaded draft's field has no recorded provenance (UI-only state, never persisted):
    // 'manual' means "not freshly resolved from a catalog pick this session". A value recovered
    // from the legacy requestedQtySqm/requestedQty pair (legacySqmPerPiece) is equally 'manual'.
    sqmPerPieceSource: (item?.sqmPerPiece ?? legacySqmPerPiece) != null ? 'manual' : null,
    catalogSqmPerPiece: null,
    catalogPriceUnit: null,
    catalogSizeText: null,
    // What Sales fixed on the persisted line; blank for a legacy line (must be completed).
    requestedCurrency: item?.requestedCurrency ?? '',
    requestedPriceUnitBasis: item?.requestedPriceUnitBasis ?? '',
    sqmPerBox: item?.sqmPerBox ?? null,
    quantityMode: quantitySeed.quantityMode,
    areaSqm: quantitySeed.areaSqm,
    piecesInput: quantitySeed.piecesInput,
    // Read-only text for a legacy PER_BOX/PER_LINEAR_M line's original quantity (see
    // legacySeededQuantity). Client-only; never sent (pricingRequestItemInputFromRow ignores it).
    legacyQuantityNote: quantitySeed.legacyQuantityNote,
    wastageMode: item?.wastageMode ?? 'NONE',
    wastageValue: item?.wastageValue ?? 0,
    piecesPerBox: item?.piecesPerBox ?? '',
    roundToFullBox: item?.roundToFullBox !== false,
    originCountry: item?.originCountry ?? '',
    stockSource: item?.stockSource ?? null,
    // remembered so un-ticking then re-ticking จากสต็อก restores the loaded value (IN_TRANSIT) instead of IN_THAILAND
    loadedStockSource: item?.stockSource ?? null,
    originCountryOther: item?.originCountryOther ?? null,
    leadTimeMinDays: item?.leadTimeMinDays ?? null,
    leadTimeMaxDays: item?.leadTimeMaxDays ?? null,
    itemNotes: item?.productDescription ?? '',
    quantityType: item?.quantityType ?? DEFAULT_QUANTITY_TYPE,
    targetDeliveryDate: item?.targetDeliveryDate ?? '',
    deliveryLocation: item?.deliveryLocation ?? '',
    specialRequirement: item?.specialRequirement ?? '',
  };
}

// Create-mode auto-fill for the ผู้รับ label: the per-type party name (TicketSummaryDto's
// designerName/ownerName/buyerName), else the customer. Edit/revision never call this — they seed
// recipientLabel from the persisted request (the seedsFromExisting guard at both call sites).
function recipientLabelForDeal(deal, recipientType) {
  if (!deal) return '';
  const byType = {
    DESIGNER: deal.designerName,
    OWNER: deal.ownerName,
    BUYER: deal.buyerName,
  }[recipientType];
  return byType || deal.customerName || '';
}

function generateClientRequestId() {
  return globalThis.crypto?.randomUUID?.()
    ?? '00000000-0000-4000-8000-' + String(Date.now()).slice(-12).padStart(12, '0');
}

/** The wire `PricingRequestItemRequest` for one editor row: the inverse of
 * emptyItemFromTicketItem/itemFromExisting. requestedQty/requestedQtySqm/requestedUnit/
 * requestedUnitBasis are never sent: the server derives all four from the fields below
 * (PricingRequestService#resolveItems). `factory`/`variantId` ARE sent, round-tripped from
 * `item.factory`/`item.variantId`, because PricingRequestRepository.replaceItems deletes and
 * re-inserts every item on every create/update/createCustomerChangeRevision: omitting either
 * would NULL a factory Import already assigned via SetItemFactoryRequest on every sales save, and
 * would disable FactoryQuoteCarryForward.sameProductAndQuantity's factory match on the next
 * revision. */
function pricingRequestItemInputFromRow(item) {
  return {
    // No `id` field: unlike the direct-deal quotation's ItemInput, PricingRequestItemRequest has
    // no stable-item-id concept — PricingRequestRepository.replaceItems always deletes and
    // re-inserts every item on create/update/createCustomerChangeRevision, generating fresh ids
    // every time. Sending one here would be inert on the real backend (an unknown JSON property,
    // ignored) but is actively wrong against the mock's createCustomerChangeRevision handler,
    // which spreads `...item` AFTER a freshly generated `id:` — a stray `id: null` in the payload
    // would silently overwrite that generated id.
    sourceTicketItemId: item.sourceTicketItemId ?? null,
    productId: item.catalogPriceId ?? null,
    variantId: item.variantId ?? null,
    factory: item.factory ?? null,
    brand: item.brand?.trim() || null,
    model: item.model?.trim() || null,
    // See this file's own module Javadoc: the หมายเหตุรายการ input is productDescription on the
    // wire, not a dedicated "รายละเอียดสินค้า" box.
    productDescription: item.itemNotes?.trim() || null,
    color: item.color?.trim() || null,
    texture: item.texture?.trim() || null,
    size: item.sizeText?.trim() || null,
    productCode: item.productCode?.trim() || null,
    thicknessMm: item.thicknessMm ?? null,
    sqmPerPiece: item.sqmPerPiece ?? null,
    quantityMode: item.quantityMode ?? 'AREA',
    areaSqm: item.areaSqm === '' ? null : item.areaSqm,
    piecesInput: item.piecesInput === '' ? null : item.piecesInput,
    wastageMode: item.wastageMode ?? 'NONE',
    wastageValue: item.wastageValue ?? 0,
    piecesPerBox: item.piecesPerBox === '' ? null : item.piecesPerBox,
    sqmPerBox: item.sqmPerBox === '' || item.sqmPerBox == null ? null : Number(item.sqmPerBox),
    roundToFullBox: item.roundToFullBox !== false,
    originCountry: item.originCountry || null,
    stockSource: item.stockSource ?? null,
    // Sent as-typed; the server (PricingRequestService#resolveItem) clears it when originCountry
    // is not "อื่นๆ". This builder does no normalization, like every other field.
    originCountryOther: item.originCountryOther?.trim() || null,
    leadTimeMinDays: item.leadTimeMinDays ?? null,
    leadTimeMaxDays: item.leadTimeMaxDays ?? null,
    // The locked terms import will see on the factory card.
    requestedCurrency: item.requestedCurrency || null,
    requestedPriceUnitBasis: item.requestedPriceUnitBasis || null,
    quantityType: item.quantityType ?? DEFAULT_QUANTITY_TYPE,
    targetDeliveryDate: item.targetDeliveryDate || null,
    deliveryLocation: item.deliveryLocation?.trim() || null,
    specialRequirement: item.specialRequirement?.trim() || null,
  };
}

/**
 * Create/submit modal for a new PricingRequest. Also the edit modal for an existing DRAFT: pass
 * `mode="edit"` + `initialValue` (that request's summary + items, as returned by
 * `api.pricingRequests.get`) and `updateFn` instead of `createFn`/`submitFn`. Create and edit
 * differ only in how the form seeds its state and what the footer buttons do; every field,
 * validation and row layout is shared.
 *
 * `mode="revision"` reuses the same seeding/catalog-picker/attachment machinery so Sales can EDIT
 * a customer-change revision's items (product, size, quantity, recipient, required date, adding/
 * removing lines). Pass `initialValue` (the CURRENT request, same shape as edit) +
 * `createRevisionFn`. The parent request is never updated by this mode: the backend's
 * `createCustomerChangeRevision` supersedes it as a side effect of creating the new DRAFT, so the
 * parent's submitted record stays immutable.
 *
 * Item rows seed from the deal's ticket_item rows in create mode (carrying sourceTicketItemId so
 * Import can trace a line back to the product line) or from the request's persisted items in
 * edit/revision mode; every field stays editable either way, since PricingRequestItemRequest
 * carries its own descriptive copy, not a read-only mirror of ticket_item.
 *
 * recipientContactId is bound to a customer-contact picker (the SAME `QuotationContactPicker` as
 * the direct-deal quotation editor; see the `contact` state's comment for why it never feeds
 * recipientLabel's auto-fill). recipientLabel (free text) stays required regardless:
 * PricingRequestService.validateRecipientIdentifiable accepts either, but this modal's validate()
 * requires recipientLabel unconditionally (a contact pick is additive).
 */
export function PricingRequestCreateModal({
  ticketItems = [], deal = null, onClose, onCreated, createFn, submitFn,
  mode = 'create', initialValue = null, updateFn, createRevisionFn,
  // Passed to QuotationContactPicker's error toasts (failed contact create/edit-in-place).
  // Optional: the picker tolerates an absent one.
  showToast,
  // Create mode only: ผู้รับ pre-selected from the deal page's เสนอราคา menu (else the deal's entry channel's party).
  initialRecipientType = null,
}) {
  const isEdit = mode === 'edit';
  const isRevision = mode === 'revision';
  // Both edit and revision mode seed every field from a PERSISTED request (edit's own DRAFT,
  // or revision's current/parent request); only plain create mode seeds from the deal's raw
  // ticket_item rows instead.
  const seedsFromExisting = isEdit || isRevision;
  const initialSummary = initialValue?.summary ?? null;
  // The backend (requireUnchangedRecipientType) 409s any recipientType change on a revision child,
  // in createCustomerChangeRevision and updateDraft alike.
  const recipientLocked = isRevision || (isEdit && initialSummary?.parentPricingRequestId != null);
  const seededRecipientType = seedsFromExisting
    ? (initialSummary?.recipientType ?? 'DESIGNER')
    : (initialRecipientType ?? initialSummary?.recipientType ?? (recipientForEntryChannel(deal?.entryChannel) || 'DESIGNER'));
  const [recipientType, setRecipientType] = useState(seededRecipientType);
  const [recipientLabel, setRecipientLabel] = useState(() => (
    seedsFromExisting
      ? (initialSummary?.recipientLabel ?? '')
      : recipientLabelForDeal(deal, seededRecipientType)
  ));
  // Tracks whether the user has hand-edited ผู้รับ's free-text label — once true, switching the
  // ผู้รับ chip must never overwrite what they typed (see the effect below). Stays false forever
  // in edit/revision mode since nothing ever writes recipientLabel from `deal` there.
  const [recipientLabelTouched, setRecipientLabelTouched] = useState(false);
  // ผู้ติดต่อ: the SAME `QuotationContactPicker` as the direct-deal quotation editor, bound to the
  // existing `recipientContactId` wire field (V59). Deliberately SEPARATE from ผู้รับคำขอราคา
  // (recipientType/recipientLabel above: "who is this PRICING REQUEST for", a PCR-only category):
  // ผู้ติดต่อ is "which registered customer CONTACT placed the order", as on the quotation, so it
  // is never cross-wired into recipientLabel's auto-fill from `deal`/recipientType.
  //
  // Seeded only from a persisted request's `recipientContactId` (edit/revision), as a stand-in
  // `{ id, firstName }` that the picker's `onResolve` completes once its contact list loads.
  const [contact, setContact] = useState(() => (
    initialSummary?.recipientContactId != null
      ? { id: initialSummary.recipientContactId, firstName: initialSummary.recipientLabel ?? '', lastName: '' }
      : null
  ));
  // customerId/customerName: `deal` (create mode) or the persisted request's own summary
  // (edit/revision mode), whichever applies. Without a customerId the picker shows "เลือกลูกค้าก่อน"
  // and stays disabled, like the direct-deal editor's picker.
  const pickerCustomerId = deal?.customerId ?? initialSummary?.customerId ?? null;
  const pickerCustomerName = deal?.customerName ?? initialSummary?.customerName ?? '';
  const [requiredDate, setRequiredDate] = useState(() => initialSummary?.requiredDate ?? '');
  const [customerTargetPrice, setCustomerTargetPrice] = useState(() => (
    initialSummary?.customerTargetPrice != null ? String(initialSummary.customerTargetPrice) : ''
  ));
  const [targetCurrency, setTargetCurrency] = useState(() => initialSummary?.targetCurrency ?? 'THB');
  const [note, setNote] = useState(() => (
    seedsFromExisting ? (initialSummary?.note ?? '') : (deal?.note ?? '')
  ));
  // Header terms mirroring the direct-deal quotation's own (V165/V179/V180), not yet carried onto
  // the quotation itself. All optional.
  //
  // No input edits these: Sales does not decide payment terms / validity / display-name / dept-unit
  // at คำขอราคา time. The BACKEND CONTRACT still takes them (buildPayload sends every field), so
  // they are read-only, preserved values:
  //   - create mode has no persisted request, so each stays blank and sends null;
  //   - edit/revision mode PRESERVES what the persisted request holds, so a saved term is never
  //     nulled. That is why they are seeded from initialSummary rather than dropped.
  // Plain constants (not useState) because nothing sets them.
  const paymentTermMode = initialSummary?.paymentTermMode ?? '';
  const creditDays = initialSummary?.creditDays != null ? String(initialSummary.creditDays) : '';
  const validityDays = initialSummary?.validityDays != null ? String(initialSummary.validityDays) : '';
  const printedByDisplayId = initialSummary?.printedByDisplayId != null ? String(initialSummary.printedByDisplayId) : '';
  const salesRepDisplayId = initialSummary?.salesRepDisplayId != null ? String(initialSummary.salesRepDisplayId) : '';
  // unitCode doubles as the ผู้ออกแบบ picker's target, exactly as on the direct-deal form (V173) —
  // a designer pick is a frontend convenience that writes this SAME free-text field, never a
  // separate column; the designer's own name never reaches it, only the code does.
  const unitCode = initialSummary?.unitCode ?? '';
  const [omitContactHonorific, setOmitContactHonorific] = useState(() => initialSummary?.omitContactHonorific ?? false);
  const [revisionReason, setRevisionReason] = useState('');
  const [clientRequestId] = useState(() => generateClientRequestId());
  const [items, setItems] = useState(() => {
    if (seedsFromExisting) {
      return initialValue?.items?.length ? initialValue.items.map(itemFromExisting) : [emptyItemFromTicketItem(null)];
    }
    return ticketItems.length
      ? ticketItems.map((ticketItem) => emptyItemFromTicketItem(ticketItem))
      : [emptyItemFromTicketItem(null)];
  });

  // Re-fills recipientLabel from the deal whenever the ผู้รับ chip changes — create mode only,
  // and only until the user types over it. Also runs once on mount, harmlessly recomputing the
  // same value the useState initializer above already seeded. deal/seedsFromExisting/
  // recipientLabelTouched are read here, not depended on: this must re-run ONLY when the user
  // switches the ผู้รับ chip, never merely because `deal` re-rendered with the same identity.
  useEffect(() => {
    if (seedsFromExisting || recipientLabelTouched) return;
    setRecipientLabel(recipientLabelForDeal(deal, recipientType));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recipientType]);

  const [error, setError] = useState('');
  // Informational (non-error) banner — currently only used by the create-mode
  // duplicate-draft guard below, to tell the user a retry is reusing the
  // draft it already created rather than making a second one.
  const [info, setInfo] = useState('');
  // Per-row field errors (required แผ่น/ตร.ม./ความหนา/etc — quotationMeta's own
  // validateQuotationItem, `skipPricing: true` since this form has no price inputs), shown once
  // the user has attempted to save/submit. Keyed by row index, same convention
  // QuotationEditorPage uses for the direct-deal form's own itemErrorsByRow.
  const [itemFieldErrors, setItemFieldErrors] = useState({});
  const [saving, setSaving] = useState(false);
  // Once createFn has succeeded, its id is held here so a retry (create succeeded, then submitFn
  // failed, or the user switches from "submit" to "save draft") reuses the same draft instead of
  // calling createFn again and orphaning a duplicate. Always null in edit mode.
  const [createdId, setCreatedId] = useState(null);

  // Pricing Request attachments (V69, review remediation COMMIT 4): Sales may optionally attach
  // supporting files while the request is a DRAFT (also true in edit mode, since editing a
  // pricing request only ever happens while it is still DRAFT). Zero attachments remains valid —
  // there is no gate anywhere requiring at least one. Files can only be attached to a PERSISTED
  // request, so in create/revision mode this stays disabled until the first successful save
  // (createdId) exists; in edit mode initialSummary.id is already known. Revision mode never
  // uses initialSummary.id here — that id belongs to the PARENT request being revised, not the
  // new DRAFT this modal creates, and attachments must never be attributed to the wrong request.
  const attachablePricingRequestId = isEdit ? initialSummary?.id ?? null : createdId;
  const [attachments, setAttachments] = useState([]);
  const [attachmentsError, setAttachmentsError] = useState('');
  const [uploadingAttachment, setUploadingAttachment] = useState(false);
  const fileInputRef = useRef(null);

  useEffect(() => {
    if (attachablePricingRequestId == null) {
      setAttachments([]);
      return;
    }
    let cancelled = false;
    api.pricingRequests.listAttachments(attachablePricingRequestId)
      .then((res) => { if (!cancelled) setAttachments(res?.items ?? []); })
      .catch((err) => { if (!cancelled) setAttachmentsError(err.message || 'โหลดไฟล์แนบไม่สำเร็จ'); });
    return () => { cancelled = true; };
  }, [attachablePricingRequestId]);

  async function handleUploadAttachment(e) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file || attachablePricingRequestId == null) return;
    setAttachmentsError('');
    setUploadingAttachment(true);
    try {
      const res = await api.pricingRequests.uploadAttachment(attachablePricingRequestId, file);
      if (res?.attachment) setAttachments((cur) => [res.attachment, ...cur]);
    } catch (err) {
      setAttachmentsError(err.message || 'แนบไฟล์ไม่สำเร็จ');
    } finally {
      setUploadingAttachment(false);
    }
  }

  async function handleDeleteAttachment(attachmentId) {
    setAttachmentsError('');
    try {
      await api.pricingRequests.deleteAttachment(attachmentId);
      setAttachments((cur) => cur.filter((a) => a.id !== attachmentId));
    } catch (err) {
      setAttachmentsError(err.message || 'ลบไฟล์แนบไม่สำเร็จ');
    }
  }

  // Per-row debounce timers for the typed-โรงงาน lookup below, keyed by clientId; cleared on unmount.
  const brandLookupTimers = useRef(new Map());
  useEffect(() => () => { brandLookupTimers.current.forEach((t) => clearTimeout(t)); brandLookupTimers.current.clear(); }, []);

  /** Typed โรงงาน on a hand-typed line → default สกุลเงิน/หน่วยราคา from the catalogue (UAT item 3). */
  function scheduleBrandLookup(row) {
    const { clientId } = row;
    const brand = String(row.brand ?? '').trim();
    clearTimeout(brandLookupTimers.current.get(clientId));
    if (!brand || row.catalogPriceId) return; // a catalogue pick already carried its own terms
    brandLookupTimers.current.set(clientId, setTimeout(async () => {
      brandLookupTimers.current.delete(clientId);
      let terms = null;
      try {
        // ponytail: agreement is judged over the first 200 rows (CatalogController's cap) of a free-text
        // search; a factory with more rows can hide a disagreeing one. A factory-scoped endpoint closes it.
        terms = termsFromFactoryMatches(brand, (await api.catalog.prices(brand, null, 200))?.items);
      } catch { /* a convenience, never a blocker: the rep can still pick by hand */ }
      if (!terms) return;
      setItems((cur) => cur.map((it) => {
        // Only the row that asked, only while it still says this name, only into fields still blank.
        if (it.clientId !== clientId || it.catalogPriceId || String(it.brand ?? '').trim() !== brand) return it;
        const next = { ...it, autoTerms: { ...it.autoTerms } };
        TERM_FIELDS.forEach((key) => {
          if (!it[key] && terms[key]) { next[key] = terms[key]; next.autoTerms[key] = true; }
        });
        return next;
      }));
    }, BRAND_LOOKUP_DEBOUNCE_MS));
  }

  // A seeded hand-typed line (a deal line, or a legacy draft line) already names its โรงงาน, so it gets
  // the same lookup on open that typing would trigger. Mount only: later lookups ride brand edits.
  useEffect(() => {
    items.forEach((row) => {
      if (!row.requestedCurrency || !row.requestedPriceUnitBasis) scheduleBrandLookup(row);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function updateItem(index, patch) {
    setItems((cur) => cur.map((item, i) => (i === index ? patchRow(item, patch) : item)));
    if ('brand' in patch) scheduleBrandLookup({ ...items[index], ...patch });
    if (itemFieldErrors[index]) {
      setItemFieldErrors((cur) => {
        const next = { ...cur };
        delete next[index];
        return next;
      });
    }
  }

  // The new row lands at the bottom of a tall modal, so focus must follow it. Same mechanism as
  // QuotationEditorPage.jsx:947: the add records the row's clientId in a ref, the effect resolves
  // it to the DOM id the row rendered with (ids carry the flat index) and focuses it once. A ref,
  // not state: only an explicit add sets it, so typing elsewhere never pulls focus back.
  const pendingFocusRef = useRef(null);
  useEffect(() => {
    const pending = pendingFocusRef.current;
    if (!pending) return;
    const index = items.findIndex((it) => it.clientId === pending.rowId);
    if (index < 0) return;
    pendingFocusRef.current = null;
    focusQuotationField(`model-${index}`);
  }, [items]);

  function addItem() {
    const row = emptyItemFromTicketItem(null);
    pendingFocusRef.current = { rowId: row.clientId };
    setItems((cur) => [...cur, row]);
  }

  // itemFieldErrors is keyed by row index, so any insert/remove must move the keys with the rows:
  // `mapIndex` returns the row's new index, or null when the row is gone.
  function reindexErrors(errors, mapIndex) {
    const next = {};
    Object.entries(errors).forEach(([key, value]) => {
      const to = mapIndex(Number(key));
      if (to != null) next[to] = value;
    });
    return next;
  }

  function removeItem(index) {
    setItems((cur) => cur.filter((_, i) => i !== index));
    setItemFieldErrors((cur) => reindexErrors(cur, (i) => (i < index ? i : i > index ? i - 1 : null)));
  }

  function duplicateItem(index) {
    setItems((cur) => {
      const copy = { ...cur[index], clientId: newItemClientId(), id: null, sourceTicketItemId: null };
      return [...cur.slice(0, index + 1), copy, ...cur.slice(index + 1)];
    });
    // The copy lands at index + 1 and starts clean; every row after it moves down one.
    setItemFieldErrors((cur) => reindexErrors(cur, (i) => (i > index ? i + 1 : i)));
  }

  function validate() {
    if (!recipientLabel.trim()) return 'กรุณาระบุผู้รับคำขอราคา';
    if (items.length === 0) return 'ต้องมีรายการสินค้าอย่างน้อย 1 รายการ';
    // Mirrors CustomerChangeRevisionRequest.revisionReason's @NotBlank — revision mode only.
    if (isRevision && !revisionReason.trim()) return 'กรุณาระบุเหตุผลของการแก้ไข';
    return '';
  }

  // Per-row field completeness — mirrors PricingRequestService#requireItemFieldsComplete
  // (รุ่น/สี/ผิว/ขนาด/ความหนา/แผ่น-ตร.ม./แผ่น-กล่อง/จำนวน), reusing quotationMeta's own
  // validateQuotationItem with `skipPricing: true` rather than a parallel copy of the rule (this
  // form has no price mode of its own, so `priceMode`/`documentLanguage` are just the harmless
  // NET/TH defaults — skipPricing means neither is actually consulted for a price check).
  // Separate from validate() above: reports per-row, not as a single form-level message.
  function validateItemFields() {
    const next = {};
    items.forEach((item, index) => {
      const fieldErrors = validateQuotationItem(item, 'NET', 'TH', {
        skipPricing: true,
        // ประเทศต้นทาง is required on THIS form. ระยะเวลานำเข้า is NOT (import gives the time):
        // the origin's default still rides along on the wire, and the field is hidden (hideLeadTime).
        requireOriginCountry: !item.stockSource, // a stock line has no import origin (backend exempts it)
      });
      // The lead-time range check still runs inside validateQuotationItem, but its error renders only in
      // the field hideLeadTime removes, so it could block a save with nothing red on screen. The server
      // still refuses min > max, with a visible banner.
      delete fieldErrors.leadTimeMinDays;
      // DISPLAY-ONLY early warning for the case PricingRequestService#resolveItem rejects with a
      // 400: AREA mode deriving 0 pieces. The server rounds every fraction of a piece UP, so a
      // positive area is at least 1 piece unless pieces/ตร.ม. itself rounds to 0.00, the only case
      // this catches. Mirrors piecesPerSqmFromSqmPerPiece's display-only reciprocal, never
      // WastageCalculator, so it does not catch every zero-piece cause (an explicit piecesInput of
      // 0 is caught by the `piecesInput` check above). The server stays authoritative; this saves
      // the common case a round trip.
      if (!fieldErrors.areaSqm && item?.quantityMode === 'AREA') {
        const area = Number(item?.areaSqm);
        const piecesPerSqm = piecesPerSqmFromSqmPerPiece(item?.sqmPerPiece);
        if (area > 0 && piecesPerSqm != null && ceilPieces(area * piecesPerSqm) < 1) {
          fieldErrors.areaSqm = 'จำนวนที่คำนวณได้จะเป็น 0 ชิ้น กรุณาระบุพื้นที่ให้มากขึ้น';
        }
      }
      // Currency + price unit are required on every line sales submits.
      if (!item?.requestedCurrency) fieldErrors.requestedCurrency = 'กรุณาระบุสกุลเงิน';
      if (!item?.requestedPriceUnitBasis) fieldErrors.requestedPriceUnitBasis = 'กรุณาระบุหน่วยราคา';
      if (Object.keys(fieldErrors).length) next[index] = fieldErrors;
    });
    setItemFieldErrors(next);
    return Object.keys(next).length === 0;
  }

  function buildPayload({ includeClientRequestId = false } = {}) {
    const payload = {
      recipientType,
      // ผู้ติดต่อ goes in the existing `recipientContactId` column (see the `contact` state).
      recipientContactId: contact?.id ?? null,
      recipientLabel: recipientLabel.trim(),
      requiredDate: requiredDate || null,
      customerTargetPrice: customerTargetPrice !== '' ? Number(customerTargetPrice) : null,
      targetCurrency: targetCurrency.trim() || null,
      note: note.trim() || null,
      // Header terms.
      paymentTermMode: paymentTermMode || null,
      creditDays: paymentTermMode === 'CREDIT' && creditDays !== '' ? Number(creditDays) : null,
      validityDays: validityDays !== '' ? Number(validityDays) : null,
      printedByDisplayId: printedByDisplayId !== '' ? Number(printedByDisplayId) : null,
      salesRepDisplayId: salesRepDisplayId !== '' ? Number(salesRepDisplayId) : null,
      // ฝ่าย has no field any more, on this form or on the quotation. Always null, so a value an
      // older draft carries is cleared by the next save instead of riding along unseen.
      deptCode: null,
      unitCode: unitCode.trim() || null,
      omitContactHonorific,
      items: items.map(pricingRequestItemInputFromRow),
    };
    if (includeClientRequestId) payload.clientRequestId = clientRequestId;
    return payload;
  }

  // CustomerChangeRevisionRequest is buildPayload's shape plus revisionReason, and, unlike
  // create/edit, always carries a clientRequestId (the backend's idempotency guard against a
  // duplicate submit of the same revision).
  function buildRevisionPayload() {
    return {
      ...buildPayload({ includeClientRequestId: true }),
      revisionReason: revisionReason.trim(),
    };
  }

  // Both create-mode actions below share a duplicate-draft guard. If createFn already succeeded
  // (createdId set), because a prior submit attempt created the draft and then failed at the submit
  // step or the user clicked "บันทึกร่าง" again, neither handler calls createFn again. They push
  // the CURRENT form state onto the existing draft via updateFn: the user may have edited the form
  // to fix what failed, and resetting createdId would resurrect the create-twice bug.
  // onCreated() fires either way so the caller's close+refresh flow is unchanged.
  async function handleSaveDraft() {
    const validationError = validate();
    if (validationError) { setError(validationError); return; }
    if (!validateItemFields()) { setError('กรุณากรอกข้อมูลรายการสินค้าให้ครบถ้วน'); return; }
    setError('');
    setInfo(createdId != null ? 'ร่างถูกสร้างแล้ว กำลังบันทึกการเปลี่ยนแปลง' : '');
    setSaving(true);
    try {
      if (createdId != null) {
        if (updateFn) await updateFn(createdId, buildPayload());
      } else {
        const created = await createFn(buildPayload({ includeClientRequestId: true }));
        const id = created?.pricingRequest?.summary?.id;
        if (id != null) setCreatedId(id);
      }
      onCreated();
    } catch (err) {
      setError(err.message || 'สร้างคำขอราคาไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function handleSubmitToImport() {
    const validationError = validate();
    if (validationError) { setError(validationError); return; }
    if (!validateItemFields()) { setError('กรุณากรอกข้อมูลรายการสินค้าให้ครบถ้วน'); return; }
    setError('');
    setInfo(createdId != null ? 'ร่างถูกสร้างแล้ว กำลังส่งให้ฝ่ายนำเข้า' : '');
    setSaving(true);
    try {
      let id = createdId;
      if (id != null) {
        if (updateFn) await updateFn(id, buildPayload());
      } else {
        const created = await createFn(buildPayload({ includeClientRequestId: true }));
        id = created?.pricingRequest?.summary?.id;
        if (id != null) setCreatedId(id);
      }
      if (id != null) await submitFn(id);
      onCreated();
    } catch (err) {
      setError(err.message || 'ส่งคำขอราคาไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  // Edit mode: a straight PUT of the persisted DRAFT's full editable representation.
  async function handleUpdate() {
    const validationError = validate();
    if (validationError) { setError(validationError); return; }
    if (!validateItemFields()) { setError('กรุณากรอกข้อมูลรายการสินค้าให้ครบถ้วน'); return; }
    setError('');
    setSaving(true);
    try {
      await updateFn(initialSummary.id, buildPayload());
      onCreated();
    } catch (err) {
      setError(err.message || 'บันทึกการแก้ไขไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  // Revision mode: creates a brand-new DRAFT customer-change revision from the edited form state,
  // never an UPDATE against initialSummary.id (the immutable parent). createCustomerChangeRevision
  // is a single atomic backend call, so there is no half-done state to guard against on retry: a
  // failed attempt created nothing server-side.
  async function handleCreateRevision() {
    const validationError = validate();
    if (validationError) { setError(validationError); return; }
    if (!validateItemFields()) { setError('กรุณากรอกข้อมูลรายการสินค้าให้ครบถ้วน'); return; }
    setError('');
    setSaving(true);
    try {
      const created = await createRevisionFn(initialSummary.id, buildRevisionPayload());
      const newId = created?.pricingRequest?.summary?.id;
      if (newId != null) setCreatedId(newId);
      onCreated(created);
    } catch (err) {
      setError(err.message || 'สร้างรอบแก้ไขไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      title={isEdit ? 'แก้ไขร่างคำขอราคา' : isRevision ? 'สร้างรอบแก้ไขตามการเปลี่ยนแปลงของลูกค้า' : 'สร้างคำขอราคา'}
      subtitle={isEdit
        ? 'แก้ไขรายละเอียดคำขอราคานี้ก่อนส่งให้ฝ่ายนำเข้า — บันทึกแล้วยังคงเป็นร่างจนกว่าจะกดส่ง'
        : isRevision
          ? 'แก้ไขสินค้า/จำนวน/ผู้รับ/วันที่ตามที่ลูกค้าต้องการ — คำขอราคาเดิมจะถูกเก็บไว้ไม่เปลี่ยนแปลง และรายการนี้จะเริ่มเป็นร่างใหม่'
          : 'ส่งรายการสินค้าให้ฝ่ายนำเข้าเสนอราคา — แนบไฟล์ประกอบได้หลังบันทึกร่าง'}
      onClose={onClose}
      footer={isEdit ? (
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={saving}>ยกเลิก</Button>
          <Button type="button" variant="primary" disabled={saving} onClick={handleUpdate}>
            <Icon name="check" size={14} />
            {saving ? 'กำลังบันทึก…' : 'บันทึกการแก้ไข'}
          </Button>
        </>
      ) : isRevision ? (
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={saving}>ยกเลิก</Button>
          <Button type="button" variant="primary" disabled={saving || !revisionReason.trim()} onClick={handleCreateRevision}>
            <Icon name="check" size={14} />
            {saving ? 'กำลังสร้าง…' : 'สร้างรอบแก้ไข'}
          </Button>
        </>
      ) : (
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={saving}>ยกเลิก</Button>
          <Button type="button" variant="secondary" disabled={saving} onClick={handleSaveDraft}>
            {saving ? 'กำลังบันทึก…' : 'บันทึกร่าง'}
          </Button>
          <Button type="button" variant="primary" disabled={saving} onClick={handleSubmitToImport}>
            <Icon name="check" size={14} />
            {saving ? 'กำลังส่ง…' : 'ส่งให้ฝ่ายนำเข้า'}
          </Button>
        </>
      )}
    >
      <div className="flex flex-col gap-4">
        {isRevision ? (
          <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
            เหตุผลของการแก้ไข *
            <textarea
              className="min-h-16"
              value={revisionReason}
              onChange={(e) => setRevisionReason(e.target.value)}
              placeholder="เช่น ลูกค้าเปลี่ยนสินค้า/จำนวน/ขนาด"
            />
          </label>
        ) : null}
        <div>
          <span className="mb-1.5 block text-sm font-bold text-text-secondary">ผู้รับคำขอราคา *</span>
          <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="ผู้รับคำขอราคา">
            {RECIPIENT_OPTIONS.map((option) => {
              const selected = recipientType === option.code;
              return (
                <button
                  key={option.code}
                  type="button"
                  role="radio"
                  aria-checked={selected}
                  disabled={recipientLocked}
                  className={`rounded-full border px-3 py-1.5 text-xs font-bold disabled:cursor-not-allowed ${
                    selected ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text-muted'
                  } ${recipientLocked && !selected ? 'opacity-60' : ''}`}
                  onClick={() => { if (!recipientLocked) setRecipientType(option.code); }}
                >
                  {option.label}
                </button>
              );
            })}
          </div>
          {recipientLocked ? (
            <p className="mt-1.5 text-xs text-text-muted">
              เปลี่ยนผู้รับไม่ได้ในรอบแก้ไข — หากต้องการเสนอราคาให้ผู้รับรายอื่น ให้สร้างคำขอราคาใหม่
            </p>
          ) : null}
        </div>

        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          ชื่อผู้รับ / บริษัท *
          <input
            value={recipientLabel}
            onChange={(e) => { setRecipientLabel(e.target.value); setRecipientLabelTouched(true); }}
            placeholder="เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ"
          />
        </label>

        {/* ผู้ติดต่อ: separate from ผู้รับคำขอราคา above (see the `contact` state). Disabled ("เลือกลูกค้า
            ก่อน") when neither `deal` nor the persisted request carries a customerId (a ticket
            without customer linkage, or a test fixture that never set one). */}
        <QuotationContactPicker
          idPrefix="pcr-contact"
          customerId={pickerCustomerId}
          customerName={pickerCustomerName}
          value={contact}
          onChange={setContact}
          onResolve={setContact}
          showToast={showToast}
          omitContactHonorific={omitContactHonorific}
          onChangeOmitContactHonorific={setOmitContactHonorific}
        />

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
            วันที่ต้องการราคา
            <ThaiDateInput value={requiredDate} onChange={(e) => setRequiredDate(e.target.value)} />
          </label>
          <div className="flex gap-2">
            <label className="flex flex-1 flex-col gap-1.5 text-sm font-bold text-text-secondary">
              ราคาเป้าหมายของลูกค้า
              <input type="number" min="0" step="0.01" value={customerTargetPrice} onChange={(e) => setCustomerTargetPrice(e.target.value)} placeholder="ไม่บังคับ" />
            </label>
            <label className="flex w-24 flex-col gap-1.5 text-sm font-bold text-text-secondary">
              สกุลเงิน
              <input value={targetCurrency} onChange={(e) => setTargetCurrency(e.target.value.toUpperCase())} maxLength={3} />
            </label>
          </div>
        </div>

        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          หมายเหตุถึงฝ่ายนำเข้า
          <textarea className="min-h-16" value={note} onChange={(e) => setNote(e.target.value)} placeholder="ข้อมูลเพิ่มเติมสำหรับฝ่ายนำเข้า (ถ้ามี)" />
        </label>

        {/* No input for the quotation header terms (payment term / validity / display-name /
            dept-unit) or the read-only ช่องทางรับงาน line: Sales does not set them at คำขอราคา time.
            They are still carried on the wire (see the header-terms constants above), so a saved
            draft's terms survive an edit. */}
        <div>
          <div className="mb-2 text-sm font-bold text-text-secondary">รายการสินค้า *</div>
          <ul className="m-0 grid list-none gap-3 pl-0">
            {items.map((item, index) => (
              <QuotationItemRow
                key={item.clientId}
                item={item}
                index={index}
                hidePricing
                brandLabel="โรงงาน"
                // ประเทศต้นทาง (and its "อื่นๆ" typed name) is required on THIS form only;
                // direct-deal keeps it optional. ระยะเวลานำเข้า is not asked here (import gives the time).
                requireOriginCountry={!item.stockSource}
                hideLeadTime
                errors={itemFieldErrors[index] ?? {}}
                onChange={(patch) => updateItem(index, patch)}
                onRemove={items.length > 1 ? () => removeItem(index) : undefined}
                onDuplicate={() => duplicateItem(index)}
                // A catalogue pick auto-fills the currency + unit; sales may still change them.
                onCatalogPicked={(cat) => updateItem(index, catalogPickPatch(cat))}
                renderTerms={(row, rowIndex) => {
                  const rowErrors = itemFieldErrors[rowIndex] ?? {};
                  const currencies = REQUEST_CURRENCY_OPTIONS.includes(row.requestedCurrency) || !row.requestedCurrency
                    ? REQUEST_CURRENCY_OPTIONS : [row.requestedCurrency, ...REQUEST_CURRENCY_OPTIONS];
                  return (
                    <div className="grid grid-cols-[repeat(2,minmax(0,1fr))] gap-3 sm:max-w-md">
                      <FormField label="สกุลเงิน (ราคาโรงงาน)" htmlFor={`req-currency-${rowIndex}`} error={rowErrors.requestedCurrency}
                        hint="ฝ่ายนำเข้าเปลี่ยนไม่ได้">
                        <select id={`req-currency-${rowIndex}`} value={row.requestedCurrency ?? ''}
                          onChange={(e) => updateItem(rowIndex, { requestedCurrency: e.target.value })}>
                          <option value="">-- เลือก --</option>
                          {currencies.map((code) => <option key={code} value={code}>{code}</option>)}
                        </select>
                      </FormField>
                      <FormField label="หน่วยราคา" htmlFor={`req-unit-${rowIndex}`} error={rowErrors.requestedPriceUnitBasis}>
                        <select id={`req-unit-${rowIndex}`} value={row.requestedPriceUnitBasis ?? ''}
                          onChange={(e) => updateItem(rowIndex, { requestedPriceUnitBasis: e.target.value })}>
                          <option value="">-- เลือก --</option>
                          {UNIT_BASIS_OPTIONS.map((option) => <option key={option.code} value={option.code}>{`ต่อ ${option.label}`}</option>)}
                        </select>
                      </FormField>
                      <label className="col-span-2 flex items-center gap-2 text-sm font-bold text-text-secondary">
                        <input type="checkbox" checked={Boolean(row.stockSource)}
                          onChange={(e) => updateItem(rowIndex, { stockSource: e.target.checked ? (row.loadedStockSource ?? 'IN_THAILAND') : null })} />
                        จากสต็อก
                      </label>
                    </div>
                  );
                }}
              />
            ))}
          </ul>
          {/* Under the rows (UAT item 1): a rep adding a third line is looking at the second, not at
              the section header 760px above it — the direct-quotation editor made the same move. */}
          <Button type="button" variant="secondary" className="mt-3" style={{ fontSize: 12 }} onClick={addItem}>
            <Icon name="plus" size={13} /> เพิ่มรายการ
          </Button>
        </div>

        <div>
          <div className="mb-2 flex items-center justify-between">
            <span className="text-sm font-bold text-text-secondary">ไฟล์แนบประกอบคำขอราคา (ไม่บังคับ)</span>
            {attachablePricingRequestId != null ? (
              <>
                <input
                  ref={fileInputRef}
                  type="file"
                  className="hidden"
                  onChange={handleUploadAttachment}
                  disabled={uploadingAttachment}
                />
                <Button
                  type="button"
                  variant="secondary"
                  style={{ fontSize: 12 }}
                  disabled={uploadingAttachment}
                  onClick={() => fileInputRef.current?.click()}
                >
                  <Icon name="plus" size={13} /> {uploadingAttachment ? 'กำลังแนบไฟล์…' : 'แนบไฟล์'}
                </Button>
              </>
            ) : null}
          </div>
          {attachablePricingRequestId == null ? (
            <p className="text-2xs text-text-muted">บันทึกร่างก่อน จึงจะแนบไฟล์ได้</p>
          ) : attachments.length === 0 ? (
            <p className="text-2xs text-text-muted">ยังไม่มีไฟล์แนบ</p>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-1.5 pl-0">
              {attachments.map((attachment) => (
                <li
                  key={attachment.id}
                  className="flex items-center justify-between gap-2 rounded-lg border border-border bg-surface-subtle px-3 py-1.5 text-xs"
                >
                  <span className="truncate">{attachment.fileName}</span>
                  <Button
                    type="button"
                    variant="icon"
                    aria-label={`ลบไฟล์แนบ ${attachment.fileName}`}
                    onClick={() => handleDeleteAttachment(attachment.id)}
                  >
                    <Icon name="close" size={13} />
                  </Button>
                </li>
              ))}
            </ul>
          )}
          {attachmentsError ? <p role="alert" className="text-2xs font-bold text-danger-dark">{attachmentsError}</p> : null}
        </div>

        {info ? <div className="text-xs font-bold text-info" role="status">{info}</div> : null}
        {error ? <div className="py-2.5 px-3 rounded-md bg-danger-bg text-danger-dark font-bold text-[length:var(--text-sm)]" role="alert">{error}</div> : null}
      </div>
    </Modal>
  );
}
