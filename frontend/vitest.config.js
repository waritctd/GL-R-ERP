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
  },
});
