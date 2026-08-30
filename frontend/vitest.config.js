import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Test config kept separate from vite.config.js so the dev/build pipeline is untouched.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
    // scripts/ carries the CI audit gate, whose own pass/fail behaviour is tested.
    // e2e-visual/ carries the accepted-changes parser, which decides whether a pixel diff
    // is allowed to pass a required check — that logic needs suite coverage even though
    // the Playwright specs beside it (*.spec.js) are never collected here.
    include: ['src/**/*.test.{js,jsx}', 'scripts/**/*.test.js', 'e2e-visual/**/*.test.js'],
    css: false,
    env: {
      // Release lockdown OFF for the suite, so the ~1300 existing tests keep
      // exercising the FULL product (sales nav, HR queues, payroll routes)
      // rather than a self-service stub. SELF_SERVICE_ONLY defaults ON — see
      // app/features.js for why the default has to be the locked one — and
      // without this line every one of those tests would assert against a
      // portal with four nav items.
      //
      // The lock's own behaviour is covered by tests that stub it back ON with
      // vi.stubEnv + vi.resetModules + a dynamic import (features.js reads
      // import.meta.env once, at module-load time): see App.test.jsx,
      // AppShell.test.jsx and app/permissions.test.js.
      VITE_SELF_SERVICE_ONLY: 'false',
    },
  },
});
