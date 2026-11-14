import { afterEach } from 'vitest';
import { cleanup, configure } from '@testing-library/react';

// Every route in App.jsx is `React.lazy()`, so any findBy*/waitFor that asserts on a routed page must cover a
// dynamic import on top of the render — and testing-library's default asyncUtilTimeout is only 1000ms.
configure({ asyncUtilTimeout: 3000 });

// The ใบเสนอราคา editor remembers a rep's last terms in localStorage, so a test that saves a quotation seeds
// the NEXT test's starting terms. Clearing the namespace between tests is what makes the two environments
// agree.
const PREF_KEY_PREFIX = 'glr.';

function clearAppPreferences() {
  try {
    const storage = globalThis.localStorage;
    // `length`/`key` are checked rather than assumed: this repo's Node ships a built-in `localStorage` that
    // shadows jsdom's and is only a partial Storage — `clear` is not even a function on it — so feature-detect the
    // two members this loop actually uses.
    if (!storage || typeof storage.length !== 'number' || typeof storage.key !== 'function') return;
    for (let i = storage.length - 1; i >= 0; i -= 1) {
      const key = storage.key(i);
      if (key?.startsWith(PREF_KEY_PREFIX)) storage.removeItem(key);
    }
  } catch {
    // No storage, or storage that refuses to be read — nothing to leak either way.
  }
}

// Unmount anything rendered (incl. renderHook) between tests so state never leaks.
afterEach(() => {
  cleanup();
  clearAppPreferences();
});
