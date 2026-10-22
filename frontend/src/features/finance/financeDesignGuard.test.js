import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

// Design-system drift guard for the finance pages (static scan; jsdom cannot measure contrast or size).
const FILES = ['AccountFinancePage.jsx', 'FinanceDealPage.jsx', 'FinanceMilestoneSections.jsx', 'FinanceActionModals.jsx'];
// vitest's root is frontend/, so resolve from cwd (import.meta.url is not a file: URL under vitest).
const read = (f) => readFileSync(`${process.cwd()}/src/features/finance/${f}`, 'utf8');

describe('finance pages design-system guard', () => {
  it.each(FILES)('%s never sets meaningful text in text-text-faint (2.3:1 on white)', (file) => {
    expect(read(file)).not.toMatch(/text-text-faint/);
  });

  it.each(FILES)('%s keeps copy on the 13px ramp: no text-xs (12px); only text-2xs for column headers / marker digits', (file) => {
    expect(read(file)).not.toMatch(/\btext-xs\b/);
  });
});
