import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The mock API is imported STATICALLY by src/api/index.js — deliberately, so `api` can stay a plain
// synchronous export that every feature file can import without becoming async. The cost was that
// the mock's demo seed shipped in every production bundle: Rollup drops the mock's method bodies
// once `VITE_USE_MOCKS` folds to `false`, but not its module-level `const db = createDemoDatabase()`
// or the `db.commissions = …` mutation under it, and keeping `db` alive keeps demoData.js,
// demoHr.js, demoSales.js and demoPayroll.js alive too. Measured on the deployed phase-1 build:
// ~156KB of dist/assets, 110 of 110 of demoData.js's Thai literals present.
//
// Swapping the module at RESOLVE time is deterministic, where a `/* @__PURE__ */` annotation was
// not (measured: 0KB saved, because the second call is a genuine mutation).
//
// Keyed on the same variable src/api/index.js uses, so the two can never disagree about which
// implementation is live. `npm run dev -- VITE_USE_MOCKS=true` and the `frontend-mock` launch
// config still get the real mock; a production build (which sets no VITE_ vars at all — there is no
// `env` block in vercel.json) gets the stub.
//
// vitest.config.js is a SEPARATE config and does not read this file, so the unit suite — including
// contract.test.js, which walks mockApi's whole method surface — always sees the real module.
const useMocks = process.env.VITE_USE_MOCKS === 'true';
const mockApiAlias = useMocks ? [] : [{
  find: /^\.\/mockApi\.js$/,
  replacement: fileURLToPath(new URL('./src/api/mockApi.stub.js', import.meta.url)),
}];

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: mockApiAlias },
  server: {
    host: '127.0.0.1',
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': {
        // Overridable so the real-backend e2e suite (playwright.real.config.js) can point the
        // dev server at a backend on another port/host without editing this file. Default is
        // unchanged: the local `docker compose up` / `./mvnw spring-boot:run` backend.
        target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 4174,
    strictPort: true,
  },
});
