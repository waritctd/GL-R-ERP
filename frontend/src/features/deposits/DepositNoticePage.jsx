import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Breadcrumbs } from '../../components/common/Breadcrumbs.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Skeleton, SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';

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
  const [doc, setDoc]               = useState(null);
  const [noteTemplates, setTemplates] = useState([]);
  const [customers, setCustomers]   = useState([]);
  const [customerSearch, setCsSearch] = useState('');
  const [loading, setLoading]       = useState(true);
  const [saving, setSaving]         = useState(false);
  const [previewHtml, setPreview]   = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);
  const [confirmIssue, setConfirmIssue] = useState(false);
  const iframeRef = useRef(null);

  // form state
  const [form, setForm] = useState({
    customerName: '', customerTaxId: '', customerAddress: '',
    projectName: '', reference: '',
    depositPercent: 0.5,
    notes: [],
    items: [],
  });

  useEffect(() => {
    async function init() {
      setLoading(true);
      try {
        const [templatesRes, customersRes] = await Promise.all([
          api.depositNotices.noteTemplates(),
          api.customers.search(''),
        ]);
        setTemplates(templatesRes.templates ?? []);
        setCustomers(customersRes.customers ?? []);

        const docsRes = await api.depositNotices.listByTicket(ticketId);
        const draft = (docsRes.depositNotices ?? []).find((d) => d.status === 'DRAFT');

        let docData;
        if (draft) {
          docData = draft;
        } else {
          const defaultNotes = (templatesRes.templates ?? [])
            .filter((t) => t.defaultSelected)
            .map((t) => t.text);
          const created = await api.depositNotices.createDraft(ticketId, {
            notes: defaultNotes,
            depositPercent: 0.5,
          });
          docData = created.depositNotice;
        }

        setDoc(docData);
        // Try to match customerName against master to auto-fill taxId + address
        populateForm(docData, customersRes.customers ?? []);
      } catch (err) {
        showToast('error', err.message || 'โหลดไม่สำเร็จ');
      } finally {
        setLoading(false);
      }
    }
    init();
  }, [ticketId]);

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

  async function handleSave() {
    if (!doc) return;
    setSaving(true);
    try {
      const res = await api.depositNotices.update(doc.id, buildPayload());
      setDoc(res.depositNotice);
      populateForm(res.depositNotice);
      showToast('success', 'บันทึกแล้ว');
    } catch (err) {
      showToast('error', err.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function handlePreview() {
    if (!doc) return;
    // Save first, then preview
    setSaving(true);
    try {
      const res = await api.depositNotices.update(doc.id, buildPayload());
      setDoc(res.depositNotice);
      setPreviewLoading(true);
      const html = await api.depositNotices.preview(doc.id);
      setPreview(html);
    } catch (err) {
      showToast('error', err.message || 'Preview ไม่สำเร็จ');
    } finally {
      setSaving(false);
      setPreviewLoading(false);
    }
  }

  function handleIssue() {
    if (!doc) return;
    setConfirmIssue(true);
  }

  async function confirmIssueDocument() {
    setSaving(true);
    try {
      // Save first
      await api.depositNotices.update(doc.id, buildPayload());
      const res = await api.depositNotices.issue(doc.id);
      setDoc(res.depositNotice);
      showToast('success', `ออกเอกสาร ${res.depositNotice.docNumber} เรียบร้อย`);
    } catch (err) {
      showToast('error', err.message || 'ออกเอกสารไม่สำเร็จ');
    } finally {
      setSaving(false);
      setConfirmIssue(false);
    }
  }

  async function handleDownloadXlsx() {
    await download(() => api.depositNotices.downloadXlsx(doc.id), 'xlsx');
  }

  async function handleDownloadPdf() {
    await download(() => api.depositNotices.downloadPdf(doc.id), 'pdf');
  }

  async function download(fetchBlob, extension) {
    if (!doc) return;
    try {
      const blob = await fetchBlob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      const ext = blob.type.startsWith('text/html') ? 'html' : extension;
      a.download = (doc.docNumber ?? 'draft') + '.' + ext;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      showToast('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
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
        <header className="flex items-center gap-3 mb-1">
          <Skeleton width={80} height={28} />
          <div className="flex-1">
            <Skeleton width="35%" height={20} />
          </div>
        </header>
        <section className="panel">
          <div className="panel-header"><Skeleton width="25%" height={16} /></div>
          <div className="px-[18px] py-[14px]">
            <SkeletonText lines={4} />
          </div>
        </section>
        <section className="panel">
          <div className="panel-header"><Skeleton width="25%" height={16} /></div>
          <div className="px-[18px] py-[14px]">
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
      <header className="flex items-center gap-3 mb-1">
        <button type="button" className="secondary-button" onClick={onBack}>
          <Icon name="chevronLeft" size={14} /> กลับ
        </button>
        <div className="flex-1">
          <h1 className="m-0 text-[20px] font-extrabold">
            ใบแจ้งยอดเงินรับมัดจำ
            {doc?.version > 1 && (
              <span className="ml-2 text-sm font-normal text-text-muted">Rev {doc.version}</span>
            )}
          </h1>
          {doc && (
            <div className="flex gap-2 items-center mt-1">
              {doc.docNumber && (
                <code className="text-xs bg-surface-subtle px-2 py-0.5 rounded">{doc.docNumber}</code>
              )}
              <StatusBadge tone={isIssued ? 'success' : 'neutral'}>
                {isIssued ? 'ออกแล้ว' : 'Draft'}
              </StatusBadge>
            </div>
          )}
        </div>
        {!isIssued && (
          <div className="flex gap-2">
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
        {isIssued && (
          <div className="flex gap-2">
            <button type="button" className="secondary-button" onClick={handleDownloadXlsx}>
              <Icon name="fileText" size={14} /> ดาวน์โหลด Excel
            </button>
            <button type="button" className="secondary-button" onClick={handleDownloadPdf}>
              <Icon name="fileText" size={14} /> ดาวน์โหลด PDF
            </button>
          </div>
        )}
      </header>

      <div className={cn('grid gap-4', previewHtml ? 'grid-cols-2' : 'grid-cols-1')}>
        {/* ── Left: Form ── */}
        <div className="flex flex-col gap-[14px]">

          {/* Customer */}
          <section className="panel">
            <div className="panel-header"><h2>ข้อมูลลูกค้า</h2></div>
            <div className="px-[18px] py-[14px] flex flex-col gap-[10px]">
              <div className="relative">
                <label htmlFor="doc-customer-search" className="!text-xs">ค้นหาลูกค้า (master)</label>
                <input
                  id="doc-customer-search"
                  value={customerSearch}
                  onChange={(e) => {
                    setCsSearch(e.target.value);
                    api.customers.search(e.target.value).then((r) => setCustomers(r.customers ?? [])).catch(() => {});
                  }}
                  placeholder="ชื่อบริษัท หรือ เลขภาษี..."
                />
                {customerSearch && customers.length > 0 && (
                  <div className="absolute top-full left-0 right-0 bg-surface border border-border-subtle rounded-[6px] z-50 shadow-[0_4px_12px_rgba(0,0,0,0.1)] max-h-[180px] overflow-y-auto">
                    {customers.map((c) => (
                      <button key={c.id} type="button"
                        onClick={() => selectCustomer(c)}
                        className="w-full px-3 py-2 text-left border-0 border-b border-b-surface-subtle bg-transparent cursor-pointer text-sm">
                        <strong className="block">{c.name}</strong>
                        <span className="text-2xs text-text-muted">{c.taxId} · {c.address}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <label className="!text-xs">
                ชื่อบริษัท / หน่วยงาน *
                <input value={form.customerName} disabled={isIssued}
                  onChange={(e) => setField('customerName', e.target.value)} placeholder="บริษัท..." />
              </label>
              <label className="!text-xs">
                เลขประจำตัวผู้เสียภาษี
                <input value={form.customerTaxId} disabled={isIssued}
                  onChange={(e) => setField('customerTaxId', e.target.value)} placeholder="0000000000000" />
              </label>
              <label className="!text-xs">
                ที่อยู่
                <textarea rows={2} value={form.customerAddress} disabled={isIssued}
                  onChange={(e) => setField('customerAddress', e.target.value)} placeholder="ที่อยู่..." className="resize-y" />
              </label>
              <div className="grid grid-cols-2 gap-[10px]">
                <label className="!text-xs">
                  ชื่อโครงการ
                  <input value={form.projectName} disabled={isIssued}
                    onChange={(e) => setField('projectName', e.target.value)} placeholder="โครงการ..." />
                </label>
                <label className="!text-xs">
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
            <div className="px-[18px] pt-0 pb-[14px]">
              <div className="grid grid-cols-[minmax(0,3fr)_60px_80px_80px_80px] gap-[6px] py-2 border-b border-border text-2xs font-bold text-text-faint">
                <span>รายละเอียด</span><span className="text-right">จำนวน</span>
                <span className="text-right">ราคา/หน่วย</span>
                <span className="text-right">ราคาสุทธิ</span>
                <span className="text-right">เป็นเงิน</span>
              </div>
              {form.items.map((it, idx) => (
                <div key={idx} className="grid grid-cols-[minmax(0,3fr)_60px_80px_80px_80px] gap-[6px] items-center py-[6px] border-b border-b-surface-muted">
                  {isIssued ? (
                    <>
                      <span className="text-sm">{it.description}</span>
                      <span className="text-sm text-right">{it.qty}</span>
                      <span className="text-sm text-right">{money(it.unitPrice)}</span>
                      <span className="text-sm text-right">{money(it.netUnitPrice)}</span>
                      <code className="text-xs text-right">{money((it.netUnitPrice ?? it.unitPrice) * it.qty)}</code>
                    </>
                  ) : (
                    <>
                      <input value={it.description ?? ''} placeholder="รายละเอียดสินค้า"
                        onChange={(e) => setItemField(idx, 'description', e.target.value)}
                        className="!text-xs" />
                      <input type="number" value={it.qty ?? ''} placeholder="จำนวน"
                        onChange={(e) => setItemField(idx, 'qty', e.target.value)}
                        className="!text-xs text-right" />
                      <input type="number" value={it.unitPrice ?? ''} placeholder="ราคา"
                        onChange={(e) => setItemField(idx, 'unitPrice', e.target.value)}
                        className="!text-xs text-right" />
                      <input type="number" value={it.netUnitPrice ?? it.unitPrice ?? ''} placeholder="สุทธิ"
                        onChange={(e) => setItemField(idx, 'netUnitPrice', e.target.value)}
                        className="!text-xs text-right" />
                      <code className="text-xs text-right text-text-secondary">
                        {money(((it.netUnitPrice ?? it.unitPrice) || 0) * (it.qty || 0))}
                      </code>
                    </>
                  )}
                </div>
              ))}
              {!isIssued && (
                <button type="button" className="secondary-button mt-2 !text-xs"
                  onClick={() => setForm((f) => ({ ...f, items: [...f.items, { seq: f.items.length + 1, description: '', qty: 1, unit: 'แผ่น', unitPrice: 0, netUnitPrice: 0 }] }))}>
                  <Icon name="plus" size={12} /> เพิ่มรายการ
                </button>
              )}
            </div>
          </section>

          {/* Notes */}
          <section className="panel">
            <div className="panel-header"><h2>หมายเหตุ</h2></div>
            <div className="px-[18px] py-[14px] flex flex-col gap-2">
              {noteTemplates.map((t) => (
                <label key={t.id} className={cn('!flex gap-2 items-start !text-sm leading-[1.5]', isIssued ? 'cursor-default' : 'cursor-pointer')}>
                  <input type="checkbox" disabled={isIssued}
                    checked={form.notes.includes(t.text)}
                    onChange={() => toggleNote(t.text)}
                    className={cn('w-[14px] h-[14px] mt-[3px] shrink-0 accent-info-dot', isIssued ? 'cursor-default' : 'cursor-pointer')} />
                  <span className="flex-1 min-w-0">{t.text}</span>
                </label>
              ))}
            </div>
          </section>

          {/* Summary + Deposit % */}
          <section className="panel">
            <div className="panel-header"><h2>สรุปยอด</h2></div>
            <div className="px-[18px] py-[14px]">
              {!isIssued && (
                <label className="!text-xs mb-3 !block">
                  % มัดจำ
                  <div className="flex gap-[6px] mt-1">
                    {DEPOSIT_OPTIONS.map((opt) => (
                      <button key={opt.value} type="button"
                        onClick={() => setField('depositPercent', opt.value)}
                        style={{ borderColor: form.depositPercent == opt.value ? '#1e40af' : '#cbd5e1' }}
                        className={cn(
                          'px-3 py-1 rounded-[6px] text-sm cursor-pointer border border-solid',
                          form.depositPercent == opt.value ? 'bg-info-dot text-surface' : 'bg-surface-subtle text-text-secondary',
                        )}>
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
                <div key={label} className="flex justify-between py-[5px] border-b border-b-surface-subtle text-sm">
                  <span className="text-icon-muted">{label}</span>
                  <code className={cn(bold ? 'font-bold text-text' : 'font-normal text-text-secondary')}>{money(value)} บาท</code>
                </div>
              ))}
            </div>
          </section>
        </div>

        {/* ── Right: Preview ── */}
        {previewHtml && (
          <div className="sticky top-4 h-[calc(100vh-120px)]">
            <div className="flex justify-between items-center mb-2">
              <strong className="text-sm">Preview</strong>
              <button type="button" className="icon-button" onClick={() => setPreview('')} title="ปิดตัวอย่าง" aria-label="ปิดตัวอย่าง"><Icon name="close" size={14} /></button>
            </div>
            <iframe
              ref={iframeRef}
              srcDoc={previewHtml}
              className="w-full h-[calc(100%-32px)] border border-border-subtle rounded-md"
              title="Deposit notice preview"
            />
            {previewLoading && (
              <div
                style={{ background: 'rgba(255,255,255,0.8)' }}
                className="absolute inset-0 flex items-center justify-center"
                aria-busy="true"
                aria-label="กำลังโหลดตัวอย่างเอกสาร"
              >
                <div className="w-4/5 max-w-[360px] flex flex-col gap-[10px]">
                  <Skeleton width="60%" height={18} />
                  <SkeletonText lines={6} />
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      <ConfirmDialog
        open={confirmIssue}
        tone="danger"
        title="ยืนยันการออกเอกสาร"
        message={(
          <p className="confirm-dialog-message">
            ยืนยันการออกเอกสาร? <strong>หลังจากนี้จะไม่สามารถแก้ไขได้</strong>
          </p>
        )}
        busy={saving}
        onCancel={() => setConfirmIssue(false)}
        onConfirm={confirmIssueDocument}
      />
    </div>
  );
}
