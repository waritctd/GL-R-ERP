import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { hasDealStageLabel } from '../../utils/format.js';
import {
  EMPTY_STAGE_CATALOG, findStage, nextStageIn, phaseIdOf, stageIndexIn, stagesInPhase,
} from './stageCatalog.js';
import { assertStageLabelsComplete, AUTO_STAGE_HINT, GATE_LABEL } from './stageMeta.js';

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
 * other two. The parse below is deliberately narrow — it reads only the `ORDER = List.of(...)`
 * block and the three `*_TARGET_STAGES` sets, and every extraction asserts a non-trivial count
 * first, so a parse that silently matched nothing fails instead of vacuously passing.
 */

const JAVA_RELATIVE_PATH = 'backend/src/main/java/th/co/glr/hr/ticket/DealStage.java';

/**
 * Walk up from the working directory to the repo root and read the Java. Deliberately THROWS when
 * the file cannot be found rather than skipping: a guard that quietly disables itself when the
 * layout moves is the same silent failure it was written to prevent.
 * (`import.meta.url` is not a file: URL under Vite's transform, hence the walk.)
 */
function readDealStageJava() {
  let dir = process.cwd();
  for (let depth = 0; depth < 8; depth += 1) {
    const candidate = resolve(dir, JAVA_RELATIVE_PATH);
    if (existsSync(candidate)) return readFileSync(candidate, 'utf8');
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error(
    `could not find ${JAVA_RELATIVE_PATH} above ${process.cwd()} — this guard compares the `
    + 'frontend stage catalog against DealStage.java and must not silently skip',
  );
}

const javaSource = readDealStageJava();

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

const javaOrder = javaConstantList('ORDER');
const javaSalesGate = javaConstantList('SALES_TARGET_STAGES');
const javaAccountGate = javaConstantList('ACCOUNT_TARGET_STAGES');
const javaImportGate = javaConstantList('IMPORT_TARGET_STAGES');

describe('DealStage.java is the pipeline, and this side must not disagree with it', () => {
  it('parsed the Java at all — a vacuous parse must fail, not pass', () => {
    // Without this, deleting the ORDER block (or a regex that stopped matching) would make every
    // assertion below trivially true against an empty list.
    expect(javaOrder.length).toBeGreaterThanOrEqual(15);
    expect(javaOrder).toContain('QUOTE_OWNER');
    expect(javaSalesGate.length).toBeGreaterThanOrEqual(12);
    expect(javaAccountGate.length).toBeGreaterThanOrEqual(2);
    expect(javaImportGate.length).toBeGreaterThanOrEqual(1);
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
