// Shared item-editor fields for the ticket/deal item forms — TicketCreateModal.jsx (create) and
// TicketDetailPage.jsx (edit-items mode). Extracted (fix/ticket-edit-items-required-markers) after
// a UAT report that reopening a deal to edit its items dropped the red required asterisks the
// create modal shows, and left leaving a required field blank to fail only with a backend 400
// (TicketItemRequest's @NotBlank on brand/model/size). Both surfaces must derive their required
// markers, labels, catalog-autocomplete UI and catalog-pick/hand-edit rules from ONE place so they
// can never drift apart again the way create and edit had (ชื่อยี่ห้อ vs ยี่ห้อ / โรงงาน, a
// separate โรงงาน input on edit only, no asterisks on edit at all).
import { api } from '../../api/index.js';
import { fieldErrorId } from '../../components/common/FormField.jsx';
import { resolveTileSqmPerPiece } from '../quotations/QuotationItemRow.jsx';

// ── required fields ─────────────────────────────────────────────────────────
// สี and เนื้อผิว are deliberately NOT here. The live catalog fills `color` on 21% of active rows
// and `surface` on 22% (both only for factories CDE/LEA/Panaria, plus Bode for surface), so
// requiring them forced a rep to invent a value on ~4 of every 5 catalog picks. They are optional
// on both sides now — `sales.ticket_item.color`/`.texture` were already nullable, and
// TicketItemRequest's @NotBlank on the two was removed to match. ยี่ห้อ/รุ่น/ขนาด stay required:
// the factory name, collection and size_raw are present on ~100% of rows.
export const REQUIRED_ITEM_FIELD_LABELS = {
  brand: 'ยี่ห้อ / โรงงาน',
  model: 'ชื่อรุ่น / Collection',
  size: 'ขนาด',
};

export function isRequiredItemField(field) {
  return field in REQUIRED_ITEM_FIELD_LABELS;
}

/**
 * Blank-field errors for the descriptive item fields, keyed by field name — 'กรุณากรอก<label>' for
 * every required field that is blank (after trim). Pure: takes an item row, returns a plain object.
 * Used by TicketCreateModal's zod superRefine (wrapped into ctx.addIssue calls, one per key) AND by
 * TicketDetailPage's pre-submit row validation directly. Messages are byte-identical to what the
 * create modal has always shown — this is a lift of that logic, not a rewrite.
 */
export function requiredItemFieldErrors(item) {
  const errors = {};
  for (const field of Object.keys(REQUIRED_ITEM_FIELD_LABELS)) {
    if (!String(item?.[field] ?? '').trim()) {
      errors[field] = `กรุณากรอก${REQUIRED_ITEM_FIELD_LABELS[field]}`;
    }
  }
  return errors;
}

// Canonical label/placeholder/hint per descriptive field — the create modal's own wording, now the
// single source both surfaces render from (this is what fixes the 'ชื่อยี่ห้อ' vs 'ยี่ห้อ / โรงงาน'
// drift: edit used to hand-roll its own shorter labels).
export const ITEM_FIELD_META = {
  brand: { label: 'ยี่ห้อ / โรงงาน', placeholder: 'เช่น Panaria, LEA, Bode' },
  model: { label: 'ชื่อรุ่น / Collection', placeholder: 'เช่น Stone Villa, Eco stone' },
  color: { label: 'สี', placeholder: 'เช่น ขาว, เทา, ครีม', hint: '(ไม่บังคับ)' },
  texture: { label: 'เนื้อผิว', placeholder: 'เช่น MATT, GLOSSY, ด้าน', hint: '(ไม่บังคับ)' },
  size: { label: 'ขนาด', placeholder: 'เช่น 600x1200' },
};

// ── quantity requiredness (PIECE vs SQM basis) ──────────────────────────────
// A ตร.ม./แผ่น factor cross-fills the two quantity boxes, but only ONE of them is ever the field the
// rep is required to fill for a given basis: จำนวน (แผ่น) for PIECE, พื้นที่ (ตร.ม.) for SQM. Single
// source for which one that is, so the asterisk on each surface's qty/qtySqm label and the
// validation that actually blocks submit can never point at different fields.
export function requiredQtyField(unitBasis) {
  return (unitBasis || 'PIECE') === 'SQM' ? 'qtySqm' : 'qty';
}

/** Mirrors makeItemSchema's two qty/qtySqm messages — kept together so a basis check picks the right one. */
export function missingQtyMessage(unitBasis, rowNumber) {
  return requiredQtyField(unitBasis) === 'qtySqm'
    ? `กรุณากรอกพื้นที่ (ตร.ม.) ในรายการที่ ${rowNumber}`
    : `กรุณากรอกจำนวน (แผ่น) ในรายการที่ ${rowNumber}`;
}

// ── catalog pick / hand-edit rules ──────────────────────────────────────────
// Every field a hand-edit to which invalidates a previously-picked catalog link — what's typed no
// longer necessarily matches what the link points at. Mirrors PricingRequestCreateModal.updateItem's
// identical rule. 'factory' is included even though neither surface exposes it as its own input any
// more (ยี่ห้อ and โรงงาน are one field) — see applyDescriptiveFieldEdit's brand branch below.
const DESCRIPTIVE_ITEM_FIELDS = ['brand', 'model', 'color', 'texture', 'size', 'factory'];

/**
 * Pure per-field row update for a hand-edit to one of the descriptive item fields. Folds in two
 * rules that used to live inline in TicketCreateModal's updateItem only:
 *   1. ยี่ห้อ and โรงงาน are one input — editing brand sets factory to the same value (the brand a
 *      rep means IS the factory: Padana, Vives, LEA, Bode…).
 *   2. A hand-edit to any descriptive field clears whatever catalog link was previously picked —
 *      source/catalogPriceId/catalogProductCode/catalogPrice/catalogCurrency/catalogPriceUnit/
 *      catalogGrade all reset. Some of those are UI-only fields TicketDetailPage's edit-items rows
 *      never carried before; nulling them there is harmless — the payload sent to
 *      api.tickets.editItems only ever reads catalogPriceId/catalogProductCode off the row (see its
 *      own items.map), so this never changes what ships to the backend for an untouched row.
 * Called with a non-descriptive field (qty, qtySqm, unitBasis, proposedPrice, …) it just assigns —
 * callers still own basis-driven qty/qtySqm cross-fill themselves, since that differs by surface.
 */
export function applyDescriptiveFieldEdit(item, field, value) {
  const updated = { ...item, [field]: value };
  if (field === 'brand') updated.factory = value;
  if (DESCRIPTIVE_ITEM_FIELDS.includes(field)) {
    updated.source = 'custom';
    updated.catalogPriceId = null;
    updated.catalogProductCode = '';
    updated.catalogPrice = null;
    updated.catalogCurrency = null;
    updated.catalogPriceUnit = null;
    updated.catalogGrade = null;
  }
  return updated;
}

/**
 * Pure row mapping for picking a catalog search result — the body of TicketCreateModal's
 * applyCatalogItem, lifted so TicketDetailPage's edit-items brand/model catalog pickers fill a row
 * exactly the way create does.
 */
export function applyCatalogPick(item, cat) {
  // ยี่ห้อ = the factory name, unconditionally. The previous rule read the catalog's `grade` as the
  // brand, on the reasoning that ProductPriceDto has no "brand" column of its own. The live catalog
  // disproves it: `grade` holds only 'A01'/'A02', and only for factory Padana (9,076 of 22,455
  // active rows) — a quality code. So that rule wrote "A01" into ยี่ห้อ for Padana and left it blank
  // for the other 60%, which is the "doesn't autofill everything" report from UAT. The factory names
  // in this catalog — Padana, Vives, Equipe, REFIN, CDE, LEA, Panaria, Bode, CITY — are exactly what
  // a rep calls the brand; this form's own ยี่ห้อ placeholder cites "Panaria", which is a factory
  // row. ยี่ห้อ and โรงงาน are therefore one field, and no text a rep left in it survives a pick:
  // whatever is in that box when the dropdown opens is the search query, not an independent brand
  // entry.
  const factory = cat.factoryName || cat.factory || cat.brand || '';

  // Owner ruling 2026-09-12 ("แก้ด้วย — ใช้ catalog เหมือนกัน"): resolved the same way the quotation
  // item editor resolves it — see resolveTileSqmPerPiece's own Javadoc-style comment for the order
  // (catalog's own sqm_per_piece; never for a per_linear_m row). The ~350 active catalog rows with
  // no sqm_per_piece of their own (all of factory Bode, plus LEA's trim pieces) resolve to `null`
  // here — nothing is guessed from the free-text size any more, so the แผ่น↔ตร.ม. toggle simply
  // leaves both quantities editable for those rows.
  const sizeRaw = cat.sizeRaw || cat.size || '';
  const sqmPerPiece = resolveTileSqmPerPiece(cat);
  const newQtySqm = item.qty && sqmPerPiece ? (Number(item.qty) * sqmPerPiece).toFixed(3) : '';

  return {
    ...item,
    brand: factory,
    factory,
    model: cat.collection || cat.productName || cat.productCode || '',
    // Blank stays blank — สี and เนื้อผิว are absent for ~79% of the catalog and are optional now.
    // Never substitute a product code or factory name for a colour the catalog lacks.
    color: cat.color || '',
    texture: cat.surface || '',
    size: sizeRaw,
    sqmPerPiece,
    qtySqm: newQtySqm,
    // UI-only provenance.
    source: 'catalog',
    catalogPrice: cat.price ?? null,
    catalogCurrency: cat.currency ?? null,
    catalogGrade: cat.grade ?? null,
    // Persisted (V110) — the catalog identity picked here, so a later step (e.g.
    // PricingRequestCreateModal) can seed its own catalog link without a re-search.
    catalogPriceId: cat.priceId ?? null,
    catalogProductCode: cat.productCode ?? '',
    catalogPriceUnit: cat.priceUnit ?? null,
  };
}

// ── catalog search debounce ──────────────────────────────────────────────────
// Module-level timer shared by every caller (create modal, edit page) — matches the single
// `_catalogTimer` TicketCreateModal.jsx always had. Only one item editor is ever open/focused at a
// time in practice, so a shared debounce timer costs nothing.
let _catalogTimer = null;
export function debouncedCatalogSearch(q, cb) {
  clearTimeout(_catalogTimer);
  _catalogTimer = setTimeout(() => cb(q), 280);
}

// Monotonic request sequence for searchCatalog below — a slower EARLIER request resolving after a
// faster LATER one would otherwise overwrite fresher dropdown results with stale ones (real risk:
// the catalog endpoint has no ordering guarantee, and two keystrokes close together can each clear
// the debounce timer but still both eventually fire their own fetch if the browser had already
// started the first one before the second keystroke's clearTimeout ran).
let _catalogSearchSeq = 0;

/**
 * The actual catalog fetch, debounced — separated from any row mutation so callers can trigger a
 * search (on focus, or on a real keystroke) without that alone counting as an edit to the row. See
 * applyDescriptiveFieldEdit's callers in both surfaces: typing goes through a path that also calls
 * this; merely focusing/re-focusing a field must not.
 */
export function searchCatalog(query, onResults) {
  const seq = ++_catalogSearchSeq;
  debouncedCatalogSearch(query, async (q) => {
    if (!q.trim()) { onResults([]); return; }
    try {
      const res = await api.catalog.prices(q, undefined, 20);
      // Stale-response guard — see _catalogSearchSeq's comment above.
      if (seq !== _catalogSearchSeq) return;
      onResults(res.items ?? []);
    } catch { /* ignore */ }
  });
}

// ── small sub-components ────────────────────────────────────────────────────

/**
 * Label content for the item-editor fields.
 *
 * Everything is wrapped in ONE <span> on purpose. The global `label { display: grid }` rule in
 * styles.css turns every direct child of a <label> — including a bare text node — into its own grid
 * row, so an unwrapped `{label}` + required-marker + hint would render as three stacked lines
 * instead of one (the same trap FormField.jsx documents at length).
 */
export function ItemFieldLabel({ label, required, hint }) {
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
 * hand-built <div> ones and read-only <div> boxes styled `px-2.5 py-[7px]` next to 40px-tall inputs,
 * so adjacent cells sat at visibly different heights and baselines.
 *
 * `content-start` keeps the control at its natural height instead of stretching it to the tallest
 * cell in the row — the row-stretch behaviour FormField.jsx documents as the app-wide field stagger.
 * The slack falls below the control, where it is invisible.
 */
export function ItemField({ id, label, hint, required, error, children }) {
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
 * result reads identically whichever box the rep is typing in — and now identically whichever
 * SURFACE (create modal or ticket-detail edit) the rep is on.
 */
export function CatalogOption({ cat }) {
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
 * Text input with the catalog autocomplete attached. Extracted (originally within
 * TicketCreateModal.jsx) because ยี่ห้อ/โรงงาน and รุ่น carried two byte-identical copies of the
 * dropdown markup, which is how the two drifted apart in the first place — a fix applied to one
 * silently missed the other. Sharing it across create AND edit closes the same class of drift
 * between surfaces instead of just within one.
 */
export function CatalogAutocompleteField({
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
