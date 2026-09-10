import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { entryChannelLabel } from '../../utils/format.js';

// ช่องทางรับงาน (owner ask 2026-09-10): the same four codes th.co.glr.hr.ticket.EntryChannel
// stores, in the order the spec lists them. Deliberately includes UNSPECIFIED as a pickable
// option and defaults to it -- unlike TicketCreateModal.jsx's own ENTRY_CHANNEL_OPTIONS (which
// never offers it, per that file's own comment), this card is reached earlier in the flow, often
// before the rep even knows how the lead came in.
const ENTRY_CHANNEL_CODES = ['UNSPECIFIED', 'DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT'];

function emptyNewCustomer() {
  return { name: '', taxId: '', address: '', phone: '' };
}

function emptyNewContact() {
  return { firstName: '', lastName: '', phone: '' };
}

/**
 * "ลูกค้าและโครงการ" -- the inline deal-creation card at the top of QuotationEditorPage when a
 * sales rep opens `/quotations/new` with no `?ticket=` (owner ask 2026-09-10,
 * `inline-deal-spec.md`). Lets the rep pick or create a customer, then a project under it, then
 * (optionally) a contact, and pick the entry channel -- everything TicketService.create needs
 * that isn't already implied by the quotation items themselves. The parent owns the selected
 * values (`value`/`onChange`, the same controlled-field contract QuotationItemRow.jsx uses for
 * its own patch-upward pattern); this component owns only its own transient search/create UI
 * state, exactly like QuotationItemRow's catalog typeahead.
 */
export function DealCustomerCard({ value, onChange, errors, showToast }) {
  const { customer, project, contact, entryChannel } = value;

  // ── ลูกค้า typeahead ──────────────────────────────────────────────────────────────────────
  const [customerSearch, setCustomerSearch] = useState('');
  const [customerResults, setCustomerResults] = useState([]);
  const [customerOpen, setCustomerOpen] = useState(false);
  const [customerLoading, setCustomerLoading] = useState(false);
  // #L2-style: a PER-INSTANCE debounce timer ref, not a module-level singleton -- see
  // QuotationItemRow.jsx's own comment on why a shared timer is the wrong shape once more than
  // one of these could ever be on screen.
  const customerTimer = useRef(null);
  useEffect(() => () => clearTimeout(customerTimer.current), []);

  const [showNewCustomer, setShowNewCustomer] = useState(false);
  const [newCustomer, setNewCustomer] = useState(emptyNewCustomer());
  const [savingCustomer, setSavingCustomer] = useState(false);

  // ── โครงการ / ผู้ติดต่อ, loaded once a customer is selected ─────────────────────────────────
  const [projectOptions, setProjectOptions] = useState([]);
  const [projectsLoading, setProjectsLoading] = useState(false);
  const [showNewProject, setShowNewProject] = useState(false);
  const [newProjectName, setNewProjectName] = useState('');
  const [savingProject, setSavingProject] = useState(false);

  const [contactOptions, setContactOptions] = useState([]);
  const [showNewContact, setShowNewContact] = useState(false);
  const [newContact, setNewContact] = useState(emptyNewContact());
  const [savingContact, setSavingContact] = useState(false);

  useEffect(() => {
    if (!customer) {
      setProjectOptions([]);
      setContactOptions([]);
      return undefined;
    }
    let cancelled = false;
    setProjectsLoading(true);
    Promise.all([api.customers.projects(customer.id), api.customers.contacts(customer.id)])
      .then(([pr, cr]) => {
        if (cancelled) return;
        setProjectOptions(pr.projects ?? []);
        setContactOptions(cr.contacts ?? []);
      })
      .catch(() => {
        if (cancelled) return;
        setProjectOptions([]);
        setContactOptions([]);
      })
      .finally(() => {
        if (!cancelled) setProjectsLoading(false);
      });
    return () => { cancelled = true; };
  }, [customer]);

  function debouncedCustomerSearch(q) {
    clearTimeout(customerTimer.current);
    customerTimer.current = setTimeout(async () => {
      setCustomerLoading(true);
      try {
        const res = await api.customers.search(q);
        setCustomerResults(res.customers ?? []);
      } catch {
        // typeahead only -- a failed search just shows no results, not an error toast
      } finally {
        setCustomerLoading(false);
      }
    }, 280);
  }

  function selectCustomer(next) {
    // Picking a NEW customer always resets โครงการ/ผู้ติดต่อ -- either was scoped to the
    // previous customer and cannot carry over.
    onChange({ customer: next, project: null, contact: null });
    setCustomerSearch('');
    setCustomerResults([]);
    setCustomerOpen(false);
    setShowNewCustomer(false);
  }

  function clearCustomer() {
    onChange({ customer: null, project: null, contact: null });
  }

  async function handleCreateCustomer() {
    if (!newCustomer.name.trim()) return;
    setSavingCustomer(true);
    try {
      const res = await api.customers.create({
        name: newCustomer.name.trim(),
        taxId: newCustomer.taxId.trim() || null,
        address: newCustomer.address.trim() || null,
        phone: newCustomer.phone.trim() || null,
      });
      selectCustomer(res.customer);
      setNewCustomer(emptyNewCustomer());
    } catch (error) {
      showToast?.('error', error.message || 'เพิ่มลูกค้าใหม่ไม่สำเร็จ');
    } finally {
      setSavingCustomer(false);
    }
  }

  function selectProject(next) {
    onChange({ project: next });
  }

  async function handleCreateProject() {
    if (!customer || !newProjectName.trim()) return;
    setSavingProject(true);
    try {
      const res = await api.customers.createProject(customer.id, { name: newProjectName.trim() });
      setProjectOptions((prev) => [...prev, res.project]);
      selectProject(res.project);
      setNewProjectName('');
      setShowNewProject(false);
    } catch (error) {
      showToast?.('error', error.message || 'เพิ่มโครงการใหม่ไม่สำเร็จ');
    } finally {
      setSavingProject(false);
    }
  }

  function selectContact(next) {
    onChange({ contact: next });
  }

  async function handleCreateContact() {
    if (!customer || !newContact.firstName.trim()) return;
    setSavingContact(true);
    try {
      const res = await api.customers.createContact(customer.id, {
        firstName: newContact.firstName.trim(),
        lastName: newContact.lastName.trim() || null,
        phone: newContact.phone.trim() || null,
      });
      setContactOptions((prev) => [...prev, res.contact]);
      selectContact(res.contact);
      setNewContact(emptyNewContact());
      setShowNewContact(false);
    } catch (error) {
      showToast?.('error', error.message || 'เพิ่มผู้ติดต่อใหม่ไม่สำเร็จ');
    } finally {
      setSavingContact(false);
    }
  }

  return (
    <Panel title="ลูกค้าและโครงการ">
      <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
        <div className="relative">
          <FormField label="ลูกค้า" htmlFor="deal-customer" required error={errors?.customer}>
            {customer ? (
              <div className="flex items-center gap-2 rounded-md border border-border-muted bg-surface-muted px-2.5 py-1.5 text-sm">
                <span className="flex-1">
                  <strong>{customer.name}</strong>
                  {customer.taxId ? <span className="ml-1.5 text-xs text-text-muted">({customer.taxId})</span> : null}
                </span>
                <button
                  type="button"
                  onClick={clearCustomer}
                  className="cursor-pointer border-0 bg-transparent p-0 text-text-faint"
                  aria-label="ล้างลูกค้าที่เลือก"
                >
                  <Icon name="close" size={14} />
                </button>
              </div>
            ) : (
              <input
                id="deal-customer"
                autoComplete="off"
                placeholder="พิมพ์ค้นหาชื่อบริษัท / ลูกค้า…"
                value={customerSearch}
                onChange={(e) => {
                  setCustomerSearch(e.target.value);
                  setCustomerOpen(true);
                  debouncedCustomerSearch(e.target.value);
                }}
                onFocus={() => { setCustomerOpen(true); debouncedCustomerSearch(customerSearch); }}
                onBlur={() => setTimeout(() => setCustomerOpen(false), 150)}
              />
            )}
          </FormField>
          {!customer && customerOpen ? (
            <ul className="absolute z-10 mt-1 max-h-64 w-full overflow-auto rounded-md border border-border bg-surface shadow-[var(--shadow-lg-heavy)]">
              {customerLoading ? <li className="px-3 py-2 text-xs text-text-muted">กำลังค้นหา…</li> : null}
              {!customerLoading && customerResults.length === 0 ? (
                <li className="px-3 py-2 text-xs text-text-muted">ไม่พบข้อมูล</li>
              ) : null}
              {customerResults.map((c) => (
                <li key={c.id}>
                  <button
                    type="button"
                    className="block w-full px-3 py-2 text-left text-xs hover:bg-surface-hover"
                    onMouseDown={(e) => { e.preventDefault(); selectCustomer(c); }}
                  >
                    <strong>{c.name}</strong>
                    {c.taxId ? <span className="block text-2xs text-text-muted">เลขภาษี {c.taxId}</span> : null}
                  </button>
                </li>
              ))}
              <li className="border-t border-border-subtle bg-surface-muted">
                <button
                  type="button"
                  className="flex w-full items-center gap-1.5 px-3 py-2 text-left text-xs font-bold text-link"
                  onMouseDown={(e) => {
                    e.preventDefault();
                    // F3 fix: seed the add-customer panel's name from what the rep already typed
                    // into the typeahead, instead of always opening on emptyNewCustomer()'s ''
                    // and making them retype a name they just typed to get here.
                    setNewCustomer((p) => ({ ...p, name: customerSearch.trim() }));
                    setShowNewCustomer(true);
                    setCustomerOpen(false);
                  }}
                >
                  <Icon name="plus" size={13} />
                  เพิ่มลูกค้าใหม่
                </button>
              </li>
            </ul>
          ) : null}
        </div>

        <FormField label="โครงการ" htmlFor="deal-project" required error={errors?.project}>
          <select
            id="deal-project"
            disabled={!customer}
            value={project?.id ?? ''}
            onChange={(e) => {
              if (e.target.value === '__new__') { setShowNewProject(true); return; }
              const found = projectOptions.find((p) => String(p.id) === e.target.value) ?? null;
              selectProject(found);
            }}
          >
            <option value="">{customer ? (projectsLoading ? 'กำลังโหลด…' : '- เลือกโครงการ -') : 'เลือกลูกค้าก่อน'}</option>
            {projectOptions.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            {customer ? <option value="__new__">+ โครงการใหม่</option> : null}
          </select>
        </FormField>
      </div>

      {showNewCustomer && !customer ? (
        <div className="mt-3 flex flex-col gap-2 rounded-md border border-info-border bg-info-row-active p-3">
          <p className="m-0 text-xs font-bold text-info">เพิ่มลูกค้าใหม่</p>
          <div className="grid grid-cols-2 gap-2 mobile:grid-cols-1">
            <label className="col-span-2 m-0 mobile:col-span-1">
              <span className="text-2xs">ชื่อบริษัท / ลูกค้า *</span>
              <input value={newCustomer.name} onChange={(e) => setNewCustomer((p) => ({ ...p, name: e.target.value }))} placeholder="บริษัท … จำกัด" />
            </label>
            <label className="m-0">
              <span className="text-2xs">เลขประจำตัวผู้เสียภาษี</span>
              <input value={newCustomer.taxId} onChange={(e) => setNewCustomer((p) => ({ ...p, taxId: e.target.value }))} placeholder="0105xxxxxxxxx" />
            </label>
            <label className="m-0">
              <span className="text-2xs">โทรศัพท์</span>
              <input value={newCustomer.phone} onChange={(e) => setNewCustomer((p) => ({ ...p, phone: e.target.value }))} placeholder="02-xxx-xxxx" />
            </label>
            <label className="col-span-2 m-0 mobile:col-span-1">
              <span className="text-2xs">ที่อยู่</span>
              <input value={newCustomer.address} onChange={(e) => setNewCustomer((p) => ({ ...p, address: e.target.value }))} placeholder="ที่อยู่บริษัท" />
            </label>
          </div>
          <div className="mt-1 flex gap-2">
            <Button variant="primary" size="sm" loading={savingCustomer} disabled={!newCustomer.name.trim() || savingCustomer} onClick={handleCreateCustomer}>
              บันทึกลูกค้าใหม่
            </Button>
            <Button variant="secondary" size="sm" onClick={() => { setShowNewCustomer(false); setNewCustomer(emptyNewCustomer()); }}>
              ยกเลิก
            </Button>
          </div>
        </div>
      ) : null}

      {showNewProject && customer ? (
        <div className="mt-3 flex flex-col gap-2 rounded-md border border-info-border bg-info-row-active p-3">
          <p className="m-0 text-xs font-bold text-info">โครงการใหม่</p>
          <div className="flex flex-wrap items-end gap-2">
            <label className="m-0 min-w-[200px] flex-1">
              <span className="text-2xs">ชื่อโครงการ *</span>
              <input value={newProjectName} onChange={(e) => setNewProjectName(e.target.value)} placeholder="ชื่อโครงการ" />
            </label>
            <Button variant="primary" size="sm" loading={savingProject} disabled={!newProjectName.trim() || savingProject} onClick={handleCreateProject}>
              เพิ่มโครงการ
            </Button>
            <Button variant="secondary" size="sm" onClick={() => { setShowNewProject(false); setNewProjectName(''); }}>
              ยกเลิก
            </Button>
          </div>
        </div>
      ) : null}

      <div className="mt-3 grid grid-cols-2 gap-3 mobile:grid-cols-1">
        <FormField label="ผู้ติดต่อ (ไม่บังคับ)" htmlFor="deal-contact">
          <select
            id="deal-contact"
            disabled={!customer}
            value={contact?.id ?? ''}
            onChange={(e) => {
              if (e.target.value === '__new__') { setShowNewContact(true); return; }
              const found = contactOptions.find((c) => String(c.id) === e.target.value) ?? null;
              selectContact(found);
            }}
          >
            <option value="">{customer ? '- ไม่ระบุ -' : 'เลือกลูกค้าก่อน'}</option>
            {contactOptions.map((c) => (
              <option key={c.id} value={c.id}>{`${c.firstName} ${c.lastName ?? ''}`.trim()}</option>
            ))}
            {customer ? <option value="__new__">+ เพิ่มผู้ติดต่อใหม่</option> : null}
          </select>
        </FormField>

        <div>
          <span className="mb-1 block text-xs">ช่องทางรับงาน</span>
          <div className="flex flex-wrap gap-2">
            {ENTRY_CHANNEL_CODES.map((code) => (
              <button
                key={code}
                type="button"
                aria-pressed={entryChannel === code}
                className={`min-h-[38px] rounded-md border px-3 text-xs font-bold ${entryChannel === code ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'}`}
                onClick={() => onChange({ entryChannel: code })}
              >
                {entryChannelLabel(code).label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {showNewContact && customer ? (
        <div className="mt-3 flex flex-col gap-2 rounded-md border border-info-border bg-info-row-active p-3">
          <p className="m-0 text-xs font-bold text-info">เพิ่มผู้ติดต่อใหม่</p>
          <div className="grid grid-cols-3 gap-2 mobile:grid-cols-1">
            <label className="m-0">
              <span className="text-2xs">ชื่อ *</span>
              <input value={newContact.firstName} onChange={(e) => setNewContact((p) => ({ ...p, firstName: e.target.value }))} />
            </label>
            <label className="m-0">
              <span className="text-2xs">นามสกุล</span>
              <input value={newContact.lastName} onChange={(e) => setNewContact((p) => ({ ...p, lastName: e.target.value }))} />
            </label>
            <label className="m-0">
              <span className="text-2xs">โทรศัพท์</span>
              <input value={newContact.phone} onChange={(e) => setNewContact((p) => ({ ...p, phone: e.target.value }))} />
            </label>
          </div>
          <div className="mt-1 flex gap-2">
            <Button variant="primary" size="sm" loading={savingContact} disabled={!newContact.firstName.trim() || savingContact} onClick={handleCreateContact}>
              เพิ่มผู้ติดต่อ
            </Button>
            <Button variant="secondary" size="sm" onClick={() => { setShowNewContact(false); setNewContact(emptyNewContact()); }}>
              ยกเลิก
            </Button>
          </div>
        </div>
      ) : null}
    </Panel>
  );
}
