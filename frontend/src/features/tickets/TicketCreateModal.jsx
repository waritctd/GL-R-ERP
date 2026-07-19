import { useEffect, useRef, useState } from 'react';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { fieldErrorId } from '../../components/common/FormField.jsx';

const emptyItem = () => ({ brand: '', model: '', color: '', texture: '', size: '', factory: '', unitBasis: 'PIECE', qty: 1, qtySqm: '', sqmPerPiece: null });

let _catalogTimer = null;
function debouncedCatalogSearch(q, cb) {
  clearTimeout(_catalogTimer);
  _catalogTimer = setTimeout(() => cb(q), 280);
}

// ── validation (UX-03) ──────────────────────────────────────────────────────
// Mirrors the rules that used to live inline in submit() as a sequence of
// "first failing check wins, generic message" early-returns. The *conditions*
// are unchanged; what changed is the shape of the result — every invalid
// field is now reported (keyed the same way as `fieldErrors` state below:
// 'customer', 'project', `items.<index>.<field>`), so a long form doesn't
// force the user to fix-and-resubmit one message at a time.
//
// Rule 3 used to fire ONE message for five different blank fields in a row
// ("กรุณากรอกข้อมูลสินค้าให้ครบทุกช่องในรายการที่ N"). That text can't serve
// as a per-field message (it doesn't say *which* field), so each field below
// gets its own "กรุณากรอก<label>" text instead — new wording, but the same
// required-ness rule. Rules 1/2/4/5's original strings are preserved
// verbatim.
const REQUIRED_ITEM_FIELD_LABELS = {
  brand: 'ชื่อยี่ห้อ',
  model: 'ชื่อรุ่น / Collection',
  color: 'สี',
  texture: 'เนื้อผิว',
  size: 'ขนาด',
};

function makeItemSchema(rowNumber) {
  return z.object({
    brand: z.string(),
    model: z.string(),
    color: z.string(),
    texture: z.string(),
    size: z.string(),
    unitBasis: z.string().optional(),
    qty: z.union([z.string(), z.number()]).nullable().optional(),
    qtySqm: z.union([z.string(), z.number()]).nullable().optional(),
  }).superRefine((item, ctx) => {
    for (const field of Object.keys(REQUIRED_ITEM_FIELD_LABELS)) {
      if (!String(item[field] ?? '').trim()) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: [field], message: `กรุณากรอก${REQUIRED_ITEM_FIELD_LABELS[field]}` });
      }
    }
    const basis = item.unitBasis || 'PIECE';
    if (basis === 'PIECE' && (!item.qty || Number(item.qty) <= 0)) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['qty'], message: `กรุณากรอกจำนวน (แผ่น) ในรายการที่ ${rowNumber}` });
    }
    if (basis === 'SQM' && (!item.qtySqm || Number(item.qtySqm) <= 0)) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['qtySqm'], message: `กรุณากรอกพื้นที่ (ตร.ม.) ในรายการที่ ${rowNumber}` });
    }
  });
}

const customerRequiredSchema = z.any().refine((v) => v != null, 'กรุณาเลือกบริษัท/ลูกค้า');
// Mirrors TicketService.create: every new deal belongs to a โครงการ.
const projectRequiredSchema = z.any().refine((v) => v != null, 'กรุณาเลือกโครงการ (1 ดีล = 1 ใบขอราคา ภายใต้โครงการ)');

/**
 * Validates the whole form and returns every invalid field, plus `order`:
 * those same keys in on-screen top-to-bottom order, so submit() can focus
 * the first one without depending on zod's internal issue ordering (a
 * root-level check runs after nested array elements are validated, which
 * would put item errors before customer/project if we relied on it).
 */
function validateTicketForm({ customer, project, items }) {
  const errors = {};
  const order = [];

  const customerResult = customerRequiredSchema.safeParse(customer);
  if (!customerResult.success) {
    errors.customer = customerResult.error.issues[0].message;
    order.push('customer');
  }

  // A project can only be chosen once a customer is selected (the picker
  // doesn't even render before then) — like the original sequential checks,
  // only flag it once the customer is valid.
  if (customerResult.success) {
    const projectResult = projectRequiredSchema.safeParse(project);
    if (!projectResult.success) {
      errors.project = projectResult.error.issues[0].message;
      order.push('project');
    }
  }

  items.forEach((item, index) => {
    const itemResult = makeItemSchema(index + 1).safeParse(item);
    if (!itemResult.success) {
      for (const issue of itemResult.error.issues) {
        const key = `items.${index}.${issue.path[0]}`;
        errors[key] = issue.message;
        order.push(key);
      }
    }
  });

  return { errors, order };
}

// ── small sub-components ──────────────────────────────────────────────────────

function SearchSelect({ id, label, value, onSelect, placeholder, options, onSearch, searchValue, onSearchChange, loading, renderOption, renderValue, createNewLabel, onCreateNew, inputRef, error }) {
  const [open, setOpen] = useState(false);
  // Hand-wired aria contract (no <FormField> here — the control renders
  // either a value chip or a search input depending on state, which doesn't
  // fit FormField's single-child-with-matching-id model). Same contract as
  // FormField: aria-invalid + aria-describedby pointing at a role="alert"
  // error paragraph, using the same `${id}-error` id convention via
  // fieldErrorId so screen readers get field/error association (WCAG 3.3.1).
  const errorId = error && id ? fieldErrorId(id) : undefined;
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
            id={id}
            ref={inputRef}
            value={searchValue}
            onChange={(e) => { onSearchChange(e.target.value); onSearch(e.target.value); setOpen(true); }}
            onFocus={() => { onSearch(searchValue); setOpen(true); }}
            onBlur={() => setTimeout(() => setOpen(false), 150)}
            placeholder={placeholder}
            style={{ width: '100%', boxSizing: 'border-box' }}
            aria-required="true"
            aria-invalid={error ? true : undefined}
            aria-describedby={errorId}
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
      {error ? (
        <p id={errorId} role="alert" style={{ margin: '4px 0 0', fontSize: 11, fontWeight: 700, color: '#ef4444' }}>{error}</p>
      ) : null}
    </div>
  );
}

// ── main modal ────────────────────────────────────────────────────────────────

export function TicketCreateModal({ onClose, onSubmit, initialItems }) {
  const [form, setForm] = useState({ note: '' });
  // V50: a deal may start with NO items (lightweight lead-stage draft) — the
  // price-request flow begins later once items are added and submitted.
  const [items, setItems] = useState(() => initialItems?.length ? initialItems : []);
  const [loading, setLoading] = useState(false);
  // Form-level: submit/API failures (สร้างใบขอราคาไม่สำเร็จ, สร้างโครงการไม่สำเร็จ,
  // เพิ่มผู้ติดต่อไม่สำเร็จ). Kept separate from `fieldErrors` — those are two
  // different kinds of problem and shouldn't share one string.
  const [error, setError] = useState('');
  // Field-level: keyed 'customer' | 'project' | `items.<index>.<field>`.
  // See validateTicketForm() above for how this is populated.
  const [fieldErrors, setFieldErrors] = useState({});
  // DOM node per fieldErrors key, so submit() can scroll+focus the first
  // invalid field on a form long enough that the error can be off-screen.
  const fieldRefs = useRef({});
  function clearFieldError(key) {
    setFieldErrors((prev) => {
      if (!(key in prev)) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  }

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
  const [creatingProject, setCreatingProject] = useState(false);
  const [creatingContact, setCreatingContact] = useState(false);

  // catalog autocomplete
  const [catalogResults, setCatalogResults] = useState([]);
  // { index, field: 'brand' | 'model' } | null — tracking which field is
  // focused (not just which item row) so the brand and model inputs for the
  // same row don't both render the same catalogResults dropdown at once.
  const [catalogFocus, setCatalogFocus] = useState(null);

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
    clearFieldError(`items.${index}.${field}`);
    // unitBasis flips which of qty/qtySqm is required — clear both so a
    // stale error from the basis the user just left doesn't linger.
    if (field === 'unitBasis') {
      clearFieldError(`items.${index}.qty`);
      clearFieldError(`items.${index}.qtySqm`);
    }
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
    // Catalog pick fills brand/model/color/texture/size in one shot — clear
    // any of those five that were previously flagged as blank.
    ['brand', 'model', 'color', 'texture', 'size'].forEach((f) => clearFieldError(`items.${index}.${f}`));
    setCatalogResults([]);
    setCatalogFocus(null);
  }

  function onCatalogInput(index, field, value) {
    updateItem(index, field, value);
    setCatalogFocus({ index, field });
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
    setCreatingProject(true);
    try {
      const res = await api.customers.createProject(selectedCustomer.id, { name: newProjectName.trim() });
      const proj = res.project;
      setProjectOptions((prev) => [...prev, proj]);
      setSelectedProject(proj);
      clearFieldError('project');
      setNewProjectName('');
      setShowNewProject(false);
    } catch (err) {
      setError(err.message || 'สร้างโครงการไม่สำเร็จ');
    } finally {
      setCreatingProject(false);
    }
  }

  async function handleCreateCustomer() {
    if (!newCustomer.name.trim()) return;
    setCustomerSaving(true);
    try {
      const res = await api.customers.create(newCustomer);
      const cust = res.customer;
      setCustomerOptions((prev) => [...prev, cust]);
      setSelectedCustomer(cust);
      clearFieldError('customer');
      setShowNewCustomer(false);
      setNewCustomer({ name: '', taxId: '', branch: 'สำนักงานใหญ่', address: '', phone: '' });
    } finally {
      setCustomerSaving(false);
    }
  }

  async function handleCreateContact() {
    if (!newContact.firstName.trim()) return;
    setCreatingContact(true);
    try {
      const res = await api.customers.createContact(selectedCustomer.id, newContact);
      const ct = res.contact;
      setContactOptions((prev) => [...prev, ct]);
      setSelectedContact(ct);
      setShowNewContact(false);
      setNewContact({ firstName: '', lastName: '', position: '', email: '', phone: '' });
    } catch (err) {
      setError(err.message || 'เพิ่มผู้ติดต่อไม่สำเร็จ');
    } finally {
      setCreatingContact(false);
    }
  }

  async function submit(event) {
    event.preventDefault();
    const { errors: nextFieldErrors, order } = validateTicketForm({
      customer: selectedCustomer,
      project: selectedProject,
      items,
    });
    if (order.length > 0) {
      setFieldErrors(nextFieldErrors);
      setError('');
      // Acceptance criterion: don't make the user hunt on a long form —
      // jump straight to (and focus) the first invalid field, in the same
      // top-to-bottom order validateTicketForm() reports them in.
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
    setError('');
    setLoading(true);
    try {
      await onSubmit({
        title: selectedCustomer.name,
        customerName: selectedCustomer.name,
        customerId: selectedCustomer.id,
        projectId: selectedProject.id,
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
      title="สร้างดีลใหม่"
      subtitle="เริ่มได้ตั้งแต่ขั้นหาทางเข้าพบ — รายการสินค้าเพิ่มทีหลังได้เมื่อถึงขั้นเสนอราคา"
      onClose={onClose}
      footer={(
        <>
          <button type="button" className="secondary-button" onClick={onClose} disabled={loading}>ยกเลิก</button>
          <button type="submit" form="ticket-create-form" className="primary-button" disabled={loading}>
            <Icon name="fileText" />
            {loading ? 'กำลังสร้าง...' : 'สร้างดีล'}
          </button>
        </>
      )}
    >
      {/*
        noValidate: several inputs below still carry the native `required`
        attribute (kept for its own semantics), but our own submit() is now
        the single source of truth for validation. Without noValidate, the
        browser's built-in constraint validation would intercept a genuinely
        empty required field and block the 'submit' event entirely — meaning
        our aria-wired per-field errors and scroll-to-first-invalid below
        would never run for exactly the case they exist to handle.
      */}
      <form id="ticket-create-form" className="form-grid" onSubmit={submit} noValidate>

        {/* ── Section 1: Customer / Project / Contact ── */}
        <div className="span-2" style={{ display: 'flex', flexDirection: 'column', gap: 12, padding: '12px 14px', border: '1px solid #e2e8f0', borderRadius: 8, background: '#f8fafc' }}>
          <p style={{ margin: 0, fontWeight: 700, fontSize: 13, color: '#1e3a5f' }}>ข้อมูลลูกค้า</p>

          {/* Customer picker */}
          <SearchSelect
            id="customer-select"
            label="บริษัท / ลูกค้า *"
            value={selectedCustomer}
            onSelect={(c) => { setSelectedCustomer(c); if (c) { setShowNewCustomer(false); clearFieldError('customer'); } }}
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
            inputRef={(el) => { fieldRefs.current.customer = el; }}
            error={fieldErrors.customer}
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
            // Hand-wired aria contract: this control is a set of pill buttons,
            // not a single input FormField can attach aria-* to, so the
            // group container carries aria-invalid/aria-describedby (like a
            // <fieldset>) and is the scroll+focus target — tabIndex={-1}
            // makes a plain <div> programmatically focusable for that.
            <div
              id="project-field"
              ref={(el) => { fieldRefs.current.project = el; }}
              tabIndex={-1}
              aria-invalid={fieldErrors.project ? true : undefined}
              aria-describedby={fieldErrors.project ? fieldErrorId('project-field') : undefined}
            >
              <span style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>โครงการ *</span>
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
                        onClick={() => { setSelectedProject(p); clearFieldError('project'); }}>
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
                      <button type="button" className="primary-button" onClick={handleCreateProject} disabled={creatingProject} style={{ padding: '4px 12px', fontSize: 12 }}>
                        {creatingProject ? 'กำลังเพิ่ม...' : 'เพิ่ม'}
                      </button>
                    </div>
                  )}
                </div>
              )}
              {fieldErrors.project ? (
                <p id={fieldErrorId('project-field')} role="alert" style={{ margin: '6px 0 0', fontSize: 11, fontWeight: 700, color: '#ef4444' }}>{fieldErrors.project}</p>
              ) : null}
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
                      <button type="button" className="primary-button" onClick={handleCreateContact} disabled={creatingContact} style={{ gridColumn: '1 / -1', fontSize: 12 }}>
                        {creatingContact ? 'กำลังเพิ่ม...' : 'เพิ่มผู้ติดต่อ'}
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
          <p style={{ margin: '0 0 10px', fontWeight: 700, fontSize: 13 }}>รายการสินค้า (ไม่บังคับ — เพิ่มทีหลังได้)</p>
          {items.map((item, index) => (
            <div key={index} style={{ border: '1px solid #e2e8f0', borderRadius: 8, padding: '12px 14px', marginBottom: 10, background: '#f8fafc', position: 'relative' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <span style={{ fontSize: 12, fontWeight: 700, color: '#64748b' }}>รายการที่ {index + 1}</span>
                {items.length > 1 && (
                  <button
                    type="button"
                    className="icon-button"
                    onClick={() => {
                      setItems((cur) => cur.filter((_, i) => i !== index));
                      // Removing a row shifts every later row's index, so a
                      // stale error keyed to the old index would otherwise
                      // render against the wrong row after this. Item errors
                      // are cheap to re-derive on the next submit.
                      setFieldErrors((prev) => Object.fromEntries(Object.entries(prev).filter(([k]) => !k.startsWith('items.'))));
                    }}
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
                    id={`item-${index}-brand`}
                    ref={(el) => { fieldRefs.current[`items.${index}.brand`] = el; }}
                    value={item.brand}
                    onChange={(e) => onBrandInput(index, e.target.value)}
                    onFocus={() => { setCatalogFocus({ index, field: 'brand' }); if (item.brand) onBrandInput(index, item.brand); }}
                    onBlur={() => setTimeout(() => setCatalogFocus(null), 180)}
                    placeholder="เช่น SCG, Cotto, Panaria"
                    required
                    aria-required="true"
                    aria-invalid={fieldErrors[`items.${index}.brand`] ? true : undefined}
                    aria-describedby={fieldErrors[`items.${index}.brand`] ? fieldErrorId(`item-${index}-brand`) : undefined}
                  />
                  {fieldErrors[`items.${index}.brand`] ? (
                    <p id={fieldErrorId(`item-${index}-brand`)} role="alert" style={{ margin: '4px 0 0', fontSize: 11, fontWeight: 700, color: '#ef4444' }}>{fieldErrors[`items.${index}.brand`]}</p>
                  ) : null}
                  {catalogFocus?.index === index && catalogFocus?.field === 'brand' && catalogResults.length > 0 && (
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
                    id={`item-${index}-model`}
                    ref={(el) => { fieldRefs.current[`items.${index}.model`] = el; }}
                    value={item.model}
                    onChange={(e) => onModelInput(index, e.target.value)}
                    onFocus={() => { setCatalogFocus({ index, field: 'model' }); if (item.model) onModelInput(index, item.model); }}
                    onBlur={() => setTimeout(() => setCatalogFocus(null), 180)}
                    placeholder="เช่น Stone, Elegance, L-Trim..."
                    required
                    aria-required="true"
                    aria-invalid={fieldErrors[`items.${index}.model`] ? true : undefined}
                    aria-describedby={fieldErrors[`items.${index}.model`] ? fieldErrorId(`item-${index}-model`) : undefined}
                  />
                  {fieldErrors[`items.${index}.model`] ? (
                    <p id={fieldErrorId(`item-${index}-model`)} role="alert" style={{ margin: '4px 0 0', fontSize: 11, fontWeight: 700, color: '#ef4444' }}>{fieldErrors[`items.${index}.model`]}</p>
                  ) : null}
                  {catalogFocus?.index === index && catalogFocus?.field === 'model' && catalogResults.length > 0 && (
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
                  <input
                    id={`item-${index}-color`}
                    ref={(el) => { fieldRefs.current[`items.${index}.color`] = el; }}
                    value={item.color}
                    onChange={(e) => updateItem(index, 'color', e.target.value)}
                    placeholder="เช่น ขาว, เทา, ครีม"
                    required
                    aria-required="true"
                    aria-invalid={fieldErrors[`items.${index}.color`] ? true : undefined}
                    aria-describedby={fieldErrors[`items.${index}.color`] ? fieldErrorId(`item-${index}-color`) : undefined}
                  />
                  {fieldErrors[`items.${index}.color`] ? (
                    <p id={fieldErrorId(`item-${index}-color`)} role="alert" style={{ margin: '4px 0 0', fontSize: 11, fontWeight: 700, color: '#ef4444' }}>{fieldErrors[`items.${index}.color`]}</p>
                  ) : null}
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 12 }}>เนื้อผิว *</span>
                  <input
                    id={`item-${index}-texture`}
                    ref={(el) => { fieldRefs.current[`items.${index}.texture`] = el; }}
                    value={item.texture}
                    onChange={(e) => updateItem(index, 'texture', e.target.value)}
                    placeholder="เช่น ด้าน, มัน, หยาบ"
                    required
                    aria-required="true"
                    aria-invalid={fieldErrors[`items.${index}.texture`] ? true : undefined}
                    aria-describedby={fieldErrors[`items.${index}.texture`] ? fieldErrorId(`item-${index}-texture`) : undefined}
                  />
                  {fieldErrors[`items.${index}.texture`] ? (
                    <p id={fieldErrorId(`item-${index}-texture`)} role="alert" style={{ margin: '4px 0 0', fontSize: 11, fontWeight: 700, color: '#ef4444' }}>{fieldErrors[`items.${index}.texture`]}</p>
                  ) : null}
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 12 }}>ขนาด *</span>
                  <input
                    id={`item-${index}-size`}
                    ref={(el) => { fieldRefs.current[`items.${index}.size`] = el; }}
                    value={item.size}
                    onChange={(e) => updateItem(index, 'size', e.target.value)}
                    placeholder="เช่น 60x60, 30x60 ซม."
                    required
                    aria-required="true"
                    aria-invalid={fieldErrors[`items.${index}.size`] ? true : undefined}
                    aria-describedby={fieldErrors[`items.${index}.size`] ? fieldErrorId(`item-${index}-size`) : undefined}
                  />
                  {fieldErrors[`items.${index}.size`] ? (
                    <p id={fieldErrorId(`item-${index}-size`)} role="alert" style={{ margin: '4px 0 0', fontSize: 11, fontWeight: 700, color: '#ef4444' }}>{fieldErrors[`items.${index}.size`]}</p>
                  ) : null}
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
                        id={`item-${index}-qty`}
                        ref={(el) => { fieldRefs.current[`items.${index}.qty`] = el; }}
                        onChange={(e) => updateItem(index, 'qty', e.target.value)}
                        placeholder="จำนวนแผ่น" required
                        aria-required="true"
                        aria-invalid={fieldErrors[`items.${index}.qty`] ? true : undefined}
                        aria-describedby={fieldErrors[`items.${index}.qty`] ? fieldErrorId(`item-${index}-qty`) : undefined}
                      />
                      {fieldErrors[`items.${index}.qty`] ? (
                        <p id={fieldErrorId(`item-${index}-qty`)} role="alert" style={{ margin: '4px 0 0', fontSize: 11, fontWeight: 700, color: '#ef4444' }}>{fieldErrors[`items.${index}.qty`]}</p>
                      ) : null}
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
                        id={`item-${index}-qtySqm`}
                        ref={(el) => { fieldRefs.current[`items.${index}.qtySqm`] = el; }}
                        onChange={(e) => updateItem(index, 'qtySqm', e.target.value)}
                        placeholder="เช่น 120.500" required
                        aria-required="true"
                        aria-invalid={fieldErrors[`items.${index}.qtySqm`] ? true : undefined}
                        aria-describedby={fieldErrors[`items.${index}.qtySqm`] ? fieldErrorId(`item-${index}-qtySqm`) : undefined}
                      />
                      {fieldErrors[`items.${index}.qtySqm`] ? (
                        <p id={fieldErrorId(`item-${index}-qtySqm`)} role="alert" style={{ margin: '4px 0 0', fontSize: 11, fontWeight: 700, color: '#ef4444' }}>{fieldErrors[`items.${index}.qtySqm`]}</p>
                      ) : null}
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

        {error ? <div className="form-error span-2" role="alert">{error}</div> : null}
      </form>
    </Modal>
  );
}
