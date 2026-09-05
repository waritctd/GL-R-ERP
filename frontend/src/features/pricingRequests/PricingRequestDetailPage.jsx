import { Fragment, useMemo, useState } from 'react';
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
  pricingCostingStatusLabel,
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
  canStartCeoReview,
  canViewCustomerQuotation,
  isCustomerQuotationEditable,
  isCustomerQuotationDiscountEditable,
  canTransition,
  pricingRequestRecipientLabel,
  unitBasisLabel,
} from './pricingRequestMeta.js';
import { PricingRequestCreateModal } from './PricingRequestCreateModal.jsx';
import { useUnitBasisCatalog } from './unitBasisCatalog.js';
import { buttonVariants } from '../../components/common/Button.jsx';
import { cn } from '../../utils/cn.js';

// V152 (V109 engine wiring), owner ruling 2026-08-16: the CEO's per-item duty product_type
// override (LandedCostCalculator defaults every item to TILE — see PricingFormulaEngine's own
// DEFAULT_PRODUCT_TYPE). Hardcodes V109's two seeded product_type codes rather than fetching
// sales.pricing_formula_config's duty rates live: correct for the CEO's actual real-world need
// today, but a new duty type the CEO adds later via /api/pricing-formula-config would not appear
// here until this list is updated too — a known drift risk, not a hidden one.
const DUTY_PRODUCT_TYPE_OPTIONS = [
  { value: 'TILE', label: 'กระเบื้อง (TILE) — อากร 30%' },
  { value: 'GLASS_MOSAIC', label: 'โมเสคแก้ว (GLASS_MOSAIC) — อากร 10%' },
];
const DUTY_PRODUCT_TYPE_LABELS = Object.fromEntries(
  DUTY_PRODUCT_TYPE_OPTIONS.map((opt) => [opt.value, opt.label]),
);

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

const DISPATCH_STATUS_LABEL = {
  PENDING: 'รอส่ง',
  SENDING: 'กำลังส่ง',
  SENT: 'ส่งแล้ว',
  FAILED: 'ส่งไม่สำเร็จ',
};

function dispatchStatusBadge(quote) {
  const status = quote?.dispatchStatus;
  if (!status || status === 'SENT') return null;
  const tone = status === 'FAILED' ? 'danger' : 'warning';
  const attempt = quote.dispatchAttemptCount > 1 ? ` (ครั้งที่ ${quote.dispatchAttemptCount})` : '';
  return (
    <StatusBadge key={`dispatch-${quote.id}`} tone={tone}>
      {(DISPATCH_STATUS_LABEL[status] ?? status) + attempt}
    </StatusBadge>
  );
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
    const unitBasis = item.unitBasis;
    const quotedUnit = item.quotedUnit ?? requestItem.requestedUnit ?? unitBasisLabel(unitBasis);
    return {
    pricingRequestItemId: item.pricingRequestItemId,
    supplierProductCode: item.supplierProductCode ?? '',
    supplierProductDescription: item.supplierProductDescription ?? '',
    quotedQuantity: item.quotedQuantity ?? requestItem.requestedQty ?? 1,
    quotedUnit,
    unitBasis,
    rawUnitPrice: item.rawUnitPrice ?? '',
    currency: item.currency ?? quote.defaultCurrency ?? requestItem.catalogCurrency ?? 'THB',
    minimumOrderQuantity: item.minimumOrderQuantity ?? '',
    sqmPerUnit: item.sqmPerUnit ?? '',
    piecesPerBox: item.piecesPerBox ?? '',
    leadTimeText: item.leadTimeText ?? '',
    availabilityNote: item.availabilityNote ?? '',
    lineNote: item.lineNote ?? '',
    };
  });
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
const FACTORY_ITEM_GRID = 'md:grid-cols-[minmax(150px,1.6fr)_minmax(120px,1.1fr)_100px_170px_150px] md:min-w-[720px]';

/**
 * The factory-quote email composer, relocated into a modal behind each factory group's header
 * "ร่างอีเมล" button (owner-supplied mockup, 2026-08-16 — the header collapses this to one action;
 * the always-visible primary surface is the item-price grid, not email mechanics). Every field and
 * action here is unchanged from the inline block it replaces: To/Subject/Body editable while
 * `quote.status === 'DRAFT'`, "คัดลอกข้อความ" (copy, never sends), and "ส่งแล้ว"/"บันทึกว่าส่งแล้วอีกครั้ง"
 * (records that a human sent it — see the copy this button's parent Confirm dialog still owns).
 * Real labels, not placeholder-only, for the same reason the inline version used them: a screen
 * reader needs a stable accessible name, not one that depends on the field being empty.
 *
 * `onRequestSend` closes this modal before opening the shared `<ConfirmDialog>` (rather than
 * stacking two modals) — sequencing one focus-trapped dialog at a time is simpler than reasoning
 * about two `useDialogFocus` traps active together, and it matches how a user's attention actually
 * moves: finish the draft, then confirm the send.
 */
function FactoryEmailDraftModal({ quote, draft, onChangeDraft, onClose, onSave, savePending, onCopy, onRequestSend }) {
  const dispatchInFlight = quote.dispatchStatus === 'PENDING' || quote.dispatchStatus === 'SENDING';
  const canOfferSendActions = quote.status === 'DRAFT' && !dispatchInFlight;
  const canEditFields = quote.status === 'DRAFT';

  return (
    <Modal
      title="ร่างอีเมลถึงโรงงาน"
      subtitle={quote.factoryName}
      onClose={onClose}
      testId="factory-email-draft-modal"
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ปิด</Button>
          {canOfferSendActions ? (
            <Button type="button" variant="secondary" data-testid="pcr-copy-factory-email" onClick={() => onCopy(draft)}>
              คัดลอกข้อความ
            </Button>
          ) : null}
          {canEditFields ? (
            <Button type="button" variant="secondary" disabled={savePending} onClick={onSave}>
              บันทึกร่างอีเมล
            </Button>
          ) : null}
          {canOfferSendActions ? (
            <Button type="button" variant="primary" data-testid="pcr-mark-factory-email-sent" onClick={onRequestSend}>
              {quote.dispatchStatus === 'FAILED' ? 'บันทึกว่าส่งแล้วอีกครั้ง' : 'ส่งแล้ว'}
            </Button>
          ) : null}
        </>
      }
    >
      <div className="grid gap-3">
        {!canEditFields ? (
          <p className="m-0 rounded-md border border-border-subtle bg-surface-subtle p-3 text-xs text-text-muted">
            ส่งคำขอราคาไปแล้ว — แก้ไขอีเมลฉบับนี้ไม่ได้ ดูรายละเอียดที่ส่งจริงด้านล่าง
          </p>
        ) : null}
        {quote.dispatchStatus === 'FAILED' && quote.dispatchFailureMessage ? (
          <p className="m-0 text-xs text-danger">ส่งไม่สำเร็จ: {quote.dispatchFailureMessage}</p>
        ) : null}
        <FormField label="อีเมลโรงงาน" htmlFor="pcr-email-to">
          <input
            id="pcr-email-to"
            type="email"
            disabled={!canEditFields}
            value={draft.emailTo}
            onChange={(e) => onChangeDraft({ ...draft, emailTo: e.target.value })}
          />
        </FormField>
        <FormField label="หัวข้ออีเมล" htmlFor="pcr-email-subject">
          <input
            id="pcr-email-subject"
            disabled={!canEditFields}
            value={draft.emailSubject}
            onChange={(e) => onChangeDraft({ ...draft, emailSubject: e.target.value })}
          />
        </FormField>
        <FormField label="เนื้อหาอีเมล" htmlFor="pcr-email-body">
          <textarea
            id="pcr-email-body"
            className="min-h-40"
            disabled={!canEditFields}
            value={draft.emailBody}
            onChange={(e) => onChangeDraft({ ...draft, emailBody: e.target.value })}
          />
        </FormField>
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
function PriceOverrideModal({ item, decision, onClose, onSubmit, pending }) {
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
              เมื่อปรับแล้ว สูตรจะไม่คำนวณราคาขายของรายการนี้อีก — ราคาที่ปรับจะถูกใช้แทนจนกว่าจะล้างค่า
            </p>
          </div>
          <FormField
            label={`ราคาที่ปรับ (${decision.currency}/หน่วยที่ขอ)`}
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
  const [sendClientRequestIds, setSendClientRequestIds] = useState({});
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

  const detailQuery = useQuery({
    queryKey: queryKeys.pricingRequestDetail(pricingRequestId),
    queryFn: () => api.pricingRequests.get(pricingRequestId).then((r) => r.pricingRequest),
    enabled: Number.isFinite(pricingRequestId),
  });

  const factoryQuery = useQuery({
    queryKey: queryKeys.pricingRequestFactoryQuotes(pricingRequestId),
    queryFn: () => api.pricingRequests.listFactoryQuotes(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId) && canSeeRaw(user),
    // The outbox worker sends/finalizes a factory quote dispatch out-of-band (send() only
    // enqueues), so while any quote has one in flight, poll instead of leaving the UI stuck
    // showing a stale "PENDING"/"SENDING" badge until the next unrelated invalidate.
    refetchInterval: (query) => {
      const quotes = query.state.data ?? [];
      return quotes.some((q) => ['PENDING', 'SENDING'].includes(q.dispatchStatus)) ? 2000 : false;
    },
  });

  const costingQuery = useQuery({
    queryKey: queryKeys.pricingRequestCostings(pricingRequestId),
    queryFn: () => api.pricingRequests.listCostings(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId) && canSeeRaw(user),
  });

  // The สกุลเงิน select on the ราคาโรงงาน per-factory control row (owner-supplied mockup,
  // 2026-08-16): options come from the real FX rate table Import/CEO already read elsewhere
  // (CeoSettingsPage's own fxRates query, same queryKeys.fxRates()/api.fxRates.list()), not a
  // hand-typed list here that could drift from it. FxRateController's read gate already covers
  // ceo/import/sales (owner ruling, #438/V112) — a strict superset of this panel's own
  // canSeeRaw (ceo/import) — so widening this page to also read it adds no new access.
  const fxRatesQuery = useQuery({
    queryKey: queryKeys.fxRates(),
    queryFn: () => api.fxRates.list().then((r) => r.fxRates ?? []),
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

  // V152 (V109 engine wiring): the LIVE selling_buffer/selling_price_round_up_to, so the "วิธี
  // คำนวณราคานี้" panel's formula text shows the buffer and round-up that actually ran, not the
  // pre-V109 "cost x (1+margin)" shape. Same read gate as PricingFormulaConfigController itself
  // ({ceo, import}) — this page's own canSeeRawPricingDecision is a superset, so no new access
  // is opened up by fetching it here.
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

  // Pricing Request attachments (V69, review remediation COMMIT 4): Sales-level supporting
  // attachments on the request itself — every viewer role can see the list (requireViewable's
  // usual scoping already applies server-side: a non-owner sales rep never even reaches this
  // page's detailQuery, so there is no separate check needed here).
  const attachmentsQuery = useQuery({
    queryKey: queryKeys.pricingRequestAttachments(pricingRequestId),
    queryFn: () => api.pricingRequests.listAttachments(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId),
  });

  // The unit-basis vocabulary for the ราคาโรงงาน response unit select below — fetched from the
  // backend at runtime (owner's choice, not a hardcoded list) and cached for the app's lifetime.
  const { unitBases: unitBasisCatalog } = useUnitBasisCatalog();

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestDetail(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestFactoryQuotes(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestCostings(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestAttachments(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingDecisions(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingDecisionSalesView(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.customerQuotations(pricingRequestId) });
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

  const setItemFactory = useActionMutation(
    ({ itemId, factory }) => api.pricingRequests.setItemFactory(pricingRequestId, itemId, { factory }),
    'บันทึกโรงงานแล้ว',
  );
  const generateDrafts = useActionMutation(() => api.pricingRequests.generateFactoryEmailDrafts(pricingRequestId), 'สร้างร่างอีเมลแล้ว');
  const updateQuote = useActionMutation(({ quote, draft }) => api.pricingRequests.updateFactoryQuote(quote.id, draft), 'บันทึกร่างอีเมลแล้ว');
  const sendQuote = useActionMutation(({ quote, draft }) => api.pricingRequests.sendFactoryQuote(quote.id, {
    emailTo: draft?.emailTo ?? quote.emailTo,
    emailSubject: draft?.emailSubject ?? quote.emailSubject,
    emailBody: draft?.emailBody ?? quote.emailBody,
    clientRequestId: sendClientRequestIds[quote.id] ?? generateClientRequestId(),
  }), 'ส่งคำขอโรงงานแล้ว');
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
  const [decisionDefaultMargin, setDecisionDefaultMargin] = useState('0.20');
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
  // Phase 1 UI simplification: the per-item margin/minimum/ceiling draft grid is gone (the main
  // view "asks for nothing" — see the CEO panel below). The only remaining CEO-typed input on a
  // decision item is "ปรับราคาเอง", which opens PriceOverrideModal per item — no draft state is
  // needed for it (the modal owns its own form state, same pattern as costOverrideItem below).
  const [priceOverrideItem, setPriceOverrideItem] = useState(null);
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
      defaultMarginPct: cleanNumber(decisionDefaultMargin),
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
          decisionNote: reason,
        }],
      }),
    'บันทึกราคาที่ปรับแล้ว',
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
  // V152 (V109 engine wiring), owner ruling 2026-08-16: product_type has no source in deal data
  // today, so LandedCostCalculator defaults every item to TILE (30% import duty). This is the
  // CEO's per-item escape hatch when the default over/under-taxes an item — the owner's own
  // example: โมเสคแก้ว must be taxed at 10%, not TILE's 30%. Recomputes the WHOLE line
  // immediately server-side (PricingDecisionService#overrideItemProductType mirrors
  // recalculateCost), so the new duty/price is visible right after this call resolves — no
  // separate manual "recalculate" step, unlike the cost override above (which needs one because
  // it REPLACES the computed figure rather than feeding a formula input).
  const overrideItemProductType = useActionMutation(
    ({ decision, item, productType }) =>
      api.pricingRequests.overridePricingDecisionItemProductType(decision.id, item.id, { productType }),
    'บันทึกประเภทสินค้าแล้ว',
  );
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
  // itemId -> the factory name Import is typing for that line. Same shape as `responseDrafts`
  // above: a key exists only once a change handler has written to it.
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
  const factoryQuotes = useMemo(() => factoryQuery.data ?? [], [factoryQuery.data]);
  const factoryGroups = useMemo(() => groupFactoryQuotesByFactory(factoryQuotes), [factoryQuotes]);
  const factoryItemCount = useMemo(
    () => factoryGroups.reduce((sum, group) => sum + (group.current.items?.length ?? 0), 0),
    [factoryGroups],
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

  return (
    <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
      <PageHeader
        title={summary.requestCode}
        subtitle={`${summary.customerName ?? '-'}${summary.projectName ? ` · ${summary.projectName}` : ''}`}
        actions={(
          <Button type="button" variant="secondary" onClick={() => navigate(-1)}>
            <Icon name="chevronLeft" size={14} />
            กลับ
          </Button>
        )}
      />

      <Panel flush title="ภาพรวม" actions={<StatusBadge tone={status.tone}>{status.label}</StatusBadge>}>
        <div className="grid gap-3 p-4 md:grid-cols-2">
          <div className="text-sm"><strong>ดีล</strong> <Link to={`/tickets/${summary.ticketId}`} className="text-info underline">{summary.ticketCode}</Link></div>
          <div className="text-sm"><strong>ผู้รับ</strong> {pricingRequestRecipientLabel(summary.recipientType)}{summary.recipientLabel ? ` · ${summary.recipientLabel}` : ''}</div>
          <div className="text-sm"><strong>ต้องการภายใน</strong> {formatThaiDate(summary.requiredDate)}</div>
          <div className="text-sm"><strong>ฝ่ายนำเข้า</strong> ผู้รับเรื่องและประสานราคาโรงงาน</div>
        </div>
      </Panel>

      <Panel flush title="รายการสินค้าและราคาตั้งต้น">
        <div className="flex flex-col gap-2 p-4">
          {/* The blocking condition, stated BEFORE the สร้างร่างอีเมล button is pressed. It used to
              be discoverable only by pressing it and reading a 422 that named the row's primary
              key — a number that appears nowhere on this page. */}
          {missingFactoryItems.length ? (
            <p className="rounded-md border border-warning-border bg-warning-bg p-3 text-xs text-warning-dark">
              {`ยังไม่ได้ระบุโรงงาน ${missingFactoryItems.length} รายการ — สร้างร่างอีเมลถึงโรงงานไม่ได้จนกว่าจะระบุครบ: `}
              {missingFactoryItems.map((entry) => `รายการที่ ${entry.position} (${itemDisplayName(entry.item)})`).join(', ')}
              {canSetItemFactory
                ? ' — กรอกชื่อโรงงานในรายการด้านล่างแล้วกดบันทึก'
                : ' — ฝ่ายนำเข้าเป็นผู้ระบุโรงงานให้ในขั้นตอนนี้'}
            </p>
          ) : null}
          {(request.items ?? []).map((item, index) => {
            const factoryName = itemFactoryName(item);
            // `position` is the 1-based row number the server counts too: findItems returns
            // ORDER BY sort_order, pricing_request_item_id and groupByFactory's sort is stable on
            // sortOrder, so "รายการที่ N" means this exact row on both sides.
            const position = index + 1;
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
                  <span className={factoryName ? undefined : 'font-bold text-warning-dark'}>
                    {`Factory: ${factoryName ?? 'ยังไม่ได้ระบุ'}`}
                  </span>
                  <span>Catalog: {item.catalogProductCode ?? '-'}</span>
                  <span>Base: {item.catalogBasePrice != null ? `${formatCurrency(item.catalogBasePrice, item.catalogCurrency ?? 'THB')} (preliminary)` : '-'}</span>
                </div>
                {/* Import's escape hatch. Only offered on a line that has NO factory: the backend
                    refuses to re-route one that does (a factory quote may already be grouped under
                    that name), so offering an editable value here would promise something the
                    service would 409. */}
                {canSetItemFactory && !factoryName ? (
                  <SafeForm
                    className="mt-2 flex flex-wrap items-end gap-2"
                    onSubmit={() => setItemFactory.mutate(
                      { itemId: item.id, factory: (factoryDrafts[item.id] ?? '').trim() },
                      { onSuccess: () => setFactoryDrafts((current) => {
                        const next = { ...current };
                        delete next[item.id];
                        return next;
                      }) },
                    )}
                  >
                    <FormField label="ระบุโรงงาน" htmlFor={`pcr-item-factory-${item.id}`}>
                      <input
                        id={`pcr-item-factory-${item.id}`}
                        value={factoryDrafts[item.id] ?? ''}
                        maxLength={255}
                        placeholder="ชื่อโรงงานที่จะขอราคา"
                        onChange={(event) => setFactoryDrafts((current) => ({ ...current, [item.id]: event.target.value }))}
                      />
                    </FormField>
                    <Button
                      type="submit"
                      variant="secondary"
                      disabled={setItemFactory.isPending || !(factoryDrafts[item.id] ?? '').trim()}
                    >
                      บันทึกโรงงาน
                    </Button>
                  </SafeForm>
                ) : null}
              </div>
            );
          })}
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

      {canSeeRaw(user) ? (
        <Panel
          flush
          title={`รายการสินค้า (${factoryItemCount} รายการ)`}
          actions={isImport(user) ? (
            <Button type="button" variant="primary" disabled={generateDrafts.isPending} onClick={() => generateDrafts.mutate()} data-testid="pcr-generate-drafts">
              สร้างร่างอีเมล
            </Button>
          ) : null}
        >
          {factoryGroups.length === 0 ? (
            <p className="p-4 text-sm text-text-muted">ยังไม่มีราคาโรงงาน</p>
          ) : (
            <div className="flex flex-col">
              {/* Column headers (DESIGN.md §13's .table-head idiom: surface-muted band, overline
                  caption) — shared FACTORY_ITEM_GRID keeps every item row below aligned to these
                  same five tracks regardless of which factory group it belongs to. md: (768px) up
                  only: below that each item reflows to a labelled stacked card instead (see the
                  per-field mobile caption spans in the item-row map below), so an empty header
                  strip would have nothing to head. */}
              <div className={cn('hidden items-center gap-3 border-b border-border-subtle bg-surface-subtle px-5 py-2.5 text-xs font-bold uppercase tracking-wide text-text-muted md:grid', FACTORY_ITEM_GRID)}>
                <span>ยี่ห้อ / รุ่น</span>
                <span>สี / เนื้อผิว</span>
                <span>จำนวน</span>
                <span>ราคาที่เสนอ (แก้ไข)</span>
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
                  defaultCurrency: current.defaultCurrency ?? 'THB',
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
                // READY_FOR_COSTING only offers ยืนยันราคาเสนอ again while dirty — see
                // confirmFactoryQuote's doc comment for why an undirtied re-click must not be
                // offered at all (it would either no-op-fail against markReady's own guard, or,
                // if this branch called receive() unconditionally, spuriously bump the revision).
                const canConfirm = isImport(user) && current.current
                  && (['DRAFT', 'REQUESTED', 'RESPONSE_RECEIVED', 'NEGOTIATING'].includes(current.status)
                    || (current.status === 'READY_FOR_COSTING' && dirty));
                const canNegotiate = isImport(user) && current.status === 'RESPONSE_RECEIVED' && current.current;
                const canOpenEmailDraft = isImport(user) && current.status === 'DRAFT';
                // หน่วยราคา is a per-FACTORY control now, not per-line (owner-supplied mockup) — every
                // line in `draft.items` shares one unitBasis, so the first line speaks for the whole
                // group. defaultResponseItems seeds every line from the same source when untouched,
                // so this only reads as "mixed" if something mutated lines independently, which
                // nothing below does any more (updateUnitBasis always writes every line at once).
                const groupUnitBasis = draft.items[0]?.unitBasis ?? '';
                const needsSqmPerUnit = groupUnitBasis === 'PER_SQM';
                const groupCurrency = draft.defaultCurrency || draft.items[0]?.currency || 'THB';
                const groupUnitLabel = unitBasisCatalog.find((option) => option.code === groupUnitBasis)?.label
                  ?? unitBasisLabel(groupUnitBasis);

                function updateDraft(patch) {
                  setResponseDrafts({ ...responseDrafts, [current.id]: { ...draft, ...patch } });
                }
                function updateLine(index, patch) {
                  const items = [...draft.items];
                  items[index] = { ...items[index], ...patch };
                  updateDraft({ items });
                }
                function updateCurrency(nextCurrency) {
                  updateDraft({
                    defaultCurrency: nextCurrency,
                    items: draft.items.map((item) => ({ ...item, currency: nextCurrency })),
                  });
                }
                function updateUnitBasis(nextBasis) {
                  const nextLabel = unitBasisCatalog.find((option) => option.code === nextBasis)?.label;
                  updateDraft({
                    items: draft.items.map((item) => ({ ...item, unitBasis: nextBasis, quotedUnit: nextLabel ?? item.quotedUnit })),
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
                  <div key={group.key} className="border-t border-border-subtle first:border-t-0">
                    {/* Factory header: name, its item count, its contact email (current.emailTo —
                        the address Import is actually corresponding with; see this file's
                        groupFactoryQuotesByFactory comment for why there is no separate master-data
                        field to read instead), and one ร่างอีเมล action collapsing the old inline
                        To/Subject/Body composer into FactoryEmailDraftModal. Never its own bordered
                        card (DESIGN.md: "never nest a card inside a card") — a tonal
                        surface-subtle band inside the flush Panel, same idiom as a table header. */}
                    <div className="flex flex-wrap items-center justify-between gap-3 bg-surface-subtle px-5 py-3 mobile:px-4">
                      <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5">
                        <strong className="text-text">{group.factoryName}</strong>
                        <span className="text-xs text-text-muted">({current.items?.length ?? 0} รายการ)</span>
                        {current.emailTo ? <span className="truncate text-xs text-text-muted">{current.emailTo}</span> : null}
                        <StatusBadge tone={quoteStatus.tone}>{quoteStatus.label}</StatusBadge>
                        {dispatchStatusBadge(current)}
                        {current.revisionNo > 1 ? <StatusBadge tone="neutral">ครั้งที่ {current.revisionNo}</StatusBadge> : null}
                      </div>
                      {canOpenEmailDraft ? (
                        <Button type="button" variant="secondary" onClick={() => setEmailModalQuoteId(current.id)} data-testid={`pcr-open-email-draft-${current.id}`}>
                          <Icon name="mail" size={14} />
                          ร่างอีเมล
                        </Button>
                      ) : null}
                    </div>

                    {/* Per-factory control row — สกุลเงิน + หน่วยราคา, shared by every item below
                        instead of a per-line picker (owner-supplied mockup). Static text once the
                        response can no longer be edited here (not `editable`), so a read-only
                        viewer (CEO) sees the values without an inert-looking select. */}
                    {editable ? (
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
                    )}

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
                            <div className="truncate text-sm font-bold text-text">{productName}</div>
                          </div>
                          <div className="min-w-0 text-sm text-text-secondary">
                            <span className="mb-0.5 block text-2xs font-bold uppercase text-text-muted md:hidden">สี / เนื้อผิว</span>
                            {variantLabel || '-'}
                          </div>
                          <div className="text-sm text-text-secondary">
                            <span className="mr-1 text-2xs font-bold uppercase text-text-muted md:hidden">จำนวน</span>
                            {qty} {unitLabel}
                          </div>
                          <div className="min-w-0">
                            <span className="mb-1 block text-2xs font-bold uppercase text-text-muted md:hidden">ราคาที่เสนอ (แก้ไข)</span>
                            {editable ? (
                              <div className="flex flex-wrap items-center gap-1.5">
                                <input
                                  id={`pcr-quote-price-${current.id}-${line.pricingRequestItemId}`}
                                  className="w-full md:w-32"
                                  type="number"
                                  min="0"
                                  step="0.0001"
                                  inputMode="decimal"
                                  placeholder={`ราคา/${unitBasisCatalog.find((option) => option.code === line.unitBasis)?.label ?? line.quotedUnit ?? ''}`}
                                  aria-label={`ราคาที่เสนอ ${itemRef}`}
                                  value={line.rawUnitPrice ?? ''}
                                  onChange={(e) => updateLine(index, { rawUnitPrice: e.target.value })}
                                />
                                {needsSqmPerUnit ? (
                                  <input
                                    id={`pcr-quote-sqm-${current.id}-${line.pricingRequestItemId}`}
                                    className="w-24"
                                    type="number"
                                    min="0.000001"
                                    step="0.000001"
                                    inputMode="decimal"
                                    placeholder="ตร.ม./หน่วย"
                                    aria-label={`ตร.ม./หน่วย ${itemRef}`}
                                    value={line.sqmPerUnit ?? ''}
                                    onChange={(e) => updateLine(index, { sqmPerUnit: e.target.value })}
                                  />
                                ) : null}
                              </div>
                            ) : (
                              <span className="text-sm text-text-secondary">{formatCurrency(line.rawUnitPrice, line.currency)}</span>
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
          )}
        </Panel>
      ) : null}

      {/* Import no longer sees the costing aggregate — submitToCeo runs it end to end. CEO keeps
          the full view (canSeeRaw is import+ceo; only the ceo half is wanted here), and it is
          READ-ONLY: this panel holds no action controls at all.

          It used to hold four — สร้างร่างต้นทุน, the หมายเหตุต้นทุน field feeding it, คำนวณใหม่ and
          ส่งให้ CEO ตรวจ — each additionally gated on isImport(user). Since this render site is
          `canSeeRaw(user) && !isImport(user)`, those two conditions were mutually exclusive and no
          user could ever reach them, for the whole life of the controls. Deleted by issue #747
          (owner ruling 2026-08-14); the routes they drove were already severed by V141/PR #702. */}
      {canSeeRaw(user) && !isImport(user) ? (
        <Panel flush title="ต้นทุนนำเข้า">
          <div className="flex flex-col gap-3 p-4">
            {costings.map((costing) => (
              <div key={costing.id} className="rounded-md border border-border bg-surface p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <strong>{costing.costingCode}</strong>
                  <StatusBadge tone="neutral">เวอร์ชัน {costing.versionNo}</StatusBadge>
                  {(() => {
                    const status = pricingCostingStatusLabel(costing.status);
                    return <StatusBadge tone={status.tone}>{status.label}</StatusBadge>;
                  })()}
                  <span className="text-xs text-text-muted">{costing.totalLandedCostThb != null ? formatCurrency(costing.totalLandedCostThb, 'THB') : '-'}</span>
                </div>
                {canSeeRaw(user) && costing.items?.length ? (
                  <div className="mt-2 flex flex-col gap-1 text-xs text-text-muted">
                    {costing.items.map((item, index) => (
                      <span key={item.id ?? `${item.factoryName ?? 'factory'}-${item.factoryQuoteRevisionNo ?? 'rev'}-${index}`}>{item.factoryName} · ครั้งที่ {item.factoryQuoteRevisionNo} · ราคาโรงงาน {formatCurrency(item.rawUnitPrice, item.rawCurrency)} · ต้นทุนนำเข้า {formatCurrency(item.landedCostPerUnitThb, 'THB')}</span>
                    ))}
                  </div>
                ) : null}
              </div>
            ))}
            {costings.length === 0 ? <p className="text-sm text-text-muted">ยังไม่มีต้นทุนนำเข้า</p> : null}
          </div>
        </Panel>
      ) : null}

      {/* Import's job ends at ยืนยันราคาเสนอ (owner ruling 2026-08-11, relabelled 2026-08-16), so the
          CEO's own selling-price decision is no longer on Import's page — canSeeRawPricingDecision
          covers import+ceo, and only the CEO half is wanted here. The predicate itself is
          unchanged (it still governs cost/margin visibility elsewhere); this render site adds the
          role narrowing rather than editing the shared gate.

          Phase 1 UI simplification (owner ruling 2026-08-16): the main view now shows numbers and
          asks for nothing — read-only base cost, the automatically computed selling price, a
          small refresh control at the top of the section (this Panel's `actions` slot, replacing
          the old "คำนวณต้นทุนใหม่" button), and exactly two actions (อนุมัติราคาขาย /
          ตีกลับให้ฝ่ายนำเข้าแก้ไข). Everything a CEO who doubts the number would want — the cost
          breakdown, the formula's actual derivation, ปรับต้นทุนเอง (V141, relocated but otherwise
          unchanged), and ปรับราคาเอง (new) — lives inside a per-item "วิธีคำนวณราคานี้"
          CollapsibleSection, collapsed by default. */}
      {canSeeRawPricingDecision(user) && !isImport(user) ? (
        <Panel
          flush
          title="การพิจารณาราคาขายของ CEO"
          actions={currentDecision && decisionEditable ? (
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
          <div className="flex flex-col gap-3 p-4">
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
            {!currentDecision && canStartCeoReview(user, summary) ? (
              <div className="flex flex-wrap items-center gap-2">
                <label className="text-xs text-text-muted">
                  อัตรากำไรเริ่มต้น
                  <input
                    className="form-input ml-2 w-24"
                    value={decisionDefaultMargin}
                    onChange={(e) => setDecisionDefaultMargin(e.target.value)}
                    placeholder="0.20"
                  />
                </label>
                <Button
                  type="button"
                  variant="primary"
                  loading={startCeoReview.isPending}
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
            {currentDecision ? (() => {
              const decision = currentDecision;
              const decisionStatus = pricingDecisionStatusLabel(decision.status);
              const editable = decisionEditable;
              // Phase 1 UI simplification: ราคาขั้นต่ำ is no longer a CEO input (auto-populated
              // server-side at approve() — see PricingDecisionService#approve), so it can never
              // block approval here any more. A "ปรับราคาเอง" override needs no margin at all —
              // its price is fixed directly, mirroring PricingDecisionService#approve's own
              // missingMargin exemption for an overridden item.
              const missingBeforeApprove = decision.items.filter((item) => {
                const hasPriceOverride = item.manualSellingPricePerRequestedUnit != null;
                return !hasPriceOverride && (item.proposedMarginPct == null || item.proposedMarginPct === '');
              });
              // V141: mirrors PricingDecisionService.approve's own stale-override 409 guard, so the
              // CEO discovers it here instead of via a failed approve. The server stays
              // authoritative — this only pre-empts a call that would fail anyway.
              const staleOverrideItems = decision.items.filter(
                (item) => decisionCostingItems.get(item.pricingCostingItemId)?.overrideStale,
              );
              return (
                <div key={decision.id} className="rounded-md border border-border bg-surface p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <strong>{decision.decisionCode}</strong>
                    <StatusBadge tone="neutral">เวอร์ชัน {decision.decisionVersionNo}</StatusBadge>
                    <StatusBadge tone={decisionStatus.tone}>{decisionStatus.label}</StatusBadge>
                    <span className="text-xs text-text-muted">
                      {decision.currency} · อัตราแลกเปลี่ยน {decision.fxRateUsed} ({decision.fxSource}, {decision.fxEffectiveDate})
                    </span>
                  </div>
                  <div className="mt-3 flex flex-col gap-3">
                    {decision.items.map((item) => {
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
                              {costingItem ? (
                                <div className="flex flex-wrap items-center gap-x-2 gap-y-1 rounded-md border border-border-subtle bg-surface-subtle p-2">
                                  <span>
                                    ประเภทสินค้า (สำหรับอากรขาเข้า):{' '}
                                    <code className={costingItem.productType === 'TILE' ? undefined : 'font-bold text-override'}>
                                      {DUTY_PRODUCT_TYPE_LABELS[costingItem.productType] ?? costingItem.productType ?? 'TILE (ค่าเริ่มต้น)'}
                                    </code>
                                    {costingItem.productType && costingItem.productType !== 'TILE' ? (
                                      <span className="ml-1 text-2xs text-override">ปรับเอง</span>
                                    ) : null}
                                  </span>
                                  {editable ? (
                                    <select
                                      className="form-select text-2xs"
                                      value=""
                                      disabled={overrideItemProductType.isPending}
                                      onChange={(e) => {
                                        const value = e.target.value;
                                        e.target.value = '';
                                        if (!value) return;
                                        overrideItemProductType.mutate({
                                          decision, item, productType: value === '__CLEAR__' ? null : value,
                                        });
                                      }}
                                      data-testid={`pcr-ceo-product-type-override-${item.id}`}
                                    >
                                      <option value="">เปลี่ยนประเภทสินค้า…</option>
                                      {DUTY_PRODUCT_TYPE_OPTIONS.map((opt) => (
                                        <option key={opt.value} value={opt.value}>{opt.label}</option>
                                      ))}
                                      {costingItem.productType && costingItem.productType !== 'TILE' ? (
                                        <option value="__CLEAR__">ล้างการปรับ (กลับเป็น TILE)</option>
                                      ) : null}
                                    </select>
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
                                <p className="m-0 mt-1">
                                  ราคาขาย/หน่วยที่ขอ = ปัดขึ้น[ ต้นทุน/หน่วยที่ขอ × (1 + อัตรากำไร) × ตัวคูณราคาขาย ,
                                  ให้เป็นทวีคูณของ ฿{formulaConfigQuery.data?.sellingPriceRoundUpTo ?? '10'} ]
                                  {decision.currency !== 'THB' ? ' ÷ อัตราแลกเปลี่ยน' : ''}
                                </p>
                                <p className="m-0 mt-1">
                                  = ปัดขึ้น[{formatCurrency(item.frozenLandedCostPerRequestedUnitThb, 'THB')} × (1 + {item.proposedMarginPct ?? '-'}) × {formulaConfigQuery.data?.sellingBuffer ?? '-'}]
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
                    })}
                  </div>
                  {editable ? (
                    <div className="mt-3 flex flex-col gap-2 border-t border-border-subtle pt-3">
                      <div className="flex flex-wrap gap-2">
                        <Button
                          type="button"
                          variant="primary"
                          disabled={approveDecision.isPending || missingBeforeApprove.length > 0 || staleOverrideItems.length > 0}
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
                      {staleOverrideItems.length > 0 ? (
                        <span className="text-xs text-danger">
                          มีรายการที่ปรับต้นทุนเองล้าสมัย — กรุณาคำนวณต้นทุนใหม่ หรือยืนยันค่าที่ปรับอีกครั้งก่อนอนุมัติ
                        </span>
                      ) : null}
                    </div>
                  ) : null}
                </div>
              );
            })() : null}
            {pricingDecisions.length > 1 ? (
              <div className="text-xs text-text-muted">
                ประวัติ: {pricingDecisions.map((d) => {
                  const status = pricingDecisionStatusLabel(d.status);
                  return `เวอร์ชัน ${d.decisionVersionNo} (${status.label})`;
                }).join(' · ')}
              </div>
            ) : null}
          </div>
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
          actions={currentCustomerQuotation ? (
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
            {!currentCustomerQuotation && canCreateCustomerQuotation(user, summary) ? (
              <Button type="button" variant="primary" className="self-start" onClick={() => createQuotation.mutate()} disabled={createQuotation.isPending}>
                สร้างร่างใบเสนอราคาลูกค้า
              </Button>
            ) : null}
            {!currentCustomerQuotation && !canCreateCustomerQuotation(user, summary) ? (
              <p className="text-sm text-text-muted">
                ยังไม่มีใบเสนอราคาลูกค้า — ต้องรออนุมัติราคาขาย (APPROVED_FOR_QUOTATION) ก่อนจึงจะสร้างได้
              </p>
            ) : null}

            {currentCustomerQuotation ? (() => {
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
          : 'ส่งอีเมลถึงโรงงาน'}
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
                  : 'ยืนยันการส่งคำขอราคาให้โรงงานด้วยรายละเอียดอีเมลนี้'}
        confirmLabel={confirmAction?.type === 'approveDecision' ? 'อนุมัติ'
          : confirmAction?.type === 'returnDecision' ? 'ตีกลับ'
          : confirmAction?.type === 'issueQuotation' ? 'ออกใบเสนอราคา'
          : confirmAction?.type === 'approveDiscount' ? 'อนุมัติส่วนลด'
          : confirmAction?.type === 'rejectDiscount' ? 'ปฏิเสธส่วนลด'
          : 'ส่งอีเมล'}
        tone={confirmAction?.type === 'returnDecision' || confirmAction?.type === 'rejectDiscount' ? 'danger' : 'default'}
        requireReason={confirmAction?.type === 'returnDecision' || confirmAction?.type === 'rejectDiscount'}
        reasonLabel={confirmAction?.type === 'rejectDiscount' ? 'เหตุผลที่ปฏิเสธส่วนลด' : 'เหตุผลที่ตีกลับ'}
        busy={sendQuote.isPending || approveDecision.isPending || returnDecisionToImport.isPending
          || issueQuotation.isPending || approveDiscount.isPending || rejectDiscount.isPending}
        onCancel={() => setConfirmAction(null)}
        onConfirm={(reason) => {
          const action = confirmAction;
          setConfirmAction(null);
          if (action?.type === 'sendQuote') sendQuote.mutate({ quote: action.quote, draft: action.emailDraft });
          if (action?.type === 'approveDecision') approveDecision.mutate(action.decision);
          if (action?.type === 'returnDecision') returnDecisionToImport.mutate({ decision: action.decision, reason });
          if (action?.type === 'issueQuotation') issueQuotation.mutate(action.quotation);
          if (action?.type === 'approveDiscount') approveDiscount.mutate(action.approval);
          if (action?.type === 'rejectDiscount') rejectDiscount.mutate({ approval: action.approval, reason });
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
          onRequestSend={() => {
            const quote = emailModalQuote;
            const draft = emailDrafts[quote.id] ?? { emailTo: quote.emailTo ?? '', emailSubject: quote.emailSubject ?? '', emailBody: quote.emailBody ?? '', note: quote.note ?? '' };
            // A FAILED dispatch has permanently exhausted its own clientRequestId (the backend's
            // unique (created_by, client_request_id) index would just replay that same dead row),
            // so a manual retry must mint a fresh idempotency key rather than reuse whatever is
            // cached for this quote.
            const clientRequestId = quote.dispatchStatus === 'FAILED'
              ? generateClientRequestId()
              : (sendClientRequestIds[quote.id] ?? generateClientRequestId());
            setSendClientRequestIds((cur) => ({ ...cur, [quote.id]: clientRequestId }));
            // Close this modal before opening the shared ConfirmDialog rather than stacking two
            // focus-trapped modals — see FactoryEmailDraftModal's own doc comment.
            setEmailModalQuoteId(null);
            setConfirmAction({ type: 'sendQuote', quote, emailDraft: draft });
          }}
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
