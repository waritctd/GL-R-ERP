import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { readFileSync } from 'node:fs';

const { version } = JSON.parse(readFileSync('./package.json', 'utf8'));

// Test config kept separate from vite.config.js so the dev/build pipeline is untouched.
export default defineConfig({
  plugins: [react()],
  define: { __APP_VERSION__: JSON.stringify(version) },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
    // scripts/ carries the CI audit gate, whose own pass/fail behaviour is tested.
    include: ['src/**/*.test.{js,jsx}', 'scripts/**/*.test.js', 'e2e-visual/**/*.test.js'],
    css: false,
    env: {
      // Release lockdown OFF for the suite, so the ~1300 existing tests keep exercising the FULL product (sales nav,
      // HR queues, payroll routes) rather than a self-service stub.
      VITE_SELF_SERVICE_ONLY: 'false',
      // mockApi's simulated 140ms latency is dev-server realism; under the suite it only burns wall-clock time.
      VITE_MOCK_LATENCY_MS: '0',
    },
  },
});
