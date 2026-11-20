import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';

// Source guard for the global `@layer base` in index.css (the rules moved out of styles.css): layer order,
// the form-control height rule and the 720px override ordering. Not a render test: jsdom has no layout.
const SRC = path.dirname(fileURLToPath(import.meta.url));
const INDEX_CSS = fs.readFileSync(path.join(SRC, 'index.css'), 'utf8');

const stripComments = (css) => css.replace(/\/\*[\s\S]*?\*\//g, '');

// Body of the `@layer base { ... }` block, comments removed. Brace-matched, not regexed: the block contains
// nested rules and a nested @media, so `[^}]*` stops at the first inner brace.
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

// Top-level rules inside a block, in source order: [{ selector, body }]. Order is what two of the assertions
// below are actually about, so the array index IS the assertion.
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

// Always reach a rule through this, never `RULES[find(...)]` directly. Failing here instead says "the rule is
// gone" once, in the right place.
function ruleAt(index, label) {
  expect(index, `no \`${label}\` rule found in @layer base`).toBeGreaterThan(-1);
  return RULES[index];
}

const FORM_CONTROL = find((r) => r.selector.startsWith('input:where('));
const MOBILE_OVERRIDE = find((r) => r.selector.startsWith('@media') && r.selector.includes('720px'));

describe('@layer base — the global base layer', () => {
  it('is declared between theme and legacy, so base rules keep LOSING to styles.css class rules', () => {
    // Assert base precedes legacy using comment-stripped CSS so defaults cannot override component classes.
    expect(stripComments(INDEX_CSS)).toMatch(/@layer\s+theme,\s*base,\s*legacy,\s*utilities\s*;/);
  });
});

describe('the four load-bearing declarations', () => {
  it('1. excludes checkbox/radio via :where(), never a bare :not() chain', () => {
    // `:where()` contributes ZERO specificity, keeping the selector at (0,0,1) -- exactly what the original bare
    // `input` scored.
    expect(ruleAt(FORM_CONTROL, 'input:where(...)').selector).toContain(':where(:not(');
    // no rule in the layer may reach for the bare :not() chain
    expect(RULES.some((r) => /input:not\(\[type=/.test(r.selector))).toBe(false);
  });

  it('2. sizes form controls with a fixed height, never min-height', () => {
    // A fixed-height control cannot absorb a stretched grid/flex row's spare height, so it stays aligned with its
    // neighbour. Under `min-height` the field WITHOUT a hint line got the TALLER control -- the app-wide field
    // stagger.
    const { body } = ruleAt(FORM_CONTROL, 'input:where(...)');
    expect(body).toMatch(/(^|[\s;])height:\s*40px/);
    expect(body).not.toMatch(/min-height/);
  });
});

describe('ordering invariants inside @layer base', () => {
  it('keeps the ≤720px 44px override LAST, after the 40px rule it overrides', () => {
    // Same layer, same specificity (0,0,1) -- source order is the ONLY thing making the override win at ≤720px.
    // Moved above the 40px rule it silently stops applying and the mobile touch-target floor dies with no test,
    // lint error, or build warning.
    const override = ruleAt(MOBILE_OVERRIDE, '@media (max-width: 720px)');
    expect(MOBILE_OVERRIDE).toBeGreaterThan(FORM_CONTROL);
    expect(MOBILE_OVERRIDE).toBe(RULES.length - 1);
    expect(override.body).toMatch(/height:\s*44px/);
  });
});
