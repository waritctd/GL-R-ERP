import { afterEach } from 'vitest';
import { cleanup, configure } from '@testing-library/react';

// Every route in App.jsx is `React.lazy()`, so any findBy*/waitFor that asserts
// on a routed page must cover a dynamic import on top of the render — and
// testing-library's default asyncUtilTimeout is only 1000ms.
//
// Measured: importing PricingRequestQueuePage's chunk alone costs ~220ms on an
// idle single-file run. Across 74 test files in parallel, transform contention
// pushes that well past 1000ms, and the assertion flakes. That is exactly how
// App.test.jsx's "/pricing-requests guard for import" failed intermittently in
// full-suite runs while passing every time in isolation — a timing limit, not a
// broken route guard.
//
// 3000ms keeps a genuine failure inside vitest's own 5000ms testTimeout, so a
// truly missing element still reports testing-library's "unable to find role"
// diagnostic (with the rendered DOM) rather than a bare, uninformative test
// timeout.
configure({ asyncUtilTimeout: 3000 });

// The ใบเสนอราคา editor remembers a rep's last terms in localStorage (quotationPrefs.js), so a
// test that saves a quotation seeds the NEXT test's starting terms — and one of those terms,
// ส่วนที่เหลือ = CREDIT, renders an extra input whose accessible name a prefix label query then
// matches alongside the item row's own field. That made a test's result depend on which test ran
// before it: green locally, where this Node's built-in localStorage shadows jsdom's and stores
// nothing, and red in CI, where it does not. Clearing the namespace between tests is what makes
// the two environments agree.
//
// Wrapped because the accessor ITSELF throws in some environments, exactly as quotationPrefs.js
// documents, and a setup file that throws takes down every test in the run rather than one.
const PREF_KEY_PREFIX = 'glr.';

function clearAppPreferences() {
  try {
    const storage = globalThis.localStorage;
    // `length`/`key` are checked rather than assumed: this repo's Node ships a built-in
    // `localStorage` that shadows jsdom's and is only a partial Storage — `clear` is not even a
    // function on it — so feature-detect the two members this loop actually uses.
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
