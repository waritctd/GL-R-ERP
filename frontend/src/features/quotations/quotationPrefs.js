// Per-rep, per-browser conveniences for the ใบเสนอราคา editor (owner ask 2026-09-10, "as little
// typing as possible"). NOTHING here is data: every value is a DEFAULT the rep can immediately
// overwrite, and losing all of it costs one extra dropdown pick. That is deliberate, and it is
// what makes localStorage the right home — these are per-device conveniences, not state anyone
// else needs to see, and they must never become a source of truth the server disagrees with.
//
// ⚠️ EVERY read and write is wrapped. `localStorage` is not merely "sometimes empty": the
// ACCESSOR ITSELF THROWS in a Safari private window and under a browser set to block site data,
// so `localStorage.getItem(...)` inside a render path takes the whole editor down rather than
// degrading. A throw here is indistinguishable from "nothing saved yet" by design — the caller
// gets null/[] and renders exactly as it would for a rep on their first visit.
//
// Keys are namespaced AND suffixed with the login-account id, because a shared sales machine is
// the normal case in this office: two reps on one browser must not inherit each other's terms.

const DEFAULTS_KEY = 'glr.quotation.defaults.v1';
const RECENT_PRODUCTS_KEY = 'glr.quotation.recentProducts.v1';

/** How many "ใช้ล่าสุด" catalog chips the item row offers. 8 per the brief — one row's worth on a
 * 1366px screen, and few enough that scanning them is faster than typing the model name. */
export const RECENT_PRODUCT_LIMIT = 8;

function storageKey(base, userId) {
  return `${base}:${userId ?? 'anon'}`;
}

function readJson(key) {
  try {
    const raw = globalThis.localStorage?.getItem(key);
    if (!raw) return null;
    return JSON.parse(raw);
  } catch {
    // Private window / blocked site data / corrupt JSON — all the same answer: no preference.
    return null;
  }
}

function writeJson(key, value) {
  try {
    // NOT `globalThis.localStorage?.setItem(...)` followed by `return true` -- optional chaining
    // makes a MISSING storage silently succeed, so this would report "saved" for a write that
    // never happened. The environment is checked, then the call is made unguarded so a real
    // failure lands in the catch.
    const storage = globalThis.localStorage;
    if (!storage) return false;
    storage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    // Quota exceeded or storage blocked. A default that failed to save is not an error the rep
    // needs to hear about — the next quotation simply starts from the app defaults.
    return false;
  }
}

// ── Last-used terms ──────────────────────────────────────────────────────────────────────────
// Only the four terms fields a rep repeats deal after deal, plus the ประเทศต้นทาง a new ITEM row
// starts on. Deliberately NOT remembered: วันที่ (always today), หมายเหตุ (customer-specific),
// ฝ่าย/หน่วยงาน (deal-specific), and anything money-shaped.

const DEFAULT_TERM_FIELDS = ['depositPercent', 'remainderMode', 'creditDays', 'validityDays', 'originCountry'];

/** `null` when nothing is stored (or storage threw). Never throws. */
export function readQuotationDefaults(userId) {
  const stored = readJson(storageKey(DEFAULTS_KEY, userId));
  if (!stored || typeof stored !== 'object') return null;
  const picked = {};
  for (const field of DEFAULT_TERM_FIELDS) {
    if (stored[field] !== undefined && stored[field] !== null) picked[field] = stored[field];
  }
  return Object.keys(picked).length ? picked : null;
}

/** Called after a successful save — the rep's own last choices become the next NEW quotation's
 * starting point. Returns false if storage refused, so a test can assert the throw path. */
export function writeQuotationDefaults(userId, values) {
  const picked = {};
  for (const field of DEFAULT_TERM_FIELDS) {
    const value = values?.[field];
    if (value !== undefined && value !== null && value !== '') picked[field] = value;
  }
  if (!Object.keys(picked).length) return false;
  return writeJson(storageKey(DEFAULTS_KEY, userId), picked);
}

// ── Recent catalog picks ─────────────────────────────────────────────────────────────────────
// The rep's own last RECENT_PRODUCT_LIMIT catalog rows, most recent first. Stored as the SUBSET of
// ProductPriceDto that QuotationItemRow.pickCatalog actually consumes plus what the chip shows —
// never the whole DTO, so a catalog schema change cannot bloat or poison this cache. A stale entry
// is harmless: picking one only ever pre-fills fields the rep can edit, exactly like the typeahead.

const RECENT_PRODUCT_FIELDS = [
  'priceId', 'productCode', 'factoryName', 'factory', 'brand', 'collection', 'productName',
  'color', 'surface', 'sizeRaw', 'size', 'thicknessMm', 'sqmPerPiece', 'pcsPerBox',
  'originCountryCode',
];

/** Always an array — `[]` when nothing is stored, storage threw, or the stored value is not a
 * list (a hand-edited or half-written key must not crash the editor). */
export function readRecentCatalogPicks(userId) {
  const stored = readJson(storageKey(RECENT_PRODUCTS_KEY, userId));
  if (!Array.isArray(stored)) return [];
  return stored.filter((entry) => entry && typeof entry === 'object').slice(0, RECENT_PRODUCT_LIMIT);
}

/** Most-recent-first, deduped by priceId, capped at RECENT_PRODUCT_LIMIT. Returns the new list so
 * the caller can update its own state without a second read (which could disagree if storage is
 * unavailable — the list stays live in memory for the session either way). */
export function pushRecentCatalogPick(userId, catalogRow) {
  const priceId = catalogRow?.priceId;
  const entry = {};
  for (const field of RECENT_PRODUCT_FIELDS) {
    if (catalogRow?.[field] !== undefined) entry[field] = catalogRow[field];
  }
  if (!Object.keys(entry).length) return readRecentCatalogPicks(userId);
  const existing = readRecentCatalogPicks(userId);
  const next = [
    entry,
    ...existing.filter((row) => (priceId != null ? row.priceId !== priceId : row !== entry)),
  ].slice(0, RECENT_PRODUCT_LIMIT);
  writeJson(storageKey(RECENT_PRODUCTS_KEY, userId), next);
  return next;
}
