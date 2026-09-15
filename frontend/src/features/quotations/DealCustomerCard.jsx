import { useEffect, useRef, useState } from 'react';
import { ThaiAddressFields, emptyThaiAddress, completeThaiAddress } from '../locations/ThaiAddressFields.jsx';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { entryChannelLabel } from '../../utils/format.js';
import { CustomerDetailsFields } from './CustomerDetailsFields.jsx';
import { QuotationContactPicker } from './QuotationContactPicker.jsx';

// ช่องทางรับงาน (owner ask 2026-09-10): the same four codes th.co.glr.hr.ticket.EntryChannel
// stores, in the order the spec lists them. Deliberately includes UNSPECIFIED as a pickable
// option and defaults to it -- unlike TicketCreateModal.jsx's own ENTRY_CHANNEL_OPTIONS (which
// never offers it, per that file's own comment), this card is reached earlier in the flow, often
// before the rep even knows how the lead came in.
const ENTRY_CHANNEL_CODES = ['UNSPECIFIED', 'DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT'];

function emptyNewCustomer() {
  return { name: '', taxId: '', phone: '', address: '', ...emptyThaiAddress() };
}

/**
 * "ลูกค้าและโครงการ" -- the inline deal-creation card at the top of QuotationEditorPage when a
 * sales rep opens `/quotations/new` with no `?ticket=` (owner ask 2026-09-10,
 * `inline-deal-spec.md`). Lets the rep pick or create a customer, then a project under it, then
 * the ผู้สั่งซื้อ (REQUIRED since owner feedback F2, 2026-09-10 -- rendered by the shared
 * QuotationContactPicker, which the editor also uses on the paths where this card is absent), and
 * pick the entry channel -- everything TicketService.create needs
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
  // Foreign-customer toggle inside เพิ่มลูกค้าใหม่ (bug fix, prod QT-2026-0039-1): a customer
  // outside Thailand has no จังหวัด/เขต/แขวง to pick, so ThaiAddressFields' three `required`
  // comboboxes could never be satisfied and the rep had no way to save one — the ticket that
  // shipped got its address hand-typed into the (Thai-only) addressLine field instead, printing
  // as "---Vietnam--- แขวงคลองตันเหนือ เขตวัฒนา กรุงเทพมหานคร 10110". Checking this box swaps the
  // structured fields for a plain textarea and, on save, sends a payload with every structured
  // field OMITTED (not merely blank -- CustomerController#structured / mockApi's own
  // structuredCustomerAddress both treat a present-but-empty string as "structured", matching
  // `Objects::nonNull`) so the backend takes its already-existing non-structured
  // `customers.create(name, taxId, address, branch, phone)` path (CustomerController ~73-77).
  const [foreignCustomer, setForeignCustomer] = useState(false);

  // F7 (2026-09-10) + owner 2026-09-11: the SELECTED customer's เลขที่ผู้เสียภาษี / โทร. / ที่อยู่
  // are edited through the shared CustomerDetailsFields (see its own doc) — the editor renders the
  // same component on the paths where this card is absent.

  // ── โครงการ / ผู้ติดต่อ, loaded once a customer is selected ─────────────────────────────────
  const [projectOptions, setProjectOptions] = useState([]);
  const [projectsLoading, setProjectsLoading] = useState(false);
  const [showNewProject, setShowNewProject] = useState(false);
  const [newProjectName, setNewProjectName] = useState('');
  const [savingProject, setSavingProject] = useState(false);

  // โครงการ type-ahead (owner testing feedback, 2026-09-11: "as the project list grows, typing
  // the first few characters should filter it down"). The full list is already fetched once per
  // customer above (`api.customers.projects`), so this filters CLIENT-SIDE over that already-loaded
  // array rather than adding a search endpoint -- there is no per-keystroke request to debounce.
  // Interaction mirrors the ลูกค้า combobox above (input + `role="listbox"` popup, a chip once
  // something is picked) but adds real keyboard support, which that one does not have: arrow keys
  // move a highlighted row (tracked as an index, not DOM focus, so typing and navigating both stay
  // on the input -- the standard ARIA 1.2 combobox pattern), Enter picks the highlighted row (or
  // opens "เพิ่มโครงการใหม่" when that row is highlighted), Escape closes the popup without
  // picking anything.
  const [projectSearch, setProjectSearch] = useState('');
  const [projectOpen, setProjectOpen] = useState(false);
  const [projectActiveIndex, setProjectActiveIndex] = useState(-1);
  const trimmedProjectSearch = projectSearch.trim().toLowerCase();
  const filteredProjects = trimmedProjectSearch
    ? projectOptions.filter((p) => p.name.toLowerCase().includes(trimmedProjectSearch))
    : projectOptions;
  // The first keyboard row is always "+ เพิ่มโครงการใหม่", followed by the real projects.
  const projectRowCount = filteredProjects.length + 1;

  function openProjectDropdown() {
    if (!customer) return;
    setProjectOpen(true);
  }

  function closeProjectDropdown() {
    setProjectOpen(false);
    setProjectActiveIndex(-1);
  }

  function selectProject(next) {
    onChange({ project: next });
    setProjectSearch('');
    closeProjectDropdown();
  }

  function openNewProjectFromDropdown() {
    // F3-style seed, same idea as เพิ่มลูกค้าใหม่ below: start the new-project name from whatever
    // the rep already typed into the filter instead of a blank field.
    setNewProjectName(projectSearch.trim());
    setShowNewProject(true);
    closeProjectDropdown();
  }

  function handleProjectKeyDown(e) {
    if (!customer) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!projectOpen) { openProjectDropdown(); return; }
      setProjectActiveIndex((i) => (i + 1) % projectRowCount);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (!projectOpen) { openProjectDropdown(); return; }
      setProjectActiveIndex((i) => (i - 1 + projectRowCount) % projectRowCount);
    } else if (e.key === 'Enter') {
      if (!projectOpen || projectActiveIndex === -1) return;
      e.preventDefault();
      if (projectActiveIndex === 0) {
        openNewProjectFromDropdown();
      } else {
        selectProject(filteredProjects[projectActiveIndex - 1]);
      }
    } else if (e.key === 'Escape' && projectOpen) {
      e.preventDefault();
      closeProjectDropdown();
    }
  }

  // ผู้สั่งซื้อ itself now lives in QuotationContactPicker (owner feedback F2, 2026-09-10) --
  // including its own contact fetch -- because the editor needs the same control on the
  // `?ticket=` and existing-DRAFT paths, where this card is not rendered at all.
  useEffect(() => {
    if (!customer) {
      setProjectOptions([]);
      return undefined;
    }
    let cancelled = false;
    setProjectsLoading(true);
    api.customers.projects(customer.id)
      .then((pr) => { if (!cancelled) setProjectOptions(pr.projects ?? []); })
      .catch(() => { if (!cancelled) setProjectOptions([]); })
      .finally(() => { if (!cancelled) setProjectsLoading(false); });
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
    // previous customer and cannot carry over. Also drops any in-progress โครงการ filter text/popup
    // state, which otherwise would go on filtering the NEW customer's project list.
    onChange({ customer: next, project: null, contact: null });
    setCustomerSearch('');
    setCustomerResults([]);
    setCustomerOpen(false);
    setShowNewCustomer(false);
    setProjectSearch('');
    closeProjectDropdown();
  }

  function clearCustomer() {
    onChange({ customer: null, project: null, contact: null });
    setProjectSearch('');
    closeProjectDropdown();
  }

  function closeNewCustomer() {
    if (savingCustomer) return;
    setShowNewCustomer(false);
    setNewCustomer(emptyNewCustomer());
    setForeignCustomer(false);
  }

  function closeNewProject() {
    if (savingProject) return;
    setShowNewProject(false);
    setNewProjectName('');
  }

  async function handleCreateCustomer() {
    if (!newCustomer.name.trim()) return;
    if (!foreignCustomer && !completeThaiAddress(newCustomer)) return;
    setSavingCustomer(true);
    try {
      // Foreign mode omits every structured field entirely (not just blanks them) -- see the
      // foreignCustomer state comment above for why that distinction is what actually reaches
      // the backend's non-structured create() path.
      const res = await api.customers.create(foreignCustomer ? {
        name: newCustomer.name.trim(),
        taxId: newCustomer.taxId.trim() || null,
        address: newCustomer.address.trim() || null,
        phone: newCustomer.phone.trim() || null,
      } : {
        name: newCustomer.name.trim(),
        taxId: newCustomer.taxId.trim() || null,
        addressLine: newCustomer.addressLine.trim(),
        provinceCode: newCustomer.provinceCode,
        districtCode: newCustomer.districtCode,
        subdistrictCode: newCustomer.subdistrictCode,
        postalCode: newCustomer.postalCode || null,
        phone: newCustomer.phone.trim() || null,
      });
      selectCustomer(res.customer);
      setNewCustomer(emptyNewCustomer());
      setForeignCustomer(false);
    } catch (error) {
      showToast?.('error', error.message || 'เพิ่มลูกค้าใหม่ไม่สำเร็จ');
    } finally {
      setSavingCustomer(false);
    }
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
            // `list-none pl-0`: this renders as a real bulleted list without it — measured
            // list-style-type `disc` with a 40 px padding-inline-start, so every suggestion sat
            // behind a bullet and indented off the popup's left edge. The `role="listbox"` /
            // `role="option"` pair is the other half: a suggestion popup announced as a plain list
            // gives a screen-reader user no way to know these are choices for the field above.
            <ul
              id="customer-typeahead-list"
              role="listbox"
              aria-label="ผลการค้นหาลูกค้า"
              className="absolute z-10 mt-1 max-h-64 w-full list-none overflow-auto rounded-md border border-border bg-surface pl-0 shadow-[var(--shadow-lg-heavy)]"
            >
              <li className="sticky top-0 z-10 border-b border-border-subtle bg-surface-muted">
                <button
                  type="button"
                  className="flex w-full items-center gap-1.5 px-3 py-2 text-left text-xs font-bold text-link"
                  onMouseDown={(e) => {
                    e.preventDefault();
                    // Keep the name the rep already typed in the search box when opening the
                    // create form, so adding a new customer does not require retyping it.
                    setNewCustomer((p) => ({ ...p, name: customerSearch.trim() }));
                    setShowNewCustomer(true);
                    setCustomerOpen(false);
                  }}
                >
                  <Icon name="plus" size={13} />
                  เพิ่มลูกค้าใหม่
                </button>
              </li>
              {customerLoading ? <li role="presentation" className="px-3 py-2 text-xs text-text-muted">กำลังค้นหา…</li> : null}
              {!customerLoading && customerResults.length === 0 ? (
                <li role="presentation" className="px-3 py-2 text-xs text-text-muted">ไม่พบข้อมูล</li>
              ) : null}
              {customerResults.map((c) => (
                // The OPTION is the button, not the <li>: a listbox child must be an option, and
                // putting the role on the wrapper would nest an interactive control inside one.
                <li key={c.id} role="presentation">
                  <button
                    type="button"
                    role="option"
                    aria-selected={false}
                    className="block w-full px-3 py-2 text-left text-xs hover:bg-surface-hover"
                    onMouseDown={(e) => { e.preventDefault(); selectCustomer(c); }}
                  >
                    <strong>{c.name}</strong>
                    {c.taxId ? <span className="block text-2xs text-text-muted">เลขภาษี {c.taxId}</span> : null}
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </div>

        <div className="relative">
          <FormField label="โครงการ" htmlFor="deal-project" required error={errors?.project}>
            {project ? (
              <div className="flex items-center gap-2 rounded-md border border-border-muted bg-surface-muted px-2.5 py-1.5 text-sm">
                <span className="flex-1">{project.name}</span>
                <button
                  type="button"
                  onClick={() => { onChange({ project: null }); setProjectSearch(''); }}
                  className="cursor-pointer border-0 bg-transparent p-0 text-text-faint"
                  aria-label="ล้างโครงการที่เลือก"
                >
                  <Icon name="close" size={14} />
                </button>
              </div>
            ) : (
              <input
                id="deal-project"
                role="combobox"
                autoComplete="off"
                disabled={!customer}
                aria-expanded={projectOpen}
                aria-controls="project-typeahead-list"
                aria-autocomplete="list"
                aria-activedescendant={projectOpen && projectActiveIndex >= 0
                  ? (projectActiveIndex === 0 ? 'project-new-option' : `project-option-${projectActiveIndex}`)
                  : undefined}
                placeholder={customer ? (projectsLoading ? 'กำลังโหลด…' : 'พิมพ์ค้นหาโครงการ…') : 'เลือกลูกค้าก่อน'}
                value={projectSearch}
                onChange={(e) => { setProjectSearch(e.target.value); setProjectActiveIndex(-1); openProjectDropdown(); }}
                onFocus={openProjectDropdown}
                onBlur={() => setTimeout(closeProjectDropdown, 150)}
                onKeyDown={handleProjectKeyDown}
              />
            )}
          </FormField>
          {!project && projectOpen && customer ? (
            <ul
              id="project-typeahead-list"
              role="listbox"
              aria-label="ผลการค้นหาโครงการ"
              className="absolute z-10 mt-1 max-h-64 w-full list-none overflow-auto rounded-md border border-border bg-surface pl-0 shadow-[var(--shadow-lg-heavy)]"
            >
              <li className="sticky top-0 z-10 border-b border-border-subtle bg-surface-muted">
                <button
                  type="button"
                  id="project-new-option"
                  role="option"
                  aria-selected={projectActiveIndex === 0}
                  className="flex w-full items-center gap-1.5 px-3 py-2 text-left text-xs font-bold text-link"
                  onMouseEnter={() => setProjectActiveIndex(0)}
                  onMouseDown={(e) => { e.preventDefault(); openNewProjectFromDropdown(); }}
                >
                  <Icon name="plus" size={13} />
                  เพิ่มโครงการใหม่
                </button>
              </li>
              {projectsLoading ? <li role="presentation" className="px-3 py-2 text-xs text-text-muted">กำลังโหลด…</li> : null}
              {!projectsLoading && filteredProjects.length === 0 ? (
                <li role="presentation" className="px-3 py-2 text-xs text-text-muted">ไม่พบโครงการ</li>
              ) : null}
              {filteredProjects.map((p, idx) => (
                <li key={p.id} role="presentation">
                  <button
                    type="button"
                    id={`project-option-${idx + 1}`}
                    role="option"
                    aria-selected={idx + 1 === projectActiveIndex}
                    className={`block w-full px-3 py-2 text-left text-xs hover:bg-surface-hover ${idx + 1 === projectActiveIndex ? 'bg-surface-hover' : ''}`}
                    onMouseEnter={() => setProjectActiveIndex(idx + 1)}
                    onMouseDown={(e) => { e.preventDefault(); selectProject(p); }}
                  >
                    {p.name}
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      </div>

      {customer ? (
        // Present, prefilled and editable the moment a customer is selected — deliberately NOT
        // behind an "แก้ไข" affordance, because the point is that the rep sees what the document is
        // about to print, and a repeat customer arrives already filled. Saved on blur; none is
        // required.
        <div className="mt-3">
          <CustomerDetailsFields customer={customer} onChange={(next) => onChange({ customer: next })} showToast={showToast} />
        </div>
      ) : null}

      {showNewCustomer && !customer ? (
        <Modal title="เพิ่มลูกค้าใหม่" onClose={closeNewCustomer}>
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
            <label className="col-span-full m-0 flex items-center gap-1.5 text-2xs">
              <input
                type="checkbox"
                checked={foreignCustomer}
                disabled={savingCustomer}
                onChange={(e) => setForeignCustomer(e.target.checked)}
              />
              ลูกค้าต่างประเทศ / ที่อยู่นอกประเทศไทย
            </label>
            <div className="col-span-full">
              {foreignCustomer ? (
                <FormField label="ที่อยู่" htmlFor="new-customer-foreign-address">
                  <textarea
                    id="new-customer-foreign-address"
                    rows={3}
                    className="min-h-20"
                    maxLength={2000}
                    value={newCustomer.address}
                    disabled={savingCustomer}
                    placeholder="ที่อยู่ลูกค้า (ภาษาใดก็ได้)"
                    onChange={(e) => setNewCustomer((p) => ({ ...p, address: e.target.value }))}
                  />
                </FormField>
              ) : (
                <ThaiAddressFields value={newCustomer} onChange={(patch) => setNewCustomer((prev) => ({ ...prev, ...patch }))} disabled={savingCustomer} />
              )}
            </div>
          </div>
          <div className="mt-4 flex flex-wrap justify-end gap-2">
            <Button variant="primary" size="sm" loading={savingCustomer} disabled={!newCustomer.name.trim() || (!foreignCustomer && !completeThaiAddress(newCustomer)) || savingCustomer} onClick={handleCreateCustomer}>
              บันทึกลูกค้าใหม่
            </Button>
            <Button variant="secondary" size="sm" disabled={savingCustomer} onClick={closeNewCustomer}>
              ยกเลิก
            </Button>
          </div>
        </Modal>
      ) : null}

      {showNewProject && customer ? (
        <Modal title="โครงการใหม่" onClose={closeNewProject}>
          <div className="flex flex-col gap-3">
            <label className="m-0">
              <span className="text-2xs">ชื่อโครงการ *</span>
              <input value={newProjectName} onChange={(e) => setNewProjectName(e.target.value)} placeholder="ชื่อโครงการ" />
            </label>
          </div>
          <div className="mt-4 flex flex-wrap justify-end gap-2">
            <Button variant="primary" size="sm" loading={savingProject} disabled={!newProjectName.trim() || savingProject} onClick={handleCreateProject}>
              เพิ่มโครงการ
            </Button>
            <Button variant="secondary" size="sm" disabled={savingProject} onClick={closeNewProject}>
              ยกเลิก
            </Button>
          </div>
        </Modal>
      ) : null}

      <div className="mt-3 grid grid-cols-2 gap-3 mobile:grid-cols-1">
        <QuotationContactPicker
          customerId={customer?.id ?? null}
          customerName={customer?.name ?? null}
          value={contact}
          onChange={(next) => onChange({ contact: next })}
          error={errors?.contact}
          showToast={showToast}
        />

        <div>
          <span className="mb-1 block text-xs">ช่องทางรับงาน</span>
          <div className="flex flex-wrap gap-2">
            {ENTRY_CHANNEL_CODES.map((code) => (
              <button
                key={code}
                type="button"
                aria-pressed={entryChannel === code}
                className={`min-h-[38px] mobile:min-h-[44px] rounded-md border px-3 text-xs font-bold ${entryChannel === code ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'}`}
                onClick={() => onChange({ entryChannel: code })}
              >
                {entryChannelLabel(code).label}
              </button>
            ))}
          </div>
        </div>
      </div>

    </Panel>
  );
}
