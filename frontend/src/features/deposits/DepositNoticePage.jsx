import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Breadcrumbs } from '../../components/common/Breadcrumbs.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Skeleton, SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
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

export function DepositNoticePage({ ticketId, onBack, onNavigateTickets, showToast }) {
  const queryClient = useQueryClient();
  const [customerSearch, setCsSearch] = useState('');
  const [previewHtml, setPreview]   = useState('');
  const [confirmIssue, setConfirmIssue] = useState(false);
  const [downloading, setDownloading] = useState(null); // 'xlsx' | 'pdf' | null
  const iframeRef = useRef(null);

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
    // eslint-disable-next-line react-hooks/exhaustive-deps -- deliberately keyed on docSeedKey only, see comment above
  }, [docSeedKey]);

  function invalidateDepositNotices() {
    return queryClient.invalidateQueries({ queryKey: queryKeys.depositNotices(ticketId) });
  }

  const createDraftMutation = useMutation({
    mutationFn: () => {
      const defaultNotes = noteTemplates.filter((t) => t.defaultSelected).map((t) => t.text);
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
  }

  function selectCustomer(c) {
    setForm((f) => ({
      ...f,
      customerName:    c.name,
      customerTaxId:   c.taxId ?? '',
      customerAddress: [c.address, c.branch].filter(Boolean).join(' '),
    }));
    setCsSearch('');
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
      <div className="page-stack" aria-busy="true" aria-label="กำลังโหลดใบแจ้งยอดเงินรับมัดจำ">
        <header style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 4 }}>
          <Skeleton width={80} height={28} />
          <div style={{ flex: 1 }}>
            <Skeleton width="35%" height={20} />
          </div>
        </header>
        <section className="panel">
          <div className="panel-header"><Skeleton width="25%" height={16} /></div>
          <div style={{ padding: '14px 18px' }}>
            <SkeletonText lines={4} />
          </div>
        </section>
        <section className="panel">
          <div className="panel-header"><Skeleton width="25%" height={16} /></div>
          <div style={{ padding: '14px 18px' }}>
            <SkeletonText lines={3} />
          </div>
        </section>
      </div>
    );
  }

  const isIssued = doc?.status === 'ISSUED';

  return (
    <div className="page-stack">
      <Breadcrumbs
        items={[
          { label: 'ใบขอราคา', onClick: onNavigateTickets },
          { label: form.customerName || 'รายละเอียดใบขอราคา', onClick: onBack },
          { label: 'ใบแจ้งยอดเงินรับมัดจำ' },
        ]}
      />
      {/* Mobile overflow fix: this row previously had no flexWrap, so the back
          button + title + 3 action buttons (บันทึก/Preview/ออกเอกสาร) forced
          .content-scroll into horizontal scroll at 375px (verified: scrollWidth
          493 vs clientWidth 375) — burying the primary "ออกเอกสาร" action off
          the right edge with no visible scrollbar affordance. Wrapping keeps
          every action reachable without horizontal scrolling. */}
      <header style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 4, flexWrap: 'wrap' }}>
        <button type="button" className="secondary-button" onClick={onBack}>
          <Icon name="chevronLeft" size={14} /> กลับ
        </button>
        <div style={{ flex: 1, minWidth: 220 }}>
          <h1 style={{ margin: 0, fontSize: 20, fontWeight: 800 }}>
            ใบแจ้งยอดเงินรับมัดจำ
            {doc?.version > 1 && (
              <span style={{ marginLeft: 8, fontSize: 13, fontWeight: 400, color: 'var(--color-text-muted)' }}>Rev {doc.version}</span>
            )}
          </h1>
          {doc && (
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 4 }}>
              {doc.docNumber && (
                <code style={{ fontSize: 12, background: 'var(--color-surface-subtle)', padding: '2px 8px', borderRadius: 4 }}>{doc.docNumber}</code>
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
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px', marginTop: 8, fontSize: 13, color: 'var(--color-text-muted)' }}>
              {doc.customerName && (
                <span>ลูกค้า <strong style={{ color: 'var(--color-text-secondary)' }}>{doc.customerName}</strong></span>
              )}
              {isIssued ? (
                <span>ออกโดย <strong style={{ color: 'var(--color-text-secondary)' }}>{doc.issuedByName || '-'}</strong> · {formatThaiDate(doc.issueDate)}</span>
              ) : (
                doc.preparerName && <span>จัดทำโดย <strong style={{ color: 'var(--color-text-secondary)' }}>{doc.preparerName}</strong></span>
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
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <button type="button" className="secondary-button" onClick={handleSave} disabled={saving}>
              บันทึก
            </button>
            <button type="button" className="secondary-button" onClick={handlePreview} disabled={saving}>
              <Icon name="fileText" size={14} /> Preview
            </button>
            <button type="button" className="primary-button" onClick={handleIssue} disabled={saving}>
              <Icon name="check" size={14} /> ออกเอกสาร
            </button>
          </div>
        )}
        {doc && isIssued && (
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <button type="button" className="secondary-button" onClick={handleDownloadXlsx} disabled={downloading === 'xlsx'}>
              <Icon name="fileText" size={14} /> {downloading === 'xlsx' ? 'กำลังดาวน์โหลด...' : 'ดาวน์โหลด Excel'}
            </button>
            <button type="button" className="secondary-button" onClick={handleDownloadPdf} disabled={downloading === 'pdf'}>
              <Icon name="fileText" size={14} /> {downloading === 'pdf' ? 'กำลังดาวน์โหลด...' : 'ดาวน์โหลด PDF'}
            </button>
          </div>
        )}
      </header>

      {loadError && !doc ? (
        <section className="panel">
          <EmptyState icon="fileText" title="โหลดข้อมูลไม่สำเร็จ" description={loadError.message || 'โหลดไม่สำเร็จ'} />
          <div style={{ padding: '0 18px 18px', display: 'flex', justifyContent: 'center' }}>
            <button type="button" className="secondary-button" onClick={retryLoad}>
              <Icon name="refresh" size={14} /> ลองใหม่
            </button>
          </div>
        </section>
      ) : !doc ? (
        <section className="panel">
          <EmptyState
            icon="fileText"
            title="ยังไม่มีใบแจ้งยอดเงินรับมัดจำ"
            description="สร้างเอกสารฉบับร่างเพื่อเริ่มกรอกรายละเอียดใบแจ้งยอดมัดจำสำหรับใบขอราคานี้"
          />
          <div style={{ padding: '0 18px 18px', display: 'flex', justifyContent: 'center' }}>
            <button type="button" className="primary-button" onClick={handleCreateDraft} disabled={creatingDraft}>
              <Icon name="plus" size={14} />
              {creatingDraft ? 'กำลังสร้าง...' : 'สร้างเอกสารฉบับร่าง'}
            </button>
          </div>
        </section>
      ) : (
      <div style={{ display: 'grid', gridTemplateColumns: previewHtml ? '1fr 1fr' : '1fr', gap: 16 }}>
        {/* ── Left: Form ── */}
        {/* min-width: 0 fix: this flex column is a CSS-grid item (parent grid
            at previewHtml ? '1fr 1fr' : '1fr') and, like all flex/grid items,
            defaults to min-width: auto — it refuses to shrink below its
            content's min-content width. Thai has no inter-word spaces, so a
            long unbreakable label/value run inside the panels below forced
            this column (and the page) 43px past the viewport at 375px
            (verified: content-scroll scrollWidth 418 vs clientWidth 375).
            min-w-0 lets it shrink to the grid track and wrap/truncate
            normally instead. */}
        <div className="min-w-0" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>

          {/* Customer */}
          <section className="panel">
            <div className="panel-header"><h2>ข้อมูลลูกค้า</h2></div>
            <div style={{ padding: '14px 18px', display: 'flex', flexDirection: 'column', gap: 10 }}>
              <div style={{ position: 'relative' }}>
                <label htmlFor="doc-customer-search" style={{ fontSize: 12 }}>ค้นหาลูกค้า (master)</label>
                <input
                  id="doc-customer-search"
                  value={customerSearch}
                  onChange={(e) => setCsSearch(e.target.value)}
                  placeholder="ชื่อบริษัท หรือ เลขภาษี..."
                />
                {customerSearch && customers.length > 0 && (
                  <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, background: 'var(--color-surface)', border: '1px solid var(--color-border-subtle)', borderRadius: 6, zIndex: 50, boxShadow: '0 4px 12px rgba(0,0,0,0.1)', maxHeight: 180, overflowY: 'auto' }}>
                    {customers.map((c) => (
                      <button key={c.id} type="button"
                        onClick={() => selectCustomer(c)}
                        style={{ width: '100%', padding: '8px 12px', textAlign: 'left', border: 'none', background: 'none', cursor: 'pointer', fontSize: 13, borderBottom: '1px solid var(--color-surface-subtle)' }}>
                        <strong style={{ display: 'block' }}>{c.name}</strong>
                        <span style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>{c.taxId} · {c.address}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <label style={{ fontSize: 12 }}>
                ชื่อบริษัท / หน่วยงาน *
                <input value={form.customerName} disabled={isIssued}
                  onChange={(e) => setField('customerName', e.target.value)} placeholder="บริษัท..." />
              </label>
              <label style={{ fontSize: 12 }}>
                เลขประจำตัวผู้เสียภาษี
                <input value={form.customerTaxId} disabled={isIssued}
                  onChange={(e) => setField('customerTaxId', e.target.value)} placeholder="0000000000000" />
              </label>
              <label style={{ fontSize: 12 }}>
                ที่อยู่
                <textarea rows={2} value={form.customerAddress} disabled={isIssued}
                  onChange={(e) => setField('customerAddress', e.target.value)} placeholder="ที่อยู่..." style={{ resize: 'vertical' }} />
              </label>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                <label style={{ fontSize: 12 }}>
                  ชื่อโครงการ
                  <input value={form.projectName} disabled={isIssued}
                    onChange={(e) => setField('projectName', e.target.value)} placeholder="โครงการ..." />
                </label>
                <label style={{ fontSize: 12 }}>
                  อ้างอิง PO / ใบเสนอราคา
                  <input value={form.reference} disabled={isIssued}
                    onChange={(e) => setField('reference', e.target.value)} placeholder="PO-XXXX" />
                </label>
              </div>
            </div>
          </section>

          {/* Items table */}
          <section className="panel">
            <div className="panel-header"><h2>รายการสินค้า ({form.items.length} รายการ)</h2></div>
            <div style={{ padding: '0 18px 14px' }}>
              {/* Muted Floor fix: was Ink Faint (#94a3b8) on a table header label —
                  DESIGN.md specifies Ink Muted (#64748b) for `.table-head` overline text. */}
              <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,3fr) minmax(0,60px) minmax(0,80px) minmax(0,80px) minmax(0,80px)', gap: 6, padding: '8px 0', borderBottom: '1px solid var(--color-border)', fontSize: 11, fontWeight: 700, color: 'var(--color-text-muted)' }}>
                <span>รายละเอียด</span><span style={{ textAlign: 'right' }}>จำนวน</span>
                <span style={{ textAlign: 'right' }}>ราคา/หน่วย</span>
                <span style={{ textAlign: 'right' }}>ราคาสุทธิ</span>
                <span style={{ textAlign: 'right' }}>เป็นเงิน</span>
              </div>
              {form.items.map((it, idx) => (
                <div key={idx} style={{ display: 'grid', gridTemplateColumns: 'minmax(0,3fr) minmax(0,60px) minmax(0,80px) minmax(0,80px) minmax(0,80px)', gap: 6, alignItems: 'center', padding: '6px 0', borderBottom: '1px solid var(--color-surface-muted)' }}>
                  {isIssued ? (
                    <>
                      <span style={{ fontSize: 13 }}>{it.description}</span>
                      <span style={{ fontSize: 13, textAlign: 'right' }}>{it.qty}</span>
                      <span style={{ fontSize: 13, textAlign: 'right' }}>{money(it.unitPrice)}</span>
                      <span style={{ fontSize: 13, textAlign: 'right' }}>{money(it.netUnitPrice)}</span>
                      <code style={{ fontSize: 12, textAlign: 'right' }}>{money((it.netUnitPrice ?? it.unitPrice) * it.qty)}</code>
                    </>
                  ) : (
                    <>
                      <input value={it.description ?? ''} placeholder="รายละเอียดสินค้า"
                        onChange={(e) => setItemField(idx, 'description', e.target.value)}
                        style={{ fontSize: 12 }} />
                      <input type="number" value={it.qty ?? ''} placeholder="จำนวน"
                        onChange={(e) => setItemField(idx, 'qty', e.target.value)}
                        style={{ fontSize: 12, textAlign: 'right' }} />
                      <input type="number" value={it.unitPrice ?? ''} placeholder="ราคา"
                        onChange={(e) => setItemField(idx, 'unitPrice', e.target.value)}
                        style={{ fontSize: 12, textAlign: 'right' }} />
                      <input type="number" value={it.netUnitPrice ?? it.unitPrice ?? ''} placeholder="สุทธิ"
                        onChange={(e) => setItemField(idx, 'netUnitPrice', e.target.value)}
                        style={{ fontSize: 12, textAlign: 'right' }} />
                      <code style={{ fontSize: 12, textAlign: 'right', color: 'var(--color-text-secondary)' }}>
                        {money(((it.netUnitPrice ?? it.unitPrice) || 0) * (it.qty || 0))}
                      </code>
                    </>
                  )}
                </div>
              ))}
              {!isIssued && (
                <button type="button" className="secondary-button" style={{ marginTop: 8, fontSize: 12 }}
                  onClick={() => setForm((f) => ({ ...f, items: [...f.items, { seq: f.items.length + 1, description: '', qty: 1, unit: 'แผ่น', unitPrice: 0, netUnitPrice: 0 }] }))}>
                  <Icon name="plus" size={12} /> เพิ่มรายการ
                </button>
              )}
            </div>
          </section>

          {/* Notes */}
          <section className="panel">
            <div className="panel-header"><h2>หมายเหตุ</h2></div>
            <div style={{ padding: '14px 18px', display: 'flex', flexDirection: 'column', gap: 8 }}>
              {noteTemplates.map((t) => (
                <label key={t.id} style={{ display: 'flex', gap: 8, alignItems: 'flex-start', fontSize: 13, cursor: isIssued ? 'default' : 'pointer', lineHeight: 1.5 }}>
                  <input type="checkbox" disabled={isIssued}
                    checked={form.notes.includes(t.text)}
                    onChange={() => toggleNote(t.text)}
                    style={{ width: 14, height: 14, marginTop: 3, flexShrink: 0, accentColor: 'var(--color-info-dot)', cursor: isIssued ? 'default' : 'pointer' }} />
                  <span style={{ flex: 1, minWidth: 0 }}>{t.text}</span>
                </label>
              ))}
            </div>
          </section>

          {/* Summary + Deposit % */}
          <section className="panel">
            <div className="panel-header"><h2>สรุปยอด</h2></div>
            <div style={{ padding: '14px 18px' }}>
              {!isIssued && (
                <label style={{ fontSize: 12, marginBottom: 12, display: 'block' }}>
                  % มัดจำ
                  <div style={{ display: 'flex', gap: 6, marginTop: 4 }}>
                    {DEPOSIT_OPTIONS.map((opt) => (
                      <button key={opt.value} type="button"
                        onClick={() => setField('depositPercent', opt.value)}
                        style={{
                          padding: '4px 12px', borderRadius: 6, fontSize: 13, cursor: 'pointer', border: '1px solid',
                          background: form.depositPercent == opt.value ? 'var(--color-info-dot)' : 'var(--color-surface-subtle)',
                          color: form.depositPercent == opt.value ? 'var(--color-surface)' : 'var(--color-text-secondary)',
                          borderColor: form.depositPercent == opt.value ? 'var(--color-info-dot)' : 'var(--color-border-muted)',
                        }}>
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
                <div key={label} style={{ display: 'flex', justifyContent: 'space-between', padding: '5px 0', borderBottom: '1px solid var(--color-surface-subtle)', fontSize: 13 }}>
                  <span style={{ color: 'var(--color-icon-muted)' }}>{label}</span>
                  <code className="font-mono" style={{ fontWeight: bold ? 700 : 400, color: bold ? 'var(--color-text)' : 'var(--color-text-secondary)' }}>{money(value)} บาท</code>
                </div>
              ))}
              {/* Next-action helper — describes exactly what api.depositNotices.issue
                  does (src/api/mockApi.js ~L2322-2345): assigns a real docNumber,
                  flips this document to ISSUED (fields above become read-only via
                  `isIssued`), and moves the parent ticket to `document_issued`. */}
              {!isIssued && (
                <p style={{ margin: '10px 0 0', fontSize: 12, color: 'var(--color-text-muted)', lineHeight: 1.6 }}>
                  กด &quot;ออกเอกสาร&quot; เพื่อออกเลขที่เอกสารอย่างเป็นทางการ — หลังจากนี้จะแก้ไขข้อมูลในเอกสารนี้ไม่ได้อีก และใบขอราคาจะเปลี่ยนสถานะเป็น &quot;ออกใบแจ้งยอดมัดจำแล้ว&quot;
                </p>
              )}
            </div>
          </section>
        </div>

        {/* ── Right: Preview ── */}
        {previewHtml && (
          <div style={{ position: 'sticky', top: 16, height: 'calc(100vh - 120px)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
              <strong style={{ fontSize: 13 }}>Preview</strong>
              <button type="button" className="icon-button" onClick={() => setPreview('')} title="ปิดตัวอย่าง" aria-label="ปิดตัวอย่าง"><Icon name="close" size={14} /></button>
            </div>
            <iframe
              ref={iframeRef}
              srcDoc={previewHtml}
              style={{ width: '100%', height: 'calc(100% - 32px)', border: '1px solid var(--color-border-subtle)', borderRadius: 8 }}
              title="Deposit notice preview"
            />
            {previewLoading && (
              <div
                style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(255,255,255,0.8)' }}
                aria-busy="true"
                aria-label="กำลังโหลดตัวอย่างเอกสาร"
              >
                <div style={{ width: '80%', maxWidth: 360, display: 'flex', flexDirection: 'column', gap: 10 }}>
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
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <p className="confirm-dialog-message" style={{ margin: 0 }}>
              ตรวจสอบยอดเงินก่อนออกเอกสารให้ <strong>{form.customerName || 'ลูกค้า'}</strong> — <strong>หลังจากนี้จะไม่สามารถแก้ไขได้</strong>
            </p>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, borderTop: '1px solid var(--color-border)', paddingTop: 8 }}>
              <span style={{ color: 'var(--color-icon-muted)' }}>ขอรับเงินมัดจำ ({Math.round(Number(form.depositPercent) * 100)}%)</span>
              <code className="font-mono">{money(deposit)} บาท</code>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13 }}>
              <span style={{ color: 'var(--color-icon-muted)' }}>ภาษีมูลค่าเพิ่ม 7%</span>
              <code className="font-mono">{money(vat)} บาท</code>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 14, fontWeight: 700 }}>
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
