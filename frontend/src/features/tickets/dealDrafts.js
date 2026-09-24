// ── client-side deal drafts (no server draft entity — see handoff 107) ──────
//
// Every localStorage access in this module is wrapped so it can NEVER throw — draft persistence
// is a nice-to-have, never a reason to break the create-deal form (private browsing, a full quota,
// or a locked-down browser all throw synchronously on getItem/setItem/removeItem in some engines).
// Callers get back a result object instead ({ ok: false, reason }) and decide what, if anything, to
// tell the rep.
//
// Storage shape (GLA-19 — "อยากให้ร่างทิ้งไว้หลายอันได้"):
//   glr:draft-deal:<id>   — one full snapshot per draft, plus its own `id` + `savedAt`.
//   glr:deal-drafts:index — a lightweight array of { id, savedAt, label } for listing without
//                           reading every draft. Treated as a cache, not the source of truth: if
//                           it's missing or corrupt, listDrafts() rebuilds it by scanning keys.
//   glr:draft-deal        — the OLD single-draft key (pre-GLA-19). migrateLegacyDraft() converts
//                           it to an id'd draft exactly once, removing it only after that write
//                           has actually landed.
const LEGACY_KEY = 'glr:draft-deal';
const INDEX_KEY = 'glr:deal-drafts:index';
const DRAFT_KEY_PREFIX = 'glr:draft-deal:';

export const MAX_DRAFTS = 10;

function draftKey(id) {
  return `${DRAFT_KEY_PREFIX}${id}`;
}

function safeRandomId() {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
  } catch {
    // fall through to the timestamp-based fallback below
  }
  return `d${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function classifyStorageError(err) {
  // DOMException QuotaExceededError (Chrome/Firefox), code 22, or Firefox's NS_ERROR_DOM_QUOTA_REACHED
  // (code 1014 on old Firefox) — anything else (private-mode Safari throws a plain SecurityError,
  // a locked-down environment may throw on the .localStorage getter itself) is reported as
  // 'unavailable' rather than guessed at.
  if (err && (err.name === 'QuotaExceededError' || err.code === 22 || err.code === 1014 || err.name === 'NS_ERROR_DOM_QUOTA_REACHED')) {
    return 'quota';
  }
  return 'unavailable';
}

function getItemSafe(key) {
  try {
    return { ok: true, value: localStorage.getItem(key) };
  } catch (err) {
    return { ok: false, value: null, reason: classifyStorageError(err) };
  }
}
function setItemSafe(key, value) {
  try {
    localStorage.setItem(key, value);
    return { ok: true };
  } catch (err) {
    return { ok: false, reason: classifyStorageError(err) };
  }
}
function removeItemSafe(key) {
  try {
    localStorage.removeItem(key);
    return { ok: true };
  } catch (err) {
    return { ok: false, reason: classifyStorageError(err) };
  }
}

/** Label shown in the draft picker: deal title, else customer, else project, else a placeholder. */
function labelFor(snapshot) {
  const title = (snapshot?.dealTitle || '').trim();
  if (title) return title;
  const customerName = snapshot?.customer?.name;
  if (customerName) return customerName;
  const projectName = snapshot?.project?.name;
  if (projectName) return projectName;
  return 'ร่างไม่มีชื่อ';
}

/** Tolerant parse of the index — never throws, treats anything not a clean array-of-entries as empty. */
function readIndexSafe() {
  const r = getItemSafe(INDEX_KEY);
  if (!r.ok || !r.value) return [];
  try {
    const parsed = JSON.parse(r.value);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((e) => e && typeof e === 'object' && typeof e.id === 'string');
  } catch {
    return [];
  }
}
function writeIndexSafe(entries) {
  try {
    return setItemSafe(INDEX_KEY, JSON.stringify(entries)).ok;
  } catch {
    return false;
  }
}

/** Enumerate every `glr:draft-deal:<id>` key currently in storage. Never throws. */
function scanDraftKeys() {
  try {
    const len = localStorage.length;
    if (typeof len !== 'number') return [];
    const keys = [];
    for (let i = 0; i < len; i += 1) {
      const k = localStorage.key(i);
      if (typeof k === 'string' && k.startsWith(DRAFT_KEY_PREFIX)) keys.push(k);
    }
    return keys;
  } catch {
    return [];
  }
}

/** Rebuild index entries by reading every draft key directly — used when the index is missing/corrupt. */
function rebuildIndexEntries() {
  const entries = [];
  for (const key of scanDraftKeys()) {
    const id = key.slice(DRAFT_KEY_PREFIX.length);
    if (!id) continue;
    const r = getItemSafe(key);
    if (!r.ok || !r.value) continue;
    try {
      const parsed = JSON.parse(r.value);
      if (parsed && typeof parsed === 'object') {
        entries.push({ id, savedAt: parsed.savedAt || null, label: labelFor(parsed) });
      }
    } catch {
      // one corrupt draft entry doesn't take the whole rebuild down
    }
  }
  return entries;
}

/**
 * Index entries whose draft key still actually exists — prunes any that don't (a draft key
 * removed directly, or a write that landed on the draft but not the index) and best-effort
 * persists the prune back to storage. This is what saveDraft()'s cap check counts against: without
 * it, a stale index entry for a draft that no longer exists on disk would count toward MAX_DRAFTS
 * forever, so the picker shows fewer real drafts than the cap thinks exist and a rep can hit
 * 'limit' with nothing they can actually see to delete.
 */
function readLiveIndexEntries() {
  const entries = readIndexSafe();
  if (entries.length === 0) return entries;
  const live = [];
  let prunedAny = false;
  for (const entry of entries) {
    if (loadDraft(entry.id) != null) {
      live.push(entry);
    } else {
      prunedAny = true;
    }
  }
  if (prunedAny) writeIndexSafe(live); // best-effort — the next read is correct even if this write fails
  return live;
}

/**
 * Every saved draft, newest first. Tolerant of a missing/corrupt index (rebuilds by scanning) and
 * of individual corrupt/missing draft entries (skipped rather than thrown).
 */
export function listDrafts() {
  let entries = readLiveIndexEntries();
  if (entries.length === 0) {
    // Could genuinely be zero drafts, or the index could be missing/corrupt — either way,
    // scanning is cheap and self-corrects the second case for free.
    entries = rebuildIndexEntries();
  }
  const result = [];
  for (const entry of entries) {
    const draft = loadDraft(entry.id);
    if (!draft) continue; // index says it exists but the draft itself is gone/corrupt — skip it
    result.push({
      id: entry.id,
      savedAt: draft.savedAt || entry.savedAt || null,
      label: labelFor(draft),
      itemCount: Array.isArray(draft.items) ? draft.items.length : 0,
      customerName: draft.customer?.name || null,
      projectName: draft.project?.name || null,
    });
  }
  result.sort((a, b) => new Date(b.savedAt || 0).getTime() - new Date(a.savedAt || 0).getTime());
  return result;
}

/** The full snapshot for one draft id, or null if missing/corrupt. */
export function loadDraft(id) {
  if (!id) return null;
  const r = getItemSafe(draftKey(id));
  if (!r.ok || !r.value) return null;
  try {
    const parsed = JSON.parse(r.value);
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch {
    return null;
  }
}

/**
 * Save (create or update) a draft. Pass `id: null` to create a new one — the cap only applies to
 * that case; updating an existing draft is always allowed, so a rep who is actively working never
 * loses ground to the cap. Returns `{ ok: true, id, savedAt }` or `{ ok: false, reason }` where
 * reason is 'unavailable' | 'quota' | 'limit'.
 */
export function saveDraft(id, snapshot) {
  // A draft counts as "existing" if either the index or the draft key itself already has it — the
  // index is a cache and can lag a successful draft write if its own write failed.
  const isExisting = Boolean(id) && (readIndexSafe().some((e) => e.id === id) || loadDraft(id) != null);
  if (!isExisting) {
    // readLiveIndexEntries (not readIndexSafe) — counting stale entries whose draft key is
    // already gone would let the cap block a NEW draft while the picker shows fewer real drafts
    // than 10, with nothing visible for the rep to delete to free a slot.
    if (readLiveIndexEntries().length >= MAX_DRAFTS) {
      return { ok: false, reason: 'limit' };
    }
  }
  const draftId = id || safeRandomId();
  const savedAt = new Date().toISOString();
  const payload = { ...snapshot, id: draftId, savedAt };

  let json;
  try {
    json = JSON.stringify(payload);
  } catch {
    return { ok: false, reason: 'unavailable' };
  }
  const written = setItemSafe(draftKey(draftId), json);
  if (!written.ok) {
    return { ok: false, reason: written.reason };
  }

  const nextIndex = readIndexSafe().filter((e) => e.id !== draftId);
  nextIndex.push({ id: draftId, savedAt, label: labelFor(payload) });
  writeIndexSafe(nextIndex); // best-effort — listDrafts() self-heals from a stale/failed index

  return { ok: true, id: draftId, savedAt };
}

/** Delete one draft (its key and its index entry). Tolerant of either half already being gone. */
export function deleteDraft(id) {
  if (!id) return { ok: false, reason: 'unavailable' };
  const removed = removeItemSafe(draftKey(id));
  writeIndexSafe(readIndexSafe().filter((e) => e.id !== id));
  return removed.ok ? { ok: true } : { ok: false, reason: removed.reason };
}

/**
 * One-time conversion of the pre-GLA-19 single `glr:draft-deal` key into a proper id'd draft.
 * Idempotent: the legacy key is removed ONLY once its replacement has actually been written, so a
 * failed write (storage unavailable, or already at MAX_DRAFTS) leaves the legacy draft in place to
 * retry on the next mount instead of losing it. A corrupt legacy value is left untouched (nothing
 * to migrate, nothing lost) rather than deleted.
 */
export function migrateLegacyDraft() {
  const legacy = getItemSafe(LEGACY_KEY);
  if (!legacy.ok || !legacy.value) return { ok: true, migrated: false };

  let parsed;
  try {
    parsed = JSON.parse(legacy.value);
  } catch {
    return { ok: true, migrated: false };
  }
  if (!parsed || typeof parsed !== 'object') {
    return { ok: true, migrated: false };
  }

  const result = saveDraft(null, parsed);
  if (!result.ok) {
    return { ok: false, reason: result.reason, migrated: false };
  }
  removeItemSafe(LEGACY_KEY);
  return { ok: true, migrated: true, id: result.id, savedAt: result.savedAt };
}

/**
 * True when a snapshot carries something a rep actually typed/picked, as opposed to only the
 * form's own defaults (priority defaults to NORMAL, nextFollowUpAt defaults to +14 days — neither
 * is "real data" on its own). No draft is ever created for a snapshot that fails this.
 */
export function hasMeaningfulDraftData(snapshot) {
  if (!snapshot) return false;
  const title = (snapshot.dealTitle || '').trim();
  const note = (snapshot.note || '').trim();
  if (title || note) return true;
  if (snapshot.customer || snapshot.project || snapshot.contact) return true;
  if (snapshot.entryChannel) return true;
  if (Array.isArray(snapshot.items) && snapshot.items.length > 0) return true;
  return false;
}
