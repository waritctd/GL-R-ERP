import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatThaiDate } from '../../utils/format.js';

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

export function DocumentPage({ user, ticketId, onBack, showToast }) {
  const [doc, setDoc]               = useState(null);
  const [noteTemplates, setTemplates] = useState([]);
  const [customers, setCustomers]   = useState([]);
  const [customerSearch, setCsSearch] = useState('');
  const [loading, setLoading]       = useState(true);
  const [saving, setSaving]         = useState(false);
  const [previewHtml, setPreview]   = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);
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
          api.documents.noteTemplates(),
          api.customers.search(''),
        ]);
        setTemplates(templatesRes.templates ?? []);
        setCustomers(customersRes.customers ?? []);

        const docsRes = await api.documents.listByTicket(ticketId);
        const draft = (docsRes.documents ?? []).find((d) => d.status === 'DRAFT');

        let docData;
        if (draft) {
          docData = draft;
        } else {
          const defaultNotes = (templatesRes.templates ?? [])
            .filter((t) => t.defaultSelected)
            .map((t) => t.text);
          const created = await api.documents.createDraft(ticketId, {
            notes: defaultNotes,
            depositPercent: 0.5,
          });
          docData = created.document;
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
      const res = await api.documents.update(doc.id, buildPayload());
      setDoc(res.document);
      populateForm(res.document);
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
      const res = await api.documents.update(doc.id, buildPayload());
      setDoc(res.document);
      setPreviewLoading(true);
      const html = await api.documents.preview(doc.id);
      setPreview(html);
    } catch (err) {
      showToast('error', err.message || 'Preview ไม่สำเร็จ');
    } finally {
      setSaving(false);
      setPreviewLoading(false);
    }
  }

  async function handleIssue() {
    if (!doc) return;
    if (!window.confirm('ยืนยันการออกเอกสาร? หลังจากนี้จะไม่สามารถแก้ไขได้')) return;
    setSaving(true);
    try {
      // Save first
      await api.documents.update(doc.id, buildPayload());
      const res = await api.documents.issue(doc.id);
      setDoc(res.document);
      showToast('success', `ออกเอกสาร ${res.document.docNumber} เรียบร้อย`);
    } catch (err) {
      showToast('error', err.message || 'ออกเอกสารไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function handleDownloadXlsx() {
    if (!doc) return;
    try {
      const blob = await api.documents.downloadXlsx(doc.id);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = (doc.docNumber ?? 'draft') + '.xlsx';
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
      <div className="page-stack">
        <div style={{ padding: 40, textAlign: 'center', color: '#94a3b8' }}>กำลังโหลด...</div>
      </div>
    );
  }

  const isIssued = doc?.status === 'ISSUED';

  return (
    <div className="page-stack">
      <header style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 4 }}>
        <button type="button" className="secondary-button" onClick={onBack}>
          <Icon name="chevronLeft" size={14} /> กลับ
        </button>
        <div style={{ flex: 1 }}>
          <h1 style={{ margin: 0, fontSize: 20, fontWeight: 800 }}>
            ใบแจ้งยอดเงินรับมัดจำ
            {doc?.version > 1 && (
              <span style={{ marginLeft: 8, fontSize: 13, fontWeight: 400, color: '#64748b' }}>Rev {doc.version}</span>
            )}
          </h1>
          {doc && (
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 4 }}>
              {doc.docNumber && (
                <code style={{ fontSize: 12, background: '#f1f5f9', padding: '2px 8px', borderRadius: 4 }}>{doc.docNumber}</code>
              )}
              <StatusBadge tone={isIssued ? 'success' : 'neutral'}>
                {isIssued ? 'ออกแล้ว' : 'Draft'}
              </StatusBadge>
            </div>
          )}
        </div>
        {!isIssued && (
          <div style={{ display: 'flex', gap: 8 }}>
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
          <button type="button" className="secondary-button" onClick={handleDownloadXlsx}>
            <Icon name="fileText" size={14} /> ดาวน์โหลด Excel
          </button>
        )}
      </header>

      <div style={{ display: 'grid', gridTemplateColumns: previewHtml ? '1fr 1fr' : '1fr', gap: 16 }}>
        {/* ── Left: Form ── */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>

          {/* Customer */}
          <section className="panel">
            <div className="panel-header"><h2>ข้อมูลลูกค้า</h2></div>
            <div style={{ padding: '14px 18px', display: 'flex', flexDirection: 'column', gap: 10 }}>
              <div style={{ position: 'relative' }}>
                <label style={{ fontSize: 12 }}>ค้นหาลูกค้า (master)</label>
                <input
                  value={customerSearch}
                  onChange={(e) => {
                    setCsSearch(e.target.value);
                    api.customers.search(e.target.value).then((r) => setCustomers(r.customers ?? [])).catch(() => {});
                  }}
                  placeholder="ชื่อบริษัท หรือ เลขภาษี..."
                />
                {customerSearch && customers.length > 0 && (
                  <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, background: '#fff', border: '1px solid #e2e8f0', borderRadius: 6, zIndex: 50, boxShadow: '0 4px 12px rgba(0,0,0,0.1)', maxHeight: 180, overflowY: 'auto' }}>
                    {customers.map((c) => (
                      <button key={c.id} type="button"
                        onClick={() => selectCustomer(c)}
                        style={{ width: '100%', padding: '8px 12px', textAlign: 'left', border: 'none', background: 'none', cursor: 'pointer', fontSize: 13, borderBottom: '1px solid #f1f5f9' }}>
                        <strong style={{ display: 'block' }}>{c.name}</strong>
                        <span style={{ fontSize: 11, color: '#64748b' }}>{c.taxId} · {c.address}</span>
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
              <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,3fr) 60px 80px 80px 80px', gap: 6, padding: '8px 0', borderBottom: '1px solid #e6eaf0', fontSize: 11, fontWeight: 700, color: '#94a3b8' }}>
                <span>รายละเอียด</span><span style={{ textAlign: 'right' }}>จำนวน</span>
                <span style={{ textAlign: 'right' }}>ราคา/หน่วย</span>
                <span style={{ textAlign: 'right' }}>ราคาสุทธิ</span>
                <span style={{ textAlign: 'right' }}>เป็นเงิน</span>
              </div>
              {form.items.map((it, idx) => (
                <div key={idx} style={{ display: 'grid', gridTemplateColumns: 'minmax(0,3fr) 60px 80px 80px 80px', gap: 6, alignItems: 'center', padding: '6px 0', borderBottom: '1px solid #f8fafc' }}>
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
                      <code style={{ fontSize: 12, textAlign: 'right', color: '#334155' }}>
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
                    style={{ width: 14, height: 14, marginTop: 3, flexShrink: 0, accentColor: '#1e40af', cursor: isIssued ? 'default' : 'pointer' }} />
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
                          background: form.depositPercent == opt.value ? '#1e40af' : '#f1f5f9',
                          color: form.depositPercent == opt.value ? '#fff' : '#334155',
                          borderColor: form.depositPercent == opt.value ? '#1e40af' : '#cbd5e1',
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
                <div key={label} style={{ display: 'flex', justifyContent: 'space-between', padding: '5px 0', borderBottom: '1px solid #f1f5f9', fontSize: 13 }}>
                  <span style={{ color: '#475569' }}>{label}</span>
                  <code style={{ fontWeight: bold ? 700 : 400, color: bold ? '#0f172a' : '#334155' }}>{money(value)} บาท</code>
                </div>
              ))}
            </div>
          </section>
        </div>

        {/* ── Right: Preview ── */}
        {previewHtml && (
          <div style={{ position: 'sticky', top: 16, height: 'calc(100vh - 120px)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
              <strong style={{ fontSize: 13 }}>Preview</strong>
              <button type="button" className="icon-button" onClick={() => setPreview('')}><Icon name="close" size={14} /></button>
            </div>
            <iframe
              ref={iframeRef}
              srcDoc={previewHtml}
              style={{ width: '100%', height: 'calc(100% - 32px)', border: '1px solid #e2e8f0', borderRadius: 8 }}
              title="Document preview"
            />
            {previewLoading && (
              <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(255,255,255,0.8)' }}>
                กำลังโหลด preview...
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
