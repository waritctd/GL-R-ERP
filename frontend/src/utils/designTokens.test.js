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

// Reads a custom-property's *declared* hex value straight out of index.css —
// never a hardcoded literal — so a future edit to the token's value (not just
// its existence) flows into the contrast assertions below and can turn them
// red. Matches only the `--token: #hex;` declaration itself (colon
// immediately followed by the hex value), not `var(--token)` call sites or
// prose mentions elsewhere in the file's comments.
function token(name) {
  const re = new RegExp(`${name}:\\s*(#[0-9a-fA-F]{3,8})\\s*;`);
  const match = indexCss.match(re);
  if (!match) {
    throw new Error(`token ${name} not found (as a declaration) in index.css`);
  }
  return match[1];
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
    // Every fg/bg here is read live from index.css via token() (see above) —
    // never a hardcoded hex literal. This is the point of the rewrite
    // (fix/ui-contrast-tokens review remediation, 2026-07-28): the previous
    // version of this table inlined hex values, so reverting either
    // --color-text-muted or --color-success in index.css left this file
    // 14/14 green — a token-value edit could silently reintroduce every
    // contrast failure this branch fixed. Mutation-checked: reverting
    // --color-text-muted to #64748b turns the surface-subtle/workspace rows
    // red (4.34:1 / 4.20:1) while white/surface-muted stay green (4.76:1 /
    // 4.55:1 — right at the edge, which is exactly why the multi-surface
    // fix mattered); reverting --color-success to #059669 turns both
    // success-fill rows red (3.77:1); nothing else moves.
    it.each([
      ['text-secondary on surface-panel', token('--color-text-secondary'), token('--color-surface'), 4.5],
      // fix/ui-contrast-tokens (2026-07-28): --color-text-muted moved from
      // #64748b (only ~4.76:1 on pure white; measured as low as 3.93:1 on
      // this app's actual light surfaces — workspace, surface-subtle,
      // #efefef) to #5c6b80, which clears 4.5+ on every light surface it's
      // used on. Still fails on the dark sidebar rail (~3.45:1) — text
      // there uses --color-text-faint instead (Sidebar.jsx's inline
      // `text-text-faint` utility on the PRODUCT_PORTAL_LABEL `<small>`,
      // ported off styles.css's former `.brand small` rule).
      ['text-muted floor on surface-panel (white)', token('--color-text-muted'), token('--color-surface'), 4.5],
      ['text-muted floor on surface-muted', token('--color-text-muted'), token('--color-surface-muted'), 4.5],
      ['text-muted floor on surface-subtle', token('--color-text-muted'), token('--color-surface-subtle'), 4.5],
      ['text-muted floor on workspace bg', token('--color-text-muted'), token('--color-bg'), 4.5],
      ['text-inverse on action-primary', token('--color-surface'), token('--color-primary'), 4.5],
      ['action-danger text on surface-panel', token('--color-danger'), token('--color-surface'), 4.5],
      ['status-warning text on warning bg', token('--color-warning'), token('--color-warning-bg'), 4.5],
      // .status-danger pairs danger-bg with danger-dark, not the plain
      // danger action color (styles.css :695-735) — matches actual CSS.
      ['status-danger text on danger bg', token('--color-danger-dark'), token('--color-danger-bg'), 4.5],
      ['status-success text on success bg', token('--color-success-dark'), token('--color-success-bg'), 4.5],
      ['status-info text on info bg', token('--color-info'), token('--color-info-bg'), 4.5],
      ['link on surface-panel', token('--color-link'), token('--color-surface'), 4.5],
      // fix/ui-contrast-tokens (2026-07-28): --color-success moved from
      // #059669 to #047857 specifically because white-on-success-fill (the
      // "อนุมัติ" Approve button on /requests) only cleared 3.77:1 — this
      // pairing used to be a documented large/bold-text-only exception
      // (see the removed 'borderline pairing' test below this array in git
      // history) and now clears the full body-text floor like every other
      // pairing in this list.
      ['action-success (white on success fill)', token('--color-surface'), token('--color-success'), 4.5],
      // Same token, other direction — --color-success used as plain text
      // (not a button fill) on a light surface, e.g. .event-dot.success and
      // any future success-toned label.
      ['success text on surface-panel', token('--color-success'), token('--color-surface'), 4.5],
      // Ink Faint is deliberately allowed as TEXT only on the dark sidebar
      // rail (see index.css's --color-text-faint comment) — this is the one
      // pairing where Faint-as-text is correct, not a violation.
      ['text-faint on sidebar rail', token('--color-text-faint'), token('--color-sidebar-bg'), 4.5],
      // --color-accent-dark backs the sidebar unread badge label and the
      // ImportOverview teal pulse numeral, both on light surfaces.
      ['accent-dark text on surface-panel', token('--color-accent-dark'), token('--color-surface'), 4.5],
      // `%s` fills positionally, so a second `%s` would print `fg` (a hex),
      // not `min` — e.g. "clears #64748b:1". Name by label only; the assertion
      // message already reports the actual ratio when a row fails.
    ])('%s clears its documented floor', (_label, fg, bg, min) => {
      expect(contrastRatio(fg, bg)).toBeGreaterThanOrEqual(min);
    });
  });
});
