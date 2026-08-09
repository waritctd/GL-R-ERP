import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';

// Guard for the global base layer moved out of styles.css into index.css's `@layer base`.
//
// WHAT THIS REPLACES, AND WHAT IT DOES NOT. Four declarations in this layer each fix a measured,
// reproducible visual defect (the reasoning lives on each rule in index.css). They used to be
// guarded by `e2e/form-field-alignment.spec.js` (5 tests) and `e2e/panel-header-spacing.spec.js`
// (2) -- real-browser geometry assertions, every one mutation-checked. PR #592 deleted the entire
// mock e2e suite on 2026-08-08 and neither spec was carried into `e2e-real/`, so those seven
// assertions are gone and nothing replaced them.
//
// This file is NOT an equivalent replacement and must not be read as one. It pins the SOURCE TEXT,
// not the rendered result: jsdom has no layout engine, so nothing in this suite can observe a
// control's height, a specificity fight, or a glyph landing on top of a value. Same technique, and
// same justification, as `src/breakpointVariants.test.js`.
//
// The rendered result is covered by `frontend/e2e-visual/visual-baseline.spec.js`, a full-page
// pixel diff at 3 viewports x 5 roles. That harness is opt-in (VISUAL_BASELINE=1) and deliberately
// NOT in CI, per #592's one-e2e-job-per-PR ruling -- so on any given PR what runs automatically is
// THIS file and nothing stronger. If you change a rule below, run the harness locally.
//
// NOTE ON TECHNIQUE: the assertions below run against a COMMENT-STRIPPED copy, and address rules
// through a parsed selector->body map rather than by regex over raw text. Both matter. The comments
// on these rules quote the very anti-patterns being forbidden (the `input:not(...)` chain is spelled
// out verbatim in the explanation of why it is wrong), so a raw-text scan flags the documentation
// as a violation. And the 40px rule's selector list ENDS with `textarea`, so a naive
// `/textarea\s*\{/` matches that rule instead of the standalone `textarea` rule three lines later.

const SRC = path.dirname(fileURLToPath(import.meta.url));
const INDEX_CSS = fs.readFileSync(path.join(SRC, 'index.css'), 'utf8');
const STYLES_CSS = fs.readFileSync(path.join(SRC, 'styles.css'), 'utf8');

const stripComments = (css) => css.replace(/\/\*[\s\S]*?\*\//g, '');

// Body of the `@layer base { ... }` block, comments removed. Brace-matched, not regexed: the block
// contains nested rules and a nested @media, so `[^}]*` stops at the first inner brace.
function layerBaseBody(css) {
  const at = css.indexOf('@layer base');
  if (at === -1) return null;
  const open = css.indexOf('{', at);
  let depth = 0;
  for (let i = open; i < css.length; i += 1) {
    if (css[i] === '{') depth += 1;
    else if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) return css.slice(open + 1, i);
    }
  }
  return null;
}

// Top-level rules inside a block, in source order: [{ selector, body }]. Order is what two of the
// assertions below are actually about, so the array index IS the assertion.
function topLevelRules(block) {
  const rules = [];
  let depth = 0;
  let selectorStart = 0;
  let bodyStart = -1;
  for (let i = 0; i < block.length; i += 1) {
    if (block[i] === '{') {
      if (depth === 0) bodyStart = i;
      depth += 1;
    } else if (block[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        rules.push({
          selector: block.slice(selectorStart, bodyStart).trim().replace(/\s+/g, ' '),
          body: block.slice(bodyStart + 1, i),
        });
        selectorStart = i + 1;
      }
    }
  }
  return rules;
}

const BASE = layerBaseBody(stripComments(INDEX_CSS));
const RULES = BASE ? topLevelRules(BASE) : [];
const find = (pred) => RULES.findIndex(pred);

// Always reach a rule through this, never `RULES[find(...)]` directly. `findIndex` returns -1 when
// a rule is missing, and `RULES[-1]` is `undefined`, so destructuring it throws a TypeError from
// whatever line happens to do the destructuring -- which reads as an unrelated failure and makes a
// mutation check ambiguous about WHICH assertion actually caught the defect. Failing here instead
// says "the rule is gone" once, in the right place.
function ruleAt(index, label) {
  expect(index, `no \`${label}\` rule found in @layer base`).toBeGreaterThan(-1);
  return RULES[index];
}

const FORM_CONTROL = find((r) => r.selector.startsWith('input:where('));
const CHECKBOX_RESET = find((r) => r.selector.includes("input[type='checkbox']"));
const LABEL = find((r) => r.selector === 'label');
const TEXTAREA = find((r) => r.selector === 'textarea');
const MOBILE_OVERRIDE = find((r) => r.selector.startsWith('@media') && r.selector.includes('720px'));

describe('@layer base — the global base layer', () => {
  it('exists in index.css and parses into top-level rules', () => {
    expect(BASE).toBeTruthy();
    expect(RULES.length).toBeGreaterThan(5);
  });

  it('is declared between theme and legacy, so base rules keep LOSING to styles.css class rules', () => {
    // Load-bearing. These rules lived in styles.css beside the class rules that override them and
    // lost to those on selector specificity. `base` before `legacy` reproduces that relationship by
    // layer order instead. Move `base` after `legacy` and every bare-element default in it starts
    // beating every class rule in styles.css -- an app-wide regression, not a local one.
    //
    // Asserted against the COMMENT-STRIPPED source, and this is not incidental: index.css quotes
    // this exact statement inside the `:root` comment explaining the token de-duplication. Run
    // against raw text, the assertion is satisfied by that quotation even when the real statement
    // has been inverted -- i.e. it cannot fail. Caught by mutation-checking this very test.
    expect(stripComments(INDEX_CSS)).toMatch(/@layer\s+theme,\s*base,\s*legacy,\s*utilities\s*;/);
  });
});

describe('the four load-bearing declarations', () => {
  it('1. excludes checkbox/radio via :where(), never a bare :not() chain', () => {
    // `:where()` contributes ZERO specificity, keeping the selector at (0,0,1) -- exactly what the
    // original bare `input` scored. A bare `input:not([type='checkbox']):not([type='radio'])`
    // scores (0,2,1), which outranks `.currency-input input` (0,1,1); the base `padding: 0 12px`
    // then beats the 34px inset those wrappers use to clear their symbol, and the ฿ glyph renders
    // on top of the value (`฿0000` instead of `฿ 60000`).
    expect(ruleAt(FORM_CONTROL, 'input:where(...)').selector).toContain(':where(:not(');
    // no rule in the layer may reach for the bare :not() chain
    expect(RULES.some((r) => /input:not\(\[type=/.test(r.selector))).toBe(false);
  });

  it('2. sizes form controls with a fixed height, never min-height', () => {
    // A fixed-height control cannot absorb a stretched grid/flex row's spare height, so it stays
    // aligned with its neighbour. Under `min-height` the field WITHOUT a hint line got the TALLER
    // control -- the app-wide field stagger.
    const { body } = ruleAt(FORM_CONTROL, 'input:where(...)');
    expect(body).toMatch(/(^|[\s;])height:\s*40px/);
    expect(body).not.toMatch(/min-height/);
  });

  it('3. resets native checkbox/radio out of the full-width padded box', () => {
    // Without this they inherit `width: 100%` and the 40px padded box: a bare radio rendered as a
    // full-width bar with its label pushed off screen (measured 668px on the ล.ย.01 form).
    const { selector, body } = ruleAt(CHECKBOX_RESET, "input[type='checkbox']");
    expect(selector).toContain("input[type='radio']");
    expect(body).toMatch(/width:\s*16px/);
    expect(body).toMatch(/height:\s*16px/);
    expect(body).toMatch(/padding:\s*0/);
  });

  it('4. packs the global label grid to the start', () => {
    // ~185 hand-rolled field wrappers across src/features inherit this rule to stack label text
    // above their control. Where such a label is itself a stretched grid/flex item -- the common
    // case, since `align-items` defaults to `stretch` -- the row's spare height is distributed into
    // its auto rows, growing the CONTROL rather than leaving whitespace below it.
    const { body } = ruleAt(LABEL, 'label');
    expect(body).toMatch(/align-content:\s*start/);
    expect(body).toMatch(/display:\s*grid/);
  });
});

describe('ordering invariants inside @layer base', () => {
  it('keeps the ≤720px 44px override LAST, after the 40px rule it overrides', () => {
    // Same layer, same specificity (0,0,1) -- source order is the ONLY thing making the override
    // win at ≤720px. Moved above the 40px rule it silently stops applying and the mobile
    // touch-target floor dies with no test, lint error, or build warning.
    const override = ruleAt(MOBILE_OVERRIDE, '@media (max-width: 720px)');
    expect(MOBILE_OVERRIDE).toBeGreaterThan(FORM_CONTROL);
    expect(MOBILE_OVERRIDE).toBe(RULES.length - 1);
    expect(override.body).toMatch(/height:\s*44px/);
  });

  it('keeps the standalone textarea rule after the shared 40px rule', () => {
    // `textarea` is the one control that must still grow; it re-opens the height the 40px rule
    // pins. Ordered before that rule, the 40px wins and every textarea collapses to one line.
    const textarea = ruleAt(TEXTAREA, 'textarea');
    expect(TEXTAREA).toBeGreaterThan(FORM_CONTROL);
    expect(textarea.body).toMatch(/height:\s*auto/);
  });
});

describe('token de-duplication', () => {
  it('never re-declares the non-color tokens in styles.css', () => {
    // styles.css's `:root` used to redeclare all 29 of these, duplicating index.css's
    // `@theme static`. Because that copy sat in `@layer legacy` -- ordered AFTER `@layer theme` --
    // it was the one the cascade actually served, so the `@theme static` values were silently dead.
    // Re-introducing any of them resurrects exactly that bug. Verified byte-identical before
    // removal, so this was a de-duplication, not a value change.
    const declarations = stripComments(STYLES_CSS).match(
      /--(?:font-family|text|space|radius|shadow)[\w-]*\s*:/g,
    );
    expect(declarations).toBeNull();
  });

  it('declares each of them exactly once, and that once is index.css', () => {
    // Non-vacuity, stated durably. The obvious formulation -- "styles.css still CONSUMES
    // var(--radius-md)" -- is what this originally said, and it is a trap: styles.css is being
    // retired, so a consumption assertion snaps the moment the last consumer is ported, for a
    // reason that has nothing to do with the invariant. That exact failure hit
    // payrollResponsiveCss.test.js's `toContain('var(--shadow-focus-ring)')` guard two commits
    // apart -- the InfoTip port removed one consumer, the CollapsibleSection port removed the last.
    //
    // Asserting a positive count in index.css cannot go vacuous: if the token disappeared entirely
    // the count would be 0 and this fails. And it keeps holding when styles.css reaches zero rules,
    // which is the whole point of the programme this test belongs to.
    const declarations = (css, token) =>
      stripComments(css).match(new RegExp(`(^|[;{\\s])${token}\\s*:`, 'g')) ?? [];

    for (const token of ['--font-family', '--text-sm', '--space-4', '--radius-md', '--shadow-md']) {
      expect(declarations(INDEX_CSS, token), `${token} must be declared in index.css`).toHaveLength(1);
      expect(declarations(STYLES_CSS, token), `${token} must NOT be declared in styles.css`).toHaveLength(0);
    }
  });
});
