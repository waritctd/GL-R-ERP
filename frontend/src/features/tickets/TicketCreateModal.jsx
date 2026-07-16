import { useEffect, useState } from 'react';
import { api } from '../../api/index.js';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';

const emptyItem = () => ({ brand: '', model: '', color: '', texture: '', size: '', factory: '', unitBasis: 'PIECE', qty: 1, qtySqm: '', sqmPerPiece: null });

let _catalogTimer = null;
function debouncedCatalogSearch(q, cb) {
  clearTimeout(_catalogTimer);
  _catalogTimer = setTimeout(() => cb(q), 280);
}

// ── small sub-components ──────────────────────────────────────────────────────

function SearchSelect({ label, value, onSelect, placeholder, options, onSearch, searchValue, onSearchChange, loading, renderOption, renderValue, createNewLabel, onCreateNew }) {
  const [open, setOpen] = useState(false);
  return (
    <div style={{ position: 'relative' }}>
      <span style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>{label}</span>
      {value ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', border: '1px solid #cbd5e1', borderRadius: 6, background: '#f8fafc', fontSize: 13 }}>
          <span style={{ flex: 1 }}>{renderValue(value)}</span>
          <button type="button" onClick={() => { onSelect(null); }} style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#94a3b8', padding: 0 }}>
            <Icon name="close" size={14} />
          </button>
        </div>
      ) : (
        <div style={{ position: 'relative' }}>
          <input
            value={searchValue}
            onChange={(e) => { onSearchChange(e.target.value); onSearch(e.target.value); setOpen(true); }}
            onFocus={() => { onSearch(searchValue); setOpen(true); }}
            onBlur={() => setTimeout(() => setOpen(false), 150)}
            placeholder={placeholder}
            style={{ width: '100%', boxSizing: 'border-box' }}
          />
          {open && (
            <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, zIndex: 50, background: '#fff', border: '1px solid #e2e8f0', borderRadius: 6, boxShadow: '0 4px 16px rgba(0,0,0,.1)', maxHeight: 220, overflowY: 'auto' }}>
              {loading && <div style={{ padding: '10px 12px', fontSize: 12, color: '#64748b' }}>กำลังโหลด...</div>}
              {!loading && options.length === 0 && <div style={{ padding: '10px 12px', fontSize: 12, color: '#64748b' }}>ไม่พบข้อมูล</div>}
              {options.map((opt) => (
                // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- dropdown option row; onMouseDown (not click) preserves input focus for typeahead
                <div key={opt.id} onMouseDown={() => { onSelect(opt); setOpen(false); }}
                  style={{ padding: '8px 12px', fontSize: 13, cursor: 'pointer', borderBottom: '1px solid #f1f5f9' }}
                  onMouseEnter={(e) => e.currentTarget.style.background = '#f8fafc'}
                  onMouseLeave={(e) => e.currentTarget.style.background = ''}
                >
                  {renderOption(opt)}
                </div>
              ))}
              {onCreateNew && (
                // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- dropdown action row; onMouseDown (not click) preserves input focus
                <div onMouseDown={() => { setOpen(false); onCreateNew(); }}
                  style={{ padding: '8px 12px', fontSize: 12, cursor: 'pointer', color: '#2563eb', fontWeight: 600, display: 'flex', alignItems: 'center', gap: 6, borderTop: options.length > 0 ? '1px solid #e2e8f0' : 'none', background: '#f8fafc' }}
                  onMouseEnter={(e) => e.currentTarget.style.background = '#eff6ff'}
                  onMouseLeave={(e) => e.currentTarget.style.background = '#f8fafc'}
                >
                  <Icon name="plus" size={13} />
                  {createNewLabel || 'สร้างรายการใหม่'}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ── main modal ────────────────────────────────────────────────────────────────

export function TicketCreateModal({ onClose, onSubmit, initialItems }) {
  const [form, setForm] = useState({ note: '' });
  const [items, setItems] = useState(() => initialItems?.length ? initialItems : [emptyItem()]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  // customer / project / contact state
  const [customerSearch, setCustomerSearch] = useState('');
  const [customerOptions, setCustomerOptions] = useState([]);
  const [customerLoading, setCustomerLoading] = useState(false);
  const [selectedCustomer, setSelectedCustomer] = useState(null);

  const [projectOptions, setProjectOptions] = useState([]);
  const [selectedProject, setSelectedProject] = useState(null);
  const [newProjectName, setNewProjectName] = useState('');
  const [showNewProject, setShowNewProject] = useState(false);

  const [contactOptions, setContactOptions] = useState([]);
  const [selectedContact, setSelectedContact] = useState(null);
  const [showNewContact, setShowNewContact] = useState(false);
  const [newContact, setNewContact] = useState({ firstName: '', lastName: '', position: '', email: '', phone: '' });

  // catalog autocomplete
  const [catalogResults, setCatalogResults] = useState([]);
  const [catalogFocusIdx, setCatalogFocusIdx] = useState(null);

  // new customer form
  const [showNewCustomer, setShowNewCustomer] = useState(false);
  const [newCustomer, setNewCustomer] = useState({ name: '', taxId: '', branch: 'สำนักงานใหญ่', address: '', phone: '' });
  const [customerSaving, setCustomerSaving] = useState(false);

  // load projects + contacts when customer is picked
  useEffect(() => {
    if (!selectedCustomer) {
      setProjectOptions([]); setContactOptions([]);
      setSelectedProject(null); setSelectedContact(null);
      setShowNewProject(false); setShowNewContact(false);
      return;
    }
    Promise.all([
      api.customers.projects(selectedCustomer.id),
      api.customers.contacts(selectedCustomer.id),
    ]).then(([pr, cr]) => {
      setProjectOptions(pr.projects ?? []);
      setContactOptions(cr.contacts ?? []);
    }).catch(() => {
      // load failed — leave the pickers empty rather than a dangling unhandled rejection
      setProjectOptions([]); setContactOptions([]);
    });
  }, [selectedCustomer]);

  async function searchCustomers(q) {
    setCustomerLoading(true);
    try {
      const res = await api.customers.search(q);
      setCustomerOptions(res.customers ?? []);
    } finally {
      setCustomerLoading(false);
    }
  }

  function updateItem(index, field, value) {
    setItems((cur) => cur.map((item, i) => {
      if (i !== index) return item;
      const updated = { ...item, [field]: value };
      if (field === 'qty' && item.sqmPerPiece) {
        updated.qtySqm = value ? (Number(value) * item.sqmPerPiece).toFixed(3) : '';
      }
      if (field === 'qtySqm' && item.sqmPerPiece) {
        updated.qty = value ? Math.ceil(Number(value) / item.sqmPerPiece) : '';
      }
      if (field === 'unitBasis' && item.sqmPerPiece) {
        if (value === 'SQM' && item.qty) updated.qtySqm = (Number(item.qty) * item.sqmPerPiece).toFixed(3);
        if (value === 'PIECE' && item.qtySqm) updated.qty = Math.ceil(Number(item.qtySqm) / item.sqmPerPiece);
      }
      return updated;
    }));
  }

  function applyCatalogItem(index, cat) {
    setItems((cur) => cur.map((item, i) => {
      if (i !== index) return item;
      const newQtySqm = item.qty && cat.sqmPerPiece ? (Number(item.qty) * cat.sqmPerPiece).toFixed(3) : '';
      // support both old catalog (brand/size) and new price catalog (factoryName/sizeRaw)
      return {
        ...item,
        brand:       cat.factoryName  || cat.brand    || '',
        model:       cat.collection   || cat.productName || cat.productCode || '',
        color:       cat.color        || '',
        texture:     cat.surface      || '',
        size:        cat.sizeRaw      || cat.size      || '',
        factory:     cat.factoryName  || cat.factory   || '',
        sqmPerPiece: cat.sqmPerPiece  || null,
        qtySqm:      newQtySqm,
      };
    }));
    setCatalogResults([]);
    setCatalogFocusIdx(null);
  }

  function onCatalogInput(index, field, value) {
    updateItem(index, field, value);
    setCatalogFocusIdx(index);
    debouncedCatalogSearch(value, async (q) => {
      if (!q.trim()) { setCatalogResults([]); return; }
      try {
        const res = await api.catalog.prices(q, undefined, 20);
        setCatalogResults(res.items ?? []);
      } catch { /* ignore */ }
    });
  }

  function onBrandInput(index, value) { onCatalogInput(index, 'brand', value); }
  function onModelInput(index, value) { onCatalogInput(index, 'model', value); }

  async function handleCreateProject() {
    if (!newProjectName.trim()) return;
    const res = await api.customers.createProject(selectedCustomer.id, { name: newProjectName.trim() });
    const proj = res.project;
    setProjectOptions((prev) => [...prev, proj]);
    setSelectedProject(proj);
    setNewProjectName('');
    setShowNewProject(false);
  }

  async function handleCreateCustomer() {
    if (!newCustomer.name.trim()) return;
    setCustomerSaving(true);
    try {
      const res = await api.customers.create(newCustomer);
      const cust = res.customer;
      setCustomerOptions((prev) => [...prev, cust]);
      setSelectedCustomer(cust);
      setShowNewCustomer(false);
      setNewCustomer({ name: '', taxId: '', branch: 'สำนักงานใหญ่', address: '', phone: '' });
    } finally {
      setCustomerSaving(false);
    }
  }

  async function handleCreateContact() {
    if (!newContact.firstName.trim()) return;
    const res = await api.customers.createContact(selectedCustomer.id, newContact);
    const ct = res.contact;
    setContactOptions((prev) => [...prev, ct]);
    setSelectedContact(ct);
    setShowNewContact(false);
    setNewContact({ firstName: '', lastName: '', position: '', email: '', phone: '' });
  }

  async function submit(event) {
    event.preventDefault();
    if (!selectedCustomer) { setError('กรุณาเลือกบริษัท/ลูกค้า'); return; }
    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      const basis = item.unitBasis || 'PIECE';
      if (!item.brand.trim() || !item.model.trim() || !item.color.trim() || !item.texture.trim() || !item.size.trim()) {
        setError(`กรุณากรอกข้อมูลสินค้าให้ครบทุกช่องในรายการที่ ${i + 1}`);
        return;
      }
      if (basis === 'PIECE' && (!item.qty || Number(item.qty) <= 0)) {
        setError(`กรุณากรอกจำนวน (แผ่น) ในรายการที่ ${i + 1}`);
        return;
      }
      if (basis === 'SQM' && (!item.qtySqm || Number(item.qtySqm) <= 0)) {
        setError(`กรุณากรอกพื้นที่ (ตร.ม.) ในรายการที่ ${i + 1}`);
        return;
      }
    }
    setError('');
    setLoading(true);
    try {
      await onSubmit({
        title: selectedCustomer.name,
        customerName: selectedCustomer.name,
        customerId: selectedCustomer.id,
        projectId: selectedProject?.id ?? null,
        contactId: selectedContact?.id ?? null,
        note: form.note.trim() || null,
        items: items.map((item) => ({
          brand: item.brand.trim(),
          model: item.model.trim(),
          color: item.color.trim(),
          texture: item.texture.trim(),
          size: item.size.trim(),
          factory: item.factory.trim() || null,
          unitBasis: item.unitBasis || 'PIECE',
          qty: Number(item.qty) || 0,
          qtySqm: item.qtySqm !== '' && item.qtySqm != null ? Number(item.qtySqm) : null,
        })),
      });
    } catch (err) {
      setError(err.message || 'สร้างใบขอราคาไม่สำเร็จ');
      setLoading(false);
    }
  }

  return (
    <Modal
      title="สร้างใบขอราคาใหม่"
      onClose={onClose}
      footer={(
        <>
          <button type="button" className="secondary-button" onClick={onClose} disabled={loading}>ยกเลิก</button>
          <button type="submit" form="ticket-create-form" className="primary-button" disabled={loading}>
            <Icon name="fileText" />
            {loading ? 'กำลังสร้าง...' : 'สร้างใบขอราคา'}
          </button>
        </>
      )}
    >
      <form id="ticket-create-form" className="form-grid" onSubmit={submit}>

        {/* ── Section 1: Customer / Project / Contact ── */}
        <div className="span-2" style={{ display: 'flex', flexDirection: 'column', gap: 12, padding: '12px 14px', border: '1px solid #e2e8f0', borderRadius: 8, background: '#f8fafc' }}>
          <p style={{ margin: 0, fontWeight: 700, fontSize: 13, color: '#1e3a5f' }}>ข้อมูลลูกค้า</p>

          {/* Customer picker */}
          <SearchSelect
            label="บริษัท / ลูกค้า *"
            value={selectedCustomer}
            onSelect={(c) => { setSelectedCustomer(c); if (c) setShowNewCustomer(false); }}
            placeholder="พิมพ์ค้นหาชื่อบริษัท..."
            options={customerOptions}
            onSearch={searchCustomers}
            searchValue={customerSearch}
            onSearchChange={setCustomerSearch}
            loading={customerLoading}
            renderOption={(c) => (
              <div>
                <div style={{ fontWeight: 600 }}>{c.name}</div>
                {c.taxId && <div style={{ fontSize: 11, color: '#64748b' }}>เลขภาษี {c.taxId}</div>}
              </div>
            )}
            renderValue={(c) => <span><strong>{c.name}</strong>{c.taxId ? <span style={{ color: '#64748b', fontSize: 12, marginLeft: 6 }}>({c.taxId})</span> : null}</span>}
            createNewLabel="สร้างบริษัท / ลูกค้าใหม่"
            onCreateNew={() => setShowNewCustomer(true)}
          />

          {/* Inline new-customer form */}
          {showNewCustomer && !selectedCustomer && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: '12px', border: '1px solid #bfdbfe', borderRadius: 8, background: '#eff6ff' }}>
              <p style={{ margin: 0, fontSize: 12, fontWeight: 700, color: '#1d4ed8' }}>เพิ่มบริษัท / ลูกค้าใหม่</p>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <label style={{ margin: 0, gridColumn: '1 / -1' }}>
                  <span style={{ fontSize: 11 }}>ชื่อบริษัท *</span>
                  <input value={newCustomer.name} onChange={(e) => setNewCustomer((p) => ({ ...p, name: e.target.value }))} placeholder="บริษัท ... จำกัด" />
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 11 }}>เลขประจำตัวผู้เสียภาษี</span>
                  <input value={newCustomer.taxId} onChange={(e) => setNewCustomer((p) => ({ ...p, taxId: e.target.value }))} placeholder="0105xxxxxxxxx" />
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 11 }}>สาขา</span>
                  <input value={newCustomer.branch} onChange={(e) => setNewCustomer((p) => ({ ...p, branch: e.target.value }))} placeholder="สำนักงานใหญ่" />
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 11 }}>โทรศัพท์</span>
                  <input value={newCustomer.phone} onChange={(e) => setNewCustomer((p) => ({ ...p, phone: e.target.value }))} placeholder="02-xxx-xxxx" />
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 11 }}>ที่อยู่</span>
                  <input value={newCustomer.address} onChange={(e) => setNewCustomer((p) => ({ ...p, address: e.target.value }))} placeholder="ที่อยู่บริษัท" />
                </label>
              </div>
              <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
                <button type="button" className="primary-button" disabled={!newCustomer.name.trim() || customerSaving} onClick={handleCreateCustomer} style={{ fontSize: 12 }}>
                  {customerSaving ? 'กำลังบันทึก...' : 'บันทึกบริษัทใหม่'}
                </button>
                <button type="button" className="secondary-button" onClick={() => { setShowNewCustomer(false); setNewCustomer({ name: '', taxId: '', branch: 'สำนักงานใหญ่', address: '', phone: '' }); }} style={{ fontSize: 12 }}>
                  ยกเลิก
                </button>
              </div>
            </div>
          )}

          {/* Project selector */}
          {selectedCustomer && (
            <div>
              <span style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>โครงการ</span>
              {selectedProject ? (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', border: '1px solid #cbd5e1', borderRadius: 6, background: '#fff', fontSize: 13 }}>
                  <Icon name="building" size={13} style={{ color: '#64748b' }} />
                  <span style={{ flex: 1 }}>{selectedProject.name}</span>
                  <button type="button" onClick={() => setSelectedProject(null)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#94a3b8', padding: 0 }}>
                    <Icon name="close" size={14} />
                  </button>
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                    {projectOptions.map((p) => (
                      <button key={p.id} type="button"
                        style={{ padding: '4px 10px', border: '1px solid #cbd5e1', borderRadius: 20, fontSize: 12, background: '#fff', cursor: 'pointer' }}
                        onClick={() => setSelectedProject(p)}>
                        {p.name}
                      </button>
                    ))}
                    <button type="button"
                      style={{ padding: '4px 10px', border: '1px dashed #94a3b8', borderRadius: 20, fontSize: 12, background: 'none', cursor: 'pointer', color: '#64748b' }}
                      onClick={() => setShowNewProject((v) => !v)}>
                      <Icon name="plus" size={12} /> สร้างโครงการใหม่
                    </button>
                  </div>
                  {showNewProject && (
                    <div style={{ display: 'flex', gap: 6, marginTop: 4 }}>
                      <input value={newProjectName} onChange={(e) => setNewProjectName(e.target.value)}
                        placeholder="ชื่อโครงการ" style={{ flex: 1 }} />
                      <button type="button" className="primary-button" onClick={handleCreateProject} style={{ padding: '4px 12px', fontSize: 12 }}>
                        เพิ่ม
                      </button>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}

          {/* Contact selector */}
          {selectedCustomer && (
            <div>
              <span style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>ผู้ติดต่อ</span>
              {selectedContact ? (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', border: '1px solid #cbd5e1', borderRadius: 6, background: '#fff', fontSize: 13 }}>
                  <div style={{ flex: 1 }}>
                    <strong>{selectedContact.firstName} {selectedContact.lastName}</strong>
                    {selectedContact.position && <span style={{ color: '#64748b', fontSize: 12, marginLeft: 6 }}>{selectedContact.position}</span>}
                    {(selectedContact.email || selectedContact.phone) && (
                      <div style={{ fontSize: 11, color: '#64748b', marginTop: 2 }}>
                        {selectedContact.email}{selectedContact.email && selectedContact.phone ? ' · ' : ''}{selectedContact.phone}
                      </div>
                    )}
                  </div>
                  <button type="button" onClick={() => setSelectedContact(null)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#94a3b8', padding: 0 }}>
                    <Icon name="close" size={14} />
                  </button>
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                    {contactOptions.map((c) => (
                      <button key={c.id} type="button"
                        style={{ padding: '4px 10px', border: '1px solid #cbd5e1', borderRadius: 20, fontSize: 12, background: '#fff', cursor: 'pointer', textAlign: 'left' }}
                        onClick={() => setSelectedContact(c)}>
                        {c.firstName} {c.lastName}
                        {c.position ? <span style={{ color: '#64748b', marginLeft: 4 }}>({c.position})</span> : null}
                      </button>
                    ))}
                    <button type="button"
                      style={{ padding: '4px 10px', border: '1px dashed #94a3b8', borderRadius: 20, fontSize: 12, background: 'none', cursor: 'pointer', color: '#64748b' }}
                      onClick={() => setShowNewContact((v) => !v)}>
                      <Icon name="plus" size={12} /> เพิ่มผู้ติดต่อ
                    </button>
                  </div>
                  {showNewContact && (
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6, marginTop: 4, padding: '10px', border: '1px solid #e2e8f0', borderRadius: 6, background: '#fff' }}>
                      <label style={{ margin: 0 }}>
                        <span style={{ fontSize: 11 }}>ชื่อ *</span>
                        <input value={newContact.firstName} onChange={(e) => setNewContact((p) => ({ ...p, firstName: e.target.value }))} placeholder="ชื่อ" />
                      </label>
                      <label style={{ margin: 0 }}>
                        <span style={{ fontSize: 11 }}>นามสกุล</span>
                        <input value={newContact.lastName} onChange={(e) => setNewContact((p) => ({ ...p, lastName: e.target.value }))} placeholder="นามสกุล" />
                      </label>
                      <label style={{ margin: 0 }}>
                        <span style={{ fontSize: 11 }}>ตำแหน่ง</span>
                        <input value={newContact.position} onChange={(e) => setNewContact((p) => ({ ...p, position: e.target.value }))} placeholder="เช่น ผู้จัดการ" />
                      </label>
                      <label style={{ margin: 0 }}>
                        <span style={{ fontSize: 11 }}>โทร</span>
                        <input value={newContact.phone} onChange={(e) => setNewContact((p) => ({ ...p, phone: e.target.value }))} placeholder="08x-xxx-xxxx" />
                      </label>
                      <label style={{ margin: 0, gridColumn: '1 / -1' }}>
                        <span style={{ fontSize: 11 }}>อีเมล</span>
                        <input value={newContact.email} onChange={(e) => setNewContact((p) => ({ ...p, email: e.target.value }))} placeholder="email@company.com" />
                      </label>
                      <button type="button" className="primary-button" onClick={handleCreateContact} style={{ gridColumn: '1 / -1', fontSize: 12 }}>
                        เพิ่มผู้ติดต่อ
                      </button>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
        </div>

        {/* ── Section 2: Items ── */}
        <div className="span-2">
          <p style={{ margin: '0 0 10px', fontWeight: 700, fontSize: 13 }}>รายการสินค้า *</p>
          {items.map((item, index) => (
            <div key={index} style={{ border: '1px solid #e2e8f0', borderRadius: 8, padding: '12px 14px', marginBottom: 10, background: '#f8fafc', position: 'relative' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <span style={{ fontSize: 12, fontWeight: 700, color: '#64748b' }}>รายการที่ {index + 1}</span>
                {items.length > 1 && (
                  <button
                    type="button"
                    className="icon-button"
                    onClick={() => setItems((cur) => cur.filter((_, i) => i !== index))}
                    title="ลบรายการ"
                    aria-label={`ลบรายการที่ ${index + 1}`}
                    style={{ color: '#ef4444' }}
                  >
                    <Icon name="close" size={14} />
                  </button>
                )}
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>

                {/* Brand with catalog autocomplete */}
                <div style={{ position: 'relative', margin: 0 }}>
                  <span style={{ fontSize: 12, display: 'block', marginBottom: 3 }}>ชื่อยี่ห้อ *</span>
                  <input
                    value={item.brand}
                    onChange={(e) => onBrandInput(index, e.target.value)}
                    onFocus={() => { setCatalogFocusIdx(index); if (item.brand) onBrandInput(index, item.brand); }}
                    onBlur={() => setTimeout(() => setCatalogFocusIdx(null), 180)}
                    placeholder="เช่น SCG, Cotto, Panaria"
                    required
                  />
                  {catalogFocusIdx === index && catalogResults.length > 0 && (
                    <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, zIndex: 60, background: '#fff', border: '1px solid #e2e8f0', borderRadius: 6, boxShadow: '0 4px 16px rgba(0,0,0,.12)', maxHeight: 200, overflowY: 'auto' }}>
                      {catalogResults.map((cat) => (
                        // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- autocomplete option row; onMouseDown (not click) preserves input focus for typeahead
                        <div key={cat.priceId ?? cat.id} onMouseDown={() => applyCatalogItem(index, cat)}
                          style={{ padding: '7px 10px', fontSize: 12, cursor: 'pointer', borderBottom: '1px solid #f1f5f9' }}
                          onMouseEnter={(e) => e.currentTarget.style.background = '#f0f9ff'}
                          onMouseLeave={(e) => e.currentTarget.style.background = ''}
                        >
                          <strong>{cat.factoryName || cat.brand}</strong>
                          {' — '}
                          {cat.collection || cat.productName || cat.productCode || '—'}
                          <span style={{ color: '#64748b', marginLeft: 4 }}>
                            {[cat.color, cat.sizeRaw || cat.size].filter(Boolean).join(' · ')}
                          </span>
                          {cat.price && (
                            <span style={{ color: '#2563eb', fontSize: 11, marginLeft: 6, fontWeight: 600 }}>
                              {Number(cat.price).toLocaleString('th-TH', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} {cat.currency}
                            </span>
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                <div style={{ position: 'relative', margin: 0 }}>
                  <span style={{ fontSize: 12, display: 'block', marginBottom: 3 }}>ชื่อรุ่น / Collection *</span>
                  <input
                    value={item.model}
                    onChange={(e) => onModelInput(index, e.target.value)}
                    onFocus={() => { setCatalogFocusIdx(index); if (item.model) onModelInput(index, item.model); }}
                    onBlur={() => setTimeout(() => setCatalogFocusIdx(null), 180)}
                    placeholder="เช่น Stone, Elegance, L-Trim..."
                    required
                  />
                  {catalogFocusIdx === index && catalogResults.length > 0 && (
                    <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, zIndex: 60, background: '#fff', border: '1px solid #e2e8f0', borderRadius: 6, boxShadow: '0 4px 16px rgba(0,0,0,.12)', maxHeight: 200, overflowY: 'auto' }}>
                      {catalogResults.map((cat) => (
                        // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- autocomplete option row; onMouseDown (not click) preserves input focus for typeahead
                        <div key={cat.priceId ?? cat.id} onMouseDown={() => applyCatalogItem(index, cat)}
                          style={{ padding: '7px 10px', fontSize: 12, cursor: 'pointer', borderBottom: '1px solid #f1f5f9' }}
                          onMouseEnter={(e) => e.currentTarget.style.background = '#f0f9ff'}
                          onMouseLeave={(e) => e.currentTarget.style.background = ''}
                        >
                          <strong>{cat.factoryName || cat.brand}</strong>
                          {' — '}
                          {cat.collection || cat.productName || cat.productCode || '—'}
                          <span style={{ color: '#64748b', marginLeft: 4 }}>
                            {[cat.color, cat.sizeRaw || cat.size].filter(Boolean).join(' · ')}
                          </span>
                          {cat.price && (
                            <span style={{ color: '#2563eb', fontSize: 11, marginLeft: 6, fontWeight: 600 }}>
                              {Number(cat.price).toLocaleString('th-TH', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} {cat.currency}
                            </span>
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 12 }}>สี *</span>
                  <input value={item.color} onChange={(e) => updateItem(index, 'color', e.target.value)} placeholder="เช่น ขาว, เทา, ครีม" required />
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 12 }}>เนื้อผิว *</span>
                  <input value={item.texture} onChange={(e) => updateItem(index, 'texture', e.target.value)} placeholder="เช่น ด้าน, มัน, หยาบ" required />
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 12 }}>ขนาด *</span>
                  <input value={item.size} onChange={(e) => updateItem(index, 'size', e.target.value)} placeholder="เช่น 60x60, 30x60 ซม." required />
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 12 }}>โรงงาน</span>
                  <input value={item.factory} onChange={(e) => updateItem(index, 'factory', e.target.value)} placeholder="เช่น SCG Ceramics" />
                </label>

                {/* Unit basis toggle */}
                <div style={{ margin: 0, gridColumn: '1 / -1' }}>
                  <span style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>หน่วยที่ใช้สั่ง *</span>
                  <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
                    {[{ value: 'PIECE', label: 'แผ่น' }, { value: 'SQM', label: 'ตร.ม.' }].map((opt) => (
                      <label key={opt.value} style={{ display: 'flex', gap: 6, alignItems: 'center', cursor: 'pointer', fontSize: 13 }}>
                        <input type="radio" name={`unitBasis-${index}`} value={opt.value}
                          checked={(item.unitBasis || 'PIECE') === opt.value}
                          onChange={() => updateItem(index, 'unitBasis', opt.value)}
                          style={{ width: 16, height: 16, accentColor: '#1e40af', cursor: 'pointer' }} />
                        <strong>{opt.label}</strong>
                      </label>
                    ))}
                    {item.sqmPerPiece && (
                      <span style={{ fontSize: 11, color: '#64748b' }}>· 1 แผ่น = {item.sqmPerPiece} ตร.ม.</span>
                    )}
                  </div>
                </div>

                {/* Primary qty input */}
                {(item.unitBasis || 'PIECE') === 'PIECE' ? (
                  <>
                    <label style={{ margin: 0 }}>
                      <span style={{ fontSize: 12 }}>จำนวน (แผ่น) *</span>
                      <input type="number" value={item.qty} step="1"
                        onChange={(e) => updateItem(index, 'qty', e.target.value)}
                        placeholder="จำนวนแผ่น" required />
                    </label>
                    <div style={{ margin: 0 }}>
                      <span style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>พื้นที่รวม (ตร.ม.)</span>
                      <div style={{ padding: '7px 10px', border: '1px solid #e2e8f0', borderRadius: 6, background: '#f8fafc', fontSize: 13, color: item.qtySqm ? '#475569' : '#94a3b8' }}>
                        {item.qtySqm ? `${Number(item.qtySqm).toFixed(3)} ตร.ม.` : item.sqmPerPiece ? 'กรอกจำนวนแผ่นก่อน' : '—'}
                      </div>
                    </div>
                  </>
                ) : (
                  <>
                    <label style={{ margin: 0 }}>
                      <span style={{ fontSize: 12 }}>พื้นที่ (ตร.ม.) *</span>
                      <input type="number" value={item.qtySqm} min="0" step="0.001"
                        onChange={(e) => updateItem(index, 'qtySqm', e.target.value)}
                        placeholder="เช่น 120.500" required />
                    </label>
                    <div style={{ margin: 0 }}>
                      <span style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>จำนวน (แผ่น)</span>
                      <div style={{ padding: '7px 10px', border: '1px solid #e2e8f0', borderRadius: 6, background: '#f8fafc', fontSize: 13, color: item.qty ? '#475569' : '#94a3b8' }}>
                        {item.qty ? `${item.qty} แผ่น` : item.sqmPerPiece ? 'กรอกพื้นที่ก่อน' : '—'}
                      </div>
                    </div>
                  </>
                )}

              </div>
            </div>
          ))}
          <button type="button" className="secondary-button" onClick={() => setItems((cur) => [...cur, emptyItem()])} style={{ marginTop: 2 }}>
            <Icon name="plus" size={14} />
            เพิ่มรายการสินค้า
          </button>
        </div>

        {/* ── Section 3: Note ── */}
        <label className="span-2">
          หมายเหตุ
          <textarea value={form.note} onChange={(e) => setForm((f) => ({ ...f, note: e.target.value }))} rows={2} placeholder="ข้อมูลเพิ่มเติม (ถ้ามี)" />
        </label>

        {error ? <div className="form-error span-2">{error}</div> : null}
      </form>
    </Modal>
  );
}
