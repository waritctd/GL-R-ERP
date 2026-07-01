import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Test config kept separate from vite.config.js so the dev/build pipeline is untouched.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
    include: ['src/**/*.test.{js,jsx}'],
    css: false,
  },
});
