import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { RECIPIENT_OPTIONS } from './pricingRequestMeta.js';
import { QuotationItemRow, newItemClientId } from '../quotations/QuotationItemRow.jsx';
import { QuotationContactPicker } from '../quotations/QuotationContactPicker.jsx';
import { piecesPerSqmFromSqmPerPiece, validateQuotationItem } from '../quotations/quotationMeta.js';
import { entryChannelLabel } from '../../utils/format.js';

// ลักษณะจำนวน / วันที่ต้องการส่งมอบ / สถานที่ส่งมอบ / ข้อกำหนดพิเศษ were removed from this form
// (owner request, 2026-08-11): Sales does not have that information at คำขอราคา time, so the
// four inputs only ever collected noise. The BACKEND CONTRACT IS UNCHANGED —
// PricingRequestItemRequest.quantityType is still @NotBlank, and the other three are still
// nullable columns — so every payload below keeps carrying all four. They are simply no longer
// user-editable:
//   - create mode sends this default plus three nulls;
//   - edit/revision mode PRESERVES whatever the persisted request already holds (see
//     itemFromExisting), because buildPayload writes the FULL item representation and nulling a
//     field here would silently erase data on any draft created before this change.
const DEFAULT_QUANTITY_TYPE = 'ESTIMATE';

/**
 * V185 (Phase 1 of the sales-flow redesign, owner ruling 2026-09-18): the item form Sales fills
 * is now the SAME as the direct-deal quotation's TILE row — this literally reuses
 * `QuotationItemRow` (see `../quotations/QuotationItemRow.jsx`) with `hidePricing` — minus
 * price/discount, which stay a later phase (the CEO will enter price/discount/วิธีกรอกราคา once
 * that phase lands). Sales no longer types a quantity/unit directly either: the server derives
 * requestedQty/requestedUnit/requestedUnitBasis from the tile fields via the SAME
 * `WastageCalculator` the direct-deal quotation uses (`PricingRequestService#resolveItems`) — see
 * that method's own Javadoc for the full derivation.
 *
 * The manual "ค้นหา Catalog" picker this modal used to have (removed 2026-08-11, replaced by a
 * background fuzzy-match effect) is gone entirely — QuotationItemRow's own รุ่น/ค้นหาแคตตาล็อก
 * typeahead (live search against the SAME `GET /catalog/prices` endpoint) replaces both. A
 * catalogue pick auto-fills brand from the catalogue's factory name, exactly as it does on the
 * direct-deal form — see that component's own `pickCatalog`.
 *
 * โรงงาน labelling (owner ruling 2026-09-18, reversed from an earlier ยี่ห้อ ruling): the SEPARATE
 * free-text `factory` input the OLD form had is still gone entirely — Sales no longer types a
 * factory, and the `factory` column and Import's `SetItemFactoryRequest` gap-fill are untouched.
 * What replaces it is the SAME field the direct-deal form calls ยี่ห้อ (the `brand` column,
 * catalogue-auto-filled) — but on THIS form it is labelled โรงงาน instead, via
 * `QuotationItemRow`'s `brandLabel` prop. This is a label choice only: the value sales enters
 * here is still stored in `brand`, never in `factory`.
 *
 * รายละเอียดสินค้า: the direct-deal form has no such field. This form maps its
 * หมายเหตุรายการ input (QuotationItemRow's own itemNotes field — same position as on the
 * direct-deal row) onto the existing `productDescription` column instead of showing a dedicated
 * box, so the column is not lost and Import still sees whatever notes Sales left.
 */
function emptyItemFromTicketItem(ticketItem) {
  // Mirrors TicketDetailPage's own qtyDisplay logic: an SQM-basis line quotes its sqm quantity in
  // AREA mode; a PIECE-basis line quotes its piece count in PIECES mode.
  const quantityMode = ticketItem?.unitBasis === 'SQM' ? 'AREA' : 'PIECES';
  return {
    clientId: newItemClientId(),
    // V110: seed the catalog link the deal-creation catalog picker already resolved (see
    // TicketCreateModal.jsx's applyCatalogItem), so this row doesn't need a re-search. Null for a
    // hand-typed ("custom") deal line, or a line created before V110 existed.
    sourceTicketItemId: ticketItem?.id ?? null,
    catalogPriceId: ticketItem?.catalogPriceId ?? null,
    // Carried through unchanged, same as the pre-V185 form did: this row has no UI for either
    // field (variantId never had one; the old form's dedicated โรงงาน input that WROTE `factory`
    // is gone per the 2026-09-18 ruling above), but a ticket_item that already carries a variant
    // pick or a factory (e.g. Import already resolved one before Sales revises the deal) must not
    // be silently nulled when this row round-trips through pricingRequestItemInputFromRow below.
    variantId: ticketItem?.variantId ?? null,
    factory: ticketItem?.factory ?? null,
    productCode: ticketItem?.catalogProductCode ?? '',
    brand: ticketItem?.brand ?? '',
    model: ticketItem?.model ?? '',
    color: ticketItem?.color ?? '',
    texture: ticketItem?.texture ?? '',
    sizeText: ticketItem?.size ?? '',
    thicknessMm: null,
    sqmPerPiece: ticketItem?.sqmPerPiece ?? null,
    piecesPerSqmDisplay: null,
    sqmPerPieceSource: ticketItem?.sqmPerPiece != null ? 'catalog' : null,
    catalogSqmPerPiece: null,
    catalogPriceUnit: ticketItem?.catalogPriceUnit ?? null,
    catalogSizeText: null,
    sqmPerBox: null,
    quantityMode,
    areaSqm: quantityMode === 'AREA' ? (ticketItem?.qtySqm ?? '') : '',
    piecesInput: quantityMode === 'PIECES' ? (ticketItem?.qty ?? '') : '',
    wastageMode: 'PERCENT',
    wastageValue: 0,
    piecesPerBox: '',
    // Owner-approved "sell loose pieces" default, same as the direct-deal form: every new row
    // rounds up to a full box unless the rep opts out.
    roundToFullBox: true,
    originCountry: '',
    // GLA-125: typed name when originCountry = "อื่นๆ" — see QuotationItemRow's own comment.
    originCountryOther: null,
    leadTimeMinDays: null,
    leadTimeMaxDays: null,
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

// V185 (Opus review finding #3, 2026-09-18): a PCR item created with the OLD (pre-V185) form has
// no quantityMode/areaSqm/piecesInput at all — only the legacy requestedQty/requestedQtySqm/
// requestedUnitBasis it was persisted with. Without this helper, itemFromExisting's plain
// `item?.quantityMode ?? 'AREA'` / `item?.areaSqm ?? ''` defaults open EVERY legacy line with
// quantityMode AREA and an EMPTY จำนวน field, silently losing the quantity that was actually
// requested — the rep sees a blank box with no clue what was there before.
//
// This recovers what it can, and is honest about what it cannot:
//   - PER_SQM   -> AREA mode, seeded from requestedQtySqm (falling back to requestedQty, the SAME
//                  number under the old basis when no separate sqm figure was stored) — the field
//                  now shows the number that was actually requested, not a stale wire-only one.
//   - PER_PIECE -> PIECES mode, seeded from requestedQty directly (1:1, no conversion needed).
//   - PER_BOX / PER_LINEAR_M -> neither AREA nor PIECES is a lossless translation of a box count
//     or a linear-metre length, so the field is left for the rep to re-enter (owner ruling: every
//     required field must be filled before saving regardless), but the ORIGINAL quantity + unit
//     is surfaced as read-only text via `legacyQuantityNote` (QuotationItemRow's `hint` on the
//     จำนวน field — see that component's own comment) so the rep can see what to re-type instead
//     of guessing.
//   - A row that already has a `quantityMode` (i.e. NOT a legacy row — created or last saved
//     under the new form) is left completely untouched; this only ever fires for the pre-V185
//     shape.
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
  // No recognizable legacy basis/quantity at all (e.g. a row that predates even requestedQty
  // being populated) — same blank-AREA default itemFromExisting always used.
  return { quantityMode: 'AREA', areaSqm: '', piecesInput: '', legacyQuantityNote: null };
}

// Edit mode seeds its rows from a persisted PricingRequestItemDto instead of a ticket_item.
// Existing catalog identity is preserved so editing a draft does not erase its product snapshot.
function itemFromExisting(item) {
  const quantitySeed = legacySeededQuantity(item);
  // V185 (Opus review finding #3, CORRECTED by a second review pass — finding N1): a legacy
  // PER_PIECE row has no sqmPerPiece of its own either, but one CAN be reconstructed from the two
  // legacy numbers it DOES have — requestedQtySqm was always requestedQty × sqmPerPiece at the
  // time it was derived under that basis, so dividing them back out recovers the original figure
  // exactly (mirrors WastageCalculator#sqmQuantityFromPieces's own multiplication, in reverse).
  //
  // This must be PER_PIECE-ONLY. A PER_SQM row's requestedQtySqm is NOT pieces × sqmPerPiece — it
  // IS the requested area itself (often literally equal to requestedQty, see
  // legacySeededQuantity's own PER_SQM branch above), so dividing the two back out there yields a
  // meaningless ~1.0 "ตร.ม./แผ่น" rather than the tile's real size. That fake sqmPerPiece would
  // then have the server derive piecesFinal ≈ areaSqm (e.g. 144 ตร.ม. -> "144 pieces" instead of
  // the ~400 a real ~0.36 ตร.ม./แผ่น tile would give), silently ordering the wrong quantity. A
  // PER_SQM (or PER_BOX/PER_LINEAR_M) legacy row is therefore left with NO derived sqmPerPiece —
  // the rep must fill in ตร.ม./แผ่น by hand, exactly like every other still-missing required
  // field on a legacy row.
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
    // Same rationale as emptyItemFromTicketItem above: no editor UI writes either field, but the
    // persisted request may already carry one (Import's SetItemFactoryRequest sets `factory`
    // directly in the DB, independent of this modal) — preserve it through the round-trip instead
    // of letting pricingRequestItemInputFromRow send an implicit null on every save.
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
    // A reloaded draft's own field carries no recorded provenance (that is UI-only state, never
    // persisted) — 'manual' just means "not freshly resolved from a catalog pick this session",
    // which is the honest reading for every field a GET just handed back. A value RECOVERED from
    // the legacy requestedQtySqm/requestedQty pair (legacySqmPerPiece) is equally "manual" by
    // this same reasoning — it is still not a catalog pick.
    sqmPerPieceSource: (item?.sqmPerPiece ?? legacySqmPerPiece) != null ? 'manual' : null,
    catalogSqmPerPiece: null,
    catalogPriceUnit: null,
    catalogSizeText: null,
    sqmPerBox: item?.sqmPerBox ?? null,
    quantityMode: quantitySeed.quantityMode,
    areaSqm: quantitySeed.areaSqm,
    piecesInput: quantitySeed.piecesInput,
    // Opus review finding #3: read-only text for a legacy PER_BOX/PER_LINEAR_M line's original
    // quantity — see legacySeededQuantity's own comment. Client-only; never sent on the wire (not
    // read by pricingRequestItemInputFromRow).
    legacyQuantityNote: quantitySeed.legacyQuantityNote,
    wastageMode: item?.wastageMode ?? 'NONE',
    wastageValue: item?.wastageValue ?? 0,
    piecesPerBox: item?.piecesPerBox ?? '',
    roundToFullBox: item?.roundToFullBox !== false,
    originCountry: item?.originCountry ?? '',
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

// Mirrors TicketSummaryDto's designerName/ownerName/buyerName/contactName/customerName — the
// deal's own recipient-shaped fields, the closest available identity for each ผู้รับ chip.
// Falls through recipientType-specific name -> contact -> customer, so the field is never left
// blank as long as the deal has ANY name recorded on it. CREATE MODE ONLY — never consulted for
// edit/revision (both seed recipientLabel from the persisted request instead, see the modal's
// seedsFromExisting guard around every call site below).
function recipientLabelForDeal(deal, recipientType) {
  if (!deal) return '';
  const byType = {
    DESIGNER: deal.designerName,
    OWNER: deal.ownerName,
    BUYER: deal.buyerName,
  }[recipientType];
  return byType || deal.contactName || deal.customerName || '';
}

function generateClientRequestId() {
  return globalThis.crypto?.randomUUID?.()
    ?? '00000000-0000-4000-8000-' + String(Date.now()).slice(-12).padStart(12, '0');
}

/** The wire `PricingRequestItemRequest` for one editor row — the inverse of
 * emptyItemFromTicketItem/itemFromExisting. requestedQty/requestedQtySqm/requestedUnit/
 * requestedUnitBasis are never sent: the server derives all four from the fields below
 * (PricingRequestService#resolveItems). `factory`/`variantId` ARE sent — round-tripped from
 * whatever `item.factory`/`item.variantId` already holds (seeded by emptyItemFromTicketItem /
 * itemFromExisting above) — because PricingRequestRepository.replaceItems deletes and re-inserts
 * every item on every create/update/createCustomerChangeRevision: omitting either field here
 * would silently NULL a factory Import already assigned via SetItemFactoryRequest on every sales
 * save, and would disable FactoryQuoteCarryForward.sameProductAndQuantity's factory match on the
 * next revision. (Fixed 2026-09-18 per Opus review finding #1 — this comment previously, and
 * incorrectly, claimed the fifth field "stays whatever Import/the catalog snapshot already set"
 * despite never being sent at all.) */
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
    // GLA-125: sent as-typed; the server (PricingRequestService#resolveItem) is what clears this
    // when originCountry is not "อื่นๆ" — this row-level payload builder does no normalization
    // itself, matching how it never normalizes any other field either (server-side derivation
    // only, see resolveItem's own Javadoc).
    originCountryOther: item.originCountryOther?.trim() || null,
    leadTimeMinDays: item.leadTimeMinDays ?? null,
    leadTimeMaxDays: item.leadTimeMaxDays ?? null,
    quantityType: item.quantityType ?? DEFAULT_QUANTITY_TYPE,
    targetDeliveryDate: item.targetDeliveryDate || null,
    deliveryLocation: item.deliveryLocation?.trim() || null,
    specialRequirement: item.specialRequirement?.trim() || null,
  };
}

/**
 * Create/submit modal for a new PricingRequest (commit 6), extended in the
 * review-remediation plan (Fix 2) to double as the edit modal for an existing
 * DRAFT: pass `mode="edit"` + `initialValue` (that request's summary + items,
 * as returned by `api.pricingRequests.get`) and `updateFn` instead of
 * `createFn`/`submitFn`. One component, not a forked near-duplicate — create
 * and edit differ only in how the form seeds its initial state and what the
 * footer buttons do; every field, validation, and row layout below is shared.
 *
 * Review remediation (COMMIT 5, P1 finding 3): a third `mode="revision"`
 * reuses the exact same seeding/catalog-picker/attachment-uploader machinery
 * to let Sales actually EDIT a customer-change revision's items — product,
 * collection, size, quantity, recipient, required date, adding/removing
 * lines — instead of the old PricingRequestDetailPage.revisionPayload
 * helper, which copied the current request verbatim and only collected a
 * revision reason, so the resulting DRAFT was always commercially identical
 * to its parent. Pass `mode="revision"` + `initialValue` (the CURRENT
 * request, same shape as edit mode) + `createRevisionFn`. The prior
 * (parent) request is never touched by this mode — no `updateFn` call is
 * ever made against it; the backend's own `createCustomerChangeRevision`
 * supersedes the parent as a side effect of creating the new DRAFT, keeping
 * the parent's own submitted record immutable.
 *
 * Item rows seed from the deal's existing ticket_item rows in create mode
 * (carrying sourceTicketItemId so Import can trace a request line back to the
 * original product line) or from the request's own persisted items in edit/
 * revision mode, but every field stays editable either way —
 * PricingRequestItemRequest carries its own independent descriptive copy, not
 * a read-only mirror of ticket_item.
 *
 * recipientContactId is wired to a real customer-contact picker (GLA-125 follow-up,
 * 2026-09-18 — the SAME `QuotationContactPicker` the direct-deal quotation editor uses, see the
 * `contact` state's own comment below for why it is bound to recipientContactId and never
 * cross-wired into recipientLabel's own auto-fill). recipientLabel (free text) remains
 * independently required either way — PricingRequestService.validateRecipientIdentifiable
 * accepts either recipientContactId or recipientLabel, and this modal's own validate() still
 * requires recipientLabel unconditionally (a contact pick is additive, not a replacement).
 */
export function PricingRequestCreateModal({
  ticketItems = [], deal = null, onClose, onCreated, createFn, submitFn,
  mode = 'create', initialValue = null, updateFn, createRevisionFn,
  // GLA-125 follow-up: passed straight through to QuotationContactPicker's own error toasts
  // (a failed contact create/edit-in-place) — optional, so a caller with no toast plumbing of
  // its own (none currently omit it, but the picker itself tolerates an absent one) still works.
  showToast,
}) {
  const isEdit = mode === 'edit';
  const isRevision = mode === 'revision';
  // Both edit and revision mode seed every field from a PERSISTED request (edit's own DRAFT,
  // or revision's current/parent request); only plain create mode seeds from the deal's raw
  // ticket_item rows instead.
  const seedsFromExisting = isEdit || isRevision;
  const initialSummary = initialValue?.summary ?? null;
  const [recipientType, setRecipientType] = useState(() => initialSummary?.recipientType ?? 'DESIGNER');
  const [recipientLabel, setRecipientLabel] = useState(() => (
    seedsFromExisting
      ? (initialSummary?.recipientLabel ?? '')
      : recipientLabelForDeal(deal, initialSummary?.recipientType ?? 'DESIGNER')
  ));
  // Tracks whether the user has hand-edited ผู้รับ's free-text label — once true, switching the
  // ผู้รับ chip must never overwrite what they typed (see the effect below). Stays false forever
  // in edit/revision mode since nothing ever writes recipientLabel from `deal` there.
  const [recipientLabelTouched, setRecipientLabelTouched] = useState(false);
  // GLA-125 follow-up (owner directive, 2026-09-18): ผู้สั่งซื้อ — the SAME `QuotationContactPicker`
  // the direct-deal quotation editor uses, bound to the SAME wire field the schema already
  // reserved for it (`recipientContactId`, V59) — this modal previously always sent that field as
  // `null` with no picker at all (see pricingRequestItemInputFromRow-era comment history). This is
  // deliberately SEPARATE from ผู้รับคำขอราคา (recipientType/recipientLabel above, which answers
  // "who is this PRICING REQUEST for" — designer/owner/buyer — a PCR-only categorisation with no
  // equivalent on the quotation): ผู้สั่งซื้อ answers "which registered customer CONTACT actually
  // placed the order", exactly the quotation's own question, so it is never cross-wired into
  // recipientLabel's own auto-fill from `deal`/recipientType.
  //
  // Seeded as a partial "stand-in" object (`{ id, firstName }`, no phone/email yet) from whichever
  // frozen id+name snapshot this mode already has — the picker's own `onResolve` then fills in the
  // rest once its contact list loads (see QuotationContactPicker's own class-level Javadoc for this
  // exact contract; QuotationEditorPage seeds its own ผู้สั่งซื้อ picker off `ticket.contactId`/
  // `ticket.contactName` the identical way for its `?ticket=` create path).
  const [contact, setContact] = useState(() => {
    if (seedsFromExisting) {
      return initialSummary?.recipientContactId != null
        ? { id: initialSummary.recipientContactId, firstName: initialSummary.recipientLabel ?? '', lastName: '' }
        : null;
    }
    return deal?.contactId != null
      ? { id: deal.contactId, firstName: deal.contactName ?? '', lastName: '' }
      : null;
  });
  // customerId/customerName: `deal` (create mode) or the persisted request's own summary (edit/
  // revision mode, GLA-125's `PricingRequestSummaryDto.customerId` addition) — whichever this mode
  // actually has. Without a customerId the picker just shows "เลือกลูกค้าก่อน" and stays disabled,
  // same as the direct-deal editor's own picker when its equivalent is unresolved.
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
  // GLA-125 (owner ruling 2026-09-18): header terms mirroring the direct-deal quotation's own
  // (V165/V179/V180) — NOT yet carried onto the quotation itself (Phase 3). All optional. Create
  // mode always starts blank (there is no "deal default" for any of these the way recipientLabel
  // has one) — only edit/revision seed from the persisted request.
  const [paymentTermMode, setPaymentTermMode] = useState(() => initialSummary?.paymentTermMode ?? '');
  const [creditDays, setCreditDays] = useState(() => (
    initialSummary?.creditDays != null ? String(initialSummary.creditDays) : ''
  ));
  const [validityDays, setValidityDays] = useState(() => (
    initialSummary?.validityDays != null ? String(initialSummary.validityDays) : ''
  ));
  const [printedByDisplayId, setPrintedByDisplayId] = useState(() => (
    initialSummary?.printedByDisplayId != null ? String(initialSummary.printedByDisplayId) : ''
  ));
  const [salesRepDisplayId, setSalesRepDisplayId] = useState(() => (
    initialSummary?.salesRepDisplayId != null ? String(initialSummary.salesRepDisplayId) : ''
  ));
  const [deptCode, setDeptCode] = useState(() => initialSummary?.deptCode ?? '');
  // unitCode doubles as the ผู้ออกแบบ picker's target, exactly as on the direct-deal form (V173) —
  // a designer pick is a frontend convenience that writes this SAME free-text field, never a
  // separate column; the designer's own name never reaches it, only the code does.
  const [unitCode, setUnitCode] = useState(() => initialSummary?.unitCode ?? '');
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
  // Fix 1 (review-remediation plan): once createFn has succeeded once, its
  // resulting id is held here so a retry (create succeeded, then submitFn
  // failed — or the user switches from "submit" to "save draft" after that
  // failure) reuses the same draft instead of calling createFn again and
  // orphaning a duplicate. Always null in edit mode (there is no create step).
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

  // GLA-125: แสดงชื่อผู้พิมพ์เป็น/แสดงชื่อพนักงานขายเป็น reuse the SAME eligible-employee list
  // the direct-deal quotation editor already fetches (DealQuotationRepository
  // #findEligibleQuotationDisplayNameOptions — sales division employees plus any
  // can_create_quotation grant holder). No PCR-specific endpoint needed.
  const [displayNameOptions, setDisplayNameOptions] = useState([]);
  const [displayNameOptionsLoading, setDisplayNameOptionsLoading] = useState(true);
  useEffect(() => {
    let cancelled = false;
    api.dealQuotations.displayNameOptions()
      .then((res) => { if (!cancelled) setDisplayNameOptions(res?.items ?? []); })
      .catch(() => { /* non-critical: selects just render with no options */ })
      .finally(() => { if (!cancelled) setDisplayNameOptionsLoading(false); });
    return () => { cancelled = true; };
  }, []);

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

  function updateItem(index, patch) {
    setItems((cur) => cur.map((item, i) => (i === index ? { ...item, ...patch } : item)));
    if (itemFieldErrors[index]) {
      setItemFieldErrors((cur) => {
        const next = { ...cur };
        delete next[index];
        return next;
      });
    }
  }

  function addItem() {
    setItems((cur) => [...cur, emptyItemFromTicketItem(null)]);
  }

  function removeItem(index) {
    setItems((cur) => cur.filter((_, i) => i !== index));
    setItemFieldErrors((cur) => {
      const next = {};
      Object.entries(cur).forEach(([key, value]) => {
        const i = Number(key);
        if (i < index) next[i] = value;
        else if (i > index) next[i - 1] = value;
      });
      return next;
    });
  }

  function duplicateItem(index) {
    setItems((cur) => {
      const copy = { ...cur[index], clientId: newItemClientId(), id: null, sourceTicketItemId: null };
      return [...cur.slice(0, index + 1), copy, ...cur.slice(index + 1)];
    });
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
        // GLA-125: ประเทศต้นทาง and ระยะเวลานำเข้า are required on THIS form (owner ruling
        // 2026-09-18) — requireLeadTime is direct-deal's own SUBMIT-only flag, reused here
        // unconditionally since a PricingRequest has no separate submit-time gate to defer to.
        requireLeadTime: true,
        requireOriginCountry: true,
        // owner ruling 2026-09-26: a factory (China/Italy) may not give a thickness — Sales may
        // submit it blank, and import/CEO fill it (setItemThickness) before costing. See
        // QuotationItemRow's thicknessRequired={false} below (same reversal, on the input's marker).
        thicknessOptional: true,
      });
      // Opus review finding #2 (2026-09-18): a cheap, DISPLAY-ONLY early warning for the same
      // case PricingRequestService#resolveItem now rejects server-side with a 400 -- AREA mode
      // rounding down to 0 pieces (e.g. 0.3 m² against 0.72 m²/piece). This mirrors ONLY
      // piecesPerSqmFromSqmPerPiece's already-DISPLAY-ONLY reciprocal (see that helper's own
      // comment), never WastageCalculator itself, so it deliberately does NOT catch every
      // zero-piece cause the server checks (e.g. an explicit piecesInput of 0 would already be
      // caught by the `piecesInput` check above via a different message) -- the server stays the
      // authoritative check either way; this only saves the common case a round trip.
      if (!fieldErrors.areaSqm && item?.quantityMode === 'AREA') {
        const area = Number(item?.areaSqm);
        const piecesPerSqm = piecesPerSqmFromSqmPerPiece(item?.sqmPerPiece);
        if (area > 0 && piecesPerSqm != null && Math.round(area * piecesPerSqm) < 1) {
          fieldErrors.areaSqm = 'จำนวนที่คำนวณได้จะเป็น 0 ชิ้น กรุณาระบุพื้นที่ให้มากขึ้น';
        }
      }
      if (Object.keys(fieldErrors).length) next[index] = fieldErrors;
    });
    setItemFieldErrors(next);
    return Object.keys(next).length === 0;
  }

  function buildPayload({ includeClientRequestId = false } = {}) {
    const payload = {
      recipientType,
      // GLA-125 follow-up: ผู้สั่งซื้อ, wired to the SAME `recipientContactId` column this modal
      // always sent as implicitly undefined before — see the `contact` state's own comment above.
      recipientContactId: contact?.id ?? null,
      recipientLabel: recipientLabel.trim(),
      requiredDate: requiredDate || null,
      customerTargetPrice: customerTargetPrice !== '' ? Number(customerTargetPrice) : null,
      targetCurrency: targetCurrency.trim() || null,
      note: note.trim() || null,
      // GLA-125 header terms.
      paymentTermMode: paymentTermMode || null,
      creditDays: paymentTermMode === 'CREDIT' && creditDays !== '' ? Number(creditDays) : null,
      validityDays: validityDays !== '' ? Number(validityDays) : null,
      printedByDisplayId: printedByDisplayId !== '' ? Number(printedByDisplayId) : null,
      salesRepDisplayId: salesRepDisplayId !== '' ? Number(salesRepDisplayId) : null,
      deptCode: deptCode.trim() || null,
      unitCode: unitCode.trim() || null,
      omitContactHonorific,
      items: items.map(pricingRequestItemInputFromRow),
    };
    if (includeClientRequestId) payload.clientRequestId = clientRequestId;
    return payload;
  }

  // Review remediation (COMMIT 5, P1 finding 3): CustomerChangeRevisionRequest is buildPayload's
  // shape plus revisionReason — and, unlike create/edit, always carries a clientRequestId (the
  // backend's own idempotency guard against a duplicate submit of the same revision).
  // recipientContactId now travels through buildPayload's own field (GLA-125 follow-up) like
  // every other field here — no separate override needed.
  function buildRevisionPayload() {
    return {
      ...buildPayload({ includeClientRequestId: true }),
      revisionReason: revisionReason.trim(),
    };
  }

  // Fix 1 (review-remediation plan): both create-mode actions below share the
  // same duplicate-draft guard. If createFn already succeeded once (createdId
  // is set) — either because a prior submit-to-import attempt created the
  // draft and then failed at the submit step, or the user clicked "บันทึกร่าง"
  // again after that — neither handler calls createFn a second time. Instead
  // they push the CURRENT form state onto the existing draft via updateFn
  // (update-then-submit/save, not "reset createdId and create fresh"): the
  // user may well have edited the form to fix whatever made the first attempt
  // fail (e.g. a validation error), and discarding createdId would silently
  // resurrect the create-twice bug this fix exists to close. onCreated() still
  // fires either way so the caller's normal close+refresh flow is unchanged.
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

  // Edit mode (Fix 2): a straight update of the persisted DRAFT — no create
  // step, no submit step, just PUT the full editable representation.
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

  // Revision mode (COMMIT 5, P1 finding 3): creates a brand-new DRAFT customer-change revision
  // carrying whatever edits the user made to the seeded (current-request) form state above —
  // never an UPDATE against initialSummary.id, which stays the immutable parent record.
  // createCustomerChangeRevision is a single atomic backend call (unlike create mode's separate
  // create-then-submit), so there is no intermediate "draft created, now push a second write"
  // step to guard against re-creating on retry the way Fix 1 does for create mode — a failed
  // attempt here has created nothing server-side to orphan.
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
                  className={`rounded-full border px-3 py-1.5 text-xs font-bold ${
                    selected ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text-muted'
                  }`}
                  onClick={() => setRecipientType(option.code)}
                >
                  {option.label}
                </button>
              );
            })}
          </div>
        </div>

        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          ชื่อผู้รับ / บริษัท *
          <input
            value={recipientLabel}
            onChange={(e) => { setRecipientLabel(e.target.value); setRecipientLabelTouched(true); }}
            placeholder="เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ"
          />
        </label>

        {/* GLA-125 follow-up: ผู้สั่งซื้อ — see the `contact` state's own comment above for why
            this is deliberately separate from ผู้รับคำขอราคา above. Renders disabled ("เลือกลูกค้า
            ก่อน") when neither `deal` nor the persisted request carries a customerId — a request
            whose ticket predates customer linkage, or a mock/test fixture that never set one. */}
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
            <input type="date" value={requiredDate} onChange={(e) => setRequiredDate(e.target.value)} />
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

        {/* GLA-125 (owner ruling 2026-09-18): header terms mirroring the direct-deal quotation's
            own (V165/V179/V180) — none of these are required (a PricingRequest has no price yet,
            so unlike quotation's deposit/remainder trio there is nothing here that MUST be
            decided before saving). NOT yet carried onto the customer quotation itself — that is
            Phase 3, see V185's migration header. "ไม่เติม 'คุณ'" now lives on the ผู้สั่งซื้อ
            picker above (its own built-in control, wired via omitContactHonorific/
            onChangeOmitContactHonorific), not duplicated here. */}
        <div className="rounded-md border border-border bg-surface-subtle p-3">
          <span className="mb-2 block text-sm font-bold text-text-secondary">เงื่อนไขสำหรับใบเสนอราคา (ไม่บังคับ)</span>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <span className="mb-1.5 block text-sm font-bold text-text-secondary">เงื่อนไขการชำระเงิน</span>
              <div className="flex flex-wrap items-center gap-2">
                <label className="flex items-center gap-1.5 text-sm">
                  <input
                    type="radio"
                    name="pcrPaymentTermMode"
                    checked={paymentTermMode === 'CREDIT'}
                    onChange={() => setPaymentTermMode('CREDIT')}
                  />
                  เครดิต
                </label>
                <input
                  type="number"
                  min="1"
                  className="w-20"
                  disabled={paymentTermMode !== 'CREDIT'}
                  value={creditDays}
                  onChange={(e) => setCreditDays(e.target.value)}
                  placeholder="วัน"
                  aria-label="ระยะเวลาเครดิต (วัน)"
                />
                <span className="text-text-muted">วัน</span>
                <label className="flex items-center gap-1.5 text-sm">
                  <input
                    type="radio"
                    name="pcrPaymentTermMode"
                    checked={paymentTermMode === 'ON_DELIVERY'}
                    onChange={() => setPaymentTermMode('ON_DELIVERY')}
                  />
                  ชำระเมื่อส่งมอบ
                </label>
              </div>
            </div>
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              ยืนราคา (วัน)
              <input type="number" min="0" value={validityDays} onChange={(e) => setValidityDays(e.target.value)} placeholder="ไม่บังคับ" />
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              แสดงชื่อผู้พิมพ์เป็น
              <select value={printedByDisplayId} onChange={(e) => setPrintedByDisplayId(e.target.value)} disabled={displayNameOptionsLoading}>
                <option value="">(ใช้ชื่อผู้สร้างคำขอราคา)</option>
                {displayNameOptions.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
              </select>
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              แสดงชื่อพนักงานขายเป็น
              <select value={salesRepDisplayId} onChange={(e) => setSalesRepDisplayId(e.target.value)} disabled={displayNameOptionsLoading}>
                <option value="">(ใช้ชื่อผู้สร้างคำขอราคา)</option>
                {displayNameOptions.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
              </select>
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              ฝ่าย
              <input value={deptCode} onChange={(e) => setDeptCode(e.target.value)} placeholder="ไม่บังคับ" />
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              หน่วยงาน / รหัสผู้ออกแบบ
              <input value={unitCode} onChange={(e) => setUnitCode(e.target.value)} placeholder="ไม่บังคับ" />
            </label>
          </div>
          {/* GLA-125: ช่องทางรับงาน is sales.ticket.entry_channel (V51/V144), set once at
              deal-creation time and immutable from here — this form only ever DISPLAYS it,
              read-only, never a new column on sales.pricing_request. Only available in create
              mode, where `deal` (the ticket summary) is actually passed in; edit/revision mode
              has no reliable `deal` prop to read this from. */}
          {deal?.entryChannel ? (
            <p className="mt-3 text-xs text-text-muted">ช่องทางรับงาน: {entryChannelLabel(deal.entryChannel).label}</p>
          ) : null}
        </div>

        <div>
          <div className="mb-2 flex items-center justify-between">
            <span className="text-sm font-bold text-text-secondary">รายการสินค้า *</span>
            <Button type="button" variant="secondary" style={{ fontSize: 12 }} onClick={addItem}>
              <Icon name="plus" size={13} /> เพิ่มรายการ
            </Button>
          </div>
          <ul className="grid list-none gap-3 pl-0">
            {items.map((item, index) => (
              <QuotationItemRow
                key={item.clientId}
                item={item}
                index={index}
                hidePricing
                brandLabel="โรงงาน"
                thicknessRequired={false}
                // GLA-125 (owner ruling 2026-09-18): ประเทศต้นทาง (and its "อื่นๆ" typed name) and
                // ระยะเวลานำเข้า are required on THIS form only — direct-deal keeps both optional.
                requireOriginCountry
                errors={itemFieldErrors[index] ?? {}}
                onChange={(patch) => updateItem(index, patch)}
                onRemove={items.length > 1 ? () => removeItem(index) : undefined}
                onDuplicate={() => duplicateItem(index)}
              />
            ))}
          </ul>
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
            <ul className="flex flex-col gap-1.5">
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
