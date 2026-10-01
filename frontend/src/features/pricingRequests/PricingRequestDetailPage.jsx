import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { hasPermission } from '../../app/permissions.js';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Icon } from '../../components/common/Icon.jsx';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { InfoTip } from '../../components/common/InfoTip.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { Skeleton, SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  discountApprovalStatusLabel,
  factoryQuoteStatusLabel,
  formatMoney,
  formatThaiDate,
  pricingDecisionStatusLabel,
  pricingRequestStatusLabel,
  quotationStatusLabel,
} from '../../utils/format.js';
import { downloadBlob } from '../../utils/download.js';
import { toUserErrorDescription, toUserErrorMessage } from '../../utils/userMessages.js';
import {
  canActOnPricingDecision,
  canConfirmOrder,
  canCreateCommercialOnlyRevision,
  canCreateCustomerQuotation,
  canCreateDepositNoticeFromQuotation,
  canDecideDiscountApproval,
  canManageCustomerQuotation,
  canRecordCustomerQuotationOutcome,
  canSeePricingDecisionSalesView,
  canSeeRawPricingDecision,
  canPickupPricingRequest,
  canStartCeoReview,
  canViewCustomerQuotation,
  isCustomerQuotationEditable,
  isCustomerQuotationDiscountEditable,
  canTransition,
  pricingRequestRecipientLabel,
  unitBasisLabel,
} from './pricingRequestMeta.js';
import { PricingRequestCreateModal } from './PricingRequestCreateModal.jsx';
import { FactoryContactDialog } from './FactoryContactDialog.jsx';
import { LeadTimeChangeBanner } from './LeadTimeChangeBanner.jsx';
import { LeadTimeChangeDialog } from './LeadTimeChangeDialog.jsx';
import {
  contactedByName,
  formatShortThaiDay,
  hasLockedTerms,
  isFactoryContacted,
  isImportLine,
  leadTimeRangeText,
  leadTimeWithUnit,
  lockedTermsList,
  splitLeadTimeChanges,
} from './factoryContactMeta.js';
import { useUnitBasisCatalog } from './unitBasisCatalog.js';
import { buttonVariants } from '../../components/common/Button.jsx';
import { cn } from '../../utils/cn.js';
import { piecesPerSqmFromSqmPerPiece, PRICE_MODE_OPTIONS } from '../quotations/quotationMeta.js';
import { SearchableCombobox } from '../../components/common/SearchableCombobox.jsx';
// B6 (GLA-135): reuses the catalog page's own add-factory dialog rather than a second, drifting
// copy of the same five fields + validation — see ImportFactoryPicker's own comment below.
import { FactoryFormModal } from '../catalog/PriceImportPage.jsx';

function isImport(user) {
  return user?.role === 'import';
}

function isSales(user) {
  return user?.role === 'sales';
}

function canSeeRaw(user) {
  return user?.role === 'import' || user?.role === 'ceo';
}

// Mirrors PricingRequestItemDto.resolvedFactory(): the catalog snapshot first, then Sales's own
// free text. `null` here is exactly what makes FactoryQuoteService.groupByFactory refuse to build
// the factory-email drafts, so this one predicate decides both the warning and the input below.
function itemFactoryName(item) {
  return item?.resolvedFactoryName?.trim() || item?.factory?.trim() || null;
}

// Mirrors PricingRequestItemDto.displayName() — same fields, same precedence as the row heading
// this page renders, so the server's "รายการที่ N (ชื่อสินค้า)" names something visible on screen.
function itemDisplayName(item) {
  const name = [item?.catalogBrand ?? item?.brand, item?.catalogModel ?? item?.model]
    .map((part) => part?.trim())
    .filter(Boolean)
    .join(' ');
  return name || item?.productDescription?.trim() || '-';
}

/**
 * Statuses in which Import may name the factory on a blank line. Mirrors
 * PricingRequestService.FACTORY_ROUTING_STATUSES, which is itself
 * FactoryQuoteService.DRAFT_STATUSES: the window in which สร้างร่างอีเมล can still run is exactly
 * the window in which its missing input can still be supplied.
 */
const FACTORY_ROUTING_STATUSES = ['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE'];

// ── V185 (direct-deal-form parity) + owner clarification 2026-09-18, FINAL ruling (reversing an
// earlier "relabel to ยี่ห้อ" instruction this comment used to describe): the label on THIS page
// stays โรงงาน, for both the sales-entered value below and Import's own SetItemFactoryRequest
// control — read-only display of the new sales-entered tile fields on each item card. `—` for
// anything a legacy (pre-V185) item never carried. ─────────────────────────────────────────────
function formatOrDash(value, suffix = '') {
  return value == null || value === '' ? '—' : `${value}${suffix}`;
}

/** แผ่น/ตร.ม. — the RECIPROCAL of the stored sqm_per_piece, same convention the direct-deal
 * quotation editor displays (QuotationItemRow's own piecesPerSqmDisplay). */
function formatPiecesPerSqm(item) {
  if (item?.sqmPerPiece == null) return '—';
  const reciprocal = piecesPerSqmFromSqmPerPiece(item.sqmPerPiece);
  return reciprocal == null ? '—' : String(reciprocal);
}

function formatLeadTime(item) {
  if (item?.leadTimeMinDays == null && item?.leadTimeMaxDays == null) return '—';
  const min = item.leadTimeMinDays ?? '?';
  const max = item.leadTimeMaxDays ?? '?';
  return `${min}–${max} วัน`;
}

function formatQuantityAsEntered(item) {
  if (item?.quantityMode === 'PIECES') return formatOrDash(item.piecesInput, ' แผ่น');
  if (item?.quantityMode === 'AREA') return formatOrDash(item.areaSqm, ' ตร.ม.');
  return '—';
}

function formatWastage(item) {
  if (!item?.wastageMode || item.wastageMode === 'NONE') return 'ไม่มี';
  if (item.wastageMode === 'PERCENT') return formatOrDash(item.wastageValue, '%');
  if (item.wastageMode === 'PIECES') return formatOrDash(item.wastageValue, ' แผ่น');
  return '—';
}

function PricingRequestDetailSkeleton() {
  return (
    <div className="grid w-[min(760px,100%)] gap-3" aria-hidden="true">
      <div className="grid gap-2 rounded-md border border-border bg-surface p-4">
        <Skeleton height={24} width="42%" />
        <Skeleton height={14} width="64%" />
        <div className="grid gap-2 pt-2 sm:grid-cols-2">
          <Skeleton height={16} />
          <Skeleton height={16} />
          <Skeleton height={16} />
          <Skeleton height={16} />
        </div>
      </div>
      <div className="grid gap-2 rounded-md border border-border bg-surface p-4">
        <Skeleton height={18} width="34%" />
        <Skeleton height={58} />
        <Skeleton height={58} />
      </div>
      <div className="grid gap-2 rounded-md border border-border bg-surface p-4">
        <Skeleton height={18} width="28%" />
        <SkeletonText lines={2} />
      </div>
    </div>
  );
}

function apiStatus(error) {
  return typeof error?.status === 'number' ? error.status : null;
}

/**
 * Seeds the "record the factory's answer" draft. Owner ruling 2026-08-11: Import types the PRICE
 * and nothing else — quantity, unit and currency all come from what Sales already requested, so
 * they are carried in this state (the backend still requires them) but never rendered as inputs.
 *
 * `requestItem` is the sales-side PricingRequestItem this quote line was generated from, looked up
 * by pricingRequestItemId. The currency fallback chain matters: `catalogCurrency` is the currency
 * of the catalog row Sales picked (EUR for the European factories), and it must win over the old
 * hardcoded 'THB'. That default was a real defect — CDE trades in EUR, so a price typed as 46 was
 * being stored as ฿46 instead of €46, a 38.5x error straight into the landed cost.
 */
function defaultResponseItems(quote, requestItemById = new Map()) {
  return (quote?.items ?? []).map((item) => {
    const requestItem = requestItemById.get(item.pricingRequestItemId) ?? {};
    // unitBasis (what LandedCostCalculator does math on: PER_SQM/PER_PIECE/...) and quotedUnit
    // (the display unit a human reads: ตร.ม., PCS, ...) are DIFFERENT fields that used to share
    // this one seed variable, so quotedUnit was overwriting a real unit — FactoryQuoteRepository.
    // insertDraftItems already seeds it from the request's own requested_unit — with the basis
    // code instead. Measured in UAT: 30 of 34 sales.factory_quote_item rows hold a basis in
    // quoted_unit. quotedUnit falls back through the item's own quoted unit, then what Sales
    // requested in free text, and only then a basis-derived display label — never the basis code.
    //
    // unitBasis is read straight off the item with NO fallback, because it cannot be absent:
    // sales.factory_quote_item.unit_basis is NOT NULL (V61) and CHECK-constrained to the four
    // canonical codes (V63), and every quote here comes from the API. The chain that used to sit
    // here — `?? item.quotedUnit ?? requestItem.requestedUnitBasis ?? 'PER_PIECE'` — could never
    // fire for that reason: it read as a live safety net while being dead, and its second term
    // would have written a display string into a basis field. Leaving the `'PER_PIECE'` default
    // off is deliberate — a silently wrong basis is what PR #789 fixed on the seeding side, and
    // an absent one should fail loudly at the backend rather than quietly price per piece.
    // CR-1 (R1): currency and price unit are fixed by Sales on the line and locked for import — the
    // line's own requested terms win over whatever the draft carries, so what is sent back always
    // matches (FactoryQuoteService#receive 409s anything else). A legacy line has neither and keeps
    // the seeding below.
    const unitBasis = requestItem.requestedPriceUnitBasis ?? item.unitBasis;
    const quotedUnit = requestItem.requestedPriceUnitBasis
      ? unitBasisLabel(unitBasis)
      : item.quotedUnit ?? requestItem.requestedUnit ?? unitBasisLabel(unitBasis);
    return {
    pricingRequestItemId: item.pricingRequestItemId,
    supplierProductCode: item.supplierProductCode ?? '',
    supplierProductDescription: item.supplierProductDescription ?? '',
    quotedQuantity: item.quotedQuantity ?? requestItem.requestedQty ?? 1,
    quotedUnit,
    unitBasis,
    rawUnitPrice: item.rawUnitPrice ?? '',
    currency: requestItem.requestedCurrency ?? item.currency ?? quote.defaultCurrency ?? requestItem.catalogCurrency ?? 'THB',
    minimumOrderQuantity: item.minimumOrderQuantity ?? '',
    // Falls back to the request item's own ตร.ม./แผ่น (required on every item since V185) so import
    // is not asked for a figure the system already holds; a value saved on the quote still wins.
    sqmPerUnit: item.sqmPerUnit ?? requestItem.sqmPerPiece ?? '',
    piecesPerBox: item.piecesPerBox ?? '',
    leadTimeText: item.leadTimeText ?? '',
    availabilityNote: item.availabilityNote ?? '',
    lineNote: item.lineNote ?? '',
    };
  });
}

// Mandatory reason the backend demands whenever an item's sellingPriceOverride is set or cleared
// (PricingDecisionService#applyItemUpdates) — the CEO-typed ราคาตั้ง has no separate note field.
const CEO_LIST_PRICE_NOTE = 'CEO กรอกราคาตั้งเอง';

// Price boxes are plain text inputs (no number spinner / scroll-to-change). Keep digits and a
// single decimal point only, so Number() on submit can never see junk; commas are dropped.
function sanitizeDecimal(raw) {
  const [whole, ...rest] = String(raw).replace(/[^\d.]/g, '').split('.');
  return rest.length ? `${whole}.${rest.join('')}` : whole;
}

function cleanNumber(value) {
  if (value === '' || value == null) return null;
  return Number(value);
}

function generateClientRequestId() {
  return crypto.randomUUID?.()
    ?? '00000000-0000-4000-8000-' + String(Date.now()).slice(-12).padStart(12, '0');
}

function formatCurrency(value, currency = 'THB') {
  if (value == null || value === '') return '-';
  return currency === 'THB' ? formatMoney(value) : `${Number(value).toLocaleString('en-US')} ${currency}`;
}

// ── Phase 2, CEO pricing method (owner rulings 2026-09-18/19, V187) ───────────────────────────
// Mirrors PricingDecisionService#isNewFormEligible exactly: the mode picker is offered only when
// EVERY item of the decision came from a Phase 1 (V185) new-form pricing-request item
// (requestedUnitBasis PER_PIECE + a non-null sqmPerPiece). A legacy decision keeps today's
// margin/"ปรับราคาเอง" UI, unchanged, below.
function isNewFormEligibleDecision(decision) {
  return Boolean(decision?.items?.length) && decision.items.every(
    (item) => item.requestedUnitBasis === 'PER_PIECE' && item.sqmPerPiece != null);
}

function round2(n) {
  return Math.round((Number(n) + Number.EPSILON) * 100) / 100;
}

// Review finding #4 (2026-09-19): the `max` attribute on each price-mode input mirrors
// PricingDecisionService's own MAX_PRICE_14_2/MAX_PRICE_12_2 (list/direct NUMERIC(14,2),
// special NUMERIC(12,2)) — a browser-level nudge only, never authoritative; the server still
// rejects an oversized value with 400, never a raw overflow 500.
const PRICE_INPUT_MAX_14_2 = '999999999999.99';
const PRICE_INPUT_MAX_12_2 = '9999999999.99';

/**
 * A CLIENT-SIDE PREVIEW ONLY — the server (PricingDecisionService#computeNetUnitPrice, reusing
 * WastageCalculator) is authoritative and recomputes on every save; this never feeds a stored
 * value. NET's `round2(list × (1 − pct/100))` has no rounding-order subtlety, so it is safe to
 * mirror (same reasoning the direct-deal quotation editor's own client math uses). SPECIAL_SQM's
 * net-per-piece derivation (`WastageCalculator#netPerPieceFromSpecialSqm`) is deliberately NOT
 * mirrored here — its two-step rounding order IS the algorithm (see that method's own Javadoc) —
 * so this returns `null` for it, same as `quotationMeta.js` already does for the identical
 * computation on the direct-deal quotation editor (see that file's own comment on
 * `piecesPerSqmFromSqmPerPiece`: "SPECIAL_SQM's net-per-piece figure is still always the
 * SERVER's"). The UI shows "คำนวณเมื่อบันทึก" for that mode instead of a number.
 */
function previewCeoNetUnitPrice(priceMode, { listUnitPrice, discountPct, directNetPrice } = {}) {
  if (priceMode === 'NET') {
    if (listUnitPrice === '' || listUnitPrice == null) return null;
    const discount = discountPct === '' || discountPct == null ? 0 : Number(discountPct);
    return round2(Number(listUnitPrice) * (1 - discount / 100));
  }
  if (priceMode === 'DIRECT_NET') {
    if (directNetPrice === '' || directNetPrice == null) return null;
    return round2(Number(directNetPrice));
  }
  return null;
}

function cleanResponsePayload(draft) {
  return {
    supplierQuoteRef: draft.supplierQuoteRef || null,
    // Falls back to the first line's currency (itself sourced from the catalog row Sales picked)
    // rather than a hardcoded 'THB' — see defaultResponseItems for why that default was a defect.
    defaultCurrency: draft.defaultCurrency || draft.items?.[0]?.currency || 'THB',
    paymentTerms: draft.paymentTerms || null,
    leadTimeText: draft.leadTimeText || null,
    revisionReason: draft.revisionReason || null,
    negotiationNote: draft.negotiationNote || null,
    items: draft.items.map((item) => ({
      ...item,
      rawUnitPrice: cleanNumber(item.rawUnitPrice),
      quotedQuantity: cleanNumber(item.quotedQuantity),
      minimumOrderQuantity: cleanNumber(item.minimumOrderQuantity),
      sqmPerUnit: cleanNumber(item.sqmPerUnit),
      piecesPerBox: cleanNumber(item.piecesPerBox),
    })),
  };
}

/**
 * Groups a pricing request's factory quotes by factory (owner-supplied mockup, 2026-08-16 —
 * "grouped by factory" is an explicit requirement). `factoryQuotes` holds full revision history —
 * FactoryQuoteService.receive() supersedes-and-creates-a-new-row on every response after the
 * first, and PricingRequestDetailPage has always rendered that history unfiltered — so a factory
 * asked twice can own several rows here, only one of them `current`.
 *
 * Keyed by `factoryId` when the quote resolved to a canonical factory-master row, falling back to
 * `factoryName` — `FactoryQuoteDto.factoryId` is nullable (a quote can exist against a name with
 * no catalog/config row behind it). Group order follows first-appearance order in `factoryQuotes`,
 * which the API/mock already return in a stable (creation) order, so the section does not
 * reshuffle itself between renders.
 *
 * `current` is the live quote — what the item-editing grid and ยืนยันราคาเสนอ act on. `history` is
 * every OTHER quote for that same factory, oldest first: never re-rendered as a second editing
 * grid (the mockup shows one row per item, not one row per revision), but never dropped either —
 * surfaced as the same compact "ประวัติ: ..." line this page already uses for pricing-decision and
 * customer-quotation revisions, so the audit trail stays reachable, just de-emphasised.
 */
function groupFactoryQuotesByFactory(factoryQuotes) {
  const order = [];
  const byKey = new Map();
  for (const quote of factoryQuotes) {
    const key = quote.factoryId != null ? `id:${quote.factoryId}` : `name:${quote.factoryName}`;
    let group = byKey.get(key);
    if (!group) {
      group = { key, factoryName: quote.factoryName, quotes: [] };
      byKey.set(key, group);
      order.push(group);
    }
    group.quotes.push(quote);
  }
  return order.map((group) => {
    const sorted = [...group.quotes].sort((a, b) => a.revisionNo - b.revisionNo);
    const current = sorted.find((q) => q.current) ?? sorted[sorted.length - 1];
    return {
      ...group,
      quotes: sorted,
      current,
      history: sorted.filter((q) => q.id !== current.id),
    };
  });
}

// One CSS-grid template shared by the column-header row and every item row beneath it (DESIGN.md
// §13: "CSS-grid columns per table type keep alignment"), so ยี่ห้อ/รุ่น · สี/เนื้อผิว · จำนวน ·
// ราคาที่เสนอ · ราคาที่อนุมัติ line up down the whole grouped list regardless of which factory a row
// belongs to. Mobile-first, matching this file's own existing per-item response grid below
// (`md:grid-cols-[1fr_auto_auto_auto]`): no columns at all below `md` (768px, this file's
// established switch point), full 5-track grid from `md` up. `min-w-[720px]` gives the grid a
// floor at tablet width — Panel's `flush` variant is `overflow-x-auto`, so a tight tablet card
// scrolls this table horizontally inside itself rather than crushing the columns unreadable or
// silently losing data off the edge (see Layout.jsx's own Panel comment on why that clip is
// scroll, not hidden).
// No fixed min-width: the section is a flush Panel (overflow-x-auto), so any min-w here scrolled the WHOLE
// card — header and chip included — at ~1100px. Five flexible tracks (variant sits under the product
// name) always fit; below md each line stacks as label/value instead.
const FACTORY_ITEM_GRID = 'md:grid-cols-[minmax(0,1.6fr)_5.5rem_minmax(0,11rem)_minmax(0,1fr)_5.5rem]';

/**
 * Import's factory-routing control for ONE blank line (rendered only when `canSetItemFactory &&
 * !factoryName` — see the item-card call site). B6 (GLA-135, owner ruling): a typed factory name
 * that did not exactly match a `price_catalog.factories` master row used to save with
 * `resolved_factory_id` NULL, dead-ending the downstream factory-email + CEO landed-cost lookups
 * that key on it — the reported "brand-new factory → factory config error". Sales/import now pick
 * from EXISTING factories only, via {@link SearchableCombobox} over the real master list
 * (`factories`, from `GET /api/price-import/factories`) rather than a free-text `<input>`.
 *
 * Import (the only role that reaches this control) may still add a genuinely new factory
 * IN-FLOW — the owner ruling is that import/CEO are the ones who may add one, not that this page
 * can never need one it doesn't already have — via the "เพิ่มโรงงานใหม่" affordance, which reuses
 * {@link FactoryFormModal} (the catalog PriceImportPage's own add/edit form: name, country,
 * countryOther, currency, unit, email) rather than a second, drifting copy of the same fields and
 * validation. `Modal` is `position: fixed` + focus-trapped (see Modal.jsx), so this dialog is
 * fully reachable regardless of the item card's own layout — never a clipped absolute dropdown.
 */
function ImportFactoryPicker({
  itemId, factories, countries, loading, loadFailed, value, onChangeValue, onSave, saving, onFactoryAdded,
}) {
  const [addOpen, setAddOpen] = useState(false);
  const [emptyWarned, setEmptyWarned] = useState(false);
  // QA BUG-20: hard lock against a double-submit. `saving` is the mutation's isPending, which only
  // flips on the NEXT render — a fast second click (or Enter) can fire between the first mutate()
  // and that re-render, and setItemFactory answers the second call with a confusing 409 ("ระบุ
  // โรงงานไว้แล้ว") even though the save succeeded. This ref closes that window synchronously; it is
  // released once `saving` returns to false (the mutation settled, success or error).
  const inFlight = useRef(false);
  useEffect(() => { if (!saving) inFlight.current = false; }, [saving]);
  const options = useMemo(
    () => factories.map((f) => ({ code: String(f.factoryId), nameTh: f.name })),
    [factories],
  );

  function handleSubmit(event) {
    if (saving || inFlight.current) return; // already submitting — swallow the repeat click/Enter
    if (!value) { setEmptyWarned(true); return; } // QA BUG-20: an empty save now says why
    setEmptyWarned(false);
    inFlight.current = true;
    onSave(event);
  }

  return (
    <>
      <SafeForm className="mt-2 flex flex-wrap items-end gap-2" onSubmit={handleSubmit}>
        <div className="w-[min(320px,100%)]">
          <FormField label="ระบุโรงงาน" htmlFor={`pcr-item-factory-${itemId}`}>
            <SearchableCombobox
              id={`pcr-item-factory-${itemId}`}
              label="โรงงาน"
              value={value}
              options={options}
              loading={loading}
              disabled={saving}
              placeholder={loading ? 'กำลังโหลดรายชื่อโรงงาน…' : 'พิมพ์ค้นหาโรงงาน…'}
              onChange={(code) => { setEmptyWarned(false); onChangeValue(code); }}
            />
          </FormField>
          {/* Distinguishes "the fetch failed" from "the roster really is empty" — SearchableCombobox's
              own empty state (ไม่พบข้อมูล) reads as the latter, which would be a misleading dead end
              for the one control that unblocks the whole request. */}
          {loadFailed ? (
            <p className="m-0 mt-1 text-xs font-bold text-danger">โหลดรายชื่อโรงงานไม่สำเร็จ — ลองรีเฟรชหน้านี้</p>
          ) : null}
          {emptyWarned ? (
            <p className="m-0 mt-1 text-xs font-bold text-warning-dark">เลือกโรงงานจากรายการก่อนกดบันทึก</p>
          ) : null}
        </div>
        {/* QA BUG-20: NOT disabled on an empty value — a disabled button is exactly the "no
            feedback" the report flagged (nothing happens, no reason given). It stays clickable so
            handleSubmit can say เลือกโรงงานก่อน; `saving` still blocks the in-flight repeat. */}
        <Button type="submit" variant="secondary" disabled={saving}>
          {saving ? 'กำลังบันทึก…' : 'บันทึกโรงงาน'}
        </Button>
        <Button type="button" variant="text" onClick={() => setAddOpen(true)} disabled={saving}>
          <Icon name="plus" size={13} />
          เพิ่มโรงงานใหม่
        </Button>
      </SafeForm>
      {addOpen ? (
        <FactoryFormModal
          factory={null}
          countries={countries}
          onClose={() => setAddOpen(false)}
          onSaved={(saved) => {
            onFactoryAdded(saved);
            setAddOpen(false);
          }}
        />
      ) : null}
    </>
  );
}

/**
 * The factory-quote mail composer, in a modal behind each factory card's "สร้างเมล" button.
 *
 * CR-1 (GLA-167, rulings R3/R8): the backend never sends this mail — a human copies it into their
 * own mail client. So the modal has exactly TWO actions beyond closing: บันทึกร่าง (save the draft)
 * and คัดลอกเมล (copy it). There is no "ส่งแล้ว" here any more: that step is a separate, FINAL
 * "ติดต่อโรงงานแล้ว" button on the card (FactoryContactDialog), which also records the date. Closing
 * this modal changes no status. ถึง is pre-filled from the factory master's email (R8).
 *
 * `canEdit` is decided by the page (contact role AND the quote still DRAFT AND the request inside
 * FactoryQuoteService.DRAFT_STATUSES — updateDraft 409s outside it); otherwise the modal is a
 * read-only view that can still copy. `attachments` are the request attachments ticked to travel
 * with the mail (read-only here — the tick lives in the request's own attachment list).
 */
function FactoryEmailDraftModal({ quote, draft, onChangeDraft, onClose, onSave, savePending, onCopy, canEdit, attachments = [] }) {
  return (
    <Modal
      title="สร้างเมล"
      subtitle={quote.factoryName}
      onClose={onClose}
      testId="factory-email-draft-modal"
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ปิด</Button>
          {canEdit ? (
            <Button type="button" variant="secondary" disabled={savePending} onClick={onSave}>
              บันทึกร่าง
            </Button>
          ) : null}
          <Button type="button" variant="primary" data-testid="pcr-copy-factory-email" onClick={() => onCopy(draft)}>
            <Icon name="clipboard" size={14} />
            คัดลอกเมล
          </Button>
        </>
      }
    >
      <div className="grid gap-3">
        <p className="m-0 rounded-md border border-border-subtle bg-surface-subtle p-3 text-xs text-text-secondary">
          {canEdit
            ? 'ระบบไม่ได้ส่งเมลให้ — คัดลอกไปส่งจากโปรแกรมเมลของคุณ แล้วกลับมากด “ติดต่อโรงงานแล้ว”'
            : 'แก้ไขเมลฉบับนี้ไม่ได้แล้ว — ยังคัดลอกไปใช้ซ้ำได้'}
        </p>
        <FormField label="ถึง" htmlFor="pcr-email-to" hint={canEdit ? 'ดึงจากอีเมลโรงงานในข้อมูลหลัก — ไม่บังคับ' : undefined}>
          <input
            id="pcr-email-to"
            type="email"
            disabled={!canEdit}
            value={draft.emailTo}
            onChange={(e) => onChangeDraft({ ...draft, emailTo: e.target.value })}
          />
        </FormField>
        <FormField label="หัวข้อ" htmlFor="pcr-email-subject">
          <input
            id="pcr-email-subject"
            disabled={!canEdit}
            value={draft.emailSubject}
            onChange={(e) => onChangeDraft({ ...draft, emailSubject: e.target.value })}
          />
        </FormField>
        <FormField label="เนื้อหา" htmlFor="pcr-email-body">
          <textarea
            id="pcr-email-body"
            className="min-h-40"
            disabled={!canEdit}
            value={draft.emailBody}
            onChange={(e) => onChangeDraft({ ...draft, emailBody: e.target.value })}
          />
        </FormField>
        {attachments.length > 0 ? (
          <div className="grid gap-1 text-xs text-text-secondary" data-testid="pcr-email-attachments">
            <span className="font-bold text-text-muted">ไฟล์ที่แนบไปกับเมล</span>
            {attachments.map((attachment) => (
              <span key={attachment.id} className="flex items-center gap-1.5">
                <Icon name="paperclip" size={12} />
                {attachment.fileName}
              </span>
            ))}
          </div>
        ) : null}
      </div>
    </Modal>
  );
}

/**
 * V141 ("CEO owns costing", PR #702): the CEO's per-line manual cost override. `costingItem` may
 * be undefined only in theory — the page's own button that opens this modal is itself gated on
 * `costingItem` being present, so this component never has to render a no-costing-item state.
 *
 * The mandatory reason is enforced HERE, client-side, on BOTH the set path and the clear path —
 * `onSubmit` (which is what ends up calling the API) is never invoked with a blank/whitespace-only
 * reason. The server's own check stays authoritative and is never removed or weakened; this is
 * purely so the CEO sees the mistake immediately instead of via a round-trip 400.
 */
function CostOverrideModal({ item, costingItem, onClose, onSubmit, pending }) {
  const hasOverride = costingItem.manualLandedCostPerUnitThb != null;
  const [amount, setAmount] = useState(() => String(
    hasOverride ? costingItem.manualLandedCostPerUnitThb : costingItem.landedCostPerUnitThb ?? '',
  ));
  const [reason, setReason] = useState('');
  const [amountError, setAmountError] = useState(null);
  const [reasonError, setReasonError] = useState(null);

  function validateReason() {
    if (reason.trim()) {
      setReasonError(null);
      return true;
    }
    setReasonError('กรุณาระบุเหตุผลในการปรับต้นทุน');
    return false;
  }

  function handleSet(event) {
    event.preventDefault();
    const reasonOk = validateReason();
    const numericAmount = Number(amount);
    const amountOk = amount !== '' && !Number.isNaN(numericAmount) && numericAmount >= 0;
    setAmountError(amountOk ? null : 'กรุณากรอกต้นทุนที่ปรับให้ถูกต้อง (ตั้งแต่ 0 ขึ้นไป)');
    if (!reasonOk || !amountOk) return;
    onSubmit({ manualLandedCostPerUnitThb: numericAmount, reason: reason.trim() });
  }

  function handleClear() {
    if (!validateReason()) return;
    onSubmit({ manualLandedCostPerUnitThb: null, reason: reason.trim() });
  }

  return (
    <Modal
      title={hasOverride ? 'แก้ไขต้นทุนที่ปรับ' : 'ปรับต้นทุนเอง'}
      subtitle={[item.brand, item.model].filter(Boolean).join(' ') || item.productDescription || undefined}
      onClose={onClose}
      testId="cost-override-modal"
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          {hasOverride ? (
            <Button type="button" variant="secondary" disabled={pending} onClick={handleClear}>
              ล้างค่าที่ปรับ
            </Button>
          ) : null}
          <Button type="submit" form="cost-override-form" variant="primary" disabled={pending}>
            {pending ? 'กำลังบันทึก…' : 'บันทึกต้นทุนที่ปรับ'}
          </Button>
        </>
      }
    >
      <SafeForm id="cost-override-form" onSubmit={handleSet} noValidate>
        <div className="grid gap-3">
          <div className="rounded-md border border-border-subtle bg-surface-subtle p-3 text-xs">
            <div>
              ต้นทุนคำนวณ/ชิ้น:{' '}
              <code className="text-info">{formatCurrency(costingItem.landedCostPerUnitThb, 'THB')}</code>
            </div>
            {hasOverride ? (
              <div className="mt-1">
                ต้นทุนที่ปรับปัจจุบัน/ชิ้น:{' '}
                <code className="font-bold text-override">{formatCurrency(costingItem.manualLandedCostPerUnitThb, 'THB')}</code>
              </div>
            ) : null}
            <p className="m-0 mt-1 text-2xs text-text-muted">ค่าที่คำนวณได้จะไม่ถูกเขียนทับ — ค่าที่ปรับเองจะแสดงคู่กันเสมอ</p>
          </div>
          <FormField label="ต้นทุนที่ปรับ (บาท/ชิ้น)" htmlFor="cost-override-amount" error={amountError}>
            <input
              id="cost-override-amount"
              type="number"
              min="0"
              step="0.0001"
              value={amount}
              onChange={(e) => {
                setAmount(e.target.value);
                if (amountError) setAmountError(null);
              }}
            />
          </FormField>
          <FormField label="เหตุผล" htmlFor="cost-override-reason" error={reasonError} required>
            <textarea
              id="cost-override-reason"
              className="min-h-20"
              value={reason}
              onChange={(e) => {
                setReason(e.target.value);
                if (reasonError) setReasonError(null);
              }}
            />
          </FormField>
        </div>
      </SafeForm>
    </Modal>
  );
}

/**
 * "ปรับราคาเอง" (Phase 1 UI simplification, owner ruling 2026-08-16) — a REAL behaviour change,
 * not a relabel of the cost override above: this overrides the SELLING PRICE directly, and the
 * formula (cost x margin) stops driving that line entirely, rather than feeding it a different
 * cost to recompute from. Same shape as {@link CostOverrideModal} deliberately — mandatory reason
 * in BOTH directions (set and clear), current computed value shown for comparison — because that
 * pattern is already established and reviewed here (V141), not because the two overrides mean the
 * same thing.
 */
function PriceOverrideModal({ item, decision, onClose, onSubmit, pending, newForm = false }) {
  const hasOverride = item.manualSellingPricePerRequestedUnit != null;
  const [amount, setAmount] = useState(() => String(
    hasOverride ? item.manualSellingPricePerRequestedUnit : item.proposedSellingPricePerRequestedUnit ?? '',
  ));
  const [reason, setReason] = useState('');
  const [amountError, setAmountError] = useState(null);
  const [reasonError, setReasonError] = useState(null);

  function validateReason() {
    if (reason.trim()) {
      setReasonError(null);
      return true;
    }
    setReasonError('กรุณาระบุเหตุผลในการปรับราคาขาย');
    return false;
  }

  function handleSet(event) {
    event.preventDefault();
    const reasonOk = validateReason();
    const numericAmount = Number(amount);
    const amountOk = amount !== '' && !Number.isNaN(numericAmount) && numericAmount >= 0;
    setAmountError(amountOk ? null : 'กรุณากรอกราคาที่ปรับให้ถูกต้อง (ตั้งแต่ 0 ขึ้นไป)');
    if (!reasonOk || !amountOk) return;
    onSubmit({ sellingPriceOverride: numericAmount, clearSellingPriceOverride: false, reason: reason.trim() });
  }

  function handleClear() {
    if (!validateReason()) return;
    onSubmit({ sellingPriceOverride: null, clearSellingPriceOverride: true, reason: reason.trim() });
  }

  return (
    <Modal
      title={hasOverride ? 'แก้ไขราคาที่ปรับ' : 'ปรับราคาเอง'}
      subtitle={[item.brand, item.model].filter(Boolean).join(' ') || item.productDescription || undefined}
      onClose={onClose}
      testId="price-override-modal"
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          {hasOverride ? (
            <Button type="button" variant="secondary" disabled={pending} onClick={handleClear}>
              ล้างค่าที่ปรับ
            </Button>
          ) : null}
          <Button type="submit" form="price-override-form" variant="primary" disabled={pending}>
            {pending ? 'กำลังบันทึก…' : 'บันทึกราคาที่ปรับ'}
          </Button>
        </>
      }
    >
      <SafeForm id="price-override-form" onSubmit={handleSet} noValidate>
        <div className="grid gap-3">
          <div className="rounded-md border border-border-subtle bg-surface-subtle p-3 text-xs">
            <div>
              ราคาขายที่คำนวณอัตโนมัติ (ก่อน VAT):{' '}
              <code className="text-info">{formatCurrency(item.proposedSellingPricePerRequestedUnit, decision.currency)}</code>
            </div>
            {hasOverride ? (
              <div className="mt-1">
                ราคาที่ปรับปัจจุบัน:{' '}
                <code className="font-bold text-override">{formatCurrency(item.manualSellingPricePerRequestedUnit, decision.currency)}</code>
              </div>
            ) : null}
            <p className="m-0 mt-1 text-2xs text-text-muted">
              {newForm
                // Owner ruling B (2026-09-19, Phase 2 CEO pricing), copy tightened per Opus
                // review minor #1 (2026-09-19): this control is now reachable ONLY under NET mode
                // (or before a mode is chosen) — DIRECT_NET/SPECIAL_SQM never consult it at all,
                // so the copy must say "ส่วนลด" specifically, not the generic "วิธีกรอกราคาที่
                // เลือกไว้" the original wording used (which read as if every mode applied it).
                ? 'ใช้ได้เฉพาะโหมด "ราคาตั้ง − ส่วนลด %" (NET) เท่านั้น — ค่านี้แทนที่ "ราคาตามสูตร" เป็นราคาตั้ง ส่วนลดที่กรอกไว้ยังคำนวณทับค่านี้ต่อ ไม่ได้ข้ามไปทั้งหมด โหมดราคาพิเศษ บาท/ตร.ม. และราคาสุทธิต่อแผ่นไม่ใช้ค่านี้เลย — รายการที่ไม่มีต้นทุนต้องปรับต้นทุนเอง (ปรับต้นทุนเอง) แทน'
                : 'เมื่อปรับแล้ว สูตรจะไม่คำนวณราคาขายของรายการนี้อีก — ราคาที่ปรับจะถูกใช้แทนจนกว่าจะล้างค่า'}
            </p>
          </div>
          <FormField
            label={newForm ? `ราคาตั้งที่ปรับ (${decision.currency}/แผ่น)` : `ราคาที่ปรับ (${decision.currency}/หน่วยที่ขอ)`}
            htmlFor="price-override-amount"
            error={amountError}
          >
            <input
              id="price-override-amount"
              type="number"
              min="0"
              step="0.0001"
              value={amount}
              onChange={(e) => {
                setAmount(e.target.value);
                if (amountError) setAmountError(null);
              }}
            />
          </FormField>
          <FormField label="เหตุผล" htmlFor="price-override-reason" error={reasonError} required>
            <textarea
              id="price-override-reason"
              className="min-h-20"
              value={reason}
              onChange={(e) => {
                setReason(e.target.value);
                if (reasonError) setReasonError(null);
              }}
            />
          </FormField>
        </div>
      </SafeForm>
    </Modal>
  );
}

export function PricingRequestDetailPage({ user, showToast }) {
  const { id } = useParams();
  const pricingRequestId = Number(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [responseDrafts, setResponseDrafts] = useState({});
  const [emailDrafts, setEmailDrafts] = useState({});
  const [receiveClientRequestIds, setReceiveClientRequestIds] = useState({});
  const [confirmAction, setConfirmAction] = useState(null);
  // Review remediation (COMMIT 5, P1 finding 3): the customer-change revision UI now reuses
  // PricingRequestCreateModal in mode="revision" (seeded from the current request, full item
  // editing, catalog picker, unit select) instead of the old inline reason-only form that copied
  // every field verbatim via the now-deleted revisionPayload() helper.
  const [revisionModalOpen, setRevisionModalOpen] = useState(false);
  // Which factory group's "ร่างอีเมล" modal is open, by factory-quote id (not the quote object
  // itself) — looked up fresh against `factoryQuotes` on every render, so the modal always shows
  // post-invalidate server data instead of a stale snapshot from the moment it was opened.
  const [emailModalQuoteId, setEmailModalQuoteId] = useState(null);
  // CR-1 (GLA-167): which factory card's ติดต่อโรงงานแล้ว dialog is open (by quote id), and the
  // lead-time change panel being filled in — { quoteId, change } with `change` set when editing a
  // PENDING request instead of raising a new one.
  const [contactQuoteId, setContactQuoteId] = useState(null);
  const [leadTimeDialog, setLeadTimeDialog] = useState(null);
  // R3 ("สร้างเมล is optional"): the pre-draft preview card of a factory can be contacted / mailed
  // directly — the draft is generated on demand. `previewContactFactory` is the factory NAME whose
  // contact dialog is open; `previewBusyFactory` the one currently generating/marking.
  const [previewContactFactory, setPreviewContactFactory] = useState(null);
  const [previewBusyFactory, setPreviewBusyFactory] = useState(null);

  const detailQuery = useQuery({
    queryKey: queryKeys.pricingRequestDetail(pricingRequestId),
    queryFn: () => api.pricingRequests.get(pricingRequestId).then((r) => r.pricingRequest),
    enabled: Number.isFinite(pricingRequestId),
  });

  // Manual-RFQ redesign: send() now completes synchronously (DRAFT -> REQUESTED in one call, no
  // out-of-band dispatch worker), so there is no longer an in-flight state to poll for — the
  // mutation's own onSuccess invalidate (see useActionMutation below) is enough to show the result.
  const factoryQuery = useQuery({
    queryKey: queryKeys.pricingRequestFactoryQuotes(pricingRequestId),
    queryFn: () => api.pricingRequests.listFactoryQuotes(pricingRequestId).then((r) => r.items ?? []),
    // Import only (2026-10-01): the factory-quote workspace no longer renders for the CEO, and
    // nothing else on the page reads this data for that role (the CEO's costing view uses
    // `costings`, below).
    enabled: Number.isFinite(pricingRequestId) && isImport(user),
  });

  const costingQuery = useQuery({
    queryKey: queryKeys.pricingRequestCostings(pricingRequestId),
    queryFn: () => api.pricingRequests.listCostings(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId) && canSeeRaw(user),
  });

  // B6 (GLA-135): the factory master list + country roster feeding ImportFactoryPicker below.
  // Gated on the ROLE alone (isImport(user), no summary/status dependency) rather than the fuller
  // canSetItemFactory (computed further down, after `summary` resolves) — an import user should
  // not wait on the detail fetch before this starts, and the picker itself is never rendered for
  // anyone else regardless. `countries()` is read-widened to import already (PriceImportService).
  const factoriesQuery = useQuery({
    queryKey: queryKeys.priceImportFactories(),
    queryFn: () => api.priceImport.factories(),
    enabled: isImport(user),
    staleTime: 60 * 1000,
  });
  const countriesQuery = useQuery({
    queryKey: queryKeys.priceImportCountries(),
    queryFn: () => api.priceImport.countries(),
    enabled: isImport(user),
    staleTime: 5 * 60 * 1000,
  });

  // The สกุลเงิน select on the ราคาโรงงาน per-factory control row (owner-supplied mockup,
  // 2026-08-16): options come from the real FX rate table (CeoSettingsPage's own fxRates query,
  // same queryKeys.fxRates()/api.fxRates.list()), not a hand-typed list here that could drift from
  // it. Only that Import-only factory-quote panel renders the select, so only Import fetches it
  // (2026-10-01; it used to also load for the CEO, who no longer gets that panel).
  const fxRatesQuery = useQuery({
    queryKey: queryKeys.fxRates(),
    queryFn: () => api.fxRates.list().then((r) => r.fxRates ?? []),
    enabled: Number.isFinite(pricingRequestId) && isImport(user),
    staleTime: 5 * 60 * 1000,
  });

  // Step 3: CEO Selling Price Decision. Raw (cost/margin-bearing) history is import/ceo only
  // (design correction 2 — never leak cost to Sales); this query must never even fire for a
  // sales/sales_manager actor, not just be hidden in the DOM.
  const decisionsQuery = useQuery({
    queryKey: queryKeys.pricingDecisions(pricingRequestId),
    queryFn: () => api.pricingRequests.listPricingDecisions(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId) && canSeeRawPricingDecision(user),
  });

  // V152 (V109 engine wiring): the LIVE selling_buffer, so the "วิธีคำนวณราคานี้" panel's formula
  // text shows the buffer that actually ran, not the pre-V109 "cost x (1+margin)" shape.
  // selling_price_round_up_to is dead (owner ruling 2026-09-19, Phase 2 CEO pricing — selling
  // price now rounds HALF_UP 2dp instead) and is no longer shown here at all. Same read gate as
  // PricingFormulaConfigController itself ({ceo, import}) — this page's own
  // canSeeRawPricingDecision is a superset, so no new access is opened up by fetching it here.
  const formulaConfigQuery = useQuery({
    queryKey: queryKeys.pricingFormulaConfig(),
    queryFn: () => api.pricingFormulaConfig.get().then((r) => r.formulaConfig),
    enabled: canSeeRawPricingDecision(user) && !isImport(user),
    staleTime: 5 * 60 * 1000,
  });

  // Sales-facing approved-price projection — a distinct query/DTO, not a client-side filter of
  // decisionsQuery above (which sales never even fetches).
  const decisionSalesViewQuery = useQuery({
    queryKey: queryKeys.pricingDecisionSalesView(pricingRequestId),
    queryFn: () => api.pricingRequests.getPricingDecisionSalesView(pricingRequestId).then((r) => r.decision),
    enabled: Number.isFinite(pricingRequestId) && !canSeeRawPricingDecision(user)
      && canSeePricingDecisionSalesView(user, detailQuery.data?.summary),
    retry: false,
  });

  // Step 4: Customer Quotation Generation and Issuance. Every viewer role canViewCustomerQuotation
  // allows may fetch the list (owner-scoped for sales, same as the sales-view decision query
  // above); account never fires this query, matching its total exclusion server-side.
  const customerQuotationsQuery = useQuery({
    queryKey: queryKeys.customerQuotations(pricingRequestId),
    queryFn: () => api.pricingRequests.listCustomerQuotations(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId) && canViewCustomerQuotation(user, detailQuery.data?.summary),
  });
  // GLA-123 slice S1 M1 fix (Opus review, 2026-09-20) — the NEW engine's counterpart of the query
  // above, for the SAME "ใบเสนอราคาลูกค้า" panel below. Enabled-gate used to just be
  // canViewCustomerQuotation (this comment used to say PRICING_REQUEST_VIEW_ROLES and
  // CustomerQuotationService.VIEW_ROLES were "the exact same role set" — true when M3 shipped,
  // no longer true since MAJOR-4/MINOR-1 (owner ruling, confirmed 2026-09-20, second re-review)
  // removed import from PRICING_REQUEST_VIEW_ROLES specifically, leaving the LEGACY engine's own
  // VIEW_ROLES — which canViewCustomerQuotation mirrors — untouched. `&& !isImport(user)` is what
  // keeps this query from firing a call the server would now 403 for import: the backend gate is
  // what actually enforces this either way, but there is no reason to let import's browser make a
  // doomed request and surface a raw error where the panel should simply not query at all.
  const dealQuotationForPrQuery = useQuery({
    queryKey: queryKeys.dealQuotationForPricingRequest(pricingRequestId),
    queryFn: () => api.dealQuotations.findForPricingRequest(pricingRequestId).then((r) => r.quotation ?? null),
    enabled: Number.isFinite(pricingRequestId) && canViewCustomerQuotation(user, detailQuery.data?.summary)
      && !isImport(user),
  });

  // Pricing Request attachments (V69, review remediation COMMIT 4): Sales-level supporting
  // attachments on the request itself — every viewer role can see the list (requireViewable's
  // usual scoping already applies server-side: a non-owner sales rep never even reaches this
  // page's detailQuery, so there is no separate check needed here).
  const attachmentsQuery = useQuery({
    queryKey: queryKeys.pricingRequestAttachments(pricingRequestId),
    queryFn: () => api.pricingRequests.listAttachments(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId),
  });

  // CR-1 (GLA-167) R2/R10: lead-time change requests of this pricing request. LeadTimeChangeService
  // lets import, the CEO, the owning rep and sales_manager read them (anyone else would 403), so the
  // query is gated on those roles — never fired for account & co. Non-blocking by design: nothing in
  // the pricing chain reads these rows, so a failed fetch only hides the lead-time UI.
  const leadTimeChangesQuery = useQuery({
    queryKey: queryKeys.pricingRequestLeadTimeChanges(pricingRequestId),
    queryFn: () => api.leadTimeChanges.listForPricingRequest(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId)
      && ['import', 'ceo', 'sales', 'sales_manager'].includes(user?.role),
  });
  // CR-1 (R9): the deal's stored ใบขอซื้อ rows, read-only on the factory card for import/CEO. One IR
  // per factory; the same query key DealFulfilmentPanel / the register use, so a create there shows here.
  const storedIrTicketId = detailQuery.data?.summary?.ticketId;
  const storedIrQuery = useQuery({
    queryKey: queryKeys.storedImportRequests(storedIrTicketId),
    queryFn: () => api.storedImportRequests.listForTicket(storedIrTicketId).then((r) => r.importRequests ?? []),
    enabled: storedIrTicketId != null && canSeeRaw(user),
  });

  // The unit-basis vocabulary for the ราคาโรงงาน response unit select below — fetched from the
  // backend at runtime (owner's choice, not a hardcoded list) and cached for the app's lifetime.
  const { unitBases: unitBasisCatalog } = useUnitBasisCatalog();

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestDetail(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestFactoryQuotes(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestCostings(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestAttachments(pricingRequestId) });
    // CR-1 (F): a contact / lead-time decision on this page is visible on the deal page and the
    // /fulfilment worklist too, so those caches are refreshed with it — never left to the 30s staleTime.
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestLeadTimeChanges(pricingRequestId) });
    const dealId = detailQuery.data?.summary?.ticketId;
    if (dealId != null) {
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(dealId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.storedImportRequests(dealId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.importDeal(dealId) });
    }
    queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingDecisions(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingDecisionSalesView(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.customerQuotations(pricingRequestId) });
    // GLA-123 S3 review round 5 fix (NEW-A): the NEW engine's counterpart of the invalidation
    // above. dealQuotationForPrQuery backs both the outcome-recording panel AND the recreate gate
    // (['EXPIRED','REVISION_REQUESTED','REJECTED'].includes(dealQuotationForPr?.docStatus)) further
    // down this file. ['pricingRequests','detail',id] does NOT prefix-match
    // ['pricingRequests','dealQuotationForPricingRequest',id] — these are siblings, not a parent/
    // child pair — so without this line the outcome mutation's success toast fires but the DTO
    // backing the recreate button stays stale until a manual reload or the 30s staleTime lapses
    // (api/queryClient.js). Nothing else in the app invalidates this key.
    queryClient.invalidateQueries({ queryKey: queryKeys.dealQuotationForPricingRequest(pricingRequestId) });
    // discountApprovals is keyed by quotation id, not pricingRequestId — invalidate the whole
    // family with the shared 'discountApprovals' prefix rather than needing the current
    // quotation's id here too (this function is called from mutations that may have just
    // superseded/replaced it).
    queryClient.invalidateQueries({ queryKey: ['customerQuotations', 'discountApprovals'] });
    queryClient.invalidateQueries({ queryKey: ['pricingRequests', 'queue'] });
  }

  const canReturnToPricingQueue = hasPermission(user?.role, 'canViewPricingRequestQueue');
  const returnToSafeList = () => {
    if (canReturnToPricingQueue) {
      navigate('/pricing-requests');
      return;
    }
    navigate(-1);
  };

  // `options.onError` / `options.onSuccess` are an opt-in escape hatch for the two costing
  // mutations below (startCeoReview, recalculateDecisionCost) — everything else in this file
  // calls useActionMutation with only (fn, successMessage) and gets EXACTLY the same
  // toast-only behaviour as before this change. Scoped this way (rather than editing the
  // default onError itself) so the ~10 other call sites are provably untouched.
  function useActionMutation(fn, successMessage, options = {}) {
    return useMutation({
      mutationFn: fn,
      onSuccess: () => {
        showToast?.('success', successMessage);
        invalidate();
        options.onSuccess?.();
      },
      onError: options.onError ?? ((error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ')),
    });
  }

  // B6 (GLA-135): payload is now { factoryId } — a real price_catalog.factories row's id, picked
  // from ImportFactoryPicker's SearchableCombobox — not a free-typed name.
  const setItemFactory = useActionMutation(
    ({ itemId, factoryId }) => api.pricingRequests.setItemFactory(pricingRequestId, itemId, { factoryId }),
    'บันทึกโรงงานแล้ว',
  );
  // รับเรื่อง (pickup) from the request page itself — a SUBMITTED request landed here (e.g. from a
  // notification link) with no way to claim it except going back to the คิวขอราคา queue. Same
  // endpoint + canPickupPricingRequest gate as the queue and the deal panel; server is the authority.
  const pickupRequest = useActionMutation(() => api.pricingRequests.pickup(pricingRequestId), 'รับเรื่องแล้ว');
  const generateDrafts = useActionMutation(() => api.pricingRequests.generateFactoryEmailDrafts(pricingRequestId), 'สร้างเมลแล้ว');
  const updateQuote = useActionMutation(({ quote, draft }) => api.pricingRequests.updateFactoryQuote(quote.id, draft), 'บันทึกร่างแล้ว');
  // CR-1 (GLA-167) R3/R5/R7: records that import (or the CEO) already contacted this factory —
  // FINAL, no undo. Replaces the old "ส่งแล้ว" send record. Closes its dialog only on success so a
  // 409 ("already contacted") leaves the user looking at what they tried.
  const markContacted = useActionMutation(
    ({ quote, body }) => api.pricingRequests.markFactoryQuoteContacted(quote.id, body),
    'บันทึกว่าติดต่อโรงงานแล้ว',
    { onSuccess: () => setContactQuoteId(null) },
  );
  // CR-1 R2/R10: lead-time change requests. Create/update/withdraw are import's; approve/reject the
  // owning rep's / a sales manager's. A 409 on a decision means import edited the request after the
  // banner loaded — say so and let the refetch (invalidate) show the current version.
  const closeLeadTimeDialog = { onSuccess: () => setLeadTimeDialog(null) };
  const createLeadTimeChange = useActionMutation(
    ({ quote, body }) => api.leadTimeChanges.create(quote.id, body),
    'ส่งคำขอเปลี่ยนระยะเวลานำเข้าแล้ว',
    closeLeadTimeDialog,
  );
  const updateLeadTimeChange = useActionMutation(
    ({ change, body }) => api.leadTimeChanges.update(change.id, body),
    'บันทึกการแก้ไขคำขอแล้ว',
    closeLeadTimeDialog,
  );
  const withdrawLeadTimeChange = useActionMutation(
    (change) => api.leadTimeChanges.withdraw(change.id),
    'ถอนคำขอแล้ว',
  );
  const decideErrorHandler = (error) => {
    showToast?.('error', error?.status === 409 ? 'คำขอถูกแก้ไขแล้ว กรุณาตรวจสอบอีกครั้ง' : (error?.message || 'ดำเนินการไม่สำเร็จ'));
    // The request moved under us — refetch so the banner shows the version that is current now.
    invalidate();
  };
  const approveLeadTimeChange = useActionMutation(
    (change) => api.leadTimeChanges.approve(change.id, { expectedVersion: change.version }),
    'อนุมัติการเปลี่ยนระยะเวลานำเข้าแล้ว',
    { onError: decideErrorHandler },
  );
  const rejectLeadTimeChange = useActionMutation(
    ({ change, reason }) => api.leadTimeChanges.reject(change.id, { reason, expectedVersion: change.version }),
    'บันทึกการไม่อนุมัติแล้ว',
    { onError: decideErrorHandler },
  );
  // Generates the drafts (idempotent: factories that already have one are skipped server-side) and
  // returns THIS factory's current quote from the answer.
  async function generateDraftFor(factoryName) {
    const result = await api.pricingRequests.generateFactoryEmailDrafts(pricingRequestId);
    invalidate();
    const quote = (result?.items ?? []).find((q) => q.factoryName === factoryName && q.current !== false);
    if (!quote) throw new Error(`สร้างเมลของโรงงาน ${factoryName} ไม่สำเร็จ`);
    return quote;
  }
  async function openMailForPreview(factoryName) {
    setPreviewBusyFactory(factoryName);
    try {
      const quote = await generateDraftFor(factoryName);
      setEmailModalQuoteId(quote.id);
    } catch (error) {
      showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ');
    } finally {
      setPreviewBusyFactory(null);
    }
  }
  async function contactFromPreview(factoryName, body) {
    setPreviewBusyFactory(factoryName);
    try {
      const quote = await generateDraftFor(factoryName);
      await api.pricingRequests.markFactoryQuoteContacted(quote.id, body);
      showToast?.('success', 'บันทึกว่าติดต่อโรงงานแล้ว');
      invalidate();
      setPreviewContactFactory(null);
    } catch (error) {
      showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ');
      invalidate();
    } finally {
      setPreviewBusyFactory(null);
    }
  }
  const negotiateQuote = useActionMutation((quote) => api.pricingRequests.startFactoryNegotiation(quote.id, { note: quote.negotiationNote || 'Negotiation in progress' }), 'เริ่มเจรจาแล้ว');
  /**
   * ยืนยันราคาเสนอ — ONE primary action per factory group (owner-supplied mockup, 2026-08-16, task
   * "factory-price-import-ui"), doing exactly what the two buttons it replaces (บันทึกคำตอบ/รอบแก้ไข
   * + ส่งให้ CEO อนุมัติราคา) already did, never more: record whatever price/unit/currency/note is
   * currently in the draft — FactoryQuoteService.receive(), the only path a fresh DRAFT/REQUESTED
   * quote can leave those statuses through — then mark the result ready for the CEO
   * (FactoryQuoteService.markReadyForCosting -> FactoryQuoteStatus.READY_FOR_COSTING, the exact
   * status this task's brief names and the only one this action is allowed to reach).
   *
   * Brief: "do not invent a new status and do not advance the parent pricing request." Neither call
   * below is new — both are the SAME two existing endpoints the old two-button flow already called,
   * in the same order, under the same preconditions — and markReadyForCosting's own auto-advance of
   * the PARENT PricingRequest once every current quote is ready (unchanged, see that method's own
   * doc comment) is pre-existing backend behaviour this button neither adds to nor suppresses. This
   * is a rename plus a click-count reduction, not a new workflow.
   *
   * The receive() call is SKIPPED when a response is already on file (status RESPONSE_RECEIVED /
   * NEGOTIATING) AND the draft was never touched this session. Calling receive() unconditionally
   * would be wrong, not merely redundant: past the FIRST response, FactoryQuoteService.receive()
   * supersedes the current quote and creates a brand-new revision on EVERY call (:539-570),
   * notifying the CEO of a "revised" price that was never actually revised — a plain re-confirm
   * click would silently inflate the revision count and spam a notification. `dirty` reuses this
   * file's own existing signal for "the user changed this quote's draft" — `responseDrafts[id]` is
   * only ever written by a change handler, never seeded eagerly (the render-time `?? {defaults}`
   * elsewhere computes the default without touching state) — so checking whether that key exists
   * already tracks exactly what a separate boolean flag would have to track by hand.
   */
  const [confirmingFactoryQuoteId, setConfirmingFactoryQuoteId] = useState(null);

  async function confirmFactoryQuote(quote, draft) {
    const dirty = Boolean(responseDrafts[quote.id]);
    const needsReceive = dirty || ['DRAFT', 'REQUESTED'].includes(quote.status);
    setConfirmingFactoryQuoteId(quote.id);
    try {
      let targetId = quote.id;
      if (needsReceive) {
        const clientRequestId = receiveClientRequestIds[quote.id] ?? generateClientRequestId();
        setReceiveClientRequestIds((cur) => ({ ...cur, [quote.id]: clientRequestId }));
        const result = await api.pricingRequests.receiveFactoryQuote(quote.id, {
          ...cleanResponsePayload(draft),
          clientRequestId,
        });
        // A successful submission consumes this idempotency key; a later distinct
        // response/revision for the same quote must mint a fresh one, not replay.
        setReceiveClientRequestIds((cur) => {
          const next = { ...cur };
          delete next[quote.id];
          return next;
        });
        targetId = result?.factoryQuote?.id ?? quote.id;
      }
      await api.pricingRequests.markFactoryQuoteReady(targetId);
      // Drop the local draft entirely: the fresh invalidate() below re-seeds the response grid from
      // the server's now-current (possibly renumbered, if receive() created a revision) quote, so a
      // stale local edit can never linger behind a value the server has already moved past.
      setResponseDrafts((cur) => {
        const next = { ...cur };
        delete next[quote.id];
        if (targetId !== quote.id) delete next[targetId];
        return next;
      });
      showToast?.('success', 'ยืนยันราคาเสนอแล้ว');
      invalidate();
    } catch (error) {
      showToast?.('error', error.message || 'ยืนยันราคาเสนอไม่สำเร็จ');
    } finally {
      setConfirmingFactoryQuoteId(null);
    }
  }

  const uploadQuoteAttachment = useActionMutation(({ quote, file }) => api.pricingRequests.uploadFactoryQuoteAttachment(quote.id, file), 'แนบไฟล์ราคาโรงงานแล้ว');
  const uploadPricingRequestAttachment = useActionMutation((file) => api.pricingRequests.uploadAttachment(pricingRequestId, file), 'แนบไฟล์แล้ว');
  const deletePricingRequestAttachment = useActionMutation((attachmentId) => api.pricingRequests.deleteAttachment(attachmentId), 'ลบไฟล์แนบแล้ว');
  const toggleAttachmentIncludeInFactoryEmail = useActionMutation(
    (attachment) => api.pricingRequests.setAttachmentIncludeInFactoryEmail(attachment.id, !attachment.includeInFactoryEmail),
    'อัปเดตไฟล์แนบแล้ว',
  );
  // Step 3: CEO Selling Price Decision.
  // null until the CEO types: the field then shows the formula config's own defaultMarginPct (the
  // same /pricing-formula-config CeoSettingsPage edits) and falls back to 0.30 only if that read
  // fails or returns nothing. Resolved below, once formulaConfigQuery's data is in scope.
  const [decisionDefaultMargin, setDecisionDefaultMargin] = useState(null);
  const resolvedDefaultMargin = decisionDefaultMargin
    ?? String(formulaConfigQuery.data?.defaultMarginPct ?? '0.30');
  const [startReviewClientRequestId] = useState(() => generateClientRequestId());
  // P0 fix follow-up (2026-09): startCeoReview/recalculateDecisionCost are the two costing calls
  // that hit LandedCostCalculator, whose 422 is now a multi-line "one heading + one bullet per
  // problem" message (see aggregateProblems in LandedCostCalculator.java) or FxResolver's own
  // staleness/missing-rate message. A 3.2s toast that collapses '\n' to a space (Toast.jsx renders
  // it in a plain <span>) cannot carry that — see the inline block rendered from this state, near
  // whichever of the two controls is on screen. Shared by both mutations rather than one state per
  // mutation: their trigger controls are mutually exclusive (recalculate only renders once
  // currentDecision exists; start-review only while it does not), so only one can ever be relevant
  // at a time.
  const [ceoCostingError, setCeoCostingError] = useState(null);
  // Phase 1 UI simplification: the per-item margin/minimum/ceiling draft grid is gone. For a
  // LEGACY (non-new-form) decision the only CEO-typed input on an item is still "ปรับราคาเอง",
  // which opens PriceOverrideModal — no draft state is needed for it (the modal owns its own form
  // state, same pattern as costOverrideItem below). A NEW-FORM decision (2026-10-01) does not use
  // the modal: its ราคาตั้ง is a typed input backed by ceoPriceDrafts.
  const [priceOverrideItem, setPriceOverrideItem] = useState(null);
  // Phase 2 (owner rulings 2026-09-18/19, V187): per-item draft state for the CEO price-mode
  // inputs (list price/discount, ราคาพิเศษ, or ราคาสุทธิ) — keyed by pricingDecisionItemId, only
  // ever populated for a new-form-eligible decision. Kept in local state (rather than firing a
  // mutation per keystroke) so the CEO can type before saving; "บันทึกราคา" below sends the
  // whole row in one PUT, mirroring how the legacy override modal collects input before submit.
  const [ceoPriceDrafts, setCeoPriceDrafts] = useState({});
  function updateCeoPriceDraft(itemId, patch) {
    setCeoPriceDrafts((prev) => ({ ...prev, [itemId]: { ...prev[itemId], ...patch } }));
  }
  const [approveClientRequestId, setApproveClientRequestId] = useState(() => generateClientRequestId());
  // Step 4: Customer Quotation Generation and Issuance.
  const [createQuotationClientRequestId, setCreateQuotationClientRequestId] = useState(() => generateClientRequestId());
  const [issueQuotationClientRequestId, setIssueQuotationClientRequestId] = useState(() => generateClientRequestId());
  const [revisionClientRequestId, setRevisionClientRequestId] = useState(() => generateClientRequestId());
  const [quotationHeaderDraft, setQuotationHeaderDraft] = useState({});
  const [quotationItemDrafts, setQuotationItemDrafts] = useState({});
  const [downloadingQuotationFormat, setDownloadingQuotationFormat] = useState(null);
  // Step 5: Customer Decision and Commercial Revisions.
  const [outcomeClientRequestId, setOutcomeClientRequestId] = useState(() => generateClientRequestId());
  const [outcomeNote, setOutcomeNote] = useState('');
  // Step 6: Deposit, Payment, and Order Confirmation.
  const [confirmOrderClientRequestId, setConfirmOrderClientRequestId] = useState(() => generateClientRequestId());
  const [depositPercentInput, setDepositPercentInput] = useState('0.5');
  const startCeoReview = useActionMutation(
    () => api.pricingRequests.startPricingDecision(pricingRequestId, {
      defaultMarginPct: cleanNumber(resolvedDefaultMargin),
      clientRequestId: startReviewClientRequestId,
    }),
    'เริ่มพิจารณาราคาขายแล้ว',
    {
      onSuccess: () => setCeoCostingError(null),
      // Rendered inline (role="alert", whitespace-pre-line, dismissible) in the CEO panel below —
      // NOT the toast: LandedCostCalculator's 422 here is a multi-line "heading + one bullet per
      // problem" message, and the toast both collapses newlines to a space and auto-dismisses
      // after 3.2s (Toast.jsx / useToast.js) — exactly wrong for a list the CEO needs to act on.
      onError: (error) => setCeoCostingError(error.message || 'เริ่มพิจารณาราคาขายไม่สำเร็จ'),
    },
  );
  // "ปรับราคาเอง" (Phase 1 UI simplification) — reuses PUT /pricing-decisions/{id} (the same
  // endpoint the old per-item margin/minimum/ceiling grid called) rather than a new route: the
  // owner's steer was to prefer the existing update endpoint over inventing one, and this is the
  // same PricingDecisionService#update -> applyItemUpdates path, just with a single-item payload.
  // sellingPriceOverride/clearSellingPriceOverride/decisionNote are the three fields that matter
  // here — marginPct/minimumSellingPrice are left unset (server leaves them unchanged, COALESCE
  // semantics) since this action never touches either.
  const overrideSellingPrice = useActionMutation(
    ({ decision, item, sellingPriceOverride, clearSellingPriceOverride, reason }) =>
      api.pricingRequests.updatePricingDecision(decision.id, {
        items: [{
          pricingDecisionItemId: item.id,
          sellingPriceOverride: clearSellingPriceOverride ? null : sellingPriceOverride,
          clearSellingPriceOverride: Boolean(clearSellingPriceOverride),
          // The real backend (primitive booleans) 400s a request that omits these three, even
          // though this action never touches them — always send them as "no change".
          clearDiscountPct: false,
          clearSpecialPriceSqm: false,
          clearDirectNetPrice: false,
          decisionNote: reason,
        }],
      }),
    'บันทึกราคาที่ปรับแล้ว',
  );
  // Phase 2 (owner rulings 2026-09-18/19, V187): the mode picker — one PUT that sets priceMode
  // header-side (only the first time; the request omits it once already set, matching the
  // "choose once" rule) — separate from the per-item price mutation below so switching modes
  // never accidentally also sends a stale item draft.
  const setCeoPriceMode = useActionMutation(
    ({ decision, priceMode }) => api.pricingRequests.updatePricingDecision(decision.id, { priceMode }),
    'เลือกวิธีกรอกราคาแล้ว',
    // The server recomputes every item's net for the new mode, so any unsent per-item draft was
    // typed against the OLD mode and would otherwise linger as a phantom pending edit.
    { onSuccess: () => setCeoPriceDrafts({}) },
  );
  // Saves one item's price-mode inputs (list price/discount, ราคาพิเศษ, or ราคาสุทธิ, depending
  // on the decision's priceMode) — mirrors PricingDecisionService#applyItemUpdates's own field
  // set exactly. The server derives and returns netUnitPrice; this component never computes the
  // stored value itself (previewCeoNetUnitPrice above is a preview only).
  // Review finding #6 (2026-09-19): `clears` carries the explicit "really clear this field"
  // signals (clearDiscountPct/clearSpecialPriceSqm/clearDirectNetPrice) -- sending `null` for a
  // value is indistinguishable from "not touched" on the server (COALESCE), so a genuine clear
  // (e.g. "ล้างส่วนลด") must set the matching `clearXxx` flag instead.
  // Owner correction (2026-09-19): `listUnitPrice`/`clearListUnitPrice` were dropped from this
  // payload because the CEO could not type ราคาตั้ง — it was always the server formula price.
  // 2026-10-01 (owner Ploy) REINSTATES a CEO-typed ราคาตั้ง under NET, in the รายการสินค้าและ
  // ราคาตั้งต้น panel. It is routed by line type because the backend accepts the two fields on
  // different lines (PricingDecisionService#applyItemUpdates):
  //   - IMPORT line (stockSource null): `sellingPriceOverride` + a mandatory `decisionNote`. NOT
  //     `listUnitPrice`, which the backend refuses with a 400 on import lines. An override is
  //     NET's effective list price (manual ?? list) and survives cost recalculation.
  //   - STOCK line (stockSource set): `listUnitPrice` — the only line type that accepts it, and
  //     the field the CEO types for a สต็อก line that has no formula price.
  // Neither key is sent unless the CEO actually changed ราคาตั้ง, so a discount-only save leaves
  // the override alone. Blanking the input on an import line that has an override clears it.
  const saveCeoItemPrice = useActionMutation(
    ({ decision, item, draft, clears = {} }) => {
      const isStockLine = item.stockSource != null;
      const currentList = item.manualSellingPricePerRequestedUnit ?? item.listUnitPrice;
      const listDraft = decision.priceMode === 'NET' || isStockLine ? draft.listUnitPrice : undefined;
      const listValue = cleanNumber(listDraft);
      const listPart = {};
      if (clears.clearSellingPriceOverride) {
        Object.assign(listPart, { clearSellingPriceOverride: true, decisionNote: CEO_LIST_PRICE_NOTE });
      } else if (listDraft !== undefined && listValue != null && (currentList == null || listValue !== Number(currentList))) {
        if (isStockLine) listPart.listUnitPrice = listValue;
        else Object.assign(listPart, { sellingPriceOverride: listValue, decisionNote: CEO_LIST_PRICE_NOTE });
      } else if (listDraft === '' && !isStockLine && item.manualSellingPricePerRequestedUnit != null) {
        Object.assign(listPart, { clearSellingPriceOverride: true, decisionNote: CEO_LIST_PRICE_NOTE });
      }
      return api.pricingRequests.updatePricingDecision(decision.id, {
        items: [{
          pricingDecisionItemId: item.id,
          discountPct: clears.clearDiscountPct ? null : cleanNumber(draft.discountPct),
          clearDiscountPct: Boolean(clears.clearDiscountPct),
          specialPriceSqm: clears.clearSpecialPriceSqm ? null : cleanNumber(draft.specialPriceSqm),
          clearSpecialPriceSqm: Boolean(clears.clearSpecialPriceSqm),
          directNetPrice: clears.clearDirectNetPrice ? null : cleanNumber(draft.directNetPrice),
          clearDirectNetPrice: Boolean(clears.clearDirectNetPrice),
          // QA fix (2026-09-28): the backend's UpdatePricingDecisionItemRequest declares
          // clearSellingPriceOverride as a primitive boolean. Omitting it fails Jackson
          // deserialization and the whole request 400s with a bare "คำขอไม่ถูกต้อง" -- so it is
          // always present; listPart overrides it to true only for an explicit clear.
          clearSellingPriceOverride: false,
          ...listPart,
        }],
      });
    },
    'บันทึกราคาแล้ว',
  );
  // V141 ("CEO owns costing", PR #702): recomputes the bound costing in place, preserving every
  // per-line override — see recalculatePricingDecisionCost's own doc comment in hrApi.js. Never
  // changes status, margins, or approved_* — it only refreshes cost.
  const recalculateDecisionCost = useActionMutation(
    (decision) => api.pricingRequests.recalculatePricingDecisionCost(decision.id),
    'คำนวณต้นทุนใหม่แล้ว',
    {
      onSuccess: () => setCeoCostingError(null),
      // Same reasoning as startCeoReview's onError above — inline, not the toast.
      onError: (error) => setCeoCostingError(error.message || 'คำนวณต้นทุนใหม่ไม่สำเร็จ'),
    },
  );
  // V141 (PR #702): a per-line manual cost override, sitting BESIDE the computed figure (which is
  // never destroyed). `reason` is mandatory in both directions — the modal refuses to call this at
  // all without one; the server's own check is the backstop, never removed or relied on alone.
  const [costOverrideItem, setCostOverrideItem] = useState(null);
  const overrideItemCost = useActionMutation(
    ({ decision, item, manualLandedCostPerUnitThb, reason }) =>
      api.pricingRequests.overridePricingDecisionItemCost(decision.id, item.id, { manualLandedCostPerUnitThb, reason }),
    'บันทึกต้นทุนที่ปรับแล้ว',
  );
  // GLA-152 (owner Ploy, 2026-10-01): the per-item duty product-type select (ประเภทสินค้า) is gone —
  // "it's only tiles". The hrApi/mockApi overridePricingDecisionItemProductType pair and the backend
  // endpoint stay; only this page stopped calling them.
  const approveDecision = useMutation({
    mutationFn: (decision) => api.pricingRequests.approvePricingDecision(decision.id, {
      clientRequestId: approveClientRequestId,
    }),
    onSuccess: () => {
      setApproveClientRequestId(generateClientRequestId());
      showToast?.('success', 'อนุมัติราคาขายแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const returnDecisionToImport = useActionMutation(
    ({ decision, reason }) => api.pricingRequests.returnPricingDecisionToImport(decision.id, { returnReason: reason }),
    'ตีกลับให้ฝ่ายนำเข้าแก้ไขต้นทุนแล้ว',
  );
  // Step 4: Customer Quotation Generation and Issuance.
  const createQuotation = useMutation({
    mutationFn: () => api.pricingRequests.createCustomerQuotation(pricingRequestId, {
      clientRequestId: createQuotationClientRequestId,
    }),
    onSuccess: () => {
      setCreateQuotationClientRequestId(generateClientRequestId());
      showToast?.('success', 'สร้างร่างใบเสนอราคาลูกค้าแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  // GLA-123 slice S1 (Phase 3, 2026-09-19) — "เขียนใบเสนอราคาจากคำขอราคา": the direct-deal
  // engine, prefilled from this request's CEO-approved decision. A legacy (pre-V187) decision is
  // refused server-side with a clear Thai 409 — this button is offered whenever the request is
  // otherwise eligible (canCreateCustomerQuotation's own gate), and the error surfaces as a toast
  // rather than being hidden client-side, so a legacy request still tells the rep why instead of
  // silently doing nothing.
  const createDealQuotationFromRequest = useMutation({
    mutationFn: () => api.dealQuotations.createFromPricingRequest(pricingRequestId),
    onSuccess: (result) => {
      const quotation = result?.quotation;
      if (quotation?.id) navigate(`/quotations/${quotation.id}`);
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const saveQuotation = useActionMutation((quotation) => api.pricingRequests.updateCustomerQuotation(quotation.id, {
    paymentTerms: quotationHeaderDraft.paymentTerms ?? quotation.paymentTerms,
    leadTime: quotationHeaderDraft.leadTime ?? quotation.leadTime,
    deliveryTerms: quotationHeaderDraft.deliveryTerms ?? quotation.deliveryTerms,
    validityDate: quotationHeaderDraft.validityDate ?? quotation.validityDate,
    customerNotes: quotationHeaderDraft.customerNotes ?? quotation.customerNotes,
    items: quotation.items.map((item) => {
      const draft = quotationItemDrafts[item.id] ?? {};
      return {
        quotationItemId: item.id,
        description: draft.description ?? item.description,
        itemNotes: draft.itemNotes ?? item.itemNotes,
        salesDiscount: cleanNumber(draft.salesDiscount ?? item.salesDiscount) ?? 0,
      };
    }),
  }), 'บันทึกใบเสนอราคาแล้ว');
  const issueQuotation = useMutation({
    mutationFn: (quotation) => api.pricingRequests.issueCustomerQuotation(quotation.id, {
      clientRequestId: issueQuotationClientRequestId,
    }),
    onSuccess: () => {
      setIssueQuotationClientRequestId(generateClientRequestId());
      showToast?.('success', 'ออกใบเสนอราคาลูกค้าแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const cancelQuotation = useActionMutation(
    (quotation) => api.pricingRequests.cancelCustomerQuotation(quotation.id, {}),
    'ยกเลิกร่างใบเสนอราคาแล้ว',
  );
  // CEO discount-approval workflow, Phase 2 (owner ruling 2026-08-16, V155). Both route through
  // the shared ConfirmDialog below (confirmAction type 'approveDiscount'/'rejectDiscount'),
  // mirroring approveDecision/returnDecisionToImport's own pattern for a CEO pricing decision —
  // approve gets a plain confirm, reject requires a reason.
  const approveDiscount = useActionMutation(
    (approval) => api.pricingRequests.approveDiscountApproval(approval.id),
    'อนุมัติส่วนลดแล้ว',
  );
  const rejectDiscount = useActionMutation(
    ({ approval, reason }) => api.pricingRequests.rejectDiscountApproval(approval.id, { reason }),
    'ปฏิเสธส่วนลดแล้ว',
  );
  const createQuotationRevision = useMutation({
    mutationFn: (quotation) => api.pricingRequests.createCustomerQuotationRevision(quotation.id, {
      clientRequestId: revisionClientRequestId,
    }),
    onSuccess: () => {
      setRevisionClientRequestId(generateClientRequestId());
      setQuotationItemDrafts({});
      setQuotationHeaderDraft({});
      showToast?.('success', 'สร้างรอบแก้ไขใหม่แล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  // Step 5: Customer Decision and Commercial Revisions.
  const recordQuotationOutcome = useMutation({
    mutationFn: ({ quotation, outcome }) => api.pricingRequests.recordCustomerQuotationOutcome(quotation.id, {
      outcome,
      customerNote: outcomeNote || null,
      clientRequestId: outcomeClientRequestId,
    }),
    onSuccess: () => {
      setOutcomeClientRequestId(generateClientRequestId());
      setOutcomeNote('');
      showToast?.('success', 'บันทึกผลใบเสนอราคาแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  // GLA-123 slice S3 (R9) — the SAME outcome action as recordQuotationOutcome above, on the
  // NEW-engine (PRICING_REQUEST-origin) quotation instead of the legacy one. Shares
  // outcomeNote/outcomeClientRequestId state with recordQuotationOutcome: M2 already guarantees a
  // pricing request can never carry a live quotation on BOTH engines at once, so only one of the
  // two outcome UI blocks below is ever rendered for a given request, and the two mutations never
  // race over the same state.
  const recordDealQuotationOutcome = useMutation({
    mutationFn: ({ quotation, outcome }) => api.dealQuotations.recordOutcome(quotation.id, {
      outcome,
      customerNote: outcomeNote || null,
      clientRequestId: outcomeClientRequestId,
    }),
    onSuccess: () => {
      setOutcomeClientRequestId(generateClientRequestId());
      setOutcomeNote('');
      showToast?.('success', 'บันทึกผลใบเสนอราคาแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  // Step 6: Deposit, Payment, and Order Confirmation.
  const confirmOrder = useMutation({
    mutationFn: () => api.pricingRequests.confirmOrder(pricingRequestId, {
      clientRequestId: confirmOrderClientRequestId,
    }),
    onSuccess: () => {
      setConfirmOrderClientRequestId(generateClientRequestId());
      showToast?.('success', 'ยืนยันคำสั่งซื้อแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const createDepositNoticeFromQuotation = useMutation({
    mutationFn: () => api.pricingRequests.createDepositNoticeFromQuotation(pricingRequestId, {
      depositPercent: cleanNumber(depositPercentInput),
    }),
    onSuccess: () => {
      showToast?.('success', 'สร้างร่างใบแจ้งยอดเงินรับมัดจำแล้ว');
      // Reuse the existing (legacy) deposit-notice page as-is — it already loads/edits/issues a
      // DRAFT by ticketId; the draft this just created is exactly what it will find and show.
      navigate(`/tickets/${summary.ticketId}/deposit`);
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  /**
   * Copies the generated factory message so Import can paste it into its own mail client.
   * `navigator.clipboard` is absent in jsdom and on non-secure origins, so the failure path
   * reports honestly rather than pretending the copy happened — a silent no-op here would have
   * Import paste stale content without knowing.
   */
  async function copyFactoryEmail(emailDraft) {
    const text = [
      emailDraft.emailTo ? `To: ${emailDraft.emailTo}` : null,
      emailDraft.emailSubject ? `Subject: ${emailDraft.emailSubject}` : null,
      '',
      emailDraft.emailBody ?? '',
    ].filter((line) => line !== null).join('\n');
    try {
      if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable');
      await navigator.clipboard.writeText(text);
      showToast?.('success', 'คัดลอกข้อความแล้ว');
    } catch {
      showToast?.('error', 'คัดลอกไม่สำเร็จ — กรุณาเลือกข้อความแล้วคัดลอกเอง');
    }
  }

  // CR-1 (R9): import reads the factory's IR PDF straight off the card (read-only — creating or
  // revising it is sales's, from the deal page).
  const [downloadingIrId, setDownloadingIrId] = useState(null);
  async function downloadIr(row) {
    setDownloadingIrId(row.id);
    try {
      const blob = await api.storedImportRequests.download(row.id, undefined);
      downloadBlob(blob, `IR-${row.docNumber ?? `draft-${row.id}`}-${row.factoryName}`, 'pdf');
    } catch (err) {
      showToast?.('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloadingIrId(null);
    }
  }

  async function handleDownloadCustomerQuotation(quotation, format) {
    setDownloadingQuotationFormat(format);
    try {
      const blob = format === 'pdf'
        ? await api.pricingRequests.downloadCustomerQuotationPdf(quotation.id)
        : await api.pricingRequests.downloadCustomerQuotationXlsx(quotation.id);
      downloadBlob(blob, quotation.number ?? 'customer-quotation', format);
    } catch (err) {
      showToast?.('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloadingQuotationFormat(null);
    }
  }
  const request = detailQuery.data;
  const summary = request?.summary;
  const status = pricingRequestStatusLabel(summary?.status);
  // itemId -> the factoryId Import has picked (as a string, matching SearchableCombobox's own
  // `code` convention) for that line. B6 (GLA-135): this used to hold the free-typed factory
  // NAME; it holds an id now, resolved server-side to the canonical master name on save. Same
  // shape as `responseDrafts` above: a key exists only once a change handler has written to it.
  const [factoryDrafts, setFactoryDrafts] = useState({});
  // pricingRequestItemId -> the sales-side item it came from. Feeds both defaultResponseItems'
  // autofill and the read-only "what Sales asked for" echo on each response row. Declared here
  // rather than beside the mutations above because it reads `request`, which is assigned just
  // above this line — and there are no early returns between, so hook order stays stable.
  const requestItemById = useMemo(
    () => new Map((request?.items ?? []).map((item) => [item.id, item])),
    [request],
  );
  // The lines FactoryQuoteService.groupByFactory would refuse, computed from the SAME predicate it
  // uses, with the same 1-based row position it reports — so the warning on screen and the 422 the
  // button would return name the same rows.
  const missingFactoryItems = useMemo(
    () => (request?.items ?? [])
      .map((item, index) => ({ item, position: index + 1 }))
      .filter((entry) => !itemFactoryName(entry.item)),
    [request],
  );
  // Import owns this field, and only while the request is in its hands. NOT an authorization
  // decision — PricingRequestService#setItemFactory is — just whether to offer an input that would
  // otherwise be refused.
  const canSetItemFactory = isImport(user) && FACTORY_ROUTING_STATUSES.includes(summary?.status);
  // Whether FactoryQuoteService.send (and updateDraft, guarded by the same DRAFT_STATUSES) would
  // still accept an action against this request — i.e. whether the request itself, not any one
  // quote, sits inside FACTORY_ROUTING_STATUSES (mirrors FactoryQuoteService.DRAFT_STATUSES, see
  // that constant's own comment). Hoisted here (rather than recomputed per factory group below)
  // because FactoryEmailDraftModal needs it too — its send/save gating is a per-QUOTE status read
  // that has the identical gap the price grid had before the #1062 review fix.
  const inSendWindow = FACTORY_ROUTING_STATUSES.includes(summary?.status);
  const factoryQuotes = useMemo(() => factoryQuery.data ?? [], [factoryQuery.data]);
  const factoryGroups = useMemo(() => groupFactoryQuotesByFactory(factoryQuotes), [factoryQuotes]);
  // Before any ร่างอีเมล has been generated there are no factory quotes, so the price section used
  // to render a bare "ยังไม่มีราคาโรงงาน" — Import had no idea what they were about to price (owner
  // UX ask 2026-09-24). This groups the request's OWN items by the factory each is routed to, so the
  // section shows "what needs a price, per factory" up front; it is read-only preview only — the
  // real price inputs still appear (and unlock per the email-sent nudge) once drafts are generated.
  //
  // Opus review of #1062 (2026-09-28): this used to be gated on `factoryGroups.length === 0`, so it
  // was all-or-nothing — the moment ANY factory quote existed, the whole preview disappeared. But
  // `canSetItemFactory` only ever GAP-FILLS a factory name on a line Sales left blank — never
  // re-routes one that already has a factory (PricingRequestService#setItemFactory's own Javadoc:
  // "deliberately a gap-FILL, never a re-route" — a line that already has a factory is refused with
  // a 409) — while the request is still in FACTORY_ROUTING_STATUSES. If that happens AFTER drafts
  // were already generated for other lines, the newly-filled item has no factory-quote row of its
  // own (it never went through สร้างร่างอีเมล), so it showed up in neither `factoryGroups` nor the
  // (suppressed) preview — invisible, with no price surface at all. Fixed by covering exactly the
  // items no CURRENT factory quote already lists (below), independent of whether other items in the
  // same request already have quotes.
  const coveredItemIds = useMemo(() => {
    const ids = new Set();
    factoryGroups.forEach((group) => {
      (group.current.items ?? []).forEach((item) => ids.add(item.pricingRequestItemId));
    });
    return ids;
  }, [factoryGroups]);
  const pricePreviewGroups = useMemo(() => {
    const map = new Map();
    (request?.items ?? [])
      .filter((item) => !coveredItemIds.has(item.id))
      .forEach((item) => {
        const name = itemFactoryName(item) || 'ยังไม่ได้ระบุโรงงาน';
        if (!map.has(name)) map.set(name, { key: name, factoryName: name, items: [] });
        map.get(name).items.push(item);
      });
    return [...map.values()];
  }, [request, coveredItemIds]);
  // Opus review of #1062 (2026-09-28): this used to count only `factoryGroups` (quoted items), so
  // the panel title undercounted in the mixed case now covered by `pricePreviewGroups` — a request
  // with 2 quoted items + 1 gap-filled-but-not-yet-quoted item showed "(2 รายการ)" above 3 rows.
  // Count both: quoted items plus previewed-but-not-yet-quoted items.
  const factoryItemCount = useMemo(
    () => factoryGroups.reduce((sum, group) => sum + (group.current.items?.length ?? 0), 0)
      + pricePreviewGroups.reduce((sum, group) => sum + group.items.length, 0),
    [factoryGroups, pricePreviewGroups],
  );
  // Currency codes Import may pick from the per-factory สกุลเงิน select — real trade currencies
  // this business already reads elsewhere (fxRatesQuery above), not an invented list. THB always
  // leads (the default for most factories) when present, then the rest in whatever order the FX
  // table returns.
  const currencyOptions = useMemo(() => {
    const codes = (fxRatesQuery.data ?? []).map((fx) => fx.currency);
    const unique = [...new Set(codes)];
    return unique.includes('THB') ? ['THB', ...unique.filter((c) => c !== 'THB')] : unique;
  }, [fxRatesQuery.data]);
  const emailModalQuote = emailModalQuoteId != null
    ? factoryQuotes.find((q) => q.id === emailModalQuoteId) ?? null
    : null;
  // CR-1 (GLA-167): who may run the contact step (สร้างเมล + ติดต่อโรงงานแล้ว) — import AND the CEO
  // (R5, B-R1; FactoryQuoteService.CONTACT_ROLES). Price entry, lead-time requests and confirm stay
  // import-only. UI-level gate only — the service is the authority.
  const canContactFactory = user?.role === 'import' || user?.role === 'ceo';
  const contactQuote = contactQuoteId != null ? factoryQuotes.find((q) => q.id === contactQuoteId) ?? null : null;
  const leadTimeChanges = useMemo(() => leadTimeChangesQuery.data ?? [], [leadTimeChangesQuery.data]);
  const leadTimeSplit = useMemo(() => splitLeadTimeChanges(leadTimeChanges), [leadTimeChanges]);
  // B-R3: only the owning rep (sales + ticket creator, matched the way this page matches ownership
  // everywhere: ticketCreatedById === employeeId) or a sales manager decides — never the CEO.
  const canDecideLeadTime = (isSales(user) && summary?.ticketCreatedById === user?.employeeId)
    || user?.role === 'sales_manager';
  // The factory a pending request is about, for a viewer who cannot read factory quotes (sales):
  // read it off the request's own lines (every line of one change belongs to one factory).
  const lineNameById = useMemo(
    () => new Map((request?.items ?? []).map((item) => [item.id, itemDisplayName(item)])),
    [request],
  );
  const leadTimeDialogQuote = leadTimeDialog ? factoryQuotes.find((q) => q.id === leadTimeDialog.quoteId) ?? null : null;
  const costings = useMemo(() => costingQuery.data ?? [], [costingQuery.data]);
  const pricingDecisions = useMemo(() => decisionsQuery.data ?? [], [decisionsQuery.data]);
  // The currently-relevant decision: the open DRAFT if one exists (the CEO's active review),
  // else the most recent one (so a just-approved or just-returned decision still renders).
  const currentDecision = useMemo(
    () => pricingDecisions.find((d) => d.status === 'DRAFT') ?? [...pricingDecisions].reverse()[0] ?? null,
    [pricingDecisions],
  );
  // Phase 1 UI simplification: lifted out of the render IIFE so both the Panel's `actions` slot
  // (the small refresh control "at the top of the section") and the body below can read it —
  // mirrors PricingDecisionService's own DRAFT + CEO_REVIEWING + canActOnPricingDecision guard.
  const decisionEditable = Boolean(currentDecision) && currentDecision.status === 'DRAFT'
    && canActOnPricingDecision(user, summary);
  // V141 ("CEO owns costing"): the bound costing's items, keyed by their OWN id — a decision
  // item's pricingCostingItemId is a FK to that id, never to pricingRequestItemId (the two are
  // easy to conflate since a costing item also carries pricingRequestItemId as its own FK). Every
  // render must tolerate a missing costing item (the map simply has no entry for it) — this query
  // can legitimately be empty (sales/sales_manager never fetch it) or not yet contain the bound
  // costing (a brand-new decision before its first paint).
  const decisionCostingItems = useMemo(() => {
    const costing = costings.find((c) => c.id === currentDecision?.pricingCostingId);
    return new Map((costing?.items ?? []).map((ci) => [ci.id, ci]));
  }, [costings, currentDecision]);
  const decisionSalesView = decisionSalesViewQuery.data;
  // Step 4: newest revision last (creation order) — the OPEN draft/ready-to-issue revision if
  // one exists, else the most recent (so a just-issued or just-cancelled quotation still shows).
  const customerQuotations = useMemo(
    () => [...(customerQuotationsQuery.data ?? [])].sort((a, b) => a.quotationRevisionNo - b.quotationRevisionNo),
    [customerQuotationsQuery.data],
  );
  const currentCustomerQuotation = useMemo(
    () => customerQuotations.find((q) => isCustomerQuotationEditable(q)) ?? [...customerQuotations].reverse()[0] ?? null,
    [customerQuotations],
  );
  // GLA-123 slice S1 M1 fix (Opus review, 2026-09-20): the NEW engine's counterpart of
  // currentCustomerQuotation above. M2's mutual exclusivity means these are never both LIVE at
  // once, but a CANCELLED legacy quotation can coexist alongside a live new-engine one (or vice
  // versa) — checking dealQuotationForPr first, and only falling back to the legacy quotation
  // display when it is absent, is what keeps the panel showing "the one that matters" rather than
  // a stale cancelled row.
  const dealQuotationForPr = dealQuotationForPrQuery.data ?? null;
  // CEO discount-approval workflow, Phase 2 (V155): per-line status for the CURRENT quotation
  // only — placed here (not grouped with the other useQuery calls above) because its key/enabled
  // genuinely depend on currentCustomerQuotation's id, and re-deriving that id independently here
  // would be the exact kind of duplicated-derivation drift this codebase has been bitten by
  // before. Same view-access gate as customerQuotationsQuery itself (the backend delegates to the
  // identical check), so nothing new is exposed by fetching it.
  const discountApprovalsQuery = useQuery({
    queryKey: queryKeys.discountApprovals(currentCustomerQuotation?.id),
    queryFn: () => api.pricingRequests.listDiscountApprovalsForQuotation(currentCustomerQuotation.id).then((r) => r.items ?? []),
    enabled: Boolean(currentCustomerQuotation?.id) && canViewCustomerQuotation(user, summary),
  });
  const discountApprovalByItemId = useMemo(
    () => new Map((discountApprovalsQuery.data ?? []).map((a) => [a.quotationItemId, a])),
    [discountApprovalsQuery.data],
  );
  // Mirrors PricingRequestService.createCustomerChangeRevision (:438-465). The status half used to
  // be a literal `!['DRAFT','CANCELLED','SUPERSEDED'].includes(status)` denylist — verbatim the
  // hand-maintained one PR #703 DELETED from the backend, replacing it with
  // `canTransition(status, SUPERSEDED)` precisely because the denylist and the state machine had
  // drifted apart in both directions. The frontend kept the copy the backend threw away, so it
  // still offered สร้างรอบแก้ไข on a QUOTATION_ACCEPTED deal — which #703 made terminal, with its
  // own explicit 409 ("ลูกค้ายอมรับใบเสนอราคาแล้ว..."). Issue #734.
  //
  // Reading the SUPERSEDED edge instead means this button and that 409 cannot disagree without the
  // transition table itself being wrong, which is one thing to keep true rather than two.
  // QUOTATION_ACCEPTED needs no special case here: it is terminal in the table, so it has no
  // SUPERSEDED edge and the predicate is already false for it.
  const canCreateCustomerRevision = isSales(user)
    && summary?.ticketCreatedById === user?.employeeId
    && canTransition(summary?.status, 'SUPERSEDED');
  const pricingRequestAttachments = attachmentsQuery.data ?? [];
  // Mirrors PricingRequestService.ATTACHMENT_EDITABLE_STATUSES: Sales may only upload/delete its
  // own Pricing Request attachments while the request is DRAFT, and only on the request it owns.
  // V140 narrowed that set from {DRAFT, MORE_INFO_REQUIRED} to {DRAFT} when the ขอข้อมูลเพิ่มเติม
  // round-trip left the product. Offering the controls on any wider set would just produce a 409
  // from uploadAttachment/deleteAttachment.
  const canEditPricingRequestAttachments = isSales(user)
    && summary?.ticketCreatedById === user?.employeeId
    && summary?.status === 'DRAFT';
  const detailErrorStatus = apiStatus(detailQuery.error);

  if (detailQuery.isLoading) {
    return (
      <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
        <StatePanel
          state="loading"
          title="กำลังโหลดคำขอราคา"
          description="กำลังดึงรายละเอียดสินค้า ผู้รับ และสถานะล่าสุด"
        >
          <PricingRequestDetailSkeleton />
        </StatePanel>
      </div>
    );
  }

  if (detailQuery.isError) {
    if (detailErrorStatus === 404) {
      return (
        <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
          <StatePanel
            state="notFound"
            title="ไม่พบคำขอราคานี้"
            description="ตรวจสอบลิงก์อีกครั้ง หรือกลับไปเปิดจากรายการที่คุณเข้าถึงได้"
            action={(
              <Button type="button" variant="primary" onClick={returnToSafeList}>
                {canReturnToPricingQueue ? 'กลับไปที่คิวขอราคา' : 'กลับ'}
              </Button>
            )}
          />
        </div>
      );
    }

    if (detailErrorStatus === 403) {
      return (
        <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
          <StatePanel
            state="denied"
            title="ยังเปิดคำขอราคานี้ไม่ได้"
            description="ระบบไม่เปิดเผยรายละเอียดของคำขอราคาที่คุณไม่มีสิทธิ์เข้าถึง"
            action={(
              <Button type="button" variant="primary" onClick={returnToSafeList}>
                {canReturnToPricingQueue ? 'กลับไปที่คิวขอราคา' : 'กลับ'}
              </Button>
            )}
          />
        </div>
      );
    }

    return (
      <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
        <StatePanel
          state="error"
          title={toUserErrorMessage(detailQuery.error, 'โหลดคำขอราคาไม่สำเร็จ')}
          description={toUserErrorDescription(detailQuery.error, 'ลองใหม่อีกครั้ง หรือกลับไปที่รายการคำขอ')}
          action={(
            <Button type="button" variant="secondary" onClick={() => detailQuery.refetch()}>
              <Icon name="refresh" size={14} />
              ลองใหม่
            </Button>
          )}
          secondaryAction={(
            <Button type="button" variant="primary" onClick={returnToSafeList}>
              {canReturnToPricingQueue ? 'กลับไปที่คิวขอราคา' : 'กลับ'}
            </Button>
          )}
        />
      </div>
    );
  }

  if (!summary) {
    return (
      <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
        <StatePanel
          state="notFound"
          title="ไม่พบคำขอราคานี้"
          description="ตรวจสอบลิงก์อีกครั้ง หรือกลับไปเปิดจากรายการที่คุณเข้าถึงได้"
          action={(
            <Button type="button" variant="primary" onClick={returnToSafeList}>
              {canReturnToPricingQueue ? 'กลับไปที่คิวขอราคา' : 'กลับ'}
            </Button>
          )}
        />
      </div>
    );
  }

  // ── CEO pricing workspace (owner Ploy, 2026-10-01) ─────────────────────────────────────
  // The CEO no longer has three separate panels (factory quotes / ต้นทุนนำเข้า / การพิจารณาราคา
  // ขายของ CEO). Everything lives in the single "รายการสินค้าและราคาตั้งต้น" panel: the mode
  // picker on top, one pricing row per item INSIDE its spec card, totals and actions below.
  // These are plain render helpers (no hooks) so they can sit after the loading/error guards.
  const ceoWorkspace = canSeeRawPricingDecision(user) && !isImport(user);

  function ceoDecisionState(decision) {
    const decisionStatus = pricingDecisionStatusLabel(decision.status);
    const editable = decisionEditable;
    // Phase 1 UI simplification: ราคาขั้นต่ำ is no longer a CEO input (auto-populated
    // server-side at approve() — see PricingDecisionService#approve), so it can never
    // block approval here any more. A "ปรับราคาเอง" override needs no margin at all —
    // its price is fixed directly, mirroring PricingDecisionService#approve's own
    // missingMargin exemption for an overridden item.
    // Phase 2: a new-form decision never carries a margin at all — its OWN gate
    // (missingCeoPrice, declared below once newForm is known) replaces this one.
    const missingBeforeApprove = isNewFormEligibleDecision(decision) ? [] : decision.items.filter((item) => {
      const hasPriceOverride = item.manualSellingPricePerRequestedUnit != null;
      return !hasPriceOverride && (item.proposedMarginPct == null || item.proposedMarginPct === '');
    });
    // V141: mirrors PricingDecisionService.approve's own stale-override 409 guard, so the
    // CEO discovers it here instead of via a failed approve. The server stays
    // authoritative — this only pre-empts a call that would fail anyway.
    const staleOverrideItems = decision.items.filter(
      (item) => decisionCostingItems.get(item.pricingCostingItemId)?.overrideStale,
    );
    // Phase 2 (owner rulings 2026-09-18/19, V187): a new-form decision is priced
    // entirely through the mode picker below — margin/"ปรับราคาเอง" never apply to it.
    const newForm = isNewFormEligibleDecision(decision);
    const missingCeoPrice = newForm ? decision.items.filter((item) => item.netUnitPrice == null) : [];
    // Opus review finding #1 (2026-09-19): mirrors PricingDecisionService.approve's own
    // uncosted gate (PricingDecisionService#approve) — previously only implied by a badge with no
    // wired disabled state, so the UI could offer "อนุมัติราคาขาย" on a decision the server would
    // still 422. Three rules, all mirrored from Java:
    //   - stock lines (stockSource set) are exempt: they have no cost, only a typed ราคาตั้ง;
    //   - an item with no frozen cost is blocked unless it carries a "ปรับราคาเอง" override ...
    //   - ... and that override only clears the gate when priceMode is null (legacy) or NET. Under
    //     DIRECT_NET/SPECIAL_SQM computeNetUnitPrice never reads it, so those modes need a REAL cost
    //     (ปรับต้นทุนเอง) — otherwise an item with zero cost backing could approve.
    const overrideClearsCostGate = decision.priceMode == null || decision.priceMode === 'NET';
    const missingCost = decision.items.filter((item) =>
      item.stockSource == null
      && item.frozenLandedCostPerRequestedUnitThb == null
      && !(overrideClearsCostGate && item.manualSellingPricePerRequestedUnit != null));
    // Java (approve, stockWithoutListPrice): EVERY stock line needs a ราคาตั้ง > 0 whatever the price
    // mode — under DIRECT_NET/SPECIAL_SQM it is a required reference, the net still comes from the
    // mode's own input.
    const missingStockListPrice = decision.items.filter((item) =>
      item.stockSource != null && !(Number(item.listUnitPrice) > 0));
    return { decision, decisionStatus, editable, missingBeforeApprove, staleOverrideItems, newForm, missingCeoPrice, missingCost, missingStockListPrice };
  }

  function renderCeoDecisionHeader({ decision, decisionStatus }) {
    return (
      <div className="flex flex-wrap items-center gap-2">
        <strong>{decision.decisionCode}</strong>
        <StatusBadge tone="neutral">เวอร์ชัน {decision.decisionVersionNo}</StatusBadge>
        <StatusBadge tone={decisionStatus.tone}>{decisionStatus.label}</StatusBadge>
        <span className="text-xs text-text-muted">
          {decision.currency} · อัตราแลกเปลี่ยน {decision.fxRateUsed} ({decision.fxSource}, {decision.fxEffectiveDate})
        </span>
      </div>
    );
  }

  function renderCeoPriceModePicker({ decision, editable }) {
    return (
      <div>
        <FormField
          label="วิธีกรอกราคากระเบื้อง"
          hint={(decision.priceMode === 'NET'
            // 2026-10-01 (owner Ploy): ราคาตั้ง is typed again — it starts from the formula
            // price and the CEO may overwrite it (see saveCeoItemPrice).
            ? 'กรอกราคาตั้งต่อแผ่นและส่วนลด % — ราคาตั้งเริ่มต้นจากสูตร แก้ไขได้'
            : PRICE_MODE_OPTIONS.find((opt) => opt.code === decision.priceMode)?.hint)
            ?? 'เลือกวิธีกรอกราคาสำหรับมติราคานี้ — เปลี่ยนได้ ใช้กับทุกรายการในมติราคานี้'}
        >
          <div className="flex flex-wrap gap-2" role="group" aria-label="วิธีกรอกราคากระเบื้อง">
            {PRICE_MODE_OPTIONS.map((opt) => (
              <Button
                key={opt.code}
                type="button"
                variant={decision.priceMode === opt.code ? 'primary' : 'secondary'}
                size="sm"
                disabled={!editable || setCeoPriceMode.isPending || decision.priceMode === opt.code}
                aria-pressed={decision.priceMode === opt.code}
                onClick={() => {
                  // Review finding #2 (2026-09-19): switching AWAY from an
                  // already-chosen mode discards every item's net under the OLD
                  // mode (the server recomputes from each item's stored inputs for
                  // the NEW one, nulling out whatever it lacks) — confirm first, so
                  // the CEO is never surprised by a price silently disappearing.
                  // Picking a mode for the FIRST time needs no confirmation.
                  if (decision.priceMode != null) {
                    setConfirmAction({ type: 'switchPriceMode', decision, priceMode: opt.code });
                  } else {
                    setCeoPriceMode.mutate({ decision, priceMode: opt.code });
                  }
                }}
                data-testid={`pcr-ceo-price-mode-${opt.code}`}
              >
                {opt.label}
              </Button>
            ))}
          </div>
        </FormField>
      </div>
    );
  }

  function renderCeoDecisionItem(decision, item, editable) {
    const newForm = isNewFormEligibleDecision(decision);
    // V141: the bound costing line this decision item was frozen from — may
    // legitimately be undefined (costings never fetched, or not yet loaded), in
    // which case the derivation below renders only the decision item's own
    // frozen cost, with no cost-override affordance at all.
    const costingItem = decisionCostingItems.get(item.pricingCostingItemId);
    const hasCostOverride = costingItem?.manualLandedCostPerUnitThb != null;
    const hasPriceOverride = item.manualSellingPricePerRequestedUnit != null;
    // Frozen (post-approval) beats an active override, which beats the formula's
    // own computed figure — the one number the main view shows. Never a fourth,
    // client-recomputed value: this is exactly what the server will freeze in
    // (design correction 7 + the ปรับราคาเอง exception to it), never a preview.
    const effectivePrice = item.approvedSellingPricePerRequestedUnit
      ?? (hasPriceOverride ? item.manualSellingPricePerRequestedUnit : item.proposedSellingPricePerRequestedUnit);
    const effectiveMargin = item.approvedMarginPct ?? item.proposedMarginPct;
    // ── Phase 2 (owner rulings 2026-09-18/19, V187) ─────────────────────────
    if (newForm) {
      const draft = ceoPriceDrafts[item.id] ?? {};
      const isStockLine = item.stockSource != null;
      // 2026-10-01 (owner Ploy): under NET the CEO types ราคาตั้ง again. The effective list price
      // mirrors PricingDecisionService (override ?? listUnitPrice); the input starts from it and
      // the in-progress draft wins for the preview.
      const currentList = hasPriceOverride ? item.manualSellingPricePerRequestedUnit : item.listUnitPrice;
      const listDraft = draft.listUnitPrice;
      const listInputValue = listDraft ?? currentList ?? '';
      const listDraftNumber = cleanNumber(listDraft);
      // Java rejects a non-positive ราคาตั้ง (stock: "> 0"; override: non-negative, and 0 would
      // price the line at zero), so the UI refuses it up front instead of round-tripping a 400.
      // Blank is NOT invalid: on an override line it means "clear the override".
      const listInvalid = listDraft != null && listDraft !== '' && !(listDraftNumber > 0);
      // A blanked input on a line that has an override means "drop the override" (server then
      // falls back to listUnitPrice); on any other line a blank simply leaves the price as saved.
      const previewList = listDraftNumber != null && !listInvalid ? listDraftNumber
        : (listDraft === '' && hasPriceOverride && !isStockLine) ? item.listUnitPrice
          : currentList;
      const previewNet = previewCeoNetUnitPrice(decision.priceMode, {
        listUnitPrice: previewList,
        discountPct: draft.discountPct ?? item.discountPct,
        directNetPrice: draft.directNetPrice ?? item.directNetPrice,
      });
      const savedNet = item.netUnitPrice;
      // Opus review minor #7 (2026-09-19): a pending, unsaved draft edit must always win over the
      // last-saved figure (repro: saved net 83 from ruling A's zero-discount auto-fill, typing 10%
      // used to keep showing ฿83.00 instead of ฿74.70).
      // A ราคาตั้ง draft only moves the net under NET; under the other modes (stock line's required
      // reference price) it must not turn the saved net into a blank "preview".
      const hasPendingDraft = Object.keys(draft).some((k) => k !== 'listUnitPrice' || decision.priceMode === 'NET');
      const displayNet = hasPendingDraft ? previewNet : savedNet;
      const showingUnsavedPreview = hasPendingDraft && displayNet != null;
      // SPECIAL_SQM's net is never previewed client-side (previewCeoNetUnitPrice returns null for
      // it on purpose — its two-step rounding order IS the algorithm): a pending edit there must
      // say "computed on save", never keep showing the pre-edit saved figure as if it were current.
      const showingUncomputedPreview = hasPendingDraft && displayNet == null
        && decision.priceMode === 'SPECIAL_SQM';
      const lineTotal = displayNet != null ? round2(Number(item.requestedQuantity) * displayNet) : null;
      // Owner ruling A (2026-09-19): the formula price is shown both per แผ่น and per ตร.ม. — a
      // plain division, not the SPECIAL_SQM VAT-stripping algorithm, so safe to compute here.
      const formulaPricePerSqm = item.proposedSellingPricePerRequestedUnit != null && item.sqmPerPiece > 0
        ? round2(Number(item.proposedSellingPricePerRequestedUnit) / Number(item.sqmPerPiece))
        : null;
      const savingThisItem = saveCeoItemPrice.isPending
        && saveCeoItemPrice.variables?.item?.id === item.id;
      const clearingThisDiscount = savingThisItem && saveCeoItemPrice.variables?.clears?.clearDiscountPct;
      const clearingThisOverride = savingThisItem && saveCeoItemPrice.variables?.clears?.clearSellingPriceOverride;
      function saveThisItem(clears) {
        // ล้างส่วนลด is a scoped action: it sends ONLY the discount clear (an empty draft), so it
        // can never commit a still-unsent ราคาตั้ง / special / direct-net edit as a side effect, and
        // on success it drops just the discount draft, keeping the others in the inputs.
        // ใช้ราคาตามสูตร is scoped the same way (only the override clear; drops just the ราคาตั้ง draft).
        const onlyClearingDiscount = Boolean(clears?.clearDiscountPct);
        const onlyClearingOverride = Boolean(clears?.clearSellingPriceOverride);
        const scopedDraftKey = onlyClearingDiscount ? 'discountPct' : onlyClearingOverride ? 'listUnitPrice' : null;
        saveCeoItemPrice.mutate({ decision, item, draft: scopedDraftKey ? {} : draft, clears }, {
          // Clear the local draft once the server has confirmed the save, so the inputs fall back
          // to the freshly-saved stored values instead of a stale draft.
          onSuccess: () => setCeoPriceDrafts((prev) => {
            const next = { ...prev };
            if (scopedDraftKey) {
              const { [scopedDraftKey]: _dropped, ...rest } = next[item.id] ?? {};
              if (Object.keys(rest).length > 0) next[item.id] = rest;
              else delete next[item.id];
            } else {
              delete next[item.id];
            }
            return next;
          }),
        });
      }
      // A stock line has no cost at all (PricingDecisionService#approve exempts it from the cost
      // gate and overrideItemCost 400s it), so it gets no cost badges and no ต้นทุน disclosure.
      const showCost = !isStockLine;
      // Enable บันทึกราคา only for a real change: a ราคาตั้ง draft equal to the effective value, or a
      // blank one with nothing to clear (a stock line, or an import line with no override), saves
      // nothing, so it must not light the button up.
      const listChanged = listDraft !== undefined && !listInvalid && (listDraft === ''
        ? (!isStockLine && hasPriceOverride)
        : currentList == null || listDraftNumber !== Number(currentList));
      const otherDraftDirty = Object.keys(draft).some((k) => k !== 'listUnitPrice');
      // A stock line's ราคาตั้ง saved under DIRECT_NET/SPECIAL_SQM makes applyItemUpdates re-derive
      // the net, which 400s while that mode's own input is still empty — so ask for it first.
      const modeInputBlank = (value) => value === '' || value == null;
      const missingModeInputForList = listChanged && isStockLine && (
        (decision.priceMode === 'DIRECT_NET' && modeInputBlank(draft.directNetPrice ?? item.directNetPrice))
        || (decision.priceMode === 'SPECIAL_SQM' && modeInputBlank(draft.specialPriceSqm ?? item.specialPriceSqm)));
      const saveDisabled = listInvalid || missingModeInputForList || !(otherDraftDirty || listChanged);
      const costForcedOpen = showCost && Boolean(costingItem?.uncostableReason || costingItem?.overrideStale);
      const listPriceField = (
        <div className="flex flex-col gap-1">
          <FormField label="ราคาตั้ง/แผ่น" htmlFor={`pcr-ceo-list-price-${item.id}`}>
            <input
              id={`pcr-ceo-list-price-${item.id}`}
              type="number"
              min="0"
              max={PRICE_INPUT_MAX_12_2}
              step="0.01"
              disabled={!editable}
              value={listInputValue}
              onChange={(e) => updateCeoPriceDraft(item.id, { listUnitPrice: e.target.value })}
              data-testid={`pcr-ceo-list-price-${item.id}`}
            />
          </FormField>
          {listInvalid ? (
            <span role="alert" className="text-2xs text-danger">ราคาตั้งต้องมากกว่า 0</span>
          ) : null}
          {missingModeInputForList ? (
            <span role="alert" className="text-2xs text-danger">
              {decision.priceMode === 'DIRECT_NET'
                ? 'กรอกราคาสุทธิต่อแผ่นด้วยก่อนบันทึก'
                : 'กรอกราคาพิเศษ บาท/ตร.ม. ด้วยก่อนบันทึก'}
            </span>
          ) : null}
          {item.proposedSellingPricePerRequestedUnit != null ? (
            <span className="text-2xs text-text-muted">
              สูตร: {formatCurrency(item.proposedSellingPricePerRequestedUnit, decision.currency)} / แผ่น
              {formulaPricePerSqm != null ? ` · ${formatCurrency(formulaPricePerSqm, decision.currency)} / ตร.ม.` : ''}
            </span>
          ) : null}
          {hasPriceOverride && !isStockLine ? (
            <span className="flex flex-wrap items-center gap-1.5 text-2xs">
              <span className="font-bold text-override">ปรับเอง</span>
              {editable ? (
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  loading={clearingThisOverride}
                  disabled={savingThisItem}
                  onClick={() => saveThisItem({ clearSellingPriceOverride: true })}
                  data-testid={`pcr-ceo-use-formula-price-${item.id}`}
                >
                  ใช้ราคาตามสูตร
                </Button>
              ) : null}
            </span>
          ) : null}
          {decision.priceMode !== 'NET' ? (
            <span className="text-2xs text-text-muted">ต้องมีราคาตั้งสำหรับรายการสต็อก</span>
          ) : null}
        </div>
      );
      const netAndTotal = (
        <div className="flex flex-col gap-1">
          <span className="text-xs text-text-muted">
            ราคาสุทธิ/แผ่น (ก่อน VAT):{' '}
            {showingUncomputedPreview ? (
              <span className="italic text-text-muted">คำนวณเมื่อบันทึก</span>
            ) : (
              <code className="font-bold text-text">{formatCurrency(displayNet, decision.currency)}</code>
            )}
            {showingUnsavedPreview ? (
              <span className="ml-1 text-2xs text-text-muted">(ตัวอย่าง ยังไม่บันทึก)</span>
            ) : null}
          </span>
          <span className="text-[length:var(--text-base)] font-bold text-text">
            รวมเป็นเงิน: {lineTotal != null ? formatCurrency(lineTotal, decision.currency) : '-'}
          </span>
        </div>
      );
      return (
        <div key={item.id} className="mt-2 flex flex-col gap-2 border-t border-border pt-2">
          <div className="flex flex-wrap items-center gap-2 text-xs text-text-muted">
            {item.netUnitPrice == null ? (
              <StatusBadge tone="warning">ยังไม่มีราคา</StatusBadge>
            ) : (
              <StatusBadge tone="success">มีราคาแล้ว</StatusBadge>
            )}
            {showCost && costingItem?.overrideStale ? <StatusBadge tone="warning">ต้นทุนที่ปรับล้าสมัย</StatusBadge> : null}
            {/* V156: the freight table could not be looked up for this line, so it arrives with NO
                cost — the CEO must supply one with "ปรับต้นทุนเอง" (inside ต้นทุน below, which
                opens itself for exactly this case). */}
            {showCost && costingItem?.uncostableReason ? (
              <StatusBadge tone="warning">ต้องระบุต้นทุนเอง</StatusBadge>
            ) : null}
          </div>
          {decision.priceMode == null ? (
            <p className="m-0 text-xs text-text-muted">เลือกวิธีกรอกราคากระเบื้องด้านบนก่อน</p>
          ) : (
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {decision.priceMode === 'NET' ? (
                <>
                  {listPriceField}
                  <FormField label="ส่วนลด %" htmlFor={`pcr-ceo-discount-${item.id}`}>
                    <div className="flex items-center gap-1.5">
                      <input
                        id={`pcr-ceo-discount-${item.id}`}
                        type="number"
                        min="0"
                        max="100"
                        step="0.01"
                        className="flex-1"
                        disabled={!editable}
                        value={draft.discountPct ?? item.discountPct ?? ''}
                        onChange={(e) => updateCeoPriceDraft(item.id, { discountPct: e.target.value })}
                        data-testid={`pcr-ceo-discount-${item.id}`}
                      />
                      {/* Review finding #6: an explicit clear -- blanking the input and saving sends
                          discountPct: null, which a plain COALESCE would read as "unchanged", not
                          "reset to 0". */}
                      {editable && item.discountPct != null ? (
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          loading={clearingThisDiscount}
                          onClick={() => saveThisItem({ clearDiscountPct: true })}
                          data-testid={`pcr-ceo-clear-discount-${item.id}`}
                        >
                          ล้างส่วนลด
                        </Button>
                      ) : null}
                    </div>
                  </FormField>
                  {netAndTotal}
                </>
              ) : null}
              {decision.priceMode === 'SPECIAL_SQM' ? (
                <>
                  {isStockLine ? listPriceField : null}
                  <FormField
                    label="ราคาพิเศษ บาท/ตร.ม. (รวม VAT)"
                    htmlFor={`pcr-ceo-special-sqm-${item.id}`}
                    hint={item.sqmPerPiece != null ? `ตร.ม./แผ่น ${item.sqmPerPiece}` : 'รายการนี้ไม่มี ตร.ม./แผ่น จึงใช้โหมดนี้ไม่ได้'}
                  >
                    <input
                      id={`pcr-ceo-special-sqm-${item.id}`}
                      type="number"
                      min="0"
                      max={PRICE_INPUT_MAX_12_2}
                      step="0.01"
                      disabled={!editable || item.sqmPerPiece == null}
                      value={draft.specialPriceSqm ?? item.specialPriceSqm ?? ''}
                      onChange={(e) => updateCeoPriceDraft(item.id, { specialPriceSqm: e.target.value })}
                      data-testid={`pcr-ceo-special-sqm-${item.id}`}
                    />
                  </FormField>
                  {netAndTotal}
                </>
              ) : null}
              {decision.priceMode === 'DIRECT_NET' ? (
                <>
                  {isStockLine ? listPriceField : null}
                  <FormField label="ราคาสุทธิต่อแผ่น" htmlFor={`pcr-ceo-direct-net-${item.id}`}>
                    <input
                      id={`pcr-ceo-direct-net-${item.id}`}
                      type="number"
                      min="0"
                      max={PRICE_INPUT_MAX_14_2}
                      step="0.01"
                      disabled={!editable}
                      value={draft.directNetPrice ?? item.directNetPrice ?? ''}
                      onChange={(e) => updateCeoPriceDraft(item.id, { directNetPrice: e.target.value })}
                      data-testid={`pcr-ceo-direct-net-${item.id}`}
                    />
                  </FormField>
                  {netAndTotal}
                </>
              ) : null}
            </div>
          )}
          {editable ? (
            <Button
              type="button"
              variant="secondary"
              size="sm"
              className="self-start"
              disabled={saveDisabled}
              loading={savingThisItem && !clearingThisDiscount && !clearingThisOverride}
              onClick={() => saveThisItem()}
              data-testid={`pcr-ceo-save-price-${item.id}`}
            >
              บันทึกราคา
            </Button>
          ) : null}
          {/* Cost controls. Collapsed by default, but forced open for a line that has a cost
              BLOCKER (no auto cost, or a stale override) so the thing stopping approval is never
              hidden. `key` remounts the section when that flag flips, because CollapsibleSection
              reads defaultOpen only on first mount (the costing query can land after this card). */}
          {showCost ? (
            <CollapsibleSection
              key={`cost-${costForcedOpen}`}
              title="ต้นทุน"
              /* Owner decision 2026-10-01: open by default. The CEO was seeing only the calculated
                 price with the cost basis hidden one click away, so could not tell what the price
                 was computed from. costForcedOpen stays the remount key (a problem still forces it
                 open across a re-render); the base state is now simply open. */
              defaultOpen
              id={`pcr-ceo-derivation-${item.id}`}
            >
              <div className="flex flex-col gap-2 text-xs">
                {/* The factory's own quoted price EXACTLY as import recorded it — the number the
                    landed cost (and therefore the CEO's price) is built from. Shown so the CEO can
                    see WHAT the cost was computed from, and spot an import entry that disagrees with
                    the catalogue preliminary. Read straight off costingItem (in scope above) + one
                    × fxRate, never a re-run of the costing math. */}
                {costingItem?.rawUnitPrice != null ? (
                  <span className="text-text-muted" data-testid={`pcr-ceo-raw-factory-price-${item.id}`}>
                    ราคาโรงงานที่ฝ่ายนำเข้ากรอก:{' '}
                    <code>{formatCurrency(costingItem.rawUnitPrice, costingItem.rawCurrency)}</code>
                    {costingItem.fxRate != null && Number(costingItem.fxRate) > 0 ? (
                      <> (≈ <code>{formatCurrency(round2(Number(costingItem.rawUnitPrice) * Number(costingItem.fxRate)), 'THB')}</code>{' '}ที่ FX {costingItem.fxRate})</>
                    ) : null}
                  </span>
                ) : null}
                <span className="text-text-muted">
                  ต้นทุนโรงงาน (ฐาน):{' '}
                  {costingItem?.uncostableReason ? (
                    <span className="font-bold text-warning">คำนวณอัตโนมัติไม่ได้</span>
                  ) : (
                    <code>{formatCurrency(item.frozenLandedCostPerRequestedUnitThb, 'THB')}</code>
                  )}
                </span>
                {costingItem?.uncostableReason ? (
                  <p className="m-0 text-warning">{costingItem.uncostableReason}</p>
                ) : null}
                {editable ? (
                  <div className="flex flex-wrap items-center gap-2">
                    <Button
                      type="button"
                      variant="secondary"
                      size="sm"
                      onClick={() => setCostOverrideItem({ decision, item, costingItem })}
                      data-testid={`pcr-ceo-cost-override-${item.id}`}
                    >
                      {hasCostOverride ? 'แก้ไขต้นทุนที่ปรับ' : 'ปรับต้นทุนเอง'}
                    </Button>
                  </div>
                ) : null}
                {costingItem ? (
                  <div className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-md border border-border-subtle bg-surface-subtle p-2">
                    <span>
                      ต้นทุนคำนวณ/ชิ้น:{' '}
                      <code className="text-info">{formatCurrency(costingItem.landedCostPerUnitThb, 'THB')}</code>
                    </span>
                    {hasCostOverride ? (
                      <span className="flex min-w-0 items-baseline gap-1.5">
                        ต้นทุนที่ปรับ/ชิ้น:{' '}
                        <code className="font-bold text-override">{formatCurrency(costingItem.manualLandedCostPerUnitThb, 'THB')}</code>
                        <span className="text-2xs text-override">ปรับเอง</span>
                        {costingItem.overrideReason ? (
                          <span
                            className="min-w-0 max-w-[220px] truncate text-2xs text-text-muted"
                            title={costingItem.overrideReason}
                          >
                            ({costingItem.overrideReason})
                          </span>
                        ) : null}
                      </span>
                    ) : null}
                  </div>
                ) : null}
                {costingItem?.overrideStale ? (
                  <p className="m-0 text-2xs text-warning-dark">
                    อัตราแลกเปลี่ยนหรือค่าคำนวณเปลี่ยนไปหลังปรับต้นทุน — ต้องคำนวณต้นทุนใหม่หรือยืนยันค่าที่ปรับอีกครั้งก่อนอนุมัติ
                  </p>
                ) : null}
                <p className="m-0 text-2xs text-text-muted">
                  ราคาตั้ง (สูตร) เป็นค่าอ้างอิงตามสูตรของ CEO — ราคาที่ใช้อนุมัติจริงคือราคาสุทธิที่คำนวณจากวิธีกรอกราคาด้านบน
                </p>
              </div>
            </CollapsibleSection>
          ) : null}
        </div>
      );
    }
    return (
      <div key={item.id} className="rounded-md border border-border-subtle p-3">
        <div className="flex flex-wrap items-center gap-2 text-xs text-text-muted">
          <strong className="text-text">{[item.brand, item.model].filter(Boolean).join(' ') || item.productDescription || '-'}</strong>
          <span>{item.factoryName ?? '-'}</span>
          <span>{item.requestedQuantity} ({item.requestedUnitBasis})</span>
          {hasPriceOverride ? <span className="font-bold text-override">ราคาปรับเอง</span> : null}
          {costingItem?.overrideStale ? <StatusBadge tone="warning">ต้นทุนที่ปรับล้าสมัย</StatusBadge> : null}
          {/* V156: the freight table could not be looked up for this line (the
              Price Catalog row has no thickness or no origin country), so it
              arrives with NO cost instead of blocking the whole costing. The CEO
              must supply one with "ปรับต้นทุน" before the decision can be
              approved — approve() refuses otherwise. */}
          {costingItem?.uncostableReason ? (
            <StatusBadge tone="warning">ต้องระบุต้นทุนเอง</StatusBadge>
          ) : null}
        </div>
        {/* The two numbers, read-only, asking for nothing. */}
        <div className="mt-2 grid gap-x-6 gap-y-1 sm:grid-cols-2">
          <span className="text-xs text-text-muted">
            ต้นทุนโรงงาน (ฐาน):{' '}
            {costingItem?.uncostableReason ? (
              <span className="font-bold text-warning">คำนวณอัตโนมัติไม่ได้</span>
            ) : (
              <code>{formatCurrency(item.frozenLandedCostPerRequestedUnitThb, 'THB')}</code>
            )}
          </span>
          <span className="text-[length:var(--text-base)] font-bold text-text">
            ราคาขาย (ก่อน VAT): {formatCurrency(effectivePrice, decision.currency)}
          </span>
        </div>
        {costingItem?.uncostableReason ? (
          <p className="mt-2 text-xs text-warning">{costingItem.uncostableReason}</p>
        ) : null}
        {!editable ? (
          <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
            <span>อัตรากำไร: {effectiveMargin ?? '-'}</span>
            <span>ราคาขั้นต่ำ: {item.minimumSellingPricePerRequestedUnit != null ? formatCurrency(item.minimumSellingPricePerRequestedUnit, decision.currency) : '-'}</span>
          </div>
        ) : null}
        <CollapsibleSection
          title="วิธีคำนวณราคานี้"
          defaultOpen={false}
          id={`pcr-ceo-derivation-${item.id}`}
        >
          <div className="flex flex-col gap-2 text-xs">
            {costingItem ? (
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-md border border-border-subtle bg-surface-subtle p-2">
                <span>
                  ต้นทุนคำนวณ/ชิ้น:{' '}
                  <code className="text-info">{formatCurrency(costingItem.landedCostPerUnitThb, 'THB')}</code>
                </span>
                {hasCostOverride ? (
                  <span className="flex min-w-0 items-baseline gap-1.5">
                    ต้นทุนที่ปรับ/ชิ้น:{' '}
                    <code className="font-bold text-override">{formatCurrency(costingItem.manualLandedCostPerUnitThb, 'THB')}</code>
                    <span className="text-2xs text-override">ปรับเอง</span>
                    {costingItem.overrideReason ? (
                      <span
                        className="min-w-0 max-w-[220px] truncate text-2xs text-text-muted"
                        title={costingItem.overrideReason}
                      >
                        ({costingItem.overrideReason})
                      </span>
                    ) : null}
                  </span>
                ) : null}
                {editable ? (
                  <Button
                    type="button"
                    variant="secondary"
                    className="text-2xs px-2 py-[3px]"
                    onClick={() => setCostOverrideItem({ decision, item, costingItem })}
                    data-testid={`pcr-ceo-cost-override-${item.id}`}
                  >
                    {hasCostOverride ? 'แก้ไขต้นทุนที่ปรับ' : 'ปรับต้นทุนเอง'}
                  </Button>
                ) : null}
              </div>
            ) : null}
            {costingItem?.overrideStale ? (
              <p className="m-0 text-2xs text-warning-dark">
                อัตราแลกเปลี่ยนหรือค่าคำนวณเปลี่ยนไปหลังปรับต้นทุน — ต้องคำนวณต้นทุนใหม่หรือยืนยันค่าที่ปรับอีกครั้งก่อนอนุมัติ
              </p>
            ) : null}
            <div className="rounded-md border border-border-subtle p-2">
              <p className="m-0 font-bold text-text">สูตรคำนวณราคาขาย</p>
              {hasPriceOverride ? (
                <p className="m-0 mt-1">
                  ราคานี้ถูก <span className="font-bold text-override">ปรับเอง</span> เป็น{' '}
                  <code className="font-bold text-override">{formatCurrency(item.manualSellingPricePerRequestedUnit, decision.currency)}</code>
                  {' '}— สูตรด้านล่างไม่ได้ใช้คำนวณราคานี้อีกต่อไป
                </p>
              ) : null}
              {/* Owner ruling 2026-09-19 (Phase 2 CEO pricing): SP is no longer
                  rounded UP to a ฿ multiple — it rounds HALF_UP to 2dp, full
                  stop. selling_price_round_up_to has no effect on this figure
                  any more (see CeoSettingsPage's own removal of the field). */}
              <p className="m-0 mt-1">
                ราคาขาย/หน่วยที่ขอ = ปัดทศนิยม 2 ตำแหน่ง[ ต้นทุน/หน่วยที่ขอ × (1 + อัตรากำไร) × ตัวคูณราคาขาย ]
                {decision.currency !== 'THB' ? ' ÷ อัตราแลกเปลี่ยน' : ''}
              </p>
              <p className="m-0 mt-1">
                = ปัดทศนิยม 2 ตำแหน่ง[{formatCurrency(item.frozenLandedCostPerRequestedUnitThb, 'THB')} × (1 + {item.proposedMarginPct ?? '-'}) × {formulaConfigQuery.data?.sellingBuffer ?? '-'}]
                {decision.currency !== 'THB' ? ` ÷ ${decision.fxRateUsed}` : ''}
                {' = '}
                <code>{formatCurrency(item.proposedSellingPricePerRequestedUnit, decision.currency)}</code>
              </p>
              <p className="m-0 mt-1 text-2xs text-text-muted">
                ตัวคูณราคาขายเป็นค่าบัฟเฟอร์ต้นทุน ไม่ใช่ VAT — ใบเสนอราคาจะเพิ่ม VAT 7% แยกต่างหากอีกขั้นหนึ่ง
              </p>
              {decision.currency !== 'THB' ? (
                <p className="m-0 mt-1 text-2xs text-text-muted">
                  อัตราแลกเปลี่ยน {decision.fxRateUsed} ({decision.fxSource}, {decision.fxEffectiveDate})
                </p>
              ) : null}
            </div>
            {editable ? (
              <Button
                type="button"
                variant="secondary"
                className="self-start text-2xs px-2 py-[3px]"
                onClick={() => setPriceOverrideItem({ decision, item })}
                data-testid={`pcr-ceo-price-override-${item.id}`}
              >
                {hasPriceOverride ? 'แก้ไขราคาที่ปรับ' : 'ปรับราคาเอง'}
              </Button>
            ) : null}
          </div>
        </CollapsibleSection>
      </div>
    );
  }

  function renderCeoDecisionActions({ decision, editable, missingBeforeApprove, staleOverrideItems, newForm, missingCeoPrice, missingCost, missingStockListPrice }) {
    if (!editable) return null;
    return (
      <>
        <div className="mt-3 flex flex-col gap-2 border-t border-border-subtle pt-3">
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="primary"
              disabled={approveDecision.isPending || missingBeforeApprove.length > 0
                || staleOverrideItems.length > 0 || missingCeoPrice.length > 0
                || missingCost.length > 0 || missingStockListPrice.length > 0
                || (newForm && decision.priceMode == null)}
              onClick={() => setConfirmAction({ type: 'approveDecision', decision })}
              data-testid="pcr-ceo-approve"
            >
              อนุมัติราคาขาย
            </Button>
            <Button
              type="button"
              variant="secondary"
              disabled={returnDecisionToImport.isPending}
              onClick={() => setConfirmAction({ type: 'returnDecision', decision })}
            >
              ตีกลับให้ฝ่ายนำเข้าแก้ไข
            </Button>
          </div>
          {missingBeforeApprove.length > 0 ? (
            <span className="text-xs text-danger">ทุกรายการต้องมีอัตรากำไรก่อนอนุมัติ (หรือปรับราคาเอง)</span>
          ) : null}
          {newForm && decision.priceMode == null ? (
            <span className="text-xs text-danger">กรุณาเลือกวิธีกรอกราคาก่อนอนุมัติ</span>
          ) : null}
          {missingCeoPrice.length > 0 ? (
            <span className="text-xs text-danger">ทุกรายการต้องมีราคาตามวิธีกรอกราคาที่เลือกก่อนอนุมัติ</span>
          ) : null}
          {missingCost.length > 0 ? (
            <span className="text-xs text-danger">
              {newForm
                ? 'ทุกรายการต้องมีต้นทุนก่อนอนุมัติ — กรุณาระบุต้นทุนเอง (ดู "ต้นทุน" ของรายการที่ต้องระบุต้นทุนเอง) หรือกรอกราคาตั้งเองในโหมดราคาตั้ง − ส่วนลด %'
                : 'ทุกรายการต้องมีต้นทุนก่อนอนุมัติ — กรุณาระบุต้นทุนเอง หรือปรับราคาตั้งเอง (ดู "วิธีคำนวณราคานี้" ของรายการที่ต้องระบุต้นทุนเอง)'}
            </span>
          ) : null}
          {missingStockListPrice.length > 0 ? (
            <span className="text-xs text-danger">รายการจากสต็อกต้องมีราคาตั้ง (มากกว่า 0) ก่อนอนุมัติ</span>
          ) : null}
          {staleOverrideItems.length > 0 ? (
            <span className="text-xs text-danger">
              มีรายการที่ปรับต้นทุนเองล้าสมัย — กรุณาคำนวณต้นทุนใหม่ หรือยืนยันค่าที่ปรับอีกครั้งก่อนอนุมัติ
            </span>
          ) : null}
        </div>
      </>
    );
  }

  function renderCeoNoteAndError() {
    return (
      <>
        {/* P2 fix (2026-09): PricingDecisionService.computeSellingPrice has no VAT term at
            all — VAT 7% is added later, only on the customer quotation. This was previously
            only hinted at inside the per-item "วิธีคำนวณราคานี้" disclosure (about the
            multiplier, not about the price itself), so state it plainly and visibly here
            first, matching the "(ก่อน VAT)" convention the ใบเสนอราคา summary below already
            uses for ยอดรวม. */}
        <p className="m-0 flex items-start gap-2 rounded-lg border border-info-border bg-info-bg px-3 py-2.5 text-xs text-info-dark">
          <Icon name="info" size={15} className="mt-0.5 shrink-0" />
          ราคาขายทุกรายการในหน้านี้เป็นราคาก่อน VAT — ใบเสนอราคาจะบวก VAT 7% แยกอีกชั้นหนึ่ง
        </p>
        {/* P0/P1a fix (2026-09): startCeoReview/recalculateDecisionCost's error is rendered
            here — inline, persistent, whitespace-pre-line — instead of (or in addition to,
            see the mutations' own comments) the toast. See ceoCostingError's declaration for
            why one block covers both controls. */}
        {ceoCostingError ? (
          <div
            role="alert"
            data-testid="pcr-ceo-costing-error"
            className="flex items-start gap-2.5 rounded-md border border-danger-border bg-danger-bg p-3"
          >
            <Icon name="triangleAlert" size={16} className="mt-0.5 shrink-0 text-danger" />
            <p className="m-0 min-w-0 flex-1 whitespace-pre-line text-sm font-bold text-danger">
              {ceoCostingError}
            </p>
            <Button
              type="button"
              variant="icon"
              size="sm"
              className="shrink-0 border-transparent bg-transparent text-danger"
              title="ปิดข้อความนี้"
              onClick={() => setCeoCostingError(null)}
            >
              <Icon name="close" size={16} />
            </Button>
          </div>
        ) : null}
      </>
    );
  }

  function renderCeoStartReview() {
    return (
      <>
        {!currentDecision && canStartCeoReview(user, summary) ? (
          <div className="flex flex-wrap items-center gap-2">
            <label className="text-xs text-text-muted">
              อัตรากำไรเริ่มต้น
              <input
                className="form-input ml-2 w-24"
                value={resolvedDefaultMargin}
                onChange={(e) => setDecisionDefaultMargin(e.target.value)}
                placeholder="0.30"
              />
            </label>
            <Button
              type="button"
              variant="primary"
              loading={startCeoReview.isPending}
              // Wait for the formula config, so an early click cannot start the review at the
              // 0.30 fallback instead of the CEO's configured default. A failed read is not
              // pending, so the fallback still applies then.
              disabled={decisionDefaultMargin == null && formulaConfigQuery.isPending}
              onClick={() => startCeoReview.mutate()}
              data-testid="pcr-ceo-start-review"
            >
              {startCeoReview.isPending ? 'กำลังคำนวณ…' : 'เริ่มพิจารณาราคาขาย'}
            </Button>
          </div>
        ) : null}
        {!currentDecision && !canStartCeoReview(user, summary) ? (
          <p className="text-sm text-text-muted">ยังไม่มีการพิจารณาราคาขาย</p>
        ) : null}
      </>
    );
  }

  function renderCeoHistory() {
    if (pricingDecisions.length <= 1) return null;
    return (
      <div className="text-xs text-text-muted">
        ประวัติ: {pricingDecisions.map((d) => {
          const status = pricingDecisionStatusLabel(d.status);
          return `เวอร์ชัน ${d.decisionVersionNo} (${status.label})`;
        }).join(' · ')}
      </div>
    );
  }

  // Which decision item prices which request card. The decision item carries its request item's id
  // (PricingDecisionItemDto#pricingRequestItemId); index order is only the fallback for a DTO that
  // omits it. A decision item that matches no card is rendered after the cards, never dropped.
  const ceoState = ceoWorkspace && currentDecision ? ceoDecisionState(currentDecision) : null;
  const ceoPricingByRequestItem = new Map();
  const ceoUnmatchedDecisionItems = [];
  if (ceoState?.newForm) {
    const decisionItems = ceoState.decision.items;
    const requestItems = request.items ?? [];
    const usedIds = new Set();
    requestItems.forEach((requestItem, index) => {
      const matched = decisionItems.every((d) => d.pricingRequestItemId != null)
        ? decisionItems.find((d) => !usedIds.has(d.id) && d.pricingRequestItemId === requestItem.id)
        : decisionItems[index];
      if (matched) {
        usedIds.add(matched.id);
        ceoPricingByRequestItem.set(requestItem.id, matched);
      }
    });
    decisionItems.forEach((d) => { if (!usedIds.has(d.id)) ceoUnmatchedDecisionItems.push(d); });
  }
  // Sum of the SAVED line totals only (a draft preview is not a price yet); lines with no net are
  // simply left out, and the label says so.
  const ceoGrandTotal = ceoState?.newForm
    ? ceoState.decision.items.reduce((sum, d) => (d.netUnitPrice != null
      ? sum + round2(Number(d.requestedQuantity) * Number(d.netUnitPrice)) : sum), 0)
    : null;

  return (
    <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
      <PageHeader
        title={summary.requestCode}
        subtitle={`${summary.customerName ?? '-'}${summary.projectName ? ` · ${summary.projectName}` : ''}`}
        actions={(
          <>
            {canPickupPricingRequest(user, summary) ? (
              <Button type="button" variant="primary" disabled={pickupRequest.isPending}
                onClick={() => pickupRequest.mutate()} data-testid="pcr-detail-pickup">
                รับเรื่อง
              </Button>
            ) : null}
            <Button type="button" variant="secondary" onClick={() => navigate(-1)}>
              <Icon name="chevronLeft" size={14} />
              กลับ
            </Button>
          </>
        )}
      />

      {/* CR-1 (R2/R10, B-R3): a pending lead-time change waits for the OWNING rep or a sales manager —
          one banner per request, deciding the whole request at once. The CEO and import never see
          decide controls (the CEO sees only the read-only badge on the factory card). */}
      {canDecideLeadTime && leadTimeSplit.pending.length > 0 ? (
        <div className="grid gap-3" data-testid="pcr-lt-banners">
          {leadTimeSplit.pending.map((change) => {
            const firstItem = (request?.items ?? []).find((item) => item.id === change.lines[0]?.pricingRequestItemId);
            return (
              <LeadTimeChangeBanner
                key={change.id}
                change={change}
                factoryName={itemFactoryName(firstItem) ?? '-'}
                lineNames={lineNameById}
                pending={approveLeadTimeChange.isPending || rejectLeadTimeChange.isPending}
                onApprove={(target) => approveLeadTimeChange.mutate(target)}
                onReject={(target, reason) => rejectLeadTimeChange.mutate({ change: target, reason })}
              />
            );
          })}
        </div>
      ) : null}

      <Panel flush title="ภาพรวม" actions={<StatusBadge tone={status.tone}>{status.label}</StatusBadge>}>
        <div className="grid gap-3 p-4 md:grid-cols-2">
          <div className="text-sm"><strong>ดีล</strong> <Link to={user?.role === 'import' ? `/import/deals/${summary.ticketId}` : `/tickets/${summary.ticketId}`} className="text-info underline">{summary.ticketCode}</Link></div>
          <div className="text-sm"><strong>ผู้รับ</strong> {pricingRequestRecipientLabel(summary.recipientType)}{summary.recipientLabel ? ` · ${summary.recipientLabel}` : ''}</div>
          <div className="text-sm"><strong>ต้องการภายใน</strong> {formatThaiDate(summary.requiredDate)}</div>
          <div className="text-sm"><strong>ฝ่ายนำเข้า</strong> ผู้รับเรื่องและประสานราคาโรงงาน</div>
        </div>
        {/* GLA-125 (owner ruling 2026-09-18): header terms, read-only for every viewer including
            Import — they carry no price or discount, only when/how the eventual quotation would
            be paid for and printed. Rendered only when at least one is actually set, so a
            pre-GLA-125 request (every field null) shows nothing extra here. NOT yet reflected on
            the quotation itself — see V185's migration header (Phase 3). */}
        {(summary.paymentTermMode || summary.validityDays != null || summary.deptCode
          || summary.unitCode || summary.printedByDisplayId != null || summary.salesRepDisplayId != null
          || summary.omitContactHonorific) ? (
          <div className="grid gap-x-4 gap-y-1 border-t border-border p-4 pt-3 text-xs text-text-muted sm:grid-cols-3">
            <span>เงื่อนไขการชำระเงิน: {summary.paymentTermMode === 'CREDIT'
              ? `เครดิต ${summary.creditDays ?? '—'} วัน`
              : summary.paymentTermMode === 'ON_DELIVERY' ? 'ชำระเมื่อส่งมอบ' : '—'}</span>
            <span>ยืนราคา: {summary.validityDays != null ? `${summary.validityDays} วัน` : '—'}</span>
            <span>ฝ่าย: {summary.deptCode || '—'}</span>
            <span>หน่วยงาน / รหัสผู้ออกแบบ: {summary.unitCode || '—'}</span>
            <span>ไม่เติม &quot;คุณ&quot; หน้าชื่อผู้รับ: {summary.omitContactHonorific ? 'ใช่' : 'ไม่ใช่'}</span>
          </div>
        ) : null}
      </Panel>

      <Panel
        flush
        title="รายการสินค้าและราคาตั้งต้น"
        actions={ceoWorkspace && currentDecision && decisionEditable ? (
          <Button
            type="button"
            variant="icon"
            size="sm"
            title={recalculateDecisionCost.isPending
              ? 'กำลังคำนวณต้นทุนใหม่…'
              : 'คำนวณต้นทุนใหม่ — ดึงต้นทุนและอัตราแลกเปลี่ยนล่าสุด (ไม่ลบค่าที่ปรับเองไว้)'}
            loading={recalculateDecisionCost.isPending}
            onClick={() => recalculateDecisionCost.mutate(currentDecision)}
            data-testid="pcr-ceo-recalculate-cost"
          >
            <Icon name="refresh" size={16} />
          </Button>
        ) : null}
      >
        {/* CEO pricing workspace (owner Ploy, 2026-10-01): this panel is where the CEO prices the
            request — decision header, ก่อน VAT note, costing error and the price-mode picker on
            top; a pricing row inside each item card; totals, approve/return and history below. */}
        {ceoWorkspace ? (
          <div className="flex flex-col gap-3 border-b border-border-subtle p-4">
            {ceoState ? renderCeoDecisionHeader(ceoState) : null}
            {renderCeoNoteAndError()}
            {ceoState?.newForm ? renderCeoPriceModePicker(ceoState) : null}
          </div>
        ) : null}
        <div className="flex flex-col gap-2 p-4">
          {/* The blocking condition, stated BEFORE the สร้างร่างอีเมล button is pressed. It used to
              be discoverable only by pressing it and reading a 422 that named the row's primary
              key — a number that appears nowhere on this page.
              Manual-RFQ redesign: generateDrafts no longer refuses the whole batch over one
              unresolved line — it drafts every factory that DOES resolve and simply skips the
              rest (see FactoryQuoteService.generateDrafts's own doc comment). This banner used to
              say a draft could not be created "until every line is filled in", which stopped being
              true the moment that changed; it now says what actually happens: partial drafts, plus
              which lines still block a QUOTE (not a draft) until they get a factory. */}
          {missingFactoryItems.length ? (
            <p className="rounded-md border border-warning-border bg-warning-bg p-3 text-xs text-warning-dark">
              {`ยังไม่ได้ระบุโรงงาน ${missingFactoryItems.length} รายการ — ระบบจะสร้างเมลให้เฉพาะรายการที่ระบุโรงงานแล้ว ส่วนรายการต่อไปนี้ต้องระบุโรงงานก่อนจึงจะขอราคาได้: `}
              {missingFactoryItems.map((entry) => `รายการที่ ${entry.position} (${itemDisplayName(entry.item)})`).join(', ')}
              {canSetItemFactory
                ? ' — เลือกโรงงานในรายการด้านล่างแล้วกดบันทึก'
                : ' — ฝ่ายนำเข้าเป็นผู้ระบุโรงงานให้ในขั้นตอนนี้'}
            </p>
          ) : null}
          {(request.items ?? []).map((item, index) => {
            const factoryName = itemFactoryName(item);
            // `position` is the 1-based row number the server counts too: findItems returns
            // ORDER BY sort_order, pricing_request_item_id and groupByFactory's sort is stable on
            // sortOrder, so "รายการที่ N" means this exact row on both sides.
            const position = index + 1;
            // Owner ruling 2026-09-18 (reversed from an earlier ยี่ห้อ ruling): the label on this
            // panel is โรงงาน, not ยี่ห้อ — matching the PCR form's own `brandLabel="โรงงาน"`
            // override (PricingRequestCreateModal.jsx). Two DIFFERENT values both say โรงงาน here,
            // so (Opus review finding #6, 2026-09-18) they are shown as two SEPARATE lines rather
            // than one falling back to the other:
            //   - `salesBrandValue`: exactly what Sales typed/picked (`brand`), never anything
            //     else — a card whose rep left this blank now correctly shows "—", not whatever
            //     factory routing happened to resolve to.
            //   - `factoryName` (below): the factory this line is actually ROUTED to — the
            //     catalog snapshot's resolvedFactoryName, or Import's own SetItemFactoryRequest
            //     gap-fill (both land in the SAME `factory` column via itemFactoryName's
            //     precedence) — shown on its own line, amber + "ยังไม่ได้ระบุ" when neither has
            //     set one yet, matching the owner's own suggested disambiguation ("the assigned
            //     one is the control, the sales value is read-only text beside it"). Import's OWN
            //     factory-assignment control below also says โรงงาน; this line is what tells
            //     Import (or anyone) what it currently holds without scrolling to the control.
            //     The underlying "is a factory resolved for routing" check (missingFactoryItems,
            //     canSetItemFactory, setItemFactory) is UNCHANGED.
            const salesBrandValue = item.brand?.trim() || null;
            const productCode = item.productCode?.trim() || item.catalogProductCode || null;
            // Owner ruling: nothing discount-related exists in Phase 1, and Import never sees
            // เผื่อ (wastage) or the pre-wastage quantity — only the FINAL order quantity (pieces
            // after wastage + full-box rounding, boxes, sqm equivalent). Sales/CEO/everyone else
            // keeps the full breakdown. UI scoping only — no backend authz change.
            const showWastageDetail = !isImport(user);
            return (
              <div
                key={item.id}
                className={cn(
                  'rounded-md border bg-surface p-3',
                  factoryName ? 'border-border' : 'border-warning-border',
                )}
              >
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-xs font-bold text-text-muted">{`รายการที่ ${position}`}</span>
                  <strong>{itemDisplayName(item)}</strong>
                  <span className="text-xs text-text-muted">{item.requestedQty} {item.requestedUnit}</span>
                </div>
                <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
                  <span>{`โรงงาน (ที่ฝ่ายขายกรอก): ${salesBrandValue ?? '—'}`}</span>
                  <span className={factoryName ? undefined : 'font-bold text-warning-dark'}>
                    {`โรงงานที่กำหนด (Import): ${factoryName ?? 'ยังไม่ได้ระบุ'}`}
                  </span>
                  <span>รหัสสินค้า: {productCode ?? '-'}</span>
                  <span>Base: {item.catalogBasePrice != null ? `${formatCurrency(item.catalogBasePrice, item.catalogCurrency ?? 'THB')} (preliminary)` : '-'}</span>
                </div>
                {/* V185: the sales-entered tile fields, read-only for every viewer — legacy
                    (pre-V185) items show "—" for whichever of these they never carried. */}
                <div className="mt-1.5 grid grid-cols-2 gap-x-4 gap-y-1 border-t border-border pt-1.5 text-xs text-text-muted sm:grid-cols-3">
                  <span>สี: {formatOrDash(item.color)}</span>
                  <span>ผิว: {formatOrDash(item.texture)}</span>
                  <span>ขนาด: {formatOrDash(item.size)}</span>
                  <span>ความหนา: {formatOrDash(item.thicknessMm, ' มม.')}</span>
                  <span>แผ่น/ตร.ม.: {formatPiecesPerSqm(item)}</span>
                  <span>แผ่น/กล่อง: {formatOrDash(item.piecesPerBox)}</span>
                  <span>ขายแผ่นไม่เต็มกล่อง: {item.piecesPerBox != null ? (item.roundToFullBox === false ? 'ใช่' : 'ไม่ใช่') : '—'}</span>
                  <span>
                    ประเทศต้นทาง: {formatOrDash(item.originCountry)}
                    {/* GLA-125 item 2: the typed name is the actual answer once origin_country
                        is the "อื่นๆ" sentinel -- showing just "อื่นๆ" alone would tell Import
                        nothing. */}
                    {item.originCountry === 'อื่นๆ' ? ` (${formatOrDash(item.originCountryOther)})` : ''}
                  </span>
                  <span>ระยะเวลานำเข้า: {formatLeadTime(item)}</span>
                </div>
                {/* Final order quantity — what Import must actually order — shown to EVERY
                    viewer: pieces after wastage + full-box rounding (the request's own
                    requestedQty/requestedUnit, already shown in the header above), plus boxes and
                    the ตร.ม. equivalent. */}
                <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 border-t border-border pt-1.5 text-xs text-text-muted">
                  <span className="font-bold text-text">จำนวนสั่งซื้อ: {item.requestedQty ?? '—'} {item.requestedUnit ?? ''}</span>
                  <span>กล่อง: {formatOrDash(item.boxes)}</span>
                  <span>เทียบ ตร.ม.: {formatOrDash(item.requestedQtySqm, ' ตร.ม.')}</span>
                </div>
                {/* เผื่อ (wastage) and the AS-ENTERED (pre-wastage) quantity — sales/CEO only, per
                    owner ruling 2026-09-18: Import sees only the final order quantity above. */}
                {showWastageDetail ? (
                  <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
                    <span>จำนวนที่กรอก: {formatQuantityAsEntered(item)}</span>
                    <span>เผื่อ (wastage): {formatWastage(item)}</span>
                  </div>
                ) : null}
                {ceoPricingByRequestItem.has(item.id)
                  ? renderCeoDecisionItem(ceoState.decision, ceoPricingByRequestItem.get(item.id), ceoState.editable)
                  : null}
                {/* Import's escape hatch. Only offered on a line that has NO factory: the backend
                    refuses to re-route one that does (a factory quote may already be grouped under
                    that name), so offering an editable value here would promise something the
                    service would 409.
                    B6 (GLA-135): a picker over the real factory master list, not a free-text
                    input — see ImportFactoryPicker's own comment for why. */}
                {/* QA BUG-20: a line that already has a factory offers no picker (re-routing is
                    refused by the backend — a factory quote may already be grouped under that name).
                    Rather than a silent dead-end, say WHY and what to do instead of guessing. */}
                {canSetItemFactory && factoryName ? (
                  <p className="mt-2 text-xs text-text-muted">
                    ต้องการเปลี่ยนโรงงาน? ต้องสร้างคำขอราคารอบใหม่ — ระบบล็อกไว้กันใบขอราคาที่จัดกลุ่มตามโรงงานเพี้ยน
                  </p>
                ) : null}
                {canSetItemFactory && !factoryName ? (
                  <ImportFactoryPicker
                    itemId={item.id}
                    factories={factoriesQuery.data ?? []}
                    countries={countriesQuery.data ?? []}
                    loading={factoriesQuery.isLoading}
                    loadFailed={factoriesQuery.isError}
                    value={factoryDrafts[item.id] ?? ''}
                    onChangeValue={(code) => setFactoryDrafts((current) => ({ ...current, [item.id]: code }))}
                    saving={setItemFactory.isPending}
                    onSave={() => setItemFactory.mutate(
                      { itemId: item.id, factoryId: Number(factoryDrafts[item.id]) },
                      { onSuccess: () => setFactoryDrafts((current) => {
                        const next = { ...current };
                        delete next[item.id];
                        return next;
                      }) },
                    )}
                    onFactoryAdded={(saved) => {
                      queryClient.invalidateQueries({ queryKey: queryKeys.priceImportFactories() });
                      setFactoryDrafts((current) => ({ ...current, [item.id]: String(saved.factoryId) }));
                      showToast?.('success', `เพิ่มโรงงาน "${saved.name}" แล้ว`);
                    }}
                  />
                ) : null}
              </div>
            );
          })}
          {ceoUnmatchedDecisionItems.map((d) => (
            <div key={d.id} className="rounded-md border border-border bg-surface p-3">
              <div className="flex flex-wrap items-center gap-2 text-xs text-text-muted">
                <strong className="text-text">{[d.brand, d.model].filter(Boolean).join(' ') || d.productDescription || '-'}</strong>
                <span>{d.requestedQuantity} แผ่น</span>
              </div>
              {renderCeoDecisionItem(ceoState.decision, d, ceoState.editable)}
            </div>
          ))}
          {ceoWorkspace && !currentDecision ? (
            <div className="flex flex-col gap-2 pt-1">{renderCeoStartReview()}</div>
          ) : null}
          {ceoState?.newForm ? (
            <div className="flex flex-wrap items-baseline justify-between gap-2 border-t border-border pt-2">
              <span className="text-xs text-text-muted">
                รวมทั้งสิ้น (ก่อน VAT) — เฉพาะรายการที่มีราคาแล้ว
              </span>
              <span className="text-[length:var(--text-base)] font-bold text-text">
                {formatCurrency(ceoGrandTotal, ceoState.decision.currency)}
              </span>
            </div>
          ) : null}
          {/* A LEGACY (non-new-form) decision keeps its existing card markup, relocated below the
              spec cards unchanged. */}
          {ceoState && !ceoState.newForm ? (
            <div className="mt-1 flex flex-col gap-3">
              {ceoState.decision.items.map((d) => renderCeoDecisionItem(ceoState.decision, d, ceoState.editable))}
            </div>
          ) : null}
          {ceoState ? renderCeoDecisionActions(ceoState) : null}
          {ceoWorkspace ? renderCeoHistory() : null}
        </div>
      </Panel>

      <Panel
        flush
        title="ไฟล์แนบประกอบคำขอราคา"
        actions={canEditPricingRequestAttachments ? (
          // Left as a <label> wrapping the hidden file input, not <Button>: a
          // <button> cannot open the native file picker the way a <label>
          // wrapping its <input type="file"> does.
          <label className={cn(buttonVariants({ variant: 'secondary' }), 'cursor-pointer')}>
            <input type="file" className="hidden" onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) uploadPricingRequestAttachment.mutate(file);
              event.target.value = '';
            }} />
            <Icon name="upload" size={13} />
            แนบไฟล์
          </label>
        ) : null}
      >
        <div className="flex flex-col gap-1 p-4 text-xs text-text-muted">
          {pricingRequestAttachments.map((attachment) => (
            <div key={attachment.id} className="flex flex-wrap items-center gap-2">
              <a className="text-info underline" href={api.pricingRequests.attachmentUrl(attachment.id)} target="_blank" rel="noreferrer">
                {attachment.fileName}
              </a>
              {isImport(user) ? (
                <label className="flex items-center gap-1">
                  <input
                    type="checkbox"
                    checked={Boolean(attachment.includeInFactoryEmail)}
                    disabled={toggleAttachmentIncludeInFactoryEmail.isPending}
                    onChange={() => toggleAttachmentIncludeInFactoryEmail.mutate(attachment)}
                  />
                  ส่งแนบไปกับอีเมลโรงงาน
                </label>
              ) : attachment.includeInFactoryEmail ? (
                <StatusBadge tone="neutral">แนบไปกับอีเมลโรงงาน</StatusBadge>
              ) : null}
              {canEditPricingRequestAttachments ? (
                <Button
                  type="button"
                  variant="icon"
                  aria-label={`ลบไฟล์แนบ ${attachment.fileName}`}
                  onClick={() => deletePricingRequestAttachment.mutate(attachment.id)}
                >
                  <Icon name="close" size={13} />
                </Button>
              ) : null}
            </div>
          ))}
          {pricingRequestAttachments.length === 0 ? <span>ยังไม่มีไฟล์แนบ</span> : null}
        </div>
      </Panel>

      {canCreateCustomerRevision ? (
        <Panel flush title="รอบแก้ไขตามการเปลี่ยนแปลงของลูกค้า">
          <div className="flex flex-wrap gap-2 p-4">
            <Button type="button" variant="secondary" onClick={() => setRevisionModalOpen(true)}>
              สร้างรอบแก้ไข
            </Button>
          </div>
        </Panel>
      ) : null}

      {/* Import-only since 2026-10-01 (was canSeeRaw: import+ceo): the CEO prices inside the
          รายการสินค้าและราคาตั้งต้น panel above and no longer gets the factory-quote workspace. */}
      {isImport(user) ? (
        <Panel
          flush
          title={`รายการสินค้า (${factoryItemCount} รายการ)`}
          // CR-1: only while some line still has no factory mail. Per-factory สร้างเมล lives on each card.
          actions={canContactFactory && inSendWindow && pricePreviewGroups.length > 0 ? (
            <Button type="button" variant="secondary" disabled={generateDrafts.isPending} onClick={() => generateDrafts.mutate()} data-testid="pcr-generate-drafts">
              สร้างเมลให้ทุกโรงงาน
            </Button>
          ) : null}
        >
          {/* Opus review of #1062 (2026-09-28): these two blocks used to be an if/else on
              `factoryGroups.length === 0`, so the preview vanished the instant ANY factory quote
              existed anywhere on the request — hiding an item whose factory was blank until Import
              gap-filled it (canSetItemFactory, during FACTORY_ROUTING_STATUSES — never a re-route of
              an already-set line, see setItemFactory's own Javadoc) AFTER drafts were already
              generated for other items; that item has no quote row of its own. They now render
              independently: the editable grid for whatever already has a quote, PLUS a preview for
              whatever still doesn't — which can both be true at once. */}
          {pricePreviewGroups.length > 0 ? (
            <div className="flex flex-col gap-3 p-4" data-testid="pcr-price-preview">
              {/* Opus review of #1062 (2026-09-28): "ยังไม่ได้สร้างร่างอีเมลขอราคา" (no draft has been
                  generated AT ALL) is false in the mixed case this block now also covers — it can
                  render ABOVE factory groups that already have a sent/answered quote, once another
                  item's factory was only just gap-filled. Text now depends on which case this is. */}
              <p className="rounded-md border border-warning-border bg-warning-bg-soft p-3 text-xs text-warning-dark">
                {factoryGroups.length === 0 ? (
                  <>
                    ยังไม่ได้สร้างเมลขอราคา — ด้านล่างคือรายการที่ต้องขอราคา จัดกลุ่มตามโรงงาน
                    {canContactFactory ? ' · กด “ติดต่อโรงงานแล้ว” ของแต่ละโรงงานเมื่อติดต่อแล้ว จึงกรอกราคาได้ (สร้างเมลหรือไม่ก็ได้)' : ''}
                  </>
                ) : (
                  <>
                    รายการที่ยังไม่ได้ขอราคาจากโรงงาน — ด้านล่างคือรายการที่ต้องขอราคาเพิ่ม จัดกลุ่มตามโรงงาน
                    {canContactFactory ? ' · กด “สร้างเมลให้ทุกโรงงาน” ด้านบนเพื่อรวมรายการเหล่านี้เข้าไปในเมล' : ''}
                  </>
                )}
              </p>
              {pricePreviewGroups.map((group) => (
                <div key={group.key} className="rounded-md border border-border bg-surface" data-testid="pcr-price-preview-group">
                  <div className="flex flex-wrap items-center gap-2 border-b border-border-subtle bg-surface-subtle px-3 py-2">
                    <strong className="text-sm text-text">{group.factoryName}</strong>
                    <span className="text-xs text-text-muted">({group.items.length} รายการ)</span>
                    {canContactFactory && inSendWindow && group.factoryName !== 'ยังไม่ได้ระบุโรงงาน' ? (
                      <div className="ml-auto flex flex-wrap items-center gap-2">
                        <Button type="button" size="sm" variant="secondary" disabled={previewBusyFactory === group.factoryName}
                          onClick={() => openMailForPreview(group.factoryName)}>
                          <Icon name="mail" size={13} />
                          สร้างเมล
                        </Button>
                        <Button type="button" size="sm" variant="primary" disabled={previewBusyFactory === group.factoryName}
                          onClick={() => setPreviewContactFactory(group.factoryName)}>
                          <Icon name="check" size={13} />
                          ติดต่อโรงงานแล้ว
                        </Button>
                      </div>
                    ) : (
                      <span className="ml-auto text-2xs text-text-muted">กรอกราคาได้หลังติดต่อโรงงานแล้ว</span>
                    )}
                  </div>
                  <div className="flex flex-col gap-1 p-3 text-xs text-text-secondary">
                    {group.items.map((item) => {
                      const variant = [item.size, item.color, item.texture].filter(Boolean).join(' · ');
                      return (
                        <div key={item.id} className="flex flex-wrap items-baseline gap-x-2">
                          <strong className="text-text">{itemDisplayName(item)}</strong>
                          {variant ? <span>{variant}</span> : null}
                          <span className="text-text-muted">· {item.requestedQty ?? '—'} {item.requestedUnit ?? ''}</span>
                        </div>
                      );
                    })}
                  </div>
                </div>
              ))}
            </div>
          ) : null}
          {factoryGroups.length > 0 ? (
            <div className="flex flex-col">
              {/* Column headers (DESIGN.md §13's .table-head idiom: surface-muted band, overline
                  caption) — shared FACTORY_ITEM_GRID keeps every item row below aligned to these
                  same five tracks regardless of which factory group it belongs to. md: (768px) up
                  only: below that each item reflows to a labelled stacked card instead (see the
                  per-field mobile caption spans in the item-row map below), so an empty header
                  strip would have nothing to head. */}
              <div className={cn('hidden items-center gap-3 border-b border-border-subtle bg-surface-subtle px-5 py-2.5 text-xs font-bold uppercase tracking-wide text-text-muted md:grid', FACTORY_ITEM_GRID)}>
                {/* Owner ruling (2026-09-19): โรงงาน on PCR screens, not ยี่ห้อ -- PCR page only,
                    TicketDetailPage's own identical header is untouched. */}
                <span>รุ่น / สี / เนื้อผิว</span>
                <span>จำนวน</span>
                <span>ราคาที่เสนอ (แก้ไข)</span>
                <span>ระยะเวลานำเข้า</span>
                <span>ราคาที่อนุมัติ</span>
              </div>
              {factoryGroups.map((group) => {
                const current = group.current;
                const quoteStatus = factoryQuoteStatusLabel(current.status);
                // The email-draft fields live in FactoryEmailDraftModal now (its own lookup against
                // `emailModalQuote`/`emailDrafts`, opened via setEmailModalQuoteId below) — no
                // per-group emailDraft needed in this closure any more.
                const draft = responseDrafts[current.id] ?? {
                  supplierQuoteRef: current.supplierQuoteRef ?? '',
                  // R1: when every line is locked to ONE currency, that is the draft's currency too.
                  defaultCurrency: (() => {
                    const locked = [...new Set((current.items ?? []).map((i) => requestItemById.get(i.pricingRequestItemId)?.requestedCurrency).filter(Boolean))];
                    return locked.length === 1 ? locked[0] : (current.defaultCurrency ?? 'THB');
                  })(),
                  paymentTerms: current.paymentTerms ?? '',
                  leadTimeText: current.leadTimeText ?? '',
                  revisionReason: '',
                  negotiationNote: current.negotiationNote ?? '',
                  items: defaultResponseItems(current, requestItemById),
                };
                // See confirmFactoryQuote's own doc comment: `responseDrafts[id]` exists in state
                // only once a change handler has written to it, so its presence already means
                // "Import touched this draft since it was last loaded from the server."
                const dirty = Boolean(responseDrafts[current.id]);
                const editable = isImport(user) && current.current
                  && ['DRAFT', 'REQUESTED', 'RESPONSE_RECEIVED', 'NEGOTIATING', 'READY_FOR_COSTING'].includes(current.status);
                // CR-1 (GLA-167) R3 / B-R2: the price grid is locked until the factory has been marked
                // ติดต่อโรงงานแล้ว — a DRAFT quote means "not contacted yet", and FactoryQuoteService#receive
                // now 409s EVERY DRAFT quote (no exception outside the contact window any more; that
                // exception, from #1062, is reversed). Locked inputs stay visible, dimmed, with the
                // reason beside them — the grid is still the primary surface (owner ruling 2026-08-16).
                const contacted = isFactoryContacted(current);
                // READY_FOR_COSTING only offers ยืนยันราคาเสนอ again while dirty — see
                // confirmFactoryQuote's doc comment for why an undirtied re-click must not be
                // offered at all (it would either no-op-fail against markReady's own guard, or,
                // if this branch called receive() unconditionally, spuriously bump the revision).
                const canConfirm = isImport(user) && current.current && contacted
                  && (['REQUESTED', 'RESPONSE_RECEIVED', 'NEGOTIATING'].includes(current.status)
                    || (current.status === 'READY_FOR_COSTING' && dirty));
                const canNegotiate = isImport(user) && current.status === 'RESPONSE_RECEIVED' && current.current;
                // สร้างเมล is offered to import AND the CEO at every status (R5): to build + copy the
                // mail while DRAFT, or just to re-read what was drafted afterwards. The modal is
                // read-only once the quote leaves DRAFT or the request leaves the contact window.
                const canOpenEmailDraft = canContactFactory;
                // ติดต่อโรงงานแล้ว: only for a current DRAFT quote inside the window markContacted
                // accepts (FactoryQuoteService.DRAFT_STATUSES) — and never again after (R7, no undo).
                const canMarkContacted = canContactFactory && current.current && current.status === 'DRAFT' && inSendWindow;
                // Per-line locked terms (R1): a line carrying requestedCurrency + requestedPriceUnitBasis
                // shows them read-only; only a LEGACY line (neither) keeps today's currency/unit selects.
                const groupRequestItems = draft.items.map((line) => requestItemById.get(line.pricingRequestItemId));
                const lockedTerms = lockedTermsList(
                  groupRequestItems.filter(Boolean),
                  (basis) => unitBasisCatalog.find((option) => option.code === basis)?.label ?? unitBasisLabel(basis),
                );
                const hasLegacyLines = groupRequestItems.some((item) => !hasLockedTerms(item));
                // The legacy selects speak for the legacy lines only: the first one that carries no terms.
                const legacyLine = draft.items.find((line) => !hasLockedTerms(requestItemById.get(line.pricingRequestItemId)));
                const groupUnitBasis = legacyLine?.unitBasis ?? draft.items[0]?.unitBasis ?? '';
                const groupCurrency = (legacyLine ? draft.defaultCurrency : null) || draft.items[0]?.currency || 'THB';
                const groupUnitLabel = unitBasisCatalog.find((option) => option.code === groupUnitBasis)?.label
                  ?? unitBasisLabel(groupUnitBasis);
                const quoteIds = group.quotes.map((q) => q.id);
                const pendingChange = leadTimeSplit.pending.find((change) => quoteIds.includes(change.factoryQuoteId)) ?? null;
                const decidedChanges = leadTimeSplit.decided.filter((change) => quoteIds.includes(change.factoryQuoteId));
                const pendingLineById = new Map((pendingChange?.lines ?? []).map((line) => [line.pricingRequestItemId, line]));
                const irRow = (storedIrQuery.data ?? [])
                  .filter((row) => row.status !== 'SUPERSEDED')
                  .find((row) => (current.factoryId != null && row.factoryId != null
                    ? row.factoryId === current.factoryId
                    : row.factoryName === current.factoryName)) ?? null;
                // Lines import may put in a lead-time change: import (non-stock) lines of THIS quote.
                const leadTimeLines = draft.items
                  .map((line) => requestItemById.get(line.pricingRequestItemId))
                  .filter((item) => item && isImportLine(item))
                  .map((item) => ({ item, name: itemDisplayName(item), current: { min: item.leadTimeMinDays, max: item.leadTimeMaxDays } }));
                const canRequestLeadTime = isImport(user) && current.current && !pendingChange && leadTimeLines.length > 0
                  && !['CANCELLED', 'SUPERSEDED', 'NOT_AVAILABLE'].includes(current.status);
                const contactedName = contactedByName(current, request?.events);
                // The chip answers "what do I do next with this factory" (IA F1): ยังไม่ติดต่อ -> ติดต่อแล้ว
                // <date> -> ยืนยันราคาแล้ว. Statuses outside that path (not available / cancelled / superseded)
                // keep their own label.
                const chip = !contacted
                  ? { tone: 'warning', label: 'ยังไม่ติดต่อ' }
                  : current.status === 'READY_FOR_COSTING'
                    ? { tone: 'success', label: 'ยืนยันราคาแล้ว' }
                    : ['NOT_AVAILABLE', 'CANCELLED', 'SUPERSEDED'].includes(current.status)
                      ? { tone: quoteStatus.tone, label: quoteStatus.label }
                      : {
                        tone: 'info',
                        label: ['ติดต่อแล้ว', formatShortThaiDay(current.contactedOn)].filter(Boolean).join(' ')
                          + (contactedName ? ` · โดย ${contactedName}` : ''),
                      };

                function updateDraft(patch) {
                  setResponseDrafts({ ...responseDrafts, [current.id]: { ...draft, ...patch } });
                }
                function updateLine(index, patch) {
                  const items = [...draft.items];
                  items[index] = { ...items[index], ...patch };
                  updateDraft({ items });
                }
                // The legacy selects only ever touch lines that carry no locked terms.
                function updateCurrency(nextCurrency) {
                  updateDraft({
                    defaultCurrency: nextCurrency,
                    items: draft.items.map((item) => (hasLockedTerms(requestItemById.get(item.pricingRequestItemId))
                      ? item : { ...item, currency: nextCurrency })),
                  });
                }
                function updateUnitBasis(nextBasis) {
                  const nextLabel = unitBasisCatalog.find((option) => option.code === nextBasis)?.label;
                  updateDraft({
                    items: draft.items.map((item) => (hasLockedTerms(requestItemById.get(item.pricingRequestItemId))
                      ? item : { ...item, unitBasis: nextBasis, quotedUnit: nextLabel ?? item.quotedUnit })),
                  });
                }
                function discardEdits() {
                  setResponseDrafts((cur) => {
                    const next = { ...cur };
                    delete next[current.id];
                    return next;
                  });
                }

                return (
                  <div key={group.key} className="border-t border-border-subtle first:border-t-0" data-testid={`pcr-factory-card-${current.id}`}>
                    {/* Factory header: name, line count, the contact-state chip, and the two actions.
                        Never its own bordered card (DESIGN.md: "never nest a card inside a card") — a
                        tonal surface-subtle band inside the flush Panel, same idiom as a table header. */}
                    <div className="flex flex-wrap items-center justify-between gap-3 bg-surface-subtle px-5 py-3 mobile:px-4">
                      <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5">
                        <strong className="text-text">{group.factoryName}</strong>
                        <span className="text-xs text-text-muted">({current.items?.length ?? 0} รายการ)</span>
                        <span data-testid={`pcr-factory-status-${current.id}`}>
                          <StatusBadge tone={chip.tone}>{chip.label}</StatusBadge>
                        </span>
                        {contacted && !['READY_FOR_COSTING', 'NOT_AVAILABLE', 'CANCELLED', 'SUPERSEDED'].includes(current.status)
                          && current.status !== 'REQUESTED' ? (
                            <StatusBadge tone={quoteStatus.tone}>{quoteStatus.label}</StatusBadge>
                          ) : null}
                        {current.revisionNo > 1 ? <StatusBadge tone="neutral">ครั้งที่ {current.revisionNo}</StatusBadge> : null}
                      </div>
                      {canOpenEmailDraft || canMarkContacted ? (
                        <div className="flex flex-wrap items-center gap-2">
                          {canOpenEmailDraft ? (
                            <Button type="button" variant="secondary" onClick={() => setEmailModalQuoteId(current.id)} data-testid={`pcr-open-email-draft-${current.id}`}>
                              <Icon name="mail" size={14} />
                              สร้างเมล
                            </Button>
                          ) : null}
                          {canMarkContacted ? (
                            <Button type="button" variant="primary" onClick={() => setContactQuoteId(current.id)} data-testid={`pcr-open-contact-${current.id}`}>
                              <Icon name="check" size={14} />
                              ติดต่อโรงงานแล้ว
                            </Button>
                          ) : null}
                        </div>
                      ) : null}
                    </div>

                    {/* The contacted note, kept visible for everyone who can see the card (R7: it is final). */}
                    {contacted && current.contactedNote ? (
                      <p className="m-0 border-b border-border-subtle px-5 py-2.5 text-xs text-text-secondary mobile:px-4" data-testid={`pcr-contact-note-${current.id}`}>
                        <span className="font-bold text-text-muted">หมายเหตุการติดต่อ: </span>{current.contactedNote}
                      </p>
                    ) : null}

                    {/* Hint (CR-1 R3): while the factory is not yet contacted the price inputs below are
                        locked. Point import at the button instead of letting a price be typed out of order. */}
                    {editable && !contacted ? (
                      <div className="flex flex-wrap items-center gap-2 border-b border-warning-border bg-warning-bg-soft px-5 py-2.5 text-xs text-warning-dark mobile:px-4" data-testid={`pcr-await-contact-${current.id}`}>
                        <Icon name="lock" size={14} />
                        กด ติดต่อโรงงานแล้ว ก่อนกรอกราคา
                      </div>
                    ) : null}

                    {/* Terms strip (R1): currency + price unit are Sales's, locked for import. A lock icon
                        and plain words, so nobody hunts for a select that is deliberately not there. */}
                    {lockedTerms.length > 0 ? (
                      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-border-subtle px-5 py-2.5 text-xs text-text-secondary mobile:px-4" data-testid={`pcr-factory-terms-${current.id}`}>
                        <Icon name="lock" size={13} className="shrink-0 text-text-muted" />
                        <strong className="text-text">{lockedTerms.join(' / ')}</strong>
                        <span className="text-text-muted">· ตามคำขอของฝ่ายขาย</span>
                      </div>
                    ) : null}

                    {/* Legacy lines (no requested terms) keep today's currency/unit controls. */}
                    {hasLegacyLines ? (editable ? (
                      <div className="flex flex-wrap items-end gap-4 border-b border-border-subtle px-5 py-3 mobile:px-4">
                        <FormField
                          label={<>สกุลเงิน<InfoTip label="สกุลเงิน" text="สกุลเงินที่โรงงานนี้เสนอราคามา อ้างอิงจากราคาตั้งต้นในแคตตาล็อกโดยอัตโนมัติ — เปลี่ยนได้หากโรงงานเสนอราคาเป็นสกุลเงินอื่น" /></>}
                          htmlFor={`pcr-currency-${current.id}`}
                        >
                          <select
                            id={`pcr-currency-${current.id}`}
                            className="md:w-32"
                            value={groupCurrency}
                            onChange={(e) => updateCurrency(e.target.value)}
                          >
                            {(currencyOptions.includes(groupCurrency) ? currencyOptions : [groupCurrency, ...currencyOptions]).map((code) => (
                              <option key={code} value={code}>{code}</option>
                            ))}
                          </select>
                        </FormField>
                        <FormField label="หน่วยราคา" htmlFor={`pcr-unit-${current.id}`}>
                          <select
                            id={`pcr-unit-${current.id}`}
                            className="md:w-32"
                            value={groupUnitBasis}
                            onChange={(e) => updateUnitBasis(e.target.value)}
                          >
                            {unitBasisCatalog.map((option) => (
                              <option key={option.code} value={option.code}>{`/ ${option.label}`}</option>
                            ))}
                          </select>
                        </FormField>
                      </div>
                    ) : (
                      <div className="flex flex-wrap gap-x-4 gap-y-1 border-b border-border-subtle px-5 py-2.5 text-xs text-text-muted mobile:px-4">
                        <span>สกุลเงิน: {groupCurrency}</span>
                        <span>{`หน่วยราคา: / ${groupUnitLabel}`}</span>
                      </div>
                    )) : null}

                    {/* Item rows: ยี่ห้อ/รุ่น · สี/เนื้อผิว · จำนวน · ราคาที่เสนอ (แก้ไข) · ราคาที่อนุมัติ. */}
                    {draft.items.map((line, index) => {
                      const itemRef = `รายการ #${line.pricingRequestItemId}`;
                      const requested = requestItemById.get(line.pricingRequestItemId);
                      const productName = [requested?.catalogBrand ?? requested?.brand, requested?.catalogModel ?? requested?.model]
                        .filter(Boolean).join(' ') || requested?.productDescription || itemRef;
                      // size, then colour, then texture — matches the order this page has always
                      // rendered them in (see the UAT-reported "cannot tell which price box belongs
                      // to which item" fix this join predates), so the string a reader already
                      // recognises does not silently reorder under the redesign.
                      const variantLabel = [requested?.size, requested?.color, requested?.texture].filter(Boolean).join(' · ');
                      const qty = requested?.requestedQty ?? line.quotedQuantity;
                      const unitLabel = requested?.requestedUnit
                        ?? unitBasisCatalog.find((option) => option.code === line.unitBasis)?.label
                        ?? unitBasisLabel(line.unitBasis);
                      // No backend field carries an "approved price" distinct from rawUnitPrice —
                      // FactoryQuoteItemDto has none, and status lives on the QUOTE, not the line.
                      // Reading it off current.status === READY_FOR_COSTING (this quote has been
                      // confirmed via ยืนยันราคาเสนอ at least once since its last edit) is therefore an
                      // inference, not a literal field read — documented here for a reviewer to
                      // correct if the owner meant something else by "approved."
                      //
                      // Reads the SERVER item (current.items), never `line` (draft.items — the
                      // editable value): approvedPrice must stay frozen at whatever was last
                      // confirmed, so it visibly still differs from ราคาที่เสนอ the instant Import
                      // types a new number, rather than instantly (and wrongly) claiming the unsaved
                      // edit was already approved.
                      const serverItem = current.items?.find((i) => i.pricingRequestItemId === line.pricingRequestItemId);
                      const approvedPrice = current.status === 'READY_FOR_COSTING' ? serverItem?.rawUnitPrice ?? null : null;
                      return (
                        <div
                          key={line.pricingRequestItemId}
                          className={cn('grid items-center gap-3 border-b border-border-subtle px-5 py-3 last:border-b-0 mobile:flex mobile:flex-col mobile:items-stretch mobile:gap-1 mobile:px-4', FACTORY_ITEM_GRID)}
                        >
                          <div className="min-w-0">
                            <div className="break-words text-sm font-bold text-text md:truncate">{productName}</div>
                            <div className="break-words text-xs text-text-secondary">{variantLabel || '-'}</div>
                          </div>
                          <div className="text-sm text-text-secondary">
                            <span className="mr-1 text-2xs font-bold uppercase text-text-muted md:hidden">จำนวน</span>
                            {qty} {unitLabel}
                          </div>
                          <div className="min-w-0">
                            <span className="mb-1 block text-2xs font-bold uppercase text-text-muted md:hidden">ราคาที่เสนอ (แก้ไข)</span>
                            {editable && contacted ? (
                              <div className="flex flex-wrap items-center gap-1.5">
                                <input
                                  id={`pcr-quote-price-${current.id}-${line.pricingRequestItemId}`}
                                  className="w-full md:w-28"
                                  type="text"
                                  inputMode="decimal"
                                  placeholder={`ราคา/${unitBasisCatalog.find((option) => option.code === line.unitBasis)?.label ?? line.quotedUnit ?? ''}`}
                                  aria-label={`ราคาที่เสนอ ${itemRef}`}
                                  value={line.rawUnitPrice ?? ''}
                                  onChange={(e) => updateLine(index, { rawUnitPrice: sanitizeDecimal(e.target.value) })}
                                />
                                {line.unitBasis === 'PER_SQM' && !(Number(requested?.sqmPerPiece) > 0) ? (
                                  <input
                                    id={`pcr-quote-sqm-${current.id}-${line.pricingRequestItemId}`}
                                    className="w-full md:w-24"
                                    type="text"
                                    inputMode="decimal"
                                    placeholder="ตร.ม./หน่วย"
                                    aria-label={`ตร.ม./หน่วย ${itemRef}`}
                                    value={line.sqmPerUnit ?? ''}
                                    onChange={(e) => updateLine(index, { sqmPerUnit: sanitizeDecimal(e.target.value) })}
                                  />
                                ) : null}
                              </div>
                            ) : editable ? (
                              // Import, but the factory is not yet marked ติดต่อโรงงานแล้ว — a dimmed,
                              // disabled stand-in so the column reads "you'll fill this after
                              // contacting", not a usable field. The hint above the grid says why.
                              <input
                                className="w-full opacity-50 md:w-32"
                                type="text"
                                disabled
                                value=""
                                placeholder="ติดต่อโรงงานก่อน"
                                aria-label={`ราคาที่เสนอ ${itemRef} — ต้องติดต่อโรงงานก่อน`}
                                data-testid={`pcr-quote-price-locked-${current.id}-${line.pricingRequestItemId}`}
                              />
                            ) : (
                              <span className="text-sm text-text-secondary">{formatCurrency(line.rawUnitPrice, line.currency)}</span>
                            )}
                          </div>
                          {/* ระยะเวลานำเข้า: the line's current range; with a PENDING change it reads
                              "old → new" in amber until the owning rep / a sales manager decides (R2/R10).
                              The old value stands until then — nothing downstream waits on it. */}
                          <div className="min-w-0 text-sm text-text-secondary" data-testid={`pcr-lt-value-${current.id}-${line.pricingRequestItemId}`}>
                            <span className="mb-1 block text-2xs font-bold uppercase text-text-muted md:hidden">ระยะเวลานำเข้า</span>
                            {pendingLineById.get(line.pricingRequestItemId) ? (
                              <>
                                <span className="tabular-nums">
                                  {`${leadTimeRangeText(pendingLineById.get(line.pricingRequestItemId).oldMinDays, pendingLineById.get(line.pricingRequestItemId).oldMaxDays)} → ${leadTimeRangeText(pendingLineById.get(line.pricingRequestItemId).newMinDays, pendingLineById.get(line.pricingRequestItemId).newMaxDays)} วัน`}
                                </span>
                                <span className="block text-2xs font-bold text-warning-dark">รอฝ่ายขายอนุมัติ</span>
                              </>
                            ) : (
                              <span className="tabular-nums">{leadTimeWithUnit(requested?.leadTimeMinDays, requested?.leadTimeMaxDays)}</span>
                            )}
                          </div>
                          <div>
                            <span className="mb-1 block text-2xs font-bold uppercase text-text-muted md:hidden">ราคาที่อนุมัติ</span>
                            <span className="text-sm font-bold text-text">
                              {approvedPrice != null ? formatCurrency(approvedPrice, serverItem?.currency ?? line.currency) : '–'}
                            </span>
                          </div>
                        </div>
                      );
                    })}

                    {/* Lead-time change (R2/R10, option C), the approved/rejected history, and this factory's
                        IR (R9, read-only). One compact block so the card keeps one reading order:
                        terms -> grid -> what is pending / decided -> the IR. */}
                    {pendingChange || canRequestLeadTime || decidedChanges.length > 0 || irRow ? (
                      <div className="grid gap-2.5 border-b border-border-subtle px-5 py-3 text-xs mobile:px-4">
                        {pendingChange ? (
                          <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 rounded-md border border-warning-border bg-warning-bg-soft px-3 py-2" data-testid={`pcr-lt-pending-${current.id}`}>
                            <StatusBadge tone="warning">รอฝ่ายขายอนุมัติ</StatusBadge>
                            <span className="min-w-0 text-text-secondary">ขอเปลี่ยนระยะเวลานำเข้า — {pendingChange.reason}</span>
                            {isImport(user) ? (
                              <span className="ml-auto flex flex-wrap items-center gap-2">
                                <Button type="button" size="sm" variant="secondary" disabled={withdrawLeadTimeChange.isPending}
                                  onClick={() => setLeadTimeDialog({ quoteId: current.id, change: pendingChange })}>
                                  แก้ไข
                                </Button>
                                <Button type="button" size="sm" variant="secondary" disabled={withdrawLeadTimeChange.isPending}
                                  onClick={() => withdrawLeadTimeChange.mutate(pendingChange)}>
                                  ถอนคำขอ
                                </Button>
                              </span>
                            ) : null}
                          </div>
                        ) : null}
                        {canRequestLeadTime ? (
                          <div>
                            <Button type="button" size="sm" variant="secondary" data-testid={`pcr-lt-open-${current.id}`}
                              onClick={() => setLeadTimeDialog({ quoteId: current.id, change: null })}>
                              <Icon name="pencil" size={13} />
                              ขอเปลี่ยนระยะเวลานำเข้า
                            </Button>
                          </div>
                        ) : null}
                        {decidedChanges.length > 0 ? (
                          <div className="grid gap-1 text-text-muted" data-testid={`pcr-lt-history-${current.id}`}>
                            <span className="font-bold">ประวัติการเปลี่ยนระยะเวลานำเข้า</span>
                            {decidedChanges.map((change) => (
                              <p key={change.id} className="m-0">
                                <span className={change.status === 'APPROVED' ? 'font-bold text-success-dark' : 'font-bold text-danger-dark'}>
                                  {change.status === 'APPROVED' ? 'อนุมัติแล้ว' : 'ไม่อนุมัติ'}
                                </span>
                                {change.decidedAt ? ` ${formatShortThaiDay(String(change.decidedAt).slice(0, 10))}` : ''}
                                {' · '}
                                {change.lines.map((line) => `${lineNameById.get(line.pricingRequestItemId) ?? `รายการ #${line.pricingRequestItemId}`} ${leadTimeRangeText(line.oldMinDays, line.oldMaxDays)} → ${leadTimeRangeText(line.newMinDays, line.newMaxDays)}`).join(', ')}
                                {change.status === 'REJECTED' && change.decisionReason ? ` · เหตุผล: ${change.decisionReason}` : ''}
                              </p>
                            ))}
                          </div>
                        ) : null}
                        {irRow ? (
                          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-text-secondary" data-testid={`pcr-ir-${current.id}`}>
                            <Icon name="fileText" size={13} className="shrink-0 text-text-muted" />
                            <span className="font-bold text-text">{irRow.docNumber ?? 'ใบขอซื้อ'}</span>
                            <StatusBadge tone={irRow.status === 'ISSUED' ? 'success' : 'neutral'}>{irRow.status === 'ISSUED' ? 'ออกเลขแล้ว' : 'ฉบับร่าง'}</StatusBadge>
                            <Button type="button" size="sm" variant="secondary" disabled={downloadingIrId === irRow.id}
                              onClick={() => downloadIr(irRow)}>
                              {downloadingIrId === irRow.id ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลด PDF'}
                            </Button>
                          </div>
                        ) : null}
                      </div>
                    ) : null}

                    {/* Attachments — unchanged functionality, relocated under the item rows now that
                        the email composer they used to trail is a modal. */}
                    {(current.attachments ?? []).length || isImport(user) ? (
                      <div className="border-b border-border-subtle px-5 py-3 mobile:px-4">
                        <div className="mb-2 flex flex-wrap items-center gap-2">
                          <span className="text-xs font-bold text-text-muted">ไฟล์แนบ</span>
                          {isImport(user) ? (
                            // Left as a <label> wrapping the hidden file input, not <Button>: a
                            // <button> cannot open the native file picker the way a <label>
                            // wrapping its <input type="file"> does.
                            <label className={cn(buttonVariants({ variant: 'secondary', size: 'sm' }), 'cursor-pointer')}>
                              <input type="file" className="hidden" onChange={(event) => {
                                const file = event.target.files?.[0];
                                if (file) uploadQuoteAttachment.mutate({ quote: current, file });
                                event.target.value = '';
                              }} />
                              <Icon name="upload" size={13} />
                              แนบไฟล์
                            </label>
                          ) : null}
                        </div>
                        <div className="flex flex-col gap-1 text-xs text-text-muted">
                          {(current.attachments ?? []).map((attachment) => (
                            <a key={attachment.id} className="text-info underline" href={api.pricingRequests.factoryQuoteAttachmentUrl(attachment.id)} target="_blank" rel="noreferrer">
                              {attachment.fileName}
                            </a>
                          ))}
                          {(current.attachments ?? []).length === 0 ? <span>-</span> : null}
                        </div>
                      </div>
                    ) : null}

                    {/* หมายเหตุราคา — negotiationNote, a real backend field
                        (ReceiveFactoryQuoteRequest.negotiationNote) already plumbed through
                        defaultResponseItems' default state and cleanResponsePayload's outgoing
                        shape, but with no <textarea> anywhere to actually set it (grep-verified: 0
                        render sites before this task) — wiring an existing field to an existing
                        control, not new backend surface. */}
                    <div className="px-5 py-3 mobile:px-4">
                      {editable ? (
                        <FormField label="หมายเหตุราคา" htmlFor={`pcr-note-${current.id}`}>
                          <textarea
                            id={`pcr-note-${current.id}`}
                            className="min-h-20"
                            placeholder="ข้อมูลเพิ่มเติมเกี่ยวกับราคา (ถ้ามี)"
                            value={draft.negotiationNote}
                            onChange={(e) => updateDraft({ negotiationNote: e.target.value })}
                          />
                        </FormField>
                      ) : draft.negotiationNote ? (
                        <p className="m-0 text-xs text-text-muted"><strong>หมายเหตุราคา:</strong> {draft.negotiationNote}</p>
                      ) : null}
                      {group.history.length ? (
                        <p className="m-0 mt-2 text-xs text-text-muted">
                          ประวัติ: {group.history.map((q) => {
                            const historyStatus = factoryQuoteStatusLabel(q.status);
                            return `ครั้งที่ ${q.revisionNo} (${historyStatus.label})`;
                          }).join(' · ')}
                        </p>
                      ) : null}
                      <div className="mt-3 flex flex-wrap justify-end gap-2">
                        {canNegotiate ? (
                          <Button type="button" variant="secondary" disabled={negotiateQuote.isPending} onClick={() => negotiateQuote.mutate(current)}>
                            เจรจา
                          </Button>
                        ) : null}
                        {editable ? (
                          <Button type="button" variant="secondary" onClick={discardEdits}>
                            ยกเลิก
                          </Button>
                        ) : null}
                        {canConfirm ? (
                          <Button
                            type="button"
                            variant="primary"
                            disabled={confirmingFactoryQuoteId === current.id}
                            onClick={() => confirmFactoryQuote(current, draft)}
                            data-testid="pcr-submit-to-ceo"
                          >
                            {confirmingFactoryQuoteId === current.id ? 'กำลังยืนยัน…' : 'ยืนยันราคาเสนอ'}
                          </Button>
                        ) : null}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : null}
        </Panel>
      ) : null}

      {!canSeeRawPricingDecision(user) && canSeePricingDecisionSalesView(user, summary) && decisionSalesView ? (
        <Panel flush title="ราคาขายที่อนุมัติ">
          <div className="flex flex-col gap-2 p-4">
            {/* P2 fix (2026-09): this is the price the rep quotes to the customer, so the ก่อน
                VAT label matters most here — see the CEO panel's identical note for the same
                reasoning (PricingDecisionService.computeSellingPrice has no VAT term). */}
            <p className="m-0 flex items-start gap-2 rounded-lg border border-info-border bg-info-bg px-3 py-2.5 text-xs text-info-dark">
              <Icon name="info" size={15} className="mt-0.5 shrink-0" />
              ราคาขายทุกรายการในหน้านี้เป็นราคาก่อน VAT — ใบเสนอราคาจะบวก VAT 7% แยกอีกชั้นหนึ่ง
            </p>
            {decisionSalesView.items.map((item) => (
              <div key={item.pricingRequestItemId} className="rounded-md border border-border bg-surface p-3 text-sm">
                <strong>{[item.brand, item.model].filter(Boolean).join(' ') || item.productDescription || '-'}</strong>
                <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
                  <span>{item.requestedQuantity} ({item.requestedUnitBasis})</span>
                  <span>ราคาขาย (ก่อน VAT): {formatCurrency(item.approvedSellingPricePerRequestedUnit, decisionSalesView.currency)}</span>
                </div>
              </div>
            ))}
          </div>
        </Panel>
      ) : null}

      {/* Same ruling: the customer-facing quotation is Sales' surface. canViewCustomerQuotation
          still grants import read access at the API (unchanged), but the panel is hidden here —
          Import was only ever shown an empty-state telling it to wait for APPROVED_FOR_QUOTATION. */}
      {canViewCustomerQuotation(user, summary) && !isImport(user) ? (
        <Panel
          flush
          title="ใบเสนอราคาลูกค้า"
          actions={dealQuotationForPr ? (
            (() => {
              const status = quotationStatusLabel(dealQuotationForPr.docStatus);
              return <StatusBadge tone={status.tone}>{status.label}</StatusBadge>;
            })()
          ) : currentCustomerQuotation ? (
            (() => {
              const status = quotationStatusLabel(currentCustomerQuotation.docStatus);
              return (
                <StatusBadge tone={status.tone}>
                  {status.label} · ครั้งที่ {currentCustomerQuotation.quotationRevisionNo}
                </StatusBadge>
              );
            })()
          ) : null}
        >
          <div className="flex flex-col gap-3 p-4">
            {/* GLA-123 slice S1 M1 fix (Opus review, 2026-09-20): the NEW engine's quotation for
                this request, shown in THIS SAME panel rather than a separate one — one pricing
                request only ever carries one "ใบเสนอราคาลูกค้า" concept to a viewer regardless of
                which engine wrote it, and M2 already guarantees the two chains cannot both be
                live, so splitting them into two panels would just make a rep hunt for the right
                one. number/status/link, matching what the legacy block below shows. */}
            {dealQuotationForPr ? (
              <div className="flex flex-col gap-1">
                <div className="text-sm"><strong>เลขที่</strong> {dealQuotationForPr.number}</div>
                <Link
                  to={`/quotations/${dealQuotationForPr.id}`}
                  className="self-start text-sm font-medium text-primary underline-offset-2 hover:underline"
                >
                  เปิดใบเสนอราคา
                </Link>
              </div>
            ) : null}

            {/* GLA-123 slice S3 (R9) — the SAME outcome-recording action the legacy block below
                offers (canRecordCustomerQuotationOutcome is origin-agnostic: sales + ticket owner,
                docStatus === 'ISSUED'), now also reachable for the NEW engine's own quotation. R8
                (only one finalized quotation per deal, across both คำขอราคา origins) is enforced
                server-side by DealQuotationService#recordOutcome, not here. */}
            {canRecordCustomerQuotationOutcome(user, summary, dealQuotationForPr) ? (
              <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
                <strong className="text-sm">บันทึกผลจากลูกค้า</strong>
                <textarea
                  className="rounded border border-border p-2 text-sm"
                  placeholder="หมายเหตุจากลูกค้า (ถ้ามี)"
                  value={outcomeNote}
                  onChange={(e) => setOutcomeNote(e.target.value)}
                />
                <div className="flex flex-wrap gap-2">
                  <Button type="button" variant="primary" disabled={recordDealQuotationOutcome.isPending}
                    onClick={() => recordDealQuotationOutcome.mutate({ quotation: dealQuotationForPr, outcome: 'ACCEPTED' })}>
                    ลูกค้ายอมรับ
                  </Button>
                  <Button type="button" variant="danger" disabled={recordDealQuotationOutcome.isPending}
                    onClick={() => recordDealQuotationOutcome.mutate({ quotation: dealQuotationForPr, outcome: 'REJECTED' })}>
                    ลูกค้าปฏิเสธ
                  </Button>
                  <Button type="button" variant="secondary" disabled={recordDealQuotationOutcome.isPending}
                    onClick={() => recordDealQuotationOutcome.mutate({ quotation: dealQuotationForPr, outcome: 'REVISION_REQUESTED' })}>
                    ลูกค้าขอแก้ไข
                  </Button>
                </div>
              </div>
            ) : null}

            {/* Read-only outcome summary — mirrors the legacy block's identical read-only
                summary below (visible to everyone with view access, once the customer's response
                has been recorded or the document moved past ISSUED for any other reason). */}
            {dealQuotationForPr
              && ['ACCEPTED', 'REJECTED', 'REVISION_REQUESTED', 'EXPIRED'].includes(dealQuotationForPr.docStatus) ? (
              <p className="text-sm text-text-muted">
                ผลใบเสนอราคา: <strong>{quotationStatusLabel(dealQuotationForPr.docStatus).label}</strong>
              </p>
            ) : null}
            {/* MAJOR-3 fix (owner ruling via coordinator, 2026-09-20 — "the expiry escape hatch"),
                widened by the S3 round-3 review (NEW-1, MAJOR, 2026-09-23): once the linked
                NEW-engine quotation has EXPIRED (D5), was REJECTED, or the customer asked for a
                REVISION, sales may write a FRESH one from the SAME approved decision —
                DealQuotationService#createFromPricingRequest tolerates all three cases
                server-side (its own PR-status check treats them identically: none of the three
                move the PR off QUOTATION_ISSUED — see that method's own comment), and
                DealQuotationRepository#hasLivePricingRequestQuotation excludes all three from
                its own "live" set for the same reason. This gate used to only cover EXPIRED,
                which left REVISION_REQUESTED and REJECTED — both reachable via this same PR's
                own outcome-recording buttons just above — as UI dead ends: the backend accepted
                a recreate, but nothing on screen offered it. Gated on canManageCustomerQuotation
                (sales + ticket owner, no PR-status check) rather than canCreateCustomerQuotation,
                which requires pr.status === 'APPROVED_FOR_QUOTATION' — a PR in any of these three
                states sits at QUOTATION_ISSUED instead (the backend deliberately does not roll PR
                status back), so that stricter gate would incorrectly hide this button in exactly
                the states it needs to appear. Reuses the SAME createDealQuotationFromRequest
                mutation the first-ever create button below already uses (same
                navigate-on-success behaviour), since the server-side call is identical in every
                case — only the PR's current state differs. */}
            {['EXPIRED', 'REVISION_REQUESTED', 'REJECTED'].includes(dealQuotationForPr?.docStatus)
              && canManageCustomerQuotation(user, summary) ? (
              <div className="flex flex-col items-start gap-2">
                <p className="m-0 text-sm text-warning">
                  {dealQuotationForPr.docStatus === 'EXPIRED'
                    ? 'ใบเสนอราคาหมดอายุแล้ว — เขียนใบใหม่ได้'
                    : dealQuotationForPr.docStatus === 'REVISION_REQUESTED'
                      ? 'ลูกค้าขอแก้ไขใบเสนอราคา — เขียนใบใหม่ได้'
                      : 'ลูกค้าปฏิเสธใบเสนอราคา — เขียนใบใหม่ได้'}
                </p>
                <Button
                  type="button"
                  variant="primary"
                  className="self-start"
                  onClick={() => createDealQuotationFromRequest.mutate()}
                  disabled={createDealQuotationFromRequest.isPending}
                >
                  เขียนใบเสนอราคาใหม่จากคำขอราคา
                </Button>
              </div>
            ) : null}
            {!dealQuotationForPr && !currentCustomerQuotation && canCreateCustomerQuotation(user, summary) ? (
              <div className="flex flex-wrap items-center gap-2">
                {/* GLA-123 slice S1 M2 fix (Opus review, 2026-09-20), field narrowed by MINOR-1
                    (owner ruling, confirmed 2026-09-20, second re-review): the OLD button is
                    hidden, not merely de-emphasized, once the request's decision is new-form
                    (decisionSalesView.newFormPricing — V187) — the NEW engine
                    (createFromPricingRequest) is the only path offered from here on, since the
                    two paths are now server-side mutually exclusive (M2) and a new-form PR's
                    quotation cannot be issued until S2 anyway, so steering everyone through the
                    OLD button on a new-form PR would just mean a wasted click once S2 ships. A
                    legacy decision (newFormPricing false) keeps BOTH buttons exactly as before —
                    decisionSalesView is undefined until the query resolves, so this defaults to
                    "still legacy-shaped" rather than flashing the new-only state first. This used
                    to read the CEO's own price_mode string directly; MINOR-1 replaced that whole
                    field with this plain boolean so the endpoint (also legitimately callable by
                    import, for an unrelated reason) never carries a price-shaped fact at all —
                    see PricingDecisionSalesViewDto#newFormPricing's own Javadoc. */}
                {!decisionSalesView?.newFormPricing ? (
                  <Button type="button" variant="secondary" className="self-start" onClick={() => createQuotation.mutate()} disabled={createQuotation.isPending}>
                    สร้างร่างใบเสนอราคาลูกค้า
                  </Button>
                ) : null}
                {/* GLA-123 slice S1 — the direct-deal engine, prefilled from the CEO's decision.
                    Preferred path for a new-form (V185/V187) request; a legacy one gets a clear
                    409 toast rather than this button being hidden. */}
                <Button
                  type="button"
                  variant="primary"
                  className="self-start"
                  onClick={() => createDealQuotationFromRequest.mutate()}
                  disabled={createDealQuotationFromRequest.isPending}
                >
                  เขียนใบเสนอราคาจากคำขอราคา
                </Button>
              </div>
            ) : null}
            {!dealQuotationForPr && !currentCustomerQuotation && !canCreateCustomerQuotation(user, summary) ? (
              <p className="text-sm text-text-muted">
                ยังไม่มีใบเสนอราคาลูกค้า — ต้องรออนุมัติราคาขาย (APPROVED_FOR_QUOTATION) ก่อนจึงจะสร้างได้
              </p>
            ) : null}

            {!dealQuotationForPr && currentCustomerQuotation ? (() => {
              const quotation = currentCustomerQuotation;
              const quotationStatus = quotationStatusLabel(quotation.docStatus);
              const editable = isCustomerQuotationEditable(quotation) && canManageCustomerQuotation(user, summary);
              // Issue #733: a revision's discount is refused by CustomerQuotationService.update,
              // so the input must not be offered on one. Narrower than `editable` on purpose —
              // description/notes stay writable, only the money does not.
              const discountEditable = editable && isCustomerQuotationDiscountEditable(quotation);
              return (
                <div key={quotation.id} className="flex flex-col gap-3">
                  <div className="text-sm"><strong>เลขที่</strong> {quotation.number}</div>
                  {editable && !discountEditable ? (
                    <p className="rounded-md border border-warning-border bg-warning-bg p-3 text-xs text-warning-dark">
                      ใบเสนอราคาฉบับแก้ไขให้ส่วนลดไม่ได้ — ส่วนลดทุกรายการถูกตั้งเป็น 0 และแก้ไขไม่ได้
                      หากต้องเปลี่ยนราคาหรือจำนวนหลังออกใบเสนอราคาแล้ว ต้องสร้างรอบแก้ไขตามการเปลี่ยนแปลงของลูกค้า
                      (customer-change revision) เพื่อให้ CEO อนุมัติราคาใหม่
                    </p>
                  ) : null}
                  <div className="flex flex-col gap-2">
                    {quotation.items.map((item) => {
                      const draft = quotationItemDrafts[item.id] ?? {};
                      const discount = cleanNumber(draft.salesDiscount ?? item.salesDiscount) ?? 0;
                      const previewFinal = item.approvedUnitPrice - discount;
                      const belowMinimum = item.minimumSellingPricePerRequestedUnit != null
                        && previewFinal < item.minimumSellingPricePerRequestedUnit;
                      // CEO discount-approval workflow, Phase 2 (V155): the SAVED line's own
                      // status, keyed off the server-persisted final_unit_price — distinct from
                      // `belowMinimum` above, which previews an UNSAVED draft edit still in the
                      // input box. Only ever set when this item's current price is genuinely
                      // below minimum (see DiscountApprovalRepository#findCurrentByQuotationId).
                      const discountApproval = discountApprovalByItemId.get(item.id);
                      const discountApprovalStatus = discountApproval
                        ? discountApprovalStatusLabel(discountApproval.status) : null;
                      return (
                        <div key={item.id} className="rounded-md border border-border bg-surface p-3 text-sm">
                          {editable ? (
                            <input
                              className="w-full rounded border border-border p-1 text-sm"
                              value={draft.description ?? item.description ?? ''}
                              onChange={(e) => setQuotationItemDrafts((cur) => ({
                                ...cur, [item.id]: { ...cur[item.id], description: e.target.value },
                              }))}
                            />
                          ) : (
                            <strong>{item.description || '-'}</strong>
                          )}
                          <div className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-text-muted">
                            <span>{item.requestedQuantity} ({item.requestedUnitBasis})</span>
                            <span>ราคาที่อนุมัติ: {formatCurrency(item.approvedUnitPrice, quotation.currency)}</span>
                            {discountEditable ? (
                              <label className="flex items-center gap-1">
                                ส่วนลด/หน่วย
                                <input
                                  type="number"
                                  step="0.01"
                                  className="w-24 rounded border border-border p-1 text-xs"
                                  value={draft.salesDiscount ?? item.salesDiscount ?? 0}
                                  onChange={(e) => setQuotationItemDrafts((cur) => ({
                                    ...cur, [item.id]: { ...cur[item.id], salesDiscount: e.target.value },
                                  }))}
                                />
                              </label>
                            ) : (
                              <span>ส่วนลด/หน่วย: {formatCurrency(item.salesDiscount, quotation.currency)}</span>
                            )}
                            <span>ราคาสุทธิ: {formatCurrency(discountEditable ? previewFinal : item.finalUnitPrice, quotation.currency)}</span>
                            <span>รวมรายการ: {formatCurrency(item.lineTotal, quotation.currency)}</span>
                          </div>
                          {belowMinimum ? (
                            <p className="mt-1 text-xs font-medium text-warning-dark">
                              ⚠ ราคาต่ำกว่าราคาขั้นต่ำที่ CEO อนุมัติ ({formatCurrency(item.minimumSellingPricePerRequestedUnit, quotation.currency)})
                              — บันทึกได้ แต่ต้องรอ CEO อนุมัติส่วนลดก่อนจึงจะออกใบเสนอราคาได้
                            </p>
                          ) : null}
                          {discountApproval ? (
                            <div className="mt-2 flex flex-wrap items-center gap-2">
                              <StatusBadge tone={discountApprovalStatus.tone}>{discountApprovalStatus.label}</StatusBadge>
                              {discountApproval.status === 'REJECTED' && discountApproval.rejectionReason ? (
                                <span className="text-xs text-danger">เหตุผล: {discountApproval.rejectionReason}</span>
                              ) : null}
                              {discountApproval.status === 'PENDING' && canDecideDiscountApproval(user) ? (
                                <div className="flex gap-2">
                                  <Button type="button" variant="success" size="sm" disabled={approveDiscount.isPending}
                                    onClick={() => setConfirmAction({ type: 'approveDiscount', approval: discountApproval })}>
                                    อนุมัติส่วนลด
                                  </Button>
                                  <Button type="button" variant="danger" size="sm" disabled={rejectDiscount.isPending}
                                    onClick={() => setConfirmAction({ type: 'rejectDiscount', approval: discountApproval })}>
                                    ปฏิเสธส่วนลด
                                  </Button>
                                </div>
                              ) : null}
                            </div>
                          ) : null}
                          {editable ? (
                            <textarea
                              className="mt-2 w-full rounded border border-border p-1 text-xs"
                              placeholder="หมายเหตุรายการ"
                              value={draft.itemNotes ?? item.itemNotes ?? ''}
                              onChange={(e) => setQuotationItemDrafts((cur) => ({
                                ...cur, [item.id]: { ...cur[item.id], itemNotes: e.target.value },
                              }))}
                            />
                          ) : item.itemNotes ? <p className="mt-1 text-xs text-text-muted">{item.itemNotes}</p> : null}
                        </div>
                      );
                    })}
                  </div>

                  <div className="grid gap-2 text-sm md:grid-cols-3">
                    <div><strong>ยอดรวม (ก่อน VAT)</strong> {formatCurrency(quotation.subtotalAmount, quotation.currency)}</div>
                    <div><strong>VAT 7%</strong> {formatCurrency(quotation.vatAmount, quotation.currency)}</div>
                    <div><strong>รวมทั้งสิ้น</strong> {formatCurrency(quotation.grandTotal, quotation.currency)}</div>
                  </div>

                  {editable ? (
                    <div className="grid gap-2 md:grid-cols-2">
                      <input className="rounded border border-border p-2 text-sm" placeholder="เงื่อนไขการชำระเงิน"
                        value={quotationHeaderDraft.paymentTerms ?? quotation.paymentTerms ?? ''}
                        onChange={(e) => setQuotationHeaderDraft((cur) => ({ ...cur, paymentTerms: e.target.value }))} />
                      <input className="rounded border border-border p-2 text-sm" placeholder="ระยะเวลาส่งมอบ"
                        value={quotationHeaderDraft.leadTime ?? quotation.leadTime ?? ''}
                        onChange={(e) => setQuotationHeaderDraft((cur) => ({ ...cur, leadTime: e.target.value }))} />
                      <input className="rounded border border-border p-2 text-sm" placeholder="เงื่อนไขการจัดส่ง"
                        value={quotationHeaderDraft.deliveryTerms ?? quotation.deliveryTerms ?? ''}
                        onChange={(e) => setQuotationHeaderDraft((cur) => ({ ...cur, deliveryTerms: e.target.value }))} />
                      <input type="date" className="rounded border border-border p-2 text-sm"
                        value={quotationHeaderDraft.validityDate ?? quotation.validityDate ?? ''}
                        onChange={(e) => setQuotationHeaderDraft((cur) => ({ ...cur, validityDate: e.target.value }))} />
                      <textarea className="rounded border border-border p-2 text-sm md:col-span-2" placeholder="หมายเหตุถึงลูกค้า"
                        value={quotationHeaderDraft.customerNotes ?? quotation.customerNotes ?? ''}
                        onChange={(e) => setQuotationHeaderDraft((cur) => ({ ...cur, customerNotes: e.target.value }))} />
                    </div>
                  ) : (
                    <div className="grid gap-1 text-sm text-text-muted md:grid-cols-2">
                      <div>เงื่อนไขการชำระเงิน: {quotation.paymentTerms || '-'}</div>
                      <div>ระยะเวลาส่งมอบ: {quotation.leadTime || '-'}</div>
                      <div>เงื่อนไขการจัดส่ง: {quotation.deliveryTerms || '-'}</div>
                      <div>ยืนราคาถึง: {quotation.validityDate ? formatThaiDate(quotation.validityDate) : '-'}</div>
                      {quotation.customerNotes ? <div className="md:col-span-2">หมายเหตุ: {quotation.customerNotes}</div> : null}
                    </div>
                  )}

                  <div className="flex flex-wrap gap-2">
                    <Button type="button" variant="secondary" disabled={downloadingQuotationFormat === 'pdf'}
                      onClick={() => handleDownloadCustomerQuotation(quotation, 'pdf')}>
                      ดูตัวอย่าง PDF
                    </Button>
                    <Button type="button" variant="secondary" disabled={downloadingQuotationFormat === 'xlsx'}
                      onClick={() => handleDownloadCustomerQuotation(quotation, 'xlsx')}>
                      ดูตัวอย่าง Excel
                    </Button>
                    {editable ? (
                      <Fragment key={`quotation-actions-${quotation.id}`}>
                        <Button type="button" variant="secondary" onClick={() => saveQuotation.mutate(quotation)} disabled={saveQuotation.isPending}>
                          บันทึก
                        </Button>
                        <Button type="button" variant="primary" disabled={issueQuotation.isPending}
                          onClick={() => setConfirmAction({ type: 'issueQuotation', quotation })}>
                          ออกใบเสนอราคา
                        </Button>
                        <Button type="button" variant="danger" disabled={cancelQuotation.isPending}
                          onClick={() => cancelQuotation.mutate(quotation)}>
                          ยกเลิกร่าง
                        </Button>
                      </Fragment>
                    ) : null}
                    {/* Widened per design correction 3: reachable once REVISION_REQUESTED too,
                        not only ISSUED — same guard the backend's createRevision now enforces. */}
                    {canCreateCommercialOnlyRevision(user, summary, quotation) ? (
                      <Button type="button" variant="secondary" disabled={createQuotationRevision.isPending}
                        onClick={() => createQuotationRevision.mutate(quotation)}>
                        {quotation.docStatus === 'REVISION_REQUESTED' ? 'สร้างรอบแก้ไขราคา/เงื่อนไข' : 'สร้างรอบแก้ไขใหม่'}
                      </Button>
                    ) : null}
                    {quotation.docStatus === 'REVISION_REQUESTED' && canManageCustomerQuotation(user, summary) ? (
                      <Button type="button" variant="secondary" onClick={() => setRevisionModalOpen(true)}>
                        สร้างรอบแก้ไขสินค้า/จำนวน/โรงงาน
                      </Button>
                    ) : null}
                  </div>

                  {/* Step 5: outcome-recording — Sales only, ISSUED only. */}
                  {canRecordCustomerQuotationOutcome(user, summary, quotation) ? (
                    <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
                      <strong className="text-sm">บันทึกผลจากลูกค้า</strong>
                      <textarea
                        className="rounded border border-border p-2 text-sm"
                        placeholder="หมายเหตุจากลูกค้า (ถ้ามี)"
                        value={outcomeNote}
                        onChange={(e) => setOutcomeNote(e.target.value)}
                      />
                      <div className="flex flex-wrap gap-2">
                        <Button type="button" variant="primary" disabled={recordQuotationOutcome.isPending}
                          onClick={() => recordQuotationOutcome.mutate({ quotation, outcome: 'ACCEPTED' })}>
                          ลูกค้ายอมรับ
                        </Button>
                        <Button type="button" variant="danger" disabled={recordQuotationOutcome.isPending}
                          onClick={() => recordQuotationOutcome.mutate({ quotation, outcome: 'REJECTED' })}>
                          ลูกค้าปฏิเสธ
                        </Button>
                        <Button type="button" variant="secondary" disabled={recordQuotationOutcome.isPending}
                          onClick={() => recordQuotationOutcome.mutate({ quotation, outcome: 'REVISION_REQUESTED' })}>
                          ลูกค้าขอแก้ไข
                        </Button>
                      </div>
                    </div>
                  ) : null}

                  {/* Read-only outcome summary — visible to everyone (CEO/Import included), once
                      the customer's response has been recorded or the document has moved past
                      ISSUED for any other reason. */}
                  {['ACCEPTED', 'REJECTED', 'REVISION_REQUESTED', 'EXPIRED', 'SUPERSEDED'].includes(quotation.docStatus) ? (
                    <p className="text-sm text-text-muted">
                      ผลใบเสนอราคา: <strong>{quotationStatus.label}</strong>
                      {quotation.outcomeNote ? ` — ${quotation.outcomeNote}` : ''}
                      {quotation.docStatus === 'SUPERSEDED' ? ' (ถูกแทนที่ด้วยเวอร์ชันใหม่แล้ว)' : ''}
                    </p>
                  ) : null}

                  {customerQuotations.length > 1 ? (
                    <div className="mt-2 text-xs text-text-muted">
                      <strong>ประวัติรอบแก้ไข:</strong>{' '}
                      {customerQuotations.map((q) => {
                        const status = quotationStatusLabel(q.docStatus);
                        return `ครั้งที่ ${q.quotationRevisionNo} (${status.label})`;
                      }).join(' · ')}
                    </div>
                  ) : null}
                </div>
              );
            })() : null}
          </div>
        </Panel>
      ) : null}

      {/* Step 6: Deposit, Payment, and Order Confirmation — only once the customer has accepted
          the quotation (Step 5's terminal status). Bridges into the existing, already-tested
          dual-track payment pipeline (TicketService.confirmCustomer/DepositNoticeService) rather
          than inventing a new one — see OrderConfirmationService's own class Javadoc. */}
      {/* This block carried a status check and NO role gate, so Import saw Sales'
          order-confirm/deposit controls once the customer accepted. Same ruling as the two
          panels above — the actions were already server-gated to the ticket owner, so this
          only stops rendering controls Import could never successfully use. */}
      {summary.status === 'QUOTATION_ACCEPTED' && !isImport(user) ? (
        <Panel flush title="ยืนยันคำสั่งซื้อและออกใบแจ้งยอดเงินรับมัดจำ">
          <div className="flex flex-col gap-3 p-4">
            {canConfirmOrder(user, summary) ? (
              <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
                <p className="text-sm text-text-muted">
                  ลูกค้ายอมรับใบเสนอราคาแล้ว — ยืนยันคำสั่งซื้อเพื่อเริ่มขั้นตอนรับมัดจำและนำเข้าสินค้า
                </p>
                <Button type="button" variant="primary" className="self-start" disabled={confirmOrder.isPending}
                  onClick={() => confirmOrder.mutate()}>
                  ยืนยันคำสั่งซื้อ
                </Button>
              </div>
            ) : null}
            {canCreateDepositNoticeFromQuotation(user, summary) ? (
              <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
                <p className="text-sm text-text-muted">
                  ยืนยันคำสั่งซื้อแล้ว — สร้างใบแจ้งยอดเงินรับมัดจำจากใบเสนอราคาที่ลูกค้ายอมรับ (แก้ไข/ออกเอกสารในหน้าใบแจ้งยอดเงินรับมัดจำ)
                </p>
                <label className="flex items-center gap-2 text-sm">
                  % มัดจำ
                  <input type="number" min="0" max="1" step="0.05" className="w-24 rounded border border-border p-1 text-sm"
                    value={depositPercentInput} onChange={(e) => setDepositPercentInput(e.target.value)} />
                </label>
                <Button type="button" variant="primary" className="self-start" disabled={createDepositNoticeFromQuotation.isPending}
                  onClick={() => createDepositNoticeFromQuotation.mutate()}>
                  สร้างใบแจ้งยอดเงินรับมัดจำ
                </Button>
              </div>
            ) : null}
            {!canConfirmOrder(user, summary) && !canCreateDepositNoticeFromQuotation(user, summary) ? (
              <p className="text-sm text-text-muted">
                {summary.orderConfirmedAt
                  ? `ยืนยันคำสั่งซื้อแล้วเมื่อ ${formatThaiDate(summary.orderConfirmedAt)} — ดูใบแจ้งยอดเงินรับมัดจำได้ที่หน้าดีล`
                  : 'ยืนยันคำสั่งซื้อได้เฉพาะเจ้าของดีล (sales)'}
              </p>
            ) : null}
          </div>
        </Panel>
      ) : null}

      <ConfirmDialog
        open={Boolean(confirmAction)}
        title={confirmAction?.type === 'approveDecision' ? 'อนุมัติราคาขาย'
          : confirmAction?.type === 'returnDecision' ? 'ตีกลับให้ฝ่ายนำเข้าแก้ไขต้นทุน'
          : confirmAction?.type === 'issueQuotation' ? 'ออกใบเสนอราคาลูกค้า'
          : confirmAction?.type === 'approveDiscount' ? 'อนุมัติส่วนลด'
          : confirmAction?.type === 'rejectDiscount' ? 'ปฏิเสธส่วนลด'
          : 'เปลี่ยนวิธีกรอกราคา'}
        message={confirmAction?.type === 'approveDecision'
          ? 'เมื่ออนุมัติแล้ว ราคาขายจะถูกส่งให้ฝ่ายขายและไม่สามารถแก้ไขราคานี้ได้อีก (ราคานี้เป็นราคาก่อน VAT — ยังไม่รวมภาษีมูลค่าเพิ่ม 7%)'
          : confirmAction?.type === 'returnDecision'
            ? 'ระบุเหตุผลที่ตีกลับให้ฝ่ายนำเข้าคำนวณต้นทุนใหม่'
            : confirmAction?.type === 'issueQuotation'
              ? 'เมื่อออกใบเสนอราคาแล้ว จะแก้ไขไม่ได้ — การแก้ไขภายหลังต้องสร้างรอบแก้ไขใหม่'
              : confirmAction?.type === 'approveDiscount'
                ? `อนุมัติส่วนลดรายการที่ ${confirmAction?.approval?.quotationItemId} ที่ราคา ${formatCurrency(confirmAction?.approval?.requestedFinalUnitPrice, currentCustomerQuotation?.currency)} — เมื่ออนุมัติแล้ว ใบเสนอราคานี้จะออกได้ตราบใดที่ไม่มีการแก้ไขราคาอีก`
                : confirmAction?.type === 'rejectDiscount'
                  ? 'ระบุเหตุผลที่ปฏิเสธส่วนลดนี้ — ฝ่ายขายจะเห็นเหตุผลนี้และต้องแก้ไขราคาหรือถอนส่วนลดก่อนออกใบเสนอราคาได้'
                  : 'ค่าที่กรอกไว้ของวิธีเดิมจะไม่ถูกใช้ — ราคาสุทธิของทุกรายการจะคำนวณใหม่ตามวิธีที่เลือก และรายการที่ยังไม่มีข้อมูลของวิธีใหม่จะว่างจนกว่าจะกรอก'}
        confirmLabel={confirmAction?.type === 'approveDecision' ? 'อนุมัติ'
          : confirmAction?.type === 'returnDecision' ? 'ตีกลับ'
          : confirmAction?.type === 'issueQuotation' ? 'ออกใบเสนอราคา'
          : confirmAction?.type === 'approveDiscount' ? 'อนุมัติส่วนลด'
          : confirmAction?.type === 'rejectDiscount' ? 'ปฏิเสธส่วนลด'
          : 'เปลี่ยนวิธีกรอกราคา'}
        tone={confirmAction?.type === 'returnDecision' || confirmAction?.type === 'rejectDiscount' ? 'danger' : 'default'}
        requireReason={confirmAction?.type === 'returnDecision' || confirmAction?.type === 'rejectDiscount'}
        reasonLabel={confirmAction?.type === 'rejectDiscount' ? 'เหตุผลที่ปฏิเสธส่วนลด' : 'เหตุผลที่ตีกลับ'}
        busy={approveDecision.isPending || returnDecisionToImport.isPending
          || issueQuotation.isPending || approveDiscount.isPending || rejectDiscount.isPending
          || setCeoPriceMode.isPending}
        onCancel={() => setConfirmAction(null)}
        onConfirm={(reason) => {
          const action = confirmAction;
          setConfirmAction(null);
          if (action?.type === 'approveDecision') approveDecision.mutate(action.decision);
          if (action?.type === 'returnDecision') returnDecisionToImport.mutate({ decision: action.decision, reason });
          if (action?.type === 'issueQuotation') issueQuotation.mutate(action.quotation);
          if (action?.type === 'approveDiscount') approveDiscount.mutate(action.approval);
          if (action?.type === 'rejectDiscount') rejectDiscount.mutate({ approval: action.approval, reason });
          if (action?.type === 'switchPriceMode') setCeoPriceMode.mutate({ decision: action.decision, priceMode: action.priceMode });
        }}
      />

      {emailModalQuote ? (
        <FactoryEmailDraftModal
          quote={emailModalQuote}
          draft={emailDrafts[emailModalQuote.id] ?? {
            emailTo: emailModalQuote.emailTo ?? '',
            emailSubject: emailModalQuote.emailSubject ?? '',
            emailBody: emailModalQuote.emailBody ?? '',
            note: emailModalQuote.note ?? '',
          }}
          onChangeDraft={(next) => setEmailDrafts({ ...emailDrafts, [emailModalQuote.id]: next })}
          onClose={() => setEmailModalQuoteId(null)}
          onSave={() => updateQuote.mutate({
            quote: emailModalQuote,
            draft: emailDrafts[emailModalQuote.id] ?? { emailTo: emailModalQuote.emailTo ?? '', emailSubject: emailModalQuote.emailSubject ?? '', emailBody: emailModalQuote.emailBody ?? '', note: emailModalQuote.note ?? '' },
          })}
          savePending={updateQuote.isPending}
          onCopy={copyFactoryEmail}
          // updateDraft is the contact roles' (import + CEO), DRAFT-only, and guarded by the same
          // DRAFT_STATUSES window as markContacted — outside any of those the modal is view + copy.
          canEdit={canContactFactory && emailModalQuote.status === 'DRAFT' && emailModalQuote.current && inSendWindow}
          attachments={pricingRequestAttachments.filter((attachment) => attachment.includeInFactoryEmail)}
        />
      ) : null}

      {previewContactFactory ? (
        <FactoryContactDialog
          factoryName={previewContactFactory}
          pending={previewBusyFactory === previewContactFactory}
          onCancel={() => setPreviewContactFactory(null)}
          onConfirm={(body) => contactFromPreview(previewContactFactory, body)}
        />
      ) : null}

      {contactQuote ? (
        <FactoryContactDialog
          factoryName={contactQuote.factoryName}
          pending={markContacted.isPending}
          onCancel={() => setContactQuoteId(null)}
          onConfirm={(body) => markContacted.mutate({ quote: contactQuote, body })}
        />
      ) : null}

      {leadTimeDialog && leadTimeDialogQuote ? (
        <LeadTimeChangeDialog
          factoryName={leadTimeDialogQuote.factoryName}
          lines={(leadTimeDialogQuote.items ?? [])
            .map((line) => requestItemById.get(line.pricingRequestItemId))
            .filter((item) => item && isImportLine(item))
            .map((item) => ({ item, name: itemDisplayName(item), current: { min: item.leadTimeMinDays, max: item.leadTimeMaxDays } }))}
          existing={leadTimeDialog.change}
          pending={createLeadTimeChange.isPending || updateLeadTimeChange.isPending}
          onCancel={() => setLeadTimeDialog(null)}
          onSubmit={(body) => (leadTimeDialog.change
            ? updateLeadTimeChange.mutate({ change: leadTimeDialog.change, body })
            : createLeadTimeChange.mutate({ quote: leadTimeDialogQuote, body }))}
        />
      ) : null}

      {costOverrideItem ? (
        <CostOverrideModal
          item={costOverrideItem.item}
          costingItem={costOverrideItem.costingItem}
          pending={overrideItemCost.isPending}
          onClose={() => setCostOverrideItem(null)}
          onSubmit={(payload) => overrideItemCost.mutate(
            { decision: costOverrideItem.decision, item: costOverrideItem.item, ...payload },
            { onSuccess: () => setCostOverrideItem(null) },
          )}
        />
      ) : null}

      {priceOverrideItem ? (
        <PriceOverrideModal
          item={priceOverrideItem.item}
          decision={priceOverrideItem.decision}
          pending={overrideSellingPrice.isPending}
          newForm={priceOverrideItem.decision.priceMode != null}
          onClose={() => setPriceOverrideItem(null)}
          onSubmit={(payload) => overrideSellingPrice.mutate(
            { decision: priceOverrideItem.decision, item: priceOverrideItem.item, ...payload },
            { onSuccess: () => setPriceOverrideItem(null) },
          )}
        />
      ) : null}

      {revisionModalOpen ? (
        <PricingRequestCreateModal
          mode="revision"
          initialValue={request}
          showToast={showToast}
          onClose={() => setRevisionModalOpen(false)}
          onCreated={(result) => {
            setRevisionModalOpen(false);
            invalidate();
            const newId = result?.pricingRequest?.summary?.id;
            if (newId) navigate(`/pricing-requests/${newId}`);
          }}
          createRevisionFn={(id, payload) => api.pricingRequests.createCustomerChangeRevision(id, payload)}
        />
      ) : null}
    </div>
  );
}
