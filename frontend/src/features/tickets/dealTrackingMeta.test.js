import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  WIN_PROBABILITY_DEFAULTS, winProbabilityDefault,
} from './dealTrackingMeta.js';

const DEAL_STAGE_JAVA = 'backend/src/main/java/th/co/glr/hr/ticket/DealStage.java';
const WIN_PROBABILITY_JAVA = 'backend/src/main/java/th/co/glr/hr/ticket/WinProbabilityDefaults.java';

/** Walk up from the working directory to the repo root and read a backend source file. */
function readBackendSource(relativePath) {
  let dir = process.cwd();
  for (let depth = 0; depth < 8; depth += 1) {
    const candidate = resolve(dir, relativePath);
    if (existsSync(candidate)) return readFileSync(candidate, 'utf8');
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error(
    `could not find ${relativePath} above ${process.cwd()} — this guard compares the frontend `
    + 'win-probability table against the Java and must not silently skip',
  );
}

/** Strip line comments so a commented-out entry is never read as live. */
const stripComments = (source) => source.replace(/\/\/[^\n]*/g, '');

/** `ORDER = List.of(A, B, C);` out of DealStage.java, as bare stage codes. */
function javaPipelineOrder() {
  const match = stripComments(readBackendSource(DEAL_STAGE_JAVA))
    .match(/ORDER\s*=\s*List\.of\(([\s\S]*?)\);/);
  if (!match) throw new Error('could not find ORDER = List.of(...) in DealStage.java');
  return match[1].split(',').map((token) => token.trim()).filter(Boolean);
}

/** `DEFAULTS = Map.ofEntries(Map.entry(DealStage.X, 10), ...)` as a plain code → number object. */
function javaWinProbabilityDefaults() {
  const block = stripComments(readBackendSource(WIN_PROBABILITY_JAVA))
    .match(/DEFAULTS\s*=\s*Map\.ofEntries\(([\s\S]*?)\);/);
  if (!block) {
    throw new Error('could not find DEFAULTS = Map.ofEntries(...) in WinProbabilityDefaults.java');
  }
  const table = {};
  for (const [, code, value] of block[1].matchAll(/Map\.entry\(DealStage\.(\w+),\s*(\d+)\)/g)) {
    table[code] = Number(value);
  }
  return table;
}

const javaOrder = javaPipelineOrder();
const javaDefaults = javaWinProbabilityDefaults();

describe('WinProbabilityDefaults.java is the table, and this side must not disagree with it', () => {
  it('parsed the Java at all — a vacuous parse must fail, not pass', () => {
    // Without this, a regex that stopped matching would make every assertion below trivially true against an empty
    // list and an empty table.
    expect(javaOrder.length).toBeGreaterThanOrEqual(15);
    expect(javaOrder).toContain('QUOTE_OWNER');
    expect(Object.keys(javaDefaults).length).toBeGreaterThanOrEqual(15);
    expect(javaDefaults.LEAD_APPROACH).toBe(10);
  });

  it('agrees with the Java on every single number', () => {
    // "Mirrors exactly" checked as an equality rather than key by key: a value edited on one side only is the
    // drift this file exists to catch, and the arity-style checks elsewhere in this repo compare shapes, never
    // values.
    expect(WIN_PROBABILITY_DEFAULTS).toEqual(javaDefaults);
  });
});

describe('the QUOTE_OWNER ruling', () => {
  /** S5 = 40 is an owner ruling (2026-08-13): quoting the project owner is the same act as quoting the designer,
   * only the recipient differs, and nothing has been confirmed — so the odds have not moved off S4's 40. */
  it('scores QUOTE_OWNER exactly as QUOTE_DESIGN_SIDE, flat by design', () => {
    expect(winProbabilityDefault('QUOTE_OWNER')).toBe(40);
    expect(winProbabilityDefault('QUOTE_OWNER')).toBe(winProbabilityDefault('QUOTE_DESIGN_SIDE'));
    // The step after it still rises — S5 sits flat against S4, not against S6.
    expect(winProbabilityDefault('OWNER_SIGNOFF')).toBe(50);
  });
});
