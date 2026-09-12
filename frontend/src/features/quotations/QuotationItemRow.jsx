import { useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { formatMoney } from '../../utils/format.js';
import {
  LINE_TYPE_ADJUSTMENT, LINE_TYPE_PLAIN, LINE_TYPE_TILE,
  ORIGIN_COUNTRY_OPTIONS, QUANTITY_MODE_OPTIONS, UNLABELLED_LOCATION_TEXT, WASTAGE_PERCENT_PRESETS,
  defaultLeadTimeForOrigin, formatQuotationMoney, lineTypeOf, originCountryFromCode,
  piecesPerSqmFromSqmPerPiece, sqmPerPieceFromPiecesPerSqm,
} from './quotationMeta.js';

// ProductPriceDto's own price_unit for a linear-metre trim (V153: 561 real catalog rows). Its
// `sqm_per_piece` is NOT an area for these rows -- it is LINEAR METRES per piece (the source
// sqm_per_box column is mislabelled the same way, per that migration's own column comment), so
// resolveTileSqmPerPiece below must never read it as one. Kept a named constant rather than a bare
// string so the one place this matters cannot silently drift from CatalogRepository's own literal.
const PRICE_UNIT_PER_LINEAR_M = 'per_linear_m';

/**
 * The pieces-per-ตร.ม. resolution order for a catalog pick (owner feedback 2026-09-12; see
 * QuotationItemRow's module comment for the full request). Returns the `sqmPerPiece` (ตร.ม./แผ่น,
 * the wire field) to autofill, or `null` when nothing should be invented and the rep must type it:
 *
 *   a. Catalog-linked, priceUnit != per_linear_m -- `cat.sqmPerPiece` STRAIGHT FROM THE CATALOGUE.
 *      This is the authoritative figure (price_catalog.product_prices.sqm_per_piece); geometry
 *      (width x height) disagrees with it on 8.6% of the real catalog, by up to 14x on trims, so
 *      it is never recomputed here even when a width/height happened to be available.
 *   b. Catalogue row with NO `sqmPerPiece` -- compute from `widthMm` x `heightMm`, which are
 *      ALWAYS millimetres in price_catalog.product_prices and so need no unit inference at all.
 *      Owner ruling 2026-09-12: "2) ไม่มีค่อยคำนวนเอง" -- fall back to computing it, but from the
 *      CATALOGUE's own dimensions, never from the free-text ขนาด field, which mixes cm and mm in
 *      the same column (confirmed against production data, and exactly what "Do not infer
 *      anything" forbids). ~250 catalogue rows carry width/height without a sqm_per_piece.
 *      ⚠️ This MUST mirror DealQuotationService#resolveSqmPerPiece's step 3: if the UI leaves the
 *      field blank where the server would have resolved it, frontend validation blocks a row the
 *      backend would have accepted.
 *   c. priceUnit === per_linear_m -- `cat.sqmPerPiece` is LINEAR METRES per piece, not area (see
 *      the constant above). Never computed geometrically either. Returns `null` so the rep enters
 *      it, and the UI says why (see the "ต่อเมตร" hint below).
 *   d. Nothing resolved -- `null`. The field stays exactly as the rep left it; nothing is invented.
 */
export function resolveTileSqmPerPiece(cat) {
  if (cat?.priceUnit === PRICE_UNIT_PER_LINEAR_M) return null;
  if (cat?.sqmPerPiece != null) return cat.sqmPerPiece;
  const w = Number(cat?.widthMm);
  const h = Number(cat?.heightMm);
  if (Number.isFinite(w) && Number.isFinite(h) && w > 0 && h > 0) return (w * h) / 1e6;
  return null;
}

/**
 * One item row of the ใบเสนอราคา editor -- catalog typeahead (autofills, everything stays
 * editable after), quantity mode toggle, wastage segmented control, and the live calculation
 * line the parent keeps up to date via a 300ms-debounced `calculate-line` call (see
 * QuotationEditorPage.jsx's `updateItem`; this component only renders what it is given and
 * reports plain field patches upward).
 *
 * ตำแหน่งติดตั้ง is NOT a field here any more (owner feedback F1, 2026-09-10): it belongs to the
 * GROUP this row sits in, is typed once in that group's header, and is stamped onto every item at
 * save time by QuotationEditorPage. This row instead carries the two controls that move a row
 * between groups -- "ย้ายไปตำแหน่ง…" and "ทำซ้ำรายการ" -- both of which are no-ops the parent
 * performs; this component never owns the item list.
 *
 * `errors` (optional, defaults to `{}`) is `validateQuotationItem(item)`'s own return value
 * (quotationMeta.js), computed by the parent and passed down already gated on whether this row
 * should show its inline hints yet -- QuotationEditorPage only passes a non-empty object once the
 * row has been touched (not on first paint of a freshly-added blank row), so this component never
 * decides that itself. `error={errors.field}` on a FormField renders the red hint + wires
 * aria-invalid; `required` marks the label with `*`, matching the rest of the app.
 */
export function QuotationItemRow({
  item, index, readOnly, errors = {}, onChange, onRemove,
  groupId = null, locationGroups = [], recentPicks = [], onMove, onDuplicate, onCatalogPicked,
  // v3: the QUOTATION's tile price mode (one per document — see quotationMeta's PRICE_MODE_OPTIONS)
  // and its currency. Both default to the pre-v3 behaviour so an existing caller is unchanged.
  priceMode = 'NET', currency = 'THB',
  // Extension point for per-item PICTURES (GLA-75 — being built on another branch, not merged).
  // A render prop rather than an upload control here, so that branch can slot its uploader and
  // thumbnail under the row's notes without re-plumbing this component: `(item, index) => node`.
  // Unused today, which renders nothing.
  renderMedia = null,
}) {
  const [catalogResults, setCatalogResults] = useState([]);
  const [catalogOpen, setCatalogOpen] = useState(false);
  // #L4: "กำหนดเอง" opens the custom input -- UI-only state, never written onto `item` itself.
  // The old version jammed `wastageValue: 12` on click (an arbitrary number nobody chose, whose
  // only purpose was to fail the presets check below so the custom input would render). Resets
  // whenever a preset or the +N mode is picked, so it never lingers after the user picks a
  // normal option.
  const [wastageCustomOpen, setWastageCustomOpen] = useState(false);
  // #L2: PER-ROW debounce timer (one ref per mounted row), not a module-level singleton -- the
  // old `_catalogTimer` was shared across every row on the page, so typing in one row's รุ่น
  // field cancelled (and could starve) whatever another row's typeahead had queued.
  const catalogTimer = useRef(null);

  function patch(fields) {
    onChange(fields);
  }

  function debouncedCatalogSearch(q, cb) {
    clearTimeout(catalogTimer.current);
    catalogTimer.current = setTimeout(() => cb(q), 280);
  }

  function onCatalogQuery(value) {
    // #M9: clears ONLY catalogPriceId, not productCode -- a typed รุ่น value invalidates the
    // catalog LINK (this is no longer "the row picked from the dropdown"), but productCode is
    // its own free-text field the user may have typed or edited independently; wiping it just
    // because รุ่น changed threw away data the user never asked to clear.
    //
    // catalogSqmPerPiece/catalogPriceUnit ride along for the same reason: they are this row's
    // provenance snapshot for the แผ่น/ตร.ม. badge below (owner feedback 2026-09-12), and a
    // provenance claim about a catalog row this line no longer names would be exactly the kind of
    // stale claim #M9 already fixed for the "จาก catalog" badge itself. `sqmPerPiece` (the actual
    // value) is deliberately left alone -- retyping รุ่น should not blank out a number the rep may
    // still want, only stop claiming it came from a catalog match.
    patch({ model: value, catalogPriceId: null, catalogSqmPerPiece: null, catalogPriceUnit: null });
    setCatalogOpen(true);
    debouncedCatalogSearch(value, async (q) => {
      if (!q?.trim()) { setCatalogResults([]); return; }
      try {
        const res = await api.catalog.prices(q, undefined, 20);
        setCatalogResults(res.items ?? []);
      } catch { /* ignore -- typeahead only */ }
    });
  }

  function pickCatalog(cat) {
    // thicknessMm/pcsPerBox/sqmPerBox are additive fields the backend slice is adding to
    // ProductPriceDto alongside this feature -- optional until it lands, per the plan. Autofills
    // brand/รุ่น/สี/ผิว/ขนาด/ความหนา/ตร.ม./แผ่น/แผ่น-กล่อง/รหัสสินค้า -- everything the DTO knows
    // (owner ruling 2026-09-10, "autofill as much as possible") -- reusing the exact mapping
    // PricingRequestCreateModal.jsx:189-207's deriveItemFromCatalogProduct already uses (collection
    // -> productName -> productCode for รุ่น, factoryName for ยี่ห้อ, color/surface/sizeRaw
    // straight across) so the two surfaces never disagree about what a catalog row means.
    //
    // Deliberately does NOT touch `unitPrice` -- `cat.price` is the FACTORY PURCHASE price
    // (price_catalog.product_prices.price), not a selling price; prefilling ราคา/หน่วย from it
    // used to leak that purchase price into the quotation as if it were what to charge the
    // customer (bug, caught in review) -- omitted from the patch entirely so unitPrice is left
    // exactly as the rep already had it.
    //
    // ประเทศต้นทาง + its default lead-time range are filled from the catalog row's own
    // `originCountryCode` (owner feedback F1, 2026-09-10, "autofill as much as you can and if
    // there is anything you can auto calculate fill it") -- ProductPriceDto exposes it as
    // price_catalog.factories.country joined via product_prices.factory_id. Only when the code
    // maps to one of the four ประเทศต้นทาง options this app offers -- originCountryFromCode
    // returns '' otherwise.
    //
    // ⚠️ The rep's OWN choice wins (review finding LOW-6). Autofill means "fill a field the rep
    // has not answered", never "correct" one they have: a rep who deliberately picked ไทย-สต็อก
    // for an Italian collection they are quoting out of local stock had that silently reverted to
    // อิตาลี -- along with the lead time -- by the next catalog pick on the same row. So this
    // reads `item.originCountry` FIRST and only falls back to the catalog. It also means the
    // lead-time default below never fires on a row that already had an origin, since
    // originCountry is then === item.originCountry.
    const originCountry = item.originCountry || originCountryFromCode(cat.originCountryCode) || '';
    // แผ่น/ตร.ม. resolution order (a)/(c)/(d) -- see resolveTileSqmPerPiece's own Javadoc-style
    // comment above for the full rule and why (b) (geometry from explicit width/height) is a
    // deliberate no-op today. `resolvedSqmPerPiece` is `null` for a per_linear_m row EVEN IF
    // `cat.sqmPerPiece` is populated -- that figure is linear metres per piece there, not area
    // (V153's own column comment), so it must never fall into the `?? item.sqmPerPiece` chain as
    // if it were a real catalog answer. `catalogSqmPerPiece`/`catalogPriceUnit` are UI-only
    // provenance (never sent to the server -- see tileInputFromRow's explicit field list below):
    // they are what lets the field distinguish "resolved from catalog" from "the rep overrode it"
    // and what lets it explain a per_linear_m row's blank field instead of just failing validation
    // silently.
    const resolvedSqmPerPiece = resolveTileSqmPerPiece(cat);
    patch({
      catalogPriceId: cat.priceId ?? null,
      productCode: cat.productCode ?? null,
      brand: cat.factoryName ?? cat.factory ?? cat.brand ?? item.brand,
      model: cat.collection ?? cat.productName ?? cat.productCode ?? item.model,
      color: cat.color ?? '',
      texture: cat.surface ?? '',
      sizeText: cat.sizeRaw ?? cat.size ?? '',
      thicknessMm: cat.thicknessMm ?? item.thicknessMm ?? null,
      sqmPerPiece: resolvedSqmPerPiece ?? item.sqmPerPiece ?? null,
      piecesPerSqmDisplay: piecesPerSqmFromSqmPerPiece(resolvedSqmPerPiece ?? item.sqmPerPiece) ?? '',
      // A fresh, real resolution always overwrites a stale "manual" flag from a previous pick --
      // the rep just linked a (possibly new) catalog row and has typed nothing on THIS pick, so
      // the badge must read "from catalog", not "overridden" left over from before. When nothing
      // resolves (per_linear_m, or the catalog row itself has no factor), the previous source is
      // left exactly as it was: a rep's earlier manual entry does not become unexplained just
      // because this pick had nothing to add, and a still-unset field stays unset (d).
      sqmPerPieceSource: resolvedSqmPerPiece != null ? 'catalog' : item.sqmPerPieceSource ?? null,
      catalogSqmPerPiece: resolvedSqmPerPiece,
      catalogPriceUnit: cat.priceUnit ?? null,
      piecesPerBox: cat.pcsPerBox ?? item.piecesPerBox ?? null,
      originCountry,
      ...(originCountry && originCountry !== item.originCountry ? defaultLeadTimeForOrigin(originCountry) : {}),
    });
    onCatalogPicked?.(cat);
    setCatalogResults([]);
    setCatalogOpen(false);
  }

  function onOriginChange(value) {
    const defaults = defaultLeadTimeForOrigin(value);
    patch({ originCountry: value, ...defaults });
  }

  // Groups this row could be moved INTO — every group but its own. Rendered only when there is
  // somewhere to go, so a single-location quotation carries no dead control.
  const moveTargets = locationGroups.filter((group) => group.groupId !== groupId);

  // แผ่น/ตร.ม. status line (owner feedback 2026-09-12) -- one of three mutually exclusive states,
  // each visibly distinct per the owner's ask that an overridden value read differently from a
  // resolved one:
  //   - a per_linear_m catalog row with nothing entered yet: explain WHY it is blank (resolveTile-
  //     SqmPerPiece's rule (c)) rather than just failing validation silently.
  //   - `sqmPerPieceSource === 'catalog'`: this row's current value is still exactly what the last
  //     catalog pick resolved (a) -- info tone, badgeCheck icon, matching the row's own "จาก
  //     catalog" badge.
  //   - `sqmPerPieceSource === 'manual'`: the rep typed over either the resolved value or a blank
  //     field (the owner's "แก้ทับได้") -- warning tone, pencil icon, so it never reads as if the
  //     catalog vouches for a number the rep chose.
  const isLinearMCatalog = Boolean(item.catalogPriceId) && item.catalogPriceUnit === PRICE_UNIT_PER_LINEAR_M;
  const sqmPerPieceStatus = isLinearMCatalog && !(Number(item.sqmPerPiece) > 0)
    ? { tone: 'text-warning', icon: 'info', text: 'สินค้านี้ขายต่อเมตร (per_linear_m) — ตร.ม./แผ่น ในแคตตาล็อกไม่ใช่พื้นที่ กรุณากรอกแผ่น/ตร.ม. เอง' }
    : item.sqmPerPieceSource === 'catalog'
      ? { tone: 'text-info', icon: 'badgeCheck', text: 'คำนวณจาก catalog' }
      : item.sqmPerPieceSource === 'manual'
        ? { tone: 'text-warning', icon: 'pencil', text: 'แก้ไขเอง' }
        : null;

  return (
    <li className="grid gap-3 rounded-md border border-border p-3.5">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-surface-subtle text-2xs font-extrabold text-text-muted">
          {index + 1}
        </span>
        {!readOnly ? (
          // `min-w-0 flex-1` so this column can be squeezed at all; without it the two selects
          // below push the whole row past the viewport on a phone (see their comment).
          <div className="flex min-w-0 flex-1 flex-wrap items-center justify-end gap-1.5">
            {moveTargets.length ? (
              <>
                <label htmlFor={`move-${index}`} className="sr-only">ย้ายไปตำแหน่ง</label>
                {/* A select whose value is always '' — it issues an ACTION, it does not hold
                    state. The row's current group is already shown by the group header it sits
                    under, so re-stating it as a selected option would just be noise.

                    ── Why `w-full min-w-0` below `sm:` and a `max-w` above it ──
                    A <select>'s intrinsic width is its LONGEST OPTION, and these options carry
                    user-typed ตำแหน่งติดตั้ง labels ("ไปยัง ชั้น 1 พื้นห้องนั่งเล่น"), so it is
                    unbounded by construction. `min-w-[9.5rem]` set a FLOOR and never a ceiling —
                    measured, `#dup-N` reached a 318 px min-content, which fixed the group
                    <section>'s min-content at 414 px and blew #main-content out to scrollWidth 451
                    at a 390 px viewport, clipping ฿1,524,369.36 off the right edge. The automatic
                    minimum size of a flex item is its min-content, so the ancestors could not
                    absorb it either — hence `min-w-0` here AND on the two wrappers above. Below
                    `sm:` the two selects stack full-width (a phone has no room for them side by
                    side anyway); from `sm:` up they return to their intrinsic width between a
                    9.5rem floor and a 13rem ceiling, so a long label truncates in the closed
                    control instead of widening the page. */}
                <select
                  id={`move-${index}`}
                  className="h-8 mobile:h-11 w-full min-w-0 py-0 text-2xs sm:w-auto sm:min-w-[9.5rem] sm:max-w-[13rem]"
                  value=""
                  onChange={(e) => { if (e.target.value) onMove?.(e.target.value); }}
                >
                  <option value="">ย้ายไปตำแหน่ง…</option>
                  {moveTargets.map((group) => (
                    <option key={group.groupId} value={group.groupId}>{group.label || UNLABELLED_LOCATION_TEXT}</option>
                  ))}
                </select>

                <label htmlFor={`dup-${index}`} className="sr-only">ทำซ้ำรายการ</label>
                <select
                  id={`dup-${index}`}
                  className="h-8 mobile:h-11 w-full min-w-0 py-0 text-2xs sm:w-auto sm:min-w-[9.5rem] sm:max-w-[13rem]"
                  value=""
                  onChange={(e) => { if (e.target.value) onDuplicate?.(e.target.value); }}
                >
                  <option value="">ทำซ้ำรายการ…</option>
                  <option value={groupId}>ในตำแหน่งนี้</option>
                  {moveTargets.map((group) => (
                    <option key={group.groupId} value={group.groupId}>ไปยัง {group.label || UNLABELLED_LOCATION_TEXT}</option>
                  ))}
                </select>
              </>
            ) : (
              // One location only: there is nowhere to move to and only one place to copy into,
              // so the duplicate degrades to a plain button rather than a one-option dropdown.
              <Button variant="secondary" size="sm" onClick={() => onDuplicate?.(groupId)}>ทำซ้ำรายการ</Button>
            )}
            <Button variant="icon" size="sm" className="mobile:min-h-[44px] mobile:w-11" onClick={onRemove} title="ลบรายการ" aria-label="ลบรายการ">
              <Icon name="close" size={16} />
            </Button>
          </div>
        ) : null}
      </div>

      {/* "ใช้ล่าสุด" — the rep's own last catalog picks, one click each (owner ask 2026-09-10,
          "as little typing as possible"). Same pickCatalog path as the typeahead, so a chip and a
          search result fill exactly the same fields; the chips are per-browser and best-effort
          (see quotationPrefs.js) and simply do not render when there are none. Only offered on an
          untouched row: once a row names a product, replacing it wholesale from a chip is far more
          likely to be a misclick than an intent. */}
      {!readOnly && recentPicks.length > 0 && !item.model ? (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">ใช้ล่าสุด</span>
          {recentPicks.map((cat) => (
            <button
              key={cat.priceId ?? `${cat.productCode}-${cat.collection}`}
              type="button"
              title={`${cat.factoryName ?? cat.factory ?? '-'} · ${cat.color ?? '-'} · ${cat.sizeRaw ?? cat.size ?? '-'}`}
              className="inline-flex min-h-7 max-w-[16rem] items-center gap-1 truncate rounded-full border border-border bg-surface-subtle px-2.5 text-2xs font-bold text-text-muted hover:border-primary hover:text-primary"
              onClick={() => pickCatalog(cat)}
            >
              <Icon name="clock" size={11} />
              <span className="truncate">{cat.collection ?? cat.productName ?? cat.productCode}</span>
            </button>
          ))}
        </div>
      ) : null}

      <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
        <div className="relative col-span-full">
          <FormField label="รุ่น / ค้นหาแคตตาล็อก" htmlFor={`model-${index}`} required error={errors.model}>
            <input
              id={`model-${index}`}
              disabled={readOnly}
              value={item.model ?? ''}
              autoComplete="off"
              onChange={(e) => onCatalogQuery(e.target.value)}
              onFocus={() => setCatalogOpen(catalogResults.length > 0)}
              onBlur={() => setTimeout(() => setCatalogOpen(false), 150)}
            />
          </FormField>
          {catalogOpen && catalogResults.length > 0 ? (
            // `list-none pl-0` + listbox/option roles, for the same two reasons as the customer
            // typeahead in DealCustomerCard: without them this popup renders as a bulleted,
            // 40 px-indented <ul>, and announces as a list of text rather than a set of choices.
            <ul
              id={`catalog-typeahead-${index}`}
              role="listbox"
              aria-label="ผลการค้นหาแคตตาล็อก"
              className="absolute z-10 mt-1 max-h-64 w-full list-none overflow-auto rounded-md border border-border bg-surface pl-0 shadow-[var(--shadow-lg-heavy)]"
            >
              {catalogResults.map((cat) => (
                <li key={cat.priceId ?? `${cat.productCode}-${cat.collection}`} role="presentation">
                  <button
                    type="button"
                    role="option"
                    aria-selected={false}
                    className="block w-full px-3 py-2 text-left text-xs hover:bg-surface-hover"
                    onMouseDown={(e) => { e.preventDefault(); pickCatalog(cat); }}
                  >
                    <strong>{cat.collection ?? cat.productName ?? cat.productCode}</strong>
                    <span className="block text-2xs text-text-muted">
                      {(cat.factoryName ?? cat.factory ?? '-')} · {cat.color ?? '-'} · {cat.sizeRaw ?? cat.size ?? '-'}
                      {cat.price != null ? ` · ${formatMoney(cat.price)}` : ''}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      </div>

      {/* Provenance badge (owner ruling 2026-09-10): reuses PricingRequestCreateModal.jsx's own
          "Catalog #..." badge pattern (text-info, small, non-blocking) so a row that came from
          the catalog says so -- every field it filled stays editable, this is informational, not
          a lock. Cleared automatically the instant รุ่น is retyped (onCatalogQuery nulls
          catalogPriceId, #M9), so it can never lie about a row that no longer matches what was
          picked. */}
      {item.catalogPriceId ? (
        <p role="status" className="m-0 flex items-center gap-1 text-2xs font-bold text-info">
          <Icon name="badgeCheck" size={12} />
          จาก catalog{item.productCode ? ` · ${item.productCode}` : ''}
        </p>
      ) : null}

      <div className="grid grid-cols-4 gap-3 mobile:grid-cols-2">
        <FormField label="ยี่ห้อ" htmlFor={`brand-${index}`}>
          <input id={`brand-${index}`} disabled={readOnly} value={item.brand ?? ''} onChange={(e) => patch({ brand: e.target.value })} />
        </FormField>
        <FormField label="สี" htmlFor={`color-${index}`} required error={errors.color}>
          <input id={`color-${index}`} disabled={readOnly} value={item.color ?? ''} onChange={(e) => patch({ color: e.target.value })} />
        </FormField>
        <FormField label="ผิว" htmlFor={`texture-${index}`} required error={errors.texture}>
          <input id={`texture-${index}`} disabled={readOnly} value={item.texture ?? ''} onChange={(e) => patch({ texture: e.target.value })} />
        </FormField>
        <FormField label="ขนาด (ซม.)" htmlFor={`size-${index}`} hint="เช่น 60x120" required error={errors.sizeText}>
          <input id={`size-${index}`} disabled={readOnly} value={item.sizeText ?? ''} onChange={(e) => patch({ sizeText: e.target.value })} />
        </FormField>
        <FormField label="ความหนา (มม.)" htmlFor={`thickness-${index}`} required error={errors.thicknessMm}>
          <input
            id={`thickness-${index}`} type="number" step="0.1" disabled={readOnly}
            value={item.thicknessMm ?? ''}
            onChange={(e) => patch({ thicknessMm: e.target.value === '' ? null : Number(e.target.value) })}
          />
        </FormField>
        <FormField label="แผ่น/ตร.ม." htmlFor={`sqm-${index}`} required error={errors.sqmPerPiece}>
          {/* Shows/accepts the RECIPROCAL of what is stored (owner feedback 2026-09-12) --
              `item.sqmPerPiece` (ตร.ม./แผ่น) stays the wire field verbatim, converted at this UI
              edge only (see quotationMeta.js's piecesPerSqmFromSqmPerPiece/sqmPerPieceFromPieces-
              PerSqm). `piecesPerSqmDisplay` is the literal text the rep is typing, kept separately
              from the derived number specifically so a partial decimal ("16.") is never clobbered
              back to "16" by a same-render round-trip through the reciprocal -- see those two
              helpers' own comments for why 6dp storage makes the round trip exact once a value IS
              complete. Falls back to deriving from the stored value for a row that has never had
              piecesPerSqmDisplay set (freshly loaded from the server, or a brand-new blank row). */}
          <input
            id={`sqm-${index}`} type="number" step="0.01" disabled={readOnly}
            value={item.piecesPerSqmDisplay ?? piecesPerSqmFromSqmPerPiece(item.sqmPerPiece) ?? ''}
            onChange={(e) => {
              const raw = e.target.value;
              if (raw === '') {
                patch({ piecesPerSqmDisplay: '', sqmPerPiece: null, sqmPerPieceSource: 'manual' });
                return;
              }
              const parsed = Number(raw);
              patch({
                piecesPerSqmDisplay: raw,
                sqmPerPiece: Number.isFinite(parsed) && parsed > 0
                  ? sqmPerPieceFromPiecesPerSqm(parsed)
                  : item.sqmPerPiece,
                // The owner's "แก้ทับได้": ANY direct edit here is an override, whether it starts
                // from a catalog-resolved value, a per_linear_m blank, or an already-manual one.
                sqmPerPieceSource: 'manual',
              });
            }}
          />
          {sqmPerPieceStatus ? (
            <span className={`mt-1 flex items-center gap-1 text-2xs font-bold ${sqmPerPieceStatus.tone}`} data-testid={`sqm-status-${index}`}>
              <Icon name={sqmPerPieceStatus.icon} size={11} />
              {sqmPerPieceStatus.text}
            </span>
          ) : null}
        </FormField>
        <FormField label="แผ่น/กล่อง" htmlFor={`ppb-${index}`} required error={errors.piecesPerBox}>
          <input
            id={`ppb-${index}`} type="number" disabled={readOnly}
            value={item.piecesPerBox ?? ''}
            onChange={(e) => patch({ piecesPerBox: e.target.value === '' ? null : Number(e.target.value) })}
          />
        </FormField>
        <FormField label="รหัสสินค้า" htmlFor={`code-${index}`}>
          <input id={`code-${index}`} disabled={readOnly} value={item.productCode ?? ''} onChange={(e) => patch({ productCode: e.target.value })} />
        </FormField>
      </div>

      <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
        <FormField label="จำนวน" htmlFor={`qty-${index}`} required error={item.quantityMode === 'PIECES' ? errors.piecesInput : errors.areaSqm}>
          <div className="flex gap-2">
            <div className="inline-flex overflow-hidden rounded-md border border-border-input">
              {QUANTITY_MODE_OPTIONS.map((opt) => (
                <button
                  key={opt.code}
                  type="button"
                  disabled={readOnly}
                  aria-pressed={item.quantityMode === opt.code}
                  className={`min-h-[38px] mobile:min-h-[44px] px-3 text-xs font-bold ${item.quantityMode === opt.code ? 'bg-primary text-surface' : 'bg-surface text-icon-muted'}`}
                  onClick={() => patch({ quantityMode: opt.code })}
                >
                  {opt.label}
                </button>
              ))}
            </div>
            <input
              id={`qty-${index}`}
              type="number"
              disabled={readOnly}
              className="flex-1"
              value={(item.quantityMode === 'PIECES' ? item.piecesInput : item.areaSqm) ?? ''}
              onChange={(e) => {
                const value = e.target.value === '' ? '' : Number(e.target.value);
                patch(item.quantityMode === 'PIECES' ? { piecesInput: value } : { areaSqm: value });
              }}
            />
          </div>
        </FormField>

        <FormField label="เผื่อ (wastage)" htmlFor={`waste-${index}`}>
          <div className="flex flex-wrap gap-2">
            {WASTAGE_PERCENT_PRESETS.map((pct) => (
              <button
                key={pct}
                type="button"
                disabled={readOnly}
                aria-pressed={item.wastageMode === 'PERCENT' && !wastageCustomOpen && Number(item.wastageValue) === pct}
                className={`min-h-[38px] mobile:min-h-[44px] rounded-md border px-3 text-xs font-bold ${
                  item.wastageMode === 'PERCENT' && !wastageCustomOpen && Number(item.wastageValue) === pct
                    ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'
                }`}
                onClick={() => { setWastageCustomOpen(false); patch({ wastageMode: 'PERCENT', wastageValue: pct }); }}
              >
                {pct}%
              </button>
            ))}
            <button
              type="button"
              disabled={readOnly}
              aria-pressed={item.wastageMode === 'PIECES'}
              className={`min-h-[38px] mobile:min-h-[44px] rounded-md border px-3 text-xs font-bold ${
                item.wastageMode === 'PIECES' ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'
              }`}
              onClick={() => { setWastageCustomOpen(false); patch({ wastageMode: 'PIECES', wastageValue: item.wastageMode === 'PIECES' ? item.wastageValue : 0 }); }}
            >
              +N แผ่น
            </button>
            {(item.wastageMode === 'PERCENT' && (wastageCustomOpen || !WASTAGE_PERCENT_PRESETS.includes(Number(item.wastageValue))))
            || item.wastageMode === 'PIECES' ? (
              <input
                id={`waste-${index}`}
                type="number"
                disabled={readOnly}
                className="w-20"
                value={item.wastageValue ?? ''}
                onChange={(e) => patch({ wastageValue: e.target.value === '' ? 0 : Number(e.target.value) })}
              />
            ) : null}
            {item.wastageMode === 'PERCENT' ? (
              <button
                type="button"
                disabled={readOnly}
                aria-pressed={wastageCustomOpen || !WASTAGE_PERCENT_PRESETS.includes(Number(item.wastageValue))}
                className="min-h-[38px] mobile:min-h-[44px] rounded-md border border-border bg-surface px-3 text-xs font-bold text-text-muted"
                onClick={() => setWastageCustomOpen(true)}
              >
                กำหนดเอง
              </button>
            ) : null}
          </div>
        </FormField>
      </div>

      <div className="grid grid-cols-4 gap-3 mobile:grid-cols-2">
        {/* v3: the two price fields follow the QUOTATION's price mode, chosen once in the
            "รูปแบบเอกสาร" block — never per row, because every tile row of every one of the owner's
            nine documents shares one mode. Each mode asks for exactly what the rep has in hand:
              NET          ราคา/หน่วย + ส่วนลด %           (unchanged)
              SPECIAL_SQM  ราคาตั้ง/แผ่น + ราคาพิเศษ บาท/ตร.ม. รวม VAT → net per piece shown live
              DIRECT_NET   ราคาสุทธิ/แผ่น + ราคาตั้ง/แผ่น (optional: blank prints "Net")
            The derived net in SPECIAL_SQM is the SERVER's (calculate-line's netUnitPrice), never
            a JS copy of WastageCalculator#netPerPieceFromSpecialSqm: its rounding order (the 2dp
            reciprocal first) is exactly what a copy would get subtly wrong. */}
        {priceMode === 'DIRECT_NET' ? (
          <FormField label="ราคาสุทธิ/แผ่น" htmlFor={`direct-net-${index}`} required error={errors.directNetPrice}>
            <input
              id={`direct-net-${index}`} type="number" step="0.01" disabled={readOnly}
              value={item.directNetPrice ?? ''}
              onChange={(e) => patch({ directNetPrice: e.target.value === '' ? '' : Number(e.target.value) })}
            />
          </FormField>
        ) : null}
        <FormField
          label={priceMode === 'NET' ? 'ราคา/หน่วย' : 'ราคาตั้ง/แผ่น'}
          htmlFor={`price-${index}`}
          required={priceMode !== 'DIRECT_NET'}
          hint={priceMode === 'DIRECT_NET' ? 'เว้นว่าง = ใช้ราคาสุทธิ (พิมพ์ส่วนลดเป็น Net)' : undefined}
          error={errors.unitPrice}
        >
          <input
            id={`price-${index}`} type="number" step="0.01" disabled={readOnly}
            value={item.unitPrice ?? ''}
            onChange={(e) => patch({ unitPrice: e.target.value === '' ? '' : Number(e.target.value) })}
          />
        </FormField>
        {priceMode === 'NET' ? (
          <FormField label="ส่วนลด %" htmlFor={`disc-${index}`}>
            <input
              id={`disc-${index}`} type="number" step="0.01" disabled={readOnly}
              value={item.discountPct ?? 0}
              onChange={(e) => patch({ discountPct: e.target.value === '' ? 0 : Number(e.target.value) })}
            />
          </FormField>
        ) : null}
        {priceMode === 'SPECIAL_SQM' ? (
          <FormField label="ราคาพิเศษ (บาท/ตร.ม. รวม VAT)" htmlFor={`special-${index}`} required error={errors.specialPriceSqm}>
            <input
              id={`special-${index}`} type="number" step="0.01" disabled={readOnly}
              value={item.specialPriceSqm ?? ''}
              onChange={(e) => patch({ specialPriceSqm: e.target.value === '' ? '' : Number(e.target.value) })}
            />
            <span className="mt-1 block text-2xs font-bold text-info" data-testid={`special-net-${index}`}>
              {item.calcPending
                ? 'กำลังคำนวณราคาสุทธิ...'
                : item.netUnitPrice != null && Number(item.specialPriceSqm) > 0
                  ? `= สุทธิ ${formatQuotationMoney(item.netUnitPrice, currency)}/แผ่น (ก่อน VAT)`
                  : 'ระบบคำนวณราคาสุทธิต่อแผ่นให้'}
            </span>
          </FormField>
        ) : null}
        <FormField label="ประเทศต้นทาง" htmlFor={`origin-${index}`}>
          <select id={`origin-${index}`} disabled={readOnly} value={item.originCountry ?? ''} onChange={(e) => onOriginChange(e.target.value)}>
            <option value="">-</option>
            {ORIGIN_COUNTRY_OPTIONS.map((opt) => <option key={opt.code} value={opt.code}>{opt.label}</option>)}
          </select>
        </FormField>
        <FormField label="ระยะเวลานำเข้า (วัน)" htmlFor={`lead-${index}`}>
          <div className="flex items-center gap-1.5">
            <input
              id={`lead-${index}`} type="number" disabled={readOnly} className="w-16"
              value={item.leadTimeMinDays ?? ''}
              onChange={(e) => patch({ leadTimeMinDays: e.target.value === '' ? null : Number(e.target.value) })}
            />
            <span className="text-text-muted">–</span>
            {/* #L1: its own id + label -- FormField only wires the ONE control whose id matches
                its own htmlFor (the min input above), so this one had neither, which is invisible
                to a screen reader. sr-only rather than a second visible "ระยะเวลานำเข้า (วัน)" --
                the range reads as one field visually, min/max just need their own names. */}
            <label htmlFor={`lead-max-${index}`} className="sr-only">ถึง (วัน)</label>
            <input
              id={`lead-max-${index}`} type="number" disabled={readOnly} className="w-16"
              value={item.leadTimeMaxDays ?? ''}
              onChange={(e) => patch({ leadTimeMaxDays: e.target.value === '' ? null : Number(e.target.value) })}
            />
          </div>
        </FormField>
      </div>

      <FormField label="หมายเหตุรายการ" htmlFor={`notes-${index}`}>
        <input id={`notes-${index}`} disabled={readOnly} value={item.itemNotes ?? ''} onChange={(e) => patch({ itemNotes: e.target.value })} />
      </FormField>

      {renderMedia ? renderMedia(item, index) : null}

      {/* Live calculation line -- from calculate-line, debounced by the parent. */}
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-surface-subtle px-3 py-2.5">
        <p className="m-0 min-w-0 flex-1 text-xs text-text-muted">
          {item.calcPending ? 'กำลังคำนวณ...' : (item.calculationLine || 'กรอกจำนวนและเผื่อเพื่อคำนวณ')}
        </p>
        <span className="tabular-nums text-md font-extrabold text-text">{formatQuotationMoney(item.lineAmount, currency)}</span>
      </div>
    </li>
  );
}

/**
 * The wire `ItemInput` for any editor row. `priceMode` is the QUOTATION's, and it decides which of
 * a tile row's two mode-specific prices travel — the row itself keeps BOTH in state, so flipping
 * the mode and back restores what the rep typed.
 *
 * ⚠️ Sending only the current mode's price is load-bearing, not tidiness: `POST calculate-line` has
 * no quotation to read a mode from, so DealQuotationService#inferPriceMode infers it FROM THE ROW —
 * a present specialPriceSqm means SPECIAL_SQM, a present directNetPrice means DIRECT_NET. A stale
 * ราคาพิเศษ left on a row in NET mode would make the live preview price it as ราคาพิเศษ.
 */
export function itemInputFromRow(item, priceMode = 'NET') {
  const type = lineTypeOf(item);
  if (type === LINE_TYPE_PLAIN) {
    return {
      // Stable item ids (V170+ picture storage, being paired on the backend): a row the server
      // already has an id for is sent WITH it, so DealQuotationService#update can upsert this row
      // in place instead of deleting + re-inserting it — which is how a per-item picture (keyed by
      // item id) would otherwise be silently orphaned on every save. `null` on a row never saved.
      id: item.id ?? null,
      lineType: LINE_TYPE_PLAIN,
      locationLabel: item.locationLabel || null,
      description: item.description?.trim() || null,
      quantity: item.quantity === '' || item.quantity == null ? null : Number(item.quantity),
      unit: item.unit || null,
      unitPrice: item.unitPrice === '' || item.unitPrice == null ? null : Number(item.unitPrice),
      discountPct: item.discountPct === '' || item.discountPct == null ? 0 : Number(item.discountPct),
      itemNotes: item.itemNotes || null,
    };
  }
  if (type === LINE_TYPE_ADJUSTMENT) return adjustmentInputFromRow(item);
  const directNet = item.directNetPrice === '' || item.directNetPrice == null ? null : Number(item.directNetPrice);
  const unitPrice = item.unitPrice === '' || item.unitPrice == null ? null : item.unitPrice;
  return {
    // See the PLAIN branch's comment above -- same reason, same contract.
    id: item.id ?? null,
    ...tileInputFromRow(item),
    lineType: LINE_TYPE_TILE,
    // DIRECT_NET: a blank ราคาตั้ง is sent as the net itself — one field typed instead of two,
    // and DealQuotationRenderAdapter#discountLabel then prints "Net" because the two are equal,
    // which is the honest reading of "the rep only has a net price".
    unitPrice: priceMode === 'DIRECT_NET' && unitPrice == null ? directNet : unitPrice,
    // A mode with no percent: SPECIAL_SQM and DIRECT_NET both print พิเศษ/Net, and the server
    // nulls the stored percent in those modes anyway (DealQuotationService#buildTileItem).
    discountPct: priceMode === 'NET' ? (item.discountPct ?? 0) : null,
    specialPriceSqm: priceMode === 'SPECIAL_SQM' && item.specialPriceSqm !== '' && item.specialPriceSqm != null
      ? Number(item.specialPriceSqm) : null,
    directNetPrice: priceMode === 'DIRECT_NET' ? directNet : null,
  };
}

/** A ส่วนลดพิเศษ row's ItemInput — EXACTLY one of adjustmentPct / adjustmentAmount, per
 * DealQuotationService#requirePriceValidForType. No unitPrice: the server derives it (and would
 * ignore one anyway — review fix F2). No locationLabel: the row sorts last and prints no heading. */
export function adjustmentInputFromRow(adjustment) {
  const flat = adjustment.adjustmentKind === 'AMOUNT';
  const number = (value) => (value === '' || value == null ? null : Number(value));
  return {
    // See itemInputFromRow's PLAIN branch -- same stable-id contract.
    id: adjustment.id ?? null,
    lineType: LINE_TYPE_ADJUSTMENT,
    locationLabel: null,
    adjustmentPct: flat ? null : number(adjustment.adjustmentPct),
    adjustmentAmount: flat ? number(adjustment.adjustmentAmount) : null,
    adjustmentDeadline: adjustment.adjustmentDeadline || null,
    // Honoured by the server for a FLAT adjustment only; a percentage one derives its own.
    description: flat ? (adjustment.description?.trim() || null) : null,
  };
}

function tileInputFromRow(item) {
  return {
    locationLabel: item.locationLabel || null,
    catalogPriceId: item.catalogPriceId ?? null,
    productCode: item.productCode || null,
    brand: item.brand || null,
    model: item.model || null,
    color: item.color || null,
    texture: item.texture || null,
    sizeText: item.sizeText || null,
    thicknessMm: item.thicknessMm ?? null,
    sqmPerPiece: item.sqmPerPiece ?? null,
    quantityMode: item.quantityMode ?? 'AREA',
    areaSqm: item.areaSqm === '' ? null : item.areaSqm,
    piecesInput: item.piecesInput === '' ? null : item.piecesInput,
    wastageMode: item.wastageMode ?? 'NONE',
    wastageValue: item.wastageValue ?? 0,
    piecesPerBox: item.piecesPerBox === '' ? null : item.piecesPerBox,
    unitPrice: item.unitPrice === '' ? null : item.unitPrice,
    discountPct: item.discountPct ?? 0,
    originCountry: item.originCountry || null,
    leadTimeMinDays: item.leadTimeMinDays ?? null,
    leadTimeMaxDays: item.leadTimeMaxDays ?? null,
    itemNotes: item.itemNotes || null,
  };
}

/** The editor's own per-row key, distinct from the server `id` a saved row also carries (a row
 * that has never been saved has no server id at all). Exported so QuotationEditorPage's
 * "ทำซ้ำรายการ" can mint one for the copy without duplicating the fallback — and, incidentally,
 * so `crypto`/`Date`/`Math.random` stay out of a component body, where the react-hooks purity rule
 * (correctly) refuses to distinguish an event handler from render. */
export function newItemClientId() {
  return crypto.randomUUID?.() ?? `tmp-${Date.now()}-${Math.random()}`;
}

/** `groupId` is the ตำแหน่งติดตั้ง group the new row belongs to (owner feedback F1) -- a
 * CLIENT-ONLY field. `itemInputFromRow` above picks the wire fields explicitly, so it is dropped
 * on the way out; the group's label is what travels, as this item's `locationLabel`.
 *
 * `defaults` seeds ประเทศต้นทาง (and its lead-time range) from the rep's own last quotation --
 * see quotationPrefs.js. Always overridable, and absent when nothing is stored. */
export function emptyQuotationItem(groupId = null, defaults = null) {
  const originCountry = defaults?.originCountry ?? '';
  return {
    clientId: newItemClientId(),
    groupId,
    lineType: LINE_TYPE_TILE,
    specialPriceSqm: '', directNetPrice: '',
    locationLabel: '', catalogPriceId: null, productCode: '',
    brand: '', model: '', color: '', texture: '', sizeText: '', thicknessMm: null, sqmPerPiece: null,
    // แผ่น/ตร.ม. provenance -- UI-only (see tileInputFromRow's explicit field list; none of these
    // travel to the server). `piecesPerSqmDisplay: null` rather than `''` so the field's own
    // fallback (derive from `sqmPerPiece`) kicks in for a row that has never had the reciprocal
    // typed into it directly -- see the `sqm-${index}` input's own comment in the render below.
    catalogSqmPerPiece: null, catalogPriceUnit: null, sqmPerPieceSource: null, piecesPerSqmDisplay: null,
    quantityMode: 'AREA', areaSqm: '', piecesInput: '',
    wastageMode: 'PERCENT', wastageValue: 0, piecesPerBox: '',
    unitPrice: '', discountPct: 0,
    originCountry, ...defaultLeadTimeForOrigin(originCountry),
    itemNotes: '',
    piecesPerSqm: null, piecesBeforeWastage: null, piecesAfterWastage: null, piecesFinal: null, boxes: null,
    netUnitPrice: null, lineAmount: null, descriptionLine: '', sizeLine: '', calculationLine: '',
    specialPriceLine: null,
    calcPending: false,
  };
}

/** A blank PLAIN row (freight, consumables, cut service, sanitary ware) — v3 S2. Same client-only
 * `clientId`/`groupId` contract as emptyQuotationItem; it lives in a ตำแหน่งติดตั้ง group like a
 * tile does, because a freight line can belong to a floor as easily as to none. */
export function emptyPlainItem(groupId = null) {
  return {
    clientId: newItemClientId(),
    groupId,
    lineType: LINE_TYPE_PLAIN,
    locationLabel: '',
    description: '', quantity: '', unit: '', unitPrice: '', discountPct: 0, itemNotes: '',
    netUnitPrice: null, lineAmount: null, calcPending: false,
  };
}

/** A blank ส่วนลดพิเศษ row — v3 S3. Percent by default (her QN6900704-2 is a percent); the
 * deadline is prefilled with `defaultDeadline` (the caller passes the quotation's ยืนราคา end date)
 * so the normal case is "type 3, done". */
export function emptyAdjustment(defaultDeadline = '') {
  return {
    clientId: newItemClientId(),
    lineType: LINE_TYPE_ADJUSTMENT,
    adjustmentKind: 'PERCENT',
    adjustmentPct: '', adjustmentAmount: '', adjustmentDeadline: defaultDeadline || '', description: '',
  };
}

/**
 * A server `DealQuotationItemDto` → an editor row. The inverse of itemInputFromRow, and the half of
 * the GET→PUT round-trip that has to reconstruct what the DTO does not carry verbatim:
 *   - DIRECT_NET has no column of its own — the typed net IS final_unit_price — so `directNetPrice`
 *     is recovered from `netUnitPrice`, or a PUT of an untouched draft would 400 "ราคาสุทธิ".
 *   - A PLAIN row's rep-typed text comes back as `descriptionLine`.
 *   - An ADJUSTMENT's kind follows which of adjustmentPct / adjustmentAmount is non-null (the DTO
 *     guarantees exactly one — review fix F2).
 */
export function rowFromServerItem(item, priceMode = 'NET') {
  const base = { ...item, clientId: item.id ?? newItemClientId(), calcPending: false };
  const type = lineTypeOf(item);
  if (type === LINE_TYPE_PLAIN) {
    return {
      ...base, lineType: LINE_TYPE_PLAIN, description: item.descriptionLine ?? '',
      quantity: item.quantity ?? '', unit: item.unit ?? '', unitPrice: item.unitPrice ?? '',
      discountPct: item.discountPct ?? 0,
    };
  }
  if (type === LINE_TYPE_ADJUSTMENT) {
    const flat = item.adjustmentPct == null && item.adjustmentAmount != null;
    return {
      ...base, lineType: LINE_TYPE_ADJUSTMENT,
      adjustmentKind: flat ? 'AMOUNT' : 'PERCENT',
      adjustmentPct: item.adjustmentPct ?? '', adjustmentAmount: item.adjustmentAmount ?? '',
      adjustmentDeadline: item.adjustmentDeadline ?? '',
      description: flat ? (item.descriptionLine ?? '') : '',
    };
  }
  return {
    ...base, lineType: LINE_TYPE_TILE,
    specialPriceSqm: item.specialPriceSqm ?? '',
    directNetPrice: priceMode === 'DIRECT_NET' ? (item.netUnitPrice ?? '') : '',
  };
}
