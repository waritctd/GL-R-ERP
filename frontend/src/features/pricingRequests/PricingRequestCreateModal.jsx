import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { QUANTITY_TYPE_OPTIONS, RECIPIENT_OPTIONS, UNIT_BASIS_OPTIONS, unitBasisLabel } from './pricingRequestMeta.js';

// Maps price_catalog.product_prices.price_unit ('per_sqm' | 'per_piece' | 'per_box' |
// 'per_linear_m' | 'unknown') to our canonical UnitBasis code, for pre-filling the unit select
// when a catalog product is picked. Falls back to the row's current basis for 'unknown' or any
// unrecognised value, rather than guessing wrong.
function unitBasisForPriceUnit(priceUnit, fallback) {
  switch (String(priceUnit ?? '').toLowerCase()) {
    case 'per_sqm': return 'PER_SQM';
    case 'per_piece': return 'PER_PIECE';
    case 'per_box': return 'PER_BOX';
    case 'per_linear_m': return 'PER_LINEAR_M';
    default: return fallback;
  }
}

let pricingCatalogTimer = null;

function debouncedCatalogSearch(query, callback) {
  clearTimeout(pricingCatalogTimer);
  pricingCatalogTimer = setTimeout(() => callback(query), 250);
}

// TicketItemDto's unitBasis is a code ('PIECE' | 'SQM'), never a display unit —
// mirrors the label mapping TicketDetailPage already uses for the same field
// (unitBasis === 'SQM' ? 'ตร.ม.' : 'แผ่น').
function unitLabelForTicketItem(ticketItem) {
  return ticketItem?.unitBasis === 'SQM' ? 'ตร.ม.' : 'แผ่น';
}

// Same mapping as unitLabelForTicketItem, but the canonical UnitBasis code — TicketItemDto's
// own unitBasis ('PIECE' | 'SQM') is a narrower, ticket-specific enum, not the pricing-request
// item's four-way UnitBasis, so this is a deliberate translation, not a passthrough.
function unitBasisForTicketItem(ticketItem) {
  return ticketItem?.unitBasis === 'SQM' ? 'PER_SQM' : 'PER_PIECE';
}

// Mirrors TicketDetailPage's own qtyDisplay logic: an SQM-basis line quotes
// its sqm quantity, a PIECE-basis line quotes its piece count.
function quantityForTicketItem(ticketItem) {
  if (ticketItem?.unitBasis === 'SQM' && ticketItem?.qtySqm != null) return ticketItem.qtySqm;
  return ticketItem?.qty ?? 1;
}

function emptyItemFromTicketItem(ticketItem, deliveryLocationSeed = '') {
  return {
    sourceTicketItemId: ticketItem?.id ?? null,
    // V110: seed the catalog link the deal-creation catalog picker already resolved (see
    // TicketCreateModal.jsx's applyCatalogItem), so this row doesn't need a re-search — the
    // whole point of this fix. Null for a hand-typed ("custom") deal line, or a line created
    // before V110 existed; findSingleFuzzyCatalogMatch below is the fallback for that case.
    productId: ticketItem?.catalogPriceId ?? null,
    catalogProductCode: ticketItem?.catalogProductCode ?? '',
    catalogBasePrice: null,
    catalogCurrency: '',
    brand: ticketItem?.brand ?? '',
    model: ticketItem?.model ?? '',
    productDescription: '',
    color: ticketItem?.color ?? '',
    texture: ticketItem?.texture ?? '',
    size: ticketItem?.size ?? '',
    factory: ticketItem?.factory ?? '',
    requestedQty: quantityForTicketItem(ticketItem),
    requestedUnit: unitLabelForTicketItem(ticketItem),
    requestedUnitBasis: unitBasisForTicketItem(ticketItem),
    quantityType: 'ESTIMATE',
    targetDeliveryDate: '',
    // Seeded from the deal's own project (create mode only — see the modal's
    // dealDeliveryLocationSeed) rather than left blank like the rest of this row's fields, which
    // have no deal-level equivalent to seed from.
    deliveryLocation: deliveryLocationSeed,
    specialRequirement: '',
    // Fuzzy catalog fallback provenance (V110 follow-up) — true only when
    // findSingleFuzzyCatalogMatch auto-applied a match with no user action, so the UI can badge
    // it as "unconfirmed" rather than a real user pick.
    catalogAutoApplied: false,
  };
}

// Edit mode seeds its rows from a persisted PricingRequestItemDto instead of a
// ticket_item. Existing catalog identity and catalog-price hints are preserved
// so editing a draft does not erase its product snapshot.
function itemFromExisting(item) {
  return {
    sourceTicketItemId: item?.sourceTicketItemId ?? null,
    productId: item?.productId ?? null,
    catalogProductCode: item?.catalogProductCode ?? '',
    catalogBasePrice: item?.catalogBasePrice ?? null,
    catalogCurrency: item?.catalogCurrency ?? '',
    brand: item?.brand ?? '',
    model: item?.model ?? '',
    productDescription: item?.productDescription ?? '',
    color: item?.color ?? '',
    texture: item?.texture ?? '',
    size: item?.size ?? '',
    factory: item?.factory ?? '',
    requestedQty: item?.requestedQty ?? 1,
    requestedUnit: item?.requestedUnit ?? '',
    requestedUnitBasis: item?.requestedUnitBasis ?? 'PER_PIECE',
    quantityType: item?.quantityType ?? 'ESTIMATE',
    targetDeliveryDate: item?.targetDeliveryDate ?? '',
    deliveryLocation: item?.deliveryLocation ?? '',
    specialRequirement: item?.specialRequirement ?? '',
    // Edit/revision seed from a PERSISTED request, never from the fuzzy catalog fallback —
    // always false here.
    catalogAutoApplied: false,
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

function normalizeForCatalogMatch(value) {
  return String(value ?? '').trim().toLowerCase().replace(/\s+/g, ' ');
}

// How many candidates the fuzzy fallback asks for. Deliberately larger than the number a row
// could plausibly match: GET /catalog/prices is `ORDER BY f.name, pp.collection, pp.product_code
// LIMIT :limit` (CatalogRepository) — ALPHABETICAL BY FACTORY, not by relevance — so a small
// limit does not return "the 5 best candidates", it returns the alphabetically-first factories
// and hides the rest. See FUZZY_MATCH_LIMIT's use below for why a FULL page is treated as
// ambiguous rather than as a candidate set.
const FUZZY_MATCH_LIMIT = 50;

// Fuzzy fallback (V110 follow-up) for deal lines created before the catalog link existed (no
// ticketItem.catalogPriceId to seed productId from): auto-applies ONLY when the search returns
// EXACTLY ONE candidate that agrees with this row on every descriptive field the row actually
// has. Zero or ambiguous results are left alone — the row just falls back to the normal manual
// catalog search, exactly as it did before this fallback existed. A wrong auto-match would feed
// a wrong base price into costing, so it is never silent — see the catalogAutoApplied badge
// rendered on the row below.
//
// The predicate deliberately covers factory/color/texture as well as model/size. Matching on
// model+size alone was not enough: deriveItemFromCatalogProduct OVERWRITES brand/factory/color/
// texture from the chosen product, so a candidate agreeing on model+size but differing on
// factory would silently rewrite the deal line's factory (and point productId at that other
// factory's price). Each field is only enforced when the ROW has a value for it — a blank field
// on the row is "unknown", not "must be blank on the product".
function findSingleFuzzyCatalogMatch(candidates, item) {
  const list = candidates ?? [];
  // A full page means the server truncated: because the ordering is alphabetical rather than
  // relevance-ranked, matches beyond the cut are invisible here, so "exactly one match" would be
  // an artifact of truncation rather than evidence of uniqueness. Ambiguity is UNKNOWN, not
  // absent — decline to guess and leave the row for a manual search.
  if (list.length >= FUZZY_MATCH_LIMIT) return null;
  const agreesOn = (rowValue, productValue) => {
    const row = normalizeForCatalogMatch(rowValue);
    if (!row) return true; // row has no opinion on this field
    return row === normalizeForCatalogMatch(productValue);
  };
  const matches = list.filter((product) => (
    agreesOn(item.model, product.collection || product.productName)
    && agreesOn(item.size, product.sizeRaw)
    && agreesOn(item.factory, product.factoryName)
    && agreesOn(item.color, product.color)
    && agreesOn(item.texture, product.surface)
  ));
  return matches.length === 1 ? matches[0] : null;
}

// Pure product-application logic shared by applyCatalogItem (a real user pick) and the fuzzy
// catalog fallback effect (an automatic best-guess) — same fields, same brand-fallback rule,
// different only in who calls it and whether catalogAutoApplied ends up true afterward.
function deriveItemFromCatalogProduct(item, product) {
  // Mirrors PricingRequestRepository.snapshotCatalogSelections: catalog_brand =
  // COALESCE(pri.brand, pp.grade) — an already-typed brand wins, otherwise the catalog's
  // own grade fills in. Brand is deliberately NOT the factory name (that belongs in the
  // separate `factory` field below) — picking a catalog product used to overwrite brand
  // with product.factoryName, which was wrong (review finding).
  const brand = item.brand?.trim() ? item.brand : (product.grade ?? '');
  const requestedUnitBasis = unitBasisForPriceUnit(product.priceUnit, item.requestedUnitBasis);
  return {
    ...item,
    productId: product.priceId ?? null,
    catalogProductCode: product.productCode ?? '',
    catalogBasePrice: product.price ?? null,
    catalogCurrency: product.currency ?? '',
    brand,
    model: product.collection ?? product.productName ?? product.productCode ?? item.model ?? '',
    productDescription: product.productName ?? product.productCode ?? item.productDescription ?? '',
    color: product.color ?? '',
    texture: product.surface ?? '',
    size: product.sizeRaw ?? '',
    factory: product.factoryName ?? '',
    requestedUnitBasis,
    requestedUnit: unitBasisLabel(requestedUnitBasis),
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
 * reuses the exact same seeding/catalog-picker/unit-select/attachment-uploader
 * machinery to let Sales actually EDIT a customer-change revision's items —
 * product, collection, size, quantity, recipient, required date, adding/
 * removing lines — instead of the old PricingRequestDetailPage.revisionPayload
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
 * recipientContactId (a real customer-contact picker) is not wired up yet —
 * recipientLabel (free text) is the only way to identify a recipient today.
 * PricingRequestService.validateRecipientIdentifiable accepts either.
 */
// Mirrors PricingRequestService.validateItems: an item must actually name a
// product somehow: a deal line, catalog product, model name, or dedicated
// product description. Brand alone is deliberately NOT enough.
function itemIdentityValid(item) {
  return Boolean(item.sourceTicketItemId) || Boolean(item.productId)
    || Boolean(item.model?.trim()) || Boolean(item.productDescription?.trim());
}

function generateClientRequestId() {
  return globalThis.crypto?.randomUUID?.()
    ?? '00000000-0000-4000-8000-' + String(Date.now()).slice(-12).padStart(12, '0');
}

export function PricingRequestCreateModal({
  ticketItems = [], deal = null, onClose, onCreated, createFn, submitFn,
  mode = 'create', initialValue = null, updateFn, createRevisionFn,
}) {
  const isEdit = mode === 'edit';
  const isRevision = mode === 'revision';
  // Both edit and revision mode seed every field from a PERSISTED request (edit's own DRAFT,
  // or revision's current/parent request); only plain create mode seeds from the deal's raw
  // ticket_item rows instead.
  const seedsFromExisting = isEdit || isRevision;
  const initialSummary = initialValue?.summary ?? null;
  // Fix for "ทุกอย่างควร autofill ตามข้อมูลขั้นตอนนั้น": deal-derived seeds, CREATE MODE ONLY —
  // edit/revision seed every field from the PERSISTED request above and must never have `deal`
  // leak into them, so this is gated the same way seedsFromExisting gates itemFromExisting vs.
  // emptyItemFromTicketItem just above.
  const dealDeliveryLocationSeed = !seedsFromExisting ? (deal?.projectName ?? '') : '';
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
  const [requiredDate, setRequiredDate] = useState(() => initialSummary?.requiredDate ?? '');
  const [customerTargetPrice, setCustomerTargetPrice] = useState(() => (
    initialSummary?.customerTargetPrice != null ? String(initialSummary.customerTargetPrice) : ''
  ));
  const [targetCurrency, setTargetCurrency] = useState(() => initialSummary?.targetCurrency ?? 'THB');
  const [note, setNote] = useState(() => (
    seedsFromExisting ? (initialSummary?.note ?? '') : (deal?.note ?? '')
  ));
  const [revisionReason, setRevisionReason] = useState('');
  const [clientRequestId] = useState(() => generateClientRequestId());
  const [items, setItems] = useState(() => {
    if (seedsFromExisting) {
      return initialValue?.items?.length ? initialValue.items.map(itemFromExisting) : [emptyItemFromTicketItem(null)];
    }
    return ticketItems.length
      ? ticketItems.map((ticketItem) => emptyItemFromTicketItem(ticketItem, dealDeliveryLocationSeed))
      : [emptyItemFromTicketItem(null, dealDeliveryLocationSeed)];
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

  // Fuzzy catalog fallback (V110 follow-up, create mode only) for deal lines created before
  // ticket_item.catalog_price_id existed: a row with a model but no productId gets ONE
  // best-effort catalog search, auto-applying only an unambiguous single match (see
  // findSingleFuzzyCatalogMatch). Rows processed one at a time (sequential await, not
  // Promise.all) so opening a many-line deal never fires a burst of concurrent requests.
  // Runs once on mount only — `items` is read fresh via the functional setItems update inside,
  // never captured stale, so it is deliberately absent from the dependency array below.
  useEffect(() => {
    if (seedsFromExisting) return;
    let cancelled = false;
    (async () => {
      for (let index = 0; index < items.length; index++) {
        if (cancelled) return;
        const item = items[index];
        if (item.productId != null || !item.model?.trim()) continue;
        let res;
        try {
          res = await api.catalog.prices(item.model, undefined, FUZZY_MATCH_LIMIT);
        } catch {
          continue;
        }
        if (cancelled) return;
        const match = findSingleFuzzyCatalogMatch(res?.items, item);
        if (!match) continue;
        setItems((cur) => cur.map((row, i) => {
          // Identify the target row by sourceTicketItemId, NOT by array index. These searches
          // run sequentially, so the in-flight window across a many-line deal is seconds long,
          // and removeItem() re-indexes every row after the one deleted — keying on `index`
          // would land this match on whatever row shifted into that slot, silently overwriting
          // a DIFFERENT product's fields (and badging the wrong row as auto-matched). Every row
          // this effect can touch is a seeded deal line, so it always has a sourceTicketItemId;
          // the index comparison is only the fallback for a row without one.
          const isTarget = item.sourceTicketItemId != null
            ? row.sourceTicketItemId === item.sourceTicketItemId
            : i === index;
          // Re-check productId at apply time, not just at fetch time — the user may have picked
          // a product manually while this request was in flight.
          if (!isTarget || row.productId != null) return row;
          // The match was computed from the row as it looked when the search STARTED. Applying it
          // to the row as it looks NOW would overwrite whatever the user typed in between: this
          // effect holds the window open for seconds across a many-line deal, and a user fixing
          // row 4's model/size by hand clears nothing the guard above can see (productId is
          // already null on exactly the rows this effect targets). So re-confirm the row still
          // describes the product that was searched for, and drop the match if it has moved on.
          const describesSameProduct = normalizeForCatalogMatch(row.model) === normalizeForCatalogMatch(item.model)
            && normalizeForCatalogMatch(row.size) === normalizeForCatalogMatch(item.size);
          if (!describesSameProduct) return row;
          return { ...deriveItemFromCatalogProduct(row, match), catalogAutoApplied: true };
        }));
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const [error, setError] = useState('');
  // Informational (non-error) banner — currently only used by the create-mode
  // duplicate-draft guard below, to tell the user a retry is reusing the
  // draft it already created rather than making a second one.
  const [info, setInfo] = useState('');
  // Per-row identity errors (keyed by item index), separate from the
  // form-level `error` banner above — unlike the other validation rules,
  // this one is attached to the specific line that is missing an identity so
  // the user knows which row to fix, not just that "a" row is wrong.
  const [itemErrors, setItemErrors] = useState({});
  const [saving, setSaving] = useState(false);
  // Fix 1 (review-remediation plan): once createFn has succeeded once, its
  // resulting id is held here so a retry (create succeeded, then submitFn
  // failed — or the user switches from "submit" to "save draft" after that
  // failure) reuses the same draft instead of calling createFn again and
  // orphaning a duplicate. Always null in edit mode (there is no create step).
  const [createdId, setCreatedId] = useState(null);
  const [catalogQuery, setCatalogQuery] = useState({});
  const [catalogResults, setCatalogResults] = useState({});

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

  function updateItem(index, field, value) {
    setItems((cur) => cur.map((item, i) => {
      if (i !== index) return item;
      const next = { ...item, [field]: value };
      if (['brand', 'model', 'productDescription', 'color', 'texture', 'size', 'factory'].includes(field)) {
        next.productId = null;
        next.catalogProductCode = '';
        next.catalogBasePrice = null;
        next.catalogCurrency = '';
        // A hand-edit invalidates a fuzzy-matched link exactly like a real catalog pick — it no
        // longer describes an unconfirmed auto-match, it describes whatever the user just typed.
        next.catalogAutoApplied = false;
      }
      return next;
    }));
    if (['sourceTicketItemId', 'productId', 'model', 'productDescription'].includes(field)) {
      setItemErrors((cur) => {
        if (!(index in cur)) return cur;
        const next = { ...cur };
        delete next[index];
        return next;
      });
    }
  }

  // Sets requestedUnitBasis (canonical, what the backend/costing engine consumes) and
  // requestedUnit (the Thai display label, still what the factory email body shows) together
  // from one select, so the two can never drift apart the way a free-typed requestedUnit could.
  function updateUnitBasis(index, code) {
    setItems((cur) => cur.map((item, i) => (
      i === index ? { ...item, requestedUnitBasis: code, requestedUnit: unitBasisLabel(code) } : item
    )));
  }

  function addItem() {
    // A row added later via "เพิ่มรายการ" gets the same deal-derived deliveryLocation seed as
    // the rows this modal opened with (create mode only — dealDeliveryLocationSeed is already ''
    // in edit/revision, see its own definition above).
    setItems((cur) => [...cur, emptyItemFromTicketItem(null, dealDeliveryLocationSeed)]);
  }

  function removeItem(index) {
    setItems((cur) => cur.filter((_, i) => i !== index));
  }

  function searchCatalog(index, value) {
    setCatalogQuery((cur) => ({ ...cur, [index]: value }));
    debouncedCatalogSearch(value, async (query) => {
      if (!query.trim()) {
        setCatalogResults((cur) => ({ ...cur, [index]: [] }));
        return;
      }
      try {
        const res = await api.catalog.prices(query, undefined, 20);
        setCatalogResults((cur) => ({ ...cur, [index]: res.items ?? [] }));
      } catch {
        setCatalogResults((cur) => ({ ...cur, [index]: [] }));
      }
    });
  }

  function applyCatalogItem(index, product) {
    setItems((cur) => cur.map((item, i) => (
      // A real user pick always wins over — and clears — any unconfirmed auto-match.
      i === index ? { ...deriveItemFromCatalogProduct(item, product), catalogAutoApplied: false } : item
    )));
    setCatalogQuery((cur) => ({
      ...cur,
      [index]: [product.productCode, product.collection, product.productName].filter(Boolean).join(' · '),
    }));
    setCatalogResults((cur) => ({ ...cur, [index]: [] }));
    setItemErrors((cur) => {
      if (!(index in cur)) return cur;
      const next = { ...cur };
      delete next[index];
      return next;
    });
  }

  function validate() {
    if (!recipientLabel.trim()) return 'กรุณาระบุผู้รับคำขอราคา';
    if (items.length === 0) return 'ต้องมีรายการสินค้าอย่างน้อย 1 รายการ';
    for (const item of items) {
      if (!Number(item.requestedQty) || Number(item.requestedQty) <= 0) return 'กรุณากรอกจำนวนของทุกรายการให้ถูกต้อง';
      if (!item.requestedUnit?.trim()) return 'กรุณากรอกหน่วยของทุกรายการ';
    }
    // Mirrors CustomerChangeRevisionRequest.revisionReason's @NotBlank — revision mode only.
    if (isRevision && !revisionReason.trim()) return 'กรุณาระบุเหตุผลของการแก้ไข';
    return '';
  }

  // Separate from validate() above: this rule reports per-row, not as a
  // single form-level message (see itemErrors' declaration). Returns whether
  // every row is valid, and updates itemErrors as a side effect.
  function validateItemIdentities() {
    const next = {};
    items.forEach((item, index) => {
      if (!itemIdentityValid(item)) {
        next[index] = 'ต้องระบุสินค้าที่ต้องการเสนอราคา (เลือกจากรายการในดีล หรือระบุรุ่น/รายละเอียด)';
      }
    });
    setItemErrors(next);
    return Object.keys(next).length === 0;
  }

  function buildPayload({ includeClientRequestId = false } = {}) {
    const payload = {
      recipientType,
      recipientLabel: recipientLabel.trim(),
      requiredDate: requiredDate || null,
      customerTargetPrice: customerTargetPrice !== '' ? Number(customerTargetPrice) : null,
      targetCurrency: targetCurrency.trim() || null,
      note: note.trim() || null,
      items: items.map((item) => ({
        sourceTicketItemId: item.sourceTicketItemId ?? null,
        productId: item.productId ?? null,
        brand: item.brand?.trim() || null,
        model: item.model?.trim() || null,
        productDescription: item.productDescription?.trim() || null,
        color: item.color?.trim() || null,
        texture: item.texture?.trim() || null,
        size: item.size?.trim() || null,
        factory: item.factory?.trim() || null,
        requestedQty: Number(item.requestedQty),
        requestedUnit: item.requestedUnit.trim(),
        requestedUnitBasis: item.requestedUnitBasis,
        quantityType: item.quantityType,
        targetDeliveryDate: item.targetDeliveryDate || null,
        deliveryLocation: item.deliveryLocation?.trim() || null,
        specialRequirement: item.specialRequirement?.trim() || null,
      })),
    };
    if (includeClientRequestId) payload.clientRequestId = clientRequestId;
    return payload;
  }

  // Review remediation (COMMIT 5, P1 finding 3): CustomerChangeRevisionRequest is buildPayload's
  // shape plus revisionReason — and, unlike create/edit, always carries a clientRequestId (the
  // backend's own idempotency guard against a duplicate submit of the same revision). It also
  // carries recipientContactId explicitly (always null here, same as buildPayload's create/edit
  // callers — this modal has never wired up a real contact picker, see this file's own class
  // Javadoc-equivalent comment above).
  function buildRevisionPayload() {
    return {
      ...buildPayload({ includeClientRequestId: true }),
      revisionReason: revisionReason.trim(),
      recipientContactId: null,
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
    if (!validateItemIdentities()) return;
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
    if (!validateItemIdentities()) return;
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
    if (!validateItemIdentities()) return;
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
    if (!validateItemIdentities()) return;
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

        <div>
          <div className="mb-2 flex items-center justify-between">
            <span className="text-sm font-bold text-text-secondary">รายการสินค้า *</span>
            <Button type="button" variant="secondary" style={{ fontSize: 12 }} onClick={addItem}>
              <Icon name="plus" size={13} /> เพิ่มรายการ
            </Button>
          </div>
          <div className="flex flex-col gap-2">
            {items.map((item, index) => (
              <div key={index} className="flex flex-col gap-2 rounded-lg border border-border bg-surface-subtle p-3">
                <div className="flex items-center justify-between">
                  <span className="text-2xs font-bold text-text-muted">
                    รายการที่ {index + 1}
                    {item.sourceTicketItemId ? ` · อ้างอิงจากรายการสินค้าในดีล #${item.sourceTicketItemId}` : ''}
                  </span>
                  {items.length > 1 ? (
                    <Button type="button" variant="icon" aria-label={`ลบรายการที่ ${index + 1}`} onClick={() => removeItem(index)}>
                      <Icon name="close" size={13} />
                    </Button>
                  ) : null}
                </div>
                {itemErrors[index] ? (
                  <p role="alert" className="text-2xs font-bold text-danger-dark">{itemErrors[index]}</p>
                ) : null}
                <div className="relative">
                  <label className="flex flex-col gap-1 text-xs">
                    ค้นหา Catalog
                    <input
                      value={catalogQuery[index] ?? item.catalogProductCode ?? ''}
                      onChange={(e) => searchCatalog(index, e.target.value)}
                      placeholder="รหัสสินค้า / Collection / โรงงาน"
                    />
                  </label>
                  {(catalogResults[index] ?? []).length > 0 ? (
                    <div className="absolute left-0 right-0 top-full z-50 max-h-56 overflow-y-auto rounded-md border border-border bg-surface shadow-lg">
                      {(catalogResults[index] ?? []).map((product) => (
                        <button
                          key={product.priceId}
                          type="button"
                          className="block w-full border-b border-border-subtle px-3 py-2 text-left text-xs hover:bg-surface-subtle"
                          onMouseDown={(event) => {
                            event.preventDefault();
                            applyCatalogItem(index, product);
                          }}
                        >
                          <strong>{product.factoryName}</strong>
                          {' · '}
                          {[product.productCode, product.collection, product.productName].filter(Boolean).join(' · ') || '-'}
                          <span className="ml-2 text-info">
                            {product.price != null ? `${Number(product.price).toLocaleString('th-TH')} ${product.currency ?? ''}` : ''}
                          </span>
                        </button>
                      ))}
                    </div>
                  ) : null}
                  {item.productId ? (
                    <p className="mt-1 text-2xs font-bold text-info">
                      Catalog #{item.productId}{item.catalogBasePrice != null ? ` · ${Number(item.catalogBasePrice).toLocaleString('th-TH')} ${item.catalogCurrency}` : ''}
                    </p>
                  ) : null}
                  {item.catalogAutoApplied ? (
                    <p role="status" className="mt-1 flex items-center gap-1 text-2xs font-bold text-warning-dark">
                      <Icon name="triangleAlert" size={12} />
                      จับคู่สินค้าจาก Catalog ให้อัตโนมัติ — โปรดตรวจสอบก่อนใช้งาน (ไม่ใช่การเลือกที่ยืนยันแล้ว)
                    </p>
                  ) : null}
                </div>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                  <label className="col-span-2 flex flex-col gap-1 text-xs sm:col-span-1">
                    ยี่ห้อ
                    <input value={item.brand} onChange={(e) => updateItem(index, 'brand', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    รุ่น
                    <input value={item.model} onChange={(e) => updateItem(index, 'model', e.target.value)} />
                  </label>
                  <label className="col-span-2 flex flex-col gap-1 text-xs sm:col-span-3">
                    รายละเอียดสินค้า
                    <input value={item.productDescription} onChange={(e) => updateItem(index, 'productDescription', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    สี
                    <input value={item.color} onChange={(e) => updateItem(index, 'color', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    เนื้อผิว
                    <input value={item.texture} onChange={(e) => updateItem(index, 'texture', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    ขนาด
                    <input value={item.size} onChange={(e) => updateItem(index, 'size', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    โรงงาน
                    <input value={item.factory} onChange={(e) => updateItem(index, 'factory', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    จำนวน *
                    <input type="number" min="0.0001" step="0.0001" value={item.requestedQty} onChange={(e) => updateItem(index, 'requestedQty', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    หน่วย *
                    <select value={item.requestedUnitBasis ?? ''} onChange={(e) => updateUnitBasis(index, e.target.value)}>
                      <option value="">-- เลือกหน่วย --</option>
                      {UNIT_BASIS_OPTIONS.map((option) => (
                        <option key={option.code} value={option.code}>{option.label}</option>
                      ))}
                    </select>
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    ลักษณะจำนวน *
                    <select value={item.quantityType} onChange={(e) => updateItem(index, 'quantityType', e.target.value)}>
                      {QUANTITY_TYPE_OPTIONS.map((option) => (
                        <option key={option.code} value={option.code}>{option.label}</option>
                      ))}
                    </select>
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    วันที่ต้องการส่งมอบ
                    <input type="date" value={item.targetDeliveryDate} onChange={(e) => updateItem(index, 'targetDeliveryDate', e.target.value)} />
                  </label>
                  <label className="col-span-2 flex flex-col gap-1 text-xs sm:col-span-1">
                    สถานที่ส่งมอบ
                    <input value={item.deliveryLocation} onChange={(e) => updateItem(index, 'deliveryLocation', e.target.value)} />
                  </label>
                  <label className="col-span-2 flex flex-col gap-1 text-xs sm:col-span-3">
                    ข้อกำหนดพิเศษ
                    <input value={item.specialRequirement} onChange={(e) => updateItem(index, 'specialRequirement', e.target.value)} />
                  </label>
                </div>
              </div>
            ))}
          </div>
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
