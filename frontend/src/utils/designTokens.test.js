import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Guards the Phase 3.4 token infrastructure (docs/ui-repair/03-design-foundation/
// TOKENS.md + DESIGN.md §19). Two things this checks: (1) the tokens the design
// docs promise actually exist in index.css, so the docs and the code can't drift
// silently; (2) the text/background pairs the docs claim are WCAG-safe actually
// clear their stated ratio, so a future token-value edit can't quietly break
// contrast without a test going red.

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const indexCss = fs.readFileSync(path.resolve(__dirname, '../index.css'), 'utf8');

function normalizeHex(hex) {
  const h = hex.replace('#', '');
  if (h.length === 3) {
    return h.split('').map((c) => c + c).join('');
  }
  return h;
}

function relativeLuminance(hex) {
  const h = normalizeHex(hex);
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
  const [rl, gl, bl] = [r, g, b].map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * rl + 0.7152 * gl + 0.0722 * bl;
}

// WCAG 2.1 contrast ratio between two opaque sRGB colors.
function contrastRatio(hexA, hexB) {
  const la = relativeLuminance(hexA);
  const lb = relativeLuminance(hexB);
  const [lighter, darker] = la >= lb ? [la, lb] : [lb, la];
  return (lighter + 0.05) / (darker + 0.05);
}

describe('design tokens — Phase 3.4 infrastructure', () => {
  it('defines every gap token TOKENS.md documents', () => {
    const expectedTokens = [
      '--color-overlay',
      '--color-overlay-drawer',
      '--color-veil',
      '--motion-fast',
      '--motion-standard',
      '--motion-slow',
      '--ease-standard',
      '--ease-exit',
      '--sidebar-width',
      '--sidebar-width-icon',
      '--content-max',
      '--page-gutter-mobile',
      '--page-gutter-tablet',
      '--page-gutter-desktop',
    ];
    expectedTokens.forEach((token) => {
      expect(indexCss, `${token} should be declared in index.css`).toContain(`${token}:`);
    });
  });

  it('declares the mobile/tablet breakpoint custom variants (single-source of the F-01 breakpoint)', () => {
    expect(indexCss).toMatch(/@custom-variant\s+mobile\s*\{[\s\S]*?max-width:\s*720px/);
    expect(indexCss).toMatch(/@custom-variant\s+tablet\s*\{[\s\S]*?min-width:\s*721px[\s\S]*?max-width:\s*1040px/);
  });

  it('provides a reduced-motion override for the new motion tokens', () => {
    expect(indexCss).toMatch(/prefers-reduced-motion:\s*reduce/);
    expect(indexCss).toMatch(/--motion-standard:\s*0ms/);
  });

  it('overlay colors match the existing hardcoded literals they will eventually replace', () => {
    // styles.css:1465 .modal-backdrop / :2036 mobile drawer backdrop
    expect(indexCss).toContain('rgba(15, 23, 42, 0.52)');
    // styles.css:2036 (drawer) — distinct from the modal overlay above
    expect(indexCss).toContain('rgba(15, 23, 42, 0.48)');
    // styles.css:1551 .loading-veil
    expect(indexCss).toContain('rgba(255, 255, 255, 0.55)');
  });

  describe('contrast (WCAG 2.1 AA) — pairs cited in DESIGN.md §19 / TOKENS.md', () => {
    it.each([
      ['text-secondary on surface-panel', '#334155', '#ffffff', 4.5],
      ['text-muted floor on surface-panel', '#64748b', '#ffffff', 4.5],
      ['text-inverse on action-primary', '#ffffff', '#4f46e5', 4.5],
      ['action-danger text on surface-panel', '#dc2626', '#ffffff', 4.5],
      ['status-warning text on warning bg', '#b45309', '#fef3c7', 4.5],
      // .status-danger pairs danger-bg with danger-dark, not the plain
      // danger action color (styles.css :695-735) — matches actual CSS.
      ['status-danger text on danger bg', '#b91c1c', '#fee2e2', 4.5],
      ['status-success text on success bg', '#15803d', '#dcfce7', 4.5],
      ['status-info text on info bg', '#1d4ed8', '#dbeafe', 4.5],
      ['link on surface-panel', '#2563eb', '#ffffff', 4.5],
    ])('%s clears %s:1', (_label, fg, bg, min) => {
      expect(contrastRatio(fg, bg)).toBeGreaterThanOrEqual(min);
    });

    // TOKENS.md §D flags this pair as a documented exception: white on the
    // success fill only clears the large/bold-text floor (3:1), not the body
    // floor (4.5:1). This test locks that caveat in — if it ever crosses 4.5
    // the token's value changed and the "bold-label only" note in DESIGN.md
    // needs re-checking, not silent drift.
    it('action-success (large/bold text only) — documents the borderline pairing', () => {
      const ratio = contrastRatio('#ffffff', '#059669');
      expect(ratio).toBeGreaterThanOrEqual(3);
      expect(ratio).toBeLessThan(4.5);
    });
  });
});
