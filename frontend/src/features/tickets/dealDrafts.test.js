import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  MAX_DRAFTS,
  deleteDraft,
  hasMeaningfulDraftData,
  listDrafts,
  loadDraft,
  migrateLegacyDraft,
  saveDraft,
} from './dealDrafts.js';

/**
 * A real, full Storage implementation (getItem/setItem/removeItem/clear/key/length) backed by a
 * Map — NOT a partial mock. dealDrafts.js's listDrafts() rebuild path calls `.length`/`.key(i)` to
 * scan for `glr:draft-deal:*` keys when the index is missing, so a stub without those would make
 * every "corrupt/missing index" case pass for the wrong reason (nothing to scan, so "rebuilds to
 * empty" and "rebuilds correctly" would look identical). See TicketCreateModal.test.jsx's own
 * localStorage-stub comment for the sibling trap this avoids (a jsdom/Node global whose methods
 * exist but are not functions).
 */
function createMemoryStorage() {
  const store = new Map();
  return {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)); },
    removeItem: (k) => { store.delete(k); },
    clear: () => { store.clear(); },
    key: (i) => Array.from(store.keys())[i] ?? null,
    get length() { return store.size; },
    // test-only escape hatch for inspecting raw storage content
    _raw: store,
  };
}

let storage;

beforeEach(() => {
  storage = createMemoryStorage();
  vi.stubGlobal('localStorage', storage);
});

describe('hasMeaningfulDraftData', () => {
  it('is false for null/undefined and for defaults-only snapshots', () => {
    expect(hasMeaningfulDraftData(null)).toBe(false);
    expect(hasMeaningfulDraftData(undefined)).toBe(false);
    expect(hasMeaningfulDraftData({ priority: 'NORMAL', nextFollowUpAt: '2026-10-01', items: [] })).toBe(false);
  });

  it('is true when a title, note, customer, project, contact, channel or item is present', () => {
    expect(hasMeaningfulDraftData({ dealTitle: 'ดีลทดสอบ' })).toBe(true);
    expect(hasMeaningfulDraftData({ note: 'บันทึกเพิ่มเติม' })).toBe(true);
    expect(hasMeaningfulDraftData({ customer: { id: 1, name: 'บริษัท เอ' } })).toBe(true);
    expect(hasMeaningfulDraftData({ project: { id: 1, name: 'โครงการ เอ' } })).toBe(true);
    expect(hasMeaningfulDraftData({ contact: { id: 1 } })).toBe(true);
    expect(hasMeaningfulDraftData({ entryChannel: 'OWNER_DIRECT' })).toBe(true);
    expect(hasMeaningfulDraftData({ items: [{ brand: 'A' }] })).toBe(true);
  });

  it('treats whitespace-only title/note as empty', () => {
    expect(hasMeaningfulDraftData({ dealTitle: '   ', note: '\n\t ' })).toBe(false);
  });
});

describe('saveDraft / loadDraft / listDrafts / deleteDraft', () => {
  it('creates a new draft when id is null and it can be loaded back', () => {
    const result = saveDraft(null, { dealTitle: 'ดีล A' });
    expect(result.ok).toBe(true);
    expect(result.id).toBeTruthy();
    expect(result.savedAt).toBeTruthy();

    const loaded = loadDraft(result.id);
    expect(loaded.dealTitle).toBe('ดีล A');
    expect(loaded.id).toBe(result.id);
    expect(loaded.savedAt).toBe(result.savedAt);
  });

  it('loadDraft returns null for an unknown id', () => {
    expect(loadDraft('does-not-exist')).toBeNull();
    expect(loadDraft(null)).toBeNull();
    expect(loadDraft(undefined)).toBeNull();
  });

  it('two independently-created drafts do not overwrite each other', () => {
    const a = saveDraft(null, { dealTitle: 'ดีล A' });
    const b = saveDraft(null, { dealTitle: 'ดีล B' });
    expect(a.id).not.toBe(b.id);
    expect(loadDraft(a.id).dealTitle).toBe('ดีล A');
    expect(loadDraft(b.id).dealTitle).toBe('ดีล B');

    const listed = listDrafts();
    expect(listed).toHaveLength(2);
    expect(listed.map((d) => d.id).sort()).toEqual([a.id, b.id].sort());
  });

  it('saving with an existing id updates that draft in place (no new entry)', () => {
    const a = saveDraft(null, { dealTitle: 'ดีล A' });
    const updated = saveDraft(a.id, { dealTitle: 'ดีล A แก้ไข' });
    expect(updated.ok).toBe(true);
    expect(updated.id).toBe(a.id);
    expect(loadDraft(a.id).dealTitle).toBe('ดีล A แก้ไข');
    expect(listDrafts()).toHaveLength(1);
  });

  it('listDrafts sorts newest first', () => {
    // savedAt is `new Date().toISOString()` at save time — fake timers make the ordering
    // deterministic instead of racing real-clock millisecond resolution.
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date('2026-09-17T10:00:00Z'));
      const a = saveDraft(null, { dealTitle: 'เก่ากว่า' });
      vi.setSystemTime(new Date('2026-09-17T10:00:05Z'));
      const b = saveDraft(null, { dealTitle: 'ใหม่กว่า' });
      vi.setSystemTime(new Date('2026-09-17T10:00:10Z'));
      saveDraft(a.id, { dealTitle: 'เก่ากว่า (แก้ไขล่าสุด)' }); // bumps `a` past `b`

      const listed = listDrafts();
      expect(listed[0].id).toBe(a.id);
      expect(listed[1].id).toBe(b.id);
    } finally {
      vi.useRealTimers();
    }
  });

  it('deleteDraft removes both the draft and its index entry', () => {
    const a = saveDraft(null, { dealTitle: 'ดีล A' });
    const b = saveDraft(null, { dealTitle: 'ดีล B' });
    const result = deleteDraft(a.id);
    expect(result.ok).toBe(true);
    expect(loadDraft(a.id)).toBeNull();
    const listed = listDrafts();
    expect(listed).toHaveLength(1);
    expect(listed[0].id).toBe(b.id);
  });

  it('deleteDraft is tolerant of an unknown id', () => {
    expect(deleteDraft('nope')).toEqual({ ok: true });
    expect(deleteDraft(null)).toEqual({ ok: false, reason: 'unavailable' });
  });

  it('label falls back through dealTitle -> customer -> project -> placeholder', () => {
    const withTitle = saveDraft(null, { dealTitle: 'ดีลมีชื่อ', customer: { name: 'ลูกค้า X' } });
    const withCustomerOnly = saveDraft(null, { customer: { name: 'ลูกค้า Y' } });
    const withProjectOnly = saveDraft(null, { project: { name: 'โครงการ Z' } });
    const withNothing = saveDraft(null, { priority: 'NORMAL' });

    const byId = Object.fromEntries(listDrafts().map((d) => [d.id, d]));
    expect(byId[withTitle.id].label).toBe('ดีลมีชื่อ');
    expect(byId[withCustomerOnly.id].label).toBe('ลูกค้า Y');
    expect(byId[withProjectOnly.id].label).toBe('โครงการ Z');
    expect(byId[withNothing.id].label).toBe('ร่างไม่มีชื่อ');
  });
});

describe('MAX_DRAFTS cap', () => {
  it('refuses a NEW draft at the cap without evicting anything', () => {
    const ids = [];
    for (let i = 0; i < MAX_DRAFTS; i += 1) {
      ids.push(saveDraft(null, { dealTitle: `ดีล ${i}` }).id);
    }
    expect(listDrafts()).toHaveLength(MAX_DRAFTS);

    const rejected = saveDraft(null, { dealTitle: 'ดีลที่เกิน' });
    expect(rejected).toEqual({ ok: false, reason: 'limit' });

    // Nothing evicted — all MAX_DRAFTS originals are still there, unchanged.
    expect(listDrafts()).toHaveLength(MAX_DRAFTS);
    expect(listDrafts().map((d) => d.id).sort()).toEqual([...ids].sort());
  });

  it('updating an EXISTING draft is always allowed, even at the cap', () => {
    const ids = [];
    for (let i = 0; i < MAX_DRAFTS; i += 1) {
      ids.push(saveDraft(null, { dealTitle: `ดีล ${i}` }).id);
    }
    const updated = saveDraft(ids[0], { dealTitle: 'ดีล 0 แก้ไขแล้ว' });
    expect(updated.ok).toBe(true);
    expect(loadDraft(ids[0]).dealTitle).toBe('ดีล 0 แก้ไขแล้ว');
    expect(listDrafts()).toHaveLength(MAX_DRAFTS);
  });

  // Review fix: the cap used to count RAW index entries, including ones whose draft key had
  // already been removed some other way (directly, or a delete whose index write failed) — so a
  // rep could be told "limit" while the picker showed fewer than MAX_DRAFTS real rows, with
  // nothing visible to delete to free a slot.
  it('prunes a stale index entry (draft key gone) so the cap reflects only real drafts', () => {
    const ids = [];
    for (let i = 0; i < MAX_DRAFTS; i += 1) {
      ids.push(saveDraft(null, { dealTitle: `ดีล ${i}` }).id);
    }
    // Remove one draft's OWN key directly, bypassing deleteDraft() -- the index still claims it.
    localStorage.removeItem(`glr:draft-deal:${ids[0]}`);

    const result = saveDraft(null, { dealTitle: 'ดีลใหม่หลังพื้นที่ว่าง' });
    expect(result.ok).toBe(true);

    const listed = listDrafts();
    expect(listed).toHaveLength(MAX_DRAFTS); // the 9 real originals + the new one
    expect(listed.some((d) => d.id === ids[0])).toBe(false);

    // The index itself no longer lists the missing id (not just listDrafts() filtering it out).
    const rawIndex = JSON.parse(localStorage.getItem('glr:deal-drafts:index'));
    expect(rawIndex.some((e) => e.id === ids[0])).toBe(false);
  });
});

describe('migrateLegacyDraft', () => {
  const LEGACY_KEY = 'glr:draft-deal';

  it('converts a legacy draft into a new id\'d draft and removes the legacy key', () => {
    localStorage.setItem(LEGACY_KEY, JSON.stringify({ dealTitle: 'ร่างเก่า', entryChannel: 'OWNER_DIRECT' }));

    const result = migrateLegacyDraft();
    expect(result.ok).toBe(true);
    expect(result.migrated).toBe(true);
    expect(localStorage.getItem(LEGACY_KEY)).toBeNull();

    const loaded = loadDraft(result.id);
    expect(loaded.dealTitle).toBe('ร่างเก่า');
    expect(loaded.entryChannel).toBe('OWNER_DIRECT');
  });

  it('is a no-op (migrated: false) when there is no legacy draft', () => {
    const result = migrateLegacyDraft();
    expect(result).toEqual({ ok: true, migrated: false });
  });

  it('is idempotent — calling it twice only migrates once', () => {
    localStorage.setItem(LEGACY_KEY, JSON.stringify({ dealTitle: 'ร่างเก่า' }));
    const first = migrateLegacyDraft();
    expect(first.migrated).toBe(true);

    const second = migrateLegacyDraft();
    expect(second).toEqual({ ok: true, migrated: false });
    // Still exactly one draft, not two.
    expect(listDrafts()).toHaveLength(1);
  });

  it('leaves a corrupt legacy value untouched rather than deleting it', () => {
    localStorage.setItem(LEGACY_KEY, '{not valid json');
    const result = migrateLegacyDraft();
    expect(result).toEqual({ ok: true, migrated: false });
    expect(localStorage.getItem(LEGACY_KEY)).toBe('{not valid json');
    expect(listDrafts()).toHaveLength(0);
  });

  it('leaves the legacy key in place when the migrated write fails (e.g. already at the cap)', () => {
    for (let i = 0; i < MAX_DRAFTS; i += 1) {
      saveDraft(null, { dealTitle: `ดีล ${i}` });
    }
    localStorage.setItem(LEGACY_KEY, JSON.stringify({ dealTitle: 'ร่างเก่า' }));

    const result = migrateLegacyDraft();
    expect(result.ok).toBe(false);
    expect(result.reason).toBe('limit');
    expect(localStorage.getItem(LEGACY_KEY)).not.toBeNull();
    expect(listDrafts()).toHaveLength(MAX_DRAFTS); // not migrated in
  });
});

describe('broken localStorage never throws', () => {
  function throwingStorage(methods) {
    const base = createMemoryStorage();
    const wrapped = { ...base };
    for (const name of methods) {
      wrapped[name] = () => { throw new Error(`${name} is not available`); };
    }
    return wrapped;
  }

  it('getItem throwing: loadDraft/listDrafts return safe empty results, no throw', () => {
    vi.stubGlobal('localStorage', throwingStorage(['getItem']));
    expect(() => loadDraft('x')).not.toThrow();
    expect(loadDraft('x')).toBeNull();
    expect(() => listDrafts()).not.toThrow();
    expect(listDrafts()).toEqual([]);
  });

  it('setItem throwing: saveDraft reports ok:false, unavailable, no throw', () => {
    vi.stubGlobal('localStorage', throwingStorage(['setItem']));
    let result;
    expect(() => { result = saveDraft(null, { dealTitle: 'x' }); }).not.toThrow();
    expect(result).toEqual({ ok: false, reason: 'unavailable' });
  });

  it('setItem throwing a quota error: saveDraft reports reason "quota"', () => {
    const base = createMemoryStorage();
    const quotaError = new DOMException('quota exceeded', 'QuotaExceededError');
    vi.stubGlobal('localStorage', {
      ...base,
      setItem: () => { throw quotaError; },
    });
    const result = saveDraft(null, { dealTitle: 'x' });
    expect(result).toEqual({ ok: false, reason: 'quota' });
  });

  it('removeItem throwing: deleteDraft reports ok:false, no throw', () => {
    const base = createMemoryStorage();
    base.setItem('glr:draft-deal:abc', JSON.stringify({ id: 'abc', dealTitle: 'x' }));
    vi.stubGlobal('localStorage', {
      ...base,
      removeItem: () => { throw new Error('nope'); },
    });
    let result;
    expect(() => { result = deleteDraft('abc'); }).not.toThrow();
    expect(result.ok).toBe(false);
    expect(result.reason).toBe('unavailable');
  });

  it('every method throwing: nothing in the module throws, everything degrades to ok:false/empty', () => {
    vi.stubGlobal('localStorage', throwingStorage(['getItem', 'setItem', 'removeItem', 'key']));
    expect(() => {
      listDrafts();
      loadDraft('x');
      saveDraft(null, { dealTitle: 'x' });
      deleteDraft('x');
      migrateLegacyDraft();
    }).not.toThrow();
  });
});

describe('corrupt index / entries are tolerated', () => {
  it('rebuilds from a scan when the index value is not valid JSON', () => {
    const a = saveDraft(null, { dealTitle: 'ดีล A' });
    localStorage.setItem('glr:deal-drafts:index', 'not json{{{');
    const listed = listDrafts();
    expect(listed).toHaveLength(1);
    expect(listed[0].id).toBe(a.id);
  });

  it('rebuilds from a scan when the index is missing entirely', () => {
    const a = saveDraft(null, { dealTitle: 'ดีล A' });
    localStorage.removeItem('glr:deal-drafts:index');
    const listed = listDrafts();
    expect(listed).toHaveLength(1);
    expect(listed[0].id).toBe(a.id);
  });

  it('skips an index entry whose draft value is missing (stale index)', () => {
    const a = saveDraft(null, { dealTitle: 'ดีล A' });
    localStorage.setItem('glr:deal-drafts:index', JSON.stringify([
      { id: a.id, savedAt: new Date().toISOString(), label: 'ดีล A' },
      { id: 'ghost-id', savedAt: new Date().toISOString(), label: 'ผี' },
    ]));
    const listed = listDrafts();
    expect(listed).toHaveLength(1);
    expect(listed[0].id).toBe(a.id);
  });

  it('skips a draft key holding corrupt JSON during a rebuild scan', () => {
    localStorage.setItem('glr:draft-deal:bad', 'not json{{{');
    const a = saveDraft(null, { dealTitle: 'ดีล A' });
    localStorage.removeItem('glr:deal-drafts:index');
    const listed = listDrafts();
    expect(listed).toHaveLength(1);
    expect(listed[0].id).toBe(a.id);
  });
});
