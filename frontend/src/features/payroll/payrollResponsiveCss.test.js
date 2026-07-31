import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// B1/B3 regression guard (Opus review, 2026-07-31): both bugs were pure CSS -- a stray
// `max-width: 1500px` breakpoint that stacked the detail panel below the table (`position: static`)
// instead of beside it, and a `(min-width: 721px) and (max-width: 1040px)` card-reflow that turned
// the payroll table into 2-column cards in a band where it already fits as a real table. Neither is
// reachable from a jsdom component render (jsdom does not evaluate `@media` against a viewport
// width for layout), so this reads styles.css itself and pins the breakpoints down textually.

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rawStylesCss = fs.readFileSync(path.resolve(__dirname, '../../styles.css'), 'utf8');
const rawIndexCss = fs.readFileSync(path.resolve(__dirname, '../../index.css'), 'utf8');
// Comments are stripped before every check below -- this file's own comments narrate the fix in
// terms of the breakpoints/selectors that were REMOVED (e.g. "the previous `max-width: 1500px`
// breakpoint"), which would otherwise trip a naive `.not.toContain` on the prose describing the fix,
// not the fix itself.
const stylesCss = rawStylesCss.replace(/\/\*[\s\S]*?\*\//g, '');
const indexCss = rawIndexCss.replace(/\/\*[\s\S]*?\*\//g, '');

// Returns the bodies of every `@media (...)` block whose header contains `headerSubstring`, by
// counting braces from each block's opening `{` (one level of nested rule blocks is expected).
function extractMediaBlocks(css, headerSubstring) {
  const blocks = [];
  let searchFrom = 0;
  for (;;) {
    const headerIndex = css.indexOf(headerSubstring, searchFrom);
    if (headerIndex === -1) break;
    const openBraceIndex = css.indexOf('{', headerIndex);
    let depth = 0;
    let endIndex = -1;
    for (let i = openBraceIndex; i < css.length; i += 1) {
      if (css[i] === '{') depth += 1;
      if (css[i] === '}') {
        depth -= 1;
        if (depth === 0) {
          endIndex = i;
          break;
        }
      }
    }
    if (endIndex === -1) throw new Error(`Unbalanced braces reading @media block at "${headerSubstring}"`);
    blocks.push(css.slice(openBraceIndex + 1, endIndex));
    searchFrom = endIndex + 1;
  }
  return blocks;
}

describe('payroll responsive CSS (B1/B3 regression guard)', () => {
  it('never reintroduces the 1500px three-state breakpoint for the detail panel', () => {
    expect(stylesCss).not.toContain('max-width: 1500px');
  });

  it('switches the detail panel to its overlay presentation at exactly the 1440px floor, not 1040px', () => {
    // Item 1 fix (2026-07-31): raised from 1280px (Tailwind's built-in `xl:`) to 1440px (this app's
    // own `payroll-wide:` custom variant, see index.css) -- real-browser measurement showed 1280px
    // still hid the "สุทธิ" (net pay) column behind a horizontal scrollbar with the panel open.
    const overlayBlocks = extractMediaBlocks(stylesCss, '@media (max-width: 1439px)');
    expect(overlayBlocks).toHaveLength(1);
    expect(overlayBlocks[0]).toContain('.payroll-detail-panel');
    expect(overlayBlocks[0]).toContain('position: fixed');

    // The old 1279px complement must be gone outright, not left behind as a second, now-dead block.
    expect(stylesCss).not.toContain('@media (max-width: 1279px)');

    // The panel must not still be governed by the generic ≤1040px nav-drawer band -- that band
    // legitimately still exists for unrelated rules (sidebar drawer, other tables' scroll trap).
    const navDrawerBlocks = extractMediaBlocks(stylesCss, '@media (max-width: 1040px)');
    expect(navDrawerBlocks.length).toBeGreaterThan(0);
    navDrawerBlocks.forEach((block) => {
      expect(block).not.toContain('.payroll-detail-panel');
    });
  });

  it('declares the payroll-wide floor as a named custom variant in index.css, at 1440px', () => {
    // CLAUDE.md's styling direction requires a new breakpoint value to be a named custom variant in
    // index.css (the single source of truth for this app's responsive bands), not a bare
    // `min-[1440px]:` literal scattered at call sites -- see the `mobile:`/`tablet:`/`nav-drawer:`
    // variants already declared there.
    const variantBlocks = extractMediaBlocks(indexCss, '@custom-variant payroll-wide');
    expect(variantBlocks).toHaveLength(1);
    expect(variantBlocks[0]).toContain('@media (min-width: 1440px)');
  });

  it('does not reflow the payroll table into cards in the 721-1040px tablet band', () => {
    const tabletBlocks = extractMediaBlocks(stylesCss, '(min-width: 721px) and (max-width: 1040px)');
    // Sanity: this band still legitimately exists for the ticket worklist table -- the assertion
    // below must be about `.payroll-table` specifically, not "the band is gone".
    expect(tabletBlocks.length).toBeGreaterThan(0);
    tabletBlocks.forEach((block) => {
      expect(block).not.toMatch(/\.payroll-table\b/);
    });
  });

  it('does not force the payroll table wider than its natural column width in the ≤1040px band', () => {
    const navDrawerBlocks = extractMediaBlocks(stylesCss, '@media (max-width: 1040px)');
    const combined = navDrawerBlocks.join('\n');
    // Sanity: other tables still get the deliberate 900px horizontal-scroll floor.
    expect(combined).toContain('min-width: 900px');
    expect(combined).not.toMatch(/\.payroll-table\b/);
  });
});

// Defect 2 regression guard (Opus review, 2026-07-31): the detail panel's side-by-side track was
// narrowed from 357px to a 300px floor by the B1 fix above, which squeezed every special-pay money
// input to 107px and wrapped every Thai label to 3 lines -- a quality regression, not a break. The
// fix restores the floor to 340px (the value the B1 comment already claimed) and moves the two
// money-field grids to a Tailwind v4 `@container` query so they collapse to 1 column whenever the
// PANEL itself is narrow, rather than staying pinned to 2 columns at every width. None of this is
// reachable from a jsdom render (jsdom has no layout engine, so container queries never evaluate),
// so -- same as the B1/B3 guard above -- this pins the source text down directly.
describe('payroll detail panel container-query grids (Defect 2 regression guard)', () => {
  const rawPayrollPageJsx = fs.readFileSync(path.resolve(__dirname, './PayrollPage.jsx'), 'utf8');
  // Strip the same way as styles.css above: this file's own comments narrate the fix in terms of the
  // widths/selectors it moved away from (e.g. "dropped to 300px"), which would otherwise trip a
  // naive `.not.toContain` on the prose describing the fix, not the fix itself.
  const payrollPageJsx = rawPayrollPageJsx.replace(/\/\*[\s\S]*?\*\//g, '');

  it('keeps the >=1440px side panel track at its 340px floor, not the narrowed 300px', () => {
    expect(payrollPageJsx).not.toContain('minmax(300px');
    // Item 1 fix (2026-07-31): `payroll-wide:` (this app's own 1440px custom variant, see
    // index.css), not Tailwind's built-in `xl:` (1280px) -- real-browser measurement showed 1280px
    // still hid the "สุทธิ" net-pay column behind a scrollbar with the panel open.
    expect(payrollPageJsx).toContain('payroll-wide:grid-cols-[minmax(0,1fr)_minmax(340px,0.35fr)]');
    expect(payrollPageJsx).not.toMatch(/\bxl:grid-cols-/);
  });

  it('mirrors the 1440px payroll-wide floor in the JS-side useMediaQuery check', () => {
    // The CSS `payroll-wide:` variant and this JS check must never be able to drift -- both must
    // name the exact same number (index.css's `@custom-variant payroll-wide` test above pins the CSS
    // side down).
    expect(payrollPageJsx).toContain("useMediaQuery('(min-width: 1440px)')");
    expect(payrollPageJsx).not.toContain("useMediaQuery('(min-width: 1280px)')");
  });

  it('makes the detail panel a container-query context', () => {
    // `@container` (Tailwind v4, no plugin) on the `<aside>` itself -- the two money-field grids
    // below key their column count off THIS element's width, not the viewport's, since the panel
    // is a sticky side column at >=1440px and a fixed-width overlay below that.
    expect(payrollPageJsx).toMatch(/className=\{cn\(PANEL_CLASS, 'payroll-detail-panel', '@container'/);
  });

  it('collapses both money-field grids to 1 column below the panel container-query floor', () => {
    // Both payroll-detail-grid (the 4 MiniMetric cards) and payroll-special-grid (the 9 special-pay
    // inputs the review measured at 107px) get the same treatment: 1 column by default, 2 only once
    // the panel container has room per Tailwind's `@sm` (24rem/384px) content-box floor.
    const detailGridMatches = payrollPageJsx.match(/payroll-detail-grid grid grid-cols-1 gap-2\.5 @sm:grid-cols-2/g) || [];
    const specialGridMatches = payrollPageJsx.match(/payroll-special-grid grid grid-cols-1 gap-2\.5 @sm:grid-cols-2/g) || [];
    expect(detailGridMatches).toHaveLength(1);
    expect(specialGridMatches).toHaveLength(1);
  });

  it('no longer pins payroll-detail-grid/payroll-special-grid to an unconditional 2-column grid in styles.css', () => {
    // The column count now lives entirely in the Tailwind classes above -- a leftover
    // `grid-template-columns: repeat(2, ...)` for either class here would be dead weight at best
    // (styles.css is `@layer legacy` and always loses to a Tailwind utility on the same element) and
    // a silent reversion at worst if the Tailwind classes were ever dropped without noticing this
    // stylesheet still pins the columns.
    expect(stylesCss).not.toMatch(/\.payroll-detail-grid\b/);
    expect(stylesCss).not.toMatch(/\.payroll-special-grid\b/);
  });

  it('does not leave a dead ≤720px override for the two grids that no longer set grid-template-columns here', () => {
    const phoneBlocks = extractMediaBlocks(stylesCss, '@media (max-width: 720px)');
    expect(phoneBlocks.length).toBeGreaterThan(0);
    phoneBlocks.forEach((block) => {
      expect(block).not.toMatch(/\.payroll-detail-grid\b/);
      expect(block).not.toMatch(/\.payroll-special-grid\b/);
    });
  });
});

// Item 2 regression guard (2026-07-31): the footer grand-total money cell (e.g. "฿1,021,720.56")
// measured ~127px wide in this app's monospace tabular-nums font, wider than the 120px money-column
// floor every row shares -- `scrollWidth` (127) exceeded `clientWidth` (120) with the excess painted
// into the inter-column gap. Not reachable from a jsdom render (no layout engine), so this pins the
// CSS fix down textually, same pattern as the B1/B3/Defect-2 guards above.
describe('payroll totals-footer money cell sizing (Item 2 regression guard)', () => {
  it('lets the total row money cell size to its own content instead of staying pinned at the shared 120px floor', () => {
    expect(stylesCss).toMatch(
      /\.payroll-total-row\.payroll-table > \.payroll-money-cell\s*{[^}]*justify-self:\s*end;[^}]*width:\s*max-content;/,
    );
  });

  it('does not widen ordinary data-row/table-head money cells the same way -- Item 2 is about the footer only', () => {
    // `.payroll-table.data-row > .payroll-money-cell` and `.payroll-table.table-head > .payroll-money-cell`
    // (the truncation-defeat overrides a few lines above the Item 2 fix) must still be the plain
    // `overflow: visible` rule from B4, not additionally carry `width: max-content` -- individual line
    // amounts were not observed overflowing, and widening every row's cells would be a wider,
    // unrequested change.
    const dataRowBlockMatch = stylesCss.match(/\.payroll-table\.table-head > \.payroll-money-cell,\s*\.payroll-table\.data-row > \.payroll-money-cell,\s*\.payroll-table\.payroll-total-row > \.payroll-money-cell\s*{([^}]*)}/);
    expect(dataRowBlockMatch).toBeTruthy();
    expect(dataRowBlockMatch[1]).not.toContain('width: max-content');
  });

  it('never lets the total row fall back to ellipsis-clipping money instead', () => {
    // The pre-existing "money must never truncate" rule this fix builds on top of, still intact.
    expect(stylesCss).toMatch(/\.payroll-table \.payroll-money\s*{[^}]*overflow:\s*visible;[^}]*text-overflow:\s*clip;/);
  });
});

// Item 4a regression guard (2026-07-31): --shadow-focus-ring composited to 1.18:1 on white (WCAG
// 1.4.11/2.4.11 need >=3:1) and was declared TWICE (index.css and styles.css) -- both had to change
// or the cascade would keep serving the old value from whichever one wins. Not reachable from jsdom
// (no rendering engine to composite box-shadow colors against a real background), so this pins both
// declarations down textually.
describe('focus-ring contrast token (Item 4a regression guard)', () => {
  it('uses the solid --color-indigo-ring token, not the old low-contrast rgba alpha, in BOTH index.css and styles.css', () => {
    expect(indexCss).toContain('--shadow-focus-ring: 0 0 0 3px var(--color-indigo-ring);');
    expect(stylesCss).toContain('--shadow-focus-ring: 0 0 0 3px var(--color-indigo-ring);');
    expect(indexCss).not.toContain('rgba(99, 102, 241, 0.13)');
    expect(stylesCss).not.toContain('rgba(99, 102, 241, 0.13)');
  });

  it('keeps --color-indigo-ring itself the known-good #2563eb (5.17:1 on white)', () => {
    expect(indexCss).toContain('--color-indigo-ring: #2563eb;');
  });
});

// Item 4b regression guard (2026-07-31): `variant="icon" size="sm"` resolves to a 36x36
// compound-variant box in Button.jsx -- too small for the detail panel's primary dismiss control.
// Fixed at the payroll call site only (via `cn`'s twMerge override), not in the shared compound
// variant, since that variant is also used by DataTable's pagination arrows and TicketListPage's
// filter-panel close button.
describe('payroll detail panel close button touch target (Item 4b regression guard)', () => {
  const rawPayrollPageJsx = fs.readFileSync(path.resolve(__dirname, './PayrollPage.jsx'), 'utf8');
  const payrollPageJsx = rawPayrollPageJsx.replace(/\/\*[\s\S]*?\*\//g, '');

  it('overrides the close button to >=44x44 at the call site', () => {
    expect(payrollPageJsx).toContain('className="payroll-detail-close w-11 min-h-[44px]"');
  });

  it('does not touch the shared icon+sm compound variant in Button.jsx', () => {
    const rawButtonJsx = fs.readFileSync(path.resolve(__dirname, '../../components/common/Button.jsx'), 'utf8');
    expect(rawButtonJsx).toContain("className: 'w-9 min-h-[36px] p-0'");
  });
});

// Item 4c regression guard (2026-07-31): the InfoTip trigger's visible badge is a deliberate 16px
// circle (there are ~11 of these in the payroll detail panel; a 44px glyph on every field label
// would be visual noise), so the touch-target fix has to grow the CLICKABLE area via an invisible
// hit-slop rather than the visible badge itself.
describe('InfoTip trigger hit-slop (Item 4c regression guard)', () => {
  it('keeps the visible badge small (16x16) and anchors an invisible hit-slop to it', () => {
    expect(stylesCss).toMatch(/\.info-tip-trigger\s*{[^}]*position:\s*relative;[^}]*width:\s*16px;[^}]*height:\s*16px;/);
  });

  it('grows the clickable area to >=44x44 via an invisible ::before, not a bigger visible circle', () => {
    // 16px visible + 14px inset on each side = 44px hit area.
    expect(stylesCss).toMatch(/\.info-tip-trigger::before\s*{[^}]*content:\s*'';[^}]*position:\s*absolute;[^}]*inset:\s*-14px;/);
  });
});
