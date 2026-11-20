import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import {
  addDaysIso, bangkokTodayIso, dealStageLabel, OFFERED_TO_LABEL, ticketPriorityLabel,
} from '../../utils/format.js';
// Imported, not modified: the quotation editor's ผู้ออกแบบ directory picker, with its own "+ เพิ่มผู้ออกแบบใหม่".
import { DesignerPicker } from '../quotations/DesignerPicker.jsx';
import {
  canEditDealItemsAfterCreate, CreateDealItems, createItemsPayload, validateCreateItems,
} from './CreateDealItems.jsx';
import { FieldError, FieldLabel, PickChips, RadioCards } from './dealDialogParts.jsx';
import { ESTIMATE_ERROR, parseEstimatedValue } from './dealEstimate.js';
import { PRE_DEPOSIT_ERROR, PreDepositField, parsePreDeposit } from './delivery/PreDepositField.jsx';

/**
 * สร้างงานใหม่ — the owner-approved short form (drawing m-create, 2026-11-16): ชื่อโครงการ, ลูกค้า / บริษัท, ผู้ออกแบบ,
 * มูลค่าโดยประมาณ (ไม่บังคับ) and เริ่มที่ S1 / S2. "Fill in what you know now; the rest fills from the quotation."
 *
 * What the old four-section accordion collected and the drawing drops, and where it lives now:
 *   - รายการสินค้า → for a `sales` creator, the deal page's รายการสินค้า panel (DealItemsPanel). Every OTHER creator
 *     cannot edit items afterwards (TicketService.editItems is sales-only), so for them the form keeps a collapsed,
 *     optional รายการสินค้า section (CreateDealItems) and sends the rows exactly as the old form did (fix round 1).
 *   - ช่องทาง (เสนอแก่) → NOT dropped: still required (owner rule 2026-09-30, kept by fix round 1 ruling 1), as
 *     three compact chips.
 *   - detailed customer / project data → the customer "add new" sub-form below, and the customer pages.
 *   - ชื่องาน, ความสำคัญ, เจ้าของงาน, วันติดตาม, หมายเหตุ → nowhere on the deal page can set them after creation, so they
 *     stay here behind the collapsed "รายละเอียดเพิ่มเติม" (a secondary control; the drawn form is what opens).
 *   - client-side drafts (dealDrafts.js) → removed: a five-field form is not worth a draft picker. Drafts already
 *     saved in a browser are left where they are (nothing reads them any more); nothing is purged.
 *
 * The customer stays REQUIRED although the drawing marks only the project: the server requires a project
 * (TicketService.create), and a project can only be created under a customer (POST /customers/{id}/projects).
 * A typed project name is matched against the chosen customer's projects (case- and space-insensitive) before a new
 * one is created, so retyping an existing name never makes a duplicate.
 */

const START_OPTIONS = [
  { value: 'LEAD_APPROACH', title: 'S1 · อยู่ระหว่างเสนอสินค้า', sub: 'เพิ่งเริ่มคุยหรือส่งตัวอย่าง' },
  { value: 'SPEC_APPROVED', title: 'S2 · ผู้ออกแบบตกลงสเปค', sub: 'เลือกสินค้าได้แล้ว รอทำราคา' },
  // A deal ordered before the portal (pre_portal): starts at the import stage and may ask for an invoice at once.
  {
    value: 'PROCUREMENT',
    title: 'สั่งซื้อแล้ว / งานที่เดินมาก่อนใช้ระบบ',
    sub: 'ไปที่ขั้นนำเข้า / ส่งของเลย ไม่ต้องย้อนทำใบเสนอราคาหรือรับมัดจำในระบบ ออกใบขอซื้อ (IR) หรือขอออกใบกำกับได้ทันที',
  },
];

const PRIORITY_OPTIONS = ['LOW', 'NORMAL', 'HIGH'].map((code) => ({ code, label: ticketPriorityLabel(code).label }));

// เสนอแก่ — the three channels th.co.glr.hr.ticket.EntryChannel accepts as a create INPUT (UNSPECIFIED is refused).
// No default on purpose, as before: a pre-selected channel would be recorded for a rep who never noticed the control.
const ENTRY_CHANNEL_OPTIONS = [
  { value: 'DESIGNER_LED', label: 'ผู้ออกแบบนำ' },
  { value: 'OWNER_DIRECT', label: 'เจ้าของตรง' },
  { value: 'BUYER_DIRECT', label: 'ผู้ซื้อตรง' },
];


const EMPTY_CUSTOMER = { name: '', taxId: '', branch: 'สำนักงานใหญ่', address: '', phone: '' };

const normalise = (name) => (name ?? '').trim().replace(/\s+/g, ' ').toLowerCase();


function CustomerSearch({ value, onSelect, onCreateNew, error, errorId }) {
  const [search, setSearch] = useState('');
  const [options, setOptions] = useState([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);

  async function runSearch(q) {
    setLoading(true);
    try {
      const res = await api.customers.search(q);
      setOptions(res.customers ?? []);
    } catch {
      setOptions([]);
    } finally {
      setLoading(false);
    }
  }

  if (value) {
    return (
      <div className="flex min-h-11 items-center gap-2 rounded-md border border-border-muted bg-surface-muted px-3 text-sm">
        <Icon name="building" size={14} className="shrink-0 text-text-muted" />
        <span className="min-w-0 flex-1 truncate font-bold text-text">{value.name}</span>
        <button
          type="button"
          aria-label={`เอา ${value.name} ออก`}
          onClick={() => onSelect(null)}
          className="grid h-11 w-11 shrink-0 cursor-pointer place-items-center border-0 bg-transparent text-text-faint"
        >
          <Icon name="close" size={14} />
        </button>
      </div>
    );
  }
  return (
    <div className="relative">
      <input
        id="deal-customer"
        value={search}
        placeholder="พิมพ์ค้นหาชื่อบริษัท…"
        autoComplete="off"
        className="box-border min-h-11 w-full"
        aria-required="true"
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        onFocus={() => { runSearch(search); setOpen(true); }}
        onChange={(event) => { setSearch(event.target.value); runSearch(event.target.value); setOpen(true); }}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
      />
      {open ? (
        <div className="absolute left-0 right-0 top-full z-50 max-h-72 overflow-y-auto rounded-md border border-border-subtle bg-surface shadow-[0_4px_16px_rgba(0,0,0,0.1)]">
          {/* eslint-disable-next-line jsx-a11y/no-static-element-interactions -- dropdown action row; onMouseDown keeps input focus */}
          <div
            onMouseDown={() => { setOpen(false); onCreateNew(search); }}
            className="sticky top-0 z-10 flex min-h-11 cursor-pointer items-center gap-1.5 border-b border-border-subtle bg-surface-muted px-3 text-xs font-semibold text-link hover:bg-info-row-active"
          >
            <Icon name="plus" size={13} />
            เพิ่มลูกค้าใหม่
          </div>
          {loading ? <div className="px-3 py-2.5 text-xs text-text-muted">กำลังค้นหา…</div> : null}
          {!loading && options.length === 0 ? <div className="px-3 py-2.5 text-xs text-text-muted">ไม่พบข้อมูล</div> : null}
          {options.map((option) => (
            // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- dropdown option row; onMouseDown keeps input focus
            <div
              key={option.id}
              onMouseDown={() => { onSelect(option); setOpen(false); setSearch(''); }}
              className="flex min-h-11 cursor-pointer flex-col justify-center border-b border-surface-subtle px-3 py-2 text-sm hover:bg-surface-muted"
            >
              <span className="font-semibold">{option.name}</span>
              {option.taxId ? <span className="text-2xs text-text-muted">เลขภาษี {option.taxId}</span> : null}
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}

export function TicketCreateModal({ user, onClose, onSubmit, initialStartStage = 'LEAD_APPROACH' }) {
  const [projectName, setProjectName] = useState('');
  const [selectedProject, setSelectedProject] = useState(null);
  const [projectOptions, setProjectOptions] = useState([]);
  const [selectedCustomer, setSelectedCustomer] = useState(null);
  const [newCustomer, setNewCustomer] = useState(null); // the "add new" sub-form's draft, or null when closed
  const [customerSaving, setCustomerSaving] = useState(false);
  const [designerCode, setDesignerCode] = useState('');
  const [estimatedValue, setEstimatedValue] = useState('');
  const [startStage, setStartStage] = useState(initialStartStage);
  const [preDeposit, setPreDeposit] = useState('');
  const [entryChannel, setEntryChannel] = useState(null);
  // Only for a creator who cannot add items afterwards (canEditDealItemsAfterCreate); always [] otherwise.
  const offersItems = !canEditDealItemsAfterCreate(user);
  const [items, setItems] = useState([]);

  // รายละเอียดเพิ่มเติม — the accordion's old รายละเอียดดีล step, collapsed.
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [dealTitle, setDealTitle] = useState('');
  const [priority, setPriority] = useState('NORMAL');
  const [ownerEmployeeId, setOwnerEmployeeId] = useState('');
  // Defaulted as before: the stage-advance readiness gate refuses every forward move while next_follow_up_at is
  // null, so a deal created without one could not be moved to S2 at all.
  const [nextFollowUpAt, setNextFollowUpAt] = useState(() => addDaysIso(bangkokTodayIso(), 14));
  const [note, setNote] = useState('');

  const [fieldErrors, setFieldErrors] = useState({});
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [duplicates, setDuplicates] = useState(null);

  // เจ้าของงาน: the same eligible-people list as before; fetched only once the details are opened.
  const ownerOptionsQuery = useQuery({
    queryKey: queryKeys.dealQuotationDisplayNameOptions(),
    queryFn: () => api.dealQuotations.displayNameOptions().then((r) => r.items ?? []),
    retry: false,
    enabled: detailsOpen,
  });
  const effectiveOwnerId = ownerEmployeeId || (user?.id != null ? String(user.id) : '');

  useEffect(() => {
    if (!selectedCustomer) { setProjectOptions([]); return undefined; }
    let cancelled = false;
    api.customers.projects(selectedCustomer.id)
      .then((res) => { if (!cancelled) setProjectOptions(res.projects ?? []); })
      .catch(() => { if (!cancelled) setProjectOptions([]); });
    return () => { cancelled = true; };
  }, [selectedCustomer]);

  // The project this submit will use: the chip the rep picked, else one of the customer's projects with the same
  // (normalised) name, else none — a new one is created at submit.
  const matchedProject = useMemo(() => {
    const key = normalise(projectName);
    if (!key) return null;
    if (selectedProject && normalise(selectedProject.name) === key) return selectedProject;
    return projectOptions.find((p) => normalise(p.name) === key) ?? null;
  }, [projectName, selectedProject, projectOptions]);

  // Soft, never-blocking warning that this project already has a deal (open a duplicate and it will be cancelled as
  // เปิดซ้ำกับงานอื่น later). Degrades silently.
  const matchedProjectId = matchedProject?.id ?? null;
  useEffect(() => {
    if (matchedProjectId == null) { setDuplicates(null); return undefined; }
    let cancelled = false;
    api.tickets.list({}).then((res) => {
      if (cancelled) return;
      const found = (res.tickets ?? []).filter((t) => t.projectId === matchedProjectId);
      setDuplicates(found.length ? found : null);
    }).catch(() => { if (!cancelled) setDuplicates(null); });
    return () => { cancelled = true; };
  }, [matchedProjectId]);

  function clearError(key) {
    setFieldErrors((prev) => {
      if (!(key in prev)) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  }

  async function handleCreateCustomer() {
    if (!newCustomer?.name.trim()) return;
    setCustomerSaving(true);
    try {
      const res = await api.customers.create(newCustomer);
      setSelectedCustomer(res.customer);
      clearError('customer');
      setNewCustomer(null);
    } catch (err) {
      setError(err.message || 'เพิ่มลูกค้าไม่สำเร็จ');
    } finally {
      setCustomerSaving(false);
    }
  }

  async function submit() {
    const errors = {};
    const name = projectName.trim().replace(/\s+/g, ' ');
    if (!name) errors.project = 'กรุณากรอกชื่อโครงการ';
    if (!selectedCustomer) errors.customer = 'กรุณาเลือกบริษัท/ลูกค้า';
    if (!entryChannel) errors.entryChannel = 'กรุณาเลือกว่างานนี้เสนอแก่ใคร';
    const value = parseEstimatedValue(estimatedValue);
    if (Number.isNaN(value)) errors.value = ESTIMATE_ERROR;
    if (startStage === 'PROCUREMENT' && Number.isNaN(parsePreDeposit(preDeposit))) errors.preDeposit = PRE_DEPOSIT_ERROR;
    const sentItems = offersItems ? items : [];
    Object.assign(errors, validateCreateItems(sentItems));
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      setError('');
      const first = ['project', 'customer', 'entryChannel', 'value', 'preDeposit'].find((key) => errors[key]);
      if (first === 'entryChannel') {
        // A radiogroup cannot take focus itself: focus its first chip (review round 2, L-5). The error is role="alert"
        // and the group is aria-describedby it, so it is announced.
        document.querySelector('#deal-entry-channel [role="radio"]')?.focus();
      } else {
        document.getElementById({
          project: 'deal-project-name', customer: 'deal-customer', value: 'deal-estimated-value', preDeposit: 'deal-pre-deposit',
        }[first])?.focus();
      }
      return;
    }
    setFieldErrors({});
    setError('');
    setLoading(true);
    try {
      let project = matchedProject;
      if (!project) {
        const res = await api.customers.createProject(selectedCustomer.id, { name });
        project = res.project;
        // Kept, so a retry after a failed create reuses it instead of making a second one.
        setProjectOptions((prev) => [...prev, project]);
        setSelectedProject(project);
      }
      let designerName = null;
      if (designerCode.trim()) {
        designerName = await api.designers.getByCode(designerCode.trim())
          .then((designer) => designer?.name || designerCode.trim())
          .catch(() => designerCode.trim());
      }
      await onSubmit({
        title: dealTitle.trim() || name,
        customerName: selectedCustomer.name,
        customerId: selectedCustomer.id,
        projectId: project.id,
        entryChannel,
        designerName,
        estimatedValue: value,
        startStage,
        ...(startStage === 'PROCUREMENT' ? { preDeposit: parsePreDeposit(preDeposit) } : {}),
        priority,
        note: note.trim() || null,
        nextFollowUpAt: nextFollowUpAt || null,
        ...(effectiveOwnerId && effectiveOwnerId !== String(user?.id) ? { ownerEmployeeId: Number(effectiveOwnerId) } : {}),
        items: createItemsPayload(sentItems),
      });
    } catch (err) {
      setError(err.message || 'สร้างงานไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  return (
    <Modal
      title="สร้างงานใหม่"
      subtitle="กรอกเท่าที่รู้ตอนนี้ ที่เหลือเติมจากใบเสนอราคาเอง"
      onClose={onClose}
      testId="ticket-create-modal"
      placement="sheet-on-phone"
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={loading}>ยกเลิก</Button>
          <Button type="button" variant="primary" onClick={submit} disabled={loading} data-testid="ticket-create-submit">
            {loading ? 'กำลังสร้าง…' : 'สร้างงาน'}
          </Button>
        </>
      )}
    >
      <div className="flex flex-col gap-4">
        <div className="grid gap-1.5">
          <FieldLabel htmlFor="deal-project-name" required>ชื่อโครงการ</FieldLabel>
          <input
            id="deal-project-name"
            className="min-h-11"
            value={projectName}
            placeholder="เช่น โครงการบ้านสวนลุมพินี"
            aria-required="true"
            aria-invalid={fieldErrors.project ? true : undefined}
            aria-describedby={fieldErrors.project ? 'deal-project-name-error' : undefined}
            onChange={(event) => { setProjectName(event.target.value); clearError('project'); }}
          />
          <FieldError id="deal-project-name-error">{fieldErrors.project}</FieldError>
          {selectedCustomer && projectOptions.length > 0 ? (
            <div className="grid gap-1">
              <span className="text-xs text-text-muted">โครงการเดิมของลูกค้านี้ — แตะเพื่อใช้</span>
              <div className="flex flex-wrap gap-1.5">
                {projectOptions.map((project) => {
                  const chosen = matchedProject?.id === project.id;
                  return (
                    <button
                      key={project.id}
                      type="button"
                      aria-pressed={chosen}
                      onClick={() => { setProjectName(project.name); setSelectedProject(project); clearError('project'); }}
                      className={`min-h-11 cursor-pointer rounded-full border px-3 text-xs font-semibold ${
                        chosen ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text hover:bg-surface-hover'
                      }`}
                    >
                      {project.name}
                    </button>
                  );
                })}
              </div>
            </div>
          ) : null}
          {duplicates ? (
            <p role="status" className="m-0 rounded-lg border border-warning-border bg-warning-bg px-3 py-2 text-xs text-warning-dark">
              โครงการนี้มีงานอยู่แล้ว — {duplicates.map((t) => `${t.code} (${dealStageLabel(t.salesStage)?.label ?? t.status})`).join(', ')}. ตรวจสอบก่อนสร้างซ้ำ
            </p>
          ) : null}
        </div>

        <div className="grid gap-1.5">
          <FieldLabel htmlFor="deal-customer" required>ลูกค้า / บริษัท</FieldLabel>
          <CustomerSearch
            value={selectedCustomer}
            onSelect={(customer) => { setSelectedCustomer(customer); setSelectedProject(null); if (customer) clearError('customer'); }}
            onCreateNew={(typed) => setNewCustomer({ ...EMPTY_CUSTOMER, name: typed.trim() })}
            error={fieldErrors.customer}
            errorId="deal-customer-error"
          />
          <FieldError id="deal-customer-error">{fieldErrors.customer}</FieldError>
          {newCustomer && !selectedCustomer ? (
            <div className="grid gap-2 rounded-md border border-info-border bg-info-row-active p-3">
              <p className="m-0 text-xs font-bold text-info">เพิ่มลูกค้าใหม่</p>
              <div className="grid gap-2 sm:grid-cols-2">
                <label className="m-0 sm:col-span-2">
                  <span className="text-2xs">ชื่อบริษัท *</span>
                  <input value={newCustomer.name} onChange={(e) => setNewCustomer((p) => ({ ...p, name: e.target.value }))} placeholder="บริษัท … จำกัด" />
                </label>
                <label className="m-0">
                  <span className="text-2xs">เลขประจำตัวผู้เสียภาษี</span>
                  <input value={newCustomer.taxId} onChange={(e) => setNewCustomer((p) => ({ ...p, taxId: e.target.value }))} placeholder="0105xxxxxxxxx" />
                </label>
                <label className="m-0">
                  <span className="text-2xs">สาขา</span>
                  <input value={newCustomer.branch} onChange={(e) => setNewCustomer((p) => ({ ...p, branch: e.target.value }))} placeholder="สำนักงานใหญ่" />
                </label>
                <label className="m-0">
                  <span className="text-2xs">โทรศัพท์</span>
                  <input value={newCustomer.phone} onChange={(e) => setNewCustomer((p) => ({ ...p, phone: e.target.value }))} placeholder="02-xxx-xxxx" />
                </label>
                <label className="m-0">
                  <span className="text-2xs">ที่อยู่</span>
                  <input value={newCustomer.address} onChange={(e) => setNewCustomer((p) => ({ ...p, address: e.target.value }))} placeholder="ที่อยู่บริษัท" />
                </label>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button variant="primary" size="sm" disabled={!newCustomer.name.trim() || customerSaving} onClick={handleCreateCustomer}>
                  {customerSaving ? 'กำลังบันทึก…' : 'บันทึกบริษัทใหม่'}
                </Button>
                <Button variant="secondary" size="sm" onClick={() => setNewCustomer(null)}>ยกเลิก</Button>
              </div>
            </div>
          ) : null}
        </div>

        <div className="grid gap-1.5">
          <span className="text-sm font-bold text-text-secondary">
            {OFFERED_TO_LABEL}
            <span className="text-danger" aria-hidden="true"> *</span>
          </span>
          <PickChips
            id="deal-entry-channel"
            describedBy={fieldErrors.entryChannel ? 'deal-entry-channel-error' : undefined}
            label={OFFERED_TO_LABEL}
            value={entryChannel}
            onChange={(value) => { setEntryChannel(value); clearError('entryChannel'); }}
            options={ENTRY_CHANNEL_OPTIONS}
          />
          <FieldError id="deal-entry-channel-error">{fieldErrors.entryChannel}</FieldError>
        </div>

        <DesignerPicker value={designerCode} onSelectCode={setDesignerCode} idPrefix="deal-designer" label="ผู้ออกแบบ" />

        <div className="grid gap-1.5">
          <FieldLabel htmlFor="deal-estimated-value">
            มูลค่าโดยประมาณ <em className="font-semibold not-italic text-text-muted">(ไม่บังคับ)</em>
          </FieldLabel>
          <div className="flex min-h-11 w-full max-w-60 items-center gap-2 rounded-md border border-border-input bg-surface px-3 focus-within:outline focus-within:outline-2 focus-within:outline-primary">
            <span aria-hidden="true" className="text-sm font-bold text-text-muted">฿</span>
            <input
              id="deal-estimated-value"
              inputMode="decimal"
              autoComplete="off"
              className="min-w-0 flex-1 border-0 bg-transparent p-0 tabular-nums outline-none"
              value={estimatedValue}
              placeholder="เช่น 1,200,000"
              aria-invalid={fieldErrors.value ? true : undefined}
              aria-describedby={fieldErrors.value ? 'deal-estimated-value-error' : undefined}
              onChange={(event) => { setEstimatedValue(event.target.value); clearError('value'); }}
            />
          </div>
          <FieldError id="deal-estimated-value-error">{fieldErrors.value}</FieldError>
        </div>

        <RadioCards
          label="เริ่มที่"
          // Starting at S2 needs the stage write access a hand move to S2 needs (TicketService.requireStageWriteAccess:
          // sales owner/keyer, sales_manager, ceo); offered to no one the server would refuse.
          options={START_OPTIONS.map((option) => ({
            ...option,
            disabled: option.value !== 'LEAD_APPROACH' && !['sales', 'sales_manager', 'ceo'].includes(user?.role),
          }))}
          value={startStage}
          onChange={setStartStage}
        />
        {startStage === 'PROCUREMENT' ? (
          <PreDepositField
            id="deal-pre-deposit"
            value={preDeposit}
            onChange={(text) => { setPreDeposit(text); clearError('preDeposit'); }}
            error={fieldErrors.preDeposit}
          />
        ) : null}

        {offersItems ? (
          <CreateDealItems items={items} onChange={setItems} errors={fieldErrors} onClearError={clearError} />
        ) : null}

        <div className="border-t border-border-subtle pt-1">
          <Button
            type="button"
            variant="text"
            className="min-h-11 gap-1.5 px-0 text-sm mobile:min-h-11"
            aria-expanded={detailsOpen}
            aria-controls="deal-create-details"
            onClick={() => setDetailsOpen((open) => !open)}
          >
            <Icon name={detailsOpen ? 'chevronDown' : 'chevronRight'} size={14} />
            รายละเอียดเพิ่มเติม (ไม่บังคับ)
          </Button>
          {detailsOpen ? (
            <div id="deal-create-details" className="mt-2 grid gap-3">
              <div className="grid gap-1.5">
                <FieldLabel htmlFor="deal-title">ชื่องาน <span className="font-semibold text-text-muted">(ค่าเริ่มต้นคือชื่อโครงการ)</span></FieldLabel>
                <input id="deal-title" className="min-h-11" value={dealTitle} placeholder={projectName.trim() || 'ชื่องาน'} onChange={(e) => setDealTitle(e.target.value)} />
              </div>
              <div className="grid gap-1.5">
                <span className="text-sm font-bold text-text-secondary">ความสำคัญ</span>
                <div role="radiogroup" aria-label="ความสำคัญ" className="flex flex-wrap gap-2">
                  {PRIORITY_OPTIONS.map((option) => (
                    <button
                      key={option.code}
                      type="button"
                      role="radio"
                      aria-checked={priority === option.code}
                      onClick={() => setPriority(option.code)}
                      className={`min-h-11 rounded-full border px-3.5 text-xs font-bold ${
                        priority === option.code ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text-muted'
                      }`}
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
              </div>
              <div className="grid gap-1.5">
                <FieldLabel htmlFor="deal-owner">เจ้าของงาน <span className="font-semibold text-text-muted">(ค่าเริ่มต้นคือคุณ — เลือกคนอื่นถ้าคีย์งานแทน)</span></FieldLabel>
                <select id="deal-owner" className="min-h-11" value={effectiveOwnerId} onChange={(e) => setOwnerEmployeeId(e.target.value)}>
                  {user?.id != null && !(ownerOptionsQuery.data ?? []).some((o) => String(o.id) === String(user.id)) ? (
                    <option value={String(user.id)}>{user.name}</option>
                  ) : null}
                  {(ownerOptionsQuery.data ?? []).map((o) => <option key={o.id} value={String(o.id)}>{o.name}</option>)}
                </select>
              </div>
              <div className="grid gap-1.5">
                <FieldLabel htmlFor="deal-next-follow-up">วันติดตามครั้งถัดไป <span className="font-semibold text-text-muted">(ตั้งไว้ล่วงหน้า 14 วัน)</span></FieldLabel>
                <ThaiDateInput id="deal-next-follow-up" value={nextFollowUpAt} onChange={(e) => setNextFollowUpAt(e.target.value)} />
              </div>
              <div className="grid gap-1.5">
                <FieldLabel htmlFor="deal-note">หมายเหตุ</FieldLabel>
                <textarea id="deal-note" rows={3} value={note} placeholder="ข้อมูลเพิ่มเติม (ถ้ามี)" onChange={(e) => setNote(e.target.value)} />
              </div>
            </div>
          ) : null}
        </div>

        {error ? <p role="alert" className="m-0 rounded-md bg-danger-bg px-3 py-2.5 text-sm font-bold text-danger-dark">{error}</p> : null}
      </div>
    </Modal>
  );
}
