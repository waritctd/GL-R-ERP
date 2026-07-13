import { useEffect, useState } from 'react';
import { api } from '../../api/index.js';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { cn } from '../../utils/cn.js';

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
    <div className="relative">
      <span className="text-xs block mb-1">{label}</span>
      {value ? (
        <div className="flex items-center gap-2 py-[6px] px-[10px] border rounded-[6px] bg-surface-muted text-[13px]" style={{ borderColor: '#cbd5e1' }}>
          <span className="flex-1">{renderValue(value)}</span>
          <button type="button" onClick={() => { onSelect(null); }} className="bg-transparent border-0 cursor-pointer text-text-faint p-0">
            <Icon name="close" size={14} />
          </button>
        </div>
      ) : (
        <div className="relative">
          <input
            value={searchValue}
            onChange={(e) => { onSearchChange(e.target.value); onSearch(e.target.value); setOpen(true); }}
            onFocus={() => { onSearch(searchValue); setOpen(true); }}
            onBlur={() => setTimeout(() => setOpen(false), 150)}
            placeholder={placeholder}
            className="w-full box-border"
          />
          {open && (
            <div className="absolute top-full left-0 right-0 z-50 bg-surface border border-border-subtle rounded-[6px] shadow-[0_4px_16px_rgba(0,0,0,.1)] max-h-[220px] overflow-y-auto">
              {loading && <div className="py-[10px] px-3 text-xs text-text-faint">กำลังโหลด...</div>}
              {!loading && options.length === 0 && <div className="py-[10px] px-3 text-xs text-text-faint">ไม่พบข้อมูล</div>}
              {options.map((opt) => (
                // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- dropdown option row; onMouseDown (not click) preserves input focus for typeahead
                <div key={opt.id} onMouseDown={() => { onSelect(opt); setOpen(false); }}
                  className="py-2 px-3 text-[13px] cursor-pointer border-b border-surface-subtle hover:bg-surface-muted"
                >
                  {renderOption(opt)}
                </div>
              ))}
              {onCreateNew && (
                // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- dropdown action row; onMouseDown (not click) preserves input focus
                <div onMouseDown={() => { setOpen(false); onCreateNew(); }}
                  className={cn(
                    'py-2 px-3 text-xs cursor-pointer text-info-dot font-semibold flex items-center gap-[6px] bg-surface-muted hover:bg-info-row-active',
                    options.length > 0 ? 'border-t border-border-subtle' : 'border-t-0',
                  )}
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

export function TicketCreateModal({ onClose, onSubmit }) {
  const [form, setForm] = useState({ note: '' });
  const [items, setItems] = useState([emptyItem()]);
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
      return {
        ...item,
        brand: cat.brand, model: cat.collection || '', color: cat.color || '',
        texture: cat.surface || '', size: cat.size || '', factory: cat.factory || '',
        sqmPerPiece: cat.sqmPerPiece || null, qtySqm: newQtySqm,
      };
    }));
    setCatalogResults([]);
    setCatalogFocusIdx(null);
  }

  function onBrandInput(index, value) {
    updateItem(index, 'brand', value);
    setCatalogFocusIdx(index);
    debouncedCatalogSearch(value, async (q) => {
      if (!q.trim()) { setCatalogResults([]); return; }
      try {
        const res = await api.catalog.search(q);
        setCatalogResults(res.items ?? []);
      } catch { /* ignore */ }
    });
  }

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
        <div className="span-2 flex flex-col gap-3 py-3 px-[14px] border border-border-subtle rounded-md bg-surface-muted">
          <p className="m-0 font-bold text-[13px]" style={{ color: '#1e3a5f' }}>ข้อมูลลูกค้า</p>

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
                <div className="font-semibold">{c.name}</div>
                {c.taxId && <div className="text-[11px] text-text-muted">เลขภาษี {c.taxId}</div>}
              </div>
            )}
            renderValue={(c) => <span><strong>{c.name}</strong>{c.taxId ? <span className="text-text-muted text-xs ml-[6px]">({c.taxId})</span> : null}</span>}
            createNewLabel="สร้างบริษัท / ลูกค้าใหม่"
            onCreateNew={() => setShowNewCustomer(true)}
          />

          {/* Inline new-customer form */}
          {showNewCustomer && !selectedCustomer && (
            <div className="flex flex-col gap-2 p-3 rounded-md bg-info-bg-alt" style={{ border: '1px solid #bfdbfe' }}>
              <p className="m-0 text-xs font-bold text-info">เพิ่มบริษัท / ลูกค้าใหม่</p>
              <div className="grid grid-cols-2 gap-2">
                <label className="m-0 col-span-2">
                  <span className="text-[11px]">ชื่อบริษัท *</span>
                  <input value={newCustomer.name} onChange={(e) => setNewCustomer((p) => ({ ...p, name: e.target.value }))} placeholder="บริษัท ... จำกัด" />
                </label>
                <label className="m-0">
                  <span className="text-[11px]">เลขประจำตัวผู้เสียภาษี</span>
                  <input value={newCustomer.taxId} onChange={(e) => setNewCustomer((p) => ({ ...p, taxId: e.target.value }))} placeholder="0105xxxxxxxxx" />
                </label>
                <label className="m-0">
                  <span className="text-[11px]">สาขา</span>
                  <input value={newCustomer.branch} onChange={(e) => setNewCustomer((p) => ({ ...p, branch: e.target.value }))} placeholder="สำนักงานใหญ่" />
                </label>
                <label className="m-0">
                  <span className="text-[11px]">โทรศัพท์</span>
                  <input value={newCustomer.phone} onChange={(e) => setNewCustomer((p) => ({ ...p, phone: e.target.value }))} placeholder="02-xxx-xxxx" />
                </label>
                <label className="m-0">
                  <span className="text-[11px]">ที่อยู่</span>
                  <input value={newCustomer.address} onChange={(e) => setNewCustomer((p) => ({ ...p, address: e.target.value }))} placeholder="ที่อยู่บริษัท" />
                </label>
              </div>
              <div className="flex gap-2 mt-1">
                <button type="button" className="primary-button text-xs" disabled={!newCustomer.name.trim() || customerSaving} onClick={handleCreateCustomer}>
                  {customerSaving ? 'กำลังบันทึก...' : 'บันทึกบริษัทใหม่'}
                </button>
                <button type="button" className="secondary-button text-xs" onClick={() => { setShowNewCustomer(false); setNewCustomer({ name: '', taxId: '', branch: 'สำนักงานใหญ่', address: '', phone: '' }); }}>
                  ยกเลิก
                </button>
              </div>
            </div>
          )}

          {/* Project selector */}
          {selectedCustomer && (
            <div>
              <span className="text-xs block mb-1">โครงการ</span>
              {selectedProject ? (
                <div className="flex items-center gap-2 py-[6px] px-[10px] rounded-[6px] bg-surface text-[13px]" style={{ border: '1px solid #cbd5e1' }}>
                  <Icon name="building" size={13} className="text-text-muted" />
                  <span className="flex-1">{selectedProject.name}</span>
                  <button type="button" onClick={() => setSelectedProject(null)} className="bg-transparent border-0 cursor-pointer text-text-faint p-0">
                    <Icon name="close" size={14} />
                  </button>
                </div>
              ) : (
                <div className="flex flex-col gap-[6px]">
                  <div className="flex gap-[6px] flex-wrap">
                    {projectOptions.map((p) => (
                      <button key={p.id} type="button"
                        className="py-1 px-[10px] rounded-full text-xs bg-surface cursor-pointer"
                        style={{ border: '1px solid #cbd5e1' }}
                        onClick={() => setSelectedProject(p)}>
                        {p.name}
                      </button>
                    ))}
                    <button type="button"
                      className="py-1 px-[10px] border border-dashed border-text-faint rounded-full text-xs bg-transparent cursor-pointer text-text-muted"
                      onClick={() => setShowNewProject((v) => !v)}>
                      <Icon name="plus" size={12} /> สร้างโครงการใหม่
                    </button>
                  </div>
                  {showNewProject && (
                    <div className="flex gap-[6px] mt-1">
                      <input value={newProjectName} onChange={(e) => setNewProjectName(e.target.value)}
                        placeholder="ชื่อโครงการ" className="flex-1" />
                      <button type="button" className="primary-button py-1 px-3 text-xs" onClick={handleCreateProject}>
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
              <span className="text-xs block mb-1">ผู้ติดต่อ</span>
              {selectedContact ? (
                <div className="flex items-center gap-2 py-[6px] px-[10px] rounded-[6px] bg-surface text-[13px]" style={{ border: '1px solid #cbd5e1' }}>
                  <div className="flex-1">
                    <strong>{selectedContact.firstName} {selectedContact.lastName}</strong>
                    {selectedContact.position && <span className="text-text-muted text-xs ml-[6px]">{selectedContact.position}</span>}
                    {(selectedContact.email || selectedContact.phone) && (
                      <div className="text-[11px] text-text-faint mt-[2px]">
                        {selectedContact.email}{selectedContact.email && selectedContact.phone ? ' · ' : ''}{selectedContact.phone}
                      </div>
                    )}
                  </div>
                  <button type="button" onClick={() => setSelectedContact(null)} className="bg-transparent border-0 cursor-pointer text-text-faint p-0">
                    <Icon name="close" size={14} />
                  </button>
                </div>
              ) : (
                <div className="flex flex-col gap-[6px]">
                  <div className="flex gap-[6px] flex-wrap">
                    {contactOptions.map((c) => (
                      <button key={c.id} type="button"
                        className="py-1 px-[10px] rounded-full text-xs bg-surface cursor-pointer text-left"
                        style={{ border: '1px solid #cbd5e1' }}
                        onClick={() => setSelectedContact(c)}>
                        {c.firstName} {c.lastName}
                        {c.position ? <span className="text-text-muted ml-1">({c.position})</span> : null}
                      </button>
                    ))}
                    <button type="button"
                      className="py-1 px-[10px] border border-dashed border-text-faint rounded-full text-xs bg-transparent cursor-pointer text-text-muted"
                      onClick={() => setShowNewContact((v) => !v)}>
                      <Icon name="plus" size={12} /> เพิ่มผู้ติดต่อ
                    </button>
                  </div>
                  {showNewContact && (
                    <div className="grid grid-cols-2 gap-[6px] mt-1 p-[10px] border border-border-subtle rounded-[6px] bg-surface">
                      <label className="m-0">
                        <span className="text-[11px]">ชื่อ *</span>
                        <input value={newContact.firstName} onChange={(e) => setNewContact((p) => ({ ...p, firstName: e.target.value }))} placeholder="ชื่อ" />
                      </label>
                      <label className="m-0">
                        <span className="text-[11px]">นามสกุล</span>
                        <input value={newContact.lastName} onChange={(e) => setNewContact((p) => ({ ...p, lastName: e.target.value }))} placeholder="นามสกุล" />
                      </label>
                      <label className="m-0">
                        <span className="text-[11px]">ตำแหน่ง</span>
                        <input value={newContact.position} onChange={(e) => setNewContact((p) => ({ ...p, position: e.target.value }))} placeholder="เช่น ผู้จัดการ" />
                      </label>
                      <label className="m-0">
                        <span className="text-[11px]">โทร</span>
                        <input value={newContact.phone} onChange={(e) => setNewContact((p) => ({ ...p, phone: e.target.value }))} placeholder="08x-xxx-xxxx" />
                      </label>
                      <label className="m-0 col-span-2">
                        <span className="text-[11px]">อีเมล</span>
                        <input value={newContact.email} onChange={(e) => setNewContact((p) => ({ ...p, email: e.target.value }))} placeholder="email@company.com" />
                      </label>
                      <button type="button" className="primary-button col-span-2 text-xs" onClick={handleCreateContact}>
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
          <p className="mx-0 mt-0 mb-[10px] font-bold text-[13px]">รายการสินค้า *</p>
          {items.map((item, index) => (
            <div key={index} className="border border-border-subtle rounded-md py-3 px-[14px] mb-[10px] bg-surface-muted relative">
              <div className="flex justify-between items-center mb-2">
                <span className="text-xs font-bold text-text-muted">รายการที่ {index + 1}</span>
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
              <div className="grid grid-cols-2 gap-2">

                {/* Brand with catalog autocomplete */}
                <div className="relative m-0">
                  <span className="text-xs block mb-[3px]">ชื่อยี่ห้อ *</span>
                  <input
                    value={item.brand}
                    onChange={(e) => onBrandInput(index, e.target.value)}
                    onFocus={() => { setCatalogFocusIdx(index); if (item.brand) onBrandInput(index, item.brand); }}
                    onBlur={() => setTimeout(() => setCatalogFocusIdx(null), 180)}
                    placeholder="เช่น SCG, Cotto, Panaria"
                    required
                  />
                  {catalogFocusIdx === index && catalogResults.length > 0 && (
                    <div className="absolute top-full left-0 right-0 z-[60] bg-surface border border-border-subtle rounded-[6px] shadow-[0_4px_16px_rgba(0,0,0,.12)] max-h-[200px] overflow-y-auto">
                      {catalogResults.map((cat) => (
                        // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- autocomplete option row; onMouseDown (not click) preserves input focus for typeahead
                        <div key={cat.id} onMouseDown={() => applyCatalogItem(index, cat)}
                          className="py-[7px] px-[10px] text-xs cursor-pointer border-b border-surface-subtle hover:bg-[#f0f9ff]"
                        >
                          <strong>{cat.brand}</strong> — {cat.collection}
                          <span className="text-text-muted ml-1">{cat.color} · {cat.size}</span>
                          {cat.factory && <span className="text-text-faint text-[11px] ml-1">({cat.factory})</span>}
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                <label className="m-0">
                  <span className="text-xs">ชื่อรุ่น *</span>
                  <input value={item.model} onChange={(e) => updateItem(index, 'model', e.target.value)} placeholder="เช่น Elegance Series" required />
                </label>
                <label className="m-0">
                  <span className="text-xs">สี *</span>
                  <input value={item.color} onChange={(e) => updateItem(index, 'color', e.target.value)} placeholder="เช่น ขาว, เทา, ครีม" required />
                </label>
                <label className="m-0">
                  <span className="text-xs">เนื้อผิว *</span>
                  <input value={item.texture} onChange={(e) => updateItem(index, 'texture', e.target.value)} placeholder="เช่น ด้าน, มัน, หยาบ" required />
                </label>
                <label className="m-0">
                  <span className="text-xs">ขนาด *</span>
                  <input value={item.size} onChange={(e) => updateItem(index, 'size', e.target.value)} placeholder="เช่น 60x60, 30x60 ซม." required />
                </label>
                <label className="m-0">
                  <span className="text-xs">โรงงาน</span>
                  <input value={item.factory} onChange={(e) => updateItem(index, 'factory', e.target.value)} placeholder="เช่น SCG Ceramics" />
                </label>

                {/* Unit basis toggle */}
                <div className="m-0 col-span-2">
                  <span className="text-xs block mb-[6px]">หน่วยที่ใช้สั่ง *</span>
                  <div className="flex gap-4 items-center flex-wrap">
                    {[{ value: 'PIECE', label: 'แผ่น' }, { value: 'SQM', label: 'ตร.ม.' }].map((opt) => (
                      <label key={opt.value} className="flex gap-[6px] items-center cursor-pointer text-[13px] m-0">
                        <input type="radio" name={`unitBasis-${index}`} value={opt.value}
                          checked={(item.unitBasis || 'PIECE') === opt.value}
                          onChange={() => updateItem(index, 'unitBasis', opt.value)}
                          className="w-4 h-4 cursor-pointer" />
                        <strong>{opt.label}</strong>
                      </label>
                    ))}
                    {item.sqmPerPiece && (
                      <span className="text-text-muted text-[11px]">· 1 แผ่น = {item.sqmPerPiece} ตร.ม.</span>
                    )}
                  </div>
                </div>

                {/* Primary qty input */}
                {(item.unitBasis || 'PIECE') === 'PIECE' ? (
                  <>
                    <label className="m-0">
                      <span className="text-xs">จำนวน (แผ่น) *</span>
                      <input type="number" value={item.qty} step="1"
                        onChange={(e) => updateItem(index, 'qty', e.target.value)}
                        placeholder="จำนวนแผ่น" required />
                    </label>
                    <div className="m-0">
                      <span className="text-xs block mb-1">พื้นที่รวม (ตร.ม.)</span>
                      <div className={`py-[7px] px-[10px] border border-border-subtle rounded-md bg-surface-muted text-sm ${item.qtySqm ? 'text-text-muted' : 'text-text-faint'}`}>
                        {item.qtySqm ? `${Number(item.qtySqm).toFixed(3)} ตร.ม.` : item.sqmPerPiece ? 'กรอกจำนวนแผ่นก่อน' : '—'}
                      </div>
                    </div>
                  </>
                ) : (
                  <>
                    <label className="m-0">
                      <span className="text-xs">พื้นที่ (ตร.ม.) *</span>
                      <input type="number" value={item.qtySqm} min="0" step="0.001"
                        onChange={(e) => updateItem(index, 'qtySqm', e.target.value)}
                        placeholder="เช่น 120.500" required />
                    </label>
                    <div className="m-0">
                      <span className="text-xs block mb-1">จำนวน (แผ่น)</span>
                      <div className={`py-[7px] px-[10px] border border-border-subtle rounded-md bg-surface-muted text-sm ${item.qty ? 'text-text-muted' : 'text-text-faint'}`}>
                        {item.qty ? `${item.qty} แผ่น` : item.sqmPerPiece ? 'กรอกพื้นที่ก่อน' : '—'}
                      </div>
                    </div>
                  </>
                )}

              </div>
            </div>
          ))}
          <button type="button" className="secondary-button mt-[2px]" onClick={() => setItems((cur) => [...cur, emptyItem()])}>
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
