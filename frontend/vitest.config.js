import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Test config kept separate from vite.config.js so the dev/build pipeline is untouched.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
    // scripts/ carries the CI audit gate, whose own pass/fail behaviour is tested.
    include: ['src/**/*.test.{js,jsx}', 'scripts/**/*.test.js'],
    css: false,
  },
});
