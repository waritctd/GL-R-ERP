import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import {
  DialogCallout, DialogSummary, DialogTable, dialogTd,
} from '../../components/common/DocumentDialogParts.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { Skeleton, SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
import { depositNoticeStatusLabel, formatThaiDate } from '../../utils/format.js';
import { downloadBlob } from '../../utils/download.js';
import { warnIfBundleSkipped } from '../../utils/userMessages.js';
import { round2 } from '../quotations/quotationMeta.js';
import {
  createDepositNoticeDraft, dealQuotationsWithConfirmedQueryOptions, depositNoticeFileName, resolveNoticeQuotation,
  subtotalsDiffer,
} from './createDepositNoticeDraft.js';
import { DocumentPreparerSelect } from './DocumentPreparerSelect.jsx';
import { invalidateDealDocuments } from '../documentChain/documentChain.js';

function money(v) {
  if (v == null) return '—';
  return Number(v).toLocaleString('th-TH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

// pre-document placeholder ONLY. Once a document exists, the served value always wins (see `vatPercent`
// below); this constant is never consulted after that.
const FALLBACK_VAT_PERCENT = 0.07;

// Formats a VAT rate (0.07, 0.10, 0.075, …) as the bare percentage string used in labels — "7", "10", "7.5" —
// rounded to 2dp of the percent value so float noise (e.g. the classic "7.000000000000001") never reaches the
// screen.
function formatVatPercent(rate) {
  return String(Math.round(Number(rate) * 100 * 100) / 100);
}

// A line's net unit price: 0 is a real price (a 100%-discounted line), only a missing one falls
// back to the unit price.
function lineNet(item) {
  return Number(item.netUnitPrice ?? item.unitPrice) || 0;
}

// A line's amount: the stored one (a quotation's own, rounded once) until the row is edited, else net x qty.
function lineAmount(item) {
  return item.amount ?? round2(lineNet(item) * (Number(item.qty) || 0));
}

// The quotation document view's item grid (QuotationDocumentView ITEM_GRID), so both documents read as one table.
const NOTICE_ITEM_GRID = 'grid-cols-[minmax(0,3fr)_minmax(5.75rem,0.8fr)_minmax(5.75rem,0.9fr)_minmax(3.5rem,0.6fr)_minmax(5.75rem,0.9fr)_minmax(8.25rem,1fr)] tablet:min-w-0 reflow-cards';
const NO_ITEMS_MESSAGE = 'กรุณาเพิ่มรายการสินค้าอย่างน้อย 1 รายการ';

// An untouched เพิ่มรายการ row (or one emptied again): no description and nothing to bill. Left
// out of the save (the backend refuses a line without a description) and of the issue checks.
function isBlankRow(item) {
  return !String(item.description ?? '').trim() && !(lineNet(item) * (Number(item.qty) || 0));
}

// The one wording for "this notice's subtotal is not its quotation's", on the page and in every issue confirm.
export function quotationDiffersText(quotation, noticeSubtotal) {
  return `ใบเสนอราคา ${quotation.number} มูลค่า ${money(quotation.subtotalAmount)} — ใบแจ้งนี้ ${money(noticeSubtotal)}`;
}

/**
 * The confirm-before-issue body (drawing m-deposit-issue, 2026-11-16), shared by this editor and the deal page's
 * เงิน tab ออกเอกสาร. Display only: every figure is passed in by the caller — the served draft's subtotal /
 * depositAmount / vatAmount / totalPayable on the deal page, this editor's own สรุปยอด figures here — and is shown
 * with the same `money` formatter; nothing is computed in this component.
 *
 * Deliberate departures from the drawing: the value row is "มูลค่าสินค้า" with the notice's own reference beside it
 * (the reference field takes a PO number too, and the notice's lines may have been edited after it was drafted from
 * the quotation, so "มูลค่าตามใบเสนอราคา" could name the wrong document or figure); the hint does not mention
 * "เงินที่คาดว่าจะเข้า" (DepositNoticeService#createDraftFromQuotation takes the percent from the quotation, never
 * from expected payments).
 *
 * `estimate` (fix round 1, F2) — this editor's own confirm. Its figures are computed in the browser and the server
 * recomputes them on save (DepositNoticeRepository#update, BigDecimal HALF_UP), so they can be a satang off the
 * issued document. They are therefore shown as the old confirm showed them — deposit, VAT, total — under a plain
 * "ยอดโดยประมาณ" line, without the subtotal row or the summary line that would present them as the document's
 * content. The deal page's เงิน tab issues its saved draft exactly as stored, so it shows the full drawn summary.
 *
 * The irreversibility warning is first in the body (F4): the body scrolls and the footer does not, so first is the
 * one place that is always on screen with the ออกเอกสาร button on a short phone.
 */
export function DepositIssueSummary({
  customerName, depositPercent, subtotal, reference, deposit, vatPercent, vat, total, estimate = false, quotation = null,
}) {
  const percent = Math.round(Number(depositPercent) * 100);
  const differs = quotation != null && subtotalsDiffer(subtotal, quotation.subtotalAmount);
  return (
    <div className="flex flex-col gap-3.5">
      <DialogCallout tone="warn" testId="deposit-issue-warning">
        ออกเลขที่เอกสารแล้ว <strong>หลังจากนี้จะไม่สามารถแก้ไขได้</strong>
      </DialogCallout>
      {estimate ? (
        <p className="m-0 text-sm text-text-secondary" data-testid="deposit-issue-estimate">
          ยอดโดยประมาณ · ยอดจริงตามเอกสารที่ระบบออก
        </p>
      ) : (
        <DialogSummary testId="deposit-issue-summary">
          {`มัดจำ ${percent}% · ${money(total)} บาท รวม VAT · ถึง ${customerName || 'ลูกค้า'}`}
        </DialogSummary>
      )}
      {differs ? (
        <p className="m-0 text-sm text-warning-dark" data-testid="deposit-issue-differs">
          {quotationDiffersText(quotation, subtotal)}
        </p>
      ) : null}
      <DialogTable label="ยอดในใบแจ้งยอดเงินรับมัดจำ">
        <tbody>
          {estimate ? null : (
            <tr>
              <td className={dialogTd}>
                มูลค่าสินค้า
                {reference ? <span className="ml-1.5 text-xs text-text-muted">อ้างอิง {reference}</span> : null}
              </td>
              <td className={`${dialogTd} text-right`}>{money(subtotal)} บาท</td>
            </tr>
          )}
          <tr>
            <td className={dialogTd}>มัดจำ {percent}% (ก่อน VAT)</td>
            <td className={`${dialogTd} text-right`}>{money(deposit)} บาท</td>
          </tr>
          <tr>
            <td className={dialogTd}>VAT {formatVatPercent(vatPercent)}%</td>
            <td className={`${dialogTd} text-right`}>{money(vat)} บาท</td>
          </tr>
          <tr className="font-bold">
            <td className="px-2 py-2">ยอดที่ลูกค้าต้องชำระ</td>
            <td className="px-2 py-2 text-right">{money(total)} บาท</td>
          </tr>
        </tbody>
      </DialogTable>
      <p className="m-0 text-xs text-text-muted">ยอดเริ่มต้นเติมจากใบเสนอราคาของงานนี้ แก้รายการได้จนกว่าจะออกเอกสาร</p>
    </div>
  );
}

// validation (UX-03) ────────────────────────────────────────────────────── This gate fires ONLY from
// handleIssue() — never from handleSave(). This is a draft editor: users save partial
// drafts incrementally, and blocking that would break a legitimate workflow.
const customerNameSchema = z.string().trim().min(1, 'กรุณากรอกชื่อบริษัท / หน่วยงาน');

function makeLineItemSchema(rowNumber) {
  return z.object({
    description: z.string(),
    qty: z.union([z.string(), z.number()]).nullable().optional(),
    discount: z.boolean().optional(),
  }).superRefine((item, ctx) => {
    if (!String(item.description ?? '').trim()) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['description'], message: `กรุณากรอกรายละเอียดสินค้าในรายการที่ ${rowNumber}` });
    }
    if (!item.discount && (!item.qty || Number(item.qty) <= 0)) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['qty'], message: `กรุณากรอกจำนวนมากกว่า 0 ในรายการที่ ${rowNumber}` });
    }
  });
}

/** Validates the deposit notice at ISSUE time only. Returns every invalid field plus `order` — those same keys
 * in on-screen top-to-bottom order — so handleIssue() can scroll/focus the first one without depending on
 * zod's internal issue ordering. */
function validateIssueForm({ customerName, items, total, depositPercent }) {
  const errors = {};
  const order = [];

  const nameResult = customerNameSchema.safeParse(customerName);
  if (!nameResult.success) {
    errors.customerName = nameResult.error.issues[0].message;
    order.push('customerName');
  }

  if (items.every(isBlankRow)) {
    errors.items = NO_ITEMS_MESSAGE;
    order.push('items');
  } else {
    items.forEach((item, index) => {
      if (isBlankRow(item)) return;
      const result = makeLineItemSchema(index + 1).safeParse(item);
      if (!result.success) {
        for (const issue of result.error.issues) {
          const key = `items.${index}.${issue.path[0]}`;
          errors[key] = issue.message;
          order.push(key);
        }
      }
    });
  }

  // The % sits after the items on screen. A whole 1–100 is what the backend issues; outside it the
  // ฿0 rule below would blame the unit price (0%) or the backend would refuse behind the dialog (150%).
  const percent = depositPercent === '' ? NaN : Math.round(Number(depositPercent) * 100);
  if (!(percent >= 1 && percent <= 100)) {
    errors.depositPercent = 'กรุณากรอก % มัดจำเป็นจำนวนเต็ม 1–100';
    order.push('depositPercent');
  }

  // Rule 4: issuing a ฿0 deposit notice is meaningless and would still advance the ticket's payment track.
  if (order.length === 0 && !(Number(total) > 0)) {
    errors.total = 'ยอดเงินรวมต้องมากกว่า 0 บาท — กรุณาตรวจสอบราคาต่อหน่วยและจำนวนในรายการสินค้า';
    order.push('total');
  }

  return { errors, order };
}

// The request body for a save of `form`; the stamp (expectedUpdatedAt) is the caller's.
function buildPayloadFrom(form, expectedUpdatedAt) {
  return {
    customerName:    form.customerName,
    customerTaxId:   form.customerTaxId,
    customerAddress: form.customerAddress,
    projectName:     form.projectName,
    reference:       form.reference,
    // A cleared field keeps the stored % (null) rather than failing the whole save at 0.
    depositPercent:  form.depositPercent === '' ? null : Number(form.depositPercent),
    notes:           form.notes,
    preparerId:      form.preparerId ?? null,
    expectedUpdatedAt,
    items: form.items.filter((it) => !isBlankRow(it)).map((it, idx) => ({
      seq:          idx + 1,
      description:  it.description ?? '',
      qty:          Number(it.qty) || 0,
      unit:         it.unit ?? 'แผ่น',
      unitPrice:    Number(it.unitPrice) || 0,
      discountLabel: it.discountLabel ?? null,
      netUnitPrice: lineNet(it),
      amount:       it.amount ?? null,
    })),
  };
}

// What the page would save (stamp left out), as a string to compare: a download of a draft saves first only when
// this differs from the key taken at the last load/seed or successful save.
function payloadKey(payload) {
  return JSON.stringify({ ...payload, expectedUpdatedAt: null });
}

export function DepositNoticePage({ ticketId, onBack, showToast }) {
  const queryClient = useQueryClient();
  const [customerSearch, setCsSearch] = useState('');
  const [confirmIssue, setConfirmIssue] = useState(false);
  // An ISSUED notice is read-only until sales presses แก้ไข; saving then updates the same document in place.
  const [editingIssued, setEditingIssued] = useState(false);
  const [downloading, setDownloading] = useState(null); // 'xlsx' | 'pdf' | null

  // UX-03: per-field issue-validation errors, keyed 'customerName' | 'items' (at-least-one-line-item) |
  // `items.<index>.<field>` | 'total'. Populated only by handleIssue() below — see the SCOPING DECISION comment
  // on validateIssueForm().
  const [fieldErrors, setFieldErrors] = useState({});
  const fieldRefs = useRef({});
  function clearFieldError(key) {
    setFieldErrors((prev) => {
      if (!(key in prev)) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  }

  // form state
  const [form, setForm] = useState({
    customerName: '', customerTaxId: '', customerAddress: '',
    projectName: '', reference: '',
    depositPercent: 0.5,
    notes: [],
    items: [],
    preparerId: null,
  });

  // Reads (TanStack Query) --- Mount only LOADS existing documents — it must never create one as a side effect
  // of navigating here. None of the queries below ever create anything.
  const noteTemplatesQuery = useQuery({
    queryKey: queryKeys.depositNoteTemplates(),
    queryFn: () => api.depositNotices.noteTemplates().then((response) => response.templates ?? []),
  });
  const noteTemplates = noteTemplatesQuery.data ?? [];

  // Keyed by the live search text so every keystroke gets its own cache entry (matches the old onChange's
  // per-keystroke api.customers.search(text) call); customerSearch === '' is the initial "whole master list"
  // load loadDocs used to do up front.
  const customersQuery = useQuery({
    queryKey: queryKeys.customersSearch(customerSearch),
    queryFn: () => api.customers.search(customerSearch).then((response) => response.customers ?? []),
  });
  const customers = customersQuery.data ?? [];

  const depositNoticesQuery = useQuery({
    queryKey: queryKeys.depositNotices(ticketId),
    queryFn: () => api.depositNotices.listByTicket(ticketId).then((response) => response.depositNotices ?? []),
  });
  // Advisory only: the draft's reference is the QT number, so its quotation's own deposit % can be
  // compared with the % typed here. A failed read (e.g. no view access) just shows no hint.
  // Read under the WITH-confirmed key (the full response, so `orderConfirmedQuotationId` is kept): the items-only
  // `dealQuotationsByTicket` entry keeps its array shape for its other readers.
  const dealQuotationsQuery = useQuery(dealQuotationsWithConfirmedQueryOptions(ticketId));
  const doc = useMemo(() => {
    const docs = depositNoticesQuery.data ?? [];
    const draft = docs.find((d) => d.status === 'DRAFT');
    const latestIssued = docs
      .filter((d) => d.status === 'ISSUED')
      .sort((a, b) => (b.version ?? 0) - (a.version ?? 0))[0];
    return draft ?? latestIssued ?? null;
  }, [depositNoticesQuery.data]);

  // The full-page skeleton only gates on the two loads a first paint actually needs (doc list + note templates).
  const loading = depositNoticesQuery.isLoading || noteTemplatesQuery.isLoading;

  // Same reasoning as `loading`: only the doc-list/template load blocks the page with an error+retry state,
  // matching the original loadDocs try/catch scope. A failed customer search (mid-session, per keystroke) stays
  // silent, as it did before (the old onChange handler had its own `.catch(() => {})`).
  const loadError = depositNoticesQuery.error || noteTemplatesQuery.error;

  // showToast (useToast.js) is now a stable useCallback([]) identity, so it belongs directly in this effect's
  // deps -- the eslint-disable this line used to carry (to route around showToast's then-unstable identity
  // retriggering the effect) is no longer needed.
  useEffect(() => {
    if (loadError) showToast('error', loadError.message || 'โหลดไม่สำเร็จ');
  }, [loadError, showToast]);

  function retryLoad() {
    depositNoticesQuery.refetch();
    noteTemplatesQuery.refetch();
    customersQuery.refetch();
  }

  // payloadKey of what the page would save, as of the last load/seed or successful save.
  const savedKeyRef = useRef(null);

  function populateForm(d, customerList = []) {
    // The tax id / address filled from the customer master below are NOT "as loaded": savedKeyRef is seeded from the
    // stored values, so the fill counts as a change and a download saves it first (the file carries what the screen
    // shows). If taxId/address already saved in draft, use them directly Otherwise try to match customerName against
    // master
    let taxId   = d.customerTaxId   ?? '';
    let address = d.customerAddress ?? '';
    if (d.customerName && (!taxId || !address)) {
      const match = customerList.find(
        (c) => c.name.trim().toLowerCase() === d.customerName.trim().toLowerCase()
      );
      if (match) {
        taxId   = taxId   || match.taxId   || '';
        address = address || [match.address, match.branch].filter(Boolean).join(' ');
      }
    }
    const seeded = {
      customerName:    d.customerName   ?? '',
      customerTaxId:   taxId,
      customerAddress: address,
      projectName:     d.projectName   ?? '',
      reference:       d.reference     ?? '',
      // As stored (null -> blank, which saves as "keep"): a save of another field must not rewrite it.
      depositPercent:  d.depositPercent ?? '',
      notes:           d.notes         ?? [],
      preparerId:      d.preparerId    ?? null,
      // A qty < 0 line is the quotation's ส่วนลดพิเศษ: its qty is not editable and is allowed past the qty > 0 check.
      items:           (d.items ?? []).map((it) => ({ ...it, discount: Number(it.qty) < 0 })),
    };
    savedKeyRef.current = payloadKey(buildPayloadFrom({
      ...seeded, customerTaxId: d.customerTaxId ?? '', customerAddress: d.customerAddress ?? '',
    }, null));
    setForm(seeded);
  }

  // Re-seed the editable `form` whenever the doc's identity/version/status actually changes (new draft created,
  // or DRAFT -> ISSUED) — not on every background refetch of the same doc (e.g. a save doesn't bump
  // version/status, so no reseed fires and in-flight edits aren't clobbered).
  const docSeedKey = doc ? `${doc.id}:${doc.version}:${doc.status}` : null;
  // The draft's updatedAt as last read from the server (the list, or this page's own save):
  // sent as expectedUpdatedAt, so a save over someone else's newer one is refused (409).
  const stampRef = useRef(null);
  useEffect(() => { stampRef.current = doc?.updatedAt ?? null; }, [doc?.updatedAt]);
  useEffect(() => {
    if (doc) populateForm(doc, customers);
    // A reseed means a different document identity (or the same one flipping to ISSUED) — any leftover UX-03
    // issue-validation errors belong to the previous edit session and would otherwise linger on screen.
    setFieldErrors({});
    // eslint-disable-next-line react-hooks/exhaustive-deps -- deliberately keyed on docSeedKey only, see comment above
  }, [docSeedKey]);

  // Create / save / issue all move the document chain the deal page and the quotation page's card read: one helper
  // invalidates the notice list AND those keys.
  function invalidateDepositNotices() {
    return invalidateDealDocuments(queryClient, ticketId);
  }

  async function saveDraft() {
    const payload = buildPayload();
    const res = await api.depositNotices.update(doc.id, payload);
    savedKeyRef.current = payloadKey(payload);
    stampRef.current = res?.depositNotice?.updatedAt ?? stampRef.current;
    return res;
  }

  // A 409 means the server's copy moved on (saved elsewhere, or issued): reload it, form included,
  // and show the server's message.
  async function onSaveError(err, fallback) {
    showToast('error', err.message || fallback);
    if (err.status !== 409) return;
    const { data } = await depositNoticesQuery.refetch();
    const fresh = (data ?? []).find((d) => d.id === doc.id);
    if (fresh) populateForm(fresh, customers);
  }

  const createDraftMutation = useMutation({
    // See createDepositNoticeDraft for the accepted-quotation vs ticket-level routing.
    mutationFn: () => createDepositNoticeDraft(ticketId, noteTemplates),
    onSuccess: () => invalidateDepositNotices(),
    onError: (err) => showToast('error', err.message || 'สร้างเอกสารฉบับร่างไม่สำเร็จ'),
  });

  function handleCreateDraft() {
    createDraftMutation.mutate();
  }

  const saveMutation = useMutation({
    mutationFn: saveDraft,
    onSuccess: () => {
      showToast('success', 'บันทึกแล้ว');
      setEditingIssued(false);
      invalidateDepositNotices();
    },
    onError: (err) => onSaveError(err, 'บันทึกไม่สำเร็จ'),
  });

  // Blank rows are not sent and an empty `items` makes the backend keep the stored lines, so a form
  // whose rows were all cleared would "save" and bring the old lines back: refuse it instead.
  function refusesClearedItems() {
    if (!(doc.items?.length > 0 && form.items.every(isBlankRow))) return false;
    setFieldErrors({ items: NO_ITEMS_MESSAGE });
    return true;
  }

  function handleSave() {
    if (!doc || refusesClearedItems()) return;
    saveMutation.mutate();
  }

  function handleIssue() {
    if (!doc) return;
    // UX-03: validate before opening the confirm dialog — don't ask "are you sure?" for something that's then
    // going to fail. `total` is read here (never written) from the money-math block below; see the HARD CONSTRAINT
    // comment on that block.
    const { errors, order } = validateIssueForm({
      customerName: form.customerName, items: form.items, total, depositPercent: form.depositPercent,
    });
    if (order.length > 0) {
      setFieldErrors(errors);
      const node = fieldRefs.current[order[0]];
      if (node) {
        if (typeof node.scrollIntoView === 'function') {
          node.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
        node.focus();
      }
      return;
    }
    setFieldErrors({});
    setConfirmIssue(true);
  }

  const issueMutation = useMutation({
    mutationFn: async () => {
      // Save first.
      await saveDraft();
      return api.depositNotices.issue(doc.id, { expectedUpdatedAt: stampRef.current });
    },
    onSuccess: (res) => {
      showToast('success', `ออกเอกสาร ${res.depositNotice.docNumber} เรียบร้อย`);
      setConfirmIssue(false);
      invalidateDepositNotices();
      // Issuing the deposit notice is the payment-track step that advances the ticket (see
      // mockApi.depositNotices.issue) — refresh the ticket list + detail so those views don't show stale
      // paymentStatus.
      queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    },
    onError: (err) => {
      setConfirmIssue(false);
      onSaveError(err, 'ออกเอกสารไม่สำเร็จ');
    },
  });

  function confirmIssueDocument() {
    issueMutation.mutate();
  }

  const creatingDraft = createDraftMutation.isPending;
  const saving = saveMutation.isPending || issueMutation.isPending;
  const downloadBusy = downloading != null || saving;

  function setField(key, value) {
    setForm((f) => ({ ...f, [key]: value }));
    if (key === 'customerName') clearFieldError('customerName');
    // The deposit % field feeds directly into `deposit`/`total` below —
    // clear a stale "total must be > 0" error so it can be re-checked fresh
    // on the next issue attempt.
    if (key === 'depositPercent') {
      clearFieldError('total');
      clearFieldError('depositPercent');
    }
  }

  function selectCustomer(c) {
    setForm((f) => ({
      ...f,
      customerName:    c.name,
      customerTaxId:   c.taxId ?? '',
      customerAddress: [c.address, c.branch].filter(Boolean).join(' '),
    }));
    setCsSearch('');
    clearFieldError('customerName');
  }

  function toggleNote(text) {
    setForm((f) => ({
      ...f,
      notes: f.notes.includes(text) ? f.notes.filter((n) => n !== text) : [...f.notes, text],
    }));
  }

  function setItemField(idx, key, value) {
    setForm((f) => ({
      ...f,
      items: f.items.map((it, i) => {
        if (i !== idx) return it;
        const updated = { ...it, [key]: value };
        // recalculate netUnitPrice if unitPrice or discountLabel changed
        if (key === 'unitPrice' || key === 'discountLabel') {
          // simple: netUnitPrice = unitPrice (no discount logic for now)
          updated.netUnitPrice = updated.unitPrice;
        }
        // an edited line recomputes its amount from net x qty
        if (['qty', 'unitPrice', 'netUnitPrice'].includes(key)) updated.amount = null;
        return updated;
      }),
    }));
    if (key === 'description') clearFieldError(`items.${idx}.description`);
    if (key === 'qty') clearFieldError(`items.${idx}.qty`);
    // Any of these four feed into `total` below — clear a stale "total must be > 0" error so it's re-checked fresh
    // on the next issue attempt.
    if (['qty', 'unitPrice', 'netUnitPrice', 'discountLabel'].includes(key)) clearFieldError('total');
  }

  // An issued notice downloads as stored. A draft is saved first when the form differs from what was loaded or last
  // saved (through the same path as บันทึกร่าง, so the 409 handling is the same) and downloads only if that save
  // succeeded; an unchanged draft downloads as stored without saving, so a reader who cannot save still gets the file.
  async function handleDownload(extension) {
    if (!doc || downloading || saving) return;
    if (!locked && refusesClearedItems()) return;
    setDownloading(extension);
    try {
      if (!locked && payloadKey(buildPayload()) !== savedKeyRef.current) {
        try {
          await saveDraft();
          showToast('success', 'บันทึกแล้ว');
          invalidateDepositNotices();
        } catch (err) {
          // A viewer who cannot save still gets the stored file; any other refusal (a 409 reloads) stops the download.
          if (err.status !== 403) {
            await onSaveError(err, 'บันทึกไม่สำเร็จ');
            return;
          }
        }
      }
      try {
        const blob = extension === 'pdf'
          ? await api.depositNotices.downloadPdf(doc.id)
          : await api.depositNotices.downloadXlsx(doc.id);
        downloadBlob(blob, depositNoticeFileName(doc, ticketId), extension);
        warnIfBundleSkipped(blob, showToast);
      } catch (err) {
        showToast('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
      }
    } finally {
      setDownloading(null);
    }
  }

  function buildPayload() {
    return buildPayloadFrom(form, stampRef.current);
  }

  // Recalculate summary from form items, rounding each step to the satang as
  // DepositNoticeRepository does (HALF_UP), so the screen matches the stored document.
  const subtotal = round2(form.items.reduce((sum, it) => sum + lineAmount(it), 0));
  const deposit = round2(subtotal * (Number(form.depositPercent) || 0));
  // the served document's own vatPercent always wins once one exists — only before that (no `doc` yet) does this
  // fall back to FALLBACK_VAT_PERCENT above.
  const vatPercent = Number(doc?.vatPercent ?? FALLBACK_VAT_PERCENT);
  const vat = round2(deposit * vatPercent);
  const total = round2(deposit + vat);

  if (loading) {
    return (
      <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]" aria-busy="true" aria-label="กำลังโหลดใบแจ้งยอดเงินรับมัดจำ">
        <header className="flex items-center gap-3 mb-1">
          <Skeleton width={80} height={28} />
          <div className="flex-1">
            <Skeleton width="35%" height={20} />
          </div>
        </header>
        <Panel>
          <Panel.Header><Skeleton width="25%" height={16} /></Panel.Header>
          <div className="px-4.5 py-3.5">
            <SkeletonText lines={4} />
          </div>
        </Panel>
        <Panel>
          <Panel.Header><Skeleton width="25%" height={16} /></Panel.Header>
          <div className="px-4.5 py-3.5">
            <SkeletonText lines={3} />
          </div>
        </Panel>
      </div>
    );
  }

  const isIssued = doc?.status === 'ISSUED';
  const locked = isIssued && !editingIssued; // read-only view
  const referencedQuotation = resolveNoticeQuotation(dealQuotationsQuery.data, form.reference);
  const noticePercent = Math.round(Number(form.depositPercent) * 100);
  // A quotation with no % stored means the 30% default (as everywhere else).
  const quotationPercent = referencedQuotation ? (referencedQuotation.depositPercent ?? 30) : null;
  // A quotation addressed to a party names the customer: the header fields follow it (the server ignores typed values).
  const customerFromQuotation = referencedQuotation?.partyId != null;
  const percentDiffers = !locked && form.depositPercent !== '' && quotationPercent != null
    && quotationPercent !== noticePercent;
  const subtotalDiffers = !locked && referencedQuotation != null
    && subtotalsDiffer(subtotal, referencedQuotation.subtotalAmount);

  return (
    <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
      <PageHeader
        title={`ใบแจ้งยอดเงินรับมัดจำ${doc?.docNumber ? ` ${doc.docNumber}` : ''}`}
        subtitle={doc
          ? [form.customerName, form.projectName].filter(Boolean).join(' · ') || undefined
          : 'สร้างเอกสารฉบับร่างเพื่อเริ่มกรอกรายละเอียด'}
        context={doc ? (
          <>
            <StatusBadge tone={depositNoticeStatusLabel(doc.status).tone}>
              {depositNoticeStatusLabel(doc.status).label}
            </StatusBadge>
            {doc.version > 1 ? <span className="text-sm text-text-muted">ครั้งที่ {doc.version}</span> : null}
          </>
        ) : null}
        actions={doc ? (
          <>
            <OverflowMenu
              label="ดาวน์โหลด"
              triggerLabel="ดาวน์โหลด"
              triggerIcon="download"
              align="start"
              items={[
                { key: 'pdf', label: 'PDF', icon: 'fileText', onSelect: () => handleDownload('pdf'), disabled: downloadBusy, disabledReason: downloading != null ? 'กำลังดาวน์โหลด…' : 'กำลังบันทึก…' },
                { key: 'xlsx', label: 'Excel', icon: 'fileText', onSelect: () => handleDownload('xlsx'), disabled: downloadBusy, disabledReason: downloading != null ? 'กำลังดาวน์โหลด…' : 'กำลังบันทึก…' },
              ]}
            />
            {!isIssued ? (
              <Button variant="primary" onClick={handleIssue} disabled={saving}>ออกเอกสาร</Button>
            ) : null}
            {locked ? (
              <Button variant="secondary" onClick={() => setEditingIssued(true)}>แก้ไขฉบับปัจจุบัน</Button>
            ) : null}
          </>
        ) : null}
      />

      <div className="flex min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1.5 text-sm">
        <span className="font-bold text-text-muted">งาน</span>
        <span className="min-w-0 max-w-full truncate font-bold text-text">{form.customerName || '-'}</span>
        {form.reference ? (
          <>
            <span aria-hidden="true" className="text-text-muted">·</span>
            <span className="text-text-muted">อ้างอิง</span>
            <code className="font-mono text-sm text-text">{form.reference}</code>
          </>
        ) : null}
        {doc ? (
          <>
            <span aria-hidden="true" className="text-text-muted">·</span>
            <span className="text-text-muted">
              {isIssued
                ? `ออกโดย ${doc.issuedByName || '-'} · ${formatThaiDate(doc.issueDate)}`
                : doc.preparerName ? `จัดทำโดย ${doc.preparerName}` : ''}
            </span>
          </>
        ) : null}
        <button
          type="button"
          onClick={onBack}
          className="inline-flex min-h-[38px] cursor-pointer items-center gap-1 rounded-md border-0 bg-transparent p-0 font-bold text-primary hover:text-primary-hover mobile:min-h-[44px]"
        >
          เปิดงาน
          <Icon name="chevronRight" size={14} />
        </button>
      </div>

      {loadError && !doc ? (
        <Panel>
          <EmptyState icon="fileText" title="โหลดข้อมูลไม่สำเร็จ" description={loadError.message || 'โหลดไม่สำเร็จ'} />
          <div className="flex justify-center px-4.5 pb-4.5">
            <Button variant="secondary" onClick={retryLoad}>
              <Icon name="refresh" size={14} /> ลองใหม่
            </Button>
          </div>
        </Panel>
      ) : !doc ? (
        <Panel>
          <EmptyState
            icon="fileText"
            title="ยังไม่มีใบแจ้งยอดเงินรับมัดจำ"
            description="สร้างเอกสารฉบับร่างเพื่อเริ่มกรอกรายละเอียดใบแจ้งยอดมัดจำสำหรับงานนี้"
          />
          <div className="flex justify-center px-4.5 pb-4.5">
            <Button variant="primary" onClick={handleCreateDraft} disabled={creatingDraft}>
              <Icon name="plus" size={14} />
              {creatingDraft ? 'กำลังสร้าง…' : 'สร้างเอกสารฉบับร่าง'}
            </Button>
          </div>
        </Panel>
      ) : (
        <>
          <Panel title="ข้อมูลลูกค้า">
            {locked ? (
              <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1 text-sm">
                <div>
                  <span className="block text-2xs font-bold uppercase text-text-muted">ลูกค้า</span>
                  <strong>{form.customerName || '-'}</strong>
                </div>
                <div>
                  <span className="block text-2xs font-bold uppercase text-text-muted">โครงการ</span>
                  <strong>{form.projectName || '-'}</strong>
                </div>
                <div>
                  <span className="block text-2xs font-bold uppercase text-text-muted">เลขที่ผู้เสียภาษี</span>
                  <strong>{form.customerTaxId || '-'}</strong>
                </div>
                <div>
                  <span className="block text-2xs font-bold uppercase text-text-muted">อ้างอิง PO / ใบเสนอราคา</span>
                  <strong>{form.reference || '-'}</strong>
                </div>
                <div>
                  <span className="block text-2xs font-bold uppercase text-text-muted">ผู้จัดทำ</span>
                  <strong>{doc?.preparerName || '-'}</strong>
                </div>
                <div className="col-span-full">
                  <span className="block text-2xs font-bold uppercase text-text-muted">ที่อยู่</span>
                  <strong className="whitespace-pre-line">{form.customerAddress || '-'}</strong>
                </div>
              </div>
            ) : (
              <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
                {customerFromQuotation ? (
                  <p className="col-span-full m-0 text-sm text-text-muted" data-testid="customer-from-quotation">
                    ชื่อ เลขผู้เสียภาษี และที่อยู่ดึงจากใบเสนอราคา {referencedQuotation.number} (แก้ไม่ได้)
                  </p>
                ) : null}
                <div className={customerFromQuotation ? 'hidden' : 'relative col-span-full'}>
                  <FormField label="ค้นหาลูกค้า (master)" htmlFor="doc-customer-search">
                    <input
                      id="doc-customer-search"
                      value={customerSearch}
                      onChange={(e) => setCsSearch(e.target.value)}
                      placeholder="ชื่อบริษัท หรือ เลขภาษี…"
                    />
                  </FormField>
                  {customerSearch && customers.length > 0 && (
                    <div className="absolute left-0 right-0 top-full z-50 max-h-[180px] overflow-y-auto rounded-[6px] border border-border-subtle bg-surface shadow-[0_4px_12px_rgba(0,0,0,0.1)]">
                      {customers.map((c) => (
                        <button key={c.id} type="button"
                          onClick={() => selectCustomer(c)}
                          className="w-full cursor-pointer border-0 border-b border-surface-subtle bg-transparent px-3 py-2 text-left text-sm text-text">
                          <strong className="block">{c.name}</strong>
                          <span className="text-2xs text-text-muted">{c.taxId} · {c.address}</span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
                <FormField label="ชื่อบริษัท / หน่วยงาน" htmlFor="doc-customer-name" required error={fieldErrors.customerName}>
                  <input
                    id="doc-customer-name"
                    ref={(el) => { fieldRefs.current.customerName = el; }}
                    value={form.customerName} readOnly={customerFromQuotation}
                    onChange={(e) => setField('customerName', e.target.value)} placeholder="บริษัท…"
                  />
                </FormField>
                <FormField label="เลขประจำตัวผู้เสียภาษี" htmlFor="doc-customer-tax">
                  <input id="doc-customer-tax" value={form.customerTaxId} readOnly={customerFromQuotation}
                    onChange={(e) => setField('customerTaxId', e.target.value)} placeholder="0000000000000" />
                </FormField>
                <div className="col-span-full">
                  <FormField label="ที่อยู่" htmlFor="doc-customer-address">
                    <textarea id="doc-customer-address" rows={2} value={form.customerAddress} readOnly={customerFromQuotation}
                      onChange={(e) => setField('customerAddress', e.target.value)} placeholder="ที่อยู่…" className="resize-y" />
                  </FormField>
                </div>
                <FormField label="โครงการ" htmlFor="doc-project">
                  <input id="doc-project" value={form.projectName}
                    onChange={(e) => setField('projectName', e.target.value)} placeholder="โครงการ…" />
                </FormField>
                <FormField label="อ้างอิง PO / ใบเสนอราคา" htmlFor="doc-reference">
                  <input id="doc-reference" value={form.reference}
                    onChange={(e) => setField('reference', e.target.value)} placeholder="PO-XXXX" />
                </FormField>
                <DocumentPreparerSelect ticketId={ticketId} id="doc-preparer" value={form.preparerId}
                  storedName={doc?.preparerName} onChange={(v) => setField('preparerId', v)} />
              </div>
            )}
          </Panel>

          <Panel title="รายการสินค้า" flush>
            <div
              id="doc-items-panel"
              ref={(el) => { fieldRefs.current.items = el; }}
              tabIndex={-1}
              aria-invalid={fieldErrors.items ? true : undefined}
              aria-describedby={fieldErrors.items ? fieldErrorId('doc-items-panel') : undefined}
            >
              <div className={`${NOTICE_ITEM_GRID} table-head`}>
                <span>รายละเอียด</span>
                <span className="text-right">จำนวน</span>
                <span className="text-right">ราคา/หน่วย</span>
                <span className="text-right">ส่วนลด</span>
                <span className="text-right">สุทธิ</span>
                <span className="text-right">เป็นเงิน</span>
              </div>
              {form.items.map((it, idx) => {
                const unitPrice = Number(it.unitPrice) || 0;
                const net = Number(it.netUnitPrice ?? it.unitPrice) || 0;
                const discount = unitPrice > net ? `ลด ${money(unitPrice - net)}` : '';
                const amount = lineAmount(it);
                return (
                  <div key={idx} className={`${NOTICE_ITEM_GRID} data-row`}>
                    {locked ? (
                      <>
                        <span className="min-w-0" data-label="รายละเอียด"><span className="font-bold">{it.description}</span></span>
                        <span className="tabular-nums text-right" data-label="จำนวน">{Number(it.qty).toLocaleString('th-TH')} {it.unit ?? ''}</span>
                        <span className="tabular-nums text-right" data-label="ราคา/หน่วย">฿{money(it.unitPrice)}</span>
                        <span className="tabular-nums text-right" data-label={discount ? 'ส่วนลด' : ''}>{discount}</span>
                        <span className="tabular-nums text-right" data-label="สุทธิ">฿{money(net)}</span>
                        <span className="tabular-nums text-right font-bold" data-label="เป็นเงิน">฿{money(amount)}</span>
                      </>
                    ) : (
                      <>
                        <span className="min-w-0" data-label="รายละเอียด">
                          <textarea
                            id={`doc-item-${idx}-description`}
                            ref={(el) => { fieldRefs.current[`items.${idx}.description`] = el; }}
                            rows={Math.max(1, String(it.description ?? '').split('\n').length)}
                            value={it.description ?? ''} placeholder="รายละเอียดสินค้า"
                            onChange={(e) => setItemField(idx, 'description', e.target.value)}
                            aria-label={`รายละเอียดรายการที่ ${idx + 1}`}
                            aria-invalid={fieldErrors[`items.${idx}.description`] ? true : undefined}
                            aria-describedby={fieldErrors[`items.${idx}.description`] ? fieldErrorId(`doc-item-${idx}-description`) : undefined}
                            className="w-full resize-y" />
                          {fieldErrors[`items.${idx}.description`] ? (
                            <span id={fieldErrorId(`doc-item-${idx}-description`)} role="alert" className="mt-1 block text-2xs font-bold text-danger">
                              {fieldErrors[`items.${idx}.description`]}
                            </span>
                          ) : null}
                        </span>
                        <span data-label="จำนวน">
                          {it.discount ? <span className="block tabular-nums text-right">{it.qty}</span> : <input type="number" value={it.qty ?? ''} placeholder="จำนวน"
                            id={`doc-item-${idx}-qty`}
                            ref={(el) => { fieldRefs.current[`items.${idx}.qty`] = el; }}
                            onChange={(e) => setItemField(idx, 'qty', e.target.value)}
                            aria-label={`จำนวนรายการที่ ${idx + 1}`}
                            aria-invalid={fieldErrors[`items.${idx}.qty`] ? true : undefined}
                            aria-describedby={fieldErrors[`items.${idx}.qty`] ? fieldErrorId(`doc-item-${idx}-qty`) : undefined}
                            className="w-full text-right" />}
                          {fieldErrors[`items.${idx}.qty`] ? (
                            <span id={fieldErrorId(`doc-item-${idx}-qty`)} role="alert" className="mt-1 block text-2xs font-bold text-danger">
                              {fieldErrors[`items.${idx}.qty`]}
                            </span>
                          ) : null}
                        </span>
                        <span data-label="ราคา/หน่วย">
                          <input type="number" value={it.unitPrice ?? ''} placeholder="ราคา"
                            aria-label={`ราคาต่อหน่วยรายการที่ ${idx + 1}`}
                            onChange={(e) => setItemField(idx, 'unitPrice', e.target.value)}
                            className="w-full text-right" />
                        </span>
                        <span className="tabular-nums text-right" data-label={discount ? 'ส่วนลด' : ''}>{discount}</span>
                        <span data-label="สุทธิ">
                          <input type="number" value={it.netUnitPrice ?? it.unitPrice ?? ''} placeholder="สุทธิ"
                            aria-label={`ราคาสุทธิรายการที่ ${idx + 1}`}
                            onChange={(e) => setItemField(idx, 'netUnitPrice', e.target.value)}
                            className="w-full text-right" />
                        </span>
                        <span className="tabular-nums text-right font-bold" data-label="เป็นเงิน">฿{money(amount)}</span>
                      </>
                    )}
                  </div>
                );
              })}
              {!locked ? (
                <div className="flex flex-wrap items-center gap-3 border-t border-border px-5 py-4">
                  <Button variant="secondary"
                    onClick={() => {
                      setForm((f) => ({ ...f, items: [...f.items, { seq: f.items.length + 1, description: '', qty: 1, unit: 'แผ่น', unitPrice: 0, netUnitPrice: 0 }] }));
                      clearFieldError('items');
                    }}>
                    <Icon name="plus" size={14} /> เพิ่มรายการ
                  </Button>
                  {fieldErrors.items ? (
                    <p id={fieldErrorId('doc-items-panel')} role="alert" className="m-0 text-xs font-bold text-danger">
                      {fieldErrors.items}
                    </p>
                  ) : null}
                </div>
              ) : null}
            </div>
          </Panel>

          <Panel title="เงื่อนไข">
            <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
              {locked ? (
                <div className="text-sm">
                  <span className="block text-2xs font-bold uppercase text-text-muted">มัดจำ</span>
                  <strong>{noticePercent}%</strong>
                </div>
              ) : (
                <FormField
                  label="มัดจำ %"
                  htmlFor="doc-deposit-percent"
                  error={fieldErrors.depositPercent}
                  hint={percentDiffers || subtotalDiffers
                    ? (
                      <>
                        {percentDiffers ? (
                          <span data-testid="deposit-percent-differs" className="text-warning-dark">
                            {`ใบเสนอราคา ${referencedQuotation.number} ระบุมัดจำ ${quotationPercent}% — ใบแจ้งนี้ใช้ ${noticePercent}%`}
                          </span>
                        ) : null}
                        {percentDiffers && subtotalDiffers ? <br /> : null}
                        {subtotalDiffers ? (
                          <span data-testid="deposit-subtotal-differs" className="text-warning-dark">
                            {quotationDiffersText(referencedQuotation, subtotal)}
                          </span>
                        ) : null}
                      </>
                    )
                    : undefined}
                >
                  <input type="number" min="1" max="100" step="1"
                    id="doc-deposit-percent"
                    ref={(el) => { fieldRefs.current.depositPercent = el; }}
                    value={form.depositPercent === '' ? '' : Math.round(Number(form.depositPercent) * 100)}
                    onChange={(e) => setField('depositPercent', e.target.value === '' ? '' : Math.round(Number(e.target.value)) / 100)} />
                </FormField>
              )}
              <div className="col-span-full">
                <span className="mb-1.5 block text-sm font-bold text-text-secondary">หมายเหตุในเอกสาร</span>
                <div className="flex flex-col gap-2">
                  {noteTemplates.map((t) => (
                    <label key={t.id} className={`flex items-start gap-2 text-sm leading-normal ${locked ? 'cursor-default' : 'cursor-pointer'}`}>
                      <input type="checkbox" disabled={locked}
                        checked={form.notes.includes(t.text)}
                        onChange={() => toggleNote(t.text)}
                        className={`mt-[3px] h-3.5 w-3.5 shrink-0 accent-info-dot ${locked ? 'cursor-default' : 'cursor-pointer'}`} />
                      <span className="min-w-0 flex-1">{t.text}</span>
                    </label>
                  ))}
                </div>
              </div>
            </div>
          </Panel>

          <Panel title="ยอดรวม">
            <div
              id="doc-summary-panel"
              ref={(el) => { fieldRefs.current.total = el; }}
              tabIndex={-1}
              aria-invalid={fieldErrors.total ? true : undefined}
              aria-describedby={fieldErrors.total ? fieldErrorId('doc-summary-panel') : undefined}
              className="flex flex-col items-end gap-1"
            >
              <span className="text-sm text-text-muted">รวมเป็นเงิน <span className="tabular-nums text-text">฿{money(subtotal)}</span></span>
              <span className="text-sm text-text-muted">ขอรับเงินมัดจำ {noticePercent}% <span className="tabular-nums text-text">฿{money(deposit)}</span></span>
              <span className="text-sm text-text-muted">ภาษีมูลค่าเพิ่ม {formatVatPercent(vatPercent)}% (คิดจากมัดจำ) <span className="tabular-nums text-text">฿{money(vat)}</span></span>
              <span className="text-lg font-extrabold">รวมเป็นเงินที่ต้องชำระ <span className="tabular-nums">฿{money(total)}</span></span>
              {fieldErrors.total ? (
                <p id={fieldErrorId('doc-summary-panel')} role="alert" className="m-0 text-xs font-bold text-danger">
                  {fieldErrors.total}
                </p>
              ) : null}
            </div>
          </Panel>

          {!isIssued ? (
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-border-muted bg-surface p-4">
              <p className="m-0 text-xs text-text-muted">
                กด &quot;ออกเอกสาร&quot; เพื่อออกเลขที่เอกสารอย่างเป็นทางการ — หลังจากนี้ยังแก้ไขได้ในเอกสารเดิม (เลขที่คงเดิม) และระบบจะบันทึกประวัติการแก้ไข
              </p>
              <Button variant="secondary" className="mobile:w-full" onClick={handleSave} disabled={saving}>
                บันทึกร่าง
              </Button>
            </div>
          ) : null}
          {isIssued && editingIssued ? (
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-border-muted bg-surface p-4">
              <p className="m-0 text-xs text-text-muted">
                แก้ไขเอกสารที่ออกเลขแล้ว — เลขที่เอกสารและสถานะคงเดิม ระบบจะบันทึกประวัติการแก้ไข
              </p>
              <div className="flex gap-2">
                <Button variant="secondary" onClick={() => { setEditingIssued(false); populateForm(doc, customers); }} disabled={saving}>
                  ยกเลิก
                </Button>
                <Button variant="primary" onClick={handleSave} disabled={saving}>บันทึก</Button>
              </div>
            </div>
          ) : null}
        </>
      )}

      <ConfirmDialog
        open={confirmIssue}
        title="ยืนยันการออกเอกสาร"
        message={(
          <DepositIssueSummary estimate customerName={form.customerName} depositPercent={form.depositPercent}
            subtotal={subtotal} quotation={referencedQuotation}
            deposit={deposit} vatPercent={vatPercent} vat={vat} total={total} />
        )}
        confirmLabel="ออกเอกสาร"
        busy={saving}
        onCancel={() => setConfirmIssue(false)}
        onConfirm={confirmIssueDocument}
      />
    </div>
  );
}
