import { useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { formatMoney } from '../../utils/format.js';
import {
  LINE_TYPE_ADJUSTMENT, LINE_TYPE_PLAIN, LINE_TYPE_TILE,
  ORIGIN_COUNTRY_OPTIONS, QUANTITY_MODE_OPTIONS, UNLABELLED_LOCATION_TEXT, WASTAGE_MODE_OPTIONS,
  WASTAGE_PERCENT_PRESETS,
  defaultLeadTimeForOrigin, formatQuotationMoney, lineTypeOf, originCountryFromCode,
  piecesPerSqmFromSqmPerPiece, sqmPerPieceFromPiecesPerSqm, isEnglishPerSqm, perSqmIncVatFromPerPiece,
  sqmPerPieceFromSizeCm, sizeTextDiffersFromCatalogFaceSize,
  roundToFullBoxDisabledReason, roundToFullBoxSummary, isTilePriceChangedFromCeoLocally,
  hasTypedPrice, usesDirectNetPriceSqm, usesListPriceSqm, availablePriceModes,
} from './quotationMeta.js';

// price_unit for a linear-metre trim. Its `sqm_per_piece` is LINEAR METRES per piece, not an area
// (the source sqm_per_box column is mislabelled the same way), so resolveTileSqmPerPiece must never
// read it as one. A named constant so it can't drift from CatalogRepository's literal.
const PRICE_UNIT_PER_LINEAR_M = 'per_linear_m';

/**
 * The pieces-per-ตร.ม. resolution order for a catalog pick. Returns the `sqmPerPiece` (ตร.ม./แผ่น,
 * the wire field) to autofill, or `null` when nothing should be invented and the rep must type it:
 *
 *   a. Catalog-linked, priceUnit != per_linear_m: `cat.sqmPerPiece` straight from the catalogue.
 *      It is authoritative (price_catalog.product_prices.sqm_per_piece); geometry (width x height)
 *      disagrees with it on 8.6% of the real catalog, by up to 14x on trims, so it is never
 *      recomputed even when a width/height is available.
 *   b. Catalogue row with NO `sqmPerPiece`: compute from `widthMm` x `heightMm`, which are always
 *      millimetres in price_catalog.product_prices (no unit inference). Never from the free-text
 *      ขนาด field, which mixes cm and mm in one column. ~250 catalogue rows need this.
 *      MUST mirror DealQuotationService#resolveSqmPerPiece's step 3: if the UI leaves the field
 *      blank where the server would resolve it, frontend validation blocks a row the backend accepts.
 *   c. priceUnit === per_linear_m: `cat.sqmPerPiece` is linear metres per piece (see the constant
 *      above), never computed geometrically either. Returns `null` so the rep enters it, and the
 *      UI says why (see the "ต่อเมตร" hint below).
 *
 * This function returns `null` when none of (a)/(b)/(c) resolve; it never reads the free-text ขนาด
 * (ซม.) field. Its callers (catalogue-pick and the ขนาด onChange handler) go one step further:
 *
 *   d. The ขนาด (ซม.) field has a DECLARED unit (cm), unlike the catalogue's free-text `sizeRaw`,
 *      so a size-based figure is allowed. A per_linear_m row is still excluded, a resolved (a)/(b)
 *      figure is never overwritten by it, and once the rep types over the result it stops
 *      recalculating. See `sqmPerPieceFromSizeCm` in quotationMeta.js for the parser and its sanity
 *      bound. The backend does not parse size at all (see that function's doc for the
 *      WastageCalculator citation).
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
 * The ขนาด (ซม.) text for a catalogue pick, built from the catalogue's OWN millimetres.
 *
 * Do NOT use `cat.sizeRaw` here. It is the raw source string from the factory's price list, kept
 * for provenance, and for some sources it is not a size at all: Equipe's list carries the size
 * inside the product NAME (import profile `size_from: product_name`), so all 2,124 of its
 * production rows have a `size_raw` like `"1,2X20 JOLLY COCO WHITE MATT"`, which reached the
 * printed ใบเสนอราคา.
 *
 * `widthMm`/`heightMm` are always millimetres (same guarantee `resolveTileSqmPerPiece` relies on),
 * so this is a pure /10 into centimetres with no unit inference. Trailing zeros are trimmed so
 * 600x1200 mm reads "60x120", not "60.0x120.0", matching the field's `เช่น 60x120` hint.
 *
 * Falls back to `sizeRaw` only when the catalogue has no dimensions at all (~50 rows): a dirty
 * string the rep can correct beats an empty required field.
 */
export function sizeTextFromCatalog(cat) {
  // 1. The database's display column (`size_cm`), once the serving backend is deployed. One
  //    derivation shared with the printed ใบเสนอราคา, so screen and document never disagree.
  if (cat?.sizeCm) return cat.sizeCm;
  // 2. Compute the identical value client-side. Not dead code: the frontend deploys the moment it
  //    merges while the backend runs a PINNED image, so there is a window where the UI is new and
  //    `sizeCm` is not served yet. Same formula as the generated column.
  const w = Number(cat?.widthMm);
  const h = Number(cat?.heightMm);
  if (Number.isFinite(w) && Number.isFinite(h) && w > 0 && h > 0) {
    const cm = (mm) => String(Number((mm / 10).toFixed(2)));
    return `${cm(w)}x${cm(h)}`;
  }
  // 3. Only when the row carries no dimensions at all (~49 production rows).
  return cat?.sizeRaw || cat?.size || '';
}

/**
 * A number input with a trailing, non-interactive unit label rendered inside the field. Nothing on
 * the Thai SPECIAL_SQM price boxes used to say which was per piece and which per ตร.ม., so a rep
 * typed a per-ตร.ม. figure into the per-piece box. Every Thai tile price box is per ตร.ม. and
 * carries the unit.
 * No shared adornment component exists in components/common/; this mirrors the `relative` +
 * `pointer-events-none absolute` suffix pattern used for the ฿ prefix in TaxAllowanceForm's
 * `MoneyInput` and the search icon in CatalogSearchPage. Forwards every prop it doesn't own
 * (including the `aria-*` attributes FormField clones onto the element whose `id` matches
 * `htmlFor`) onto the real `<input>`, so FormField's aria wiring still reaches the control.
 *
 * `unitInLabel`: for a box whose LABEL already names the unit (every Thai per-ตร.ม. price box:
 * "ราคา/ตร.ม. (รวม VAT)"). The in-field unit is then a convenience and the typed number matters
 * more: below 11rem (176px) of box width the unit steps aside and the input takes its 72px back.
 * Measured: at an 820px window this row's grid columns are 142px, leaving 58px for digits and
 * cutting "1041.11" to "1041."; at 176px ~90px remain beside the unit. It is a CONTAINER query on
 * the box itself, not a `tablet:`/`mobile:` breakpoint, because the same squeeze happens in two
 * unrelated places: the tablet band (4 columns, no sidebar) and the narrow end of the desktop
 * band (sidebar open). Left OFF for เผื่อ's "กรอกเอง" box, whose label names neither % nor แผ่น, so
 * the in-field unit is the only place that distinction lives.
 */
function PriceInputWithSuffix({ id, suffix, className, unitInLabel = false, ...inputProps }) {
  return (
    <div className={unitInLabel ? '@container relative' : 'relative'}>
      <input
        id={id} {...inputProps}
        className={`pr-[4.5rem] ${unitInLabel ? '@max-[11rem]:pr-3 ' : ''}${className ?? ''}`.trim()}
      />
      <span
        className={`pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 whitespace-nowrap text-2xs font-bold text-text-muted${unitInLabel ? ' @max-[11rem]:hidden' : ''}`}
        aria-hidden="true"
      >
        {suffix}
      </span>
    </div>
  );
}

/**
 * One item row of the ใบเสนอราคา editor: catalog typeahead (autofills, everything stays editable),
 * quantity mode toggle, wastage segmented control, and the live calculation line the parent keeps
 * up to date via a 300ms-debounced `calculate-line` call (see QuotationEditorPage.jsx's
 * `updateItem`). This component only renders what it is given and reports plain field patches up.
 *
 * ตำแหน่งติดตั้ง is not a field here: it belongs to the GROUP this row sits in, is typed once in
 * the group's header and stamped onto every item at save time by QuotationEditorPage. The row
 * carries the two controls that move a row between groups, "ย้ายไปตำแหน่ง…" and "ทำซ้ำรายการ",
 * both no-ops the parent performs; this component never owns the item list.
 *
 * `errors` (optional, defaults to `{}`) is `validateQuotationItem(item)`'s return value
 * (quotationMeta.js), computed by the parent and already gated on whether this row should show
 * inline hints yet (QuotationEditorPage passes a non-empty object only once the row is touched),
 * so this component never decides that. `error={errors.field}` on a FormField renders the red
 * hint + aria-invalid; `required` marks the label with `*`.
 */
/**
 * The CEO's ORIGINAL price for this line, formatted per mode: "ราคา CEO: ฿83.00 · ส่วนลด 10%" (NET),
 * "ราคาพิเศษ CEO: ฿1,350.00/ตร.ม." (SPECIAL_SQM), "ราคาสุทธิ CEO: ฿74.70" (DIRECT_NET). Reads the
 * `ceo*` fields the server joins onto a linked item (DealQuotationItemDto#ceoListUnitPrice etc.),
 * never re-derives them. Exported so the approve confirm dialog (QuotationEditorPage.jsx) renders
 * the same text for every priceChangedFromCeo line.
 */
export function ceoOriginalPriceText(priceMode, item, currency) {
  if (priceMode === 'SPECIAL_SQM') {
    return `ราคาพิเศษ CEO: ${formatQuotationMoney(item.ceoSpecialPriceSqm, currency)}/ตร.ม.`;
  }
  if (priceMode === 'DIRECT_NET') {
    return `ราคาสุทธิ CEO: ${formatQuotationMoney(item.ceoDirectNetPrice, currency)}`;
  }
  const discountPct = item.ceoDiscountPct ?? 0;
  return `ราคา CEO: ${formatQuotationMoney(item.ceoListUnitPrice, currency)} · ส่วนลด ${discountPct}%`;
}

export function QuotationItemRow({
  item, index, readOnly, errors = {}, onChange, onRemove,
  groupId = null, locationGroups = [], recentPicks = [], onMove, onDuplicate, onCatalogPicked,
  // The QUOTATION's tile price mode (one per document; see quotationMeta's PRICE_MODE_OPTIONS) and
  // its currency. Defaults keep an older caller unchanged.
  priceMode = 'NET', currency = 'THB',
  // The CEO's ORIGINAL pricing_decision.price_mode (DealQuotationDto#ceoPriceMode, header-level,
  // frozen at create), distinct from `priceMode`, the document's CURRENT mode, which sales can
  // switch after create. ceoOriginalPriceText must format against THIS: a rep who switches
  // SPECIAL_SQM -> NET would otherwise get item.ceoSpecialPriceSqm (null, the CEO's decision was
  // NET) instead of ceoListUnitPrice/ceoDiscountPct. Defaults to `priceMode` so a DEAL_DIRECT
  // caller (no ceoPriceMode; the marker never renders there) is unaffected.
  ceoPriceMode,
  // The document's language: SPECIAL_SQM on English is a USD/ตร.ม. price whose quantity is
  // boxes × ตร.ม./กล่อง, so the row asks for that instead of a list price per piece.
  documentLanguage = 'TH',
  // Extension point for per-item pictures: a render prop `(item, index) => node` slotted under the
  // row's notes so an uploader needs no re-plumbing here. Unused today, renders nothing.
  renderMedia = null,
  // PricingRequestCreateModal's per-line สกุลเงิน + หน่วยราคา controls (sales fixes them on the
  // line; import cannot change them). A render prop `(item, index) => node` so this row stays a
  // shared component without knowing the PCR form's fields.
  renderTerms = null,
  // The PCR item form reuses this SAME row for every field except price/discount (CEO pricing is
  // separate). Hides ราคา/หน่วย, ส่วนลด %, ราคาพิเศษ, ราคาสุทธิ (whichever the price mode would show)
  // and the live ฿-amount footer; the default `false` leaves the direct-deal rendering unchanged.
  hidePricing = false,
  // The PCR form labels this same field (the `brand` column, still auto-filled from the catalog
  // pick) โรงงาน instead of ยี่ห้อ: a label override only, never the field or its storage.
  // Direct-deal keeps ยี่ห้อ via the default.
  brandLabel = 'ยี่ห้อ',
  // ประเทศต้นทาง (and, for "อื่นๆ", the typed country name) is REQUIRED on the PCR form but optional
  // on direct-deal; PricingRequestCreateModal's validateItemFields is the only caller passing
  // `true`. Here it only toggles the `required` marker and the "อื่นๆ" text input; the actual
  // check lives in quotationMeta's validateQuotationItem (requireOriginCountry).
  requireOriginCountry = false,
  // PCR form: ระยะเวลานำเข้า is import's to give, so the form does not show it. Render gate only:
  // onOriginChange still writes the origin's default onto the row so import starts pre-filled.
  // The direct-deal editor never sets this.
  hideLeadTime = false,
  // Direct quotation only: จากสต็อก, this line ships from Thai stock (stockSource 'IN_THAILAND'). The PCR form
  // keeps its own checkbox (renderTerms) and a pricing-request-origin quotation takes it from the request.
  showStockOption = false,
  // A duplicated row has no server id, so the save would be refused as a new row on a
  // PRICING_REQUEST-origin quotation (DealQuotationService#update); the control is hidden instead
  // of left as a dead end. Defaults false, so DEAL_DIRECT is unchanged.
  hideDuplicate = false,
  // Mixed price modes. On a MIXED document the page passes `priceMode` as THIS ROW's mode
  // (quotationMeta#rowPriceMode), so everything below keeps reading "the price mode" as usual, and
  // sets `mixedPriceModes`, which only adds the row's own "วิธีคิดราคา" picker. Choosing there calls
  // `onChangePriceMode(code)`; the PAGE re-prices the row (it owns the carry-over, the dropped
  // preview and the re-preview), so the row does not patch itself.
  mixedPriceModes = false,
  onChangePriceMode = null,
}) {
  const perSqm = isEnglishPerSqm(priceMode, documentLanguage);
  // On a Thai document every tile price box is per ตร.ม. including VAT. See quotationMeta's
  // "ราคา/ตร.ม. รวม VAT" block for the two ways a row carries a price; this component only decides
  // what each box SHOWS:
  //   - a price typed per ตร.ม. shows as typed;
  //   - a price the row holds per แผ่น (saved earlier, set by a คำขอราคา, or carried over by a
  //     price-mode switch) shows as its per-ตร.ม. equivalent: DISPLAY-ONLY, never patched onto
  //     `item` or sent (perSqmIncVatFromPerPiece). The row keeps sending the per-แผ่น price until
  //     the box is retyped.
  const thaiPricing = documentLanguage !== 'EN';
  const listIsPerSqm = usesListPriceSqm(item, documentLanguage);
  const netIsPerSqm = usesDirectNetPriceSqm(item, documentLanguage);
  const listPriceSqmShown = listIsPerSqm
    ? item.listPriceSqm
    : (perSqmIncVatFromPerPiece(item.unitPrice, item.sqmPerPiece) ?? '');
  const netPriceSqmShown = netIsPerSqm
    ? item.directNetPriceSqm
    : (perSqmIncVatFromPerPiece(item.directNetPrice, item.sqmPerPiece) ?? '');
  const hasArea = Number(item.sqmPerPiece) > 0;
  // The line under ราคาตั้ง: what the price comes to per แผ่น, before VAT, which is the figure the
  // document prints. It is the SERVER's (calculate-line's unitPrice), never worked out here: the
  // 2dp pieces-per-ตร.ม. rounding order is what a copy would get subtly wrong.
  // For a price the row holds per แผ่น, the line names THAT figure since the box above is only its
  // per-ตร.ม. equivalent. With no แผ่น/ตร.ม. on the row there is no equivalent, the box is empty,
  // and the line must say the price is still there: an empty required box with nothing under it
  // would read as "no price" for a row that has one and keeps sending it.
  const perPieceHeldNote = (value) => (hasArea
    ? `คิดจากราคา ${formatQuotationMoney(value, currency)}/แผ่น (ก่อน VAT)`
    : `ราคา ${formatQuotationMoney(value, currency)}/แผ่น (ก่อน VAT) · กรอกแผ่น/ตร.ม. เพื่อแสดงเป็นราคาต่อ ตร.ม.`);
  const listPerPieceNote = listIsPerSqm
    ? (item.calcPending ? 'กำลังคำนวณราคาต่อแผ่น...'
      : hasTypedPrice(item.unitPrice) ? `= ${formatQuotationMoney(item.unitPrice, currency)}/แผ่น (ก่อน VAT)`
        : hasArea ? 'ระบบคำนวณราคาต่อแผ่นให้' : 'กรอกแผ่น/ตร.ม. ก่อน ระบบจึงคำนวณราคาต่อแผ่นได้')
    : hasTypedPrice(item.unitPrice) ? perPieceHeldNote(item.unitPrice)
      // ราคาสุทธิ mode's ราคาตั้ง is optional, and its own hint already says what blank means.
      : priceMode === 'DIRECT_NET' ? null : 'ระบบคำนวณราคาต่อแผ่นให้';
  const netPerPieceNote = netIsPerSqm
    ? (item.calcPending ? 'กำลังคำนวณราคาสุทธิ...'
      : item.netUnitPrice != null ? `= สุทธิ ${formatQuotationMoney(item.netUnitPrice, currency)}/แผ่น (ก่อน VAT)`
        : hasArea ? 'ระบบคำนวณราคาสุทธิต่อแผ่นให้' : 'กรอกแผ่น/ตร.ม. ก่อน ระบบจึงคำนวณราคาสุทธิต่อแผ่นได้')
    : hasTypedPrice(item.directNetPrice) ? perPieceHeldNote(item.directNetPrice)
      : 'ระบบคำนวณราคาสุทธิต่อแผ่นให้';
  // "Sell loose pieces": see quotationMeta.js for both helpers' Javadoc. `roundLooseDisabledReason`
  // doubles as the disabled flag (non-null = disabled) and its own hint text, so they can't disagree.
  const roundLooseDisabledReason = roundToFullBoxDisabledReason(item, priceMode, documentLanguage);
  const roundLooseChecked = !roundLooseDisabledReason && item.roundToFullBox === false;
  const roundLooseSummary = roundToFullBoxSummary(item);
  const [catalogResults, setCatalogResults] = useState([]);
  const [catalogOpen, setCatalogOpen] = useState(false);
  // PER-ROW debounce timer (one ref per mounted row), not a module-level singleton: a shared timer
  // let typing in one row's รุ่น field cancel (and starve) another row's queued typeahead.
  const catalogTimer = useRef(null);

  function patch(fields) {
    onChange(fields);
  }

  function debouncedCatalogSearch(q, cb) {
    clearTimeout(catalogTimer.current);
    catalogTimer.current = setTimeout(() => cb(q), 280);
  }

  function onCatalogQuery(value) {
    // Clears ONLY catalogPriceId, not productCode: a typed รุ่น invalidates the catalog LINK (the
    // row no longer "was picked from the dropdown"), but productCode is its own free-text field the
    // user may have edited independently, and wiping it would discard data they never asked to clear.
    //
    // catalogSqmPerPiece/catalogPriceUnit are cleared too: they are this row's provenance snapshot
    // for the แผ่น/ตร.ม. badge, and a provenance claim about a catalog row this line no longer names
    // would be a stale claim like the "จาก catalog" badge. `sqmPerPiece` (the actual value) is left
    // alone: retyping รุ่น should not blank a number the rep may still want, only stop claiming it
    // came from a catalog match.
    patch({ model: value, catalogPriceId: null, catalogSqmPerPiece: null, catalogPriceUnit: null, catalogSizeText: null });
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
    // thicknessMm/pcsPerBox/sqmPerBox are optional fields on ProductPriceDto. Autofills
    // brand/รุ่น/สี/ผิว/ขนาด/ความหนา/ตร.ม./แผ่น/แผ่น-กล่อง/รหัสสินค้า: everything the DTO knows,
    // reusing the mapping PricingRequestCreateModal.jsx's deriveItemFromCatalogProduct uses
    // (collection -> productName -> productCode for รุ่น, factoryName for ยี่ห้อ, color/surface/
    // sizeRaw straight across) so the two surfaces never disagree about what a catalog row means.
    //
    // Deliberately does NOT touch `unitPrice`: `cat.price` is the FACTORY PURCHASE price
    // (price_catalog.product_prices.price), not a selling price, and prefilling ราคา/หน่วย from it
    // leaked the purchase price into the quotation. Omitted from the patch so unitPrice stays as
    // the rep had it.
    //
    // ประเทศต้นทาง + its default lead-time range come from the catalog row's `originCountryCode`
    // (price_catalog.factories.country via product_prices.factory_id), only when the code maps to
    // one of the four ประเทศต้นทาง options this app offers (originCountryFromCode returns ''
    // otherwise).
    //
    // The rep's OWN choice wins: autofill fills a field the rep has not answered, never "corrects"
    // one they have (a rep who picked ไทย-สต็อก for an Italian collection quoted from local stock
    // must not be silently reverted to อิตาลี, with the lead time, by the next pick on the row). So
    // this reads `item.originCountry` FIRST and falls back to the catalog; the lead-time default
    // below therefore never fires on a row that already had an origin.
    const originCountry = item.originCountry || originCountryFromCode(cat.originCountryCode) || '';
    // แผ่น/ตร.ม. resolution order (a)/(c)/(d); see resolveTileSqmPerPiece's doc for the rule and why
    // (b) (geometry from explicit width/height) is a deliberate no-op today. `resolvedSqmPerPiece`
    // is `null` for a per_linear_m row EVEN IF `cat.sqmPerPiece` is populated (linear metres per
    // piece there, not area), so it must never fall into the `?? item.sqmPerPiece` chain as a real
    // catalog answer. `catalogSqmPerPiece`/`catalogPriceUnit` are UI-only provenance (never sent;
    // see tileInputFromRow's explicit field list): they let the field tell "resolved from catalog"
    // from "the rep overrode it" and explain a per_linear_m row's blank field instead of failing
    // validation silently.
    const resolvedSqmPerPiece = resolveTileSqmPerPiece(cat);
    const newSizeText = sizeTextFromCatalog(cat);
    // ขนาด (ซม.) fallback for a pick that resolves nothing (rule (d) in resolveTileSqmPerPiece's
    // doc): never for a per_linear_m row, and never when the rep already overrode the field by
    // hand: a pick with nothing to add must not clobber a manual entry.
    const isPickLinearM = cat?.priceUnit === PRICE_UNIT_PER_LINEAR_M;
    const sizeFallbackSqmPerPiece = resolvedSqmPerPiece == null && !isPickLinearM && item.sqmPerPieceSource !== 'manual'
      ? sqmPerPieceFromSizeCm(newSizeText)
      : null;
    const effectiveSqmPerPiece = resolvedSqmPerPiece ?? sizeFallbackSqmPerPiece ?? item.sqmPerPiece ?? null;
    // The supplier-stated ตร.ม./กล่อง this pick fills, computed ahead of the patch so the field
    // and the roundToFullBox reset use the same resolved value.
    const effectiveSqmPerBox = cat.priceUnit === PRICE_UNIT_PER_LINEAR_M ? null : (cat.sqmPerBox ?? null);
    patch({
      catalogPriceId: cat.priceId ?? null,
      productCode: cat.productCode ?? null,
      brand: cat.factoryName ?? cat.factory ?? cat.brand ?? item.brand,
      model: cat.collection ?? cat.productName ?? cat.productCode ?? item.model,
      color: cat.color ?? '',
      texture: cat.surface ?? '',
      sizeText: newSizeText,
      thicknessMm: cat.thicknessMm ?? item.thicknessMm ?? null,
      sqmPerPiece: effectiveSqmPerPiece,
      piecesPerSqmDisplay: piecesPerSqmFromSqmPerPiece(effectiveSqmPerPiece) ?? '',
      // A fresh, real resolution overwrites a stale "manual" flag from a previous pick: the rep just
      // linked a catalog row and typed nothing on THIS pick, so the badge must read "from catalog".
      // A resolved size-derived fallback reads "from size" (rule (d)). When nothing resolves (per_linear_m,
      // or no factor and no readable size), the previous source is left as it was, so an earlier
      // manual entry stays explained and an unset field stays unset.
      sqmPerPieceSource: resolvedSqmPerPiece != null
        ? 'catalog'
        : sizeFallbackSqmPerPiece != null
          ? 'size'
          : item.sqmPerPieceSource ?? null,
      catalogSqmPerPiece: resolvedSqmPerPiece,
      catalogPriceUnit: cat.priceUnit ?? null,
      // The picked catalogue row's OWN face size (cm), so a later ขนาด (ซม.) edit can tell "the rep
      // retyped the same size in another unit" (matched: keep the catalog-resolved แผ่น/ตร.ม.) from
      // "a genuinely different tile" (recompute; see the ขนาด onChange handler below and
      // DealQuotationLines#sizeLine's REFINEMENT javadoc for the backend half of the rule).
      // Cleared wherever catalogSqmPerPiece is cleared (onCatalogQuery) so it never outlives the
      // catalog link. NEVER sent to the server (not in tileInputFromRow's field list).
      catalogSizeText: newSizeText,
      // Never inherited from a previous pick (same reason as sqmPerBox below): a box count belongs to
      // its own product, and keeping the old one saved 66 แผ่น/กล่อง for a product the catalogue says
      // is 38. A missing figure (684 active catalogue rows, nearly all Daugres/Bode) stays blank.
      piecesPerBox: cat.pcsPerBox ?? null,
      // The supplier-stated ตร.ม./กล่อง, which an English per-sqm row prints its quantity from.
      // NEVER from a per_linear_m row (its sqm_per_box holds LINEAR METRES) and never inherited from
      // a previous pick: a box area belongs to its own product, and a stale one would misprice every
      // box of the new one. A missing figure stays blank.
      sqmPerBox: effectiveSqmPerBox,
      originCountry,
      ...(originCountry && originCountry !== item.originCountry ? defaultLeadTimeForOrigin(originCountry) : {}),
      // A pick that FILLS ตร.ม./กล่อง must reset "ขายแผ่นไม่เต็มกล่อง" like typing the value by hand does
      // (onChange below): a box area forces full-box rounding server-side, so a roundToFullBox=false
      // left ticked from a previous box-area-less row would mismatch the printed summary line.
      ...(effectiveSqmPerBox > 0 && item.roundToFullBox === false ? { roundToFullBox: true } : {}),
    });
    onCatalogPicked?.(cat);
    setCatalogResults([]);
    setCatalogOpen(false);
  }

  function onOriginChange(value) {
    const defaults = defaultLeadTimeForOrigin(value);
    // A typed "อื่นๆ" name belongs to the code selected when it was typed: switching to a DIFFERENT
    // code (including blank) clears it, so a stale name never survives under another country.
    patch({ originCountry: value, originCountryOther: value === 'อื่นๆ' ? item.originCountryOther : null, ...defaults });
  }

  // Groups this row could be moved INTO: every group but its own. Rendered only when there is
  // somewhere to go, so a single-location quotation carries no dead control.
  const moveTargets = locationGroups.filter((group) => group.groupId !== groupId);

  // แผ่น/ตร.ม. status line: one of four mutually exclusive states, each visibly distinct so an
  // overridden value reads differently from a resolved one:
  //   - a per_linear_m catalog row with nothing entered yet: explain WHY it is blank
  //     (resolveTileSqmPerPiece's rule (c)) rather than just failing validation.
  //   - `sqmPerPieceSource === 'catalog'`: still exactly what the last catalog pick resolved
  //     (a)/(b); info tone, badgeCheck icon, matching the row's "จาก catalog" badge.
  //   - `sqmPerPieceSource === 'size'`: computed from ขนาด (ซม.), never the catalogue (rule (d); see
  //     sqmPerPieceFromSizeCm in quotationMeta.js); info tone like 'catalog' (both auto-resolved),
  //     with a calculator icon so the rep can tell the source.
  //   - `sqmPerPieceSource === 'manual'`: the rep typed over the resolved value or a blank field;
  //     warning tone, pencil icon, so it never reads as if the catalog or size vouches for it.
  const isLinearMCatalog = Boolean(item.catalogPriceId) && item.catalogPriceUnit === PRICE_UNIT_PER_LINEAR_M;
  const sqmPerPieceStatus = isLinearMCatalog && !(Number(item.sqmPerPiece) > 0)
    ? { tone: 'text-warning', icon: 'info', text: 'สินค้านี้ขายต่อเมตร (per_linear_m) — ตร.ม./แผ่น ในแคตตาล็อกไม่ใช่พื้นที่ กรุณากรอกแผ่น/ตร.ม. เอง' }
    : item.sqmPerPieceSource === 'catalog'
      ? { tone: 'text-info', icon: 'badgeCheck', text: 'คำนวณจาก catalog' }
      : item.sqmPerPieceSource === 'size'
        ? { tone: 'text-info', icon: 'calculator', text: 'คำนวณจากขนาด' }
        : item.sqmPerPieceSource === 'manual'
          ? { tone: 'text-warning', icon: 'pencil', text: 'แก้ไขเอง' }
          : null;

  return (
    <li className="grid gap-3 rounded-md border border-border bg-surface p-3.5">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-surface-subtle text-2xs font-extrabold text-text-muted">
          {index + 1}
        </span>
        {!readOnly ? (
          // `min-w-0 flex-1` so this column can be squeezed at all; without it the two selects below
          // push the whole row past the viewport on a phone (see their comment).
          // `max-sm:contents` puts its children on the row's own lines there: the number and ✕ share
          // the first, and the two full-width selects follow (`max-sm:order-*`).
          // `max-sm:`, not `mobile:`: it must flip exactly where the selects stop being full width
          // (`sm:`); between 640 and 720px `mobile:` would put ✕ in front of two inline selects.
          <div className="flex min-w-0 flex-1 flex-wrap items-center justify-end gap-1.5 max-sm:contents">
            {moveTargets.length ? (
              <>
                <label htmlFor={`move-${index}`} className="sr-only">ย้ายไปตำแหน่ง</label>
                {/* A select whose value is always '': it issues an ACTION, it does not hold state.
                    The row's current group is already shown by its group header, so a selected
                    option would be noise.

                    Why `w-full min-w-0` below `sm:` and a `max-w` above it: a <select>'s intrinsic
                    width is its LONGEST OPTION, and these options carry user-typed ตำแหน่งติดตั้ง
                    labels, so it is unbounded. A `min-w-[9.5rem]` floor with no ceiling let `#dup-N`
                    reach a 318 px min-content, which fixed the group <section>'s min-content at 414 px
                    and blew #main-content out to scrollWidth 451 at a 390 px viewport, clipping
                    ฿1,524,369.36. A flex item's automatic minimum size is its min-content, so the
                    ancestors could not absorb it: hence `min-w-0` here AND on the two wrappers above.
                    Below `sm:` the two selects stack full-width; from `sm:` up they keep their
                    intrinsic width between a 9.5rem floor and a 13rem ceiling, so a long label
                    truncates in the closed control instead of widening the page. */}
                <select
                  id={`move-${index}`}
                  className="h-8 max-sm:order-2 mobile:h-11 w-full min-w-0 py-0 text-2xs mobile:text-base sm:w-auto sm:min-w-[9.5rem] sm:max-w-[13rem]"
                  value=""
                  onChange={(e) => { if (e.target.value) onMove?.(e.target.value); }}
                >
                  <option value="">ย้ายไปตำแหน่ง…</option>
                  {moveTargets.map((group) => (
                    <option key={group.groupId} value={group.groupId}>{group.label || UNLABELLED_LOCATION_TEXT}</option>
                  ))}
                </select>

                {hideDuplicate ? null : (
                  <>
                    <label htmlFor={`dup-${index}`} className="sr-only">ทำซ้ำรายการ</label>
                    <select
                      id={`dup-${index}`}
                      className="h-8 max-sm:order-2 mobile:h-11 w-full min-w-0 py-0 text-2xs mobile:text-base sm:w-auto sm:min-w-[9.5rem] sm:max-w-[13rem]"
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
                )}
              </>
            ) : hideDuplicate ? null : (
              // One location only: there is nowhere to move to and only one place to copy into, so the duplicate degrades to
              // a plain button rather than a one-option dropdown.
              <Button variant="secondary" size="sm" onClick={() => onDuplicate?.(groupId)}>ทำซ้ำรายการ</Button>
            )}
            {/* No onRemove means nothing to remove (the PCR form's lone row), so no dead ✕. */}
            {onRemove ? (
              <Button variant="icon" size="sm" className="max-sm:order-1 max-sm:ml-auto mobile:min-h-[44px] mobile:w-11" onClick={onRemove} title="ลบรายการ" aria-label="ลบรายการ">
                <Icon name="close" size={16} />
              </Button>
            ) : null}
          </div>
        ) : null}
      </div>

      {/* "ใช้ล่าสุด": the rep's own last catalog picks, one click each. Same pickCatalog path as the
          typeahead, so a chip and a search result fill the same fields; the chips are per-browser
          and best-effort (see quotationPrefs.js) and do not render when there are none. Only offered
          on an untouched row: once a row names a product, replacing it wholesale from a chip is far
          more likely a misclick than an intent. */}
      {!readOnly && recentPicks.length > 0 && !item.model ? (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">ใช้ล่าสุด</span>
          {recentPicks.map((cat) => (
            <button
              key={cat.priceId ?? `${cat.productCode}-${cat.collection}`}
              type="button"
              title={`${cat.factoryName ?? cat.factory ?? '-'} · ${cat.color ?? '-'} · ${sizeTextFromCatalog(cat) || '-'}`}
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
            // `list-none pl-0` + listbox/option roles, as for the customer typeahead in
            // DealCustomerCard: without them this popup renders as a bulleted, 40 px-indented <ul>
            // and announces as a list of text rather than a set of choices.
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
                    className="block w-full border-0 bg-transparent px-3 py-2 text-left text-xs text-text hover:bg-surface-hover"
                    onMouseDown={(e) => { e.preventDefault(); pickCatalog(cat); }}
                  >
                    <strong>{cat.collection ?? cat.productName ?? cat.productCode}</strong>
                    <span className="block text-2xs text-text-muted">
                      {(cat.factoryName ?? cat.factory ?? '-')} · {cat.color ?? '-'} · {sizeTextFromCatalog(cat) || '-'}
                      {cat.price != null ? ` · ${formatMoney(cat.price)}` : ''}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      </div>

      {/* Provenance badge: reuses PricingRequestCreateModal.jsx's "Catalog #..." badge pattern
          (text-info, small, non-blocking) so a row from the catalog says so. Every field it filled
          stays editable; this is informational, not a lock. Cleared the instant รุ่น is retyped
          (onCatalogQuery nulls catalogPriceId), so it never lies about a row that no longer matches. */}
      {item.catalogPriceId ? (
        <p role="status" className="m-0 flex items-center gap-1 text-2xs font-bold text-info">
          <Icon name="badgeCheck" size={12} />
          จาก catalog{item.productCode ? ` · ${item.productCode}` : ''}
        </p>
      ) : null}

      <div className="grid grid-cols-4 gap-3 mobile:grid-cols-1">
        <FormField label={brandLabel} htmlFor={`brand-${index}`}>
          <input id={`brand-${index}`} disabled={readOnly} value={item.brand ?? ''} onChange={(e) => patch({ brand: e.target.value })} />
        </FormField>
        <FormField label="สี" htmlFor={`color-${index}`} required error={errors.color}>
          <input id={`color-${index}`} disabled={readOnly} value={item.color ?? ''} onChange={(e) => patch({ color: e.target.value })} />
        </FormField>
        <FormField label="ผิว" htmlFor={`texture-${index}`} required error={errors.texture}>
          <input id={`texture-${index}`} disabled={readOnly} value={item.texture ?? ''} onChange={(e) => patch({ texture: e.target.value })} />
        </FormField>
        <FormField label="ขนาด (ซม.)" htmlFor={`size-${index}`} hint="เช่น 60x120" required error={errors.sizeText}>
          {/* Size-derived แผ่น/ตร.ม. fallback (rule (d) in resolveTileSqmPerPiece's doc and
              sqmPerPieceFromSizeCm in quotationMeta.js). Applies only when ALL hold:
                - not read-only (a read-only row never patches anything);
                - not a per_linear_m catalogue row (that figure is linear metres, never area);
                - the field is not already resolved from the catalogue ('catalog') or overridden by
                  hand ('manual'): either wins outright over a typed size;
                - NOT a value loaded from a saved draft with no recorded source: `sqmPerPieceSource`
                  is UI-only and never persisted, so a reloaded draft's figure must not be silently
                  overwritten because its provenance did not survive the reload.
              When eligible and the new text parses, recompute in the SAME patch as sizeText so the
              two fields never render one render apart. When eligible but the text no longer parses
              AND the current value came from a size (source 'size'), clear it rather than leave a
              stale figure derived from a size the field no longer shows. */}
          <input
            id={`size-${index}`} disabled={readOnly} value={item.sizeText ?? ''}
            onChange={(e) => {
              const newSizeText = e.target.value;
              // A row whose แผ่น/ตร.ม. came from the catalogue pick (`sqmPerPieceSource === 'catalog'`)
              // must NOT keep that figure once the rep retypes ขนาด to a genuinely DIFFERENT size
              // (a line kept a linked catalogue's 600x600mm 0.36 after being retyped to "30x60",
              // should be 0.18). Recompute only when the new text is CONFIRMED to parse to a
              // DIFFERENT face size than the picked catalogue row (`catalogSizeText`, compared
              // cm-or-mm/order-insensitively like DealQuotationLines#sizeLine's REFINEMENT).
              //
              // Use `sizeTextDiffersFromCatalogFaceSize`, NOT `!sizeTextMatchesCatalogFaceSize`: the
              // negation reads "can't tell" (either side failed to parse as a plain size pair) the
              // same as "confirmed different", which wiped the catalogue value on every unparseable
              // intermediate keystroke ("3", "30x") and on EVERY edit when `catalogSizeText` itself
              // is unparseable (a row with no width/height falls back to a dirty sizeRaw). That is
              // the opposite of the backend's FALLBACK rule, where a blank/unparseable typed size
              // keeps printing the catalogue dims. `sizeTextDiffersFromCatalogFaceSize` is `true`
              // ONLY when both texts parse AND differ; otherwise it falls through to the logic below
              // (for source 'catalog', patch sizeText and keep the catalogue figure). Typing the SAME
              // size in another unit ("600x600" over a picked 60x60cm/600x600mm row) does the same,
              // matching the backend's "still the same tile" rule.
              if (!readOnly && item.sqmPerPieceSource === 'catalog'
                && sizeTextDiffersFromCatalogFaceSize(newSizeText, item.catalogSizeText)) {
                const recomputed = sqmPerPieceFromSizeCm(newSizeText);
                // `recomputed` is `null` when the text fails to parse AND when it parses to an
                // OUT-OF-RANGE area (sqmPerPieceFromSizeCm's 0.001-10 m² bound): typing the catalogue's
                // millimetre figures ("300x600") into this cm field for a 60x60cm tile parses as
                // 300x600 CM (18 m²) and is rejected. Patching `sqmPerPiece: recomputed`
                // unconditionally NULLED a valid catalogue figure on either failure, turning a size
                // typo into a blocking "required" error that refused EVERY save (autosave, บันทึกร่าง,
                // ส่งขออนุมัติ) on the whole document. So replace the figure only when the recompute
                // is valid; otherwise keep the row's existing ตร.ม./แผ่น so the rep can edit it by hand.
                if (recomputed != null) {
                  patch({
                    sizeText: newSizeText,
                    sqmPerPiece: recomputed,
                    piecesPerSqmDisplay: piecesPerSqmFromSqmPerPiece(recomputed) ?? '',
                    sqmPerPieceSource: 'size',
                  });
                } else {
                  patch({ sizeText: newSizeText });
                }
                return;
              }
              const sizeFallbackEligible = !readOnly
                && !isLinearMCatalog
                && item.sqmPerPieceSource !== 'catalog'
                && item.sqmPerPieceSource !== 'manual'
                && !(item.sqmPerPieceSource == null && Number(item.sqmPerPiece) > 0);
              if (!sizeFallbackEligible) {
                patch({ sizeText: newSizeText });
                return;
              }
              const computed = sqmPerPieceFromSizeCm(newSizeText);
              if (computed != null) {
                patch({
                  sizeText: newSizeText,
                  sqmPerPiece: computed,
                  piecesPerSqmDisplay: piecesPerSqmFromSqmPerPiece(computed) ?? '',
                  sqmPerPieceSource: 'size',
                });
              } else if (item.sqmPerPieceSource === 'size') {
                patch({ sizeText: newSizeText, sqmPerPiece: null, piecesPerSqmDisplay: '', sqmPerPieceSource: null });
              } else {
                patch({ sizeText: newSizeText });
              }
            }}
          />
        </FormField>
        <FormField label="ความหนา (มม.)" htmlFor={`thickness-${index}`} required error={errors.thicknessMm}>
          <input
            id={`thickness-${index}`} type="number" step="0.1" disabled={readOnly}
            value={item.thicknessMm ?? ''}
            onChange={(e) => patch({ thicknessMm: e.target.value === '' ? null : Number(e.target.value) })}
          />
        </FormField>
        <FormField label="แผ่น/ตร.ม." htmlFor={`sqm-${index}`} required error={errors.sqmPerPiece}>
          {/* Shows/accepts the RECIPROCAL of what is stored: `item.sqmPerPiece` (ตร.ม./แผ่น) stays
              the wire field verbatim, converted at this UI edge only (quotationMeta.js's
              piecesPerSqmFromSqmPerPiece/sqmPerPieceFromPiecesPerSqm). `piecesPerSqmDisplay` is the
              literal text being typed, kept apart from the derived number so a partial decimal
              ("16.") is not clobbered back to "16" by a same-render round trip through the
              reciprocal (6dp storage makes the round trip exact once a value is complete). Falls back
              to deriving from the stored value for a row that never had piecesPerSqmDisplay set
              (freshly loaded, or a brand-new blank row). */}
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
                // ANY direct edit here is an override, whether it starts from a catalog-resolved
                // value, a per_linear_m blank, or an already-manual one.
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
        <FormField
          label="จำนวน"
          htmlFor={`qty-${index}`}
          required
          error={item.quantityMode === 'PIECES' ? errors.piecesInput : errors.areaSqm}
          // PricingRequestCreateModal-only: a legacy PCR line whose stored basis was PER_BOX/
          // PER_LINEAR_M has no AREA/PIECES equivalent to seed this field from. itemFromExisting sets
          // this CLIENT-ONLY note (never sent; same pattern as clientId/catalogSqmPerPiece) so the rep
          // still sees what quantity was requested instead of an unexplained blank field.
          // Always undefined for the direct-deal form and for any PIECES/AREA-basis PCR row.
          hint={item.legacyQuantityNote || undefined}
        >
          <div className="flex gap-2">
            <div className="inline-flex overflow-hidden rounded-md border border-border-input">
              {QUANTITY_MODE_OPTIONS.map((opt) => (
                <button
                  key={opt.code}
                  type="button"
                  disabled={readOnly}
                  aria-pressed={item.quantityMode === opt.code}
                  className={`min-h-[38px] mobile:min-h-[44px] mobile:min-w-11 border-0 px-3 text-xs font-bold ${item.quantityMode === opt.code ? 'bg-primary text-surface' : 'bg-surface text-icon-muted'}`}
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

        <FormField label="เผื่อ (wastage)" htmlFor={`waste-${index}`} error={errors.wastageValue}>
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap gap-2">
              {/* Mode switch, styled like the จำนวน ตร.ม./แผ่น segmented control above so the two
                  mutually-exclusive-mode controls read as one family. Re-clicking the active mode
                  keeps `wastageValue` (a typed number); switching mode resets it to 0, since a percent
                  and a piece count are never comparable. */}
              <div className="inline-flex overflow-hidden rounded-md border border-border-input">
                {WASTAGE_MODE_OPTIONS.map((opt) => (
                  <button
                    key={opt.code}
                    type="button"
                    disabled={readOnly}
                    aria-pressed={item.wastageMode === opt.code}
                    className={`min-h-[38px] mobile:min-h-[44px] mobile:min-w-11 border-0 px-3 text-xs font-bold ${item.wastageMode === opt.code ? 'bg-primary text-surface' : 'bg-surface text-icon-muted'}`}
                    onClick={() => patch({
                      wastageMode: opt.code,
                      wastageValue: item.wastageMode === opt.code ? item.wastageValue : 0,
                    })}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
              {item.wastageMode === 'PERCENT' ? WASTAGE_PERCENT_PRESETS.map((pct) => (
                <button
                  key={pct}
                  type="button"
                  disabled={readOnly}
                  aria-pressed={Number(item.wastageValue) === pct}
                  className={`min-h-[38px] mobile:min-h-[44px] rounded-md border px-3 text-xs font-bold ${
                    Number(item.wastageValue) === pct
                      ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'
                  }`}
                  onClick={() => patch({ wastageMode: 'PERCENT', wastageValue: pct })}
                >
                  {pct}%
                </button>
              )) : null}
            </div>
            {/* The manual box is always visible in both modes. The trailing unit (% vs แผ่น) is what
                was ambiguous about this single box; PriceInputWithSuffix already renders that
                adornment for the price fields above, so it is reused here. */}
            {/* The width is on the wrapper, so the unit sits inside the box and not at the far
                edge of the column. */}
            <div className="w-32">
              <span className="block text-2xs font-bold text-text-muted">กรอกเอง</span>
              <PriceInputWithSuffix
                id={`waste-${index}`}
                type="number"
                disabled={readOnly}
                suffix={item.wastageMode === 'PIECES' ? 'แผ่น' : '%'}
                value={item.wastageValue ?? ''}
                onChange={(e) => patch({ wastageValue: e.target.value === '' ? 0 : Number(e.target.value) })}
              />
            </div>
          </div>
        </FormField>
      </div>

      {/* "Sell loose pieces": default OFF (round up to a full box). Checking it lets this TILE row
          sell exactly its wastage-adjusted piece count, unrounded, split into full boxes plus a
          loose remainder. Disabled until แผ่น/กล่อง is filled (no box multiple yet) and in English
          per-sqm mode (quantity is boxes × ตร.ม./กล่อง, no loose-pieces term);
          `roundLooseDisabledReason` decides both the disabled state and its hint text. The whole
          label is tappable (≥44px on mobile), like this row's other toggles above. */}
      <div>
        <label
          htmlFor={`round-loose-${index}`}
          className={`flex min-h-[38px] mobile:min-h-[44px] items-start gap-2 rounded-md border px-3 py-2 text-xs font-bold ${
            roundLooseChecked ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text'
          } ${roundLooseDisabledReason ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'}`}
        >
          <input
            id={`round-loose-${index}`}
            type="checkbox"
            className="mt-0.5 size-4 shrink-0"
            disabled={readOnly || !!roundLooseDisabledReason}
            checked={roundLooseChecked}
            onChange={(e) => patch({ roundToFullBox: e.target.checked ? false : true })}
          />
          <span className="min-w-0 flex-1">
            <span className="block">ขายแผ่นไม่เต็มกล่อง</span>
            {roundLooseDisabledReason ? (
              <span className="mt-0.5 block text-2xs font-normal text-text-muted">{roundLooseDisabledReason}</span>
            ) : null}
          </span>
        </label>
        {roundLooseSummary ? (
          <p
            className="mt-1 break-words text-2xs font-bold text-text-muted"
            data-testid={`round-loose-summary-${index}`}
          >
            {roundLooseSummary}
          </p>
        ) : null}
      </div>

      {/* Four columns only when this row has the room (`@min-[45rem]`, a container query on the
          wrapper just below), two otherwise, one on a phone. The four Thai price labels run to 164px
          ("ราคาพิเศษ/ตร.ม. (รวม VAT) *"), so four columns need ~170px each, and the row is narrower
          than that in two places a window-width breakpoint cannot tell apart: the tablet band
          (721–1040px, no sidebar: 142px columns at 820) and the narrow desktop band with the
          sidebar open (133px at 1045, an iPad in landscape), where the price label wrapped beside a
          "ส่วนลด %" that did not. Base is two columns and the query WIDENS it, so `mobile:grid-cols-1`
          only competes with an un-prefixed utility.

          The query container is this wrapper and NOT the <li>: a container is also a
          layout-containment and stacking boundary, and the <li> holds the catalogue dropdown, which
          must stay free to overlap the row beneath it. Nothing in here pops out. */}
      <div className="@container">
        <div className="grid grid-cols-2 gap-3 @min-[45rem]:grid-cols-4 mobile:grid-cols-1">
          {/* Mixed price modes: the row's own "วิธีคิดราคา", first in the price row so it reads as
              what the boxes after it depend on. A <select>, not three buttons: it repeats on every
              row, must fit one grid cell at every width, and is native on a phone. Same three labels
              as the document-level picker (one vocabulary). Never on a single-mode document (a
              per-row choice would contradict the document's own), nor where the row has no prices. */}
          {mixedPriceModes && !hidePricing ? (
            <FormField label="วิธีคิดราคา" htmlFor={`price-mode-${index}`}>
              <select
                id={`price-mode-${index}`}
                value={priceMode}
                disabled={readOnly}
                onChange={(e) => onChangePriceMode?.(e.target.value)}
              >
                {availablePriceModes(documentLanguage).map((opt) => (
                  <option key={opt.code} value={opt.code}>{opt.label}</option>
                ))}
              </select>
            </FormField>
          ) : null}
          {/* The two price fields follow the price mode this row is in: the QUOTATION's, chosen once
              in the "รูปแบบเอกสาร" block (every tile row of the owner's documents shares one mode),
              unless the document is "ผสมหลายวิธี", where it is the row's own, chosen in "วิธีคิดราคา"
              just above. Each mode asks for what the rep has in hand.

              On a THAI document every one of these is typed per ตร.ม. INCLUDING VAT, carries the unit
              inside the field, and shows what it comes to per แผ่น underneath:
                NET          ราคา/ตร.ม. (รวม VAT) + ส่วนลด %
                SPECIAL_SQM  ราคาตั้ง/ตร.ม. (รวม VAT) + ราคาพิเศษ/ตร.ม. (รวม VAT)
                DIRECT_NET   ราคาสุทธิ/ตร.ม. (รวม VAT) + ราคาตั้ง/ตร.ม. (รวม VAT)
                             (ราคาตั้ง optional: blank prints "Net")
              One short label family on purpose: the longer "ราคาพิเศษ (บาท/ตร.ม. รวม VAT)" measured
              191px in this grid's 192px column at a 1280px window and wrapped, dropping its input
              below its neighbour's. The บาท is not lost; it is the unit inside the field.
              On an ENGLISH document they are USD, no VAT:
                NET          ราคา/หน่วย + ส่วนลด %
                SPECIAL_SQM  ราคา (USD/ตร.ม.) + ตร.ม./กล่อง
                DIRECT_NET   ราคาสุทธิ/แผ่น + ราคาตั้ง/แผ่น
              Every per-แผ่น figure shown under a Thai box is the SERVER's (calculate-line), never a JS
              copy of WastageCalculator: its rounding order (the 2dp reciprocal first) is what a copy
              would get subtly wrong.

              `hidePricing` (PricingRequestCreateModal) hides every price input below
              (DIRECT_NET/NET/SPECIAL_SQM/perSqm) without changing the direct-deal quotation's
              behaviour; ประเทศต้นทาง/ระยะเวลานำเข้า further down render either way, since both forms
              treat them the same (optional). */}
          {hidePricing || priceMode !== 'DIRECT_NET' ? null : thaiPricing ? (
            <FormField
              label="ราคาสุทธิ/ตร.ม. (รวม VAT)" htmlFor={`direct-net-${index}`} required
              error={errors.directNetPrice}
            >
              {/* Typing here makes the per-ตร.ม. figure THE net: a per-แผ่น net the row was carrying is
                  dropped with it, so the row never holds two nets that could disagree. */}
              <PriceInputWithSuffix
                id={`direct-net-${index}`} type="number" step="0.01" disabled={readOnly} suffix="บาท/ตร.ม."
                unitInLabel
                aria-describedby={`direct-net-${index}-per-piece`}
                value={netPriceSqmShown}
                onChange={(e) => patch({
                  directNetPriceSqm: e.target.value === '' ? '' : Number(e.target.value), directNetPrice: '',
                })}
              />
              {/* Read out with the box (aria-describedby above): the per-แผ่น net is what the document
                  prints, so it is not left for sighted users only. */}
              <span
                id={`direct-net-${index}-per-piece`} className="mt-1 block text-2xs font-bold text-info"
                data-testid={`direct-net-per-piece-${index}`}
              >
                {netPerPieceNote}
              </span>
            </FormField>
          ) : (
            <FormField label="ราคาสุทธิ/แผ่น" htmlFor={`direct-net-${index}`} required error={errors.directNetPrice}>
              <input
                id={`direct-net-${index}`} type="number" step="0.01" disabled={readOnly}
                value={item.directNetPrice ?? ''}
                onChange={(e) => patch({ directNetPrice: e.target.value === '' ? '' : Number(e.target.value) })}
              />
            </FormField>
          )}
          {hidePricing || perSqm ? null : thaiPricing ? (
            <FormField
              label={priceMode === 'NET' ? 'ราคา/ตร.ม. (รวม VAT)' : 'ราคาตั้ง/ตร.ม. (รวม VAT)'}
              htmlFor={`price-${index}`}
              required={priceMode !== 'DIRECT_NET'}
              // Only while the box is blank, the one time "leave it blank" says anything. Once a list
              // price is in, the line under the box is what it comes to per แผ่น instead.
              hint={priceMode === 'DIRECT_NET' && listPriceSqmShown === ''
                ? 'เว้นว่าง = ใช้ราคาสุทธิ (พิมพ์ส่วนลดเป็น Net)' : undefined}
              error={errors.unitPrice}
            >
              {/* A typed figure is written as `listPriceSqm` and nothing else: the per-แผ่น `unitPrice`
                  is the server's to fill in (QuotationEditorPage#scheduleCalc), and the one already
                  there stays until that answer lands, since blanking it for those few hundred
                  milliseconds would flick the document's "has special pricing" off and reset ยืนราคา.
                  EMPTYING the box clears the price altogether, per-แผ่น figure included, or the box
                  would refill itself with that figure's per-ตร.ม. equivalent. */}
              <PriceInputWithSuffix
                id={`price-${index}`} type="number" step="0.01" disabled={readOnly} suffix="บาท/ตร.ม."
                unitInLabel
                aria-describedby={listPerPieceNote ? `price-${index}-per-piece` : undefined}
                value={listPriceSqmShown}
                onChange={(e) => patch(e.target.value === ''
                  ? { listPriceSqm: '', unitPrice: null }
                  : { listPriceSqm: Number(e.target.value) })}
              />
              {listPerPieceNote ? (
                <span
                  id={`price-${index}-per-piece`} className="mt-1 block text-2xs font-bold text-info"
                  data-testid={`list-per-piece-${index}`}
                >
                  {listPerPieceNote}
                </span>
              ) : null}
            </FormField>
          ) : (
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
          )}
          {!hidePricing && priceMode === 'NET' ? (
            <FormField label="ส่วนลด %" htmlFor={`disc-${index}`}>
              <input
                id={`disc-${index}`} type="number" step="0.01" disabled={readOnly}
                value={item.discountPct ?? ''}
                onChange={(e) => patch({ discountPct: e.target.value === '' ? null : Number(e.target.value) })}
              />
            </FormField>
          ) : null}
          {!hidePricing && priceMode === 'SPECIAL_SQM' && !perSqm ? (
            <FormField label="ราคาพิเศษ/ตร.ม. (รวม VAT)" htmlFor={`special-${index}`} required error={errors.specialPriceSqm}>
              <PriceInputWithSuffix
                id={`special-${index}`} type="number" step="0.01" disabled={readOnly} suffix="บาท/ตร.ม."
                unitInLabel
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
          {hidePricing ? null : perSqm ? (
            <>
              {/* English per-sqm: the USD/ตร.ม. is the printed Unit price AND Net price, no VAT; the
                  printed Qty is boxes × ตร.ม./กล่อง when a box area is given, or pieces × ตร.ม./แผ่น
                  when blank. Both are computed by the server (calculate-line), never here. */}
              <FormField label="ราคา (USD/ตร.ม.)" htmlFor={`special-${index}`} required error={errors.specialPriceSqm}>
                <input
                  id={`special-${index}`} type="number" step="0.01" disabled={readOnly}
                  value={item.specialPriceSqm ?? ''}
                  onChange={(e) => patch({ specialPriceSqm: e.target.value === '' ? '' : Number(e.target.value) })}
                />
                <span className="mt-1 block text-2xs font-bold text-info" data-testid={`special-net-${index}`}>
                  {Number(item.sqmPerBox) > 0
                    ? 'ไม่มี VAT · จำนวนพิมพ์เป็น ตร.ม. ตามกล่อง'
                    : 'ไม่มี VAT · จำนวนพิมพ์เป็น ตร.ม. จากจำนวนแผ่น'}
                </span>
              </FormField>
              {/* ตร.ม./กล่อง is OPTIONAL: blank derives the printed sqm quantity from จำนวนแผ่น ×
                  ตร.ม./แผ่น, like the Thai ราคาพิเศษ mode. The hint explains the fallback. */}
              <FormField
                label="ตร.ม./กล่อง" htmlFor={`sqm-box-${index}`} error={errors.sqmPerBox}
                hint="ตามที่ผู้ผลิตระบุ (จากแคตตาล็อก แก้ได้) · ไม่บังคับ — ถ้าเว้นว่าง จะคำนวณ ตร.ม. จากจำนวนแผ่น × ตร.ม./แผ่น"
              >
                {/* null on clear, never '': the payload builders coerce with `??`, which keeps ''.
                    Typing a value while "ขายแผ่นไม่เต็มกล่อง" is ticked resets that checkbox: a box area
                    forces full-box rounding, so a stored roundToFullBox=false the server would refuse
                    is unreachable (same as the price-mode switch, QuotationEditorPage#applyPriceMode). */}
                <input
                  id={`sqm-box-${index}`} type="number" step="0.000001" min="0" disabled={readOnly}
                  value={item.sqmPerBox ?? ''}
                  onChange={(e) => {
                    const value = e.target.value === '' ? null : Number(e.target.value);
                    patch({
                      sqmPerBox: value,
                      ...(value > 0 && item.roundToFullBox === false ? { roundToFullBox: true } : {}),
                    });
                  }}
                />
              </FormField>
            </>
          ) : null}
          {/* CEO-comparison marker for a PRICING_REQUEST-origin TILE row linked to a decision item.
              `item.ceoNetUnitPrice` (server LEFT JOIN, see DealQuotationRepository#mapItemColumns) is
              non-null exactly on such a row; a DEAL_DIRECT row or an unlinked PRICING_REQUEST row
              reads null and renders nothing. Sales MAY edit these fields (no server-side lock); this
              is informational, not a disabled state. */}
          {!hidePricing && item.ceoNetUnitPrice != null ? (
            <div className="col-span-2 mobile:col-span-1">
              {/* OR the server's flag with a LOCAL recomputation over the row's current (possibly
                  unsaved) values, so the marker flips amber as the rep types a differing price/
                  discount, not only after autosave lands. OR (not replace) keeps the server flag
                  authoritative post-save; see isTilePriceChangedFromCeoLocally. */}
              {item.priceChangedFromCeo || isTilePriceChangedFromCeoLocally(item, priceMode) ? (
                <p className="m-0 flex flex-wrap items-baseline gap-x-2 gap-y-0.5 rounded-md border border-warning-border bg-warning/10 px-3 py-2 text-2xs font-bold text-warning">
                  <span>เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ</span>
                  <span className="font-normal text-text-muted">{ceoOriginalPriceText(ceoPriceMode ?? priceMode, item, currency)}</span>
                </p>
              ) : (
                <p className="m-0 flex items-center gap-1.5 text-2xs font-bold text-text-muted">
                  {/* "lock" would read as a locked price; it is not (sales may edit it freely), so a
                      "check" (nothing to flag) reads correctly. */}
                  <Icon name="check" size={12} />
                  ราคาจาก CEO
                </p>
              )}
            </div>
          ) : null}
          <FormField
            label="ประเทศต้นทาง"
            htmlFor={`origin-${index}`}
            required={requireOriginCountry}
            error={errors.originCountry}
          >
            <select id={`origin-${index}`} disabled={readOnly} value={item.originCountry ?? ''} onChange={(e) => onOriginChange(e.target.value)}>
              <option value="">-</option>
              {ORIGIN_COUNTRY_OPTIONS.map((opt) => <option key={opt.code} value={opt.code}>{opt.label}</option>)}
            </select>
          </FormField>
          {/* "อื่นๆ" (ORIGIN_COUNTRY_OPTIONS' sentinel code, with no default lead time) reveals a typed
              name, required on the PCR form (validateQuotationItem's requireOriginCountry). This box
              must render ONLY when requireOriginCountry is on: sales.quotation_item has no
              origin_country_other column (only sales.pricing_request_item does), and direct-deal's
              `itemInputFromRow` never reads item.originCountryOther, so showing it there let a rep
              type a country name that was silently DROPPED on every save. Cleared (never merely
              hidden) when the rep switches away from อื่นๆ, via onOriginChange's defaults, so a stale
              name never lingers under another country code. */}
          {item.originCountry === 'อื่นๆ' && requireOriginCountry ? (
            <FormField
              label="ระบุประเทศต้นทาง"
              htmlFor={`origin-other-${index}`}
              required={requireOriginCountry}
              error={errors.originCountryOther}
            >
              <input
                id={`origin-other-${index}`}
                disabled={readOnly}
                value={item.originCountryOther ?? ''}
                onChange={(e) => patch({ originCountryOther: e.target.value })}
              />
            </FormField>
          ) : null}
          {/* The CHECK is submit-only (QuotationEditorPage's `submitItemErrorsByRow` requires a lead
              time; `itemErrorsByRow`, which gates saving a draft, does not), but the HINT is not
              submit-gated: `errors.leadTimeMinDays` arrives as soon as this row is `touched`
              (QuotationEditorPage.jsx's touchedRowIds, set on load for an existing draft or on first
              edit for a new row), like every other field's error here. */}
          {showStockOption ? (
            <label className="flex items-center gap-2 text-sm font-bold text-text-secondary">
              <input
                type="checkbox" disabled={readOnly} checked={item.stockSource === 'IN_THAILAND'}
                onChange={(e) => patch(e.target.checked
                  ? { stockSource: 'IN_THAILAND', leadTimeMinDays: null, leadTimeMaxDays: null }
                  : { stockSource: null, ...defaultLeadTimeForOrigin(item.originCountry) })}
              />
              จากสต็อก
            </label>
          ) : null}
          {hideLeadTime || (showStockOption && item.stockSource === 'IN_THAILAND') ? null : (
          <FormField label="ระยะเวลานำเข้า (วัน)" htmlFor={`lead-${index}`} error={errors.leadTimeMinDays}>
            <div className="flex items-center gap-1.5">
              <input
                id={`lead-${index}`} type="number" disabled={readOnly} className="w-16"
                value={item.leadTimeMinDays ?? ''}
                onChange={(e) => patch({ leadTimeMinDays: e.target.value === '' ? null : Number(e.target.value) })}
              />
              <span className="text-text-muted">–</span>
              {/* Its own id + label: FormField only wires the ONE control whose id matches its htmlFor
                  (the min input above), so this one was invisible to a screen reader. sr-only rather
                  than a second visible "ระยะเวลานำเข้า (วัน)": the range reads as one field visually,
                  min/max just need their own names. */}
              <label htmlFor={`lead-max-${index}`} className="sr-only">ถึง (วัน)</label>
              <input
                id={`lead-max-${index}`} type="number" disabled={readOnly} className="w-16"
                value={item.leadTimeMaxDays ?? ''}
                onChange={(e) => patch({ leadTimeMaxDays: e.target.value === '' ? null : Number(e.target.value) })}
              />
            </div>
          </FormField>
          )}
        </div>
      </div>

      <FormField label="หมายเหตุรายการ" htmlFor={`notes-${index}`}>
        <input id={`notes-${index}`} disabled={readOnly} value={item.itemNotes ?? ''} onChange={(e) => patch({ itemNotes: e.target.value })} />
      </FormField>

      {renderMedia ? renderMedia(item, index) : null}
      {renderTerms ? renderTerms(item, index) : null}

      {/* Live calculation line from calculate-line, debounced by the parent. Hidden entirely under
          hidePricing: the PCR form has no calculate-line preview (that endpoint requires a positive
          unitPrice even on its lenient preview path; see PricingRequestCreateModal.jsx's note), so
          the only thing to show would be a permanently-zero ฿ amount that reads as a real number
          rather than "not applicable". */}
      {hidePricing ? null : (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-surface-subtle px-3 py-2.5 max-sm:flex-col max-sm:items-stretch">
          <p className="m-0 min-w-0 flex-1 text-xs text-text-muted">
            {item.calcPending ? 'กำลังคำนวณ...' : (item.calculationLine || 'กรอกจำนวนและเผื่อเพื่อคำนวณ')}
          </p>
          <span className="tabular-nums text-md font-extrabold text-text max-sm:text-right">{formatQuotationMoney(item.lineAmount, currency)}</span>
        </div>
      )}
    </li>
  );
}

/**
 * The wire `ItemInput` for any editor row. `priceMode` is the QUOTATION's and decides which of a
 * tile row's two mode-specific prices travel; the row keeps BOTH in state, so flipping the mode and
 * back restores what the rep typed.
 *
 * Sending only the current mode's price is load-bearing: `POST calculate-line` has no quotation to
 * read a mode from, so DealQuotationService#inferPriceMode infers it FROM THE ROW (a present
 * specialPriceSqm means SPECIAL_SQM, a present directNetPrice/directNetPriceSqm means DIRECT_NET).
 * A stale ราคาพิเศษ left on a NET row would make the live preview price it as ราคาพิเศษ.
 *
 * On a THAI document each of a tile row's two prices travels in the unit the row holds it in (see
 * quotationMeta's "ราคา/ตร.ม. รวม VAT" block):
 *   - typed per ตร.ม. -> `listPriceSqm` / `directNetPriceSqm`, with the per-แผ่น counterpart sent as
 *     null. The row's `unitPrice` is then only the server's last ANSWER and is never sent back (the
 *     server would ignore it, since a per-ตร.ม. price replaces it);
 *   - still per แผ่น (a row nobody has retyped) -> `unitPrice` / `directNetPrice`, so the price
 *     cannot move.
 * An English document sends neither per-ตร.ม. field, whatever the row carries: the server refuses a
 * VAT-inclusive price there.
 *
 * On a MIXED document the caller passes THIS ROW's mode as `priceMode` and `{ mixed: true }`, and a
 * tile row then also STATES that mode on the wire (`priceMode`): the server requires it there, and
 * calculate-line uses it instead of guessing from the fields. On a single-mode document no mode is
 * sent, even if the row object carries one.
 */
export function itemInputFromRow(item, priceMode = 'NET', documentLanguage = 'TH', { mixed = false } = {}) {
  const type = lineTypeOf(item);
  if (type === LINE_TYPE_PLAIN) {
    return {
      // Stable item ids: a row the server already has an id for is sent WITH it, so
      // DealQuotationService#update upserts it in place instead of delete + re-insert, which would
      // orphan a per-item picture (keyed by item id) on every save. `null` on a never-saved row.
      id: item.id ?? null,
      lineType: LINE_TYPE_PLAIN,
      locationLabel: item.locationLabel || null,
      description: item.description?.trim() || null,
      quantity: item.quantity === '' || item.quantity == null ? null : Number(item.quantity),
      unit: item.unit || null,
      unitPrice: item.unitPrice === '' || item.unitPrice == null ? null : Number(item.unitPrice),
      discountPct: item.discountPct === '' || item.discountPct == null ? 0 : Number(item.discountPct),
      // A PLAIN row (สินค้า/บริการอื่น, e.g. sanitaryware sold on ชุด) may carry an OPTIONAL import
      // lead time (a reference quotation prints "ระยะเวลานำเข้า 75-90 วัน" against such a row).
      // DealQuotationService#buildPlainItem already forwards both fields (the DTO columns are shared
      // with the TILE row). Null when blank, like a TILE row's leadTimeMinDays/Max below; NEVER
      // required here (DealQuotationService#requireEveryTileItemHasALeadTime only gates TILE rows).
      leadTimeMinDays: item.leadTimeMinDays ?? null,
      leadTimeMaxDays: item.leadTimeMaxDays ?? null,
      itemNotes: item.itemNotes || null,
    };
  }
  if (type === LINE_TYPE_ADJUSTMENT) return adjustmentInputFromRow(item);
  const directNet = hasTypedPrice(item.directNetPrice) ? Number(item.directNetPrice) : null;
  const unitPrice = hasTypedPrice(item.unitPrice) ? item.unitPrice : null;
  // Which unit each of the two prices is in. Both null on an English document.
  const listSqm = usesListPriceSqm(item, documentLanguage) ? Number(item.listPriceSqm) : null;
  const netSqm = priceMode === 'DIRECT_NET' && usesDirectNetPriceSqm(item, documentLanguage)
    ? Number(item.directNetPriceSqm) : null;
  // DIRECT_NET: a blank ราคาตั้ง is sent as the net itself (one field typed instead of two), and
  // DealQuotationRenderAdapter#discountLabel then prints "Net" because the two are equal. In
  // whichever unit the net is in: a per-ตร.ม. net stands in as a per-ตร.ม. list, so both derive to
  // the same per-แผ่น figure.
  const netStandsInForList = priceMode === 'DIRECT_NET' && listSqm == null && unitPrice == null;
  const wireListSqm = listSqm ?? (netStandsInForList ? netSqm : null);
  return {
    // Same reason and contract as the PLAIN branch above.
    id: item.id ?? null,
    ...tileInputFromRow(item),
    lineType: LINE_TYPE_TILE,
    // English per-sqm: the USD/ตร.ม. IS the unit price (the server substitutes it anyway).
    // Otherwise null whenever the list price travels per ตร.ม. (see this function's Javadoc).
    unitPrice: isEnglishPerSqm(priceMode, documentLanguage)
      ? (hasTypedPrice(item.specialPriceSqm) ? Number(item.specialPriceSqm) : null)
      : wireListSqm != null ? null
        : netStandsInForList ? directNet : unitPrice,
    listPriceSqm: isEnglishPerSqm(priceMode, documentLanguage) ? null : wireListSqm,
    // A mode with no percent: SPECIAL_SQM and DIRECT_NET both print พิเศษ/Net, and the server nulls the stored
    // percent in those modes anyway (DealQuotationService#buildTileItem).
    discountPct: priceMode === 'NET' ? (item.discountPct ?? 0) : null,
    specialPriceSqm: priceMode === 'SPECIAL_SQM' && hasTypedPrice(item.specialPriceSqm)
      ? Number(item.specialPriceSqm) : null,
    directNetPrice: priceMode === 'DIRECT_NET' && netSqm == null ? directNet : null,
    directNetPriceSqm: netSqm,
    // English per-sqm WITH a box area cannot express a loose-piece quantity in square metres (its
    // quantity is boxes × sqmPerBox, with no remainder term), and DealQuotationService#buildTileItem's
    // hasBoxArea branch refuses roundToFullBox=false outright. Forced true ONLY when a box area is
    // present, whatever the row's state holds, so a row that had loose pieces under NET/TH and then
    // had its DOCUMENT switched to English per-sqm WITH a box area can't smuggle a false through and
    // 400 at save. The checkbox is also disabled there (roundToFullBoxDisabledReason), but this is
    // the authoritative guard. WITHOUT a box area, roundToFullBox is honoured normally.
    roundToFullBox: isEnglishPerSqm(priceMode, documentLanguage) && Number(item.sqmPerBox) > 0
      ? true
      : item.roundToFullBox !== false,
    // See this function's Javadoc. Absent, not null, on a single-mode document.
    ...(mixed ? { priceMode } : {}),
  };
}

/** A ส่วนลดพิเศษ row's ItemInput: EXACTLY one of adjustmentPct / adjustmentAmount, per
 * DealQuotationService#requirePriceValidForType. No unitPrice: the server derives it (and would
 * ignore one anyway). No locationLabel: the row sorts last and prints no heading. */
export function adjustmentInputFromRow(adjustment) {
  const flat = adjustment.adjustmentKind === 'AMOUNT';
  const number = (value) => (value === '' || value == null ? null : Number(value));
  return {
    // Same stable-id contract as itemInputFromRow's PLAIN branch.
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
    sqmPerBox: item.sqmPerBox === '' || item.sqmPerBox == null ? null : Number(item.sqmPerBox),
    // "Sell loose pieces": sent as an explicit boolean (never '' or null) so a PUT round trip of an
    // unmodified row is byte-identical to what GET returned. The English per-sqm override lives in
    // itemInputFromRow, not here, because that decision needs priceMode/documentLanguage, which
    // this helper does not receive.
    roundToFullBox: item.roundToFullBox !== false,
    unitPrice: item.unitPrice === '' ? null : item.unitPrice,
    discountPct: item.discountPct ?? 0,
    originCountry: item.originCountry || null,
    leadTimeMinDays: item.leadTimeMinDays ?? null,
    leadTimeMaxDays: item.leadTimeMaxDays ?? null,
    itemNotes: item.itemNotes || null,
    // Only IN_THAILAND is accepted on a direct line: IN_TRANSIT (a pricing-request row's value) would 400.
    stockSource: item.stockSource === 'IN_THAILAND' ? 'IN_THAILAND' : null,
  };
}

/** The editor's own per-row key, distinct from the server `id` a saved row also carries (a row that has never
 * been saved has no server id at all). */
export function newItemClientId() {
  return crypto.randomUUID?.() ?? `tmp-${Date.now()}-${Math.random()}`;
}

/** `groupId` is the ตำแหน่งติดตั้ง group the new row belongs to: a CLIENT-ONLY field.
 * `itemInputFromRow` picks the wire fields explicitly, so it is dropped on the way out; the group's
 * label travels as this item's `locationLabel`.
 *
 * `defaults` seeds ประเทศต้นทาง (and its lead-time range) from the rep's own last quotation (see
 * quotationPrefs.js). Always overridable, and absent when nothing is stored. */
export function emptyQuotationItem(groupId = null, defaults = null) {
  const originCountry = defaults?.originCountry ?? '';
  return {
    clientId: newItemClientId(),
    groupId,
    lineType: LINE_TYPE_TILE,
    specialPriceSqm: '', directNetPrice: '',
    // The two prices a Thai row is typed in, per ตร.ม. including VAT. Blank until typed; see
    // quotationMeta's "ราคา/ตร.ม. รวม VAT" block.
    listPriceSqm: '', directNetPriceSqm: '',
    locationLabel: '', catalogPriceId: null, productCode: '',
    brand: '', model: '', color: '', texture: '', sizeText: '', thicknessMm: null, sqmPerPiece: null,
    sqmPerBox: null,
    // แผ่น/ตร.ม. provenance: UI-only (none of these travel; see tileInputFromRow's field list).
    // `piecesPerSqmDisplay: null` rather than `''` so the field's fallback (derive from
    // `sqmPerPiece`) kicks in for a row whose reciprocal was never typed directly (see the
    // `sqm-${index}` input's comment in the render below).
    catalogSqmPerPiece: null, catalogPriceUnit: null, catalogSizeText: null, sqmPerPieceSource: null, piecesPerSqmDisplay: null,
    quantityMode: 'AREA', areaSqm: '', piecesInput: '',
    wastageMode: 'PERCENT', wastageValue: 0, piecesPerBox: '',
    // "Sell loose pieces": default OFF, so every new row rounds up to a full box unless the rep opts out.
    roundToFullBox: true,
    unitPrice: '', discountPct: null,
    originCountry, ...defaultLeadTimeForOrigin(originCountry),
    itemNotes: '',
    piecesPerSqm: null, piecesBeforeWastage: null, piecesAfterWastage: null, piecesFinal: null, boxes: null,
    netUnitPrice: null, lineAmount: null, descriptionLine: '', sizeLine: '', calculationLine: '',
    specialPriceLine: null,
    calcPending: false,
  };
}

/** A blank PLAIN row (freight, consumables, cut service, sanitary ware). Same client-only
 * `clientId`/`groupId` contract as emptyQuotationItem; it lives in a ตำแหน่งติดตั้ง group like a
 * tile does, because a freight line can belong to a floor as easily as to none. */
export function emptyPlainItem(groupId = null) {
  return {
    clientId: newItemClientId(),
    groupId,
    lineType: LINE_TYPE_PLAIN,
    locationLabel: '',
    description: '', quantity: '', unit: '', unitPrice: '', discountPct: null, itemNotes: '',
    // Optional import lead time (see itemInputFromRow's PLAIN branch). `null`, not '', matching
    // emptyQuotationItem's tile defaults (defaultLeadTimeForOrigin returns null/null for a blank origin).
    leadTimeMinDays: null, leadTimeMaxDays: null,
    netUnitPrice: null, lineAmount: null, calcPending: false,
  };
}

/** A blank ส่วนลดพิเศษ row. Percent by default; the deadline is prefilled with `defaultDeadline`
 * (the caller passes the quotation's ยืนราคา end date) so the normal case is "type 3, done". */
export function emptyAdjustment(defaultDeadline = '') {
  return {
    clientId: newItemClientId(),
    lineType: LINE_TYPE_ADJUSTMENT,
    adjustmentKind: 'PERCENT',
    adjustmentPct: '', adjustmentAmount: '', adjustmentDeadline: defaultDeadline || '', description: '',
  };
}

/**
 * A server `DealQuotationItemDto` -> an editor row. The inverse of itemInputFromRow, and the half of
 * the GET->PUT round trip that reconstructs what the DTO does not carry verbatim:
 *   - DIRECT_NET has no column of its own (the typed net IS final_unit_price), so `directNetPrice`
 *     is recovered from `netUnitPrice`, or a PUT of an untouched draft would 400 "ราคาสุทธิ".
 *   - A PLAIN row's rep-typed text comes back as `descriptionLine`.
 *   - An ADJUSTMENT's kind follows which of adjustmentPct / adjustmentAmount is non-null (the DTO
 *     guarantees exactly one).
 *   - A price typed per ตร.ม. comes back VERBATIM (`listPriceSqm` / `directNetPriceSqm`; the server
 *     stores what was typed, since the per-แผ่น figure cannot be converted back), and is null on a
 *     row priced per แผ่น. A net that came back per ตร.ม. is NOT also recovered per แผ่น from
 *     `netUnitPrice`: the row would then hold the same net twice.
 */
export function rowFromServerItem(item, priceMode = 'NET') {
  const base = { ...item, clientId: item.id ?? newItemClientId(), calcPending: false };
  const type = lineTypeOf(item);
  if (type === LINE_TYPE_PLAIN) {
    return {
      ...base, lineType: LINE_TYPE_PLAIN, description: item.descriptionLine ?? '',
      quantity: item.quantity ?? '', unit: item.unit ?? '', unitPrice: item.unitPrice ?? '',
      discountPct: item.discountPct ?? null,
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
  const directNetPriceSqm = priceMode === 'DIRECT_NET' ? (item.directNetPriceSqm ?? '') : '';
  return {
    ...base, lineType: LINE_TYPE_TILE,
    specialPriceSqm: item.specialPriceSqm ?? '',
    listPriceSqm: item.listPriceSqm ?? '',
    directNetPriceSqm,
    directNetPrice: priceMode === 'DIRECT_NET' && directNetPriceSqm === '' ? (item.netUnitPrice ?? '') : '',
  };
}
