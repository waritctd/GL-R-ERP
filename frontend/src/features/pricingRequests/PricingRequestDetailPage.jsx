import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { isOwnerOrKeyer } from '../tickets/dealOwnership.js';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { hasPermission } from '../../app/permissions.js';
import { quotedPriceInThb } from './quotedPriceThb.js';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Icon } from '../../components/common/Icon.jsx';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { ConfirmOrderDialog } from '../quotations/ConfirmOrderDialog.jsx';
import { FormField } from '../../components/common/FormField.jsx';
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
  confirmOrderBlockedReason,
  canCreateCommercialOnlyRevision,
  canCreateCustomerQuotation,
  canCreateDepositNoticeFromQuotation,
  issuedDepositNotice,
  canDecideDiscountApproval,
  canManageCustomerQuotation,
  canRecordCustomerQuotationOutcome,
  canSeePricingDecisionSalesView,
  canSeeRawPricingDecision,
  canPickupPricingRequest,
  canSubmitPricingRequest,
  canStartCeoReview,
  canViewCustomerQuotation,
  isCustomerQuotationEditable,
  isCustomerQuotationDiscountEditable,
  canTransition,
  itemDisplayName,
  itemFactoryName,
  ceoGateBlockerMessage,
  groupFactoryQuotesByFactory,
  pricingRequestRecipientLabel,
  unitBasisLabel,
} from './pricingRequestMeta.js';
import { PricingRequestCreateModal } from './PricingRequestCreateModal.jsx';
import { CeoPricingHeader, CeoPricingWorkspace } from './CeoPricingWorkspace.jsx';
import {
  ceoRowFigures, cleanNumber, fractionToPct, lineProfitThb, marginOutOfRange, pctToFraction, round2,
} from './ceoPricingRow.js';
import { ImportPricingHeader, ImportPricingWorkspace } from './ImportPricingWorkspace.jsx';
import {
  formatOrDash, formatQty, qtyOf, specFor, productCodeOf, variantOf, qtyDetailOf, metaOf, nextStep,
} from './importPricingModel.js';
import { FactoryContactDialog } from './FactoryContactDialog.jsx';
import { LeadTimeChangeBanner } from './LeadTimeChangeBanner.jsx';
import { LeadTimeChangeDialog } from './LeadTimeChangeDialog.jsx';
import {
  contactedByName,
  formatShortThaiDay,
  isFactoryContacted,
  isImportLine,
  leadTimeRangeText,
  leadTimeWithUnit,
  splitLeadTimeChanges,
} from './factoryContactMeta.js';
import { useUnitBasisCatalog } from './unitBasisCatalog.js';
import { buttonVariants } from '../../components/common/Button.jsx';
import { cn } from '../../utils/cn.js';
import { acceptOutcomeConfirmCopy, piecesPerSqmFromSqmPerPiece, dealQuotationStatusLabel, dropOutcomeKey, generateClientRequestId, newestQuotationByRequest, outcomeKeyFor, outcomeReasonConfirmCopy, shownCustomerQuotation } from '../quotations/quotationMeta.js';
import { SearchableCombobox } from '../../components/common/SearchableCombobox.jsx';
// Reuses the catalog page's add-factory dialog instead of a second copy of the same fields and
// validation (see ImportFactoryPicker below).
import { FactoryFormModal } from '../catalog/PriceImportPage.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';

function isImport(user) {
  return user?.role === 'import';
}

function isSales(user) {
  return user?.role === 'sales';
}

function canSeeRaw(user) {
  return user?.role === 'import' || user?.role === 'ceo';
}

/**
 * Statuses in which Import may name the factory on a blank line. Mirrors
 * PricingRequestService.FACTORY_ROUTING_STATUSES, which is itself
 * FactoryQuoteService.DRAFT_STATUSES: the window in which สร้างร่างอีเมล can still run is exactly
 * the window in which its missing input can still be supplied.
 */
const FACTORY_ROUTING_STATUSES = ['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE'];

// Read-only display of the sales-entered tile fields on each item card. `—` for anything a legacy
// item never carried. The label on THIS page stays โรงงาน, for both the sales-entered value and
// Import's own SetItemFactoryRequest control.
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
 * Seeds the "record the factory's answer" draft. Import types the PRICE and nothing else:
 * quantity, unit and currency come from what Sales requested, so they are carried in this state
 * (the backend still requires them) but never rendered as inputs.
 *
 * `requestItem` is the sales-side PricingRequestItem this quote line was generated from, looked up
 * by pricingRequestItemId. The currency fallback matters: `catalogCurrency` is the currency of the
 * catalog row Sales picked (EUR for the European factories) and must win over a hardcoded 'THB',
 * which stored a price typed as 46 as ฿46 instead of €46 (a 38.5x error in the landed cost).
 */
function defaultResponseItems(quote, requestItemById = new Map()) {
  return (quote?.items ?? []).map((item) => {
    const requestItem = requestItemById.get(item.pricingRequestItemId) ?? {};
    // unitBasis (what LandedCostCalculator does math on: PER_SQM/PER_PIECE/...) and quotedUnit
    // (the display unit a human reads: ตร.ม., PCS, ...) are DIFFERENT fields; sharing one seed
    // variable overwrote a real unit with the basis code (FactoryQuoteRepository.insertDraftItems
    // seeds it from the request's own requested_unit). quotedUnit falls back through the item's
    // own quoted unit, then what Sales requested in free text, then a basis-derived display
    // label, never the basis code.
    //
    // unitBasis is read straight off the item with NO fallback: sales.factory_quote_item.unit_basis
    // is NOT NULL and CHECK-constrained to the four canonical codes, and every quote comes from the
    // API. A `'PER_PIECE'` default would only hide a bad value as a silently wrong basis; an
    // absent one should fail loudly at the backend.
    // Currency and price unit are fixed by Sales on the line and locked for import: the line's own
    // requested terms win over the draft, so what is sent back always matches
    // (FactoryQuoteService#receive 409s anything else). A legacy line has neither and keeps the
    // seeding below.
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

// The same bound as MARGIN_RANGE_ERROR, stated in percent for the new-form NET margin input.
const MARGIN_RANGE_ERROR_PCT = 'อัตรากำไรต้องอยู่ระหว่าง -100 ถึง 99999.9999 (%) (ราคาขายต้องไม่ติดลบและไม่เกินขอบเขต)';
const MARGIN_RANGE_ERROR = 'อัตรากำไรต้องอยู่ระหว่าง -1 ถึง 999.999999 (ราคาขายต้องไม่ติดลบและไม่เกินขอบเขต)';

/**
 * Per-line margin input on a CEO legacy-formula decision line: the CEO sets อัตรากำไร% PER product
 * line (price = cost × (1 + its own margin) × buffer) instead of the one "อัตรากำไรเริ่มต้น" for the
 * whole request. Seeded from the line's {@code proposedMarginPct}. A PLAIN input (no mandatory
 * reason, unlike the cost/price-override modals): {@code onSave} PUTs a single-item
 * {@code marginPct} payload and the server recomputes THIS line's selling price. Own state so the
 * parent's render-time {@code renderCeoDecisionItem} (called in a loop) holds no hook, and it
 * re-syncs when the server value changes after a refetch.
 */
function CeoLineMarginField({ item, disabled, onSave }) {
  const server = item.proposedMarginPct;
  const serverStr = server == null ? '' : String(server);
  const [value, setValue] = useState(serverStr);
  useEffect(() => { setValue(serverStr); }, [serverStr]);

  const parsed = cleanNumber(value);
  const invalid = value.trim() !== '' && marginOutOfRange(parsed);
  const dirty = value.trim() !== '' && !invalid && parsed !== server;

  return (
    <div className="mt-1 flex flex-wrap items-end gap-2">
      <FormField
        label="อัตรากำไร (เช่น 0.3 = 30%)"
        htmlFor={`pcr-ceo-margin-${item.id}`}
        className="mb-0"
        error={invalid ? MARGIN_RANGE_ERROR : null}
      >
        <input
          id={`pcr-ceo-margin-${item.id}`}
          className="w-28"
          type="number"
          step="0.01"
          value={value}
          disabled={disabled}
          onChange={(e) => setValue(e.target.value)}
          data-testid={`pcr-ceo-margin-input-${item.id}`}
        />
      </FormField>
      <Button
        type="button"
        variant="secondary"
        className="text-2xs px-2 py-[3px]"
        disabled={disabled || !dirty}
        onClick={() => onSave(parsed)}
        data-testid={`pcr-ceo-margin-save-${item.id}`}
      >
        บันทึกกำไร
      </Button>
    </div>
  );
}

/**
 * A line's realized margin in BOTH ฿ and % on EVERY price mode. Profit/unit = NET selling price −
 * landed cost; the % is of cost; รวม is profit × quantity. Dashes out when the line has no cost or
 * no price yet.
 */
function LineMarginReadout({ costThb, netPrice, fxRate, qty, testid }) {
  const m = lineProfitThb(costThb, netPrice, fxRate);
  if (m == null) {
    // Distinguish the two null cases: no cost at all vs a cost but no computed net yet (e.g. a
    // SPECIAL_SQM line whose net the server derives on save) — the latter is NOT "missing a price".
    const message = costThb == null ? 'กำไร/หน่วย: — (ยังไม่มีต้นทุน)' : 'กำไร/หน่วย: คำนวณเมื่อบันทึก';
    return (
      <span className="text-2xs text-text-muted" data-testid={testid}>
        {message}
      </span>
    );
  }
  const total = qty != null && qty !== '' ? m.profitPerUnit * Number(qty) : null;
  const tone = m.profitPerUnit < 0 ? 'text-danger' : 'text-success';
  return (
    <span className={`text-2xs font-semibold ${tone}`} data-testid={testid}>
      กำไร/หน่วย: <code>{formatCurrency(round2(m.profitPerUnit), 'THB')}</code>
      {m.pct != null ? ` (${m.pct.toFixed(1)}% ของต้นทุน)` : ''}
      {total != null ? <> · รวม <code>{formatCurrency(round2(total), 'THB')}</code></> : null}
    </span>
  );
}

const CURRENCY_SYMBOLS = { EUR: '€', THB: '฿', USD: '$', CNY: '¥', JPY: '¥', GBP: '£' };
const QUOTE_UNIT_SUFFIX = { PER_SQM: '/ตร.ม.', PER_PIECE: '/แผ่น', PER_BOX: '/กล่อง', PER_LINEAR_M: '/เมตร' };

/**
 * The typed price expressed in the OTHER unit (per ตร.ม. <-> per แผ่น), so import sees both without
 * doing the arithmetic. null until a price and the ตร.ม./แผ่น are known, and for any other unit.
 */
function equivalentUnitPrice(line, requestItem) {
  const price = Number(line.rawUnitPrice);
  const sqmPerPiece = Number(line.sqmPerUnit) > 0 ? Number(line.sqmPerUnit) : Number(requestItem?.sqmPerPiece);
  if (!(price > 0) || !(sqmPerPiece > 0)) return null;
  if (line.unitBasis === 'PER_SQM') return { value: Number((price * sqmPerPiece).toFixed(4)), unitLabel: 'แผ่น' };
  if (line.unitBasis === 'PER_PIECE') return { value: Number((price / sqmPerPiece).toFixed(4)), unitLabel: 'ตร.ม.' };
  return null;
}

function formatCurrency(value, currency = 'THB') {
  if (value == null || value === '') return '-';
  return currency === 'THB' ? formatMoney(value) : `${Number(value).toLocaleString('en-US')} ${currency}`;
}

// ── CEO pricing method ────────────────────────────────────────────────────────────────────
// Mirrors PricingDecisionService#isNewFormEligible exactly: the mode picker is offered only when
// EVERY item of the decision came from a new-form pricing-request item (requestedUnitBasis
// PER_PIECE + a non-null sqmPerPiece). A legacy decision keeps the margin/"ปรับราคาเอง" UI below.
function isNewFormEligibleDecision(decision) {
  return Boolean(decision?.items?.length) && decision.items.every(
    (item) => item.requestedUnitBasis === 'PER_PIECE' && item.sqmPerPiece != null);
}

// THE approve gate, shared by the new-form workspace and the legacy decision card: every rule is one the
// Java approve() enforces (missingBeforeApprove is [] for a new-form decision), plus "unsaved edits"
// (new-form only; the legacy card has none, so it passes false). A pending approve is the caller's own.
function approveBlocked(st, { hasUnsaved = false } = {}) {
  return hasUnsaved
    || st.missingBeforeApprove.length > 0 || st.staleOverrideItems.length > 0
    || st.missingCeoPrice.length > 0 || st.missingCost.length > 0 || st.missingStockListPrice.length > 0
    || (st.newForm && st.decision.priceMode == null);
}

// The `max` attribute on each price-mode input mirrors PricingDecisionService's MAX_PRICE_14_2 /
// MAX_PRICE_12_2 (list/direct NUMERIC(14,2), special NUMERIC(12,2)): a browser-level nudge only;
// the server still rejects an oversized value with 400.
const PRICE_INPUT_MAX_14_2 = '999999999999.99';
const PRICE_INPUT_MAX_12_2 = '9999999999.99';

function cleanResponsePayload(draft) {
  return {
    supplierQuoteRef: draft.supplierQuoteRef || null,
    // Falls back to the first line's currency (sourced from the catalog row Sales picked), not a
    // hardcoded 'THB' (see defaultResponseItems).
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
 * Import's factory-routing control for ONE blank line (rendered only when `canSetItemFactory &&
 * !factoryName`, see the unassigned-factory callout, renderFactoryPicker). A typed factory name
 * that did not exactly match a `price_catalog.factories` master row would save with
 * `resolved_factory_id` NULL and dead-end the downstream factory-email + CEO landed-cost lookups,
 * so Sales/import pick from EXISTING factories only, via {@link SearchableCombobox} over the
 * master list (`factories`, from `GET /api/price-import/factories`), not a free-text `<input>`.
 *
 * Import (the only role that reaches this control) may still add a genuinely new factory
 * in-flow via "เพิ่มโรงงานใหม่", which reuses {@link FactoryFormModal} (the catalog
 * PriceImportPage's add/edit form). `Modal` is `position: fixed` + focus-trapped (see Modal.jsx),
 * so the dialog is reachable regardless of the callout's layout.
 */
function ImportFactoryPicker({
  itemId, factories, countries, loading, loadFailed, value, onChangeValue, onSave, saving, onFactoryAdded,
}) {
  const [addOpen, setAddOpen] = useState(false);
  const [emptyWarned, setEmptyWarned] = useState(false);
  // Hard lock against a double-submit. `saving` (the mutation's isPending) only flips on the NEXT
  // render, so a fast second click (or Enter) can fire between mutate() and that re-render, and
  // setItemFactory answers it with a confusing 409 ("ระบุโรงงานไว้แล้ว") though the save succeeded.
  // This ref closes the window synchronously; released once `saving` returns to false.
  const inFlight = useRef(false);
  useEffect(() => { if (!saving) inFlight.current = false; }, [saving]);
  const options = useMemo(
    () => factories.map((f) => ({ code: String(f.factoryId), nameTh: f.name })),
    [factories],
  );

  function handleSubmit(event) {
    if (saving || inFlight.current) return; // already submitting — swallow the repeat click/Enter
    if (!value) { setEmptyWarned(true); return; } // an empty save says why
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
        {/* NOT disabled on an empty value: a disabled button gives no feedback. It stays clickable
            so handleSubmit can say เลือกโรงงานก่อน; `saving` still blocks the in-flight repeat. */}
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
 * The backend never sends this mail: a human copies it into their own mail client. So the modal
 * has exactly TWO actions beyond closing: บันทึกร่าง (save the draft) and คัดลอกเมล (copy it). The
 * FINAL "ติดต่อโรงงานแล้ว" step is a separate button on the card (FactoryContactDialog), which also
 * records the date. Closing this modal changes no status. ถึง is pre-filled from the factory
 * master's email.
 *
 * `canEdit` is decided by the page (contact role AND the quote still DRAFT AND the request inside
 * FactoryQuoteService.DRAFT_STATUSES, since updateDraft 409s outside it); otherwise the modal is a
 * read-only view that can still copy. `attachments` are the request attachments ticked to travel
 * with the mail (read-only here; the tick lives in the request's own attachment list).
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
 * The CEO's per-line manual cost override. `costingItem` may be undefined only in theory: the
 * button that opens this modal is gated on `costingItem` being present.
 *
 * The mandatory reason is enforced HERE, client-side, on BOTH the set and clear paths:
 * `onSubmit` is never invoked with a blank/whitespace-only reason. The server's own check stays
 * authoritative; this only spares the CEO a round-trip 400.
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
 * "ปรับราคาเอง": a REAL behaviour change, not a relabel of the cost override above. It overrides
 * the SELLING PRICE directly and the formula (cost x margin) stops driving that line, rather than
 * feeding it a different cost. Same shape as {@link CostOverrideModal} (mandatory reason in BOTH
 * directions, current computed value shown) because that pattern is already established, not
 * because the two overrides mean the same thing.
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
                // Reachable ONLY under NET mode (or before a mode is chosen): DIRECT_NET/SPECIAL_SQM
                // never consult it, so the copy says "ส่วนลด" specifically, not the generic "วิธีกรอกราคาที่
                // เลือกไว้".
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

const FX_SOURCE_LABEL = { THB: 'บาท', MANUAL: 'กรอกเอง', BOT: 'ธปท.' };
const fxNote = (decision) => `${FX_SOURCE_LABEL[decision.fxSource] ?? decision.fxSource}, ${formatThaiDate(decision.fxEffectiveDate)}`;

export function PricingRequestDetailPage({ user, showToast }) {
  const { id } = useParams();
  const pricingRequestId = Number(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [responseDrafts, setResponseDrafts] = useState({});
  const [emailDrafts, setEmailDrafts] = useState({});
  const [receiveClientRequestIds, setReceiveClientRequestIds] = useState({});
  const [confirmAction, setConfirmAction] = useState(null);
  // Confirm-order is irreversible -> dialog first.
  const [confirmingOrder, setConfirmingOrder] = useState(false);
  // The customer-change revision UI reuses PricingRequestCreateModal in mode="revision" (seeded
  // from the current request, full item editing).
  const [revisionModalOpen, setRevisionModalOpen] = useState(false);
  // Which factory group's "ร่างอีเมล" modal is open, by factory-quote id (not the quote object),
  // looked up fresh against `factoryQuotes` on every render so it never shows a stale snapshot.
  const [emailModalQuoteId, setEmailModalQuoteId] = useState(null);
  // Which factory card's ติดต่อโรงงานแล้ว dialog is open (by quote id), and the lead-time change
  // panel being filled in: { quoteId, change }, with `change` set when editing a PENDING request
  // instead of raising a new one.
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

  // send() completes synchronously (DRAFT -> REQUESTED in one call), so there is no in-flight state
  // to poll: the mutation's onSuccess invalidate (see useActionMutation) shows the result.
  const factoryQuery = useQuery({
    queryKey: queryKeys.pricingRequestFactoryQuotes(pricingRequestId),
    queryFn: () => api.pricingRequests.listFactoryQuotes(pricingRequestId).then((r) => r.items ?? []),
    // Import and CEO (server: ceo/import). The factory-quote WORKSPACE renders for Import only; the
    // CEO reads this just for Import's quoted price on each item card.
    enabled: Number.isFinite(pricingRequestId) && canSeeRaw(user),
  });

  const costingQuery = useQuery({
    queryKey: queryKeys.pricingRequestCostings(pricingRequestId),
    queryFn: () => api.pricingRequests.listCostings(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId) && canSeeRaw(user),
  });

  // The factory master list + country roster feeding ImportFactoryPicker below. Gated on the ROLE
  // alone (isImport(user)) rather than canSetItemFactory (computed after `summary` resolves), so
  // import does not wait on the detail fetch; the picker is never rendered for anyone else.
  // `countries()` is read-widened to import already (PriceImportService).
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

  // The real FX rate table (CeoSettingsPage's own fxRates query, same queryKeys.fxRates()/
  // api.fxRates.list()) feeds the read-only currency/FX note and the baht conversion on the factory
  // cards, not a hand-typed list that could drift from it.
  const fxRatesQuery = useQuery({
    queryKey: queryKeys.fxRates(),
    queryFn: () => api.fxRates.list().then((r) => r.fxRates ?? []),
    // The CEO needs the same list for the baht conversion of a line's catalogue price (GET
    // /api/fx-rates is ceo/import/sales/sales_manager on the server).
    enabled: Number.isFinite(pricingRequestId) && canSeeRaw(user),
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

  // The LIVE selling_buffer, so the "วิธีคำนวณราคานี้" panel's formula text shows the buffer that
  // actually ran. selling_price_round_up_to is dead (selling price rounds HALF_UP 2dp) and not
  // shown. Same read gate as PricingFormulaConfigController ({ceo, import}); this page's
  // canSeeRawPricingDecision is a superset, so fetching here opens no new access.
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

  // Customer Quotation Generation and Issuance. Every viewer role canViewCustomerQuotation allows
  // may fetch the list (owner-scoped for sales, like the sales-view decision query above); account
  // never fires this query, matching its total exclusion server-side.
  const customerQuotationsQuery = useQuery({
    queryKey: queryKeys.customerQuotations(pricingRequestId),
    queryFn: () => api.pricingRequests.listCustomerQuotations(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId) && canViewCustomerQuotation(user, detailQuery.data?.summary),
  });
  // The NEW engine's counterpart of the query above, for the SAME "ใบเสนอราคาลูกค้า" panel below.
  // Gated on canViewCustomerQuotation (which mirrors the LEGACY engine's VIEW_ROLES) and
  // `!isImport(user)`: import is not in PRICING_REQUEST_VIEW_ROLES, so the server would 403 it.
  // The backend gate is what enforces this; this just avoids a doomed request and a raw error.
  const dealQuotationForPrQuery = useQuery({
    queryKey: queryKeys.dealQuotationForPricingRequest(pricingRequestId),
    // Review V4: the server's newest row may be a draft cancelled after แก้ใบเสนอราคา — then the
    // quotation it revised is still this request's, read from the deal's list the way the deal page
    // reads it (newest row that is not CANCELLED). If that list is not readable, the server's row.
    queryFn: async () => {
      const quotation = (await api.dealQuotations.findForPricingRequest(pricingRequestId)).quotation ?? null;
      if (quotation?.docStatus !== 'CANCELLED') return quotation;
      try {
        const rows = (await api.dealQuotations.listForTicket(Number(detailQuery.data?.summary?.ticketId))).items ?? [];
        return newestQuotationByRequest(rows).get(pricingRequestId) ?? null;
      } catch {
        return quotation;
      }
    },
    enabled: Number.isFinite(pricingRequestId) && canViewCustomerQuotation(user, detailQuery.data?.summary)
      && !isImport(user),
  });

  // Sales-level supporting attachments on the request itself. Every viewer role can see the list
  // (requireViewable's scoping applies server-side: a non-owner sales rep never reaches this
  // page's detailQuery), so no separate check is needed here.
  const attachmentsQuery = useQuery({
    queryKey: queryKeys.pricingRequestAttachments(pricingRequestId),
    queryFn: () => api.pricingRequests.listAttachments(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId),
  });

  // Lead-time change requests of this pricing request. LeadTimeChangeService lets import, the CEO,
  // the owning rep and sales_manager read them (anyone else would 403), so the query is gated on
  // those roles. Non-blocking: nothing in the pricing chain reads these rows, so a failed fetch
  // only hides the lead-time UI.
  const leadTimeChangesQuery = useQuery({
    queryKey: queryKeys.pricingRequestLeadTimeChanges(pricingRequestId),
    queryFn: () => api.leadTimeChanges.listForPricingRequest(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId)
      && ['import', 'ceo', 'sales', 'sales_manager'].includes(user?.role),
  });
  // The deal's stored ใบขอซื้อ rows, read-only on the factory card for import/CEO. One IR per
  // factory; same query key as DealFulfilmentPanel / the register, so a create there shows here.
  const storedIrTicketId = detailQuery.data?.summary?.ticketId;
  const storedIrQuery = useQuery({
    queryKey: queryKeys.storedImportRequests(storedIrTicketId),
    queryFn: () => api.storedImportRequests.listForTicket(storedIrTicketId).then((r) => r.importRequests ?? []),
    enabled: storedIrTicketId != null && canSeeRaw(user),
  });

  // The unit-basis vocabulary for the ราคาโรงงาน response unit select below, fetched from the
  // backend and cached for the app's lifetime.
  const { unitBases: unitBasisCatalog } = useUnitBasisCatalog();

  function invalidate() {
    const refetched = Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestDetail(pricingRequestId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestFactoryQuotes(pricingRequestId) }),
    ]);
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestCostings(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestAttachments(pricingRequestId) });
    // A contact / lead-time decision here is visible on the deal page and the /fulfilment worklist
    // too, so those caches are refreshed with it instead of waiting out the 30s staleTime.
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
    // The NEW engine's counterpart of the invalidation above. dealQuotationForPrQuery backs both
    // the outcome-recording panel AND the recreate gate
    // (['EXPIRED','REVISION_REQUESTED','REJECTED'].includes(dealQuotationForPr?.docStatus)) below.
    // ['pricingRequests','detail',id] does NOT prefix-match
    // ['pricingRequests','dealQuotationForPricingRequest',id] (siblings, not parent/child), so
    // without this line the recreate button stays stale until a reload or the 30s staleTime
    // (api/queryClient.js). Nothing else invalidates this key.
    queryClient.invalidateQueries({ queryKey: queryKeys.dealQuotationForPricingRequest(pricingRequestId) });
    // discountApprovals is keyed by quotation id, not pricingRequestId — invalidate the whole
    // family with the shared 'discountApprovals' prefix rather than needing the current
    // quotation's id here too (this function is called from mutations that may have just
    // superseded/replaced it).
    queryClient.invalidateQueries({ queryKey: ['customerQuotations', 'discountApprovals'] });
    queryClient.invalidateQueries({ queryKey: ['pricingRequests', 'queue'] });
    // The deal page's sticky bar reads its pricing requests and quotations. By prefix: that page
    // keys them by its own route param.
    queryClient.invalidateQueries({ queryKey: ['pricingRequests', 'byTicket'] });
    queryClient.invalidateQueries({ queryKey: ['dealQuotations', 'byTicket'] });
    return refetched; // resolves once the detail + factory quotes have been re-read
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
        if (successMessage) showToast?.('success', successMessage);
        if (!options.skipInvalidate) invalidate();
        options.onSuccess?.();
      },
      onError: options.onError ?? ((error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ')),
    });
  }

  // Payload is { factoryId }: a real price_catalog.factories row's id, picked from
  // ImportFactoryPicker's SearchableCombobox, not a free-typed name.
  const setItemFactory = useActionMutation(
    ({ itemId, factoryId }) => api.pricingRequests.setItemFactory(pricingRequestId, itemId, { factoryId }),
    'บันทึกโรงงานแล้ว',
  );
  // รับเรื่อง (pickup) from the request page itself — a SUBMITTED request landed here (e.g. from a
  // notification link) with no way to claim it except going back to the คิวขอราคา queue. Same
  // endpoint + canPickupPricingRequest gate as the queue and the deal panel; server is the authority.
  const pickupRequest = useActionMutation(() => api.pricingRequests.pickup(pricingRequestId), 'รับเรื่องแล้ว');
  // A DRAFT (e.g. a customer-change revision) is sent from its own page; same gate as the deal panel.
  const submitRequest = useActionMutation(() => api.pricingRequests.submit(pricingRequestId), 'ส่งคำขอราคาแล้ว');
  const generateDrafts = useActionMutation(() => api.pricingRequests.generateFactoryEmailDrafts(pricingRequestId), 'สร้างเมลแล้ว');
  const updateQuote = useActionMutation(({ quote, draft }) => api.pricingRequests.updateFactoryQuote(quote.id, draft), 'บันทึกร่างแล้ว');
  // Records that import (or the CEO) already contacted this factory: FINAL, no undo. Closes its
  // dialog only on success so a 409 ("already contacted") leaves the user looking at what they tried.
  const markContacted = useActionMutation(
    ({ quote, body }) => api.pricingRequests.markFactoryQuoteContacted(quote.id, body),
    'บันทึกว่าติดต่อโรงงานแล้ว',
    { onSuccess: () => setContactQuoteId(null) },
  );
  // Lead-time change requests. Create/update/withdraw are import's; approve/reject the owning
  // rep's / a sales manager's. A 409 on a decision means import edited the request after the
  // banner loaded: say so and let the refetch (invalidate) show the current version.
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
    // The request moved under us: refetch so the banner shows the current version.
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
   * ยืนยันราคาเสนอ: ONE primary action per factory group, doing what the two buttons it replaces
   * (บันทึกคำตอบ/รอบแก้ไข + ส่งให้ CEO อนุมัติราคา) did, never more: record whatever
   * price/unit/currency/note is in the draft (FactoryQuoteService.receive(), the only path a fresh
   * DRAFT/REQUESTED quote can leave those statuses through), then mark the result ready for the
   * CEO (FactoryQuoteService.markReadyForCosting -> READY_FOR_COSTING, the only status this
   * action may reach). It adds no status and does not advance the parent pricing request itself;
   * markReadyForCosting's own auto-advance of the parent once every current quote is ready is
   * existing backend behaviour.
   *
   * The receive() call is SKIPPED when a response is already on file (RESPONSE_RECEIVED /
   * NEGOTIATING) AND the draft was never touched this session. Calling it unconditionally would be
   * wrong, not just redundant: past the FIRST response, receive() supersedes the current quote and
   * creates a new revision on EVERY call, notifying the CEO of a "revised" price that never
   * changed. `dirty` is whether `responseDrafts[id]` exists: it is only ever written by a change
   * handler, never seeded eagerly (the render-time `?? {defaults}` computes the default without
   * touching state).
   */
  // Per-quote in-flight set: confirms on different factories can overlap, and one finishing must not
  // re-enable another's button. The CEO-gate notice below also waits until this set is EMPTY, so a
  // confirm that lands while another is still running cannot name the running one as "still waiting".
  const [confirmingFactoryQuoteIds, setConfirmingFactoryQuoteIds] = useState(() => new Set());
  // Set to this request's id after a confirm; the persistent notice (a toast's 3.2 s cannot carry a
  // blocker list) is computed from the live queries while it matches. Cleared on the next confirm.
  const [ceoGateCheckedForId, setCeoGateCheckedForId] = useState(null);

  async function confirmFactoryQuote(quote, draft) {
    const dirty = Boolean(responseDrafts[quote.id]);
    const needsReceive = dirty || ['DRAFT', 'REQUESTED'].includes(quote.status);
    setConfirmingFactoryQuoteIds((cur) => new Set(cur).add(quote.id));
    setCeoGateCheckedForId(null);
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
      // Drop the local draft entirely: the invalidate() below re-seeds the response grid from the
      // server's now-current (possibly renumbered, if receive() created a revision) quote, so a
      // stale local edit cannot linger behind a value the server has moved past.
      setResponseDrafts((cur) => {
        const next = { ...cur };
        delete next[quote.id];
        if (targetId !== quote.id) delete next[targetId];
        return next;
      });
      showToast?.('success', 'ยืนยันราคาเสนอแล้ว');
      // markReadyForCosting advances the request only when every import line is routed and covered
      // by a READY_FOR_COSTING quote; otherwise nothing visibly happens. Once the re-read lands,
      // the notice below (computed at render from the live queries) says what still blocks it.
      await invalidate(); // never rejects: refetchQueries swallows fetch errors unless throwOnError
      setCeoGateCheckedForId(pricingRequestId);
    } catch (error) {
      showToast?.('error', error.message || 'ยืนยันราคาเสนอไม่สำเร็จ');
    } finally {
      setConfirmingFactoryQuoteIds((cur) => {
        const next = new Set(cur);
        next.delete(quote.id);
        return next;
      });
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
  // Typed and shown as a PERCENT (30); the call below sends the stored fraction (0.30).
  const resolvedDefaultMargin = decisionDefaultMargin
    ?? fractionToPct(formulaConfigQuery.data?.defaultMarginPct ?? 0.3);
  const [startReviewClientRequestId] = useState(() => generateClientRequestId());
  // startCeoReview/recalculateDecisionCost hit LandedCostCalculator, whose 422 is a multi-line
  // "one heading + one bullet per problem" message (aggregateProblems) or FxResolver's
  // staleness/missing-rate message. A 3.2s toast that collapses '\n' to a space (Toast.jsx renders
  // a plain <span>) cannot carry that, so the message renders inline from this state near
  // whichever control is on screen. Shared by both mutations: their triggers are mutually
  // exclusive (recalculate only renders once currentDecision exists; start-review only while it
  // does not).
  const [ceoCostingError, setCeoCostingError] = useState(null);
  // For a LEGACY (non-new-form) decision the only CEO-typed input on an item is "ปรับราคาเอง",
  // which opens PriceOverrideModal and needs no draft state (the modal owns its form state, like
  // costOverrideItem below). A NEW-FORM decision does not use the modal: its ราคาตั้ง is a typed
  // input backed by ceoPriceDrafts.
  const [priceOverrideItem, setPriceOverrideItem] = useState(null);
  // Per-item draft state for the CEO price-mode inputs (list price/discount, ราคาพิเศษ, or
  // ราคาสุทธิ), keyed by pricingDecisionItemId, only populated for a new-form-eligible decision.
  // Local state (not a mutation per keystroke) so the CEO can type before saving; "บันทึกราคา
  // (n รายการ)" sends each dirty row in its own PUT, like the legacy override modal.
  const [ceoPriceDrafts, setCeoPriceDrafts] = useState({});
  const [savingAllCeoRows, setSavingAllCeoRows] = useState(false);
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
  // Customer Decision and Commercial Revisions. Idempotency keys per (engine, quotation, outcome)
  // (outcomeKeyFor).
  const outcomeClientRequestIds = useRef({});
  const [outcomeNote, setOutcomeNote] = useState('');
  // The ACCEPTED outcome a DESIGNER/OWNER request asks about first: { engine, quotation }.
  const [acceptToConfirm, setAcceptToConfirm] = useState(null);
  // ปฏิเสธ / ขอแก้ไข ask for the customer's reason first, both engines: { engine, quotation, outcome }.
  const [outcomeToConfirm, setOutcomeToConfirm] = useState(null);
  // Step 6: Deposit, Payment, and Order Confirmation.
  const [confirmOrderClientRequestId, setConfirmOrderClientRequestId] = useState(() => generateClientRequestId());
  const startCeoReview = useActionMutation(
    () => api.pricingRequests.startPricingDecision(pricingRequestId, {
      defaultMarginPct: pctToFraction(resolvedDefaultMargin),
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
  // "ปรับราคาเอง": reuses PUT /pricing-decisions/{id} (PricingDecisionService#update ->
  // applyItemUpdates) with a single-item payload instead of a new route.
  // sellingPriceOverride/clearSellingPriceOverride/decisionNote are the fields that matter;
  // marginPct/minimumSellingPrice are left unset (server leaves them unchanged, COALESCE semantics).
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
  // Per-line margin: same PUT /pricing-decisions/{id} as the price override above, with a
  // single-item marginPct payload; applyItemUpdates recomputes THIS line's selling price from
  // cost × (1 + margin). No reason field: a margin is the ordinary formula input, not an override.
  // The clear* flags are omitted (the record is wire-tolerant, they default to false), so every
  // other item column is unchanged.
  const saveLineMargin = useActionMutation(
    ({ decision, item, marginPct }) =>
      api.pricingRequests.updatePricingDecision(decision.id, {
        items: [{ pricingDecisionItemId: item.id, marginPct }],
      }),
    'บันทึกอัตรากำไรแล้ว',
  );
  // The mode picker: one PUT that sets priceMode header-side (only the first time; the request
  // omits it once set, per the "choose once" rule). Separate from the per-item price mutation
  // below so switching modes never also sends a stale item draft.
  const setCeoPriceMode = useActionMutation(
    ({ decision, priceMode }) => api.pricingRequests.updatePricingDecision(decision.id, { priceMode }),
    'เลือกวิธีกรอกราคาแล้ว',
    // The server recomputes every item's net for the new mode, so any unsent per-item draft was
    // typed against the OLD mode and would linger as a phantom pending edit.
    { onSuccess: () => setCeoPriceDrafts({}) },
  );
  // Saves one item's price-mode inputs (list price/discount, ราคาพิเศษ, or ราคาสุทธิ, per the
  // decision's priceMode); mirrors PricingDecisionService#applyItemUpdates's field set. The server
  // derives and returns netUnitPrice; this component never computes the stored value
  // (previewCeoNetUnitPrice above is a preview only).
  // `clears` carries the explicit "really clear this field" signals (clearDiscountPct/
  // clearSpecialPriceSqm/clearDirectNetPrice): `null` is indistinguishable from "not touched" on
  // the server (COALESCE), so a genuine clear (e.g. "ล้างส่วนลด") must set the matching `clearXxx`.
  // `listUnitPrice`/`clearListUnitPrice` are not sent for the legacy path (the CEO cannot type
  // ราคาตั้ง there, it is the server formula price), but the CEO pricing workspace
  // (CeoPricingWorkspace) lets the CEO type ราคาตั้ง under NET, routed by line type because the
  // backend accepts the two fields on different lines (PricingDecisionService#applyItemUpdates):
  //   - IMPORT line (stockSource null): `sellingPriceOverride` + a mandatory `decisionNote`. NOT
  //     `listUnitPrice`, which the backend refuses with a 400 on import lines. An override is
  //     NET's effective list price (manual ?? list) and survives cost recalculation.
  //   - STOCK line (stockSource set): `listUnitPrice`, the only line type that accepts it, and
  //     the field the CEO types for a สต็อก line that has no formula price.
  // Neither key is sent unless the CEO actually changed ราคาตั้ง, so a discount-only save leaves
  // the override alone. Blanking the input on an import line that has an override clears it.
  const saveCeoItemPriceFn = ({ decision, item, draft, clears = {} }) => {
    const isStockLine = item.stockSource != null;
    // Per-line margin: under NET a live อัตรากำไร draft is saved as marginPct and the server derives
    // ราคาตั้ง from it via the formula (no override, no reason). It is mutually exclusive with a
    // manual ราคาตั้ง override, so when present it is the ONLY list-side field sent.
    const marginValue = decision.priceMode === 'NET' && !isStockLine && !clears.clearSellingPriceOverride
      ? pctToFraction(draft.marginPct) : null;
    const currentList = item.manualSellingPricePerRequestedUnit ?? item.listUnitPrice;
    const listDraft = decision.priceMode === 'NET' || isStockLine ? draft.listUnitPrice : undefined;
    const listValue = cleanNumber(listDraft);
    const listPart = {};
    if (marginValue != null) {
      listPart.marginPct = marginValue;
    } else if (clears.clearSellingPriceOverride) {
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
        // UpdatePricingDecisionItemRequest declares clearSellingPriceOverride as a primitive
        // boolean. Omitting it fails Jackson deserialization and the whole request 400s with a bare
        // "คำขอไม่ถูกต้อง", so it is always present; listPart sets it true only for an explicit clear.
        clearSellingPriceOverride: false,
        ...listPart,
      }],
    });
  };
  const saveCeoItemPrice = useActionMutation(saveCeoItemPriceFn, 'บันทึกราคาแล้ว');
  // บันทึกราคา (n รายการ) saves row after row and toasts ONCE, so its per-row saves are silent on success.
  // It does not refetch either: saveAllCeoRows refetches ONCE after the loop.
  const saveCeoRowQuiet = useActionMutation(saveCeoItemPriceFn, null, { skipInvalidate: true });
  // Recomputes the bound costing in place, preserving every per-line override (see
  // recalculatePricingDecisionCost in hrApi.js). Never changes status, margins, or approved_*;
  // it only refreshes cost.
  const recalculateDecisionCost = useActionMutation(
    (decision) => api.pricingRequests.recalculatePricingDecisionCost(decision.id),
    'คำนวณต้นทุนใหม่แล้ว',
    {
      onSuccess: () => setCeoCostingError(null),
      // Same reasoning as startCeoReview's onError above — inline, not the toast.
      onError: (error) => setCeoCostingError(error.message || 'คำนวณต้นทุนใหม่ไม่สำเร็จ'),
    },
  );
  // A per-line manual cost override, BESIDE the computed figure (never destroyed). `reason` is
  // mandatory in both directions: the modal refuses to call this without one; the server's check is
  // the backstop.
  const [costOverrideItem, setCostOverrideItem] = useState(null);
  const overrideItemCost = useActionMutation(
    ({ decision, item, manualLandedCostPerUnitThb, reason }) =>
      api.pricingRequests.overridePricingDecisionItemCost(decision.id, item.id, { manualLandedCostPerUnitThb, reason }),
    'บันทึกต้นทุนที่ปรับแล้ว',
  );
  // The per-item duty product-type select (ประเภทสินค้า) is gone ("it's only tiles"). The
  // hrApi/mockApi overridePricingDecisionItemProductType pair and the backend endpoint stay; only
  // this page stopped calling them.
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
  // "เขียนใบเสนอราคาจากคำขอราคา": the direct-deal engine, prefilled from this request's CEO-approved
  // decision. A legacy decision is refused server-side with a clear Thai 409. The button is offered
  // whenever the request is otherwise eligible (canCreateCustomerQuotation's gate) and the error
  // surfaces as a toast, so a legacy request tells the rep why instead of doing nothing.
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
  // CEO discount-approval workflow. Both route through the shared ConfirmDialog below
  // (confirmAction type 'approveDiscount'/'rejectDiscount'), like approveDecision/
  // returnDecisionToImport: approve gets a plain confirm, reject requires a reason.
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
    mutationFn: ({ quotation, outcome, note = outcomeNote }) => api.pricingRequests.recordCustomerQuotationOutcome(quotation.id, {
      outcome,
      customerNote: note || null,
      clientRequestId: outcomeKeyFor(outcomeClientRequestIds.current, `legacy:${quotation.id}`, outcome),
    }),
    onSuccess: (_data, { quotation, outcome }) => {
      dropOutcomeKey(outcomeClientRequestIds.current, `legacy:${quotation.id}`, outcome);
      setOutcomeNote('');
      showToast?.('success', 'บันทึกผลใบเสนอราคาแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  // The SAME outcome action as recordQuotationOutcome above, on the NEW-engine
  // (PRICING_REQUEST-origin) quotation. Shares outcomeNote/outcomeClientRequestIds state with it:
  // a pricing request never carries a live quotation on BOTH engines at once, so only one of the
  // two outcome UI blocks renders and the mutations never race over the same state.
  const recordDealQuotationOutcome = useMutation({
    mutationFn: ({ quotation, outcome, note = outcomeNote }) => api.dealQuotations.recordOutcome(quotation.id, {
      outcome,
      customerNote: note || null,
      clientRequestId: outcomeKeyFor(outcomeClientRequestIds.current, `deal:${quotation.id}`, outcome),
    }),
    onSuccess: (_data, { quotation, outcome }) => {
      dropOutcomeKey(outcomeClientRequestIds.current, `deal:${quotation.id}`, outcome);
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
    mutationFn: () => api.pricingRequests.createDepositNoticeFromQuotation(pricingRequestId, {}),
    onSuccess: () => {
      showToast?.('success', 'สร้างร่างใบแจ้งยอดเงินรับมัดจำแล้ว');
      // Reuse the legacy deposit-notice page as-is: it loads/edits/issues a DRAFT by ticketId, and
      // the draft just created is what it will find.
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

  // Import reads the factory's IR PDF straight off the card (read-only; creating or revising it is
  // sales's, from the deal page).
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
  // ลูกค้ายอมรับ always asks first, both engines.
  const acceptConfirm = acceptOutcomeConfirmCopy(summary?.recipientType);
  function recordAccepted(engine, quotation) {
    (engine === 'deal' ? recordDealQuotationOutcome : recordQuotationOutcome).mutate({ quotation, outcome: 'ACCEPTED' });
  }
  // One deposit notice per deal. Read only when the owner could otherwise be offered "สร้างใบแจ้งยอด
  // เงินรับมัดจำ", so the block can point at an ISSUED one instead.
  const depositNoticesQuery = useQuery({
    queryKey: queryKeys.depositNotices(summary?.ticketId),
    queryFn: () => api.depositNotices.listByTicket(summary.ticketId).then((r) => r.depositNotices ?? []),
    enabled: summary != null && canCreateDepositNoticeFromQuotation(user, summary),
  });
  const issuedNotice = issuedDepositNotice(depositNoticesQuery.data);
  // itemId -> the factoryId Import has picked (as a string, matching SearchableCombobox's `code`
  // convention) for that line; resolved server-side to the canonical master name on save. Same
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
      .filter((entry) => entry.item.stockSource == null && !itemFactoryName(entry.item)),
    [request],
  );
  // Import owns this field, and only while the request is in its hands. NOT an authorization
  // decision — PricingRequestService#setItemFactory is — just whether to offer an input that would
  // otherwise be refused.
  const canSetItemFactory = isImport(user) && FACTORY_ROUTING_STATUSES.includes(summary?.status);
  // Whether FactoryQuoteService.send (and updateDraft, guarded by the same DRAFT_STATUSES) would
  // still accept an action against this request: whether the request itself, not any one quote,
  // sits inside FACTORY_ROUTING_STATUSES (mirrors FactoryQuoteService.DRAFT_STATUSES). Hoisted
  // because FactoryEmailDraftModal needs it too: its send/save gating is a per-QUOTE status read
  // with the same gap the price grid would have.
  const inSendWindow = FACTORY_ROUTING_STATUSES.includes(summary?.status);
  // Mirrors FactoryQuoteService.RESPONSE_STATUSES: the only request statuses in which
  // receive / startNegotiation take prices, so the factory grid is read-only outside them.
  const inResponseWindow = ['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'READY_FOR_CEO_REVIEW'].includes(summary?.status);
  const factoryQuotes = useMemo(() => factoryQuery.data ?? [], [factoryQuery.data]);
  const ceoGateNotice = useMemo(
    () => (ceoGateCheckedForId === pricingRequestId && confirmingFactoryQuoteIds.size === 0 && !detailQuery.isFetching && !factoryQuery.isFetching
      && !detailQuery.isError && !factoryQuery.isError
      ? ceoGateBlockerMessage(request, factoryQuotes)
      : null),
    [ceoGateCheckedForId, pricingRequestId, confirmingFactoryQuoteIds, detailQuery.isFetching, factoryQuery.isFetching,
      detailQuery.isError, factoryQuery.isError, request, factoryQuotes],
  );
  const factoryGroups = useMemo(() => groupFactoryQuotesByFactory(factoryQuotes), [factoryQuotes]);
  // pricingRequestItemId -> that item's line on the CURRENT factory quote, once Import has priced it.
  const importQuoteItemByRequestItem = useMemo(() => {
    const byItem = new Map();
    factoryGroups.forEach((group) => {
      (group.current?.items ?? []).forEach((line) => {
        if (line.rawUnitPrice != null) byItem.set(line.pricingRequestItemId, line);
      });
    });
    return byItem;
  }, [factoryGroups]);
  // Before any ร่างอีเมล is generated there are no factory quotes, so Import needs to see "what
  // needs a price, per factory" up front. This groups the request's OWN items by the factory each
  // is routed to; read-only preview, the real price inputs appear (and unlock per the email-sent
  // nudge) once drafts exist.
  //
  // Not gated on `factoryGroups.length === 0`: `canSetItemFactory` only GAP-FILLS a factory name on
  // a line Sales left blank, never re-routes one (PricingRequestService#setItemFactory is
  // "deliberately a gap-FILL, never a re-route"; a line that already has a factory 409s), while the
  // request is in FACTORY_ROUTING_STATUSES. If that happens AFTER drafts were generated for other
  // lines, the newly-filled item has no factory-quote row (it never went through สร้างร่างอีเมล)
  // and would show in neither `factoryGroups` nor a suppressed preview. So this covers exactly the
  // items no CURRENT factory quote already lists, independent of other items' quotes.
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
      .filter((item) => !coveredItemIds.has(item.id) && item.stockSource == null) // stock lines are never quoted by a factory
      .forEach((item) => {
        const name = itemFactoryName(item) || 'ยังไม่ได้ระบุโรงงาน';
        if (!map.has(name)) map.set(name, { key: name, factoryName: name, items: [] });
        map.get(name).items.push(item);
      });
    return [...map.values()];
  }, [request, coveredItemIds]);
  // Count quoted items plus previewed-but-not-yet-quoted items (`pricePreviewGroups`), so the
  // panel title matches the rows shown in the mixed case.
  const factoryItemCount = useMemo(
    () => factoryGroups.reduce((sum, group) => sum + (group.current.items?.length ?? 0), 0)
      + pricePreviewGroups.reduce((sum, group) => sum + group.items.length, 0),
    [factoryGroups, pricePreviewGroups],
  );
  const emailModalQuote = emailModalQuoteId != null
    ? factoryQuotes.find((q) => q.id === emailModalQuoteId) ?? null
    : null;
  // Who may run the contact step (สร้างเมล + ติดต่อโรงงานแล้ว): import AND the CEO
  // (FactoryQuoteService.CONTACT_ROLES). Price entry, lead-time requests and confirm stay
  // import-only. UI-level gate only; the service is the authority.
  const canContactFactory = user?.role === 'import' || user?.role === 'ceo';
  const contactQuote = contactQuoteId != null ? factoryQuotes.find((q) => q.id === contactQuoteId) ?? null : null;
  const leadTimeChanges = useMemo(() => leadTimeChangesQuery.data ?? [], [leadTimeChangesQuery.data]);
  const leadTimeSplit = useMemo(() => splitLeadTimeChanges(leadTimeChanges), [leadTimeChanges]);
  // B-R3: only the owning rep (sales + ticket creator, matched the way this page matches ownership
  // everywhere: ticketCreatedById === employeeId) or a sales manager decides — never the CEO.
  const canDecideLeadTime = (isSales(user) && isOwnerOrKeyer({ createdById: summary?.ticketCreatedById, keyedById: summary?.ticketKeyedById }, user?.employeeId))
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
  // Returned to import and back READY: the RETURNED decision is history, the CEO starts a fresh one.
  const needsFreshReview = !currentDecision || currentDecision.status === 'RETURNED';
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
  // The NEW engine's counterpart of currentCustomerQuotation above. Both are never LIVE at once,
  // but a CANCELLED legacy quotation can coexist with a live new-engine one (or vice versa), so
  // checking dealQuotationForPr first and falling back to the legacy quotation only when it is
  // absent keeps the panel on "the one that matters" instead of a stale cancelled row.
  const dealQuotationForPr = dealQuotationForPrQuery.data ?? null;
  const shownQuotation = shownCustomerQuotation(dealQuotationForPr, currentCustomerQuotation);
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
  // Mirrors PricingRequestService.createCustomerChangeRevision (:438-465). The status half reads
  // the SUPERSEDED edge of the transition table (`canTransition(status, SUPERSEDED)`) instead of a
  // hand-kept denylist, which drifted from the state machine: it still offered สร้างรอบแก้ไข on a
  // QUOTATION_ACCEPTED deal, which is terminal and 409s ("ลูกค้ายอมรับใบเสนอราคาแล้ว..."). This
  // button and that 409 cannot disagree without the table itself being wrong.
  // QUOTATION_ACCEPTED needs no special case: it is terminal, so it has no SUPERSEDED edge and the
  // predicate is already false for it.
  const canCreateCustomerRevision = isSales(user)
    && isOwnerOrKeyer({ createdById: summary?.ticketCreatedById, keyedById: summary?.ticketKeyedById }, user?.employeeId)
    && summary?.ticketPaymentStatus == null // order confirmed: no new pricing round (the backend 409s it)
    && canTransition(summary?.status, 'SUPERSEDED');
  const pricingRequestAttachments = attachmentsQuery.data ?? [];
  // Mirrors PricingRequestService.ATTACHMENT_EDITABLE_STATUSES: Sales may only upload/delete its
  // own attachments while the request is DRAFT, and only on the request it owns. A wider set would
  // just produce a 409 from uploadAttachment/deleteAttachment.
  const canEditPricingRequestAttachments = isSales(user)
    && isOwnerOrKeyer({ createdById: summary?.ticketCreatedById, keyedById: summary?.ticketKeyedById }, user?.employeeId)
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

  // ── CEO pricing workspace ─────────────────────────────────────────────────────────────────
  // Everything lives in the single "รายการสินค้าและราคาตั้งต้น" panel: the mode picker on top, one
  // pricing row per item INSIDE its spec card, totals and actions below. These are plain render
  // helpers (no hooks) so they can sit after the loading/error guards.
  const ceoWorkspace = canSeeRawPricingDecision(user) && !isImport(user);

  function ceoDecisionState(decision) {
    const decisionStatus = pricingDecisionStatusLabel(decision.status);
    const editable = decisionEditable;
    // ราคาขั้นต่ำ is no longer a CEO input (auto-populated server-side at approve(), see
    // PricingDecisionService#approve), so it never blocks approval here. A "ปรับราคาเอง" override
    // needs no margin: its price is fixed directly (mirrors approve's missingMargin exemption).
    // A new-form decision never carries a margin; its OWN gate (missingCeoPrice, declared below
    // once newForm is known) replaces this one.
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
    // A new-form decision is priced entirely through the mode picker (CeoPricingWorkspace);
    // margin/"ปรับราคาเอง" never apply to it.
    const newForm = isNewFormEligibleDecision(decision);
    const missingCeoPrice = newForm ? decision.items.filter((item) => item.netUnitPrice == null) : [];
    // Mirrors PricingDecisionService.approve's uncosted gate, so the UI does not offer "อนุมัติราคาขาย"
    // on a decision the server would 422. Three rules, all mirrored from Java:
    //   - stock lines (stockSource set) are exempt: they have no cost, only a typed ราคาตั้ง;
    //   - an item with no frozen cost is blocked unless it carries a "ปรับราคาเอง" override ...
    //   - ... and that override only clears the gate when priceMode is null (legacy) or NET. Under
    //     DIRECT_NET/SPECIAL_SQM computeNetUnitPrice never reads it, so those modes need a REAL cost
    //     (ปรับต้นทุนเอง), otherwise an item with zero cost backing could approve.
    const overrideClearsCostGate = decision.priceMode == null || decision.priceMode === 'NET';
    const missingCost = decision.items.filter((item) =>
      item.stockSource == null
      && item.frozenLandedCostPerRequestedUnitThb == null
      && !(overrideClearsCostGate && item.manualSellingPricePerRequestedUnit != null));
    // Java approve stockWithoutListPrice: a stock line needs a ราคาตั้ง > 0 in NET mode only. Under
    // DIRECT_NET/SPECIAL_SQM the quotation prints the net as its ราคา, so ราคาตั้ง is neither asked
    // for nor required.
    const missingStockListPrice = decision.priceMode === 'NET' ? decision.items.filter((item) =>
      item.stockSource != null && !(Number(item.listUnitPrice) > 0)) : [];
    return { decision, decisionStatus, editable, missingBeforeApprove, staleOverrideItems, newForm, missingCeoPrice, missingCost, missingStockListPrice };
  }

  function renderCeoDecisionHeader({ decision, decisionStatus }) {
    return (
      <div className="flex flex-wrap items-center gap-2">
        <strong>{decision.decisionCode}</strong>
        <StatusBadge tone="neutral">เวอร์ชัน {decision.decisionVersionNo}</StatusBadge>
        <StatusBadge tone={decisionStatus.tone}>{decisionStatus.label}</StatusBadge>
        <span className="text-xs text-text-muted">
          {decision.currency} · อัตราแลกเปลี่ยน {decision.fxRateUsed} ({fxNote(decision)})
        </span>
      </div>
    );
  }

  // One row of the redesigned (new-form) CEO pricing table: every number the table and the mobile
  // card show, derived from the decision item + the CEO's unsaved drafts. The pricing rules are the
  // ones this page has always used (formulaFromMargin, previewCeoNetUnitPrice, saveCeoItemPrice);
  // only the margin is new at the edge — typed/shown as a PERCENT, converted to the stored fraction
  // here (pctToFraction) and nowhere else.
  function buildCeoRow(decision, item, requestItem, position, editable) {
    const costingItem = decisionCostingItems.get(item.pricingCostingItemId);
    const hasCostOverride = costingItem?.manualLandedCostPerUnitThb != null;
    const hasPriceOverride = item.manualSellingPricePerRequestedUnit != null;
    const isStockLine = item.stockSource != null;
    const cost = item.frozenLandedCostPerRequestedUnitThb != null ? Number(item.frozenLandedCostPerRequestedUnitThb) : null;
    const rawDraft = ceoPriceDrafts[item.id] ?? {};
    const {
      currentList, marginDraftRaw, marginDrives, marginInvalid, formulaFromMargin, formulaPerPiece, draft,
      autoSpecialPending, listDraft, listInvalid, net, netPending, total, profit, qty, invalid, dirty,
    } = ceoRowFigures({
      decision, item, rawDraft, cost, editable,
      // No guessed buffer: until the formula config loads, do NOT preview a margin-driven price.
      sellingBuffer: Number(formulaConfigQuery.data?.sellingBuffer),
    });
    const sqm = Number(item.sqmPerPiece);
    const st = ceoState;
    let blocker = null;
    // No mode chosen is ONE request-level blocker (model.modeBlocker), never repeated per row; the
    // per-row "no price yet" is just its consequence, so it waits for a mode too.
    if (st.missingCost.includes(item)) blocker = 'ต้องระบุต้นทุนเอง';
    else if (st.staleOverrideItems.includes(item)) blocker = 'ต้นทุนที่ปรับล้าสมัย';
    else if (st.missingStockListPrice.includes(item)) blocker = 'ต้องใส่ราคาตั้ง (สต็อก)';
    else if (st.missingCeoPrice.includes(item) && decision.priceMode != null) blocker = 'ยังไม่มีราคา';
    // During save-all the quiet mutation's pending variables name the row being sent right now.
    const scopedSaving = saveCeoItemPrice.isPending && saveCeoItemPrice.variables?.item?.id === item.id;
    const savingThis = scopedSaving || (saveCeoRowQuiet.isPending && saveCeoRowQuiet.variables?.item?.id === item.id);
    const stale = !isStockLine && Boolean(costingItem?.overrideStale);
    const uncostable = !isStockLine && (Boolean(costingItem?.uncostableReason) || st.missingCost.includes(item));
    const name = requestItem ? itemDisplayName(requestItem)
      : ([item.brand, item.model].filter(Boolean).join(' ') || item.productDescription || '-');
    const factoryName = requestItem ? itemFactoryName(requestItem) : null;
    const importQuoteItem = requestItem ? importQuoteItemByRequestItem.get(requestItem.id) : null;
    const importPriceThb = requestItem ? quotedPriceInThb(importQuoteItem, requestItem, fxRatesQuery.data) : null;
    const productNode = requestItem ? (
      <>
        <span>{`โรงงาน (ที่ฝ่ายขายกรอก): ${requestItem.brand?.trim() || '—'}`}</span>
        <span>{`รหัสสินค้า: ${requestItem.productCode?.trim() || requestItem.catalogProductCode || '-'}`}</span>
        <span>{[requestItem.color, requestItem.texture, requestItem.size, requestItem.thicknessMm != null ? `${requestItem.thicknessMm} มม.` : null].filter(Boolean).join(' · ') || '—'}</span>
        <span>{`โรงงาน: ${factoryName ?? (isStockLine ? 'สต็อก' : 'ยังไม่ได้ระบุ')} · ${formatOrDash(requestItem.originCountry)}`}</span>
        <span data-testid={`pcr-import-price-${requestItem.id}`}>
          ราคาจากฝ่ายนำเข้า: {importQuoteItem?.rawUnitPrice != null
            ? `${formatCurrency(importQuoteItem.rawUnitPrice, importQuoteItem.currency ?? 'THB')}${QUOTE_UNIT_SUFFIX[importQuoteItem.unitBasis] ?? ''}`
            : 'ยังไม่มี'}
        </span>
        {importPriceThb ? (
          <span data-testid={`pcr-import-thb-${requestItem.id}`}>
            {`≈ ${formatMoney(importPriceThb.perSqm)}/ตร.ม. · ${formatMoney(importPriceThb.perPiece)}/แผ่น · รวม ${formatMoney(importPriceThb.total)}`}
          </span>
        ) : null}
        <span>{`จำนวนสั่งซื้อ: ${requestItem.requestedQty ?? '—'} ${requestItem.requestedUnit ?? ''} · กล่อง ${formatOrDash(requestItem.boxes)} · ระยะเวลานำเข้า ${formatLeadTime(requestItem)}`}</span>
        <span>{`ขายแผ่นไม่เต็มกล่อง: ${requestItem.piecesPerBox != null ? (requestItem.roundToFullBox === false ? 'ใช่' : 'ไม่ใช่') : '—'} · เทียบ ตร.ม.: ${formatOrDash(requestItem.requestedQtySqm, ' ตร.ม.')}`}</span>
        <span>{`จำนวนที่กรอก: ${formatQuantityAsEntered(requestItem)} · เผื่อ (wastage): ${formatWastage(requestItem)}`}</span>
      </>
    ) : <span>{name}</span>;
    return {
      item, costingItem, position, name, productNode, editable,
      size: requestItem?.size,
      factoryLabel: isStockLine ? 'สต็อก' : (factoryName ?? item.factoryName),
      isStock: isStockLine,
      hasPriceOverride,
      hasCostOverride,
      hasDiscount: item.discountPct != null,
      cost,
      costPerSqm: cost != null && sqm > 0 ? round2(cost / sqm) : null,
      qtyText: `${item.requestedQuantity} แผ่น`,
      sqmText: `${formatQty(round2(qty * sqm))} ตร.ม.`,
      piecesPerSqm: piecesPerSqmFromSqmPerPiece(item.sqmPerPiece),
      formulaPerPiece: isStockLine && decision.priceMode !== 'NET' ? null : formulaPerPiece,
      formulaPerSqm: formulaPerPiece != null && sqm > 0 ? round2(Number(formulaPerPiece) / sqm) : null,
      margin: {
        show: !isStockLine && cost != null && !hasPriceOverride && decision.priceMode === 'NET',
        value: rawDraft.marginPct ?? fractionToPct(item.proposedMarginPct),
        invalid: marginInvalid,
        error: MARGIN_RANGE_ERROR_PCT,
        // 0 < n < 1 is legal (a real margin under 1%) but is usually a fraction typed by habit: warn, never block.
        looksLikeFraction: marginDraftRaw != null && marginDraftRaw !== '' && Number(marginDraftRaw) > 0 && Number(marginDraftRaw) < 1,
      },
      list: {
        value: formulaFromMargin != null ? formulaFromMargin : (listDraft ?? currentList ?? ''),
        disabled: marginDrives,
        invalid: listInvalid,
        max: PRICE_INPUT_MAX_12_2,
      },
      discount: draft.discountPct ?? item.discountPct ?? '',
      special: draft.specialPriceSqm ?? item.specialPriceSqm ?? '',
      direct: draft.directNetPrice ?? item.directNetPrice ?? '',
      specialMax: PRICE_INPUT_MAX_12_2,
      directMax: PRICE_INPUT_MAX_14_2,
      net,
      netPending,
      netPerSqm: net != null && sqm > 0 ? round2(net / sqm) : null,
      total,
      profitTotal: profit ? round2(profit.profitPerUnit * qty) : null,
      profitPct: profit?.pct ?? null,
      blocker,
      stale,
      uncostable,
      dirty,
      autoSpecialPending,
      invalid,
      draft,
      defaultOpen: Boolean(costingItem?.uncostableReason || costingItem?.overrideStale)
        || blocker === 'ต้องระบุต้นทุนเอง' || blocker === 'ต้นทุนที่ปรับล้าสมัย',
      saving: savingThis,
      clearingDiscount: scopedSaving && Boolean(saveCeoItemPrice.variables?.clears?.clearDiscountPct),
      clearingOverride: scopedSaving && Boolean(saveCeoItemPrice.variables?.clears?.clearSellingPriceOverride),
      setDraft: (patch) => updateCeoPriceDraft(item.id, patch),
      openCostOverride: () => setCostOverrideItem({ decision, item, costingItem }),
      // ล้างส่วนลด / ใช้ราคาตามสูตร are scoped actions: each sends ONLY its own clear (an empty draft), so
      // neither can commit another still-unsent edit, and on success only that field's draft is dropped.
      clearDiscount: () => saveScopedCeoClear(decision, item, { clearDiscountPct: true }, 'discountPct'),
      useFormula: () => saveScopedCeoClear(decision, item, { clearSellingPriceOverride: true }, 'listUnitPrice'),
    };
  }

  function saveScopedCeoClear(decision, item, clears, draftKey) {
    saveCeoItemPrice.mutate({ decision, item, draft: {}, clears }, {
      onSuccess: () => setCeoPriceDrafts((prev) => {
        const next = { ...prev };
        const { [draftKey]: _dropped, ...rest } = next[item.id] ?? {};
        if (Object.keys(rest).length > 0) next[item.id] = rest;
        else delete next[item.id];
        return next;
      }),
    });
  }

  // บันทึกราคา (n รายการ): the EXISTING per-item save, once per dirty row, one after another. A row's
  // drafts clear only when ITS save succeeds; a failing row stays dirty (its error is already toasted
  // by the mutation's onError) and the loop moves on to the next row.
  async function saveAllCeoRows(decision, dirtyRows) {
    setSavingAllCeoRows(true);
    let saved = 0;
    try {
      for (const row of dirtyRows) {
        try {
          await saveCeoRowQuiet.mutateAsync({ decision, item: row.item, draft: row.draft, clears: {} });
          saved += 1;
          // Drop only what was SENT: a key whose value changed since stays as a pending draft.
          setCeoPriceDrafts((prev) => {
            const next = { ...prev };
            const rest = Object.fromEntries(Object.entries(next[row.item.id] ?? {}).filter(([k, v]) => row.draft[k] !== v));
            if (Object.keys(rest).length > 0) next[row.item.id] = rest;
            else delete next[row.item.id];
            return next;
          });
        } catch {
          // toasted by useActionMutation's onError; the row stays dirty
        }
      }
    } finally {
      setSavingAllCeoRows(false);
      invalidate();
    }
    if (saved > 0) showToast?.('success', `บันทึกราคาแล้ว ${saved} รายการ`);
  }

  function renderCeoDecisionItem(decision, item, editable) {
    // The bound costing line this decision item was frozen from; may be undefined (costings not
    // fetched or loaded yet), in which case only the decision item's own frozen cost renders, with
    // no cost-override affordance.
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
    return (
      <div key={item.id} className="rounded-md border border-border-subtle p-3">
        <div className="flex flex-wrap items-center gap-2 text-xs text-text-muted">
          <strong className="text-text">{[item.brand, item.model].filter(Boolean).join(' ') || item.productDescription || '-'}</strong>
          <span>{item.factoryName ?? '-'}</span>
          <span>{item.requestedQuantity} {unitBasisLabel(item.requestedUnitBasis)}</span>
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
            ต้นทุนรวมนำเข้า (รวมค่าขนส่งและภาษี):{' '}
            {costingItem?.uncostableReason ? (
              <span className="font-bold text-warning">คำนวณอัตโนมัติไม่ได้</span>
            ) : (
              <code>{formatCurrency(item.frozenLandedCostPerRequestedUnitThb, 'THB')}</code>
            )}
          </span>
          <span className="text-[length:var(--text-base)] font-bold text-text">
            ราคาขาย (ก่อน VAT): {formatCurrency(effectivePrice, decision.currency)}
          </span>
          <LineMarginReadout
            costThb={item.frozenLandedCostPerRequestedUnitThb}
            netPrice={effectivePrice}
            fxRate={decision.fxRateUsed}
            qty={item.requestedQuantity}
            testid={`pcr-ceo-margin-readout-${item.id}`}
          />
        </div>
        {costingItem?.uncostableReason ? (
          <p className="mt-2 text-xs text-warning">{costingItem.uncostableReason}</p>
        ) : null}
        {/* Per-line margin: on an editable, formula-driven line the CEO sets อัตรากำไร% for THIS
            line here. Hidden once a "ปรับราคาเอง" override is active (the formula no longer drives
            the line) and once the decision is read-only; both fall back to the read-only
            อัตรากำไร/ราคาขั้นต่ำ summary. The NEW-FORM path returns above and never reaches here. */}
        {editable && !hasPriceOverride ? (
          <CeoLineMarginField
            item={item}
            disabled={saveLineMargin.isPending}
            onSave={(marginPct) => saveLineMargin.mutate({ decision, item, marginPct })}
          />
        ) : (
          <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
            <span>อัตรากำไร: {effectiveMargin ?? '-'}</span>
            <span>ราคาขั้นต่ำ: {item.minimumSellingPricePerRequestedUnit != null ? formatCurrency(item.minimumSellingPricePerRequestedUnit, decision.currency) : '-'}</span>
          </div>
        )}
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
              {/* Selling price rounds HALF_UP to 2dp, not UP to a ฿ multiple;
                  selling_price_round_up_to has no effect on this figure (see CeoSettingsPage). */}
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
                  อัตราแลกเปลี่ยน {decision.fxRateUsed} ({fxNote(decision)})
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

  function renderCeoDecisionActions(st) {
    const { decision, editable, missingBeforeApprove, staleOverrideItems, newForm, missingCeoPrice, missingCost, missingStockListPrice } = st;
    if (!editable) return null;
    // No import line means no import cost to fix, so there is nothing to send back (the server refuses too).
    const allLinesAreStock = (request.items ?? []).length > 0 && request.items.every((item) => item.stockSource != null);
    return (
      <>
        <div className="mt-3 flex flex-col gap-2 border-t border-border-subtle pt-3">
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="primary"
              disabled={approveDecision.isPending || approveBlocked(st)}
              onClick={() => setConfirmAction({ type: 'approveDecision', decision })}
              data-testid="pcr-ceo-approve"
            >
              อนุมัติราคาขาย
            </Button>
            {allLinesAreStock ? null : (
              <Button
                type="button"
                variant="secondary"
                disabled={returnDecisionToImport.isPending}
                onClick={() => setConfirmAction({ type: 'returnDecision', decision })}
              >
                ตีกลับให้ฝ่ายนำเข้าแก้ไข
              </Button>
            )}
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
        {/* PricingDecisionService.computeSellingPrice has no VAT term: VAT 7% is added later, only on
            the customer quotation. State it plainly here, matching the "(ก่อน VAT)" convention the
            ใบเสนอราคา summary below uses for ยอดรวม. */}
        <p className="m-0 flex items-start gap-2 rounded-lg border border-info-border bg-info-bg px-3 py-2.5 text-xs text-info-dark">
          <Icon name="info" size={15} className="mt-0.5 shrink-0" />
          ราคาขายทุกรายการในหน้านี้เป็นราคาก่อน VAT — ใบเสนอราคาจะบวก VAT 7% แยกอีกชั้นหนึ่ง
        </p>
        {renderCeoCostingError()}
      </>
    );
  }

  function renderCeoCostingError() {
    return (
      <>
        {/* startCeoReview/recalculateDecisionCost's error renders here: inline, persistent,
            whitespace-pre-line (see ceoCostingError's declaration for why one block covers both
            controls). */}
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

  function renderMissingFactoryBanner() {
    if (!missingFactoryItems.length) return null;
    return (
      <p className="rounded-md border border-warning-border bg-warning-bg p-3 text-xs text-warning-dark">
        {`ยังไม่ได้ระบุโรงงาน ${missingFactoryItems.length} รายการ — ระบบจะสร้างเมลให้เฉพาะรายการที่ระบุโรงงานแล้ว ส่วนรายการต่อไปนี้ต้องระบุโรงงานก่อนจึงจะขอราคาได้: `}
        {missingFactoryItems.map((entry) => `รายการที่ ${entry.position} (${itemDisplayName(entry.item)})`).join(', ')}
        {' — ฝ่ายนำเข้าเป็นผู้ระบุโรงงานให้ในขั้นตอนนี้'}
      </p>
    );
  }

  function renderCeoStartReview() {
    const startMarginValue = cleanNumber(resolvedDefaultMargin);
    // Only a value the CEO TYPED can be a fraction by habit; the configured default always passes.
    const startMarginLooksLikeFraction = decisionDefaultMargin != null
      && startMarginValue != null && startMarginValue > 0 && startMarginValue < 1;
    return (
      <>
        {needsFreshReview && canStartCeoReview(user, summary) ? (
          <div className="flex flex-col gap-1">
            <div className="flex flex-wrap items-center gap-2">
              <label htmlFor="pcr-ceo-start-margin" className="text-xs text-text-muted">อัตรากำไรเริ่มต้น (%)</label>
              <input
                id="pcr-ceo-start-margin"
                className="form-input w-24"
                value={resolvedDefaultMargin}
                onChange={(e) => setDecisionDefaultMargin(e.target.value)}
                placeholder="30"
              />
              <span className="text-xs text-text-muted" data-testid="pcr-ceo-start-margin-suffix">%</span>
              <Button
                type="button"
                variant="primary"
                loading={startCeoReview.isPending}
                // Wait for the formula config, so an early click cannot start the review at the
                // 30 % fallback instead of the CEO's configured default. A failed read is not
                // pending, so the fallback still applies then. A fraction typed by habit (0 < n < 1)
                // is refused: it would start the review at a fraction of one percent.
                disabled={startMarginLooksLikeFraction || (decisionDefaultMargin == null && formulaConfigQuery.isPending)}
                onClick={() => startCeoReview.mutate()}
                data-testid="pcr-ceo-start-review"
              >
                {startCeoReview.isPending ? 'กำลังคำนวณ…' : 'เริ่มพิจารณาราคาขาย'}
              </Button>
            </div>
            <span className="text-2xs text-text-muted">เช่น 30 = 30%</span>
            {startMarginLooksLikeFraction ? <span role="alert" className="text-2xs text-danger">กรอกเป็น % เช่น 30</span> : null}
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
  // omits it. A decision item that matches no request item row is rendered after the rows, never dropped.
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
  const headerActions = (
    <>
      {canPickupPricingRequest(user, summary) ? (
        <Button type="button" variant="primary" disabled={pickupRequest.isPending}
          onClick={() => pickupRequest.mutate()} data-testid="pcr-detail-pickup">
          รับเรื่อง
        </Button>
      ) : null}
      {canSubmitPricingRequest(user, summary) ? (
        <Button type="button" variant="primary" disabled={submitRequest.isPending}
          onClick={() => submitRequest.mutate()} data-testid="pcr-detail-submit">
          ส่งให้ฝ่ายนำเข้า
        </Button>
      ) : null}
      <Button type="button" variant="secondary" onClick={() => navigate(-1)}>
        <Icon name="chevronLeft" size={14} />
        กลับ
      </Button>
    </>
  );

  // The redesigned CEO workspace (new-form decision only; every other view keeps the item cards).
  const ceoNewForm = Boolean(ceoState?.newForm);
  let ceoModel = null;
  if (ceoNewForm) {
    const decision = ceoState.decision;
    const requestItems = request.items ?? [];
    const rows = [
      ...requestItems.flatMap((requestItem, index) => (ceoPricingByRequestItem.has(requestItem.id)
        ? [buildCeoRow(decision, ceoPricingByRequestItem.get(requestItem.id), requestItem, index + 1, ceoState.editable)] : [])),
      ...ceoUnmatchedDecisionItems.map((d, index) => buildCeoRow(decision, d, null, requestItems.length + index + 1, ceoState.editable)),
    ];
    const dirtyRows = rows.filter((row) => row.dirty);
    // The summary is the sum of what the rows show (live draft previews included): sale = row total,
    // cost only over lines with BOTH a cost and a net, profit = the row's own profit.
    let sellTotal = 0;
    let costTotal = 0;
    let profitTotal = 0;
    rows.forEach((row) => {
      if (row.total != null) sellTotal += row.total;
      if (row.cost == null || row.net == null) return;
      costTotal += row.cost * Number(row.item.requestedQuantity);
      profitTotal += row.profitTotal ?? 0;
    });
    const allLinesAreStock = requestItems.length > 0 && requestItems.every((item) => item.stockSource != null);
    ceoModel = {
      decisionCode: decision.decisionCode,
      decisionStatus: ceoState.decisionStatus,
      factoryWarning: renderMissingFactoryBanner(),
      startReview: needsFreshReview && canStartCeoReview(user, summary) ? renderCeoStartReview() : null,
      missingRows: requestItems.flatMap((requestItem, index) => (ceoPricingByRequestItem.has(requestItem.id) ? [] : [{
        requestItem, position: index + 1, name: itemDisplayName(requestItem), qtyText: qtyOf(requestItem),
      }])),
      versionNo: decision.decisionVersionNo,
      priceMode: decision.priceMode,
      editable: ceoState.editable,
      fmt: (value, currency = decision.currency) => formatCurrency(value, currency),
      fxText: `${decision.currency} · อัตราแลกเปลี่ยน ${decision.fxRateUsed} (${fxNote(decision)})`,
      rows,
      blockers: rows.filter((row) => row.blocker),
      modeBlocker: decision.priceMode == null,
      summary: {
        priced: rows.filter((row) => !row.blocker && row.item.netUnitPrice != null).length,
        sellTotal: round2(sellTotal),
        costTotal: round2(costTotal),
        profitTotal: round2(profitTotal),
        profitPct: costTotal > 0 ? (profitTotal / costTotal) * 100 : null,
      },
      dirtyCount: dirtyRows.length,
      saveDisabled: savingAllCeoRows || rows.some((row) => row.invalid),
      savingAll: savingAllCeoRows,
      onSaveAll: () => saveAllCeoRows(decision, dirtyRows),
      // The gates are the ones this page has always mirrored from Java (missingCeoPrice, missingCost,
      // missingStockListPrice, staleOverrideItems, mode == null); unsaved drafts are the only addition.
      approveDisabled: approveDecision.isPending || approveBlocked(ceoState, { hasUnsaved: dirtyRows.length > 0 }),
      onApprove: () => setConfirmAction({ type: 'approveDecision', decision }),
      // No import line means no import cost to fix, so nothing to send back (the server refuses too).
      canReturn: !allLinesAreStock,
      returnPending: returnDecisionToImport.isPending,
      onReturn: () => setConfirmAction({ type: 'returnDecision', decision }),
      modePending: setCeoPriceMode.isPending,
      // Switching AWAY from a chosen mode discards every item's net under the old mode: confirm first.
      onPickMode: (priceMode) => {
        if (decision.priceMode != null) setConfirmAction({ type: 'switchPriceMode', decision, priceMode });
        else setCeoPriceMode.mutate({ decision, priceMode });
      },
      costingError: renderCeoCostingError(),
      recalc: ceoState.editable ? (
        <Button
          type="button"
          variant="icon"
          size="sm"
          title={recalculateDecisionCost.isPending
            ? 'กำลังคำนวณต้นทุนใหม่…'
            : 'คำนวณต้นทุนใหม่ — ดึงต้นทุนและอัตราแลกเปลี่ยนล่าสุด (ไม่ลบค่าที่ปรับเองไว้)'}
          loading={recalculateDecisionCost.isPending}
          disabled={savingAllCeoRows}
          onClick={() => recalculateDecisionCost.mutate(decision)}
          data-testid="pcr-ceo-recalculate-cost"
        >
          <Icon name="refresh" size={16} />
        </Button>
      ) : null,
      history: pricingDecisions.map((d) => `เวอร์ชัน ${d.decisionVersionNo} (${pricingDecisionStatusLabel(d.status).label})`),
    };
  }
  function renderFactoryPicker(item) {
    return (
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
    );
  }

  // Import view-model for ImportPricingWorkspace (gates and handlers live here; the component only draws).
  function buildImportModel() {
    const NOT_CONTACTED_HELPER = 'ติดต่อโรงงานก่อน จึงกรอกราคาและยืนยันได้';

    const quoteCards = factoryGroups.map((group) => {
      const current = group.current;
      const quoteStatus = factoryQuoteStatusLabel(current.status);
      const unsavedDraft = inResponseWindow ? responseDrafts[current.id] : undefined;
      const seedDraft = () => ({
        supplierQuoteRef: current.supplierQuoteRef ?? '',
        defaultCurrency: (() => {
          const locked = [...new Set((current.items ?? []).map((i) => requestItemById.get(i.pricingRequestItemId)?.requestedCurrency).filter(Boolean))];
          return locked.length === 1 ? locked[0] : (current.defaultCurrency ?? 'THB');
        })(),
        paymentTerms: current.paymentTerms ?? '',
        leadTimeText: current.leadTimeText ?? '',
        revisionReason: '',
        negotiationNote: current.negotiationNote ?? '',
        items: defaultResponseItems(current, requestItemById),
      });
      const draft = unsavedDraft ?? seedDraft();
      const dirty = Boolean(unsavedDraft);
      const editable = isImport(user) && current.current && inResponseWindow
        && ['DRAFT', 'REQUESTED', 'RESPONSE_RECEIVED', 'NEGOTIATING', 'READY_FOR_COSTING'].includes(current.status);
      const contacted = isFactoryContacted(current);
      const canConfirm = isImport(user) && current.current && contacted && inResponseWindow
        && (['REQUESTED', 'RESPONSE_RECEIVED', 'NEGOTIATING'].includes(current.status)
          || (current.status === 'READY_FOR_COSTING' && dirty));
      const canNegotiate = isImport(user) && current.status === 'RESPONSE_RECEIVED' && current.current && inResponseWindow;
      const canOpenEmailDraft = canContactFactory && !['CANCELLED', 'SUPERSEDED'].includes(summary?.status);
      const canMarkContacted = canContactFactory && current.current && current.status === 'DRAFT' && inSendWindow;
      const groupRequestItems = draft.items.map((line) => requestItemById.get(line.pricingRequestItemId));
      const quoteIds = group.quotes.map((q) => q.id);
      const pendingChange = leadTimeSplit.pending.find((change) => quoteIds.includes(change.factoryQuoteId)) ?? null;
      const decidedChanges = leadTimeSplit.decided.filter((change) => quoteIds.includes(change.factoryQuoteId));
      const pendingLineById = new Map((pendingChange?.lines ?? []).map((line) => [line.pricingRequestItemId, line]));
      const irRow = (storedIrQuery.data ?? [])
        .filter((row) => row.status !== 'SUPERSEDED')
        .find((row) => (current.factoryId != null && row.factoryId != null
          ? row.factoryId === current.factoryId
          : row.factoryName === current.factoryName)) ?? null;
      const leadTimeLines = draft.items
        .map((line) => requestItemById.get(line.pricingRequestItemId))
        .filter((item) => item && isImportLine(item));
      const canRequestLeadTime = isImport(user) && current.current && !pendingChange && leadTimeLines.length > 0
        && !['CANCELLED', 'SUPERSEDED', 'NOT_AVAILABLE'].includes(current.status);
      const contactedName = contactedByName(current, request?.events);
      const confirmedState = current.status === 'READY_FOR_COSTING' && !dirty;

      // Functional updates: two edits in one batch must both land, so build from the LATEST draft.
      function updateDraft(patch) {
        setResponseDrafts((cur) => ({ ...cur, [current.id]: { ...(cur[current.id] ?? seedDraft()), ...patch } }));
      }
      function updateLine(index, patch) {
        setResponseDrafts((cur) => {
          const latest = cur[current.id] ?? seedDraft();
          const items = [...latest.items];
          items[index] = { ...items[index], ...patch };
          return { ...cur, [current.id]: { ...latest, items } };
        });
      }
      function discardEdits() {
        setResponseDrafts((cur) => {
          const next = { ...cur };
          delete next[current.id];
          return next;
        });
      }

      const lineCurrencies = [...new Set(draft.items.map((line) => line.currency || draft.defaultCurrency || 'THB'))];
      const sharedCurrency = lineCurrencies.length === 1 ? lineCurrencies[0] : null;
      const rows = draft.items.map((line, index) => {
        const itemRef = `รายการ #${line.pricingRequestItemId}`;
        const requested = requestItemById.get(line.pricingRequestItemId);
        const productName = [requested?.catalogBrand ?? requested?.brand, requested?.catalogModel ?? requested?.model]
          .filter(Boolean).join(' ') || requested?.productDescription || itemRef;
        const qty = requested?.requestedQty ?? line.quotedQuantity;
        const unitLabel = requested?.requestedUnit
          ?? unitBasisCatalog.find((option) => option.code === line.unitBasis)?.label
          ?? unitBasisLabel(line.unitBasis);
        // Every line switches แผ่น / ตร.ม.; a line Sales asked per กล่อง or per เมตร also keeps that unit.
        const requestedBasis = requested?.requestedPriceUnitBasis ?? requested?.requestedUnitBasis ?? line.unitBasis;
        const unitCodes = [...new Set([
          'PER_PIECE', 'PER_SQM',
          ...(['PER_BOX', 'PER_LINEAR_M'].includes(requestedBasis) ? [requestedBasis] : []),
          line.unitBasis,
        ].filter(Boolean))];
        const unitName = (code) => unitBasisCatalog.find((option) => option.code === code)?.label ?? unitBasisLabel(code);
        const lineCurrency = line.currency || draft.defaultCurrency || 'THB';
        const serverItem = current.items?.find((i) => i.pricingRequestItemId === line.pricingRequestItemId);
        const approvedPrice = current.status === 'READY_FOR_COSTING' ? serverItem?.rawUnitPrice ?? null : null;
        const equivalentPrice = equivalentUnitPrice(line, requested);
        const thb = requested ? quotedPriceInThb(importQuoteItemByRequestItem.get(requested.id), requested, fxRatesQuery.data) : null;
        const pendingLine = pendingLineById.get(line.pricingRequestItemId);
        let price;
        if (editable && contacted) {
          price = {
            mode: 'input',
            id: `pcr-quote-price-${current.id}-${line.pricingRequestItemId}`,
            value: line.rawUnitPrice ?? '',
            placeholder: `ราคา/${unitBasisCatalog.find((option) => option.code === line.unitBasis)?.label ?? line.quotedUnit ?? ''}`,
            ariaLabel: `ราคาที่เสนอ ${itemRef} (${lineCurrency} ต่อ${unitName(line.unitBasis)})`,
            onChange: (value) => updateLine(index, { rawUnitPrice: sanitizeDecimal(value) }),
            symbol: CURRENCY_SYMBOLS[lineCurrency] ?? lineCurrency,
            // A Sales-LOCKED per-กล่อง / per-เมตร line cannot switch at all (FactoryQuoteService 409s anything
            // but the ตร.ม.<->แผ่น pair on a locked line): show the unit read-only. Legacy lines are unconstrained.
            fixedUnit: ['PER_BOX', 'PER_LINEAR_M'].includes(requested?.requestedPriceUnitBasis) ? `/ ${unitName(line.unitBasis)}` : null,
            unitSwitch: {
              value: line.unitBasis,
              ariaLabel: `หน่วยราคา ${itemRef}`,
              options: unitCodes.map((code) => ({ code, label: `/ ${unitName(code)}` })),
              onChange: (value) => updateLine(index, {
                unitBasis: value,
                quotedUnit: unitBasisCatalog.find((option) => option.code === value)?.label ?? line.quotedUnit,
              }),
            },
            equivalent: equivalentPrice ? {
              testId: `pcr-quote-equiv-${current.id}-${line.pricingRequestItemId}`,
              text: `= ${equivalentPrice.value} ${lineCurrency}/${equivalentPrice.unitLabel}`,
            } : null,
            sqm: line.unitBasis === 'PER_SQM' && !(Number(requested?.sqmPerPiece) > 0) ? {
              id: `pcr-quote-sqm-${current.id}-${line.pricingRequestItemId}`,
              ariaLabel: `ตร.ม./หน่วย ${itemRef}`,
              value: line.sqmPerUnit ?? '',
              onChange: (value) => updateLine(index, { sqmPerUnit: sanitizeDecimal(value) }),
            } : null,
          };
        } else if (editable) {
          price = {
            mode: 'locked',
            ariaLabel: `ราคาที่เสนอ ${itemRef} — ต้องติดต่อโรงงานก่อน`,
            testId: `pcr-quote-price-locked-${current.id}-${line.pricingRequestItemId}`,
          };
        } else {
          price = { mode: 'text', text: formatCurrency(line.rawUnitPrice, lineCurrency) };
        }
        return {
          itemId: line.pricingRequestItemId,
          name: productName,
          variant: variantOf(requested),
          code: `รหัส ${productCodeOf(requested)}`,
          qty: `${qty} ${unitLabel}`,
          qtyDetail: qtyDetailOf(requested),
          spec: specFor(requested),
          price,
          thb: thb ? `≈ ${formatMoney(thb.perSqm)}/ตร.ม. · รวม ${formatMoney(thb.total)}` : null,
          currency: lineCurrency,
          currencyNote: sharedCurrency ? null : lineCurrency,
          priceEmpty: price.mode === 'input' && !thb && cleanNumber(line.rawUnitPrice) == null,
          leadPending: pendingLine
            ? `${leadTimeRangeText(pendingLine.oldMinDays, pendingLine.oldMaxDays)} → ${leadTimeRangeText(pendingLine.newMinDays, pendingLine.newMaxDays)} วัน`
            : null,
          lead: leadTimeWithUnit(requested?.leadTimeMinDays, requested?.leadTimeMaxDays),
          approved: approvedPrice != null ? formatCurrency(approvedPrice, serverItem?.currency || lineCurrency) : null,
        };
      });

      const fxRate = sharedCurrency && sharedCurrency !== 'THB' ? (fxRatesQuery.data ?? []).find((fx) => fx.currency === sharedCurrency)?.rateToThb : null;
      const emptyPrices = draft.items.filter((line) => cleanNumber(line.rawUnitPrice) == null).length;
      let helper = null;
      if (!contacted) helper = NOT_CONTACTED_HELPER;
      else if (canConfirm) {
        helper = `ยืนยันแล้วราคานี้ใช้คำนวณต้นทุน · "กำลังเจรจา" ใช้เมื่อยังต่อรองอยู่${emptyPrices > 0 ? ` · ยังไม่กรอกราคา ${emptyPrices} รายการ` : ''}`;
      }

      return {
        key: group.key,
        quoteId: current.id,
        name: group.factoryName,
        meta: metaOf(groupRequestItems, current.items?.length ?? 0),
        contactedLine: contacted
          ? ['ติดต่อแล้ว', formatShortThaiDay(current.contactedOn)].filter(Boolean).join(' ') + (contactedName ? ` · โดย ${contactedName}` : '')
          : null,
        badges: [
          ...(contacted && (['NOT_AVAILABLE', 'CANCELLED', 'SUPERSEDED'].includes(current.status)
            || !['READY_FOR_COSTING', 'REQUESTED'].includes(current.status)) ? [{ tone: quoteStatus.tone, label: quoteStatus.label }] : []),
          ...(current.revisionNo > 1 ? [{ tone: 'neutral', label: `ครั้งที่ ${current.revisionNo}` }] : []),
        ],
        steps: contacted && current.status === 'NOT_AVAILABLE' ? ['done', 'declined', 'todo']
          : confirmedState ? ['done', 'done', 'done'] : contacted ? ['done', 'now', 'todo'] : ['now', 'todo', 'todo'],
        mail: canOpenEmailDraft ? {
          label: contacted ? 'ดูเมลที่ส่ง' : 'สร้างเมล',
          testId: `pcr-open-email-draft-${current.id}`,
          onClick: () => setEmailModalQuoteId(current.id),
        } : null,
        contact: canMarkContacted ? { testId: `pcr-open-contact-${current.id}`, onClick: () => setContactQuoteId(current.id) } : null,
        contactNote: contacted && current.contactedNote ? current.contactedNote : null,
        terms: {
          // Currency is Sales's and read-only. One shared code is named in the header; mixed codes stay
          // generic here and each row shows its own.
          currency: sharedCurrency,
          symbol: sharedCurrency ? (CURRENCY_SYMBOLS[sharedCurrency] ?? sharedCurrency) : null,
          fxNote: fxRate != null ? `อัตรา ${Number(fxRate).toFixed(2)} บาท (ธปท.)` : null,
          lockReason: editable && !contacted,
        },
        rows,
        leadBlock: pendingChange || canRequestLeadTime || decidedChanges.length > 0 || irRow ? {
          id: current.id,
          pending: pendingChange ? {
            reason: pendingChange.reason,
            canAct: isImport(user),
            busy: withdrawLeadTimeChange.isPending,
            onEdit: () => setLeadTimeDialog({ quoteId: current.id, change: pendingChange }),
            onWithdraw: () => withdrawLeadTimeChange.mutate(pendingChange),
          } : null,
          request: canRequestLeadTime ? { onClick: () => setLeadTimeDialog({ quoteId: current.id, change: null }) } : null,
          history: decidedChanges.map((change) => ({
            id: change.id,
            approved: change.status === 'APPROVED',
            day: change.decidedAt ? ` ${formatShortThaiDay(String(change.decidedAt).slice(0, 10))}` : '',
            lines: change.lines.map((line) => `${lineNameById.get(line.pricingRequestItemId) ?? `รายการ #${line.pricingRequestItemId}`} ${leadTimeRangeText(line.oldMinDays, line.oldMaxDays)} → ${leadTimeRangeText(line.newMinDays, line.newMaxDays)}`).join(', '),
            reason: change.status === 'REJECTED' && change.decisionReason ? ` · เหตุผล: ${change.decisionReason}` : '',
          })),
          ir: irRow ? {
            docNumber: irRow.docNumber ?? 'ใบขอซื้อ',
            issued: irRow.status === 'ISSUED',
            busy: downloadingIrId === irRow.id,
            onDownload: () => downloadIr(irRow),
          } : null,
        } : null,
        footer: {
          editable,
          note: draft.negotiationNote,
          onNote: (value) => updateDraft({ negotiationNote: value }),
          history: group.history.length
            ? group.history.map((q) => `ครั้งที่ ${q.revisionNo} (${factoryQuoteStatusLabel(q.status).label})`).join(' · ')
            : null,
          attachments: (current.attachments ?? []).length || isImport(user) ? {
            canUpload: isImport(user),
            onUpload: (file) => uploadQuoteAttachment.mutate({ quote: current, file }),
            list: (current.attachments ?? []).map((a) => ({ id: a.id, fileName: a.fileName, href: api.pricingRequests.factoryQuoteAttachmentUrl(a.id) })),
          } : null,
          canNegotiate,
          negotiating: negotiateQuote.isPending,
          onNegotiate: () => negotiateQuote.mutate(current),
          showCancel: editable && dirty,
          onCancel: discardEdits,
          canConfirm,
          confirming: confirmingFactoryQuoteIds.has(current.id),
          onConfirm: () => confirmFactoryQuote(current, draft),
          confirmedLine: confirmedState && !canConfirm ? `ยืนยันราคาเสนอแล้ว · ${current.items?.length ?? 0} รายการ` : null,
          helper,
        },
        isContacted: contacted,
        isDeclined: contacted && current.status === 'NOT_AVAILABLE',
        isConfirmed: confirmedState,
      };
    });

    const previewCards = pricePreviewGroups
      .filter((group) => group.factoryName !== 'ยังไม่ได้ระบุโรงงาน')
      .map((group) => {
        const canAct = canContactFactory && inSendWindow;
        return {
          key: group.key,
          quoteId: null,
          name: group.factoryName,
          meta: metaOf(group.items, group.items.length),
          contactedLine: null,
          steps: ['now', 'todo', 'todo'],
          mail: canAct ? {
            label: 'สร้างเมล', busy: previewBusyFactory === group.factoryName, onClick: () => openMailForPreview(group.factoryName),
          } : null,
          contact: canAct ? {
            busy: previewBusyFactory === group.factoryName, onClick: () => setPreviewContactFactory(group.factoryName),
          } : null,
          terms: null,
          rows: group.items.map((item) => ({
            itemId: item.id,
            name: itemDisplayName(item),
            variant: variantOf(item),
            code: `รหัส ${productCodeOf(item)}`,
            qty: qtyOf(item),
            qtyDetail: qtyDetailOf(item),
            spec: specFor(item),
            price: { mode: 'none' },
            thb: null,
            priceEmpty: false,
            leadPending: null,
            lead: leadTimeWithUnit(item.leadTimeMinDays, item.leadTimeMaxDays),
            approved: null,
          })),
          footer: null,
          helper: NOT_CONTACTED_HELPER,
          isContacted: false,
          isConfirmed: false,
        };
      });

    const allCards = [...previewCards, ...quoteCards];
    const { next, sub } = nextStep({
      status: summary?.status,
      cards: allCards,
      missingCount: missingFactoryItems.length,
      firstUnassignedName: missingFactoryItems[0] ? itemDisplayName(missingFactoryItems[0].item) : '',
      canSetItemFactory,
      hasGateNotice: Boolean(ceoGateNotice),
    });
    const stock = (request?.items ?? []).filter((item) => item.stockSource != null);

    return {
      progress: {
        figs: {
          factories: String(allCards.length),
          contacted: `${allCards.filter((c) => c.isContacted).length}/${allCards.length}`,
          confirmed: `${allCards.filter((c) => c.isConfirmed).length}/${allCards.length}`,
          items: String(factoryItemCount + stock.length),
        },
        next,
        sub,
        segs: allCards.map((c) => (c.isConfirmed ? 'confirmed' : c.isContacted ? 'contacted' : 'none')),
      },
      missingBanner: canSetItemFactory ? null : renderMissingFactoryBanner(),
      callout: canSetItemFactory && missingFactoryItems.length > 0 ? {
        entries: missingFactoryItems.map(({ item, position }) => ({
          itemId: item.id,
          position,
          name: itemDisplayName(item),
          detail: qtyOf(item),
          picker: renderFactoryPicker(item),
        })),
      } : null,
      gateNotice: ceoGateNotice,
      showFactoryChangeHint: canSetItemFactory,
      generate: canContactFactory && inSendWindow && pricePreviewGroups.length > 0
        ? { pending: generateDrafts.isPending, onClick: () => generateDrafts.mutate() } : null,
      previewCards,
      quoteCards,
      stock: stock.map((item) => ({
        id: item.id,
        name: itemDisplayName(item),
        variant: variantOf(item),
        qty: [qtyOf(item), qtyDetailOf(item)].filter(Boolean).join(' · '),
      })),
    };
  }
  const importModel = isImport(user) ? buildImportModel() : null;

  return (
    <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
      {importModel ? (
        <ImportPricingHeader summary={summary} status={status} actions={headerActions} />
      ) : ceoNewForm ? (
        <CeoPricingHeader summary={summary} status={status} actions={headerActions} />
      ) : (
        <PageHeader
          title={summary.requestCode}
          subtitle={`${summary.customerName ?? '-'}${summary.projectName ? ` · ${summary.projectName}` : ''}`}
          actions={headerActions}
        />
      )}

      {/* A pending lead-time change waits for the OWNING rep or a sales manager: one banner per
          request, deciding the whole request at once. The CEO and import never see decide controls
          (the CEO sees only the read-only badge on the factory card). */}
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

      {importModel ? <ImportPricingWorkspace model={importModel} /> : null}

      {!ceoNewForm && !importModel ? (
      <Panel flush title="ภาพรวม" actions={<StatusBadge tone={status.tone}>{status.label}</StatusBadge>}>
        <div className="grid gap-3 p-4 md:grid-cols-2">
          <div className="text-sm"><strong>ดีล</strong> <Link to={`/tickets/${summary.ticketId}`} className="text-info underline">{summary.ticketCode}</Link></div>
          <div className="text-sm"><strong>ผู้รับ</strong> {pricingRequestRecipientLabel(summary.recipientType)}{summary.recipientLabel ? ` · ${summary.recipientLabel}` : ''}</div>
          <div className="text-sm"><strong>ต้องการภายใน</strong> {formatThaiDate(summary.requiredDate)}</div>
          <div className="text-sm"><strong>ฝ่ายนำเข้า</strong> ผู้รับเรื่องและประสานราคาโรงงาน</div>
        </div>
        {/* Header terms, read-only for every viewer including Import: they carry no price or
            discount, only when/how the eventual quotation would be paid for and printed. Rendered
            only when at least one is set, so a request with every field null shows nothing extra.
            Not reflected on the quotation itself. */}
        {(summary.paymentTermMode || summary.validityDays != null
          || summary.unitCode || summary.printedByDisplayId != null || summary.salesRepDisplayId != null
          || summary.omitContactHonorific) ? (
          <div className="grid gap-x-4 gap-y-1 border-t border-border p-4 pt-3 text-xs text-text-muted sm:grid-cols-3">
            <span>เงื่อนไขการชำระเงิน: {summary.paymentTermMode === 'CREDIT'
              ? `เครดิต ${summary.creditDays ?? '—'} วัน`
              : summary.paymentTermMode === 'ON_DELIVERY' ? 'ชำระเมื่อส่งมอบ' : '—'}</span>
            <span>ยืนราคา: {summary.validityDays != null ? `${summary.validityDays} วัน` : '—'}</span>
            <span>หน่วยงาน / รหัสผู้ออกแบบ: {summary.unitCode || '—'}</span>
            <span>ไม่เติม &quot;คุณ&quot; หน้าชื่อผู้รับ: {summary.omitContactHonorific ? 'ใช่' : 'ไม่ใช่'}</span>
          </div>
        ) : null}
      </Panel>
      ) : null}

      {importModel ? null : ceoNewForm ? <CeoPricingWorkspace model={ceoModel} /> : (
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
        {/* LEGACY (non-new-form) decision panel only: the decision header, ก่อน VAT note and costing
            error on top; the item cards, then the legacy decision cards, actions and history below.
            A new-form decision renders CeoPricingWorkspace instead. */}
        {ceoWorkspace ? (
          <div className="flex flex-col gap-3 border-b border-border-subtle p-4">
            {ceoState ? renderCeoDecisionHeader(ceoState) : null}
            {renderCeoNoteAndError()}
          </div>
        ) : null}
        <div className="flex flex-col gap-2 p-4">
          {/* The blocking condition, stated BEFORE the สร้างร่างอีเมล button is pressed. generateDrafts
              does not refuse the whole batch over one unresolved line: it drafts every factory that
              DOES resolve and skips the rest (see FactoryQuoteService.generateDrafts), so this says
              partial drafts, plus which lines still block a QUOTE (not a draft) until they get a
              factory. */}
          {renderMissingFactoryBanner()}
          {(request.items ?? []).map((item, index) => {
            const factoryName = itemFactoryName(item);
            // `position` is the 1-based row number the server counts too: findItems returns
            // ORDER BY sort_order, pricing_request_item_id and groupByFactory's sort is stable on
            // sortOrder, so "รายการที่ N" means this exact row on both sides.
            const position = index + 1;
            // The label on this panel is โรงงาน, matching the PCR form's `brandLabel="โรงงาน"`
            // (PricingRequestCreateModal.jsx). Two DIFFERENT values both say โรงงาน, so they are
            // two SEPARATE lines rather than one falling back to the other:
            //   - `salesBrandValue`: exactly what Sales typed/picked (`brand`); a blank shows "—",
            //     not whatever factory routing resolved to.
            //   - `factoryName` (below): the factory this line is actually ROUTED to (the catalog
            //     snapshot's resolvedFactoryName, or Import's SetItemFactoryRequest gap-fill; both
            //     land in the SAME `factory` column via itemFactoryName's precedence), amber +
            //     "ยังไม่ได้ระบุ" when neither is set. Import's factory-assignment control below
            //     also says โรงงาน; this line tells Import what it currently holds without
            //     scrolling to the control.
            // The "is a factory resolved for routing" check (missingFactoryItems,
            // canSetItemFactory, setItemFactory) is separate from this display.
            const salesBrandValue = item.brand?.trim() || null;
            const productCode = item.productCode?.trim() || item.catalogProductCode || null;
            // Import never sees เผื่อ (wastage) or the pre-wastage quantity, only the FINAL order
            // quantity (pieces after wastage + full-box rounding, boxes, sqm equivalent).
            // Sales/CEO/everyone else keeps the full breakdown. UI scoping only, no backend authz change.
            const showWastageDetail = !isImport(user);
            // Import's own quoted price (the CURRENT factory quote's line for this item) — not the
            // catalogue list price. Absent until Import has typed a price.
            const importQuoteItem = importQuoteItemByRequestItem.get(item.id);
            const importPriceThb = quotedPriceInThb(importQuoteItem, item, fxRatesQuery.data);
            return (
              <div
                key={item.id}
                className={cn(
                  'rounded-md border bg-surface p-3',
                  factoryName || item.stockSource != null ? 'border-border' : 'border-warning-border',
                )}
              >
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-xs font-bold text-text-muted">{`รายการที่ ${position}`}</span>
                  <strong>{itemDisplayName(item)}</strong>
                  {item.stockSource != null ? <StatusBadge tone="warning">จากสต็อก</StatusBadge> : null}
                  <span className="text-xs text-text-muted">{item.requestedQty} {item.requestedUnit}</span>
                </div>
                <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
                  <span>{`โรงงาน (ที่ฝ่ายขายกรอก): ${salesBrandValue ?? '—'}`}</span>
                  <span className={factoryName || item.stockSource != null ? undefined : 'font-bold text-warning-dark'}>
                    {`โรงงานที่กำหนด (Import): ${factoryName ?? (item.stockSource != null ? '—' : 'ยังไม่ได้ระบุ')}`}
                  </span>
                  <span>รหัสสินค้า: {productCode ?? '-'}</span>
                  <span data-testid={`pcr-import-price-${item.id}`}>
                    ราคาจากฝ่ายนำเข้า: {importQuoteItem?.rawUnitPrice != null
                      ? `${formatCurrency(importQuoteItem.rawUnitPrice, importQuoteItem.currency ?? 'THB')}${QUOTE_UNIT_SUFFIX[importQuoteItem.unitBasis] ?? ''}`
                      : 'ยังไม่มี'}
                  </span>
                  {importPriceThb ? (
                    <span className="font-bold text-text" data-testid={`pcr-import-thb-${item.id}`}>
                      {`≈ ${formatMoney(importPriceThb.perSqm)}/ตร.ม. · ${formatMoney(importPriceThb.perPiece)}/แผ่น · รวม ${formatMoney(importPriceThb.total)} (${formatQty(importPriceThb.pieces)} แผ่น = ${formatQty(importPriceThb.sqm)} ตร.ม.)`}
                    </span>
                  ) : null}
                </div>
                {/* The sales-entered tile fields, read-only for every viewer; legacy items show "—"
                    for whichever they never carried. */}
                <div className="mt-1.5 grid grid-cols-2 gap-x-4 gap-y-1 border-t border-border pt-1.5 text-xs text-text-muted sm:grid-cols-3">
                  <span>สี: {formatOrDash(item.color)}</span>
                  <span>ผิว: {formatOrDash(item.texture)}</span>
                  <span>ขนาด: {formatOrDash(item.size)}</span>
                  <span>ความหนา: {formatOrDash(item.thicknessMm, ' มม.')}</span>
                  <span>ขายแผ่นไม่เต็มกล่อง: {item.piecesPerBox != null ? (item.roundToFullBox === false ? 'ใช่' : 'ไม่ใช่') : '—'}</span>
                  <span>
                    ประเทศต้นทาง: {formatOrDash(item.originCountry)}
                    {/* The typed name is the actual answer once origin_country is the "อื่นๆ"
                        sentinel; showing just "อื่นๆ" would tell Import nothing. */}
                    {item.originCountry === 'อื่นๆ' ? ` (${formatOrDash(item.originCountryOther)})` : ''}
                  </span>
                  <span>ระยะเวลานำเข้า: {formatLeadTime(item)}</span>
                </div>
                {/* Final order quantity, shown to EVERY viewer: pieces after wastage + full-box
                    rounding (the request's own requestedQty/requestedUnit, shown in the header
                    above), plus boxes and the ตร.ม. equivalent. */}
                <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 border-t border-border pt-1.5 text-xs text-text-muted">
                  <span className="font-bold text-text">จำนวนสั่งซื้อ: {item.requestedQty ?? '—'} {item.requestedUnit ?? ''}</span>
                  <span>กล่อง: {formatOrDash(item.boxes)}</span>
                  <span>เทียบ ตร.ม.: {formatOrDash(item.requestedQtySqm, ' ตร.ม.')}</span>
                </div>
                {/* เผื่อ (wastage) and the AS-ENTERED (pre-wastage) quantity: sales/CEO only; Import
                    sees only the final order quantity above. */}
                {showWastageDetail ? (
                  <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
                    <span>จำนวนที่กรอก: {formatQuantityAsEntered(item)}</span>
                    <span>เผื่อ (wastage): {formatWastage(item)}</span>
                  </div>
                ) : null}
              </div>
            );
          })}
          {ceoWorkspace && needsFreshReview ? (
            <div className="flex flex-col gap-2 pt-1">{renderCeoStartReview()}</div>
          ) : null}
          {/* A LEGACY (non-new-form) decision keeps its existing card markup, below the spec cards. */}
          {ceoState && !ceoState.newForm ? (
            <div className="mt-1 flex flex-col gap-3">
              {ceoState.decision.items.map((d) => renderCeoDecisionItem(ceoState.decision, d, ceoState.editable))}
            </div>
          ) : null}
          {ceoState ? renderCeoDecisionActions(ceoState) : null}
          {ceoWorkspace ? renderCeoHistory() : null}
        </div>
      </Panel>
      )}

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

      {!canSeeRawPricingDecision(user) && canSeePricingDecisionSalesView(user, summary) && decisionSalesView ? (
        <Panel flush title="ราคาขายที่อนุมัติ">
          <div className="flex flex-col gap-2 p-4">
            {/* This is the price the rep quotes to the customer, so the ก่อน VAT label matters most
                here (see the CEO panel's identical note: computeSellingPrice has no VAT term). */}
            <p className="m-0 flex items-start gap-2 rounded-lg border border-info-border bg-info-bg px-3 py-2.5 text-xs text-info-dark">
              <Icon name="info" size={15} className="mt-0.5 shrink-0" />
              ราคาขายทุกรายการในหน้านี้เป็นราคาก่อน VAT — ใบเสนอราคาจะบวก VAT 7% แยกอีกชั้นหนึ่ง
            </p>
            {decisionSalesView.items.map((item) => (
              <div key={item.pricingRequestItemId} className="rounded-md border border-border bg-surface p-3 text-sm">
                <strong>{[item.brand, item.model].filter(Boolean).join(' ') || item.productDescription || '-'}</strong>
                <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
                  <span>{item.requestedQuantity} {unitBasisLabel(item.requestedUnitBasis)}</span>
                  <span>ราคาขาย (ก่อน VAT): {formatCurrency(item.approvedSellingPricePerRequestedUnit, decisionSalesView.currency)}</span>
                </div>
              </div>
            ))}
          </div>
        </Panel>
      ) : null}

      {/* The customer-facing quotation is Sales' surface. canViewCustomerQuotation still grants
          import read access at the API, but the panel is hidden here: Import would only see an
          empty-state telling it to wait for APPROVED_FOR_QUOTATION. */}
      {canViewCustomerQuotation(user, summary) && !isImport(user) ? (
        <Panel
          flush
          title="ใบเสนอราคาลูกค้า"
          actions={dealQuotationForPr ? (
            (() => {
              const status = dealQuotationStatusLabel(dealQuotationForPr.docStatus);
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
            {/* The NEW engine's quotation for this request, shown in THIS SAME panel: one pricing
                request carries one "ใบเสนอราคาลูกค้า" concept to a viewer regardless of engine, and
                the two chains cannot both be live, so two panels would make a rep hunt for the
                right one. number/status/link, like the legacy block below. */}
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

            {/* The SAME outcome-recording action the legacy block below offers
                (canRecordCustomerQuotationOutcome is origin-agnostic: sales + ticket owner,
                docStatus === 'ISSUED'), for the NEW engine's quotation. Only one finalized
                quotation per deal across both origins is enforced server-side by
                DealQuotationService#recordOutcome, not here. */}
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
                    onClick={() => setAcceptToConfirm({ engine: 'deal', quotation: dealQuotationForPr })}>
                    ลูกค้ายอมรับ
                  </Button>
                  <Button type="button" variant="danger" disabled={recordDealQuotationOutcome.isPending}
                    onClick={() => setOutcomeToConfirm({ engine: 'deal', quotation: dealQuotationForPr, outcome: 'REJECTED' })}>
                    ลูกค้าปฏิเสธ
                  </Button>
                  <Button type="button" variant="secondary" disabled={recordDealQuotationOutcome.isPending}
                    onClick={() => setOutcomeToConfirm({ engine: 'deal', quotation: dealQuotationForPr, outcome: 'REVISION_REQUESTED' })}>
                    ลูกค้าขอแก้ไข
                  </Button>
                </div>
              </div>
            ) : null}

            {/* Read-only outcome summary, mirroring the legacy block's below (visible to everyone
                with view access once the customer's response is recorded or the document moved past
                ISSUED). */}
            {dealQuotationForPr
              && ['ACCEPTED', 'REJECTED', 'REVISION_REQUESTED', 'EXPIRED'].includes(dealQuotationForPr.docStatus) ? (
              <p className="text-sm text-text-muted">
                ผลใบเสนอราคา: <strong>{dealQuotationStatusLabel(dealQuotationForPr.docStatus).label}</strong>
              </p>
            ) : null}
            {/* Once the linked NEW-engine quotation has EXPIRED, was REJECTED, or the customer asked
                for a REVISION, sales may write a FRESH one from the SAME approved decision:
                DealQuotationService#createFromPricingRequest tolerates all three (none move the PR
                off QUOTATION_ISSUED), and DealQuotationRepository#hasLivePricingRequestQuotation
                excludes all three from its "live" set. Gated on canManageCustomerQuotation (sales +
                ticket owner, minus a CANCELLED/SUPERSEDED request, checked just below), NOT
                canCreateCustomerQuotation, which requires pr.status === 'APPROVED_FOR_QUOTATION';
                a PR in any of these states sits at QUOTATION_ISSUED (the backend does not roll PR
                status back), so that stricter gate would hide this button exactly when needed.
                Reuses the SAME createDealQuotationFromRequest mutation as the first-ever create
                button below (same navigate-on-success), since the server call is identical. */}
            {['EXPIRED', 'REVISION_REQUESTED', 'REJECTED'].includes(dealQuotationForPr?.docStatus)
              && !['CANCELLED', 'SUPERSEDED'].includes(summary?.status)
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
                {/* Once the request's decision is new-form (decisionSalesView.newFormPricing) the
                    OLD button is hidden, not just de-emphasized: the NEW engine
                    (createFromPricingRequest) is the only path offered, since the two paths are
                    server-side mutually exclusive and a new-form PR's quotation cannot be issued
                    through the old one. A legacy decision (newFormPricing false) keeps BOTH
                    buttons; decisionSalesView is undefined until the query resolves, so this
                    defaults to "still legacy-shaped" rather than flashing the new-only state. The
                    plain boolean keeps the endpoint (also callable by import) free of any
                    price-shaped fact; see PricingDecisionSalesViewDto#newFormPricing. */}
                {!decisionSalesView?.newFormPricing ? (
                  <Button type="button" variant="secondary" className="self-start" onClick={() => createQuotation.mutate()} disabled={createQuotation.isPending}>
                    สร้างร่างใบเสนอราคาลูกค้า
                  </Button>
                ) : null}
                {/* The direct-deal engine, prefilled from the CEO's decision. Preferred path for a
                    new-form request; a legacy one gets a clear 409 toast rather than a hidden button. */}
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
                {summary.status === 'APPROVED_FOR_QUOTATION'
                  ? 'อนุมัติราคาขายแล้ว — รอฝ่ายขายสร้างใบเสนอราคาลูกค้า'
                  : ['CANCELLED', 'SUPERSEDED'].includes(summary.status)
                    ? `ยังไม่มีใบเสนอราคาลูกค้า — คำขอราคานี้${pricingRequestStatusLabel(summary.status).label}`
                    : 'ยังไม่มีใบเสนอราคาลูกค้า — ต้องรออนุมัติราคาขายก่อนจึงจะสร้างได้'}
              </p>
            ) : null}

            {!dealQuotationForPr && currentCustomerQuotation ? (() => {
              const quotation = currentCustomerQuotation;
              const quotationStatus = quotationStatusLabel(quotation.docStatus);
              const editable = isCustomerQuotationEditable(quotation) && canManageCustomerQuotation(user, summary);
              // A revision's discount is refused by CustomerQuotationService.update, so the input
              // must not be offered on one. Narrower than `editable` on purpose: description/notes
              // stay writable, only the money does not.
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
                            <span>{item.requestedQuantity} {unitBasisLabel(item.requestedUnitBasis)}</span>
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
                      <ThaiDateInput className="rounded border border-border p-2 text-sm"
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
                    {/* Reachable once REVISION_REQUESTED too, not only ISSUED: same guard the
                        backend's createRevision enforces. */}
                    {canCreateCommercialOnlyRevision(user, summary, quotation) ? (
                      <Button type="button" variant="secondary" disabled={createQuotationRevision.isPending}
                        onClick={() => createQuotationRevision.mutate(quotation)}>
                        {quotation.docStatus === 'REVISION_REQUESTED' ? 'สร้างรอบแก้ไขราคา/เงื่อนไข' : 'สร้างรอบแก้ไขใหม่'}
                      </Button>
                    ) : null}
                    {quotation.docStatus === 'REVISION_REQUESTED' && canCreateCommercialOnlyRevision(user, summary, quotation) ? (
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
                          onClick={() => setAcceptToConfirm({ engine: 'customer', quotation })}>
                          ลูกค้ายอมรับ
                        </Button>
                        <Button type="button" variant="danger" disabled={recordQuotationOutcome.isPending}
                          onClick={() => setOutcomeToConfirm({ engine: 'legacy', quotation, outcome: 'REJECTED' })}>
                          ลูกค้าปฏิเสธ
                        </Button>
                        <Button type="button" variant="secondary" disabled={recordQuotationOutcome.isPending}
                          onClick={() => setOutcomeToConfirm({ engine: 'legacy', quotation, outcome: 'REVISION_REQUESTED' })}>
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

      {/* Deposit, Payment, and Order Confirmation: only once the customer has accepted the quotation
          (the terminal status of the decision step). Bridges into the existing dual-track payment
          pipeline (TicketService.confirmCustomer/DepositNoticeService) rather than a new one, see
          OrderConfirmationService's class Javadoc. */}
      {/* No role gate here would show Sales' order-confirm/deposit controls to Import once the
          customer accepted. The actions are server-gated to the ticket owner; this only stops
          rendering controls Import could never use. */}
      {summary.status === 'QUOTATION_ACCEPTED' && !isImport(user) ? (
        <Panel flush title="ยืนยันคำสั่งซื้อและออกใบแจ้งยอดเงินรับมัดจำ">
          <div className="flex flex-col gap-3 p-4">
            {canConfirmOrder(user, summary) ? (
              <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
                <p className="text-sm text-text-muted">
                  ลูกค้ายอมรับใบเสนอราคาแล้ว — ยืนยันคำสั่งซื้อเพื่อเริ่มขั้นตอนรับมัดจำและนำเข้าสินค้า
                </p>
                <Button type="button" variant="primary" className="self-start" disabled={confirmOrder.isPending}
                  onClick={() => setConfirmingOrder(true)}>
                  ยืนยันคำสั่งซื้อ
                </Button>
              </div>
            ) : null}
            {issuedNotice && canCreateDepositNoticeFromQuotation(user, summary) ? (
              <p className="text-sm text-text-muted">
                ดีลนี้ออกใบแจ้งยอดเงินรับมัดจำแล้ว —{' '}
                <Link to={`/tickets/${summary.ticketId}/deposit`} className="text-info underline">
                  เปิดเลขที่ {issuedNotice.docNumber}
                </Link>
              </p>
            ) : null}
            {canCreateDepositNoticeFromQuotation(user, summary, dealQuotationForPr, depositNoticesQuery.data) ? (
              <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
                <p className="text-sm text-text-muted">
                  ยืนยันคำสั่งซื้อแล้ว — สร้างใบแจ้งยอดเงินรับมัดจำจากใบเสนอราคาที่ลูกค้ายอมรับ (แก้ไข/ออกเอกสารในหน้าใบแจ้งยอดเงินรับมัดจำ)
                </p>
                <Button type="button" variant="primary" className="self-start" disabled={createDepositNoticeFromQuotation.isPending}
                  onClick={() => createDepositNoticeFromQuotation.mutate()}>
                  สร้างใบแจ้งยอดเงินรับมัดจำ
                </Button>
              </div>
            ) : null}
            {confirmOrderBlockedReason(summary) ? (
              <p className="text-sm text-danger" role="alert">{confirmOrderBlockedReason(summary)}</p>
            ) : null}
            {!canConfirmOrder(user, summary) && !canCreateDepositNoticeFromQuotation(user, summary, dealQuotationForPr) ? (
              <p className="text-sm text-text-muted">
                {summary.orderConfirmedAt
                  ? `ยืนยันคำสั่งซื้อแล้วเมื่อ ${formatThaiDate(summary.orderConfirmedAt)}${
                    dealQuotationForPr?.docStatus === 'ACCEPTED' && dealQuotationForPr.depositPercent === 0
                      ? ' — ใบเสนอราคานี้ไม่รับมัดจำ จึงไม่มีใบแจ้งยอดเงินรับมัดจำ'
                      : ' — ดูใบแจ้งยอดเงินรับมัดจำได้ที่หน้าดีล'}`
                  : 'ยืนยันคำสั่งซื้อได้เฉพาะเจ้าของดีล (sales)'}
              </p>
            ) : null}
          </div>
        </Panel>
      ) : null}

      <ConfirmOrderDialog
        open={confirmingOrder}
        number={shownQuotation?.number}
        depositPercent={shownQuotation?.depositPercent}
        busy={confirmOrder.isPending}
        onCancel={() => setConfirmingOrder(false)}
        onConfirm={() => { setConfirmingOrder(false); confirmOrder.mutate(); }}
      />
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

      <ConfirmDialog
        open={Boolean(acceptToConfirm)}
        title={acceptConfirm.title}
        message={acceptConfirm.message}
        busy={recordQuotationOutcome.isPending || recordDealQuotationOutcome.isPending}
        onCancel={() => setAcceptToConfirm(null)}
        onConfirm={() => {
          const { engine, quotation } = acceptToConfirm;
          setAcceptToConfirm(null);
          recordAccepted(engine, quotation);
        }}
      />

      <ConfirmDialog
        open={Boolean(outcomeToConfirm)}
        {...outcomeReasonConfirmCopy(outcomeToConfirm?.outcome)}
        requireReason
        busy={recordQuotationOutcome.isPending || recordDealQuotationOutcome.isPending}
        onCancel={() => setOutcomeToConfirm(null)}
        onConfirm={(reason) => {
          const { engine, quotation, outcome } = outcomeToConfirm;
          // Close only on success: a failed call keeps the dialog (and the typed reason) for a same-key retry.
          (engine === 'deal' ? recordDealQuotationOutcome : recordQuotationOutcome)
            .mutate({ quotation, outcome, note: reason }, { onSuccess: () => setOutcomeToConfirm(null) });
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
