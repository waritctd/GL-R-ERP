import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { fieldErrorId } from '../../components/common/FormField.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { dealStageLabel, ticketPriorityLabel } from '../../utils/format.js';
import { formatCatalogPrice } from './catalogPriceDisplay.js';

/**
 * ตร.ม. per piece derived from a catalog `size_raw` string, for the ~350 active catalog rows that
 * carry no `sqm_per_piece` of their own (all of factory Bode, plus LEA's trim pieces). Without a
 * factor the แผ่น↔ตร.ม. toggle cannot cross-fill, which is what stranded a rep's entered quantity
 * in UAT.
 *
 * Only ever called when the catalog has no factor of its own, and deliberately only accepts a
 * MILLIMETRE reading — both dimensions >= 100. Catalog sizes are genuinely mixed-unit ("600x1200"
 * is mm, "30 x 60" and "120 x 278" are cm), and there is no reliable way to tell them apart from
 * the string alone. Every row that actually lacks a factor is millimetres; every centimetre-format
 * row already ships its own `sqm_per_piece` and so never reaches this function. Anything else —
 * centimetre-looking values, a third thickness dimension ("598X598X18"), junk ("15X1'5"), or no
 * size at all — returns null, and the UI then lets the rep enter both quantities by hand rather
 * than converting on a guess.
 */
export function deriveSqmPerPiece(sizeRaw) {
  const match = /^\s*(\d+(?:\.\d+)?)\s*[xX×]\s*(\d+(?:\.\d+)?)\s*$/.exec(sizeRaw ?? '');
  if (!match) return null;
  const width = Number(match[1]);
  const height = Number(match[2]);
  if (!(width >= 100) || !(height >= 100)) return null;
  const sqm = (width / 1000) * (height / 1000);
  return sqm > 0 ? Number(sqm.toFixed(6)) : null;
}

const emptyItem = () => ({
  brand: '', model: '', color: '', texture: '', size: '', factory: '',
  unitBasis: 'PIECE', qty: 1, qtySqm: '', sqmPerPiece: null,
  // True when sqmPerPiece came from deriveSqmPerPiece() rather than the catalog, so the UI can
  // label the conversion as derived instead of presenting it as catalog fact.
  sqmPerPieceDerived: false,
  // source ('catalog' | 'custom') + the catalog's reference price/currency/priceUnit are UI-only
  // — never sent in the onSubmit payload (see submit() below). They exist so the items view can
  // badge a line "จากแคตตาล็อก" vs "custom" and show the catalog's own price in its own currency
  // alongside a baht conversion (catalogPriceUnit names the unit that price is quoted per).
  source: 'custom', catalogPrice: null, catalogCurrency: null, catalogPriceUnit: null,
  // Padana's A01/A02 quality code, shown as a badge. NEVER written into ยี่ห้อ: `grade` is a
  // quality code that only factory Padana populates (9,076 of 22,455 active rows, values A01/A02
  // only) — the code used to map it to ยี่ห้อ, which put "A01" in a brand field or left it blank
  // for the other 60%. The brand a rep means is the factory (Padana, Vives, LEA, Bode…), which is
  // why ยี่ห้อ and โรงงาน are now one field.
  catalogGrade: null,
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
// สี and เนื้อผิว are deliberately NOT here. The live catalog fills `color` on 21% of active rows
// and `surface` on 22% (both only for factories CDE/LEA/Panaria, plus Bode for surface), so
// requiring them forced a rep to invent a value on ~4 of every 5 catalog picks. They are optional
// on both sides now — `sales.ticket_item.color`/`.texture` were already nullable, and
// TicketItemRequest's @NotBlank on the two was removed to match. ยี่ห้อ/รุ่น/ขนาด stay required:
// the factory name, collection and size_raw are present on ~100% of rows.
const REQUIRED_ITEM_FIELD_LABELS = {
  brand: 'ยี่ห้อ / โรงงาน',
  model: 'ชื่อรุ่น / Collection',
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
// Descriptive-only by owner ruling (it drives no behaviour), but it must be STATED — see the
// no-default comment on the entryChannel state below.
const entryChannelRequiredSchema = z.any().refine((v) => v != null, 'กรุณาเลือกช่องทางดีล (ระบุว่าดีลนี้เข้ามาทางไหน)');

/**
 * Validates the whole form and returns every invalid field, plus `order`:
 * those same keys in on-screen top-to-bottom order, so submit() can focus
 * the first one without depending on zod's internal issue ordering (a
 * root-level check runs after nested array elements are validated, which
 * would put item errors before customer/project if we relied on it).
 */
function validateTicketForm({ customer, project, entryChannel, items }) {
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

  // Unlike project, NOT nested inside `if (customerResult.success)` — the channel picker is
  // reachable and answerable regardless of whether a customer is chosen yet.
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

// Which sub-view owns a given fieldErrors key — used to jump the user
// straight to (and, once mounted, focus) the first invalid field.
function viewForFieldKey(key) {
  if (key === 'customer') return 'customer';
  if (key === 'project') return 'project';
  if (key === 'entryChannel') return 'contact';
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

/**
 * Label content for the item-editor fields.
 *
 * Everything is wrapped in ONE <span> on purpose. The global `label { display: grid }` rule in
 * styles.css turns every direct child of a <label> — including a bare text node — into its own
 * grid row, so an unwrapped `{label}` + required-marker + hint would render as three stacked
 * lines instead of one (the same trap FormField.jsx documents at length).
 */
function ItemFieldLabel({ label, required, hint }) {
  return (
    <span>
      {label}
      {required ? <span className="text-danger" aria-hidden="true"> *</span> : null}
      {hint ? <span className="ml-1 font-semibold text-text-muted">{hint}</span> : null}
    </span>
  );
}

/**
 * Label + control + error wrapper for the item editor, matching CatalogAutocompleteField's own
 * markup exactly so every cell of the two-column grid has an identical label row, gap and error
 * slot. That identity is the alignment fix: the editor previously mixed bare <label> fields with
 * hand-built <div> ones and read-only <div> boxes styled `px-2.5 py-[7px]` next to 40px-tall
 * inputs, so adjacent cells sat at visibly different heights and baselines.
 *
 * `content-start` keeps the control at its natural height instead of stretching it to the tallest
 * cell in the row — the row-stretch behaviour FormField.jsx documents as the app-wide field
 * stagger. The slack falls below the control, where it is invisible.
 */
function ItemField({ id, label, hint, required, error, children }) {
  const errorId = error ? fieldErrorId(id) : undefined;
  return (
    <div className="grid content-start gap-[7px]">
      <label htmlFor={id} className="m-0 text-xs">
        <ItemFieldLabel label={label} required={required} hint={hint} />
      </label>
      {children}
      {error ? (
        <p id={errorId} role="alert" className="m-0 text-2xs font-bold text-danger">{error}</p>
      ) : null}
    </div>
  );
}

/**
 * One catalog-search row of the summary dropdown. Shared by the ยี่ห้อ/โรงงาน and รุ่น fields so a
 * result reads identically whichever box the rep is typing in.
 */
function CatalogOption({ cat }) {
  return (
    <>
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
    </>
  );
}

/**
 * Text input with the catalog autocomplete attached. Extracted because ยี่ห้อ/โรงงาน and รุ่น
 * carried two byte-identical copies of the dropdown markup, which is how the two drifted apart in
 * the first place — a fix applied to one silently missed the other.
 */
function CatalogAutocompleteField({
  id, label, hint, required, value, placeholder, onInput, onFocusSearch,
  onBlur, expanded, results, onPick, error, inputRef,
}) {
  const errorId = error ? fieldErrorId(id) : undefined;
  return (
    <div className="relative grid content-start gap-[7px]">
      <label htmlFor={id} className="m-0 text-xs">
        <ItemFieldLabel label={label} required={required} hint={hint} />
      </label>
      <input
        id={id}
        ref={inputRef}
        value={value}
        onChange={(e) => onInput(e.target.value)}
        onFocus={onFocusSearch}
        onBlur={onBlur}
        placeholder={placeholder}
        aria-required={required ? 'true' : undefined}
        aria-invalid={error ? true : undefined}
        aria-describedby={errorId}
      />
      {error ? (
        <p id={errorId} role="alert" className="m-0 text-2xs font-bold text-danger">{error}</p>
      ) : null}
      {expanded && results.length > 0 && (
        <div className="absolute left-0 right-0 top-full z-[60] max-h-[200px] overflow-y-auto rounded-[6px] border border-border-subtle bg-surface shadow-[0_4px_16px_rgba(0,0,0,0.12)]">
          {results.map((cat) => (
            // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- autocomplete option row; onMouseDown (not click) preserves input focus for typeahead
            <div key={cat.priceId ?? cat.id} onMouseDown={() => onPick(cat)}
              className="cursor-pointer border-b border-surface-subtle px-2.5 py-[7px] text-xs hover:bg-info-row-active"
            >
              <CatalogOption cat={cat} />
            </div>
          ))}
        </div>
      )}
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

  // hub | customer | project | contact | items | details
  const [view, setView] = useState('hub');
  // fieldErrors key waiting to be scrolled+focused once its owning view/item
  // editor has actually mounted (see the effect below).
  const [pendingFocusKey, setPendingFocusKey] = useState(null);

  const [form, setForm] = useState({ note: initialDraft?.note ?? '' });
  const [dealTitle, setDealTitle] = useState(initialDraft?.dealTitle ?? '');
  const [priority, setPriority] = useState(initialDraft?.priority ?? 'NORMAL');
  // No default on purpose. A pre-selected channel meant a rep who never noticed this control
  // silently recorded "designer-led", and because a value is ALWAYS sent the backend could not
  // tell that apart from a deliberate choice — so no backend default could rescue it. A restored
  // draft still pre-fills, because editing a draft is not the same as creating fresh.
  const [entryChannel, setEntryChannel] = useState(initialDraft?.entryChannel ?? null);
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

  // ── FX rates, for showing a catalog price in baht alongside its own currency ───────────────
  // The catalog quotes EUR and USD only. This is a straight currency conversion and nothing more:
  // the ราคาตั้ง (ประมาณการ) estimate that used to live here — catalog price × FX × a
  // CEO-configured markup — was removed on the owner's instruction after UAT, because reps read
  // its output as a selling price when the real one only comes out of the pricing-request → CEO
  // costing chain. See catalogPriceDisplay.js.
  //
  // Degrades silently by design: `retry: false`, no toast (this is optional display enrichment,
  // not critical modal data, and a deal must stay creatable with zero prices — V50). When a rate
  // is missing the catalog's own currency is shown alone; it is never converted at an assumed 1:1.
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

  // Opening the ลูกค้า step lands the cursor in its search box, which also opens the results list
  // (SearchSelect searches on focus). Without this the step arrives as one empty field in a tall
  // panel — the company list, the only thing on the step, stays hidden behind a keystroke. Skipped
  // while a validation jump is pending so the two never fight over focus, and once a customer is
  // chosen (the field is then a value chip, not an input).
  useEffect(() => {
    if (view !== 'customer' || selectedCustomer || pendingFocusKey) return;
    fieldRefs.current.customer?.focus();
  }, [view, selectedCustomer, pendingFocusKey]);

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
      // ยี่ห้อ and โรงงาน are one input now (the brand a rep means IS the factory — Padana, Vives,
      // LEA, Bode…), but the payload still carries both fields, so keep them in lockstep here
      // rather than deriving at submit time only.
      if (field === 'brand') updated.factory = value;
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
      // A size edit invalidates a factor that was DERIVED from the old size (see
      // deriveSqmPerPiece) — recompute it, or drop it when the new size can't be read. A factor
      // the catalog supplied is left alone; the user editing the size already unlinks the row.
      if (field === 'size' && item.sqmPerPieceDerived) {
        const rederived = deriveSqmPerPiece(value);
        updated.sqmPerPiece = rederived;
        updated.sqmPerPieceDerived = rederived != null;
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
        updated.catalogPriceUnit = null;
        updated.catalogGrade = null;
      }
      return updated;
    }));
  }

  function applyCatalogItem(index, cat) {
    setItems((cur) => cur.map((item, i) => {
      if (i !== index) return item;

      // ยี่ห้อ = the factory name, unconditionally. The previous rule read the catalog's `grade`
      // as the brand, on the reasoning that ProductPriceDto has no "brand" column of its own. The
      // live catalog disproves it: `grade` holds only 'A01'/'A02', and only for factory Padana
      // (9,076 of 22,455 active rows) — a quality code. So that rule wrote "A01" into ยี่ห้อ for
      // Padana and left it blank for the other 60%, which is the "doesn't autofill everything"
      // report from UAT. The factory names in this catalog — Padana, Vives, Equipe, REFIN, CDE,
      // LEA, Panaria, Bode, CITY — are exactly what a rep calls the brand; this form's own ยี่ห้อ
      // placeholder cites "Panaria", which is a factory row. ยี่ห้อ and โรงงาน are therefore one
      // field, and no text a rep left in it survives a pick: whatever is in that box when the
      // dropdown opens is the search query, not an independent brand entry.
      const factory = cat.factoryName || cat.factory || cat.brand || '';

      // The catalog carries a per-piece area for 98.4% of rows; derive it from the size for the
      // rest so the แผ่น↔ตร.ม. toggle can still cross-fill (see deriveSqmPerPiece).
      const catalogSqmPerPiece = cat.sqmPerPiece || null;
      const sizeRaw = cat.sizeRaw || cat.size || '';
      const derivedSqmPerPiece = catalogSqmPerPiece ? null : deriveSqmPerPiece(sizeRaw);
      const sqmPerPiece = catalogSqmPerPiece ?? derivedSqmPerPiece;
      const newQtySqm = item.qty && sqmPerPiece ? (Number(item.qty) * sqmPerPiece).toFixed(3) : '';

      return {
        ...item,
        brand:       factory,
        factory,
        model:       cat.collection   || cat.productName || cat.productCode || '',
        // Blank stays blank — สี and เนื้อผิว are absent for ~79% of the catalog and are optional
        // now. Never substitute a product code or factory name for a colour the catalog lacks.
        color:       cat.color        || '',
        texture:     cat.surface      || '',
        size:        sizeRaw,
        sqmPerPiece,
        sqmPerPieceDerived: catalogSqmPerPiece == null && derivedSqmPerPiece != null,
        qtySqm:      newQtySqm,
        // UI-only provenance — see emptyItem()'s comment.
        source: 'catalog',
        catalogPrice: cat.price ?? null,
        catalogCurrency: cat.currency ?? null,
        catalogGrade: cat.grade ?? null,
        // Persisted (V110) — see emptyItem()'s comment. cat.priceId is
        // ProductPriceDto.priceId, the same price_catalog.product_prices.price_id that
        // sales.pricing_request_item.product_id already points at (V68).
        catalogPriceId: cat.priceId ?? null,
        catalogProductCode: cat.productCode ?? '',
        catalogPriceUnit: cat.priceUnit ?? null,
      };
    }));
    // A catalog pick fills every required field in one shot — clear anything previously flagged.
    Object.keys(REQUIRED_ITEM_FIELD_LABELS).forEach((f) => clearFieldError(`items.${index}.${f}`));
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
      entryChannel,
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
          // Optional since the ยี่ห้อ/สี/เนื้อผิว rework — null, not '', so a row with no colour
          // reads as absent rather than as an empty string someone typed. Both columns are
          // nullable in sales.ticket_item and TicketItemRequest no longer marks them @NotBlank.
          color: item.color.trim() || null,
          texture: item.texture.trim() || null,
          size: item.size.trim(),
          // One input feeds both — see updateItem(). The fallback covers a row restored from an
          // older draft whose factory was captured separately.
          factory: (item.factory || item.brand).trim() || null,
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
    // ผู้ติดต่อ is optional (contactId is nullable); the REQUIRED thing in this section is the
    // entry channel, so that is what ticks the row — same as ลูกค้า/โครงการ tracking theirs.
    contact: Boolean(entryChannel),
    items: items.length > 0,
    details: Boolean(dealTitle.trim() || form.note.trim() || priority !== 'NORMAL'),
  };
  // 5 real sections. The separate "ตรวจสอบ & บันทึก" step used to be a sixth row that could never
  // be ticked; it was removed on the owner's instruction after UAT — the hub already IS the
  // review (every row shows what is filled in), and the สร้างดีล button sits in the footer beside
  // it, so a dedicated step only added a click between the rep and the thing they came to do.
  const TOTAL_SECTIONS = 5;
  const doneCount = Object.values(sectionDone).filter(Boolean).length;

  // Outstanding fields listed on the hub after a failed submit. Recomputed from the CURRENT form
  // rather than read off `fieldErrors`' key order, so the list shrinks as the rep fixes things and
  // stays in on-screen order (`fieldErrors` is a plain object and carries neither property).
  const hubValidation = Object.keys(fieldErrors).length > 0
    ? validateTicketForm({ customer: selectedCustomer, project: selectedProject, entryChannel, items })
    : null;
  const hubMissingErrors = hubValidation?.errors ?? {};
  const hubMissingKeys = hubValidation?.order ?? [];
  const canCreateNow = Boolean(selectedCustomer && selectedProject);

  const entryChannelLabel = entryChannel
    ? (ENTRY_CHANNEL_OPTIONS.find((o) => o.code === entryChannel)?.label ?? entryChannel)
    : 'ยังไม่ได้เลือกช่องทาง';
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
            required
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
        </div>

        {/* Only after a submit attempt has actually failed — the จำเป็น badges above already say
            what is outstanding, so showing a red block to someone who has just opened the form
            would be nagging rather than helping. This is what the removed ตรวจสอบ step contributed
            that the hub rows do not: a jump straight to a specific bad field inside an item row. */}
        {hubMissingKeys.length > 0 ? (
          <div className="rounded-lg border border-danger-border bg-danger-bg px-3 py-2.5">
            <p className="flex items-center gap-1.5 text-xs font-extrabold text-danger-dark">
              <Icon name="triangleAlert" size={14} /> ยังกรอกไม่ครบ {hubMissingKeys.length} รายการ
            </p>
            <ul className="mt-1.5 flex list-disc flex-col gap-1 pl-4 text-2xs text-danger-dark">
              {hubMissingKeys.map((key) => (
                <li key={key}>
                  {hubMissingErrors[key]}
                  {' — '}
                  <button
                    type="button"
                    className="font-extrabold underline"
                    onClick={() => { setFieldErrors(hubMissingErrors); jumpToField(key); }}
                  >
                    ไปที่ขั้นตอน
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ) : null}

        <div className="flex items-start gap-2 rounded-lg border border-info-border bg-info-bg px-3 py-2.5 text-xs text-info-dark">
          <Icon name="info" size={15} className="mt-0.5 shrink-0" />
          <span>ดีลที่สร้างยังไม่มีราคา — ขั้นต่อไปคือ “สร้างคำขอราคา” จากหน้าดีล ซึ่งต้องมีรายการสินค้าอย่างน้อย 1 รายการ</span>
        </div>

        {error ? <div className="py-2.5 px-3 rounded-md bg-danger-bg text-danger-dark font-bold text-[length:var(--text-sm)]" role="alert">{error}</div> : null}
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
          <span className="mb-1 block text-sm font-bold text-text-secondary">
            ช่องทางดีล (entry channel)
            <span className="text-danger" aria-hidden="true"> *</span>
          </span>
          {/* Why this is asked, so a forced choice does not become a random one. */}
          <p className="mb-1.5 mt-0 text-2xs text-text-muted">
            ดีลนี้เข้ามาทางไหน — ใช้อธิบายว่าทำไมดีลถึงข้ามขั้นตอนฝั่งออกแบบ
          </p>
          <div
            className="grid grid-cols-3 gap-1.5"
            role="radiogroup"
            aria-label="ช่องทางดีล"
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
                  onClick={() => { setEntryChannel(option.code); clearFieldError('entryChannel'); }}
                  className={`rounded-md border px-1.5 py-2.5 text-center text-xs font-extrabold leading-tight ${
                    selected
                      ? 'border-primary bg-primary/10 text-primary'
                      : entryChannel
                        // A choice HAS been made, just not this one — plain solid, as before.
                        ? 'border-border bg-surface text-text-secondary'
                        // Nothing chosen yet. Dashed is this app's "not yet decided" idiom
                        // (HubRow's incomplete check circle, the empty-state boxes, the add
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
    const basis = item.unitBasis || 'PIECE';
    // With a ตร.ม./แผ่น factor the two quantity boxes cross-fill, so whichever one the rep is not
    // driving is derived and read-only. Without a factor NEITHER can be derived — ~350 active
    // catalog rows carry no area per piece and their size string could not be read either — so
    // both stay editable instead of leaving the required box empty while the number the rep
    // actually typed sits greyed out in the other one. That stranding is the exact state in the
    // UAT screenshot: "1000 แผ่น" greyed beside an empty, erroring พื้นที่ (ตร.ม.).
    const hasFactor = Boolean(item.sqmPerPiece);
    const catalogPrice = item.catalogPrice != null
      ? formatCatalogPrice(item.catalogPrice, item.catalogCurrency, item.catalogPriceUnit, fxRatesByCurrency)
      : null;

    return (
      <div className="flex flex-col gap-4">
        <BackLink onClick={() => setEditingItemIndex(null)} label="กลับไปรายการสินค้า" />

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
            label="ยี่ห้อ / โรงงาน"
            required
            value={item.brand}
            placeholder="เช่น Panaria, LEA, Bode"
            onInput={(value) => onBrandInput(index, value)}
            onFocusSearch={() => { setCatalogFocus({ index, field: 'brand' }); if (item.brand) onBrandInput(index, item.brand); }}
            onBlur={() => setTimeout(() => setCatalogFocus(null), 180)}
            expanded={catalogFocus?.index === index && catalogFocus?.field === 'brand'}
            results={catalogResults}
            onPick={(cat) => applyCatalogItem(index, cat)}
            error={fieldErrors[`items.${index}.brand`]}
            inputRef={(el) => { fieldRefs.current[`items.${index}.brand`] = el; }}
          />

          <CatalogAutocompleteField
            id={`item-${index}-model`}
            label="ชื่อรุ่น / Collection"
            required
            value={item.model}
            placeholder="เช่น Stone Villa, Eco stone"
            onInput={(value) => onModelInput(index, value)}
            onFocusSearch={() => { setCatalogFocus({ index, field: 'model' }); if (item.model) onModelInput(index, item.model); }}
            onBlur={() => setTimeout(() => setCatalogFocus(null), 180)}
            expanded={catalogFocus?.index === index && catalogFocus?.field === 'model'}
            results={catalogResults}
            onPick={(cat) => applyCatalogItem(index, cat)}
            error={fieldErrors[`items.${index}.model`]}
            inputRef={(el) => { fieldRefs.current[`items.${index}.model`] = el; }}
          />

          <ItemField
            id={`item-${index}-size`}
            label="ขนาด"
            required
            error={fieldErrors[`items.${index}.size`]}
          >
            <input
              id={`item-${index}-size`}
              ref={(el) => { fieldRefs.current[`items.${index}.size`] = el; }}
              value={item.size}
              onChange={(e) => updateItem(index, 'size', e.target.value)}
              placeholder="เช่น 600x1200"
              aria-required="true"
              aria-invalid={fieldErrors[`items.${index}.size`] ? true : undefined}
              aria-describedby={fieldErrors[`items.${index}.size`] ? fieldErrorId(`item-${index}-size`) : undefined}
            />
          </ItemField>

          {/* สี and เนื้อผิว are optional: the live catalog fills them on ~21% of active rows, so
              requiring them made a rep invent a value on 4 of every 5 catalog picks. */}
          <ItemField id={`item-${index}-color`} label="สี" hint="(ไม่บังคับ)">
            <input
              id={`item-${index}-color`}
              value={item.color}
              onChange={(e) => updateItem(index, 'color', e.target.value)}
              placeholder="เช่น ขาว, เทา, ครีม"
            />
          </ItemField>

          <ItemField id={`item-${index}-texture`} label="เนื้อผิว" hint="(ไม่บังคับ)">
            <input
              id={`item-${index}-texture`}
              value={item.texture}
              onChange={(e) => updateItem(index, 'texture', e.target.value)}
              placeholder="เช่น MATT, GLOSSY, ด้าน"
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
              {item.sqmPerPieceDerived ? ` (คำนวณจากขนาด ${item.size})` : ''}
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
            required={basis === 'PIECE'}
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
              aria-required={basis === 'PIECE' ? 'true' : undefined}
              aria-invalid={fieldErrors[`items.${index}.qty`] ? true : undefined}
              aria-describedby={fieldErrors[`items.${index}.qty`] ? fieldErrorId(`item-${index}-qty`) : undefined}
            />
          </ItemField>

          <ItemField
            id={`item-${index}-qtySqm`}
            label="พื้นที่ (ตร.ม.)"
            required={basis === 'SQM'}
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
              aria-required={basis === 'SQM' ? 'true' : undefined}
              aria-invalid={fieldErrors[`items.${index}.qtySqm`] ? true : undefined}
              aria-describedby={fieldErrors[`items.${index}.qtySqm`] ? fieldErrorId(`item-${index}-qtySqm`) : undefined}
            />
          </ItemField>
        </div>

        {/* Catalog price: the factory's own figure in the currency it is quoted in, with a baht
            conversion underneath. Replaces the ราคาตั้ง (ประมาณการ) block — see
            catalogPriceDisplay.js for why a marked-up figure is no longer shown here. */}
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
            <p className="text-xs text-text-muted">พิมพ์ในช่อง ยี่ห้อ / โรงงาน หรือ รุ่น เพื่อค้นจากแคตตาล็อก — หรือกรอกสินค้าที่ยังไม่มีในระบบเอง</p>
            {/* One button, not two: the pair here ("ค้นหาสินค้า" / "เพิ่มสินค้าเอง") both called
                addItem() and opened the same editor, so the choice they offered was not real. */}
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
                    {/* No factory chip: ยี่ห้อ and โรงงาน are one field now, so it would repeat
                        the brand already shown in this row's title. */}
                    {item.color ? <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-2xs font-bold text-text-secondary">{item.color}</span> : null}
                    {item.texture ? <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-2xs font-bold text-text-secondary">{item.texture}</span> : null}
                    {item.size ? <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-2xs font-bold text-text-secondary">{item.size}</span> : null}
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
                  {/* The catalog's own price, in its own currency plus baht. No row total and no
                      grand total: a line's price unit (per_sqm / per_piece / per_box) does not
                      always match the unit the rep is ordering in, and restating it needs a
                      pieces-per-box factor the catalog has no column for. */}
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

  function renderFooter() {
    if (view === 'hub') {
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

  // CRITICAL (HTML implicit submission, fix/form-enter-submits-real-records; migrated to
  // SafeForm's `canSubmit` under #safe-form-primitive): this <form> wraps all 6 sub-views below,
  // and a form with no submit button but exactly ONE field that blocks implicit submission fires
  // a real 'submit' event on Enter with no button ever pressed. That was true for DETAILS
  // (renderDetailsView's ชื่อดีล input is its only such field -- the textarea and role="radio"
  // priority chips don't count) and, before a project/customer is picked, for PROJECT and
  // CUSTOMER too — `submit()` used to run regardless of `view`, so typing a deal title and
  // pressing Enter filed a real deal (confirmed in a real browser; jsdom does not implement
  // implicit submission, which is why the suite stayed green with the bug live — see
  // TicketCreateModal.test.jsx's `fireEvent.submit(form)` cases).
  //
  // `canSubmit={view === 'hub'}` matches renderFooter()'s own condition for when a real
  // `type="submit" form="ticket-create-form"` button exists (above). It was
  // `view === 'hub' || view === 'review'` until the ตรวจสอบ & บันทึก step was removed; HUB is now
  // the only view that renders a submitter, and every other view is unconditionally blocked here.
  // The protection is unchanged in strength — the set of submitting views shrank by one, and the
  // one that went away is the one that no longer exists.
  //
  // HUB was never part of this bug class: a linked submit button is in the DOM the whole time HUB
  // is showing (it lives in the modal's footer, a sibling of this <form>, wired via `form=` — see
  // Modal.jsx), so HUB never satisfies "no submit button". Narrowing this gate further would
  // silently turn that real, working button into a dead click instead of closing a hole.
  // `canSubmit` is a RESTRICTION and never a permission (see SafeForm.jsx's header): SafeForm's
  // submitter guard still applies on top of it, so BOTH must pass. That AND costs this form
  // nothing — HUB's footer button is real and always in the DOM whenever `canSubmit` is true — but
  // it does mean the happy-path tests have to carry a real submitter rather than dispatching a
  // bare `fireEvent.submit(form)`; see `submitForm()` in TicketCreateModal.test.jsx.

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
      <SafeForm id="ticket-create-form" onSubmit={submit} canSubmit={view === 'hub'} noValidate>
        {/*
          min-height keeps the panel one consistent size across steps. Modal's body is
          `overflow-auto` with no floor, so a short view — ลูกค้า is a single search field — used to
          collapse the whole dialog to a few centimetres and then CLIP its own results dropdown at
          the body's scroll edge (reported from UAT: "the modal when selecting ลูกค้า is very
          small", with the company list cut mid-row). 26rem clears SearchSelect's 380px dropdown
          with room to spare, and the body still scrolls normally on a short viewport.
        */}
        <div className="min-h-[26rem]">
          {view === 'hub' && renderHub()}
          {view === 'customer' && renderCustomerView()}
          {view === 'project' && renderProjectView()}
          {view === 'contact' && renderContactView()}
          {view === 'items' && renderItemsView()}
          {view === 'details' && renderDetailsView()}
        </div>
      </SafeForm>
    </Modal>
  );
}
