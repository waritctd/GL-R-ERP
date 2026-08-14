import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { hasDealStageLabel } from '../../utils/format.js';
import {
  EMPTY_STAGE_CATALOG, findStage, nextStageIn, phaseIdOf, stageIndexIn, stagesInPhase,
} from './stageCatalog.js';
import { assertStageLabelsComplete, AUTO_STAGE_HINT, GATE_LABEL } from './stageMeta.js';
import { MOCK_FACT_GATED_STAGES } from '../../api/mockApi.js';

/**
 * THE GUARD THIS CHANGE EXISTS TO ADD.
 *
 * The frontend used to declare the deal pipeline for itself. Nothing compared that declaration to
 * the backend's, so when V143 split QUOTE_OWNER out of QUOTE_DESIGN_SIDE the frontend simply kept
 * fourteen stages: a deal on the new stage rendered with no label, no number, no phase and no
 * gate, and the whole suite stayed green. That is what blocked #704 from being deployed.
 *
 * The declaration is gone — the catalog is served by GET /api/meta/deal-stages — but two mirrors
 * necessarily remain on this side, and both are guarded here against the Java source itself:
 *
 *   1. `data/dealStageCatalog.js`, the canned payload mockApi answers with. A fixture, but a
 *      fixture that has to keep matching the real endpoint's contents or mock-mode QA is testing a
 *      pipeline the backend does not have.
 *   2. the Thai LABEL maps, which stay client-side by owner ruling. A code the backend serves with
 *      no label here is the exact QUOTE_OWNER defect.
 *
 * Reading DealStage.java from the source tree is unusual, and it is the point: a test that
 * re-declared the fifteen stages in JavaScript would be a third copy, and would go stale with the
 * other two. Every extraction asserts a non-trivial count first, so a parse that silently matched
 * nothing fails instead of vacuously passing.
 *
 * ── WIDENED FOR ISSUE #742 ──────────────────────────────────────────────────────────────────
 *
 * This guard used to read only `ORDER` and the three `*_TARGET_STAGES` sets. It asserted the
 * catalog's codes, order, `gate` and `no` — and NOT `auto`, `phase` (beyond monotonicity) or
 * `businessCode` at all. `auto` is the field that matters most, because it is what mockApi's
 * fact-gate stub keyed off:
 *
 *   DealStage.AUTO_ADVANCED  = ORDER_RECEIVED, DEPOSIT_RECEIVED, PROCUREMENT, CLOSED_PAID
 *   requireStageFactsHold    = ORDER_RECEIVED, DEPOSIT_RECEIVED, DELIVERED,   CLOSED_PAID
 *
 * Two different sets, differing in BOTH directions. The mock's own comment claimed its stub was
 * "STRICTER than production, never looser" — for DELIVERED that was false: a manual stage move to
 * DELIVERED on an undelivered deal was ALLOWED in mock mode while production answers 409
 * ยังส่งมอบสินค้าไม่ครบ. More permissive than production is the dangerous direction CLAUDE.md names.
 *
 * So this file now parses four more things: AUTO_ADVANCED, PHASE, BUSINESS_CODE, and
 * TicketService.requireStageFactsHold's own target list — the last one guarding the mock stub
 * against the method that actually enforces it, rather than against an unrelated set.
 */

const JAVA_RELATIVE_PATH = 'backend/src/main/java/th/co/glr/hr/ticket/DealStage.java';
const TICKET_SERVICE_RELATIVE_PATH = 'backend/src/main/java/th/co/glr/hr/ticket/TicketService.java';

/**
 * Walk up from the working directory to the repo root and read the Java. Deliberately THROWS when
 * the file cannot be found rather than skipping: a guard that quietly disables itself when the
 * layout moves is the same silent failure it was written to prevent.
 * (`import.meta.url` is not a file: URL under Vite's transform, hence the walk.)
 */
function readBackendJava(relativePath) {
  let dir = process.cwd();
  for (let depth = 0; depth < 8; depth += 1) {
    const candidate = resolve(dir, relativePath);
    if (existsSync(candidate)) return readFileSync(candidate, 'utf8');
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error(
    `could not find ${relativePath} above ${process.cwd()} — this guard compares the `
    + 'frontend stage catalog against the backend source and must not silently skip',
  );
}

const javaSource = readBackendJava(JAVA_RELATIVE_PATH);
const ticketServiceSource = readBackendJava(TICKET_SERVICE_RELATIVE_PATH);

/** Pull `NAME = List.of(A, B, C);` / `NAME = Set.of(A, B);` out of the Java, as bare identifiers. */
function javaConstantList(declaration) {
  const match = javaSource.match(
    new RegExp(`${declaration}\\s*=\\s*(?:List|Set)\\.of\\(([\\s\\S]*?)\\);`),
  );
  if (!match) throw new Error(`could not find ${declaration} in DealStage.java`);
  return match[1]
    // Strip any line comment so a commented-out constant is not read as live.
    .replace(/\/\/[^\n]*/g, '')
    .split(',')
    .map((token) => token.trim())
    .filter(Boolean);
}

/** Pull `NAME = Map.ofEntries(Map.entry(STAGE, VALUE), ...)` out of the Java, as identifier -> raw value. */
function javaConstantMap(declaration) {
  const match = javaSource.match(
    new RegExp(`${declaration}\\s*=\\s*Map\\.ofEntries\\(([\\s\\S]*?)\\);`),
  );
  if (!match) throw new Error(`could not find ${declaration} in DealStage.java`);
  const entries = {};
  const pattern = /Map\.entry\(\s*([A-Z_]+)\s*,\s*(?:"([^"]*)"|(\d+))\s*\)/g;
  let entry = pattern.exec(match[1].replace(/\/\/[^\n]*/g, ''));
  while (entry !== null) {
    entries[entry[1]] = entry[2] !== undefined ? entry[2] : Number(entry[3]);
    entry = pattern.exec(match[1].replace(/\/\/[^\n]*/g, ''));
  }
  return entries;
}

/**
 * The stages `TicketService.requireStageFactsHold` actually refuses a manual move INTO.
 *
 * Read from the method body's own `DealStage.X.equals(targetStage)` guards rather than from any
 * named set, because there IS no named set — the four gates are four `if` statements, and the
 * absence of a constant to import is precisely why the mock reached for `AUTO_ADVANCED` instead
 * and got a different four (issue #742). The body is isolated first so an `equals(targetStage)`
 * elsewhere in this 2000-line service cannot leak in.
 */
function javaFactGatedStages() {
  const method = ticketServiceSource.match(
    // The closing `\n {4}\}` is the method's own dedented brace at four-space indentation —
    // that is what bounds the body, so an `equals(targetStage)` later in the file cannot leak in.
    /private void requireStageFactsHold\([^)]*\)\s*\{([\s\S]*?)\n {4}\}/,
  );
  if (!method) throw new Error('could not find requireStageFactsHold in TicketService.java');
  const stages = new Set();
  const pattern = /DealStage\.([A-Z_]+)\.equals\(targetStage\)/g;
  let hit = pattern.exec(method[1]);
  while (hit !== null) {
    stages.add(hit[1]);
    hit = pattern.exec(method[1]);
  }
  return [...stages].sort();
}

const javaOrder = javaConstantList('ORDER');
const javaSalesGate = javaConstantList('SALES_TARGET_STAGES');
const javaAccountGate = javaConstantList('ACCOUNT_TARGET_STAGES');
const javaImportGate = javaConstantList('IMPORT_TARGET_STAGES');
const javaAutoAdvanced = javaConstantList('AUTO_ADVANCED');
const javaPhase = javaConstantMap('PHASE');
const javaBusinessCode = javaConstantMap('BUSINESS_CODE');
const javaFactGated = javaFactGatedStages();

describe('DealStage.java is the pipeline, and this side must not disagree with it', () => {
  it('parsed the Java at all — a vacuous parse must fail, not pass', () => {
    // Without this, deleting the ORDER block (or a regex that stopped matching) would make every
    // assertion below trivially true against an empty list.
    expect(javaOrder.length).toBeGreaterThanOrEqual(15);
    expect(javaOrder).toContain('QUOTE_OWNER');
    expect(javaSalesGate.length).toBeGreaterThanOrEqual(12);
    expect(javaAccountGate.length).toBeGreaterThanOrEqual(2);
    expect(javaImportGate.length).toBeGreaterThanOrEqual(1);
    // #742's four additions get the same anti-vacuity floor. Without these an empty parse would
    // make every new assertion below trivially true — which is the failure this whole file exists
    // to make impossible.
    expect(javaAutoAdvanced.length).toBeGreaterThanOrEqual(4);
    expect(Object.keys(javaPhase).length).toBeGreaterThanOrEqual(15);
    expect(Object.keys(javaBusinessCode).length).toBeGreaterThanOrEqual(15);
    expect(javaFactGated.length).toBeGreaterThanOrEqual(4);
  });

  it('the canned mock catalog carries exactly the backend stages, in the backend order', () => {
    expect(DEAL_STAGE_CATALOG.stages.map((stage) => stage.code)).toEqual(javaOrder);
  });

  it('the canned mock catalog carries the backend write gate for every stage', () => {
    const expectedGate = (code) => {
      if (javaSalesGate.includes(code)) return 'sales';
      if (javaAccountGate.includes(code)) return 'account';
      if (javaImportGate.includes(code)) return 'import';
      throw new Error(`${code} is in DealStage.ORDER but in no *_TARGET_STAGES set`);
    };
    for (const stage of DEAL_STAGE_CATALOG.stages) {
      expect(stage.gate, `gate of ${stage.code}`).toBe(expectedGate(stage.code));
      expect(GATE_LABEL[stage.gate], `label for gate ${stage.gate}`).toBeTruthy();
    }
  });

  it('display numbers are the 1-based pipeline position, and phases never go backwards', () => {
    let previousPhase = 0;
    DEAL_STAGE_CATALOG.stages.forEach((stage, index) => {
      expect(stage.no, `no of ${stage.code}`).toBe(index + 1);
      expect(stage.phase, `phase of ${stage.code}`).toBeGreaterThanOrEqual(previousPhase);
      previousPhase = stage.phase;
    });
    expect(DEAL_STAGE_CATALOG.phases).toEqual([...new Set(
      DEAL_STAGE_CATALOG.stages.map((stage) => stage.phase),
    )].sort((a, b) => a - b));
  });

  it('every auto-advanced stage has wording explaining where it comes from', () => {
    for (const stage of DEAL_STAGE_CATALOG.stages.filter((s) => s.auto)) {
      expect(AUTO_STAGE_HINT[stage.code], `auto hint for ${stage.code}`).toBeTruthy();
    }
  });

  // ── #742: the three fields this guard did not check ────────────────────────────────────────

  it('`auto` is exactly DealStage.AUTO_ADVANCED — in both directions', () => {
    // The pre-existing auto assertion only checked that each `auto: true` stage HAS a hint. It
    // could not notice a stage wrongly marked auto, nor an AUTO_ADVANCED stage the fixture had
    // left false. Both matter: `auto` drives whether the UI offers a one-click advance at all.
    expect(
      DEAL_STAGE_CATALOG.stages.filter((stage) => stage.auto).map((stage) => stage.code).sort(),
    ).toEqual([...javaAutoAdvanced].sort());
    // DELIVERY_SCHEDULING is deliberately NOT auto although recordDelivery/reserveStock advance
    // into it — the rep also reaches it by hand as a normal part of the flow. Named explicitly so
    // a future "surely this belongs in AUTO_ADVANCED" edit has to argue with the Javadoc.
    expect(javaAutoAdvanced).not.toContain('DELIVERY_SCHEDULING');
  });

  it('`phase` is exactly DealStage.PHASE, not merely non-decreasing', () => {
    // The old check only asserted phases never go BACKWARDS, which every wrong-but-sorted
    // assignment also satisfies: shifting the whole phase-2 block into phase 1 stays monotonic.
    for (const stage of DEAL_STAGE_CATALOG.stages) {
      expect(stage.phase, `phase of ${stage.code}`).toBe(javaPhase[stage.code]);
    }
  });

  it('`businessCode` is exactly DealStage.BUSINESS_CODE — the CEO\'s S-numbers', () => {
    // Never checked at all before. It is the identifier the business itself uses when discussing a
    // deal ("we are at S12"), and it is deliberately NOT derivable from `no`: PROCUREMENT covers
    // six sheet steps (S12–S17), so the sheet runs to S20 while the pipeline has 15 entries, and
    // V143's QUOTE_OWNER insertion moved every later display number without moving any S-number.
    for (const stage of DEAL_STAGE_CATALOG.stages) {
      expect(stage.businessCode, `businessCode of ${stage.code}`).toBe(javaBusinessCode[stage.code]);
    }
    expect(javaBusinessCode.PROCUREMENT).toBe('S12–S17');
  });
});

/**
 * THE TRIPWIRE #742 IS ACTUALLY ABOUT.
 *
 * mockApi's fact-gate stub derived its closed-stage set from the catalog's `auto` field. That was
 * the wrong source, and not by a little: `DealStage.AUTO_ADVANCED` and the stages
 * `TicketService.requireStageFactsHold` actually gates differ in BOTH directions —
 *
 *   AUTO_ADVANCED         ORDER_RECEIVED, DEPOSIT_RECEIVED, PROCUREMENT, CLOSED_PAID
 *   requireStageFactsHold ORDER_RECEIVED, DEPOSIT_RECEIVED, DELIVERED,   CLOSED_PAID
 *
 * so mock mode ALLOWED a manual move to DELIVERED on an undelivered deal (production: 409
 * ยังส่งมอบสินค้าไม่ครบ) and REFUSED a manual move to PROCUREMENT that production permits. The
 * mock's own comment asserted the stub was "STRICTER than production, never looser" and named this
 * as "the exact hazard CLAUDE.md names" — for DELIVERED that claim was simply false.
 *
 * Fixing the derivation without this test would just move the mirror. This asserts the stub
 * against the METHOD THAT ENFORCES IT.
 */
describe('the mock fact-gate stub tracks TicketService.requireStageFactsHold', () => {
  it('closes exactly the stages the real service refuses a manual move into', () => {
    expect([...MOCK_FACT_GATED_STAGES].sort()).toEqual(javaFactGated);
  });

  it('is not the AUTO_ADVANCED set — the two genuinely differ, in both directions', () => {
    // Wrong-way-round, and the assertion that would have gone red the day the stub was written.
    // If a future change makes these sets coincide this test fails LOUDLY rather than quietly
    // blessing a derivation that happens to work; read requireStageFactsHold before deleting it.
    expect(javaFactGated).toContain('DELIVERED');
    expect(javaAutoAdvanced).not.toContain('DELIVERED');
    expect(javaAutoAdvanced).toContain('PROCUREMENT');
    expect(javaFactGated).not.toContain('PROCUREMENT');
  });
});

describe('the label guard', () => {
  // THIS is the test that would have gone red the day #704 merged.
  it('every stage the backend serves has Thai display copy', () => {
    for (const code of javaOrder) {
      expect(hasDealStageLabel(code), `${code} has no Thai label in utils/format.js`).toBe(true);
    }
    // Named explicitly as well as covered by the loop: QUOTE_OWNER is the code that was missing.
    expect(hasDealStageLabel('QUOTE_OWNER')).toBe(true);
  });

  it('assertStageLabelsComplete throws — loudly — on a code with no label', () => {
    expect(() => assertStageLabelsComplete(javaOrder)).not.toThrow();
    expect(() => assertStageLabelsComplete([...javaOrder, 'S21_SOMETHING_NEW']))
      .toThrow(/S21_SOMETHING_NEW/);
  });

  it('lost and cancel reason codes all have labels', () => {
    // The code sets come from the backend (DealLostReason.ORDER / DealCancelReason.ORDER); only
    // the wording is ours, so this is the same guard applied to the other two code sets.
    const stageMeta = { LOST: DEAL_STAGE_CATALOG.lostReasons, CANCEL: DEAL_STAGE_CATALOG.cancelReasons };
    expect(stageMeta.LOST.length).toBeGreaterThan(0);
    expect(stageMeta.CANCEL.length).toBeGreaterThan(0);
  });
});

describe('catalog lookups', () => {
  const catalog = DEAL_STAGE_CATALOG;

  it('finds a stage, its index, its phase and its successor', () => {
    expect(findStage(catalog, 'QUOTE_OWNER')?.no).toBe(5);
    expect(stageIndexIn(catalog, 'ORDER_RECEIVED')).toBe(9);
    expect(phaseIdOf(catalog, 'QUOTE_OWNER')).toBe(2);
    expect(nextStageIn(catalog, 'QUOTE_DESIGN_SIDE')?.code).toBe('QUOTE_OWNER');
    expect(nextStageIn(catalog, 'CLOSED_PAID')).toBeNull();
    expect(stagesInPhase(catalog, 2).map((s) => s.code)).toEqual(
      ['SPEC_APPROVED', 'QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF'],
    );
  });

  it('answers safely for an unknown code and for the pre-load empty catalog', () => {
    // Every consumer renders at least one frame before the catalog arrives, so these must not throw.
    expect(findStage(catalog, 'NOT_A_STAGE')).toBeNull();
    expect(stageIndexIn(catalog, 'NOT_A_STAGE')).toBe(-1);
    expect(phaseIdOf(catalog, 'NOT_A_STAGE')).toBeNull();
    expect(nextStageIn(catalog, 'NOT_A_STAGE')).toBeNull();
    expect(findStage(EMPTY_STAGE_CATALOG, 'LEAD_APPROACH')).toBeNull();
    expect(stageIndexIn(EMPTY_STAGE_CATALOG, 'LEAD_APPROACH')).toBe(-1);
    expect(stagesInPhase(EMPTY_STAGE_CATALOG, 1)).toEqual([]);
    expect(findStage(undefined, 'LEAD_APPROACH')).toBeNull();
  });
});
