import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// Unmount anything rendered (incl. renderHook) between tests so state never leaks.
afterEach(() => {
  cleanup();
});
