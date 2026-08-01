import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import process from 'node:process';
import { describe, expect, it } from 'vitest';

const stylesCss = readFileSync(join(process.cwd(), 'src/styles.css'), 'utf8');
const indexCss = readFileSync(join(process.cwd(), 'src/index.css'), 'utf8');

describe('ticket workspace sticky layout source guards', () => {
  it('keeps one measured ticket chrome height token with a safe fallback', () => {
    expect(indexCss).toContain('--app-topbar-h: 66px;');
    expect(indexCss).toContain('--deal-header-h: 18rem;');
    expect(stylesCss).toContain('scroll-padding-top: calc(var(--deal-header-h, 18rem) + var(--space-4));');
  });

  it('keeps mobile focus scrolling clear of the fixed bottom action bar', () => {
    expect(stylesCss).toContain('scroll-padding-bottom: calc(112px + env(safe-area-inset-bottom));');
  });
});
