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

// Unmount anything rendered (incl. renderHook) between tests so state never leaks.
afterEach(() => {
  cleanup();
});
