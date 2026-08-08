import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';

// Guard for #exclusive-breakpoint-variant (2026-08-08).
//
// Tailwind's arbitrary `max-[Npx]` variant is EXCLUSIVE of its own boundary: it compiles to
// `(width < Npx)`, which the production build ships as `not all and (width>=Npx)`. Both are FALSE
// at exactly Npx. This app reads the same bands INCLUSIVELY everywhere else -- useIsMobile.js's
// MOBILE_QUERY is `(max-width: 720px)`, and styles.css uses `@media (max-width: 720px)` -- so the
// CSS and the JS disagreed at exactly the boundary pixel, and a `grid-cols-2` + narrow-band-override
// pair fell through to its wide base there.
//
// Measured on the real production bundle before the migration: AccountOverview's stat grid went
// 2 cols at 719px -> 3 cols at 720px, and 3 cols at 1039px -> 5 cols at 1040px; every Button lost
// its 44px mobile touch target at exactly 720px.
//
// WHY A SOURCE SCAN AND NOT A RENDER TEST: jsdom has no layout engine and never evaluates a media
// query, so no amount of `render()` in this suite can observe a breakpoint at all. Browser testing
// misses it too, because it never lands on the exact boundary pixel. Pinning the source text is the
// only check available -- same technique, and same reason, as payrollResponsiveCss.test.js.
//
// NOTE: this file writes the forbidden pattern only as a regex (`max-\[\d+px\]` + a colon), never as
// a literal, so the scan below can include this file without matching itself.

const SRC = path.dirname(fileURLToPath(import.meta.url));
const INDEX_CSS = fs.readFileSync(path.join(SRC, 'index.css'), 'utf8');

// The one file still carrying the old variant, and the only permitted exemption. PR #587
// (feat/ot-holiday-visibility) is concurrently adding ~123 lines to it, and at 10 occurrences it is
// the densest call site in the app, so the migration skipped it rather than force a hand-resolved
// conflict. Delete this entry and the matching eslint.config.js block together when #587 lands.
const EXEMPT = ['features/overtime/OvertimePanel.jsx'];

const EXCLUSIVE_VARIANT = new RegExp(`max-\\[\\d+px\\]${':'}`);

function sourceFiles(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) sourceFiles(p, out);
    else if (/\.jsx?$/.test(entry.name)) out.push(p);
  }
  return out;
}

describe('inclusive breakpoint variants', () => {
  it('has no exclusive max-width variant left in src, outside the documented exemption', () => {
    const offenders = sourceFiles(SRC)
      .filter((f) => EXCLUSIVE_VARIANT.test(fs.readFileSync(f, 'utf8')))
      .map((f) => path.relative(SRC, f))
      .sort();

    expect(offenders).toEqual([...EXEMPT].sort());
  });

  it('keeps the exemption to exactly one file', () => {
    // A widening exemption list is how this defect comes back at scale: the whole point of the
    // migration is that there is one known-bad file, not a growing allowlist.
    expect(EXEMPT).toHaveLength(1);
  });

  it('declares the pure max-width variants widest-first, so the narrower band wins', () => {
    // LOAD-BEARING. Tailwind sorts its own built-in `max-*` variants widest-first so the narrower
    // band wins. `@custom-variant`s get no such treatment -- they emit in the order declared in
    // index.css, and same-layer, same-specificity utilities are won by whichever is emitted LAST.
    // Declaring `mobile` (<=720) before `nav-drawer` (<=1040) would make the WIDER band win below
    // 720px and silently invert every element that sets one property in both bands -- 15 files do,
    // including AccountOverview.jsx and Layout.jsx's StatGrid.
    //
    // Only variants whose media query is a bare `(max-width: N)` are ordered here: they form a
    // nested chain, so "narrower declared later" is exactly "descending N". `tablet` (a bounded
    // 721-1040 band) and `payroll-wide` (a min-width variant) are deliberately excluded -- neither
    // nests in that chain, and neither shares a property with it at any call site.
    const declared = [...INDEX_CSS.matchAll(/@custom-variant\s+([\w-]+)\s*\{\s*@media\s*([^{]+)\{/g)]
      .map(([, name, query]) => ({ name, query: query.trim() }));

    expect(declared.length).toBeGreaterThan(0);

    const pureMax = declared
      .map(({ name, query }) => {
        const m = /^\(max-width:\s*(\d+)px\)$/.exec(query);
        return m ? { name, width: Number(m[1]) } : null;
      })
      .filter(Boolean);

    expect(pureMax.map((v) => v.name)).toEqual([
      'nav-drawer',
      'attendance-scroll',
      'mobile',
      'mobile-sm',
      'mobile-xs',
    ]);

    const widths = pureMax.map((v) => v.width);
    expect(widths).toEqual([...widths].sort((a, b) => b - a));
    expect(new Set(widths).size).toBe(widths.length);
  });

  it('keeps the mobile variant byte-identical to useIsMobile.js MOBILE_QUERY', () => {
    // This equality IS the bug that was fixed: the CSS band and the JS band must resolve the same
    // way at every width, including the boundary. If one moves, the other has to move with it.
    const hook = fs.readFileSync(path.join(SRC, 'hooks/useIsMobile.js'), 'utf8');
    const jsQuery = /MOBILE_QUERY\s*=\s*'([^']+)'/.exec(hook)?.[1];

    expect(jsQuery).toBe('(max-width: 720px)');
    expect(INDEX_CSS).toMatch(
      new RegExp(`@custom-variant\\s+mobile\\s*\\{\\s*@media\\s*\\(max-width:\\s*720px\\)`),
    );
  });
});
