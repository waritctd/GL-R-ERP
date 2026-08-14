import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  WIN_PROBABILITY_DEFAULTS, effectiveWinProbability, winProbabilityDefault,
} from './dealTrackingMeta.js';

/**
 * THE GUARD THIS FILE EXISTS TO ADD.
 *
 * `WIN_PROBABILITY_DEFAULTS` says of itself that it "mirrors WinProbabilityDefaults.java's stage →
 * % map exactly", and until now nothing checked that. So when V143 inserted QUOTE_OWNER (S5) into
 * the pipeline, BOTH maps stayed at fourteen entries and neither side complained: the Java answered
 * its 0 sentinel, the JS fell through its `?? 0`, and every deal sitting at S5 rendered 0% and
 * contributed nothing to TicketListPage's win-weighted forecast. A wrong number, not an error, on
 * the screen whose entire job is that number.
 *
 * The approach is #713's, from stageCatalog.test.js: read the Java out of the source tree rather
 * than re-declaring the table in JavaScript. A JS copy of the expected values would be a THIRD copy
 * and would go stale alongside the other two — it would have been written with fourteen entries in
 * V143 and passed. Reading the source means this test cannot agree with a mistake it did not make.
 *
 * The parse is deliberately narrow and asserts a non-trivial count before anything else, so a regex
 * that stopped matching fails loudly instead of vacuously passing against an empty map.
 */

const DEAL_STAGE_JAVA = 'backend/src/main/java/th/co/glr/hr/ticket/DealStage.java';
const WIN_PROBABILITY_JAVA = 'backend/src/main/java/th/co/glr/hr/ticket/WinProbabilityDefaults.java';

/**
 * Walk up from the working directory to the repo root and read a backend source file. Deliberately
 * THROWS when the file cannot be found rather than skipping: a guard that quietly disables itself
 * when the layout moves is the same silent failure it was written to prevent.
 * (`import.meta.url` is not a file: URL under Vite's transform, hence the walk.)
 */
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
    // Without this, a regex that stopped matching would make every assertion below trivially true
    // against an empty list and an empty table.
    expect(javaOrder.length).toBeGreaterThanOrEqual(15);
    expect(javaOrder).toContain('QUOTE_OWNER');
    expect(Object.keys(javaDefaults).length).toBeGreaterThanOrEqual(15);
    expect(javaDefaults.LEAD_APPROACH).toBe(10);
  });

  // THIS is the test that would have gone red the day #704 merged.
  it('every stage in the backend pipeline has a win-probability default on this side', () => {
    for (const code of javaOrder) {
      expect(
        WIN_PROBABILITY_DEFAULTS[code],
        `${code} is in DealStage.ORDER but has no WIN_PROBABILITY_DEFAULTS entry — a deal on this `
        + 'stage would show 0% and drop out of the win-weighted forecast',
      ).toBeGreaterThan(0);
    }
    // Named explicitly as well as covered by the loop: QUOTE_OWNER is the code that was missing.
    expect(winProbabilityDefault('QUOTE_OWNER')).toBe(40);
  });

  it('every stage in the backend pipeline has a default in the Java itself', () => {
    // The same completeness question asked of the backend map, from the source text — which is the
    // one direction WinProbabilityDefaultsTest cannot ask, since DEFAULTS is private and
    // `defaultFor` can only be probed key by key.
    for (const code of javaOrder) {
      expect(javaDefaults[code], `${code} has no WinProbabilityDefaults entry in the Java`)
        .toBeGreaterThan(0);
    }
  });

  it('carries no stage the backend pipeline does not have', () => {
    // The reverse direction: a leftover code here would be a number nothing can ever read, and a
    // stage rename that updated only the Java would look like it worked.
    expect(Object.keys(WIN_PROBABILITY_DEFAULTS).sort()).toEqual([...javaOrder].sort());
    expect(Object.keys(javaDefaults).sort()).toEqual([...javaOrder].sort());
  });

  it('agrees with the Java on every single number', () => {
    // "Mirrors exactly" checked as an equality rather than key by key: a value edited on one side
    // only is the drift this file exists to catch, and the arity-style checks elsewhere in this
    // repo compare shapes, never values.
    expect(WIN_PROBABILITY_DEFAULTS).toEqual(javaDefaults);
  });

  it('lists the stages in pipeline order, so the two files read side by side', () => {
    // Readability, not behaviour — object key order changes nothing at runtime. It is asserted
    // separately from the equality above so a harmless reorder cannot be mistaken for a drifted
    // value.
    expect(Object.keys(WIN_PROBABILITY_DEFAULTS)).toEqual(javaOrder);
  });
});

describe('the QUOTE_OWNER ruling', () => {
  /**
   * S5 = 40 is an owner ruling (2026-08-13): quoting the project owner is the same act as quoting
   * the designer, only the recipient differs, and nothing has been confirmed — so the odds have not
   * moved off S4's 40. Pinned as an equality with S4 rather than as a bare 40 so the intent
   * survives a future re-rating of the pre-quote stages.
   */
  it('scores QUOTE_OWNER exactly as QUOTE_DESIGN_SIDE, flat by design', () => {
    expect(winProbabilityDefault('QUOTE_OWNER')).toBe(40);
    expect(winProbabilityDefault('QUOTE_OWNER')).toBe(winProbabilityDefault('QUOTE_DESIGN_SIDE'));
    // The step after it still rises — S5 sits flat against S4, not against S6.
    expect(winProbabilityDefault('OWNER_SIGNOFF')).toBe(50);
  });

  it('never goes backwards along the pipeline', () => {
    let previous = 0;
    for (const code of javaOrder) {
      const probability = winProbabilityDefault(code);
      expect(probability, `${code} scores lower than the stage before it`)
        .toBeGreaterThanOrEqual(previous);
      previous = probability;
    }
  });
});

describe('effectiveWinProbability', () => {
  it('prefers the rep override, including an explicit zero', () => {
    expect(effectiveWinProbability(75, 'LEAD_APPROACH')).toBe(75);
    expect(effectiveWinProbability(0, 'NEGOTIATION')).toBe(0);
    expect(effectiveWinProbability('55', 'NEGOTIATION')).toBe(55);
    expect(effectiveWinProbability(null, 'NEGOTIATION')).toBe(70);
    expect(effectiveWinProbability(undefined, 'QUOTE_OWNER')).toBe(40);
  });

  it('answers 0 for an unknown or absent stage instead of throwing', () => {
    // A DELIBERATE divergence from the Java, which throws NPE on a null stage (Map.ofEntries
    // rejects a null probe — see WinProbabilityDefaultsTest). Every consumer here renders at least
    // one frame before the ticket data lands, so throwing would take the page down; the backend has
    // a NOT NULL column and no such frame. Stated rather than silently different.
    expect(winProbabilityDefault('NOT_A_STAGE')).toBe(0);
    expect(winProbabilityDefault(null)).toBe(0);
    expect(winProbabilityDefault(undefined)).toBe(0);
    expect(effectiveWinProbability(null, null)).toBe(0);
  });
});
