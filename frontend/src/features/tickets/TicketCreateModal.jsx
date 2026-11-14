import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { fieldErrorId } from '../../components/common/FormField.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { dealStageLabel, formatThaiDate, OFFERED_TO_LABEL, ticketPriorityLabel } from '../../utils/format.js';
import { formatCatalogPrice } from './catalogPriceDisplay.js';
import {
  applyCatalogPick,
  applyDescriptiveFieldEdit,
  CatalogAutocompleteField,
  ITEM_FIELD_META,
  ItemField,
  isRequiredItemField,
  missingQtyMessage,
  REQUIRED_ITEM_FIELD_LABELS,
  requiredItemFieldErrors,
  requiredQtyField,
  searchCatalog,
} from './ticketItemFields.jsx';
import {
  MAX_DRAFTS, deleteDraft, hasMeaningfulDraftData, listDrafts, loadDraft, migrateLegacyDraft, saveDraft,
} from './dealDrafts.js';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';

// Units are never GUESSED from a size string: this modal resolves ตร.ม./แผ่น like the quotation
// item editor, via ticketItemFields.jsx's applyCatalogPick (resolveTileSqmPerPiece): the
// catalog's own sqm_per_piece, never for a per_linear_m row; otherwise nothing is invented and the
// rep types both quantities by hand.

const emptyItem = () => ({
  brand: '', model: '', color: '', texture: '', size: '', factory: '',
  unitBasis: 'PIECE', qty: 1, qtySqm: '', sqmPerPiece: null,
  // source ('catalog' | 'custom') + the catalog's reference price/currency/priceUnit are UI-only — never sent in
  // the onSubmit payload (see submit() below).
  source: 'custom', catalogPrice: null, catalogCurrency: null, catalogPriceUnit: null,
  // Padana's A01/A02 quality code, shown as a badge. NEVER written into ยี่ห้อ: `grade` is only
  // populated by factory Padana (values A01/A02), and the brand a rep means is the factory
  // (Padana, Vives, LEA, Bode…), which is why ยี่ห้อ and โรงงาน are one field.
  catalogGrade: null,
  // catalogPriceId/catalogProductCode ARE sent (see submit()'s items map below) — the catalog identity picked
  // here, persisted on ticket_item (V110) so PricingRequestCreateModal can seed its own catalog link without a
  // re-search.
  catalogPriceId: null, catalogProductCode: '',
  // "จากสต็อก" is only a flag (walk 4): the CEO prices stock lines, so sales types no price here.
  sourcedFromStock: false,
});

// ── client-side drafts (no server draft entity) ──────────
// Multiple named drafts + debounced autosave. All storage access lives in dealDrafts.js (which
// owns never-throwing localStorage access); this file orchestrates WHEN to call it (debounce,
// beforeunload, close confirmation) and renders the picker. The backend's own "draft" is the
// DRAFT ticket status created on a real submit.

function defaultNextFollowUpAt() {
  // Lazy-computed (never at module scope): the stage-advance readiness gate refuses every forward move while
  // next_follow_up_at is null, so a deal created without one could not be advanced at all. +14 days is a
  // sensible starting point the rep can change on the รายละเอียดดีล step.
  return new Date(Date.now() + 14 * 864e5).toISOString().slice(0, 10);
}

/** "17 ก.ย. 2569 14:32" — used by the draft picker rows. */
function formatDraftSavedAt(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const time = date.toLocaleTimeString('th-TH', { hour: '2-digit', minute: '2-digit' });
  return `${formatThaiDate(iso)} ${time}`;
}

/** Thai copy for the non-blocking "autosave failed" notice, keyed by dealDrafts.js's reason codes. */
function draftSaveNoticeCopy(reason) {
  if (reason === 'quota') return 'พื้นที่จัดเก็บในเบราว์เซอร์เต็ม';
  if (reason === 'limit') return `บันทึกร่างใหม่ไม่ได้ — มีร่างครบ ${MAX_DRAFTS} รายการแล้ว ลบร่างเก่าก่อน`;
  return 'เบราว์เซอร์นี้ไม่รองรับการบันทึกร่าง';
}

// ── เสนอแก่ — backend th.co.glr.hr.ticket.EntryChannel ──
const ENTRY_CHANNEL_OPTIONS = [
  { code: 'DESIGNER_LED', label: 'ผู้ออกแบบนำ', sub: 'Designer-led' },
  { code: 'OWNER_DIRECT', label: 'เจ้าของตรง', sub: 'Owner-direct' },
  { code: 'BUYER_DIRECT', label: 'ผู้ซื้อตรง', sub: 'Buyer-direct' },
];

// CreateTicketRequest.priority is optional and mockApi already defaults it to 'NORMAL' when absent — reusing
// the canonical labels from format.js keeps this picker's wording identical to wherever priority is displayed
// later.
const PRIORITY_OPTIONS = ['LOW', 'NORMAL', 'HIGH'].map((code) => ({ code, label: ticketPriorityLabel(code).label }));

// ── validation ──────────────────────────────────────────────────────
// Every invalid field is reported at once (keyed like the `fieldErrors` state: 'customer',
// 'project', `items.<index>.<field>`), so a long form doesn't force fix-and-resubmit one message
// at a time. REQUIRED_ITEM_FIELD_LABELS/requiredItemFieldErrors/requiredQtyField/missingQtyMessage
// live in ticketItemFields.jsx, shared with TicketDetailPage's edit-items mode (see its comments
// for the สี/เนื้อผิว-are-optional and per-basis-qty reasoning).
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
    sourcedFromStock: z.boolean().optional(),
  }).superRefine((item, ctx) => {
    const fieldErrors = requiredItemFieldErrors(item);
    for (const [field, message] of Object.entries(fieldErrors)) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: [field], message });
    }
    const qtyField = requiredQtyField(item.unitBasis);
    if (!item[qtyField] || Number(item[qtyField]) <= 0) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: [qtyField], message: missingQtyMessage(item.unitBasis, rowNumber) });
    }
  });
}

const customerRequiredSchema = z.any().refine((v) => v != null, 'กรุณาเลือกบริษัท/ลูกค้า');
// Mirrors TicketService.create: every new deal belongs to a โครงการ.
const projectRequiredSchema = z.any().refine((v) => v != null, 'กรุณาเลือกโครงการ (1 ดีล = 1 Ticket ภายใต้โครงการ)');
// Descriptive-only (it drives no behaviour), but it must be STATED; see the no-default comment on
// the entryChannel state below.
const entryChannelRequiredSchema = z.any().refine((v) => v != null, 'กรุณาเลือกว่าดีลนี้เสนอแก่ใคร');

/** Validates the whole form and returns every invalid field, plus `order`: those same keys in on-screen
 * top-to-bottom order, so submit can focus the first one without depending on zod's internal issue ordering. */
function validateTicketForm({ customer, project, entryChannel, items }) {
  const errors = {};
  const order = [];

  const customerResult = customerRequiredSchema.safeParse(customer);
  if (!customerResult.success) {
    errors.customer = customerResult.error.issues[0].message;
    order.push('customer');
  }

  // A project can only be chosen once a customer is selected (the picker doesn't even render before then) — like
  // the original sequential checks, only flag it once the customer is valid.
  if (customerResult.success) {
    const projectResult = projectRequiredSchema.safeParse(project);
    if (!projectResult.success) {
      errors.project = projectResult.error.issues[0].message;
      order.push('project');
    }
  }

  // Unlike project, NOT nested inside `if (customerResult.success)` — the channel picker is reachable and
  // answerable regardless of whether a customer is chosen yet.
  const entryChannelResult = entryChannelRequiredSchema.safeParse(entryChannel);
  if (!entryChannelResult.success) {
    errors.entryChannel = entryChannelResult.error.issues[0].message;
    order.push('entryChannel');
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

// The modal renders at size="lg" (see the bottom of this file), so the dropdown can grow with the
// panel; a smaller maxHeight squeezed the company picker's results to ~3 rows.
const SEARCH_SELECT_OPTIONS_MAX_HEIGHT = 380;

function SearchSelect({ id, label, value, onSelect, placeholder, options, onSearch, searchValue, onSearchChange, loading, renderOption, renderValue, createNewLabel, onCreateNew, inputRef, error }) {
  const [open, setOpen] = useState(false);
  // Hand-wired aria contract (no <FormField> here — the control renders either a value chip or a search input
  // depending on state, which doesn't fit FormField's single-child-with-matching-id model).
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
            // maxHeight stays inline: it's sourced from SEARCH_SELECT_OPTIONS_MAX_HEIGHT (a JS constant), which a static
            // Tailwind arbitrary-value class can't reference.
            <div className="absolute left-0 right-0 top-full z-50 overflow-y-auto rounded-[6px] border border-border-subtle bg-surface shadow-[0_4px_16px_rgba(0,0,0,0.1)]" style={{ maxHeight: SEARCH_SELECT_OPTIONS_MAX_HEIGHT }}>
              {onCreateNew && (
                // First and sticky, like DealCustomerCard's "+ เพิ่มลูกค้าใหม่": always reachable
                // without scrolling; the opaque bg keeps scrolled options from showing through.
                // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- dropdown action row; onMouseDown (not click) preserves input focus
                <div onMouseDown={() => { setOpen(false); onCreateNew(); }}
                  className="sticky top-0 z-10 flex cursor-pointer items-center gap-1.5 border-b border-border-subtle bg-surface-muted px-3 py-2 text-xs font-semibold text-link hover:bg-info-row-active"
                >
                  <Icon name="plus" size={13} />
                  {createNewLabel || 'สร้างรายการใหม่'}
                </div>
              )}
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

// ItemFieldLabel/ItemField/CatalogOption/CatalogAutocompleteField moved to ticketItemFields.jsx (imported
// above) — shared with TicketDetailPage's edit-items mode.

/**
 * One accordion section (IA-deal-form.md). Same APG structure as CollapsibleSection — a heading
 * wrapping the disclosure button, the body a region labelled by it — but controlled and open-only,
 * so that component is not a drop-in. The header line reads title · summary once done · badge
 * (จำเป็น / ไม่บังคับ / ✓) · chevron. `body()` runs only while open; `below` renders under the card
 * either way.
 */
function Section({ id, title, required, done, summary, open, onOpen, body, below }) {
  const headerRef = useRef(null);
  const headerId = `deal-section-${id}-header`;
  const bodyId = `deal-section-${id}-body`;
  // Phones: an opened section scrolls its header into view. React runs this child effect before
  // the form's pending-focus effect in the same commit, so a field's smooth scroll still wins.
  useEffect(() => {
    if (open) headerRef.current?.scrollIntoView?.({ block: 'nearest' });
  }, [open]);
  return (
    <>
      <div data-testid={`deal-section-${id}`} className={`rounded-xl border bg-surface ${open ? 'border-primary' : 'border-border'}`}>
        {/* The button inherits its font (index.css), so the heading must not change it. */}
        <h3 className="m-0 text-[length:inherit] font-normal">
          <button
            type="button"
            id={headerId}
            ref={headerRef}
            aria-expanded={open}
            aria-controls={open ? bodyId : undefined}
            // Open-only by ruling (no toggle), so the open header announces that it cannot collapse.
            aria-disabled={open || undefined}
            onClick={onOpen}
            // No preflight in this app: a raw <button> keeps the UA's grey bevel unless reset here.
            className="flex min-h-11 w-full cursor-pointer items-center gap-3 rounded-xl border-0 bg-transparent px-3.5 py-2.5 text-left hover:bg-surface-hover"
          >
            <span className="shrink-0 text-sm font-extrabold text-text">{title}</span>
            <span className="min-w-0 flex-1 truncate text-xs text-text-muted">{done ? summary : null}</span>
            {done ? (
              <span role="img" aria-label="เสร็จแล้ว" className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-success text-white">
                <Icon name="check" size={12} />
              </span>
            ) : required ? (
              <span className="shrink-0 rounded-full bg-danger-bg px-1.5 text-2xs font-extrabold text-danger-dark">จำเป็น</span>
            ) : (
              <span className="shrink-0 rounded-full bg-surface-subtle px-1.5 text-2xs font-semibold text-text-muted">ไม่บังคับ</span>
            )}
            <Icon name={open ? 'chevronDown' : 'chevronRight'} size={16} className="shrink-0 text-text-faint" />
          </button>
        </h3>
        {open ? (
          <div id={bodyId} role="region" aria-labelledby={headerId} className="border-t border-border-subtle px-3.5 pb-4 pt-3">
            {body()}
          </div>
        ) : null}
      </div>
      {below}
    </>
  );
}

// ── main modal ────────────────────────────────────────────────────────────────

export function TicketCreateModal({ user, onClose, onSubmit, initialItems }) {
  // initialItems (only passed by TicketListPage) means the caller wants a NEW deal seeded with
  // specific items, never a reason to resurrect an unrelated old draft over them.
  const hasInitialItems = Boolean(initialItems?.length);

  // One-time legacy-draft migration (the old single `glr:draft-deal` key -> an id'd draft) plus the
  // initial picker decision, in ONE lazy initialiser so the migration has run by the time
  // listDrafts() is read for the picker.
  const [{ initialDraftList, showPickerInitially }] = useState(() => {
    migrateLegacyDraft();
    const drafts = listDrafts();
    return { initialDraftList: drafts, showPickerInitially: !hasInitialItems && drafts.length > 0 };
  });

  // The picker shows first: the form below always MOUNTS blank/initialItems-seeded, and a restored
  // draft is applied onto it via applyDraftToForm() once the rep picks "เปิดต่อ". Mounting the form
  // fresh per draft id would drop mid-edit state like `catalogResults`/`catalogFocus` on every
  // restore.
  const [showPicker, setShowPicker] = useState(showPickerInitially);
  const [draftList, setDraftList] = useState(initialDraftList);
  // The draft id this session is autosaving into — null until either a draft is restored (เปิดต่อ) or the first
  // meaningful autosave creates one.
  const [currentDraftId, setCurrentDraftId] = useState(null);
  const [draftDeleteTarget, setDraftDeleteTarget] = useState(null); // picker row pending ลบ confirmation
  const [closeConfirmOpen, setCloseConfirmOpen] = useState(false);
  const [closeFlushFailed, setCloseFlushFailed] = useState(false);
  // Only meaningful when closeFlushFailed is true: was a persisted copy of this draft ALREADY on
  // disk before the failed close-time flush? If so it is untouched (saveDraft() never wrote) and
  // only the latest edits are at risk, so the copy is softer than "everything will be lost".
  const [closeHasOlderCopy, setCloseHasOlderCopy] = useState(false);

  // The one open accordion section (a `sections` id, or null). Changed only by goToSection().
  const [openSection, setOpenSection] = useState('customer');
  // fieldErrors key waiting to be scrolled+focused once its section/item editor has actually
  // mounted it (see the effect below).
  const [pendingFocusKey, setPendingFocusKey] = useState(null);

  const [form, setForm] = useState({ note: '' });
  const [dealTitle, setDealTitle] = useState('');
  const [priority, setPriority] = useState('NORMAL');
  // No default on purpose. A pre-selected channel meant a rep who never noticed this control silently recorded
  // "designer-led", and because a value is ALWAYS sent the backend could not tell that apart from a deliberate
  // choice — so no backend default could rescue it.
  const [entryChannel, setEntryChannel] = useState(null);
  // Defaulted, not left blank, and this matters: the stage-advance readiness gate refuses EVERY forward move
  // while next_follow_up_at is null, so a deal created without one could not be advanced at all — the rep met
  // that refusal on their first action.
  const [nextFollowUpAt, setNextFollowUpAt] = useState(() => defaultNextFollowUpAt());
  // V50: a deal may start with NO items (lightweight lead-stage draft) — the price-request flow begins later
  // once items are added and submitted.
  const [items, setItems] = useState(() => (initialItems?.length ? initialItems : []));
  const [editingItemIndex, setEditingItemIndex] = useState(null);

  const [loading, setLoading] = useState(false);
  // Form-level: submit/API failures (สร้างคำขอราคาไม่สำเร็จ, สร้างโครงการไม่สำเร็จ, เพิ่มผู้ติดต่อไม่สำเร็จ).
  // Kept separate from `fieldErrors` — those are two different kinds of problem and shouldn't share one string.
  const [error, setError] = useState('');
  // Field-level: keyed 'customer' | 'project' | `items.<index>.<field>`. See validateTicketForm() above for how
  // this is populated.
  const [fieldErrors, setFieldErrors] = useState({});
  // DOM node per fieldErrors key, so submit() can scroll+focus the first invalid field on a form long enough
  // that the error can be off-screen.
  const fieldRefs = useRef({});
  function clearFieldError(key) {
    setFieldErrors((prev) => {
      if (!(key in prev)) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  }

  // customer / project state
  const [customerSearch, setCustomerSearch] = useState('');
  const [customerOptions, setCustomerOptions] = useState([]);
  const [customerLoading, setCustomerLoading] = useState(false);
  const [selectedCustomer, setSelectedCustomer] = useState(null);

  const [projectOptions, setProjectOptions] = useState([]);
  const [selectedProject, setSelectedProject] = useState(null);
  const [newProjectName, setNewProjectName] = useState('');
  const [showNewProject, setShowNewProject] = useState(false);

  // Client-only duplicate-deal warning (V50 has no server-side duplicate check) — dismissible, never blocks.
  // Degrades silently on API failure.
  const [duplicateWarning, setDuplicateWarning] = useState(null);
  const [duplicateDismissed, setDuplicateDismissed] = useState(false);

  const [creatingProject, setCreatingProject] = useState(false);

  // catalog autocomplete
  const [catalogResults, setCatalogResults] = useState([]);
  // { index, field: 'brand' | 'model' } | null — tracking which field is focused (not just which item row) so
  // the brand and model inputs for the same row don't both render the same catalogResults dropdown at once.
  const [catalogFocus, setCatalogFocus] = useState(null);

  // เจ้าของงาน (2026-11-05): admin staff key deals for other reps. Same eligible-people list as the
  // quotation editor's print-name selectors; the default (and a missing/failed list) is the current
  // user, and ownerEmployeeId is only sent when it names someone else.
  const ownerOptionsQuery = useQuery({
    queryKey: queryKeys.dealQuotationDisplayNameOptions(),
    queryFn: () => api.dealQuotations.displayNameOptions().then((r) => r.items ?? []),
    retry: false,
  });
  const [ownerEmployeeId, setOwnerEmployeeId] = useState('');
  const effectiveOwnerId = ownerEmployeeId || (user?.id != null ? String(user.id) : '');

  // ── FX rates, for showing a catalog price in baht alongside its own currency ───────────────
  // The catalog quotes EUR and USD only. This is a straight currency conversion; the old ราคาตั้ง
  // (ประมาณการ) estimate (catalog price × FX × markup) was removed because reps read it as a
  // selling price, which only comes out of the pricing-request → CEO costing chain. See
  // catalogPriceDisplay.js.
  //
  // Degrades silently: `retry: false`, no toast (optional display enrichment, and a deal must stay
  // creatable with zero prices). A missing rate shows the catalog's own currency alone, never
  // converted at an assumed 1:1.
  const fxRatesQuery = useQuery({
    queryKey: queryKeys.fxRates(),
    queryFn: () => api.fxRates.list().then((res) => res.fxRates ?? []),
    retry: false,
  });
  const fxRatesByCurrency = useMemo(() => {
    const map = { THB: 1 };
    for (const rate of fxRatesQuery.data ?? []) {
      if (rate?.currency) map[rate.currency] = Number(rate.rateToThb);
    }
    return map;
  }, [fxRatesQuery.data]);

  // new customer form
  const [showNewCustomer, setShowNewCustomer] = useState(false);
  const [newCustomer, setNewCustomer] = useState({ name: '', taxId: '', branch: 'สำนักงานใหญ่', address: '', phone: '' });
  const [customerSaving, setCustomerSaving] = useState(false);

  // client-side draft persistence (debounced autosave)
  const [draftSavedAt, setDraftSavedAt] = useState(null);
  // Non-blocking "autosave failed" notice — a dealDrafts.js reason code ('unavailable' | 'quota' | 'limit') or
  // null.
  const [draftSaveNotice, setDraftSaveNotice] = useState(null);

  // Refs mirroring state that the debounce timer / beforeunload / close-confirm handlers need to read WITHOUT a
  // stale closure — same shape as PayrollPage.jsx's draftDirtyRef/canSaveDraftRef pattern (~L1171).
  const currentDraftIdRef = useRef(currentDraftId);
  useEffect(() => { currentDraftIdRef.current = currentDraftId; }, [currentDraftId]);
  // true once submit() has deleted the current draft on a successful create: the authoritative
  // guard against a debounce timer already in flight resurrecting a draft the rep just turned into
  // a real ticket. Checked by the timer's callback and implicitly by the autosave effect's
  // early-return once `loading` stays true post-submit.
  const submittedRef = useRef(false);
  // true whenever there is a meaningful change not yet reflected in a successful saveDraft() call — read
  // synchronously by the beforeunload handler (which cannot await a debounce) to decide whether a synchronous
  // flush is even worth attempting.
  const dirtyRef = useRef(false);
  const autosaveTimerRef = useRef(null);
  // The JSON of the last snapshot actually written to storage (autosave, บันทึกร่าง, close-time
  // flush, OR a restored draft's own on-disk snapshot: เปิดต่อ counts as already persisted). ''
  // means nothing persisted for the current attempt yet. Without it a restored draft looked like a
  // fresh unsaved edit (hasMeaningfulDraftData is true for both), so autosave re-fired, rewriting
  // savedAt (reordering the picker) and showing the close-confirm dialog for an unchanged draft.
  const lastPersistedSnapshotJsonRef = useRef('');

  /** The full draft snapshot — the same shape dealDrafts.js persists and hasMeaningfulDraftData reads. */
  function buildDraftSnapshot() {
    return {
      dealTitle, note: form.note, priority, entryChannel, nextFollowUpAt,
      customer: selectedCustomer, project: selectedProject,
      items,
      ownerEmployeeId,
    };
  }
  // Kept fresh after every render so the debounce timer's callback — which fires up to a second after the render
  // that scheduled it — always saves the LATEST snapshot, not the one at schedule time.
  const draftSnapshotRef = useRef(buildDraftSnapshot());
  // The item editor's row, kept fresh the same way for closeItemEditor(): goToSection also runs
  // after an await (a late create), whose closure predates any editor the rep opened meanwhile.
  const editingItemIndexRef = useRef(editingItemIndex);
  useEffect(() => {
    draftSnapshotRef.current = buildDraftSnapshot();
    editingItemIndexRef.current = editingItemIndex;
  });

  /** Meaningful AND different from what's already safely on disk — see lastPersistedSnapshotJsonRef. */
  function isSnapshotDirty(snapshot) {
    return hasMeaningfulDraftData(snapshot) && JSON.stringify(snapshot) !== lastPersistedSnapshotJsonRef.current;
  }

  function applyDraftSaveResult(result, snapshot) {
    if (result.ok) {
      dirtyRef.current = false;
      currentDraftIdRef.current = result.id;
      setCurrentDraftId(result.id);
      setDraftSavedAt(result.savedAt);
      setDraftSaveNotice(null);
      if (snapshot) lastPersistedSnapshotJsonRef.current = JSON.stringify(snapshot);
    } else {
      setDraftSaveNotice(result.reason);
    }
    return result;
  }

  // Debounced autosave (~1s after the last change to a meaningful field). Dependencies are every
  // field buildDraftSnapshot() reads; other changes (search boxes, catalog dropdown, customer
  // projects) do not reschedule it.
  useEffect(() => {
    // Nothing to save yet (picker showing, nothing restored/typed), a submit is already in flight, or one has
    // already succeeded (submittedRef) — never autosave in any of those.
    if (showPicker || loading || submittedRef.current) return undefined;
    const snapshot = draftSnapshotRef.current;
    if (!isSnapshotDirty(snapshot)) {
      dirtyRef.current = false;
      return undefined;
    }
    dirtyRef.current = true;
    const timer = setTimeout(() => {
      if (submittedRef.current) return; // extra guard against a razor-thin race with submit()
      applyDraftSaveResult(saveDraft(currentDraftIdRef.current, draftSnapshotRef.current), draftSnapshotRef.current);
    }, 1000);
    autosaveTimerRef.current = timer;
    // Re-running this effect on the NEXT change clears this timer (React runs the cleanup below before the next
    // run) — that cancel-and-reschedule is the entire debounce mechanism: rapid edits coalesce into whichever one
    // is still pending 1s after the last of them.
    return () => clearTimeout(timer);
  }, [dealTitle, form.note, priority, entryChannel, nextFollowUpAt, selectedCustomer, selectedProject, items, ownerEmployeeId, showPicker, loading]);

  // beforeunload guard: mirrors PayrollPage.jsx's beforeunload effect. Refs only, registered once,
  // removed on unmount. It first attempts a synchronous flush (localStorage is synchronous) and
  // warns only if that could not land (storage unavailable/quota, or nothing meaningful typed).
  useEffect(() => {
    function handleBeforeUnload(event) {
      if (submittedRef.current) return;
      const snapshot = draftSnapshotRef.current;
      if (!hasMeaningfulDraftData(snapshot)) return;
      if (dirtyRef.current) {
        applyDraftSaveResult(saveDraft(currentDraftIdRef.current, snapshot), snapshot);
      }
      if (dirtyRef.current) {
        event.preventDefault();
        event.returnValue = '';
      }
    }
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, []);

  /** Applies a restored draft (or `null` for a blank form) onto the already-mounted form state. Returns the
   * normalized snapshot it just applied — same shape/key-order as buildDraftSnapshot() — so the caller can set
   * that as the new "already persisted" baseline (see lastPersistedSnapshotJsonRef). */
  function applyDraftToForm(draft) {
    const normalized = {
      dealTitle: draft?.dealTitle ?? '',
      note: draft?.note ?? '',
      priority: draft?.priority ?? 'NORMAL',
      entryChannel: draft?.entryChannel ?? null,
      nextFollowUpAt: draft?.nextFollowUpAt ?? defaultNextFollowUpAt(),
      customer: draft?.customer ?? null,
      project: draft?.project ?? null,
      items: draft?.items?.length ? draft.items : [],
      ownerEmployeeId: draft?.ownerEmployeeId ?? '',
    };
    setOwnerEmployeeId(normalized.ownerEmployeeId);
    setForm({ note: normalized.note });
    setDealTitle(normalized.dealTitle);
    setPriority(normalized.priority);
    setEntryChannel(normalized.entryChannel);
    setNextFollowUpAt(normalized.nextFollowUpAt);
    setItems(normalized.items);
    setSelectedCustomer(normalized.customer);
    setSelectedProject(normalized.project);
    setFieldErrors({});
    setError('');
    return normalized;
  }

  function handleRestoreDraft(id) {
    const draft = loadDraft(id);
    if (!draft) {
      // Gone since the picker was drawn (deleted in another tab, or corrupt): refresh and stay on
      // the picker rather than silently opening a blank form.
      setDraftList(listDrafts());
      return;
    }
    const normalized = applyDraftToForm(draft);
    currentDraftIdRef.current = id;
    setCurrentDraftId(id);
    setDraftSavedAt(draft.savedAt || null);
    setDraftSaveNotice(null);
    dirtyRef.current = false;
    // Baseline = what is now on screen AND already on disk, so an unchanged restored draft does not
    // look dirty to the autosave effect or handleRequestClose (hasMeaningfulDraftData() cannot tell
    // "has data" from "has UNSAVED data").
    lastPersistedSnapshotJsonRef.current = JSON.stringify(normalized);
    setShowPicker(false);
    goToSection(firstMissingSection(normalized));
  }

  function handleStartNewDraft() {
    const normalized = applyDraftToForm(null);
    currentDraftIdRef.current = null;
    setCurrentDraftId(null);
    setDraftSavedAt(null);
    setDraftSaveNotice(null);
    dirtyRef.current = false;
    // A blank baseline — nothing is meaningful yet either, so this only matters once the rep types something for
    // the first time.
    lastPersistedSnapshotJsonRef.current = JSON.stringify(normalized);
    setShowPicker(false);
    goToSection(firstMissingSection(normalized));
  }

  function handleConfirmDeleteDraft() {
    if (!draftDeleteTarget) return;
    deleteDraft(draftDeleteTarget);
    const next = listDrafts();
    setDraftList(next);
    // Deleting the draft this session is actively editing must not leave it silently autosaving into an id that no
    // longer exists on disk — saveDraft() would just recreate it under the same id (its "existing" check would say
    // no, so it'd also cost a cap slot again), which is not what ลบ means here.
    if (draftDeleteTarget === currentDraftIdRef.current) {
      currentDraftIdRef.current = null;
      setCurrentDraftId(null);
      setDraftSavedAt(null);
    }
    setDraftDeleteTarget(null);
    if (next.length === 0) {
      // Nothing left to pick from — a picker with zero rows is a dead end, not a real choice.
      handleStartNewDraft();
    }
  }

  /**
   * Wraps EVERY way the outer Modal can close (ยกเลิก, header ✕, Escape, backdrop click all funnel
   * into Modal's single `onClose`; see Modal.jsx/useDialogFocus.js). Guarded against re-entry while
   * either confirm dialog below is open: they are nested <Modal>s whose backdrop clicks/Escape
   * presses re-dispatch into this component's DOM tree and would otherwise ALSO close the outer
   * Modal in the same gesture, dismissing everything instead of just the confirm dialog.
   */
  function handleRequestClose() {
    // submit() succeeding does not itself close the modal; that is the CALLER's (onSubmit's)
    // decision. If the rep hits Escape/✕ before that, the fields still hold the submitted values
    // (submit() only clears draft bookkeeping), so hasMeaningfulDraftData() would read true and
    // flush a draft OF THE DEAL JUST CREATED. Once submittedRef is set there is nothing to confirm
    // or persist: just close.
    if (submittedRef.current) { onClose(); return; }
    if (closeConfirmOpen || draftDeleteTarget) return;
    const snapshot = draftSnapshotRef.current;
    if (!hasMeaningfulDraftData(snapshot)) {
      // Nothing worth keeping. A draft id may already be assigned (restored via เปิดต่อ, then
      // cleared by hand): delete that empty husk rather than leave it in the picker as
      // "ร่างไม่มีชื่อ".
      if (currentDraftIdRef.current) deleteDraft(currentDraftIdRef.current);
      onClose();
      return;
    }
    if (!isSnapshotDirty(snapshot)) {
      // Meaningful but IDENTICAL to what's safely on disk (เปิดต่อ then ยกเลิก with no edits):
      // nothing to confirm or lose, so close immediately. Do NOT delete it: it is real,
      // previously-saved data, not an empty husk.
      onClose();
      return;
    }
    if (autosaveTimerRef.current) { clearTimeout(autosaveTimerRef.current); autosaveTimerRef.current = null; }
    const hadExistingDraft = Boolean(currentDraftIdRef.current);
    const result = saveDraft(currentDraftIdRef.current, snapshot);
    applyDraftSaveResult(result, snapshot);
    setCloseFlushFailed(!result.ok);
    setCloseHasOlderCopy(hadExistingDraft && !result.ok);
    setCloseConfirmOpen(true);
  }

  function handleDiscardDraftAndClose() {
    if (currentDraftIdRef.current) deleteDraft(currentDraftIdRef.current);
    submittedRef.current = true; // same guard submit() uses — a still-pending timer must not resurrect it
    setCloseConfirmOpen(false);
    onClose();
  }

  /** ปิดโดยไม่บันทึก (the failed-flush branch's destructive option) — closes WITHOUT deleting any older,
   * already-persisted copy; only the latest unsaved edits are given up. */
  function handleCloseWithoutSaving() {
    submittedRef.current = true; // a still-pending timer must not resurrect/overwrite after this
    setCloseConfirmOpen(false);
    onClose();
  }

  // load projects when customer is picked (also fires once on mount when a draft restored a
  // customer, refreshing the list). ผู้ติดต่อ is no longer part of the deal (picked per quotation).
  useEffect(() => {
    if (!selectedCustomer) {
      setProjectOptions([]);
      setSelectedProject(null);
      setShowNewProject(false);
      return;
    }
    api.customers.projects(selectedCustomer.id).then((pr) => {
      setProjectOptions(pr.projects ?? []);
    }).catch(() => {
      // load failed — leave the picker empty rather than a dangling unhandled rejection
      setProjectOptions([]);
    });
  }, [selectedCustomer]);

  // Client-only duplicate-deal check: once a โครงการ is picked, see whether any existing deal already carries
  // that projectId. Soft warning only — never blocks creation — and silently gives up on a failed fetch.
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

  // Focus (and scroll to) a pending field once its section/item editor has actually mounted the
  // node. Each Section scrolls its own header first (see Section).
  useEffect(() => {
    if (!pendingFocusKey) return;
    const node = fieldRefs.current[pendingFocusKey];
    if (!node) return;
    if (typeof node.scrollIntoView === 'function') {
      node.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    node.focus();
    setPendingFocusKey(null);
  }, [pendingFocusKey, openSection, items, editingItemIndex]);

  // Opening the ลูกค้า step lands the cursor in its search box, which also opens the results list (SearchSelect
  // searches on focus). Without this the step arrives as one empty field in a tall panel — the company list, the
  // only thing on the step, stays hidden behind a keystroke.
  useEffect(() => {
    if (openSection !== 'customer' || selectedCustomer || pendingFocusKey) return;
    fieldRefs.current.customer?.focus();
  }, [openSection, selectedCustomer, pendingFocusKey]);

  // The one way the open section changes: a header click, the auto-advance after a pick, a
  // ไปที่ขั้นตอน or failed-submit jump, restoring a draft or starting a new one. Leaving รายการสินค้า
  // goes through closeItemEditor(), so a refused close keeps the rep in the editor. `item` opens
  // that row's editor; `focusKey` is focused once its field has mounted.
  function goToSection(section, { item = null, focusKey = null } = {}) {
    if (section !== 'items' && !closeItemEditor()) return;
    if (item != null) setEditingItemIndex(item);
    setOpenSection(section);
    if (focusKey) setPendingFocusKey(focusKey);
  }

  // Auto-advance: `field` (customer | project | entryChannel) was just picked, so clear its error
  // and open the next required section still missing, or none. Judged on the latest COMMITTED
  // values plus this pick, not this render's closure: the create handlers call this after an
  // await, by which time the rep may have finished another section.
  function advancePast(field, value) {
    clearFieldError(field);
    goToSection(firstMissingSection({ ...draftSnapshotRef.current, [field]: value }));
  }

  /** Opens the section owning a fieldErrors key (its part before the first dot) and focuses it. */
  function jumpToField(key) {
    const [section, row] = key.split('.');
    goToSection(section, { item: row == null ? null : Number(row), focusKey: key });
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
    // unitBasis flips which of qty/qtySqm is required — clear both so a stale error from the basis the user just
    // left doesn't linger.
    if (field === 'unitBasis') {
      clearFieldError(`items.${index}.qty`);
      clearFieldError(`items.${index}.qtySqm`);
    }
    setItems((cur) => cur.map((item, i) => {
      if (i !== index) return item;
      // Descriptive-field assignment, brand→factory lockstep and catalog-link clearing live in the
      // shared helper (ticketItemFields.jsx); non-descriptive fields (qty/qtySqm/unitBasis) pass
      // through as a plain assign.
      let updated = applyDescriptiveFieldEdit(item, field, value);
      if (field === 'qty' && item.sqmPerPiece) {
        updated = { ...updated, qtySqm: value ? (Number(value) * item.sqmPerPiece).toFixed(3) : '' };
      }
      if (field === 'qtySqm' && item.sqmPerPiece) {
        updated = { ...updated, qty: value ? Math.ceil(Number(value) / item.sqmPerPiece) : '' };
      }
      if (field === 'unitBasis' && item.sqmPerPiece) {
        if (value === 'SQM' && item.qty) updated = { ...updated, qtySqm: (Number(item.qty) * item.sqmPerPiece).toFixed(3) };
        if (value === 'PIECE' && item.qtySqm) updated = { ...updated, qty: Math.ceil(Number(item.qtySqm) / item.sqmPerPiece) };
      }
      return updated;
    }));
  }

  function applyCatalogItem(index, cat) {
    setItems((cur) => cur.map((item, i) => (i === index ? applyCatalogPick(item, cat) : item)));
    // A catalog pick fills every required field in one shot: clear anything previously flagged.
    Object.keys(REQUIRED_ITEM_FIELD_LABELS).forEach((f) => clearFieldError(`items.${index}.${f}`));
    setCatalogResults([]);
    setCatalogFocus(null);
  }

  // Real keystroke: mutates the row AND searches. Kept separate from onCatalogFieldFocus below:
  // re-focusing an already-filled ยี่ห้อ/รุ่น box (no keystroke) through this mutating path would
  // re-run the brand→factory lockstep and catalog-link CLEAR on every focus, wiping
  // catalogPriceId/catalogProductCode and resetting factory from the unchanged brand (data loss).
  function onCatalogInput(index, field, value) {
    updateItem(index, field, value);
    setCatalogFocus({ index, field });
    searchCatalog(value, setCatalogResults);
  }

  // Focus-only: search-only, no row mutation. Safe to call on every focus, including a re-focus of a field the
  // rep never actually retyped.
  function onCatalogFieldFocus(index, field, value) {
    setCatalogFocus({ index, field });
    if (value) searchCatalog(value, setCatalogResults);
  }

  function onBrandInput(index, value) { onCatalogInput(index, 'brand', value); }
  function onModelInput(index, value) { onCatalogInput(index, 'model', value); }

  // Every exit from the item editor (the footer's "บันทึกรายการ", the "กลับไปรายการสินค้า" link,
  // opening another section) routes through here; nothing blocks leaving it. A blank ยี่ห้อ/รุ่น/ขนาด
  // is caught at final submit.
  function closeItemEditor() {
    if (editingItemIndexRef.current == null) return true;
    setEditingItemIndex(null);
    return true;
  }

  function addItem() {
    setItems((cur) => [...cur, emptyItem()]);
    setEditingItemIndex(items.length);
  }

  function removeItem(index) {
    setItems((cur) => cur.filter((_, i) => i !== index));
    // Removing a row shifts every later row's index, so a stale error keyed to the old index would
    // render against the wrong row. Item errors are cheap to re-derive on the next submit.
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
      advancePast('project', proj);
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
      advancePast('customer', cust);
      setShowNewCustomer(false);
      setNewCustomer({ name: '', taxId: '', branch: 'สำนักงานใหญ่', address: '', phone: '' });
    } finally {
      setCustomerSaving(false);
    }
  }

  // Manual "บันทึกร่าง" — immediate, and cancels whatever autosave timer is pending so the two never race and
  // write twice.
  function handleSaveDraft() {
    if (autosaveTimerRef.current) { clearTimeout(autosaveTimerRef.current); autosaveTimerRef.current = null; }
    const snapshot = draftSnapshotRef.current;
    if (!hasMeaningfulDraftData(snapshot)) return; // nothing to save yet
    applyDraftSaveResult(saveDraft(currentDraftIdRef.current, snapshot), snapshot);
  }

  async function submit(event) {
    event.preventDefault();
    const { errors: nextFieldErrors, order } = validateTicketForm({
      customer: selectedCustomer,
      project: selectedProject,
      entryChannel,
      items,
    });
    if (order.length > 0) {
      setFieldErrors(nextFieldErrors);
      setError('');
      // Acceptance criterion: don't make the user hunt on a long form — jump straight to (and focus) the first
      // invalid field, in the same top-to-bottom order validateTicketForm() reports them in.
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
        note: form.note.trim() || null,
        entryChannel,
        priority,
        nextFollowUpAt: nextFollowUpAt || null,
        ...(effectiveOwnerId && effectiveOwnerId !== String(user?.id) ? { ownerEmployeeId: Number(effectiveOwnerId) } : {}),
        items: items.map((item) => ({
          brand: item.brand.trim(),
          model: item.model.trim(),
          // Optional: null, not '', so a row with no colour reads as absent. Both columns are
          // nullable in sales.ticket_item and TicketItemRequest does not mark them @NotBlank.
          color: item.color.trim() || null,
          texture: item.texture.trim() || null,
          size: item.size.trim(),
          // One input feeds both (see updateItem()). The fallback covers a row restored from an
          // older draft whose factory was captured separately.
          factory: (item.factory || item.brand).trim() || null,
          unitBasis: item.unitBasis || 'PIECE',
          qty: Number(item.qty) || 0,
          qtySqm: item.qtySqm !== '' && item.qtySqm != null ? Number(item.qtySqm) : null,
          // Persisted (V110) so PricingRequestCreateModal can seed its own catalog link without a re-search — see
          // emptyItem()'s comment.
          catalogPriceId: item.catalogPriceId ?? null,
          catalogProductCode: item.catalogProductCode?.trim() || null,
          sourcedFromStock: Boolean(item.sourcedFromStock),
        })),
      });
      // Server accepted the deal — the client-only draft has served its purpose and would otherwise resurrect stale
      // state next time the modal opens.
      submittedRef.current = true;
      if (autosaveTimerRef.current) { clearTimeout(autosaveTimerRef.current); autosaveTimerRef.current = null; }
      if (currentDraftIdRef.current) deleteDraft(currentDraftIdRef.current);
      dirtyRef.current = false;
      currentDraftIdRef.current = null;
      setCurrentDraftId(null);
      setDraftSavedAt(null);
      setDraftSaveNotice(null);
    } catch (err) {
      setError(err.message || 'สร้างคำขอราคาไม่สำเร็จ');
      setLoading(false);
    }
  }

  // ── sections ─────────────────────────────────────────────────────────────
  const duplicateBanner = selectedProject && duplicateWarning && !duplicateDismissed ? (
    <div role="status" className="flex items-start gap-2 rounded-lg border border-warning-border bg-warning-bg px-3 py-2.5 text-xs text-warning-dark">
      <Icon name="triangleAlert" size={15} className="mt-0.5 shrink-0" />
      <span className="flex-1">
        พบดีลที่ผูกกับโครงการนี้อยู่แล้ว — {duplicateWarning.map((t, i) => (
          <span key={t.id}>
            {i > 0 ? ', ' : ''}
            <strong>{t.code}</strong> ({dealStageLabel(t.salesStage)?.label ?? t.status})
          </span>
        ))}. ตรวจสอบก่อนสร้างดีลซ้ำ
      </span>
      <button type="button" onClick={() => setDuplicateDismissed(true)} aria-label="ปิดคำเตือนดีลซ้ำ" className="shrink-0 border-0 bg-transparent text-warning-dark">
        <Icon name="close" size={13} />
      </button>
    </div>
  ) : null;

  // The five accordion sections, in on-screen order (= validateTicketForm's order). Each id IS the
  // fieldErrors key it owns (`items` owns every `items.<i>.<field>`; see jumpToField). done/summary
  // read the draft-snapshot shape (buildDraftSnapshot, applyDraftToForm), so one test serves the
  // progress line, the default-open choice and the auto-advance. Section shows a summary only once
  // done.
  const sections = [
    {
      id: 'customer', title: 'ลูกค้า', required: true, body: renderCustomerBody,
      done: (s) => Boolean(s.customer),
      summary: (s) => (
        <>
          {s.customer?.name}
          {s.customer?.taxId ? <span className="ml-1.5 text-text-faint">เลขภาษี {s.customer.taxId}</span> : null}
        </>
      ),
    },
    {
      id: 'project', title: 'โครงการ', required: true, body: renderProjectBody,
      done: (s) => Boolean(s.project),
      summary: (s) => s.project?.name,
      // Under the card, outside the body: picking the project collapses the body, and the duplicate
      // check only answers afterwards, so inside it the warning went unseen.
      below: duplicateBanner,
    },
    {
      id: 'entryChannel', title: OFFERED_TO_LABEL, required: true, body: renderEntryChannelBody,
      done: (s) => Boolean(s.entryChannel),
      summary: (s) => ENTRY_CHANNEL_OPTIONS.find((o) => o.code === s.entryChannel)?.label ?? s.entryChannel,
    },
    {
      id: 'items', title: 'รายการสินค้า', required: false, body: renderItemsBody,
      done: (s) => s.items.length > 0,
      summary: (s) => `${s.items.length} รายการ`,
    },
    {
      id: 'details', title: 'รายละเอียดดีล', required: false, body: renderDetailsBody,
      done: (s) => Boolean(s.dealTitle.trim() || s.note.trim() || s.priority !== 'NORMAL'),
      summary: (s) => s.dealTitle.trim() || (s.priority !== 'NORMAL' ? ticketPriorityLabel(s.priority).label : 'มีหมายเหตุ'),
    },
  ];
  /** The first required section still missing its value in `state` (a draft snapshot), else null. */
  const firstMissingSection = (state) => sections.find((s) => s.required && !s.done(state))?.id ?? null;
  const snapshot = buildDraftSnapshot();
  const doneCount = sections.filter((s) => s.done(snapshot)).length;

  // Outstanding fields listed above the sections after a failed submit. Recomputed from the CURRENT form
  // rather than read off `fieldErrors`' key order, so the list shrinks as the rep fixes things and
  // stays in on-screen order (`fieldErrors` is a plain object and carries neither property).
  const missing = Object.keys(fieldErrors).length > 0
    ? validateTicketForm({ customer: selectedCustomer, project: selectedProject, entryChannel, items })
    : { errors: {}, order: [] };
  const canCreateNow = Boolean(selectedCustomer && selectedProject);

  /** The one page: progress line, the post-submit missing list, five accordion sections. */
  function renderSections() {
    return (
      <div className="flex flex-col gap-3">
        <div className="flex items-center gap-3 rounded-lg border border-border bg-surface px-3 py-2.5">
          <span className="whitespace-nowrap text-xs font-extrabold text-text-secondary">{doneCount}/{sections.length} เสร็จ</span>
          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-subtle">
            <div className="h-full rounded-full bg-accent" style={{ width: `${Math.round((doneCount / sections.length) * 100)}%` }} />
          </div>
          {draftSaveNotice ? (
            <span className="whitespace-nowrap text-2xs font-bold text-danger-dark">
              บันทึกร่างอัตโนมัติไม่ได้ — {draftSaveNoticeCopy(draftSaveNotice)}
            </span>
          ) : draftSavedAt ? (
            <span className="whitespace-nowrap text-2xs font-bold text-accent-dark">ร่างบันทึกแล้ว</span>
          ) : null}
        </div>

        {/* Only after a submit attempt has actually failed — the จำเป็น badges already say what is
            outstanding, so a red block on a freshly opened form would be nagging rather than
            helping. Each line jumps straight to (and opens the section of) a specific bad field. */}
        {missing.order.length > 0 ? (
          <div className="rounded-lg border border-danger-border bg-danger-bg px-3 py-2.5">
            <p className="flex items-center gap-1.5 text-xs font-extrabold text-danger-dark">
              <Icon name="triangleAlert" size={14} /> ยังกรอกไม่ครบ {missing.order.length} รายการ
            </p>
            <ul className="mb-0 mt-1.5 flex list-disc flex-col gap-1 pl-4 text-2xs text-danger-dark">
              {missing.order.map((key) => (
                <li key={key}>
                  {missing.errors[key]}
                  {' — '}
                  <button
                    type="button"
                    className="border-0 bg-transparent p-0 font-extrabold text-danger-dark underline"
                    onClick={() => { setFieldErrors(missing.errors); jumpToField(key); }}
                  >
                    ไปที่ขั้นตอน
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ) : null}

        {sections.map((section) => (
          <Section
            key={section.id}
            id={section.id}
            title={section.title}
            required={section.required}
            done={section.done(snapshot)}
            summary={section.summary(snapshot)}
            open={openSection === section.id}
            onOpen={() => goToSection(section.id)}
            body={section.body}
            below={section.below}
          />
        ))}

        <div className="flex items-start gap-2 rounded-lg border border-info-border bg-info-bg px-3 py-2.5 text-xs text-info-dark">
          <Icon name="info" size={15} className="mt-0.5 shrink-0" />
          <span>ดีลที่สร้างยังไม่มีราคา — สร้างคำขอราคาได้เมื่อดีลถึงขั้น 3 ซึ่งต้องมีรายการสินค้าอย่างน้อย 1 รายการ</span>
        </div>

        {error ? <div className="py-2.5 px-3 rounded-md bg-danger-bg text-danger-dark font-bold text-[length:var(--text-sm)]" role="alert">{error}</div> : null}
      </div>
    );
  }

  function renderCustomerBody() {
    return (
      <div className="flex flex-col gap-3">
        <SearchSelect
          id="customer-select"
          label="ลูกค้า *"
          value={selectedCustomer}
          onSelect={(c) => { setSelectedCustomer(c); if (c) { setShowNewCustomer(false); advancePast('customer', c); } }}
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
          createNewLabel="เพิ่มลูกค้าใหม่"
          onCreateNew={() => setShowNewCustomer(true)}
          inputRef={(el) => { fieldRefs.current.customer = el; }}
          error={fieldErrors.customer}
        />

        {showNewCustomer && !selectedCustomer && (
          <div className="flex flex-col gap-2 rounded-md border border-info-border bg-info-row-active p-3">
            <p className="m-0 text-xs font-bold text-info">เพิ่มลูกค้าใหม่</p>
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

  function renderProjectBody() {
    return (
      <div className="flex flex-col gap-3">
        {!selectedCustomer ? (
          <div className="flex flex-col items-start gap-2 rounded-lg border border-dashed border-border-strong bg-surface px-3.5 py-4 text-sm text-text-muted">
            <span>ต้องเลือกลูกค้าก่อน — โครงการต้องผูกกับลูกค้าเสมอ</span>
            <Button variant="secondary" onClick={() => goToSection('customer')}>ไปที่ ลูกค้า</Button>
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
                  <button type="button"
                    className="cursor-pointer rounded-pill border border-dashed border-text-faint bg-transparent px-2.5 py-1 text-xs text-text-muted"
                    onClick={() => setShowNewProject((v) => !v)}>
                    <Icon name="plus" size={12} /> เพิ่มโครงการใหม่
                  </button>
                  {projectOptions.map((p) => (
                    <button key={p.id} type="button"
                      className="cursor-pointer rounded-pill border border-border-muted bg-surface px-2.5 py-1 text-xs text-text"
                      onClick={() => { setSelectedProject(p); advancePast('project', p); }}>
                      {p.name}
                    </button>
                  ))}
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
      </div>
    );
  }

  function renderEntryChannelBody() {
    return (
      <div className="flex flex-col gap-4">

        <div>
          <span className="mb-1 block text-sm font-bold text-text-secondary">
            ดีลนี้เสนอแก่ใคร
            <span className="text-danger" aria-hidden="true"> *</span>
          </span>
          <div
            className="grid grid-cols-3 gap-1.5"
            role="radiogroup"
            aria-label={OFFERED_TO_LABEL}
            aria-required="true"
            aria-invalid={fieldErrors.entryChannel ? true : undefined}
            aria-describedby={fieldErrors.entryChannel ? fieldErrorId('entry-channel') : undefined}
          >
            {ENTRY_CHANNEL_OPTIONS.map((option, index) => {
              const selected = entryChannel === option.code;
              return (
                <button
                  key={option.code}
                  type="button"
                  role="radio"
                  aria-checked={selected}
                  ref={index === 0 ? (node) => { fieldRefs.current.entryChannel = node; } : undefined}
                  onClick={() => { setEntryChannel(option.code); advancePast('entryChannel', option.code); }}
                  className={`rounded-md border px-1.5 py-2.5 text-center text-xs font-extrabold leading-tight ${
                    selected
                      ? 'border-primary bg-primary/10 text-primary'
                      : entryChannel
                        // A choice HAS been made, just not this one — plain solid, as before.
                        ? 'border-border bg-surface text-text-secondary'
                        // Nothing chosen yet. Dashed is this app's "not yet decided" idiom
                        // (the empty-state boxes, the add
                        // pills) — it makes the blank state read as awaiting an answer rather
                        // than as a broken control, and it clears itself the moment one lands.
                        : 'border-dashed border-border-strong bg-surface text-text-secondary'
                  }`}
                >
                  {option.label}
                  <small className={`mt-0.5 block font-semibold ${selected ? 'text-primary-hover' : 'text-text-muted'}`}>{option.sub}</small>
                </button>
              );
            })}
          </div>
          {fieldErrors.entryChannel ? (
            <p id={fieldErrorId('entry-channel')} role="alert" className="mx-0 mb-0 mt-1.5 text-2xs font-bold text-danger">
              {fieldErrors.entryChannel}
            </p>
          ) : null}
        </div>

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
    const basis = item.unitBasis || 'PIECE';
    // With a ตร.ม./แผ่น factor the two quantity boxes cross-fill, so the one the rep is not driving
    // is derived and read-only. Without a factor NEITHER can be derived (~350 active catalog rows
    // have no sqm_per_piece and nothing is guessed from the size string), so both stay editable
    // instead of leaving the required box empty while the typed number sits greyed out in the other.
    const hasFactor = Boolean(item.sqmPerPiece);
    const catalogPrice = item.catalogPrice != null
      ? formatCatalogPrice(item.catalogPrice, item.catalogCurrency, item.catalogPriceUnit, fxRatesByCurrency)
      : null;

    return (
      <div className="flex flex-col gap-4">
        <button
          type="button"
          onClick={closeItemEditor}
          className="inline-flex w-fit items-center gap-1 border-0 bg-transparent p-0 text-sm font-extrabold text-primary"
        >
          <Icon name="chevronLeft" size={15} /> กลับไปรายการสินค้า
        </button>

        {item.source === 'catalog' ? (
          <div className="flex flex-wrap items-center gap-2 rounded-lg border border-accent bg-accent/10 px-3 py-2 text-xs font-bold text-accent-dark">
            <Icon name="check" size={14} className="shrink-0" />
            <span className="min-w-0 flex-1">เติมข้อมูลจากแคตตาล็อกให้แล้ว — ตรวจสอบและแก้ไขได้</span>
            {item.catalogGrade ? <StatusBadge tone="teal">เกรด {item.catalogGrade}</StatusBadge> : null}
          </div>
        ) : null}

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <CatalogAutocompleteField
            id={`item-${index}-brand`}
            label={ITEM_FIELD_META.brand.label}
            required={isRequiredItemField('brand')}
            value={item.brand}
            placeholder={ITEM_FIELD_META.brand.placeholder}
            onInput={(value) => onBrandInput(index, value)}
            onFocusSearch={() => onCatalogFieldFocus(index, 'brand', item.brand)}
            onBlur={() => setTimeout(() => setCatalogFocus(null), 180)}
            expanded={catalogFocus?.index === index && catalogFocus?.field === 'brand'}
            results={catalogResults}
            onPick={(cat) => applyCatalogItem(index, cat)}
            error={fieldErrors[`items.${index}.brand`]}
            inputRef={(el) => { fieldRefs.current[`items.${index}.brand`] = el; }}
          />

          <CatalogAutocompleteField
            id={`item-${index}-model`}
            label={ITEM_FIELD_META.model.label}
            required={isRequiredItemField('model')}
            value={item.model}
            placeholder={ITEM_FIELD_META.model.placeholder}
            onInput={(value) => onModelInput(index, value)}
            onFocusSearch={() => onCatalogFieldFocus(index, 'model', item.model)}
            onBlur={() => setTimeout(() => setCatalogFocus(null), 180)}
            expanded={catalogFocus?.index === index && catalogFocus?.field === 'model'}
            results={catalogResults}
            onPick={(cat) => applyCatalogItem(index, cat)}
            error={fieldErrors[`items.${index}.model`]}
            inputRef={(el) => { fieldRefs.current[`items.${index}.model`] = el; }}
          />

          <ItemField
            id={`item-${index}-size`}
            label={ITEM_FIELD_META.size.label}
            required={isRequiredItemField('size')}
            error={fieldErrors[`items.${index}.size`]}
          >
            <input
              id={`item-${index}-size`}
              ref={(el) => { fieldRefs.current[`items.${index}.size`] = el; }}
              value={item.size}
              onChange={(e) => updateItem(index, 'size', e.target.value)}
              placeholder={ITEM_FIELD_META.size.placeholder}
              aria-required="true"
              aria-invalid={fieldErrors[`items.${index}.size`] ? true : undefined}
              aria-describedby={fieldErrors[`items.${index}.size`] ? fieldErrorId(`item-${index}-size`) : undefined}
            />
          </ItemField>

          {/* สี and เนื้อผิว are optional: the live catalog fills them on ~21% of active rows, so requiring them made a
           * rep invent a value on 4 of every 5 catalog picks. */}
          <ItemField id={`item-${index}-color`} label={ITEM_FIELD_META.color.label} hint={ITEM_FIELD_META.color.hint}>
            <input
              id={`item-${index}-color`}
              value={item.color}
              onChange={(e) => updateItem(index, 'color', e.target.value)}
              placeholder={ITEM_FIELD_META.color.placeholder}
            />
          </ItemField>

          <ItemField id={`item-${index}-texture`} label={ITEM_FIELD_META.texture.label} hint={ITEM_FIELD_META.texture.hint}>
            <input
              id={`item-${index}-texture`}
              value={item.texture}
              onChange={(e) => updateItem(index, 'texture', e.target.value)}
              placeholder={ITEM_FIELD_META.texture.placeholder}
            />
          </ItemField>
        </div>

        <div className="flex flex-col gap-2 border-t border-border-subtle pt-4">
          <span className="text-xs">
            หน่วยที่ใช้สั่ง<span className="text-danger" aria-hidden="true"> *</span>
          </span>
          <div className="flex flex-wrap items-center gap-4" role="radiogroup" aria-label="หน่วยที่ใช้สั่ง">
            {[{ value: 'PIECE', label: 'แผ่น' }, { value: 'SQM', label: 'ตร.ม.' }].map((opt) => (
              <label key={opt.value} className="m-0 flex cursor-pointer items-center gap-1.5 text-sm">
                <input type="radio" name={`unitBasis-${index}`} value={opt.value}
                  checked={basis === opt.value}
                  onChange={() => updateItem(index, 'unitBasis', opt.value)}
                  className="h-4 w-4 cursor-pointer accent-info-dot" />
                <strong>{opt.label}</strong>
              </label>
            ))}
          </div>
          {hasFactor ? (
            <span className="text-2xs text-text-muted">
              1 แผ่น = {item.sqmPerPiece} ตร.ม.
            </span>
          ) : (
            <span className="text-2xs text-text-muted">
              สินค้านี้ไม่มีค่า ตร.ม./แผ่น ในแคตตาล็อก — กรอกจำนวนและพื้นที่เองได้ทั้งสองช่อง
            </span>
          )}
        </div>

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <ItemField
            id={`item-${index}-qty`}
            label="จำนวน (แผ่น)"
            required={requiredQtyField(basis) === 'qty'}
            hint={hasFactor && basis === 'SQM' ? '(คำนวณให้)' : undefined}
            error={fieldErrors[`items.${index}.qty`]}
          >
            <input
              type="number" step="1" min="0"
              id={`item-${index}-qty`}
              ref={(el) => { fieldRefs.current[`items.${index}.qty`] = el; }}
              value={item.qty ?? ''}
              onChange={(e) => updateItem(index, 'qty', e.target.value)}
              placeholder="จำนวนแผ่น"
              readOnly={hasFactor && basis === 'SQM'}
              className={hasFactor && basis === 'SQM' ? 'bg-surface-muted text-text-muted' : undefined}
              aria-required={requiredQtyField(basis) === 'qty' ? 'true' : undefined}
              aria-invalid={fieldErrors[`items.${index}.qty`] ? true : undefined}
              aria-describedby={fieldErrors[`items.${index}.qty`] ? fieldErrorId(`item-${index}-qty`) : undefined}
            />
          </ItemField>

          <ItemField
            id={`item-${index}-qtySqm`}
            label="พื้นที่ (ตร.ม.)"
            required={requiredQtyField(basis) === 'qtySqm'}
            hint={hasFactor && basis === 'PIECE' ? '(คำนวณให้)' : undefined}
            error={fieldErrors[`items.${index}.qtySqm`]}
          >
            <input
              type="number" min="0" step="0.001"
              id={`item-${index}-qtySqm`}
              ref={(el) => { fieldRefs.current[`items.${index}.qtySqm`] = el; }}
              value={item.qtySqm ?? ''}
              onChange={(e) => updateItem(index, 'qtySqm', e.target.value)}
              placeholder="เช่น 120.500"
              readOnly={hasFactor && basis === 'PIECE'}
              className={hasFactor && basis === 'PIECE' ? 'bg-surface-muted text-text-muted' : undefined}
              aria-required={requiredQtyField(basis) === 'qtySqm' ? 'true' : undefined}
              aria-invalid={fieldErrors[`items.${index}.qtySqm`] ? true : undefined}
              aria-describedby={fieldErrors[`items.${index}.qtySqm`] ? fieldErrorId(`item-${index}-qtySqm`) : undefined}
            />
          </ItemField>
        </div>

        {/* "จากสต็อก" is only a flag (walk 4): the CEO prices stock lines, so there is no price input. */}
        <div className="flex flex-col gap-1.5 rounded-md border border-border-input bg-surface-muted px-3 py-2.5">
          <label className="m-0 flex cursor-pointer items-center gap-2 text-sm">
            <input
              type="checkbox"
              className="h-4 w-4 cursor-pointer accent-info-dot"
              checked={Boolean(item.sourcedFromStock)}
              onChange={(e) => updateItem(index, 'sourcedFromStock', e.target.checked)}
            />
            <strong>จากสต็อก</strong>
          </label>
          <p className="m-0 pl-6 text-2xs font-semibold text-text-muted">
            สินค้าที่มีอยู่ในคลัง — ราคาขายมาจากการอนุมัติของ CEO
          </p>
        </div>

        {/* Catalog price: the factory's own figure in the currency it is quoted in, with a baht conversion underneath.
         * Replaces the ราคาตั้ง (ประมาณการ) block — see catalogPriceDisplay.js for why a marked-up figure is no longer
         * shown here. */}
        {catalogPrice ? (
          <div className="flex flex-col gap-1 rounded-md border border-border-input bg-surface-muted px-3 py-2.5 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
            <span className="text-2xs font-bold text-icon-muted">
              ราคาแคตตาล็อก
              <span className="block font-medium text-text-muted">ราคาซื้อจากโรงงาน — ราคาขายจริงมาจากขั้นคำขอราคา</span>
            </span>
            <span className="shrink-0 text-left sm:text-right">
              <span className="text-md font-extrabold">{catalogPrice.original}</span>
              {catalogPrice.thb ? (
                <span className="block text-xs font-semibold text-text-muted">{catalogPrice.thb}</span>
              ) : null}
            </span>
          </div>
        ) : null}
      </div>
    );
  }

  function renderItemsBody() {
    if (editingItemIndex != null && items[editingItemIndex]) {
      return renderItemEditor(editingItemIndex);
    }
    return (
      <div className="flex flex-col gap-3">
        <p className="text-xs text-text-muted">
          รายการที่เพิ่มตรงนี้เป็นข้อมูลเบื้องต้นของดีลเท่านั้น — ฝ่ายนำเข้าจะเห็นก็ต่อเมื่อสร้างคำขอราคาจากหน้าดีลแล้วส่งให้ฝ่ายนำเข้าเท่านั้น (ไม่บังคับตอนนี้)
        </p>
        {items.length === 0 ? (
          <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-border-strong bg-surface px-5 py-8 text-center">
            <Icon name="fileText" size={28} className="text-text-faint" />
            <p className="text-sm font-extrabold text-text">ยังไม่มีรายการสินค้า</p>
            <p className="text-xs text-text-muted">พิมพ์ในช่อง ยี่ห้อ / โรงงาน หรือ รุ่น เพื่อค้นจากแคตตาล็อก — หรือกรอกสินค้าที่ยังไม่มีในระบบเอง</p>
            {/* One button, not two: "ค้นหาสินค้า" and "เพิ่มสินค้าเอง" both called addItem() and
                opened the same editor. */}
            <Button variant="primary" className="mt-1" onClick={addItem}>
              <Icon name="plus" size={13} /> เพิ่มรายการสินค้า
            </Button>
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
                    {/* No factory chip: ยี่ห้อ and โรงงาน are one field, so it would repeat the
                        brand in this row's title. */}
                    {item.color ? <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-2xs font-bold text-text-secondary">{item.color}</span> : null}
                    {item.texture ? <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-2xs font-bold text-text-secondary">{item.texture}</span> : null}
                    {item.size ? <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-2xs font-bold text-text-secondary">{item.size}</span> : null}
                    {item.source === 'catalog' ? (
                      <StatusBadge tone="teal">✓ จากแคตตาล็อก</StatusBadge>
                    ) : (
                      <StatusBadge tone="indigo">custom</StatusBadge>
                    )}
                    {item.sourcedFromStock ? (
                      <StatusBadge tone="warning">จากสต็อก</StatusBadge>
                    ) : null}
                  </div>
                </div>
                <div className="shrink-0 text-right text-xs font-extrabold text-text">
                  {(item.unitBasis || 'PIECE') === 'SQM'
                    ? (item.qtySqm ? `${item.qtySqm} ตร.ม.` : '—')
                    : (item.qty ? `${item.qty} แผ่น` : '—')}
                  {/* The catalog's own price, in its own currency plus baht. No row total and no grand total: a line's price unit
                   * (per_sqm / per_piece / per_box) does not always match the unit the rep is ordering in, and restating it
                   * needs a pieces-per-box factor the catalog has no column for. */}
                  {item.catalogPrice != null ? (() => {
                    const price = formatCatalogPrice(item.catalogPrice, item.catalogCurrency, item.catalogPriceUnit, fxRatesByCurrency);
                    return (
                      <div data-testid={`item-catalog-price-${index}`} className="mt-0.5 font-semibold text-2xs text-text-muted">
                        {price.original}
                        {price.thb ? <span className="block">{price.thb}</span> : null}
                      </div>
                    );
                  })() : null}
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
            <Button variant="secondary" onClick={addItem}>
              <Icon name="plus" size={14} /> เพิ่มรายการสินค้า
            </Button>
          </div>
        )}
      </div>
    );
  }

  function renderDetailsBody() {
    return (
      <div className="flex flex-col gap-4">
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
          เจ้าของงาน <span className="font-semibold text-text-muted">(ค่าเริ่มต้นคือคุณ — เลือกคนอื่นถ้าคีย์ดีลแทน)</span>
          <select value={effectiveOwnerId} onChange={(e) => setOwnerEmployeeId(e.target.value)}>
            {user?.id != null && !(ownerOptionsQuery.data ?? []).some((o) => String(o.id) === String(user.id)) ? (
              <option value={String(user.id)}>{user.name}</option>
            ) : null}
            {(ownerOptionsQuery.data ?? []).map((o) => <option key={o.id} value={String(o.id)}>{o.name}</option>)}
          </select>
        </label>

        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          วันติดตามครั้งถัดไป <span className="font-semibold text-text-muted">(ตั้งไว้ล่วงหน้า 14 วัน — แก้ไขได้)</span>
          <ThaiDateInput
            value={nextFollowUpAt}
            onChange={(e) => setNextFollowUpAt(e.target.value)}
          />
          <span className="text-xs font-semibold text-text-muted">
            ต้องมีวันติดตาม ดีลจึงจะเลื่อนขั้นตอนได้
          </span>
        </label>

        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          หมายเหตุ
          <textarea value={form.note} onChange={(e) => setForm((f) => ({ ...f, note: e.target.value }))} rows={3} placeholder="ข้อมูลเพิ่มเติม (ถ้ามี)" />
        </label>
      </div>
    );
  }

  /** Shown first whenever saved drafts exist (and no initialItems override), before the form. */
  function renderPicker() {
    const atCap = draftList.length >= MAX_DRAFTS;
    return (
      <div className="flex flex-col gap-4">
        <div className="flex items-start gap-2 rounded-lg border border-info-border bg-info-bg px-3 py-2.5 text-xs text-info-dark">
          <Icon name="info" size={15} className="mt-0.5 shrink-0" />
          <span>พบร่างดีลที่บันทึกไว้ {draftList.length} รายการ — เปิดต่อร่างเดิม หรือเริ่มดีลใหม่ก็ได้</span>
        </div>
        {atCap ? (
          <div className="flex items-start gap-2 rounded-lg border border-warning-border bg-warning-bg px-3 py-2.5 text-xs text-warning-dark">
            <Icon name="triangleAlert" size={15} className="mt-0.5 shrink-0" />
            <span>ร่างเต็ม {MAX_DRAFTS} รายการแล้ว — ลบร่างเก่าก่อน ดีลใหม่จึงจะบันทึกร่างอัตโนมัติได้</span>
          </div>
        ) : null}
        <div className="flex flex-col gap-2">
          {draftList.map((draft) => (
            <div key={draft.id} className="flex items-start gap-3 rounded-xl border border-border bg-surface px-3.5 py-3">
              <Icon name="fileText" size={18} className="mt-0.5 shrink-0 text-text-faint" />
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-extrabold text-text">{draft.label}</p>
                <div className="mt-1 flex flex-wrap items-center gap-x-1.5 gap-y-0.5 text-2xs text-text-muted">
                  {draft.customerName ? <span>{draft.customerName}</span> : null}
                  {draft.projectName ? <span>· {draft.projectName}</span> : null}
                  <span>· {draft.itemCount} รายการสินค้า</span>
                  {draft.savedAt ? (
                    <span className="inline-flex items-center gap-1">
                      <Icon name="clock" size={11} /> {formatDraftSavedAt(draft.savedAt)}
                    </span>
                  ) : null}
                </div>
              </div>
              <div className="flex shrink-0 items-center gap-2">
                {/* handleRestoreDraft writes currentDraftIdRef.current — but only ever from inside this real onClick handler,
                 * never during render. */}
                {/* eslint-disable-next-line react-hooks/refs */}
                <Button variant="secondary" size="sm" onClick={() => handleRestoreDraft(draft.id)}>เปิดต่อ</Button>
                <Button variant="icon" size="sm" aria-label={`ลบร่าง ${draft.label}`} className="text-danger" onClick={() => setDraftDeleteTarget(draft.id)}>
                  <Icon name="close" size={14} />
                </Button>
              </div>
            </div>
          ))}
        </div>
        <Button variant="primary" onClick={handleStartNewDraft}>
          <Icon name="plus" size={13} /> เริ่มดีลใหม่
        </Button>
      </div>
    );
  }

  function renderFooter() {
    if (showPicker) {
      return <Button variant="secondary" onClick={handleRequestClose}>ยกเลิก</Button>;
    }
    if (editingItemIndex != null) {
      return (
        <>
          <Button variant="secondary" className="text-danger" onClick={() => removeItem(editingItemIndex)}>ลบ</Button>
          <Button variant="primary" onClick={closeItemEditor}>
            <Icon name="check" size={14} /> บันทึกรายการ
          </Button>
        </>
      );
    }
    return (
      <>
        <Button variant="secondary" onClick={handleRequestClose} disabled={loading}>ยกเลิก</Button>
        <Button variant="secondary" onClick={handleSaveDraft} disabled={loading}>บันทึกร่าง</Button>
        <Button type="submit" form="ticket-create-form" variant="primary" disabled={loading || !canCreateNow} data-testid="ticket-create-submit">
          <Icon name="fileText" />
          {loading ? 'กำลังสร้าง…' : 'สร้างดีล'}
        </Button>
      </>
    );
  }

  // HTML implicit submission: Enter in a text input clicks the form's default button (the footer's
  // real สร้างดีล, wired via `form=`) and would file a deal nobody asked for. Enter is swallowed on
  // <input>s only; buttons (Enter = click) and the textarea (Enter = newline) are untouched.
  // SafeForm's submitter guard applies on top, and the draft picker has no form fields at all.
  function swallowEnterOnInputs(event) {
    if (event.key === 'Enter' && event.target.tagName === 'INPUT') event.preventDefault();
  }

  return (
    <>
      <Modal
        title="สร้างดีลใหม่"
        subtitle={showPicker
          ? 'พบร่างดีลที่บันทึกไว้ — เปิดต่อร่างเดิม หรือเริ่มดีลใหม่ก็ได้'
          : 'จัดทีละหัวข้อได้ตามสะดวก — ระบบบันทึกร่างอัตโนมัติให้ระหว่างกรอก ไม่บังคับกรอกให้ครบในครั้งเดียว'}
        onClose={handleRequestClose}
        footer={renderFooter()}
        testId="ticket-create-modal"
        size="lg"
      >
        {/*
          noValidate: several inputs still carry the native `required` attribute (kept for its own
          semantics), but our own submit() is the single source of truth. Without noValidate the
          browser would block the 'submit' event on an empty required field, so our aria-wired
          per-field errors and scroll-to-first-invalid would never run.
        */}
        <SafeForm id="ticket-create-form" onSubmit={submit} canSubmit={!showPicker} onKeyDown={swallowEnterOnInputs} noValidate>
          {/*
            min-height keeps the panel one size across steps. Modal's body is `overflow-auto` with
            no floor, so a short view (ลูกค้า is a single search field) collapsed the dialog and
            CLIPPED the results dropdown at the body's scroll edge. 26rem clears SearchSelect's
            380px dropdown, and the body still scrolls on a short viewport.
          */}
          <div className="min-h-[26rem]">
            {showPicker ? renderPicker() : renderSections()}
          </div>
        </SafeForm>
      </Modal>

      {/* ลบร่าง (from the picker) — its own confirm, independent of the close-confirm below. */}
      <ConfirmDialog
        open={Boolean(draftDeleteTarget)}
        title="ลบร่างนี้?"
        message="ร่างดีลนี้จะถูกลบออกจากเครื่องนี้ถาวร กู้คืนไม่ได้"
        confirmLabel="ลบร่าง"
        cancelLabel="ยกเลิก"
        tone="danger"
        onConfirm={handleConfirmDeleteDraft}
        onCancel={() => setDraftDeleteTarget(null)}
      />

      {/* Close confirmation: reuses ConfirmDialog's two-button shape rather than widening its
          shared API (it has no initial-focus prop; it always focuses `confirmButtonRef`). So which
          real choice maps to `confirmLabel` (focused) vs `cancelLabel` (never focused) SWAPS by case:
            - flush succeeded: confirm=เก็บร่างไว้ (safe, common path), cancel=กลับไปแก้ต่อ.
            - flush FAILED: confirm=กลับไปแก้ต่อ (the safe choice must be reachable by a bare
              Enter), cancel=ปิดโดยไม่บันทึก (destructive, deliberately NOT focused).
          ทิ้งร่างนี้ (delete the draft entirely) stays a tertiary link inside the message body in
          both cases, not a new button slot on the shared component. */}
      <ConfirmDialog
        open={closeConfirmOpen}
        title="ยังไม่ได้สร้างดีล"
        message={
          <>
            <p className="confirm-dialog-message text-text-secondary leading-normal">
              {closeFlushFailed
                ? (closeHasOlderCopy
                  // A draft id already existed BEFORE this failed flush -- that older copy on disk is untouched (saveDraft()
                  // never got to overwrite it), so only the latest edits are actually at risk. Saying "everything will be lost"
                  // here would be wrong and needlessly alarming.
                  ? 'บันทึกการแก้ไขล่าสุดไม่สำเร็จ — ถ้าปิดตอนนี้เฉพาะการแก้ไขล่าสุดจะหายไป (ร่างที่เคยบันทึกไว้ก่อนหน้ายังอยู่)'
                  : 'บันทึกร่างอัตโนมัติไม่สำเร็จ — ถ้าปิดตอนนี้ข้อมูลที่กรอกไว้จะหายไปทั้งหมด')
                : 'ร่างดีลนี้ถูกบันทึกไว้แล้ว ปิดหน้าต่างนี้ได้ แล้วกลับมาเปิดต่อได้ภายหลัง'}
            </p>
            <button
              type="button"
              onClick={handleDiscardDraftAndClose}
              className="mt-2 cursor-pointer border-0 bg-transparent p-0 text-xs font-bold text-danger underline"
            >
              หรือทิ้งร่างนี้ไปเลย
            </button>
          </>
        }
        confirmLabel={closeFlushFailed ? 'กลับไปแก้ต่อ' : 'เก็บร่างไว้'}
        cancelLabel={closeFlushFailed ? 'ปิดโดยไม่บันทึก' : 'กลับไปแก้ต่อ'}
        tone="default"
        onConfirm={closeFlushFailed ? (() => setCloseConfirmOpen(false)) : (() => { setCloseConfirmOpen(false); onClose(); })}
        onCancel={closeFlushFailed ? handleCloseWithoutSaving : (() => setCloseConfirmOpen(false))}
        // In the closeFlushFailed branch, cancelLabel is the DESTRUCTIVE "ปิดโดยไม่บันทึก" action.
        // Without onDismiss, ConfirmDialog's Modal wires Escape/backdrop/header-✕ to that same
        // onCancel, so an accidental dismissal would discard the unsaved draft. onDismiss keeps
        // every dismissal gesture to "just close this confirm". Omitted otherwise: there
        // cancelLabel ("กลับไปแก้ต่อ") already IS the safe dismiss.
        onDismiss={closeFlushFailed ? (() => setCloseConfirmOpen(false)) : undefined}
      />
    </>
  );
}
