import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { fieldErrorId } from '../../components/common/FormField.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { dealStageLabel, ticketPriorityLabel } from '../../utils/format.js';
import { computeItemEstimateThb, formatThb, summarizeItemsEstimate } from './dealEstimatePricing.js';

// Thai copy for each dealEstimatePricing.js `reason` code — keep in sync with that module's
// EstimateNotComputableReason typedef. An unmapped reason still renders (falls back to the
// generic message) rather than showing "undefined" to a rep.
const ESTIMATE_REASON_LABELS = {
  UNSUPPORTED_UNIT: 'หน่วยราคานี้ยังไม่รองรับ',
  MISSING_QUANTITY: 'ยังไม่ได้กรอกจำนวน/พื้นที่',
  NO_FX_RATE: 'ยังไม่มีอัตราแลกเปลี่ยนสำหรับสกุลเงินนี้',
  NO_CATALOG_PRICE: 'ยังไม่มีราคาแคตตาล็อก',
  UNIT_BASIS_MISMATCH: 'หน่วยที่เลือกไม่ตรงกับหน่วยราคา — เปลี่ยนหน่วยหรือกรอกอัตราแปลงต่อแผ่น',
  NOT_CATALOG: 'ไม่ใช่รายการจากแคตตาล็อก',
  NO_MARKUP: 'ยังไม่มีตัวคูณราคาจาก CEO',
};
function estimateReasonLabel(reason) {
  return ESTIMATE_REASON_LABELS[reason] || 'ยังคำนวณไม่ได้';
}

const emptyItem = () => ({
  brand: '', model: '', color: '', texture: '', size: '', factory: '',
  unitBasis: 'PIECE', qty: 1, qtySqm: '', sqmPerPiece: null,
  // source ('catalog' | 'custom') + the catalog's reference price/currency/priceUnit are UI-only
  // — never sent in the onSubmit payload (see submit() below). They exist so the items view can
  // badge a line "จากแคตตาล็อก" vs "custom", show a read-only reference price, and (catalogPriceUnit)
  // let dealEstimatePricing.js compute the ราคาตั้ง estimate against the right quantity basis.
  source: 'custom', catalogPrice: null, catalogCurrency: null, catalogPriceUnit: null,
  // catalogPriceId/catalogProductCode ARE sent (see submit()'s items map below) — the catalog
  // identity picked here, persisted on ticket_item (V110) so PricingRequestCreateModal can seed
  // its own catalog link without a re-search. Cleared by updateItem() below whenever the user
  // hand-edits a descriptive field the link no longer accurately describes.
  catalogPriceId: null, catalogProductCode: '',
});

let _catalogTimer = null;
function debouncedCatalogSearch(q, cb) {
  clearTimeout(_catalogTimer);
  _catalogTimer = setTimeout(() => cb(q), 280);
}

// ── client-side draft (no server draft entity — see handoff 107) ───────────
// localStorage-scoped key: 'DRAFT-tmp' style local persistence for an
// in-progress create form. There is nothing server-side backing this; the
// backend's own "draft" is the DRAFT ticket status created on a real submit.
const DRAFT_KEY = 'glr:draft-deal';

function loadDraft() {
  try {
    const raw = localStorage.getItem(DRAFT_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch {
    return null;
  }
}
function saveDraft(snapshot) {
  try {
    localStorage.setItem(DRAFT_KEY, JSON.stringify({ ...snapshot, savedAt: new Date().toISOString() }));
  } catch {
    // localStorage unavailable (private mode / quota) — draft save is a nice-to-have, never fatal.
  }
}
function clearDraft() {
  try {
    localStorage.removeItem(DRAFT_KEY);
  } catch {
    /* ignore */
  }
}

// ── ช่องทางดีล (entry channel) — backend th.co.glr.hr.ticket.EntryChannel ──
const ENTRY_CHANNEL_OPTIONS = [
  { code: 'DESIGNER_LED', label: 'ผู้ออกแบบนำ', sub: 'Designer-led' },
  { code: 'OWNER_DIRECT', label: 'เจ้าของตรง', sub: 'Owner-direct' },
  { code: 'BUYER_DIRECT', label: 'ผู้ซื้อตรง', sub: 'Buyer-direct' },
];

// CreateTicketRequest.priority is optional and mockApi already defaults it to
// 'NORMAL' when absent — reusing the canonical labels from format.js keeps
// this picker's wording identical to wherever priority is displayed later.
const PRIORITY_OPTIONS = ['LOW', 'NORMAL', 'HIGH'].map((code) => ({ code, label: ticketPriorityLabel(code).label }));

// ── validation (UX-03) ──────────────────────────────────────────────────────
// Mirrors the rules that used to live inline in submit() as a sequence of
// "first failing check wins, generic message" early-returns. The *conditions*
// are unchanged; what changed is the shape of the result — every invalid
// field is now reported (keyed the same way as `fieldErrors` state below:
// 'customer', 'project', `items.<index>.<field>`), so a long form doesn't
// force the user to fix-and-resubmit one message at a time.
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
const projectRequiredSchema = z.any().refine((v) => v != null, 'กรุณาเลือกโครงการ (1 ดีล = 1 Ticket ภายใต้โครงการ)');

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

// Which sub-view owns a given fieldErrors key — used to jump the user
// straight to (and, once mounted, focus) the first invalid field.
function viewForFieldKey(key) {
  if (key === 'customer') return 'customer';
  if (key === 'project') return 'project';
  if (key.startsWith('items.')) return 'items';
  return 'hub';
}
function itemIndexForFieldKey(key) {
  return key.startsWith('items.') ? Number(key.split('.')[1]) : null;
}

// ── small sub-components ──────────────────────────────────────────────────────

// maxHeight 220 was sized for the old 720px-wide modal, where it squeezed the company picker's
// results down to ~3 visible rows. The modal now renders at size="lg" (see the bottom of this
// file), so this can grow along with the panel without the dropdown outgrowing it.
const SEARCH_SELECT_OPTIONS_MAX_HEIGHT = 380;

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
    <div className="relative">
      <span className="mb-1 block text-xs">{label}</span>
      {value ? (
        <div className="flex items-center gap-2 rounded-[6px] border border-border-muted bg-surface-muted px-2.5 py-1.5 text-sm">
          <span className="flex-1">{renderValue(value)}</span>
          <button type="button" onClick={() => { onSelect(null); }} className="cursor-pointer border-0 bg-transparent p-0 text-text-faint">
            <Icon name="close" size={14} />
          </button>
        </div>
      ) : (
        <div className="relative">
          <input
            id={id}
            ref={inputRef}
            value={searchValue}
            onChange={(e) => { onSearchChange(e.target.value); onSearch(e.target.value); setOpen(true); }}
            onFocus={() => { onSearch(searchValue); setOpen(true); }}
            onBlur={() => setTimeout(() => setOpen(false), 150)}
            placeholder={placeholder}
            className="box-border w-full"
            aria-required="true"
            aria-invalid={error ? true : undefined}
            aria-describedby={errorId}
          />
          {open && (
            // maxHeight stays inline: it's sourced from SEARCH_SELECT_OPTIONS_MAX_HEIGHT (a JS
            // constant), which a static Tailwind arbitrary-value class can't reference.
            <div className="absolute left-0 right-0 top-full z-50 overflow-y-auto rounded-[6px] border border-border-subtle bg-surface shadow-[0_4px_16px_rgba(0,0,0,0.1)]" style={{ maxHeight: SEARCH_SELECT_OPTIONS_MAX_HEIGHT }}>
              {loading && <div className="px-3 py-2.5 text-xs text-text-muted">กำลังโหลด{label}…</div>}
              {!loading && options.length === 0 && <div className="px-3 py-2.5 text-xs text-text-muted">ไม่พบข้อมูล</div>}
              {options.map((opt) => (
                // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- dropdown option row; onMouseDown (not click) preserves input focus for typeahead
                <div key={opt.id} onMouseDown={() => { onSelect(opt); setOpen(false); }}
                  className="cursor-pointer border-b border-surface-subtle px-3 py-2 text-sm hover:bg-surface-muted"
                >
                  {renderOption(opt)}
                </div>
              ))}
              {onCreateNew && (
                // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- dropdown action row; onMouseDown (not click) preserves input focus
                <div onMouseDown={() => { setOpen(false); onCreateNew(); }}
                  className={`flex cursor-pointer items-center gap-1.5 bg-surface-muted px-3 py-2 text-xs font-semibold text-link hover:bg-info-row-active ${options.length > 0 ? 'border-t border-border-subtle' : ''}`}
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
        <p id={errorId} role="alert" className="mx-0 mb-0 mt-1 text-2xs font-bold text-danger">{error}</p>
      ) : null}
    </div>
  );
}

/** Back-to-hub / back-to-list link used at the top of every sub-view. */
function BackLink({ onClick, label = 'กลับ' }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="inline-flex w-fit items-center gap-1 text-sm font-extrabold text-primary"
    >
      <Icon name="chevronLeft" size={15} /> {label}
    </button>
  );
}

/** One checklist row on the hub. */
function HubRow({ title, subtitle, done, required, optional, active, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex items-center gap-3 rounded-xl border bg-surface px-3.5 py-3 text-left hover:bg-surface-hover ${active ? 'border-primary shadow-[var(--shadow-focus-ring)]' : 'border-border'}`}
    >
      <span
        aria-hidden="true"
        className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full ${done ? 'bg-success text-white' : 'border-2 border-dashed border-border-strong'}`}
      >
        {done ? <Icon name="check" size={12} /> : null}
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-1.5 text-sm font-extrabold text-text">
          {title}
          {/* WCAG AA fix (fix/ui-contrast-tokens): text-danger (#dc2626) on
              bg-danger-bg (#fee2e2) measures 3.95:1, failing 4.5:1 — found
              by an automated contrast scan of this modal, not the original
              manual audit. text-danger-dark (#b91c1c) is the app's
              established danger-on-danger-bg pairing (already asserted at
              4.5+ in designTokens.test.js's 'status-danger text on danger
              bg' case; StatusBadge's own danger tone uses it too). */}
          {required && !done ? <span className="rounded-full bg-danger-bg px-1.5 text-2xs font-extrabold text-danger-dark">จำเป็น</span> : null}
          {optional ? <span className="text-2xs font-semibold text-text-muted">(ไม่บังคับ)</span> : null}
        </span>
        <span className="block truncate text-xs text-text-muted">{subtitle}</span>
      </span>
      <Icon name="chevronRight" size={16} className="shrink-0 text-text-faint" />
    </button>
  );
}

// ── main modal ────────────────────────────────────────────────────────────────

export function TicketCreateModal({ onClose, onSubmit, initialItems }) {
  // Read once at mount — a stable snapshot, not re-read on every render.
  const [initialDraft] = useState(() => loadDraft());

  // hub | customer | project | contact | items | details | review
  const [view, setView] = useState('hub');
  // fieldErrors key waiting to be scrolled+focused once its owning view/item
  // editor has actually mounted (see the effect below).
  const [pendingFocusKey, setPendingFocusKey] = useState(null);

  const [form, setForm] = useState({ note: initialDraft?.note ?? '' });
  const [dealTitle, setDealTitle] = useState(initialDraft?.dealTitle ?? '');
  const [priority, setPriority] = useState(initialDraft?.priority ?? 'NORMAL');
  const [entryChannel, setEntryChannel] = useState(initialDraft?.entryChannel ?? 'DESIGNER_LED');
  // V50: a deal may start with NO items (lightweight lead-stage draft) — the
  // price-request flow begins later once items are added and submitted.
  const [items, setItems] = useState(() => (
    initialDraft?.items?.length ? initialDraft.items : (initialItems?.length ? initialItems : [])
  ));
  const [editingItemIndex, setEditingItemIndex] = useState(null);

  const [loading, setLoading] = useState(false);
  // Form-level: submit/API failures (สร้างคำขอราคาไม่สำเร็จ, สร้างโครงการไม่สำเร็จ,
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
  const [selectedCustomer, setSelectedCustomer] = useState(initialDraft?.customer ?? null);

  const [projectOptions, setProjectOptions] = useState([]);
  const [selectedProject, setSelectedProject] = useState(initialDraft?.project ?? null);
  const [newProjectName, setNewProjectName] = useState('');
  const [showNewProject, setShowNewProject] = useState(false);

  // Client-only duplicate-deal warning (V50 has no server-side duplicate
  // check) — dismissible, never blocks. Degrades silently on API failure.
  const [duplicateWarning, setDuplicateWarning] = useState(null);
  const [duplicateDismissed, setDuplicateDismissed] = useState(false);

  const [contactOptions, setContactOptions] = useState([]);
  const [selectedContact, setSelectedContact] = useState(initialDraft?.contact ?? null);
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

  // ── ราคาตั้ง (ประมาณการ) estimate inputs — FX rates + CEO markup multiplier ────────────
  // Both fetches degrade silently: if EITHER fails, `estimateReady` below stays false and every
  // ราคาตั้ง display site is skipped entirely, leaving the existing catalog reference price as the
  // only price shown (a deal must stay creatable with zero prices — V50). Never surfaced via
  // showToast from an effect — this repo's useToast has an unstable identity that turns a toast in
  // a useEffect dep array into an infinite render loop (see CLAUDE.md); these are silent, no-toast
  // failures on purpose. `retry: false` keeps a genuinely-down endpoint from stalling the modal.
  const fxRatesQuery = useQuery({
    queryKey: queryKeys.fxRates(),
    queryFn: () => api.fxRates.list().then((res) => res.fxRates ?? []),
    retry: false,
  });
  const markupQuery = useQuery({
    queryKey: queryKeys.dealEstimateMarkup(),
    queryFn: () => api.dealEstimateMarkup.get().then((res) => res.dealEstimateMarkup ?? null),
    retry: false,
  });
  const fxRatesByCurrency = useMemo(() => {
    const map = { THB: 1 };
    for (const rate of fxRatesQuery.data ?? []) {
      if (rate?.currency) map[rate.currency] = Number(rate.rateToThb);
    }
    return map;
  }, [fxRatesQuery.data]);
  const markupMultiplier = markupQuery.data?.multiplier ?? null;
  // Deliberately requires BOTH queries to have actually succeeded with a usable multiplier — never
  // "readable, so default markup to 1": an un-marked-up supplier cost displayed as ราคาตั้ง is
  // exactly what the product owner said must not happen (see dealEstimatePricing.js).
  const estimateReady = fxRatesQuery.isSuccess && markupQuery.isSuccess && markupMultiplier != null;
  const estimateContext = { fxRatesByCurrency, markupMultiplier };
  const itemsEstimate = useMemo(
    () => (estimateReady ? summarizeItemsEstimate(items, estimateContext) : null),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- estimateContext is a fresh object every render; fxRatesByCurrency/markupMultiplier are its real deps
    [items, estimateReady, fxRatesByCurrency, markupMultiplier],
  );

  // new customer form
  const [showNewCustomer, setShowNewCustomer] = useState(false);
  const [newCustomer, setNewCustomer] = useState({ name: '', taxId: '', branch: 'สำนักงานใหญ่', address: '', phone: '' });
  const [customerSaving, setCustomerSaving] = useState(false);

  // client-side draft persistence
  const [draftSavedAt, setDraftSavedAt] = useState(initialDraft?.savedAt ?? null);

  // load projects + contacts when customer is picked (also fires once on
  // mount when a draft restored a customer, refreshing both lists).
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

  // Client-only duplicate-deal check: once a โครงการ is picked, see whether
  // any existing deal already carries that projectId. Soft warning only —
  // never blocks creation — and silently gives up on a failed fetch.
  useEffect(() => {
    setDuplicateDismissed(false);
    if (!selectedProject) { setDuplicateWarning(null); return undefined; }
    let cancelled = false;
    api.tickets.list({}).then((res) => {
      if (cancelled) return;
      const matches = (res.tickets ?? []).filter((t) => t.projectId === selectedProject.id);
      setDuplicateWarning(matches.length ? matches : null);
    }).catch(() => {
      if (!cancelled) setDuplicateWarning(null);
    });
    return () => { cancelled = true; };
  }, [selectedProject]);

  // Focus (and scroll to) a pending invalid field once its owning view/item
  // editor has actually mounted the node.
  useEffect(() => {
    if (!pendingFocusKey) return;
    const node = fieldRefs.current[pendingFocusKey];
    if (!node) return;
    if (typeof node.scrollIntoView === 'function') {
      node.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    node.focus();
    setPendingFocusKey(null);
  }, [pendingFocusKey, view, items, editingItemIndex]);

  function jumpToField(key) {
    setPendingFocusKey(key);
    const targetView = viewForFieldKey(key);
    if (targetView === 'items') {
      const idx = itemIndexForFieldKey(key);
      if (idx != null) setEditingItemIndex(idx);
    }
    setView(targetView);
  }

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
      // A hand-edit to any field that describes WHICH product this row is invalidates a
      // previously-picked catalog link — what's typed no longer necessarily matches what the
      // link points at. Mirrors PricingRequestCreateModal.updateItem's identical rule.
      if (['brand', 'model', 'color', 'texture', 'size', 'factory'].includes(field)) {
        updated.source = 'custom';
        updated.catalogPriceId = null;
        updated.catalogProductCode = '';
        updated.catalogPrice = null;
        updated.catalogCurrency = null;
      }
      return updated;
    }));
  }

  // `pickedFrom` is which autocomplete the user chose from ('brand' | 'model'), which decides
  // whether the current ยี่ห้อ text is a deliberate brand entry or merely the search query —
  // see the brand rule below.
  function applyCatalogItem(index, cat, pickedFrom) {
    setItems((cur) => cur.map((item, i) => {
      if (i !== index) return item;
      const newQtySqm = item.qty && cat.sqmPerPiece ? (Number(item.qty) * cat.sqmPerPiece).toFixed(3) : '';
      // Brand bug fix: this used to write the FACTORY name into ยี่ห้อ
      // (cat.factoryName || cat.brand), which is wrong — factory and brand are
      // different concepts (factory has its own field below). The catalog's
      // grade is the right source: ProductPriceDto has no separate "brand" of
      // its own, and grade is the closest analogue (the same
      // COALESCE(pri.brand, pp.grade) rule
      // PricingRequestRepository.snapshotCatalogSelections uses server-side).
      //
      // Unlike PricingRequestCreateModal — which has a DEDICATED catalog search
      // box, so a non-blank brand there is always a deliberate entry that must
      // win — ยี่ห้อ IS the search box on this form. Text sitting in it after a
      // pick from the ยี่ห้อ dropdown is the QUERY ("sc"), not a brand, so
      // keeping it would leave junk in the field and fail this fix's own intent
      // just as the factory name did. A pick from the รุ่น dropdown is the
      // other case: ยี่ห้อ was filled independently there, so it wins.
      const brandTypedIndependently = pickedFrom !== 'brand' && item.brand?.trim();
      const brand = brandTypedIndependently ? item.brand : (cat.grade || cat.brand || '');
      // support both old catalog (brand/size) and new price catalog (factoryName/sizeRaw)
      return {
        ...item,
        brand,
        model:       cat.collection   || cat.productName || cat.productCode || '',
        color:       cat.color        || '',
        texture:     cat.surface      || '',
        size:        cat.sizeRaw      || cat.size      || '',
        factory:     cat.factoryName  || cat.factory   || '',
        sqmPerPiece: cat.sqmPerPiece  || null,
        qtySqm:      newQtySqm,
        // UI-only provenance — see emptyItem()'s comment.
        source: 'catalog',
        catalogPrice: cat.price ?? null,
        catalogCurrency: cat.currency ?? null,
        // Persisted (V110) — see emptyItem()'s comment. cat.priceId is
        // ProductPriceDto.priceId, the same price_catalog.product_prices.price_id that
        // sales.pricing_request_item.product_id already points at (V68).
        catalogPriceId: cat.priceId ?? null,
        catalogProductCode: cat.productCode ?? '',
        catalogPriceUnit: cat.priceUnit ?? null,
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

  function addItem() {
    setItems((cur) => [...cur, emptyItem()]);
    setEditingItemIndex(items.length);
  }

  function removeItem(index) {
    setItems((cur) => cur.filter((_, i) => i !== index));
    // Removing a row shifts every later row's index, so a stale error keyed
    // to the old index would otherwise render against the wrong row after
    // this. Item errors are cheap to re-derive on the next submit.
    setFieldErrors((prev) => Object.fromEntries(Object.entries(prev).filter(([k]) => !k.startsWith('items.'))));
    setEditingItemIndex((cur) => {
      if (cur === index) return null;
      if (cur != null && cur > index) return cur - 1;
      return cur;
    });
  }

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

  function handleSaveDraft() {
    saveDraft({
      dealTitle, note: form.note, priority, entryChannel,
      customer: selectedCustomer, project: selectedProject, contact: selectedContact,
      items,
    });
    setDraftSavedAt(new Date().toISOString());
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
      jumpToField(order[0]);
      return;
    }
    setFieldErrors({});
    setError('');
    setLoading(true);
    try {
      await onSubmit({
        title: dealTitle.trim() || selectedCustomer.name,
        customerName: selectedCustomer.name,
        customerId: selectedCustomer.id,
        projectId: selectedProject.id,
        contactId: selectedContact?.id ?? null,
        note: form.note.trim() || null,
        entryChannel,
        priority,
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
          // Persisted (V110) so PricingRequestCreateModal can seed its own catalog link
          // without a re-search — see emptyItem()'s comment.
          catalogPriceId: item.catalogPriceId ?? null,
          catalogProductCode: item.catalogProductCode?.trim() || null,
        })),
      });
      // Server accepted the deal — the client-only draft has served its
      // purpose and would otherwise resurrect stale state next time the
      // modal opens.
      clearDraft();
      setDraftSavedAt(null);
    } catch (err) {
      setError(err.message || 'สร้างคำขอราคาไม่สำเร็จ');
      setLoading(false);
    }
  }

  // ── progress meter ──────────────────────────────────────────────────────
  const sectionDone = {
    customer: Boolean(selectedCustomer),
    project: Boolean(selectedProject),
    contact: Boolean(selectedContact),
    items: items.length > 0,
    details: Boolean(dealTitle.trim() || form.note.trim() || priority !== 'NORMAL'),
  };
  const TOTAL_SECTIONS = 6; // 5 above + ตรวจสอบ & บันทึก itself (never "done" on its own)
  const doneCount = Object.values(sectionDone).filter(Boolean).length;
  const canCreateNow = Boolean(selectedCustomer && selectedProject);

  const entryChannelLabel = ENTRY_CHANNEL_OPTIONS.find((o) => o.code === entryChannel)?.label ?? entryChannel;
  const priorityLabel = PRIORITY_OPTIONS.find((p) => p.code === priority)?.label ?? priority;

  // ── sub-views ────────────────────────────────────────────────────────────

  function renderHub() {
    return (
      <div className="flex flex-col gap-4">
        <div className="flex items-center gap-3 rounded-lg border border-border bg-surface px-3 py-2.5">
          <span className="whitespace-nowrap text-xs font-extrabold text-text-secondary">{doneCount}/{TOTAL_SECTIONS} เสร็จ</span>
          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-subtle">
            <div className="h-full rounded-full bg-accent" style={{ width: `${Math.round((doneCount / TOTAL_SECTIONS) * 100)}%` }} />
          </div>
          {draftSavedAt ? <span className="whitespace-nowrap text-2xs font-bold text-accent-dark">ร่างบันทึกแล้ว</span> : null}
        </div>

        <div className="flex flex-col gap-2">
          <HubRow
            title="ลูกค้า"
            required
            done={sectionDone.customer}
            subtitle={selectedCustomer ? selectedCustomer.name : 'ยังไม่ได้เลือก'}
            onClick={() => setView('customer')}
          />
          <HubRow
            title="โครงการ"
            required
            done={sectionDone.project}
            subtitle={selectedProject ? selectedProject.name : (selectedCustomer ? 'ยังไม่ได้เลือก' : 'เลือกลูกค้าก่อน')}
            onClick={() => setView('project')}
          />
          <HubRow
            title="ผู้ติดต่อ & ช่องทางดีล"
            done={sectionDone.contact}
            subtitle={[selectedContact ? `${selectedContact.firstName} ${selectedContact.lastName}`.trim() : null, entryChannelLabel].filter(Boolean).join(' · ')}
            onClick={() => setView('contact')}
          />
          <HubRow
            title="รายการสินค้า"
            optional
            done={sectionDone.items}
            subtitle={items.length ? `${items.length} รายการ` : 'ยังไม่มีรายการ'}
            onClick={() => setView('items')}
          />
          <HubRow
            title="รายละเอียดดีล"
            optional
            done={sectionDone.details}
            subtitle={[dealTitle.trim(), priority !== 'NORMAL' ? priorityLabel : null, form.note.trim() ? 'มีหมายเหตุ' : null].filter(Boolean).join(' · ') || 'ชื่อดีล · ความสำคัญ · หมายเหตุ'}
            onClick={() => setView('details')}
          />
          <HubRow
            title="ตรวจสอบ & บันทึก"
            subtitle="ตรวจก่อนสร้างดีล"
            onClick={() => setView('review')}
          />
        </div>

        <div className="flex items-start gap-2 rounded-lg border border-info-border bg-info-bg px-3 py-2.5 text-xs text-info-dark">
          <Icon name="info" size={15} className="mt-0.5 shrink-0" />
          <span>เพิ่มรายการสินค้าอย่างน้อย 1 รายการ ก่อนจึงจะสร้างคำขอราคาได้</span>
        </div>

        {error ? <div className="form-error" role="alert">{error}</div> : null}
      </div>
    );
  }

  function renderCustomerView() {
    return (
      <div className="flex flex-col gap-3">
        <BackLink onClick={() => setView('hub')} />
        <SearchSelect
          id="customer-select"
          label="บริษัท / ลูกค้า *"
          value={selectedCustomer}
          onSelect={(c) => { setSelectedCustomer(c); if (c) { setShowNewCustomer(false); clearFieldError('customer'); } }}
          placeholder="พิมพ์ค้นหาชื่อบริษัท…"
          options={customerOptions}
          onSearch={searchCustomers}
          searchValue={customerSearch}
          onSearchChange={setCustomerSearch}
          loading={customerLoading}
          renderOption={(c) => (
            <div>
              <div className="font-semibold">{c.name}</div>
              {c.taxId && <div className="text-2xs text-text-muted">เลขภาษี {c.taxId}</div>}
            </div>
          )}
          renderValue={(c) => <span><strong>{c.name}</strong>{c.taxId ? <span className="ml-1.5 text-xs text-text-muted">({c.taxId})</span> : null}</span>}
          createNewLabel="สร้างบริษัท / ลูกค้าใหม่"
          onCreateNew={() => setShowNewCustomer(true)}
          inputRef={(el) => { fieldRefs.current.customer = el; }}
          error={fieldErrors.customer}
        />

        {showNewCustomer && !selectedCustomer && (
          <div className="flex flex-col gap-2 rounded-md border border-info-border bg-info-row-active p-3">
            <p className="m-0 text-xs font-bold text-info">เพิ่มบริษัท / ลูกค้าใหม่</p>
            <div className="grid grid-cols-2 gap-2">
              <label className="col-span-2 m-0">
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
            <div className="mt-1 flex gap-2">
              <Button variant="primary" className="text-xs" disabled={!newCustomer.name.trim() || customerSaving} onClick={handleCreateCustomer}>
                {customerSaving ? 'กำลังบันทึก…' : 'บันทึกบริษัทใหม่'}
              </Button>
              <Button variant="secondary" className="text-xs" onClick={() => { setShowNewCustomer(false); setNewCustomer({ name: '', taxId: '', branch: 'สำนักงานใหญ่', address: '', phone: '' }); }}>
                ยกเลิก
              </Button>
            </div>
          </div>
        )}
      </div>
    );
  }

  function renderProjectView() {
    return (
      <div className="flex flex-col gap-3">
        <BackLink onClick={() => setView('hub')} />
        {!selectedCustomer ? (
          <div className="flex flex-col items-start gap-2 rounded-lg border border-dashed border-border-strong bg-surface px-3.5 py-4 text-sm text-text-muted">
            <span>ต้องเลือกลูกค้าก่อน — โครงการต้องผูกกับลูกค้าเสมอ</span>
            <Button variant="secondary" onClick={() => setView('customer')}>ไปที่ ลูกค้า</Button>
          </div>
        ) : (
          <div
            id="project-field"
            ref={(el) => { fieldRefs.current.project = el; }}
            tabIndex={-1}
            aria-invalid={fieldErrors.project ? true : undefined}
            aria-describedby={fieldErrors.project ? fieldErrorId('project-field') : undefined}
          >
            <span className="mb-1 block text-xs">โครงการ *</span>
            {selectedProject ? (
              <div className="flex items-center gap-2 rounded-[6px] border border-border-muted bg-surface px-2.5 py-1.5 text-sm">
                <Icon name="building" size={13} className="text-text-muted" />
                <span className="flex-1">{selectedProject.name}</span>
                <button type="button" onClick={() => setSelectedProject(null)} className="cursor-pointer border-0 bg-transparent p-0 text-text-faint">
                  <Icon name="close" size={14} />
                </button>
              </div>
            ) : (
              <div className="flex flex-col gap-1.5">
                <div className="flex flex-wrap gap-1.5">
                  {projectOptions.map((p) => (
                    <button key={p.id} type="button"
                      className="cursor-pointer rounded-pill border border-border-muted bg-surface px-2.5 py-1 text-xs"
                      onClick={() => { setSelectedProject(p); clearFieldError('project'); }}>
                      {p.name}
                    </button>
                  ))}
                  <button type="button"
                    className="cursor-pointer rounded-pill border border-dashed border-text-faint bg-transparent px-2.5 py-1 text-xs text-text-muted"
                    onClick={() => setShowNewProject((v) => !v)}>
                    <Icon name="plus" size={12} /> สร้างโครงการใหม่
                  </button>
                </div>
                {showNewProject && (
                  <div className="mt-1 flex gap-1.5">
                    <input value={newProjectName} onChange={(e) => setNewProjectName(e.target.value)}
                      placeholder="ชื่อโครงการ" className="flex-1" />
                    <Button variant="primary" className="px-3 py-1 text-xs" onClick={handleCreateProject} disabled={creatingProject}>
                      {creatingProject ? 'กำลังเพิ่ม…' : 'เพิ่ม'}
                    </Button>
                  </div>
                )}
              </div>
            )}
            {fieldErrors.project ? (
              <p id={fieldErrorId('project-field')} role="alert" className="mx-0 mb-0 mt-1.5 text-2xs font-bold text-danger">{fieldErrors.project}</p>
            ) : null}
          </div>
        )}

        {selectedProject && duplicateWarning && !duplicateDismissed ? (
          <div className="flex items-start gap-2 rounded-lg border border-warning-border bg-warning-bg px-3 py-2.5 text-xs text-warning-dark">
            <Icon name="triangleAlert" size={15} className="mt-0.5 shrink-0" />
            <span className="flex-1">
              พบดีลที่ผูกกับโครงการนี้อยู่แล้ว — {duplicateWarning.map((t, i) => (
                <span key={t.id}>
                  {i > 0 ? ', ' : ''}
                  <strong>{t.code}</strong> ({dealStageLabel(t.salesStage)?.label ?? t.status})
                </span>
              ))}. ตรวจสอบก่อนสร้างดีลซ้ำ
            </span>
            <button type="button" onClick={() => setDuplicateDismissed(true)} aria-label="ปิดคำเตือนดีลซ้ำ" className="shrink-0 text-warning-dark">
              <Icon name="close" size={13} />
            </button>
          </div>
        ) : null}
      </div>
    );
  }

  function renderContactView() {
    return (
      <div className="flex flex-col gap-4">
        <BackLink onClick={() => setView('hub')} />

        <div>
          <span className="mb-1.5 block text-sm font-bold text-text-secondary">ช่องทางดีล (entry channel)</span>
          <div className="grid grid-cols-3 gap-1.5" role="radiogroup" aria-label="ช่องทางดีล">
            {ENTRY_CHANNEL_OPTIONS.map((option) => {
              const selected = entryChannel === option.code;
              return (
                <button
                  key={option.code}
                  type="button"
                  role="radio"
                  aria-checked={selected}
                  onClick={() => setEntryChannel(option.code)}
                  className={`rounded-md border px-1.5 py-2.5 text-center text-xs font-extrabold leading-tight ${
                    selected ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text-secondary'
                  }`}
                >
                  {option.label}
                  <small className={`mt-0.5 block font-semibold ${selected ? 'text-primary-hover' : 'text-text-muted'}`}>{option.sub}</small>
                </button>
              );
            })}
          </div>
        </div>

        {!selectedCustomer ? (
          <div className="flex flex-col items-start gap-2 rounded-lg border border-dashed border-border-strong bg-surface px-3.5 py-4 text-sm text-text-muted">
            <span>เลือกลูกค้าก่อนจึงจะเพิ่มผู้ติดต่อได้</span>
            <Button variant="secondary" onClick={() => setView('customer')}>ไปที่ ลูกค้า</Button>
          </div>
        ) : (
          <div>
            <span className="mb-1 block text-xs">ผู้ติดต่อหลัก</span>
            {selectedContact ? (
              <div className="flex items-center gap-2 rounded-[6px] border border-border-muted bg-surface px-2.5 py-1.5 text-sm">
                <div className="flex-1">
                  <strong>{selectedContact.firstName} {selectedContact.lastName}</strong>
                  {selectedContact.position && <span className="ml-1.5 text-xs text-text-muted">{selectedContact.position}</span>}
                  {(selectedContact.email || selectedContact.phone) && (
                    <div className="mt-0.5 text-2xs text-text-muted">
                      {selectedContact.email}{selectedContact.email && selectedContact.phone ? ' · ' : ''}{selectedContact.phone}
                    </div>
                  )}
                </div>
                <button type="button" onClick={() => setSelectedContact(null)} className="cursor-pointer border-0 bg-transparent p-0 text-text-faint">
                  <Icon name="close" size={14} />
                </button>
              </div>
            ) : (
              <div className="flex flex-col gap-1.5">
                <div className="flex flex-wrap gap-1.5">
                  {contactOptions.map((c) => (
                    <button key={c.id} type="button"
                      className="cursor-pointer rounded-pill border border-border-muted bg-surface px-2.5 py-1 text-left text-xs"
                      onClick={() => setSelectedContact(c)}>
                      {c.firstName} {c.lastName}
                      {c.position ? <span className="ml-1 text-text-muted">({c.position})</span> : null}
                    </button>
                  ))}
                  <button type="button"
                    className="cursor-pointer rounded-pill border border-dashed border-text-faint bg-transparent px-2.5 py-1 text-xs text-text-muted"
                    onClick={() => setShowNewContact((v) => !v)}>
                    <Icon name="plus" size={12} /> เพิ่มผู้ติดต่อ
                  </button>
                </div>
                {showNewContact && (
                  <div className="mt-1 grid grid-cols-2 gap-1.5 rounded-[6px] border border-border-subtle bg-surface p-2.5">
                    <label className="m-0">
                      <span className="text-2xs">ชื่อ *</span>
                      <input value={newContact.firstName} onChange={(e) => setNewContact((p) => ({ ...p, firstName: e.target.value }))} placeholder="ชื่อ" />
                    </label>
                    <label className="m-0">
                      <span className="text-2xs">นามสกุล</span>
                      <input value={newContact.lastName} onChange={(e) => setNewContact((p) => ({ ...p, lastName: e.target.value }))} placeholder="นามสกุล" />
                    </label>
                    <label className="m-0">
                      <span className="text-2xs">ตำแหน่ง</span>
                      <input value={newContact.position} onChange={(e) => setNewContact((p) => ({ ...p, position: e.target.value }))} placeholder="เช่น ผู้จัดการ" />
                    </label>
                    <label className="m-0">
                      <span className="text-2xs">โทร</span>
                      <input value={newContact.phone} onChange={(e) => setNewContact((p) => ({ ...p, phone: e.target.value }))} placeholder="08x-xxx-xxxx" />
                    </label>
                    <label className="col-span-2 m-0">
                      <span className="text-2xs">อีเมล</span>
                      <input value={newContact.email} onChange={(e) => setNewContact((p) => ({ ...p, email: e.target.value }))} placeholder="email@company.com" />
                    </label>
                    <Button variant="primary" className="col-span-2 text-xs" onClick={handleCreateContact} disabled={creatingContact}>
                      {creatingContact ? 'กำลังเพิ่ม…' : 'เพิ่มผู้ติดต่อ'}
                    </Button>
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        <div className="flex items-start gap-2 rounded-lg border border-info-border bg-info-bg px-3 py-2.5 text-xs text-info-dark">
          <Icon name="info" size={15} className="mt-0.5 shrink-0" />
          <span>ผู้รับใบเสนอราคา (ผู้ออกแบบ / เจ้าของ / ผู้ซื้อ) จะเลือกตอนออกใบเสนอราคา ไม่ใช่ตอนนี้</span>
        </div>
      </div>
    );
  }

  function renderItemEditor(index) {
    const item = items[index];
    if (!item) return null;
    return (
      <div className="flex flex-col gap-3">
        <BackLink onClick={() => setEditingItemIndex(null)} label="กลับไปรายการสินค้า" />

        {item.source === 'catalog' ? (
          <div className="flex items-start gap-2 rounded-lg border border-accent bg-accent/10 px-3 py-2 text-xs font-bold text-accent-dark">
            <Icon name="check" size={14} className="mt-0.5 shrink-0" />
            <span>เติมสเปคและราคาตั้งต้นจากแคตตาล็อกให้แล้ว — ตรวจสอบและแก้ไขได้</span>
          </div>
        ) : null}

        <div className="grid grid-cols-2 gap-2">

          {/* Brand with catalog autocomplete */}
          <div className="relative m-0">
            <span className="mb-[3px] block text-xs">ชื่อยี่ห้อ *</span>
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
              <p id={fieldErrorId(`item-${index}-brand`)} role="alert" className="mx-0 mb-0 mt-1 text-2xs font-bold text-danger">{fieldErrors[`items.${index}.brand`]}</p>
            ) : null}
            {catalogFocus?.index === index && catalogFocus?.field === 'brand' && catalogResults.length > 0 && (
              <div className="absolute left-0 right-0 top-full z-[60] max-h-[200px] overflow-y-auto rounded-[6px] border border-border-subtle bg-surface shadow-[0_4px_16px_rgba(0,0,0,0.12)]">
                {catalogResults.map((cat) => (
                  // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- autocomplete option row; onMouseDown (not click) preserves input focus for typeahead
                  <div key={cat.priceId ?? cat.id} onMouseDown={() => applyCatalogItem(index, cat, 'brand')}
                    className="cursor-pointer border-b border-surface-subtle px-2.5 py-[7px] text-xs hover:bg-info-row-active"
                  >
                    <strong>{cat.factoryName || cat.brand}</strong>
                    {' — '}
                    {cat.collection || cat.productName || cat.productCode || '—'}
                    <span className="ml-1 text-text-muted">
                      {[cat.color, cat.sizeRaw || cat.size].filter(Boolean).join(' · ')}
                    </span>
                    {cat.price && (
                      <span className="ml-1.5 text-2xs font-semibold text-link">
                        {Number(cat.price).toLocaleString('th-TH', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} {cat.currency}
                      </span>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="relative m-0">
            <span className="mb-[3px] block text-xs">ชื่อรุ่น / Collection *</span>
            <input
              id={`item-${index}-model`}
              ref={(el) => { fieldRefs.current[`items.${index}.model`] = el; }}
              value={item.model}
              onChange={(e) => onModelInput(index, e.target.value)}
              onFocus={() => { setCatalogFocus({ index, field: 'model' }); if (item.model) onModelInput(index, item.model); }}
              onBlur={() => setTimeout(() => setCatalogFocus(null), 180)}
              placeholder="เช่น Stone, Elegance, L-Trim…"
              required
              aria-required="true"
              aria-invalid={fieldErrors[`items.${index}.model`] ? true : undefined}
              aria-describedby={fieldErrors[`items.${index}.model`] ? fieldErrorId(`item-${index}-model`) : undefined}
            />
            {fieldErrors[`items.${index}.model`] ? (
              <p id={fieldErrorId(`item-${index}-model`)} role="alert" className="mx-0 mb-0 mt-1 text-2xs font-bold text-danger">{fieldErrors[`items.${index}.model`]}</p>
            ) : null}
            {catalogFocus?.index === index && catalogFocus?.field === 'model' && catalogResults.length > 0 && (
              <div className="absolute left-0 right-0 top-full z-[60] max-h-[200px] overflow-y-auto rounded-[6px] border border-border-subtle bg-surface shadow-[0_4px_16px_rgba(0,0,0,0.12)]">
                {catalogResults.map((cat) => (
                  // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- autocomplete option row; onMouseDown (not click) preserves input focus for typeahead
                  <div key={cat.priceId ?? cat.id} onMouseDown={() => applyCatalogItem(index, cat, 'model')}
                    className="cursor-pointer border-b border-surface-subtle px-2.5 py-[7px] text-xs hover:bg-info-row-active"
                  >
                    <strong>{cat.factoryName || cat.brand}</strong>
                    {' — '}
                    {cat.collection || cat.productName || cat.productCode || '—'}
                    <span className="ml-1 text-text-muted">
                      {[cat.color, cat.sizeRaw || cat.size].filter(Boolean).join(' · ')}
                    </span>
                    {cat.price && (
                      <span className="ml-1.5 text-2xs font-semibold text-link">
                        {Number(cat.price).toLocaleString('th-TH', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} {cat.currency}
                      </span>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
          <label className="m-0">
            <span className="text-xs">สี *</span>
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
              <p id={fieldErrorId(`item-${index}-color`)} role="alert" className="mx-0 mb-0 mt-1 text-2xs font-bold text-danger">{fieldErrors[`items.${index}.color`]}</p>
            ) : null}
          </label>
          <label className="m-0">
            <span className="text-xs">เนื้อผิว *</span>
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
              <p id={fieldErrorId(`item-${index}-texture`)} role="alert" className="mx-0 mb-0 mt-1 text-2xs font-bold text-danger">{fieldErrors[`items.${index}.texture`]}</p>
            ) : null}
          </label>
          <label className="m-0">
            <span className="text-xs">ขนาด *</span>
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
              <p id={fieldErrorId(`item-${index}-size`)} role="alert" className="mx-0 mb-0 mt-1 text-2xs font-bold text-danger">{fieldErrors[`items.${index}.size`]}</p>
            ) : null}
          </label>
          <label className="m-0">
            <span className="text-xs">โรงงาน</span>
            <input value={item.factory} onChange={(e) => updateItem(index, 'factory', e.target.value)} placeholder="เช่น SCG Ceramics" />
          </label>

          {/* Unit basis toggle */}
          <div className="col-span-2 m-0">
            <span className="mb-1.5 block text-xs">หน่วยที่ใช้สั่ง *</span>
            <div className="flex flex-wrap items-center gap-4">
              {[{ value: 'PIECE', label: 'แผ่น' }, { value: 'SQM', label: 'ตร.ม.' }].map((opt) => (
                <label key={opt.value} className="flex cursor-pointer items-center gap-1.5 text-sm">
                  <input type="radio" name={`unitBasis-${index}`} value={opt.value}
                    checked={(item.unitBasis || 'PIECE') === opt.value}
                    onChange={() => updateItem(index, 'unitBasis', opt.value)}
                    className="h-4 w-4 cursor-pointer accent-info-dot" />
                  <strong>{opt.label}</strong>
                </label>
              ))}
              {item.sqmPerPiece && (
                <span className="text-2xs text-text-muted">· 1 แผ่น = {item.sqmPerPiece} ตร.ม.</span>
              )}
            </div>
          </div>

          {/* Primary qty input */}
          {(item.unitBasis || 'PIECE') === 'PIECE' ? (
            <>
              <label className="m-0">
                <span className="text-xs">จำนวน (แผ่น) *</span>
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
                  <p id={fieldErrorId(`item-${index}-qty`)} role="alert" className="mx-0 mb-0 mt-1 text-2xs font-bold text-danger">{fieldErrors[`items.${index}.qty`]}</p>
                ) : null}
              </label>
              <div className="m-0">
                <span className="mb-1 block text-xs">พื้นที่รวม (ตร.ม.)</span>
                <div className={`rounded-[6px] border border-border-subtle bg-surface-muted px-2.5 py-[7px] text-sm ${item.qtySqm ? 'text-icon-muted' : 'text-text-muted'}`}>
                  {item.qtySqm ? `${Number(item.qtySqm).toFixed(3)} ตร.ม.` : item.sqmPerPiece ? 'กรอกจำนวนแผ่นก่อน' : '—'}
                </div>
              </div>
            </>
          ) : (
            <>
              <label className="m-0">
                <span className="text-xs">พื้นที่ (ตร.ม.) *</span>
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
                  <p id={fieldErrorId(`item-${index}-qtySqm`)} role="alert" className="mx-0 mb-0 mt-1 text-2xs font-bold text-danger">{fieldErrors[`items.${index}.qtySqm`]}</p>
                ) : null}
              </label>
              <div className="m-0">
                <span className="mb-1 block text-xs">จำนวน (แผ่น)</span>
                <div className={`rounded-[6px] border border-border-subtle bg-surface-muted px-2.5 py-[7px] text-sm ${item.qty ? 'text-icon-muted' : 'text-text-muted'}`}>
                  {item.qty ? `${item.qty} แผ่น` : item.sqmPerPiece ? 'กรอกพื้นที่ก่อน' : '—'}
                </div>
              </div>
            </>
          )}

          {item.catalogPrice != null ? (
            <div className="col-span-2 m-0 flex items-center justify-between rounded-md border border-border-input bg-surface-muted px-3 py-2.5">
              {/* Two-tone hierarchy restored here (review-remediation, fix/ui-contrast-tokens):
                  both label and caveat used to share --color-text-muted after the AA fix, which
                  flattened the label/caveat distinction to weight alone. The label now uses
                  --color-icon-muted (#475569, 7.24:1+ on this row's surfaces) so it reads a step
                  darker than the caveat's --color-text-muted (#5c6b80, 5.19:1) — both clear AA. */}
              <span className="text-2xs font-bold text-icon-muted">
                ราคาอ้างอิง (แคตตาล็อก) <span className="font-medium text-text-muted">— ราคาขายจริงมาจากขั้นคำขอราคา</span>
              </span>
              <span className="text-md font-extrabold">
                {Number(item.catalogPrice).toLocaleString('th-TH', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} {item.catalogCurrency}
              </span>
            </div>
          ) : null}

          {/* ราคาตั้ง (ประมาณการ) — hidden entirely (not just blank) whenever the FX/markup
              fetch failed, per the "never fall back to markup=1" rule in dealEstimatePricing.js. */}
          {estimateReady && item.catalogPrice != null ? (() => {
            const lineEstimate = computeItemEstimateThb(item, estimateContext);
            return (
              <div data-testid={`item-editor-estimate-${index}`} className="col-span-2 m-0 flex items-center justify-between rounded-md border border-accent bg-success-bg px-3 py-2.5">
                <span className="text-2xs font-bold text-icon-muted">
                  ราคาตั้ง (ประมาณการ) <span className="font-medium text-text-muted">— ราคาแคตตาล็อก × อัตราแลกเปลี่ยน × ตัวคูณ CEO ไม่ใช่ราคาขายจริง</span>
                </span>
                {lineEstimate.ok ? (
                  <span className="text-right text-md font-extrabold">
                    {formatThb(lineEstimate.unitThb)} บาท/หน่วย
                    <br />
                    <span className="text-xs">รวม {formatThb(lineEstimate.total)} บาท</span>
                  </span>
                ) : (
                  <span className="text-xs font-bold text-text-muted">ยังคำนวณไม่ได้ ({estimateReasonLabel(lineEstimate.reason)})</span>
                )}
              </div>
            );
          })() : null}
        </div>
      </div>
    );
  }

  function renderItemsView() {
    if (editingItemIndex != null && items[editingItemIndex]) {
      return renderItemEditor(editingItemIndex);
    }
    return (
      <div className="flex flex-col gap-3">
        <BackLink onClick={() => setView('hub')} />
        <p className="text-xs text-text-muted">
          รายการที่เพิ่มตรงนี้เป็นข้อมูลเบื้องต้นของดีลเท่านั้น — ฝ่ายนำเข้าจะเห็นก็ต่อเมื่อสร้างคำขอราคาจากหน้าดีลแล้วส่งให้ฝ่ายนำเข้าเท่านั้น (ไม่บังคับตอนนี้)
        </p>
        {items.length === 0 ? (
          <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-border-strong bg-surface px-5 py-8 text-center">
            <Icon name="fileText" size={28} className="text-text-faint" />
            <p className="text-sm font-extrabold text-text">ยังไม่มีรายการสินค้า</p>
            <p className="text-xs text-text-muted">ค้นหาจากแคตตาล็อก หรือเพิ่มสินค้าที่ยังไม่มีในระบบเอง</p>
            <div className="mt-1 flex flex-wrap justify-center gap-2">
              <Button variant="primary" className="text-xs" onClick={addItem}>
                <Icon name="plus" size={13} /> ค้นหาสินค้า
              </Button>
              <Button variant="secondary" className="text-xs" onClick={addItem}>
                เพิ่มสินค้าเอง (custom)
              </Button>
            </div>
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            {items.map((item, index) => (
              <div key={index} className="flex items-start gap-3 rounded-xl border border-border bg-surface px-3.5 py-3">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-extrabold text-text">
                    {[item.brand, item.model].filter(Boolean).join(' ') || `รายการที่ ${index + 1}`}
                  </p>
                  <div className="mt-1 flex flex-wrap items-center gap-1.5">
                    {item.color ? <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-2xs font-bold text-text-secondary">{item.color}</span> : null}
                    {item.texture ? <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-2xs font-bold text-text-secondary">{item.texture}</span> : null}
                    {item.factory ? <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-2xs font-bold text-text-secondary">{item.factory}</span> : null}
                    {item.source === 'catalog' ? (
                      <StatusBadge tone="teal">✓ จากแคตตาล็อก</StatusBadge>
                    ) : (
                      <StatusBadge tone="indigo">custom</StatusBadge>
                    )}
                  </div>
                </div>
                <div className="shrink-0 text-right text-xs font-extrabold text-text">
                  {(item.unitBasis || 'PIECE') === 'SQM'
                    ? (item.qtySqm ? `${item.qtySqm} ตร.ม.` : '—')
                    : (item.qty ? `${item.qty} แผ่น` : '—')}
                  {estimateReady ? (
                    <div data-testid={`item-estimate-${index}`} className="mt-0.5 font-semibold text-2xs text-accent-dark">
                      {itemsEstimate?.perItem[index]?.ok ? `${formatThb(itemsEstimate.perItem[index].total)} บาท` : '—'}
                    </div>
                  ) : null}
                </div>
                <div className="flex shrink-0 flex-col gap-1">
                  <Button variant="icon" aria-label={`แก้ไขรายการที่ ${index + 1}`} onClick={() => setEditingItemIndex(index)}>
                    <Icon name="pencil" size={14} />
                  </Button>
                  <Button variant="icon" aria-label={`ลบรายการที่ ${index + 1}`} className="text-danger" onClick={() => removeItem(index)}>
                    <Icon name="close" size={14} />
                  </Button>
                </div>
              </div>
            ))}
            {itemsEstimate?.total != null ? (
              <div data-testid="items-estimate-total" className="flex items-center justify-between rounded-xl border border-accent bg-success-bg px-3.5 py-2.5">
                <span className="text-xs font-extrabold text-text">
                  ราคาตั้งรวม (ประมาณการ)
                  {itemsEstimate.uncomputableCount > 0 ? (
                    <span data-testid="items-estimate-uncomputable" className="ml-1.5 font-semibold text-2xs text-text-muted">
                      · ยังคำนวณไม่ได้ {itemsEstimate.uncomputableCount} รายการ
                    </span>
                  ) : null}
                </span>
                <span className="text-sm font-extrabold text-accent-dark">{formatThb(itemsEstimate.total)} บาท</span>
              </div>
            ) : null}
            <Button variant="secondary" onClick={addItem}>
              <Icon name="plus" size={14} /> เพิ่มรายการสินค้า
            </Button>
          </div>
        )}
      </div>
    );
  }

  function renderDetailsView() {
    return (
      <div className="flex flex-col gap-4">
        <BackLink onClick={() => setView('hub')} />
        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          ชื่อดีล <span className="font-semibold text-text-muted">(ไม่บังคับ — ค่าเริ่มต้นคือชื่อลูกค้า)</span>
          <input
            value={dealTitle}
            onChange={(e) => setDealTitle(e.target.value)}
            placeholder={selectedCustomer?.name || 'ชื่อดีล'}
          />
        </label>

        <div>
          <span className="mb-1.5 block text-sm font-bold text-text-secondary">ความสำคัญ <span className="font-semibold text-text-muted">(ไม่บังคับ)</span></span>
          <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="ความสำคัญ">
            {PRIORITY_OPTIONS.map((option) => {
              const selected = priority === option.code;
              return (
                <button
                  key={option.code}
                  type="button"
                  role="radio"
                  aria-checked={selected}
                  onClick={() => setPriority(option.code)}
                  className={`rounded-full border px-3 py-1.5 text-xs font-bold ${
                    selected ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text-muted'
                  }`}
                >
                  {option.label}
                </button>
              );
            })}
          </div>
        </div>

        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          หมายเหตุ
          <textarea value={form.note} onChange={(e) => setForm((f) => ({ ...f, note: e.target.value }))} rows={3} placeholder="ข้อมูลเพิ่มเติม (ถ้ามี)" />
        </label>
      </div>
    );
  }

  function renderReviewView() {
    const { errors: reviewErrors, order: reviewOrder } = validateTicketForm({
      customer: selectedCustomer, project: selectedProject, items,
    });
    return (
      <div className="flex flex-col gap-3">
        <BackLink onClick={() => setView('hub')} />

        {reviewOrder.length > 0 ? (
          <div className="rounded-lg border border-danger-border bg-danger-bg px-3 py-2.5">
            <p className="flex items-center gap-1.5 text-xs font-extrabold text-danger-dark">
              <Icon name="triangleAlert" size={14} /> ยังกรอกไม่ครบ {reviewOrder.length} รายการ
            </p>
            <ul className="mt-1.5 flex list-disc flex-col gap-1 pl-4 text-2xs text-danger-dark">
              {reviewOrder.map((key) => (
                <li key={key}>
                  {reviewErrors[key]}
                  {' — '}
                  <button
                    type="button"
                    className="font-extrabold underline"
                    onClick={() => { setFieldErrors(reviewErrors); jumpToField(key); }}
                  >
                    ไปที่ขั้นตอน
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ) : null}

        <div className="rounded-xl border border-border bg-surface">
          <div className="flex items-center justify-between border-b border-border px-3.5 py-2.5">
            <h4 className="text-sm font-extrabold text-text">ลูกค้า</h4>
            <button type="button" className="text-xs font-extrabold text-primary" onClick={() => setView('customer')}>แก้ไข</button>
          </div>
          <div className="px-3.5 py-2.5 text-sm">
            {selectedCustomer ? selectedCustomer.name : <span className="text-text-muted">ยังไม่ได้เลือก</span>}
          </div>
        </div>

        <div className="rounded-xl border border-border bg-surface">
          <div className="flex items-center justify-between border-b border-border px-3.5 py-2.5">
            <h4 className="text-sm font-extrabold text-text">โครงการ</h4>
            <button type="button" className="text-xs font-extrabold text-primary" onClick={() => setView('project')}>แก้ไข</button>
          </div>
          <div className="px-3.5 py-2.5 text-sm">
            {selectedProject ? selectedProject.name : <span className="text-text-muted">ยังไม่ได้เลือก</span>}
          </div>
        </div>

        <div className="rounded-xl border border-border bg-surface">
          <div className="flex items-center justify-between border-b border-border px-3.5 py-2.5">
            <h4 className="text-sm font-extrabold text-text">ผู้ติดต่อ & ช่องทางดีล</h4>
            <button type="button" className="text-xs font-extrabold text-primary" onClick={() => setView('contact')}>แก้ไข</button>
          </div>
          <div className="flex flex-col gap-0.5 px-3.5 py-2.5 text-sm">
            <span>ช่องทาง: {entryChannelLabel}</span>
            <span>ผู้ติดต่อ: {selectedContact ? `${selectedContact.firstName} ${selectedContact.lastName}`.trim() : <span className="text-text-muted">ไม่ได้ระบุ</span>}</span>
          </div>
        </div>

        <div className="rounded-xl border border-border bg-surface">
          <div className="flex items-center justify-between border-b border-border px-3.5 py-2.5">
            <h4 className="text-sm font-extrabold text-text">รายการสินค้า · {items.length}</h4>
            <button type="button" className="text-xs font-extrabold text-primary" onClick={() => setView('items')}>แก้ไข</button>
          </div>
          <div className="px-3.5 py-2.5">
            {items.length === 0 ? (
              <p className="text-xs text-text-muted">ยังไม่มีรายการสินค้า — เพิ่มได้ภายหลังจากหน้าดีล (ไม่บังคับตอนนี้)</p>
            ) : (
              <div className="flex flex-col gap-1">
                {items.map((item, index) => (
                  <div key={index} className="flex items-center justify-between text-sm">
                    <span className="truncate">{[item.brand, item.model].filter(Boolean).join(' ') || `รายการที่ ${index + 1}`}</span>
                    <span className="shrink-0 text-text-muted">
                      {(item.unitBasis || 'PIECE') === 'SQM' ? `${item.qtySqm || 0} ตร.ม.` : `${item.qty || 0} แผ่น`}
                    </span>
                  </div>
                ))}
                {itemsEstimate?.total != null ? (
                  <div data-testid="review-estimate-total" className="mt-1 flex items-center justify-between border-t border-border-subtle pt-1.5 text-sm font-extrabold text-text">
                    <span>
                      ราคาตั้งรวม (ประมาณการ)
                      {itemsEstimate.uncomputableCount > 0 ? (
                        <span className="ml-1.5 font-semibold text-2xs text-text-muted">
                          · ยังคำนวณไม่ได้ {itemsEstimate.uncomputableCount} รายการ
                        </span>
                      ) : null}
                    </span>
                    <span className="text-accent-dark">{formatThb(itemsEstimate.total)} บาท</span>
                  </div>
                ) : null}
              </div>
            )}
          </div>
        </div>

        <div className="rounded-xl border border-border bg-surface">
          <div className="flex items-center justify-between border-b border-border px-3.5 py-2.5">
            <h4 className="text-sm font-extrabold text-text">รายละเอียดดีล</h4>
            <button type="button" className="text-xs font-extrabold text-primary" onClick={() => setView('details')}>แก้ไข</button>
          </div>
          <div className="flex flex-col gap-0.5 px-3.5 py-2.5 text-sm">
            <span>ชื่อดีล: {dealTitle.trim() || (selectedCustomer?.name ?? '—')}</span>
            <span>ความสำคัญ: {priorityLabel}</span>
            <span>หมายเหตุ: {form.note.trim() || <span className="text-text-muted">ไม่มี</span>}</span>
          </div>
        </div>

        <div className="flex items-start gap-2 rounded-lg border border-info-border bg-info-bg px-3 py-2.5 text-xs text-info-dark">
          <Icon name="info" size={15} className="mt-0.5 shrink-0" />
          <span>สร้างดีลแล้วยังไม่มีราคา — ขั้นต่อไปคือ “สร้างคำขอราคา” จากหน้าดีล</span>
        </div>

        {error ? <div className="form-error" role="alert">{error}</div> : null}
      </div>
    );
  }

  function renderFooter() {
    if (view === 'hub' || view === 'review') {
      return (
        <>
          <Button variant="secondary" onClick={onClose} disabled={loading}>ยกเลิก</Button>
          <Button variant="secondary" onClick={handleSaveDraft} disabled={loading}>บันทึกร่าง</Button>
          <Button type="submit" form="ticket-create-form" variant="primary" disabled={loading || !canCreateNow} data-testid="ticket-create-submit">
            <Icon name="fileText" />
            {loading ? 'กำลังสร้าง…' : 'สร้างดีล'}
          </Button>
        </>
      );
    }
    if (view === 'items' && editingItemIndex != null) {
      return (
        <>
          <Button variant="secondary" className="text-danger" onClick={() => removeItem(editingItemIndex)}>ลบ</Button>
          <Button variant="primary" onClick={() => setEditingItemIndex(null)}>
            <Icon name="check" size={14} /> บันทึกรายการ
          </Button>
        </>
      );
    }
    return (
      <Button variant="primary" className="ml-auto" onClick={() => setView('hub')}>
        <Icon name="check" size={14} /> {view === 'items' ? `เสร็จสิ้น · ${items.length} รายการ` : 'เสร็จสิ้น'}
      </Button>
    );
  }

  // CRITICAL (HTML implicit submission, fix/form-enter-submits-real-records): this <form> wraps
  // all 7 sub-views below, and a form with no submit button but exactly ONE field that blocks
  // implicit submission fires a real 'submit' event on Enter with no button ever pressed. That was
  // true for DETAILS (renderDetailsView's ชื่อดีล input is its only such field -- the textarea and
  // role="radio" priority chips don't count) and, before a project/customer is picked, for PROJECT
  // and CUSTOMER too — `submit()` used to run regardless of `view`, so typing a deal title and
  // pressing Enter filed a real deal (confirmed in a real browser; jsdom does not implement
  // implicit submission, which is why the suite stayed green with the bug live — see
  // TicketCreateModal.test.jsx's `fireEvent.submit(form)` cases).
  //
  // Gated on `view === 'hub' || view === 'review'`, matching renderFooter()'s own condition for
  // when a real `type="submit" form="ticket-create-form"` button exists (above) — not `view ===
  // 'review'` alone: CUSTOMER/PROJECT/CONTACT/ITEMS/DETAILS never render one, so they can never
  // legitimately submit, implicit or explicit, and are unconditionally blocked here. HUB is
  // different from every other non-REVIEW view — a linked submit button is in the DOM the whole
  // time HUB is showing (it lives in the modal's footer, a sibling of this <form>, wired via
  // `form=` — see Modal.jsx), so HUB never satisfies "no submit button" and was never part of this
  // bug class; narrowing this gate to review-only would silently turn that real, working button
  // into a dead click instead of closing a hole that was never open on HUB.
  function handleFormSubmit(event) {
    if (view !== 'hub' && view !== 'review') {
      event.preventDefault();
      return;
    }
    submit(event);
  }

  return (
    <Modal
      title="สร้างดีลใหม่"
      subtitle="จัดทีละหัวข้อได้ตามสะดวก — บันทึกร่างได้ทุกเมื่อ ไม่บังคับกรอกให้ครบในครั้งเดียว"
      onClose={onClose}
      footer={renderFooter()}
      testId="ticket-create-modal"
      size="lg"
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
      <form id="ticket-create-form" onSubmit={handleFormSubmit} noValidate>
        {view === 'hub' && renderHub()}
        {view === 'customer' && renderCustomerView()}
        {view === 'project' && renderProjectView()}
        {view === 'contact' && renderContactView()}
        {view === 'items' && renderItemsView()}
        {view === 'details' && renderDetailsView()}
        {view === 'review' && renderReviewView()}
      </form>
    </Modal>
  );
}
