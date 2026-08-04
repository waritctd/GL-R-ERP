import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Breadcrumbs } from '../../components/common/Breadcrumbs.jsx';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Skeleton, SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { fieldErrorId } from '../../components/common/FormField.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { downloadBlob } from '../../utils/download.js';

const DEPOSIT_OPTIONS = [
  { value: 0.3,  label: '30%' },
  { value: 0.5,  label: '50%' },
  { value: 0.7,  label: '70%' },
  { value: 1.0,  label: '100%' },
];

function money(v) {
  if (v == null) return '—';
  return Number(v).toLocaleString('th-TH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

// ── validation (UX-03) ──────────────────────────────────────────────────────
// This gate fires ONLY from handleIssue() — never from handleSave() or the
// preview path. This is a draft editor: users save partial drafts
// incrementally, and blocking that would break a legitimate workflow.
// Issuing is different — it is irreversible (fields become read-only) and it
// advances the ticket's payment track, so it is the one action worth
// validating before the confirm dialog even opens. Every field below is
// hand-wired (not <FormField>): each candidate control already lives inside
// a pre-existing `<label className="...">` or a CSS-grid table row whose exact
// markup this task must not restyle (UX-18 is a separate, out-of-scope
// finding) — nesting <FormField>'s own <label> inside those would either
// produce an invalid nested <label> or silently change spacing/typography.
const customerNameSchema = z.string().trim().min(1, 'กรุณากรอกชื่อบริษัท / หน่วยงาน');

function makeLineItemSchema(rowNumber) {
  return z.object({
    description: z.string(),
    qty: z.union([z.string(), z.number()]).nullable().optional(),
  }).superRefine((item, ctx) => {
    if (!String(item.description ?? '').trim()) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['description'], message: `กรุณากรอกรายละเอียดสินค้าในรายการที่ ${rowNumber}` });
    }
    if (!item.qty || Number(item.qty) <= 0) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['qty'], message: `กรุณากรอกจำนวนมากกว่า 0 ในรายการที่ ${rowNumber}` });
    }
  });
}

/**
 * Validates the deposit notice at ISSUE time only. Returns every invalid
 * field plus `order` — those same keys in on-screen top-to-bottom order — so
 * handleIssue() can scroll/focus the first one without depending on zod's
 * internal issue ordering.
 */
function validateIssueForm({ customerName, items, total }) {
  const errors = {};
  const order = [];

  const nameResult = customerNameSchema.safeParse(customerName);
  if (!nameResult.success) {
    errors.customerName = nameResult.error.issues[0].message;
    order.push('customerName');
  }

  if (items.length === 0) {
    errors.items = 'กรุณาเพิ่มรายการสินค้าอย่างน้อย 1 รายการ';
    order.push('items');
  } else {
    items.forEach((item, index) => {
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

  // Rule 4: issuing a ฿0 deposit notice is meaningless and would still
  // advance the ticket's payment track. Only checked once the rules above
  // are otherwise satisfied — a ฿0 total caused by missing item data is
  // already covered by those, and flagging it again here would just be a
  // second message for the same root cause.
  if (order.length === 0 && !(Number(total) > 0)) {
    errors.total = 'ยอดเงินรวมต้องมากกว่า 0 บาท — กรุณาตรวจสอบราคาต่อหน่วยและจำนวนในรายการสินค้า';
    order.push('total');
  }

  return { errors, order };
}

export function DepositNoticePage({ ticketId, onBack, onNavigateTickets, showToast }) {
  const queryClient = useQueryClient();
  const [customerSearch, setCsSearch] = useState('');
  const [previewHtml, setPreview]   = useState('');
  const [confirmIssue, setConfirmIssue] = useState(false);
  const [downloading, setDownloading] = useState(null); // 'xlsx' | 'pdf' | null
  const iframeRef = useRef(null);

  // UX-03: per-field issue-validation errors, keyed 'customerName' | 'items'
  // (at-least-one-line-item) | `items.<index>.<field>` | 'total'. Populated
  // only by handleIssue() below — see the SCOPING DECISION comment on
  // validateIssueForm(). fieldRefs holds the DOM node for each key so a
  // failed issue attempt can scroll/focus the first invalid field.
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
  });

  // --- Reads (TanStack Query) ---
  // Mount only LOADS existing documents — it must never create one as a side
  // effect of navigating here. The previous version unconditionally called
  // createDraft() whenever no DRAFT was found, which (a) fired twice under React
  // StrictMode's double-invoke, (b) minted a stray draft every time a user opened
  // this page and then navigated away without touching it, and (c) minted ANOTHER
  // new draft (version+1) every time this page was revisited after the document
  // had already been issued, since an ISSUED doc is never a DRAFT. Now: load the
  // DRAFT if one exists, else show the most recent ISSUED doc read-only, else
  // render an explicit empty state — creation only happens on the user's click
  // (see handleCreateDraft). None of the queries below ever create anything.
  const noteTemplatesQuery = useQuery({
    queryKey: queryKeys.depositNoteTemplates(),
    queryFn: () => api.depositNotices.noteTemplates().then((response) => response.templates ?? []),
  });
  const noteTemplates = noteTemplatesQuery.data ?? [];

  // Keyed by the live search text so every keystroke gets its own cache entry
  // (matches the old onChange's per-keystroke api.customers.search(text) call);
  // customerSearch === '' is the initial "whole master list" load loadDocs used
  // to do up front.
  const customersQuery = useQuery({
    queryKey: queryKeys.customersSearch(customerSearch),
    queryFn: () => api.customers.search(customerSearch).then((response) => response.customers ?? []),
  });
  const customers = customersQuery.data ?? [];

  const depositNoticesQuery = useQuery({
    queryKey: queryKeys.depositNotices(ticketId),
    queryFn: () => api.depositNotices.listByTicket(ticketId).then((response) => response.depositNotices ?? []),
  });
  const doc = useMemo(() => {
    const docs = depositNoticesQuery.data ?? [];
    const draft = docs.find((d) => d.status === 'DRAFT');
    const latestIssued = docs
      .filter((d) => d.status === 'ISSUED')
      .sort((a, b) => (b.version ?? 0) - (a.version ?? 0))[0];
    return draft ?? latestIssued ?? null;
  }, [depositNoticesQuery.data]);

  // The full-page skeleton only gates on the two loads a first paint actually
  // needs (doc list + note templates). customersQuery is deliberately excluded:
  // it's keyed by customerSearch, so every keystroke in the customer-search box
  // mints a "new" query key with isLoading briefly true for it — including it
  // here would flash the *entire page* back to a skeleton on every keystroke,
  // which the original imperative version never did (its onChange fetch had no
  // loading indicator at all).
  const loading = depositNoticesQuery.isLoading || noteTemplatesQuery.isLoading;

  // Same reasoning as `loading`: only the doc-list/template load blocks the page
  // with an error+retry state, matching the original loadDocs try/catch scope.
  // A failed customer search (mid-session, per keystroke) stays silent, as it
  // did before (the old onChange handler had its own `.catch(() => {})`).
  const loadError = depositNoticesQuery.error || noteTemplatesQuery.error;

  useEffect(() => {
    if (loadError) showToast('error', loadError.message || 'โหลดไม่สำเร็จ');
    // eslint-disable-next-line react-hooks/exhaustive-deps -- fire once per new error identity, not on every showToast identity change
  }, [loadError]);

  function retryLoad() {
    depositNoticesQuery.refetch();
    noteTemplatesQuery.refetch();
    customersQuery.refetch();
  }

  function populateForm(d, customerList = []) {
    // If taxId/address already saved in draft, use them directly
    // Otherwise try to match customerName against master
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
    setForm({
      customerName:    d.customerName   ?? '',
      customerTaxId:   taxId,
      customerAddress: address,
      projectName:     d.projectName   ?? '',
      reference:       d.reference     ?? '',
      depositPercent:  d.depositPercent ?? 0.5,
      notes:           d.notes         ?? [],
      items:           (d.items ?? []).map((it) => ({ ...it })),
    });
  }

  // Re-seed the editable `form` whenever the doc's identity/version/status
  // actually changes (new draft created, or DRAFT -> ISSUED) — not on every
  // background refetch of the same doc (e.g. a save doesn't bump
  // version/status, so no reseed fires and in-flight edits aren't clobbered).
  const docSeedKey = doc ? `${doc.id}:${doc.version}:${doc.status}` : null;
  useEffect(() => {
    if (doc) populateForm(doc, customers);
    // A reseed means a different document identity (or the same one flipping
    // to ISSUED) — any leftover UX-03 issue-validation errors belong to the
    // previous edit session and would otherwise linger on screen.
    setFieldErrors({});
    // eslint-disable-next-line react-hooks/exhaustive-deps -- deliberately keyed on docSeedKey only, see comment above
  }, [docSeedKey]);

  function invalidateDepositNotices() {
    return queryClient.invalidateQueries({ queryKey: queryKeys.depositNotices(ticketId) });
  }

  const createDraftMutation = useMutation({
    // Prefer the pricing-request-chain endpoint (createDepositNoticeFromQuotation) when this
    // deal has a pricing request whose customer quotation was accepted — that route sources
    // items from the accepted quotation and (per DepositNoticeService.createDraft, which it
    // delegates to) autofills the header from the customer master + ticket project. Resolving
    // "does this deal have an accepted quotation" only needs listForTicket's own summary shape
    // (status === QUOTATION_ACCEPTED is set exactly when the customer accepts — see
    // OrderConfirmationService's own Javadoc), so no extra round trip to the quotation list is
    // needed. A failure ANYWHERE in this resolution step (network error, permission edge case,
    // no pricing requests at all) degrades to today's ticket-level route — it must never break
    // the button.
    mutationFn: async () => {
      const defaultNotes = noteTemplates.filter((t) => t.defaultSelected).map((t) => t.text);
      let acceptedPricingRequestId = null;
      try {
        const { items: pricingRequests } = await api.pricingRequests.listForTicket(ticketId);
        acceptedPricingRequestId = (pricingRequests ?? [])
          .find((pr) => pr.status === 'QUOTATION_ACCEPTED')?.id ?? null;
      } catch {
        acceptedPricingRequestId = null;
      }
      if (acceptedPricingRequestId != null) {
        return api.pricingRequests.createDepositNoticeFromQuotation(acceptedPricingRequestId, {
          depositPercent: 0.5,
        });
      }
      return api.depositNotices.createDraft(ticketId, {
        notes: defaultNotes,
        depositPercent: 0.5,
      });
    },
    onSuccess: () => invalidateDepositNotices(),
    onError: (err) => showToast('error', err.message || 'สร้างเอกสารฉบับร่างไม่สำเร็จ'),
  });

  function handleCreateDraft() {
    createDraftMutation.mutate();
  }

  const saveMutation = useMutation({
    mutationFn: () => api.depositNotices.update(doc.id, buildPayload()),
    onSuccess: () => {
      showToast('success', 'บันทึกแล้ว');
      invalidateDepositNotices();
    },
    onError: (err) => showToast('error', err.message || 'บันทึกไม่สำเร็จ'),
  });

  function handleSave() {
    if (!doc) return;
    saveMutation.mutate();
  }

  const previewMutation = useMutation({
    mutationFn: async () => {
      // Save first, then preview.
      await api.depositNotices.update(doc.id, buildPayload());
      return api.depositNotices.preview(doc.id);
    },
    onSuccess: (html) => {
      setPreview(html);
      invalidateDepositNotices();
    },
    onError: (err) => showToast('error', err.message || 'Preview ไม่สำเร็จ'),
  });

  function handlePreview() {
    if (!doc) return;
    previewMutation.mutate();
  }

  function handleIssue() {
    if (!doc) return;
    // UX-03: validate before opening the confirm dialog — don't ask "are you
    // sure?" for something that's then going to fail. `total` is read here
    // (never written) from the money-math block below; see the HARD
    // CONSTRAINT comment on that block.
    const { errors, order } = validateIssueForm({ customerName: form.customerName, items: form.items, total });
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
      await api.depositNotices.update(doc.id, buildPayload());
      return api.depositNotices.issue(doc.id);
    },
    onSuccess: (res) => {
      showToast('success', `ออกเอกสาร ${res.depositNotice.docNumber} เรียบร้อย`);
      setConfirmIssue(false);
      invalidateDepositNotices();
      // Issuing the deposit notice is the payment-track step that advances the
      // ticket (see mockApi.depositNotices.issue) — refresh the ticket list +
      // detail so those views don't show stale paymentStatus.
      queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    },
    onError: (err) => showToast('error', err.message || 'ออกเอกสารไม่สำเร็จ'),
  });

  function confirmIssueDocument() {
    issueMutation.mutate();
  }

  const creatingDraft = createDraftMutation.isPending;
  const saving = saveMutation.isPending || previewMutation.isPending || issueMutation.isPending;
  const previewLoading = previewMutation.isPending;

  function setField(key, value) {
    setForm((f) => ({ ...f, [key]: value }));
    if (key === 'customerName') clearFieldError('customerName');
    // The deposit % toggle feeds directly into `deposit`/`total` below —
    // clear a stale "total must be > 0" error so it can be re-checked fresh
    // on the next issue attempt.
    if (key === 'depositPercent') clearFieldError('total');
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
        return updated;
      }),
    }));
    if (key === 'description') clearFieldError(`items.${idx}.description`);
    if (key === 'qty') clearFieldError(`items.${idx}.qty`);
    // Any of these four feed into `total` below — clear a stale "total must
    // be > 0" error so it's re-checked fresh on the next issue attempt.
    if (['qty', 'unitPrice', 'netUnitPrice', 'discountLabel'].includes(key)) clearFieldError('total');
  }

  async function handleDownloadXlsx() {
    await download(() => api.depositNotices.downloadXlsx(doc.id), 'xlsx');
  }

  async function handleDownloadPdf() {
    await download(() => api.depositNotices.downloadPdf(doc.id), 'pdf');
  }

  async function download(fetchBlob, extension) {
    if (!doc) return;
    setDownloading(extension);
    try {
      const blob = await fetchBlob();
      downloadBlob(blob, doc.docNumber ?? 'draft', extension);
    } catch (err) {
      showToast('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloading((current) => (current === extension ? null : current));
    }
  }

  function buildPayload() {
    return {
      customerName:    form.customerName,
      customerTaxId:   form.customerTaxId,
      customerAddress: form.customerAddress,
      projectName:     form.projectName,
      reference:       form.reference,
      depositPercent:  Number(form.depositPercent),
      notes:           form.notes,
      items: form.items.map((it, idx) => ({
        seq:          idx + 1,
        description:  it.description ?? '',
        qty:          Number(it.qty) || 0,
        unit:         it.unit ?? 'แผ่น',
        unitPrice:    Number(it.unitPrice) || 0,
        discountLabel: it.discountLabel ?? null,
        netUnitPrice: Number(it.netUnitPrice) || Number(it.unitPrice) || 0,
      })),
    };
  }

  // Recalculate summary from form items
  const subtotal = form.items.reduce((sum, it) => {
    const net = Number(it.netUnitPrice) || Number(it.unitPrice) || 0;
    return sum + net * (Number(it.qty) || 0);
  }, 0);
  const deposit = subtotal * Number(form.depositPercent || 0.5);
  const vat = deposit * 0.07;
  const total = deposit + vat;

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

  return (
    <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
      <Breadcrumbs
        items={[
          { label: 'คำขอราคา', onClick: onNavigateTickets },
          { label: form.customerName || 'รายละเอียดคำขอราคา', onClick: onBack },
          { label: 'ใบแจ้งยอดเงินรับมัดจำ' },
        ]}
      />
      {/* Mobile overflow fix: this row previously had no flexWrap, so the back
          button + title + 3 action buttons (บันทึก/Preview/ออกเอกสาร) forced
          .content-scroll into horizontal scroll at 375px (verified: scrollWidth
          493 vs clientWidth 375) — burying the primary "ออกเอกสาร" action off
          the right edge with no visible scrollbar affordance. Wrapping keeps
          every action reachable without horizontal scrolling. */}
      <header className="flex flex-wrap items-start gap-3 mb-1">
        <Button variant="secondary" onClick={onBack}>
          <Icon name="chevronLeft" size={14} /> กลับ
        </Button>
        <div className="min-w-[220px] flex-1">
          <h1 className="m-0 text-[20px] font-extrabold">
            ใบแจ้งยอดเงินรับมัดจำ
            {doc?.version > 1 && (
              <span className="ml-2 text-sm font-normal text-text-muted">ครั้งที่ {doc.version}</span>
            )}
          </h1>
          {doc && (
            <div className="mt-1 flex items-center gap-2">
              {doc.docNumber && (
                <code className="rounded bg-surface-subtle px-2 py-0.5 text-xs">{doc.docNumber}</code>
              )}
              <StatusBadge tone={isIssued ? 'success' : 'neutral'}>
                {isIssued ? 'ออกแล้ว' : 'Draft'}
              </StatusBadge>
            </div>
          )}
          {/* Summary line: who this document is for and who prepared/issued it.
              Every field below already comes back on `doc` from
              api.depositNotices.get/createDraft/issue (src/api/mockApi.js
              ~L2266-2345) — no new API calls, nothing invented. Pieces are
              omitted individually when the underlying field is empty. */}
          {doc && (
            <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-sm text-text-muted">
              {doc.customerName && (
                <span>ลูกค้า <strong className="text-text-secondary">{doc.customerName}</strong></span>
              )}
              {isIssued ? (
                <span>ออกโดย <strong className="text-text-secondary">{doc.issuedByName || '-'}</strong> · {formatThaiDate(doc.issueDate)}</span>
              ) : (
                doc.preparerName && <span>จัดทำโดย <strong className="text-text-secondary">{doc.preparerName}</strong></span>
              )}
            </div>
          )}
        </div>
        {/* Save/Preview/ออกเอกสาร only render once a document actually exists —
            previously these rendered whenever `!isIssued` (true for `doc === null`
            too), so a failed load or a not-yet-created draft still offered three
            live-looking buttons whose handlers silently no-op'd on `if (!doc) return;`.
            No doc now means no action buttons, not dead ones. */}
        {doc && !isIssued && (
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" onClick={handleSave} disabled={saving}>
              บันทึก
            </Button>
            <Button variant="secondary" onClick={handlePreview} disabled={saving}>
              <Icon name="fileText" size={14} /> Preview
            </Button>
            <Button variant="primary" onClick={handleIssue} disabled={saving}>
              <Icon name="check" size={14} /> ออกเอกสาร
            </Button>
          </div>
        )}
        {doc && isIssued && (
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" onClick={handleDownloadXlsx} disabled={downloading === 'xlsx'}>
              <Icon name="fileText" size={14} /> {downloading === 'xlsx' ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลด Excel'}
            </Button>
            <Button variant="secondary" onClick={handleDownloadPdf} disabled={downloading === 'pdf'}>
              <Icon name="fileText" size={14} /> {downloading === 'pdf' ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลด PDF'}
            </Button>
          </div>
        )}
      </header>

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
            description="สร้างเอกสารฉบับร่างเพื่อเริ่มกรอกรายละเอียดใบแจ้งยอดมัดจำสำหรับคำขอราคานี้"
          />
          <div className="flex justify-center px-4.5 pb-4.5">
            <Button variant="primary" onClick={handleCreateDraft} disabled={creatingDraft}>
              <Icon name="plus" size={14} />
              {creatingDraft ? 'กำลังสร้าง…' : 'สร้างเอกสารฉบับร่าง'}
            </Button>
          </div>
        </Panel>
      ) : (
      <div className={previewHtml ? 'grid grid-cols-2 gap-4' : 'grid grid-cols-1 gap-4'}>
        {/* ── Left: Form ── */}
        {/* min-width: 0 fix: this flex column is a CSS-grid item (parent grid
            at previewHtml ? grid-cols-2 : grid-cols-1) and, like all flex/grid items,
            defaults to min-width: auto — it refuses to shrink below its
            content's min-content width. Thai has no inter-word spaces, so a
            long unbreakable label/value run inside the panels below forced
            this column (and the page) 43px past the viewport at 375px
            (verified: content-scroll scrollWidth 418 vs clientWidth 375).
            min-w-0 lets it shrink to the grid track and wrap/truncate
            normally instead. */}
        <div className="flex min-w-0 flex-col gap-3.5">

          {/* Customer */}
          <Panel title="ข้อมูลลูกค้า">
            <div className="flex flex-col gap-2.5 px-4.5 py-3.5">
              <div className="relative">
                <label htmlFor="doc-customer-search" className="text-xs">ค้นหาลูกค้า (master)</label>
                <input
                  id="doc-customer-search"
                  value={customerSearch}
                  onChange={(e) => setCsSearch(e.target.value)}
                  placeholder="ชื่อบริษัท หรือ เลขภาษี…"
                />
                {customerSearch && customers.length > 0 && (
                  <div className="absolute left-0 right-0 top-full z-50 max-h-[180px] overflow-y-auto rounded-[6px] border border-border-subtle bg-surface shadow-[0_4px_12px_rgba(0,0,0,0.1)]">
                    {customers.map((c) => (
                      <button key={c.id} type="button"
                        onClick={() => selectCustomer(c)}
                        className="w-full cursor-pointer border-0 border-b border-surface-subtle bg-transparent px-3 py-2 text-left text-sm">
                        <strong className="block">{c.name}</strong>
                        <span className="text-2xs text-text-muted">{c.taxId} · {c.address}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <label className="text-xs">
                ชื่อบริษัท / หน่วยงาน *
                <input
                  id="doc-customer-name"
                  ref={(el) => { fieldRefs.current.customerName = el; }}
                  value={form.customerName} disabled={isIssued}
                  onChange={(e) => setField('customerName', e.target.value)} placeholder="บริษัท…"
                  aria-required="true"
                  aria-invalid={fieldErrors.customerName ? true : undefined}
                  aria-describedby={fieldErrors.customerName ? fieldErrorId('doc-customer-name') : undefined}
                />
              </label>
              {fieldErrors.customerName ? (
                <p id={fieldErrorId('doc-customer-name')} role="alert" className="m-0 text-2xs font-bold text-danger">
                  {fieldErrors.customerName}
                </p>
              ) : null}
              <label className="text-xs">
                เลขประจำตัวผู้เสียภาษี
                <input value={form.customerTaxId} disabled={isIssued}
                  onChange={(e) => setField('customerTaxId', e.target.value)} placeholder="0000000000000" />
              </label>
              <label className="text-xs">
                ที่อยู่
                <textarea rows={2} value={form.customerAddress} disabled={isIssued}
                  onChange={(e) => setField('customerAddress', e.target.value)} placeholder="ที่อยู่…" className="resize-y" />
              </label>
              <div className="grid grid-cols-2 gap-2.5">
                <label className="text-xs">
                  ชื่อโครงการ
                  <input value={form.projectName} disabled={isIssued}
                    onChange={(e) => setField('projectName', e.target.value)} placeholder="โครงการ…" />
                </label>
                <label className="text-xs">
                  อ้างอิง PO / ใบเสนอราคา
                  <input value={form.reference} disabled={isIssued}
                    onChange={(e) => setField('reference', e.target.value)} placeholder="PO-XXXX" />
                </label>
              </div>
            </div>
          </Panel>

          {/* Items table */}
          <Panel title={`รายการสินค้า (${form.items.length} รายการ)`}>
            {/*
              UX-03: this whole panel body is the scroll/focus target for the
              "at least 1 line item" rule — there's no single input to point
              at when the list is empty, so (like TicketCreateModal's project
              selector) the wrapper itself carries aria-invalid/
              aria-describedby and a tabIndex so it's focusable.
            */}
            <div
              id="doc-items-panel"
              ref={(el) => { fieldRefs.current.items = el; }}
              tabIndex={-1}
              aria-invalid={fieldErrors.items ? true : undefined}
              aria-describedby={fieldErrors.items ? fieldErrorId('doc-items-panel') : undefined}
              className="px-4.5 pb-3.5"
            >
              {/* Muted Floor fix: was Ink Faint (#94a3b8) on a table header label —
                  DESIGN.md specifies Ink Muted (#5c6b80) for `.table-head` overline text. */}
              <div className="grid grid-cols-[minmax(0,3fr)_minmax(0,60px)_minmax(0,80px)_minmax(0,80px)_minmax(0,80px)] gap-1.5 border-b border-border py-2 text-2xs font-bold text-text-muted">
                <span>รายละเอียด</span><span className="text-right">จำนวน</span>
                <span className="text-right">ราคา/หน่วย</span>
                <span className="text-right">ราคาสุทธิ</span>
                <span className="text-right">เป็นเงิน</span>
              </div>
              {form.items.map((it, idx) => (
                <div key={idx} className="grid grid-cols-[minmax(0,3fr)_minmax(0,60px)_minmax(0,80px)_minmax(0,80px)_minmax(0,80px)] items-center gap-1.5 border-b border-surface-muted py-1.5">
                  {isIssued ? (
                    <>
                      <span className="text-sm">{it.description}</span>
                      <span className="text-right text-sm">{it.qty}</span>
                      <span className="text-right text-sm">{money(it.unitPrice)}</span>
                      <span className="text-right text-sm">{money(it.netUnitPrice)}</span>
                      <code className="text-right text-xs">{money((it.netUnitPrice ?? it.unitPrice) * it.qty)}</code>
                    </>
                  ) : (
                    <>
                      <div>
                        <input
                          id={`doc-item-${idx}-description`}
                          ref={(el) => { fieldRefs.current[`items.${idx}.description`] = el; }}
                          value={it.description ?? ''} placeholder="รายละเอียดสินค้า"
                          onChange={(e) => setItemField(idx, 'description', e.target.value)}
                          aria-invalid={fieldErrors[`items.${idx}.description`] ? true : undefined}
                          aria-describedby={fieldErrors[`items.${idx}.description`] ? fieldErrorId(`doc-item-${idx}-description`) : undefined}
                          className="w-full box-border text-xs" />
                        {fieldErrors[`items.${idx}.description`] ? (
                          <p id={fieldErrorId(`doc-item-${idx}-description`)} role="alert" className="mx-0 mb-0 mt-0.5 text-[10px] font-bold text-danger">
                            {fieldErrors[`items.${idx}.description`]}
                          </p>
                        ) : null}
                      </div>
                      <div>
                        <input type="number" value={it.qty ?? ''} placeholder="จำนวน"
                          id={`doc-item-${idx}-qty`}
                          ref={(el) => { fieldRefs.current[`items.${idx}.qty`] = el; }}
                          onChange={(e) => setItemField(idx, 'qty', e.target.value)}
                          aria-invalid={fieldErrors[`items.${idx}.qty`] ? true : undefined}
                          aria-describedby={fieldErrors[`items.${idx}.qty`] ? fieldErrorId(`doc-item-${idx}-qty`) : undefined}
                          className="w-full box-border text-right text-xs" />
                        {fieldErrors[`items.${idx}.qty`] ? (
                          <p id={fieldErrorId(`doc-item-${idx}-qty`)} role="alert" className="mx-0 mb-0 mt-0.5 text-[10px] font-bold text-danger">
                            {fieldErrors[`items.${idx}.qty`]}
                          </p>
                        ) : null}
                      </div>
                      <input type="number" value={it.unitPrice ?? ''} placeholder="ราคา"
                        onChange={(e) => setItemField(idx, 'unitPrice', e.target.value)}
                        className="text-right text-xs" />
                      <input type="number" value={it.netUnitPrice ?? it.unitPrice ?? ''} placeholder="สุทธิ"
                        onChange={(e) => setItemField(idx, 'netUnitPrice', e.target.value)}
                        className="text-right text-xs" />
                      <code className="text-right text-xs text-text-secondary">
                        {money(((it.netUnitPrice ?? it.unitPrice) || 0) * (it.qty || 0))}
                      </code>
                    </>
                  )}
                </div>
              ))}
              {!isIssued && (
                <Button variant="secondary" className="mt-2 text-xs"
                  onClick={() => {
                    setForm((f) => ({ ...f, items: [...f.items, { seq: f.items.length + 1, description: '', qty: 1, unit: 'แผ่น', unitPrice: 0, netUnitPrice: 0 }] }));
                    clearFieldError('items');
                  }}>
                  <Icon name="plus" size={12} /> เพิ่มรายการ
                </Button>
              )}
              {fieldErrors.items ? (
                <p id={fieldErrorId('doc-items-panel')} role="alert" className="mx-0 mb-0 mt-2 text-xs font-bold text-danger">
                  {fieldErrors.items}
                </p>
              ) : null}
            </div>
          </Panel>

          {/* Notes */}
          <Panel title="หมายเหตุ">
            <div className="flex flex-col gap-2 px-4.5 py-3.5">
              {noteTemplates.map((t) => (
                <label key={t.id} className={`flex items-start gap-2 text-sm leading-normal ${isIssued ? 'cursor-default' : 'cursor-pointer'}`}>
                  <input type="checkbox" disabled={isIssued}
                    checked={form.notes.includes(t.text)}
                    onChange={() => toggleNote(t.text)}
                    className={`mt-[3px] h-3.5 w-3.5 shrink-0 accent-info-dot ${isIssued ? 'cursor-default' : 'cursor-pointer'}`} />
                  <span className="min-w-0 flex-1">{t.text}</span>
                </label>
              ))}
            </div>
          </Panel>

          {/* Summary + Deposit % */}
          <Panel title="สรุปยอด">
            {/*
              UX-03: scroll/focus target for the "total must be > 0" rule —
              total is a computed value with no single input of its own, so
              (same technique as doc-items-panel above) this wrapper carries
              the aria-invalid/aria-describedby contract itself.
            */}
            <div
              id="doc-summary-panel"
              ref={(el) => { fieldRefs.current.total = el; }}
              tabIndex={-1}
              aria-invalid={fieldErrors.total ? true : undefined}
              aria-describedby={fieldErrors.total ? fieldErrorId('doc-summary-panel') : undefined}
              className="px-4.5 py-3.5"
            >
              {!isIssued && (
                <label className="mb-3 block text-xs">
                  % มัดจำ
                  <div className="mt-1 flex gap-1.5">
                    {DEPOSIT_OPTIONS.map((opt) => (
                      <button key={opt.value} type="button"
                        onClick={() => setField('depositPercent', opt.value)}
                        className={`cursor-pointer rounded-[6px] border px-3 py-1 text-sm ${
                          form.depositPercent == opt.value
                            ? 'border-info-dot bg-info-dot text-surface'
                            : 'border-border-muted bg-surface-subtle text-text-secondary'
                        }`}>
                        {opt.label}
                      </button>
                    ))}
                  </div>
                </label>
              )}
              {[
                { label: 'รวมเป็นเงิน', value: subtotal },
                { label: `ขอรับเงินมัดจำ (${Math.round(Number(form.depositPercent) * 100)}%)`, value: deposit },
                { label: 'ภาษีมูลค่าเพิ่ม 7% (คิดจากมัดจำ)', value: vat },
                { label: 'รวมเป็นเงินที่ต้องชำระ', value: total, bold: true },
              ].map(({ label, value, bold }) => (
                <div key={label} className="flex justify-between border-b border-surface-subtle py-[5px] text-sm">
                  <span className="text-icon-muted">{label}</span>
                  <code className={`font-mono ${bold ? 'font-bold text-text' : 'font-normal text-text-secondary'}`}>{money(value)} บาท</code>
                </div>
              ))}
              {fieldErrors.total ? (
                <p id={fieldErrorId('doc-summary-panel')} role="alert" className="mx-0 mb-0 mt-2 text-xs font-bold text-danger">
                  {fieldErrors.total}
                </p>
              ) : null}
              {/* Next-action helper — describes exactly what api.depositNotices.issue
                  does (src/api/mockApi.js ~L2322-2345): assigns a real docNumber,
                  flips this document to ISSUED (fields above become read-only via
                  `isIssued`), and moves the parent ticket to `document_issued`. */}
              {!isIssued && (
                <p className="mx-0 mb-0 mt-2.5 text-xs leading-[1.6] text-text-muted">
                  กด &quot;ออกเอกสาร&quot; เพื่อออกเลขที่เอกสารอย่างเป็นทางการ — หลังจากนี้จะแก้ไขข้อมูลในเอกสารนี้ไม่ได้อีก และคำขอราคาจะเปลี่ยนสถานะเป็น &quot;ออกใบแจ้งยอดมัดจำแล้ว&quot;
                </p>
              )}
            </div>
          </Panel>
        </div>

        {/* ── Right: Preview ── */}
        {previewHtml && (
          <div className="sticky top-4 h-[calc(100vh_-_120px)]">
            <div className="mb-2 flex items-center justify-between">
              <strong className="text-sm">Preview</strong>
              <Button variant="icon" onClick={() => setPreview('')} title="ปิดตัวอย่าง" aria-label="ปิดตัวอย่าง"><Icon name="close" size={14} /></Button>
            </div>
            <iframe
              ref={iframeRef}
              srcDoc={previewHtml}
              className="h-[calc(100%_-_32px)] w-full rounded-md border border-border-subtle"
              title="Deposit notice preview"
            />
            {previewLoading && (
              <div
                className="absolute inset-0 flex items-center justify-center bg-white/80"
                aria-busy="true"
                aria-label="กำลังโหลดตัวอย่างเอกสาร"
              >
                <div className="flex w-[80%] max-w-[360px] flex-col gap-2.5">
                  <Skeleton width="60%" height={18} />
                  <SkeletonText lines={6} />
                </div>
              </div>
            )}
          </div>
        )}
      </div>
      )}

      <ConfirmDialog
        open={confirmIssue}
        title="ยืนยันการออกเอกสาร"
        message={(
          <div className="flex flex-col gap-2">
            <p className="confirm-dialog-message m-0">
              ตรวจสอบยอดเงินก่อนออกเอกสารให้ <strong>{form.customerName || 'ลูกค้า'}</strong> — <strong>หลังจากนี้จะไม่สามารถแก้ไขได้</strong>
            </p>
            <div className="flex justify-between border-t border-border pt-2 text-sm">
              <span className="text-icon-muted">ขอรับเงินมัดจำ ({Math.round(Number(form.depositPercent) * 100)}%)</span>
              <code className="font-mono">{money(deposit)} บาท</code>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-icon-muted">ภาษีมูลค่าเพิ่ม 7%</span>
              <code className="font-mono">{money(vat)} บาท</code>
            </div>
            <div className="flex justify-between text-md font-bold">
              <span>รวมเป็นเงินที่ต้องชำระ</span>
              <code className="font-mono">{money(total)} บาท</code>
            </div>
          </div>
        )}
        confirmLabel="ออกเอกสาร"
        busy={saving}
        onCancel={() => setConfirmIssue(false)}
        onConfirm={confirmIssueDocument}
      />
    </div>
  );
}
