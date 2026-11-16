// The old create-deal form kept client-side drafts in localStorage (features/tickets/dealDrafts.js, removed with the
// short create form on 2026-11-16). Nothing reads them any more, and they can hold customer details, so on app start
// — once per browser — they are removed (fix round 1, ruling 7). The keys were:
//   glr:draft-deal              (the original single draft)
//   glr:deal-drafts:index       (the draft list)
//   glr:draft-deal:<id>         (one per draft)
// Never throws: storage may be missing, full or blocked (private mode), and the app must start regardless.

const LEGACY_KEY = 'glr:draft-deal';
const INDEX_KEY = 'glr:deal-drafts:index';
const DRAFT_KEY_PREFIX = 'glr:draft-deal:';
export const PURGED_MARKER = 'glr:deal-drafts:purged';

export function purgeLegacyDealDrafts(storage = globalThis.localStorage) {
  try {
    if (!storage || storage.getItem(PURGED_MARKER)) return;
    const doomed = [LEGACY_KEY, INDEX_KEY];
    for (let i = 0; i < storage.length; i += 1) {
      const key = storage.key(i);
      if (key && key.startsWith(DRAFT_KEY_PREFIX)) doomed.push(key);
    }
    doomed.forEach((key) => storage.removeItem(key));
    storage.setItem(PURGED_MARKER, '1');
  } catch {
    // Storage unavailable: nothing to purge, and nothing to do about it.
  }
}
