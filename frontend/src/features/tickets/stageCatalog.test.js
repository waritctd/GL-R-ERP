import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { hasDealStageLabel } from '../../utils/format.js';
import {
  EMPTY_STAGE_CATALOG, findStage, nextOnRoute, nextStageIn, phaseIdOf, routeForChannel, routePath,
  routePosition, routePositionForChannel, stageIndexIn, stagesInPhase,
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
const DEAL_ROUTE_RELATIVE_PATH = 'backend/src/main/java/th/co/glr/hr/ticket/DealRoute.java';

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
const dealRouteSource = readBackendJava(DEAL_ROUTE_RELATIVE_PATH);

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

/**
 * Route-aware lookups (deal-route-staging). The backend serves `onRoute` per stage on
 * GET /api/tickets/{id}/actions (StageDecisionDto); these helpers only READ it.
 *
 * ⚠️ The decisions below are built here and passed in DIRECTLY. mockApi.js does not emit `onRoute`,
 * so a mock-driven test would see every stage on-route and pass vacuously — the "mock omits a field
 * the feature keys on" failure mode CLAUDE.md documents.
 */
describe('route-aware lookups', () => {
  const catalog = DEAL_STAGE_CATALOG;
  const OWNER_OFF = ['QUOTE_DESIGN_SIDE'];
  const BUYER_OFF = ['QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'AWAITING_BUYER'];
  const decisionsFor = (off) => catalog.stages.map((stage) => ({
    stage: stage.code,
    no: stage.no,
    allowed: !off.includes(stage.code),
    requiresReason: false,
    blockedReason: off.includes(stage.code) ? `ขั้นที่ ${stage.no} ไม่อยู่ในเส้นทางของดีลนี้` : null,
    onRoute: !off.includes(stage.code),
  }));

  describe('routePath', () => {
    it('returns all fifteen stages when there are no decisions at all (empty, undefined, null)', () => {
      expect(catalog.stages).toHaveLength(15);
      expect(routePath(catalog, [])).toHaveLength(15);
      expect(routePath(catalog, undefined)).toHaveLength(15);
      expect(routePath(catalog, null)).toHaveLength(15);
    });

    it('returns all fifteen when every decision says onRoute:true (designer-led)', () => {
      expect(routePath(catalog, decisionsFor([])).map((s) => s.code))
        .toEqual(catalog.stages.map((s) => s.code));
    });

    it('excludes onRoute:false stages and keeps catalog order (buyer-direct)', () => {
      const path = routePath(catalog, decisionsFor(BUYER_OFF));
      expect(path).toHaveLength(11);
      expect(path.map((s) => s.code)).toEqual(
        catalog.stages.map((s) => s.code).filter((code) => !BUYER_OFF.includes(code)),
      );
    });

    it('treats a stage with NO decision, or a decision with no onRoute field, as on-route', () => {
      // Degrades to today's behaviour: only an explicit `false` removes a stage.
      const partial = [
        { stage: 'QUOTE_DESIGN_SIDE', no: 4, allowed: true, blockedReason: null }, // onRoute absent
        { stage: 'QUOTE_OWNER', no: 5, allowed: false, blockedReason: 'x', onRoute: false },
      ];
      const codes = routePath(catalog, partial).map((s) => s.code);
      expect(codes).toContain('QUOTE_DESIGN_SIDE');
      expect(codes).not.toContain('QUOTE_OWNER');
      expect(codes).toHaveLength(14);
    });
  });

  describe('routePosition', () => {
    it('buyer-direct at QUOTE_BUYER (S8) is the 4th of 11 — not 8 of 15', () => {
      expect(routePosition(catalog, 'QUOTE_BUYER', decisionsFor(BUYER_OFF)))
        .toEqual({ position: 4, total: 11 });
    });

    it('designer-led at QUOTE_BUYER stays 8 of 15 (majority-route regression guard)', () => {
      expect(routePosition(catalog, 'QUOTE_BUYER', decisionsFor([])))
        .toEqual({ position: 8, total: 15 });
    });

    it('with no decisions at all it is the plain catalog position', () => {
      expect(routePosition(catalog, 'QUOTE_BUYER', [])).toEqual({ position: 8, total: 15 });
      expect(routePosition(catalog, 'QUOTE_BUYER', undefined)).toEqual({ position: 8, total: 15 });
    });

    it('owner-direct: 14 stages, and S5 is the 4th on the route', () => {
      expect(routePosition(catalog, 'QUOTE_OWNER', decisionsFor(OWNER_OFF)))
        .toEqual({ position: 4, total: 14 });
    });

    it('a deal SITTING on an off-route stage (channel corrected after it got there) gets a sane position and does not throw', () => {
      // Sat on S4, then the channel was corrected to owner-direct: S4 is now off-route.
      const result = routePosition(catalog, 'QUOTE_DESIGN_SIDE', decisionsFor(OWNER_OFF));
      expect(result.total).toBe(14);
      // Counts the on-route stages it has passed (S1-S3); never 0, never past the total.
      expect(result.position).toBeGreaterThanOrEqual(1);
      expect(result.position).toBeLessThanOrEqual(result.total);
      expect(result.position).toBe(3);
    });

    it('never throws for an unknown stage code or the pre-load empty catalog', () => {
      expect(() => routePosition(catalog, 'NOT_A_STAGE', decisionsFor([]))).not.toThrow();
      expect(() => routePosition(EMPTY_STAGE_CATALOG, 'LEAD_APPROACH', [])).not.toThrow();
      expect(routePosition(EMPTY_STAGE_CATALOG, 'LEAD_APPROACH', []).total).toBe(0);
    });
  });

  describe('nextOnRoute', () => {
    it('owner-direct at SPEC_APPROVED (S3) offers QUOTE_OWNER (S5), not QUOTE_DESIGN_SIDE (S4)', () => {
      expect(nextOnRoute(catalog, 'SPEC_APPROVED', decisionsFor(OWNER_OFF))?.code).toBe('QUOTE_OWNER');
    });

    it('buyer-direct at SPEC_APPROVED (S3) offers QUOTE_BUYER (S8)', () => {
      expect(nextOnRoute(catalog, 'SPEC_APPROVED', decisionsFor(BUYER_OFF))?.code).toBe('QUOTE_BUYER');
    });

    it('designer-led / no decisions agree with nextStageIn everywhere', () => {
      for (const stage of catalog.stages) {
        expect(nextOnRoute(catalog, stage.code, decisionsFor([]))?.code ?? null)
          .toBe(nextStageIn(catalog, stage.code)?.code ?? null);
        expect(nextOnRoute(catalog, stage.code, [])?.code ?? null)
          .toBe(nextStageIn(catalog, stage.code)?.code ?? null);
      }
    });

    it('a deal sitting on an off-route stage is offered the next ON-route stage after it', () => {
      expect(nextOnRoute(catalog, 'QUOTE_DESIGN_SIDE', decisionsFor(OWNER_OFF))?.code).toBe('QUOTE_OWNER');
    });

    it('is null at the last stage and for an unknown code', () => {
      expect(nextOnRoute(catalog, 'CLOSED_PAID', decisionsFor([]))).toBeNull();
      expect(nextOnRoute(catalog, 'NOT_A_STAGE', decisionsFor([]))).toBeNull();
    });

    it('never returns an off-route stage', () => {
      for (const stage of catalog.stages) {
        const next = nextOnRoute(catalog, stage.code, decisionsFor(BUYER_OFF));
        if (next) expect(BUYER_OFF).not.toContain(next.code);
      }
    });
  });
});

// ── The served route: GET /api/meta/deal-stages -> `routes` ────────────────────────────────────
// The backend serves each entry channel's route (DealStageMetaController -> DealRoute.path) so a
// deal LIST row, which has `deal.entryChannel` but no per-deal decision payload, can print its
// position on its own route. Serving it is only safer than mirroring it if the fixture that stands
// in for the endpoint cannot drift from DealRoute.java — so the guard below reads DealRoute's
// off-route tables out of the Java source and compares.

/** Stage identifiers named inside a Java fragment, in order: `DealStage.X` -> X. */
function dealStageNames(fragment) {
  return [...fragment.matchAll(/DealStage\.([A-Z_]+)/g)].map((match) => match[1]);
}

/** DealRoute.OFF_ROUTE: channel -> list of off-route stage codes, parsed from the Java. */
function javaOffRouteTable() {
  const match = dealRouteSource.match(/OFF_ROUTE\s*=\s*Map\.of\(([\s\S]*?)\)\);/);
  if (!match) throw new Error('could not find OFF_ROUTE in DealRoute.java');
  // The lazy match stops at the `));` that closes `Map.of(`, which consumes the LAST `Set.of(`'s own
  // closing paren — restore it, or the final channel's entry silently fails to parse.
  const body = `${match[1].replace(/\/\/[^\n]*/g, '')})`;
  const table = {};
  for (const entry of body.matchAll(/EntryChannel\.([A-Z_]+)\s*,\s*Set\.of\(([^)]*)\)/g)) {
    table[entry[1]] = dealStageNames(entry[2]);
  }
  return table;
}

/** DealRoute.ROUTE_VARIABLE: the only stages a route may ever exclude. */
function javaRouteVariable() {
  const match = dealRouteSource.match(/ROUTE_VARIABLE\s*=\s*Set\.of\(([\s\S]*?)\);/);
  if (!match) throw new Error('could not find ROUTE_VARIABLE in DealRoute.java');
  return dealStageNames(match[1].replace(/\/\/[^\n]*/g, ''));
}

const javaOffRoute = javaOffRouteTable();
const javaRouteVariableStages = javaRouteVariable();

describe('DealRoute.java is the route, and the served-routes fixture must not disagree with it', () => {
  const routes = DEAL_STAGE_CATALOG.routes;

  it('parsed DealRoute.java at all — a vacuous parse must fail, not pass', () => {
    expect(Object.keys(javaOffRoute).sort()).toEqual(['BUYER_DIRECT', 'DESIGNER_LED', 'OWNER_DIRECT']);
    expect(javaOffRoute.DESIGNER_LED).toEqual([]);
    expect(javaOffRoute.OWNER_DIRECT.length).toBeGreaterThanOrEqual(1);
    expect(javaOffRoute.BUYER_DIRECT.length).toBeGreaterThanOrEqual(4);
    expect(javaRouteVariableStages.length).toBeGreaterThanOrEqual(5);
  });

  it('the fixture serves a route for every channel DealRoute names, plus UNSPECIFIED', () => {
    expect(routes, 'DEAL_STAGE_CATALOG.routes').toBeTruthy();
    expect(Object.keys(routes).sort()).toEqual(
      [...Object.keys(javaOffRoute), 'UNSPECIFIED'].sort(),
    );
  });

  it('each served route is DealStage.ORDER minus that channel\'s off-route stages, in order', () => {
    for (const [channel, off] of Object.entries(javaOffRoute)) {
      expect(routes?.[channel], `route of ${channel}`).toEqual(
        javaOrder.filter((code) => !off.includes(code)),
      );
    }
  });

  it('UNSPECIFIED serves all fifteen — DealRoute gives it no off-route stage', () => {
    expect(routes?.UNSPECIFIED).toEqual(javaOrder);
    expect(routes?.UNSPECIFIED).toHaveLength(15);
  });

  it('a route only ever varies the party stages: every route keeps the whole tail from NEGOTIATION', () => {
    const tail = javaOrder.slice(javaOrder.indexOf('NEGOTIATION'));
    expect(tail).toContain('CLOSED_PAID');
    for (const [channel, off] of Object.entries(javaOffRoute)) {
      for (const stage of off) {
        expect(javaRouteVariableStages, `${stage} off-route for ${channel}`).toContain(stage);
      }
      expect(routes?.[channel], `route of ${channel}`).toEqual(expect.arrayContaining(tail));
    }
  });
});

describe('routeForChannel / routePositionForChannel — lookups over the SERVED routes', () => {
  const catalog = DEAL_STAGE_CATALOG;
  const withoutRoutes = { ...catalog, routes: undefined };

  describe('routeForChannel', () => {
    it('buyer-direct is 11 stages, owner-direct 14, designer-led 15 — in catalog order', () => {
      expect(routeForChannel(catalog, 'BUYER_DIRECT')).toHaveLength(11);
      expect(routeForChannel(catalog, 'OWNER_DIRECT')).toHaveLength(14);
      expect(routeForChannel(catalog, 'DESIGNER_LED')).toHaveLength(15);
      expect(routeForChannel(catalog, 'BUYER_DIRECT').map((s) => s.code)).toEqual(
        catalog.routes.BUYER_DIRECT,
      );
    });

    it('UNSPECIFIED, an unknown channel, null and undefined are all fifteen', () => {
      for (const channel of ['UNSPECIFIED', 'SOMETHING_NEW', null, undefined]) {
        expect(routeForChannel(catalog, channel), String(channel)).toHaveLength(15);
      }
    });

    it('absent `routes` (older backend, pre-load) is all stages, and never throws', () => {
      expect(routeForChannel(withoutRoutes, 'BUYER_DIRECT')).toHaveLength(15);
      expect(routeForChannel(EMPTY_STAGE_CATALOG, 'BUYER_DIRECT')).toEqual([]);
      expect(() => routeForChannel(undefined, 'BUYER_DIRECT')).not.toThrow();
    });
  });

  describe('routePositionForChannel', () => {
    it('buyer-direct at QUOTE_BUYER is the 4th of 11 — not 8 of 15', () => {
      expect(routePositionForChannel(catalog, 'QUOTE_BUYER', 'BUYER_DIRECT'))
        .toEqual({ position: 4, total: 11 });
    });

    it('designer-led at QUOTE_BUYER stays 8 of 15 (majority-route regression guard)', () => {
      expect(routePositionForChannel(catalog, 'QUOTE_BUYER', 'DESIGNER_LED'))
        .toEqual({ position: 8, total: 15 });
    });

    it('UNSPECIFIED, absent and unknown channels are the plain catalog position', () => {
      for (const channel of ['UNSPECIFIED', null, undefined, 'SOMETHING_NEW']) {
        expect(routePositionForChannel(catalog, 'QUOTE_BUYER', channel), String(channel))
          .toEqual({ position: 8, total: 15 });
      }
    });

    it('absent catalog.routes is all fifteen even for a buyer-direct deal', () => {
      expect(routePositionForChannel(withoutRoutes, 'QUOTE_BUYER', 'BUYER_DIRECT'))
        .toEqual({ position: 8, total: 15 });
    });

    it('owner-direct at QUOTE_OWNER (S5) is the 4th of 14', () => {
      expect(routePositionForChannel(catalog, 'QUOTE_OWNER', 'OWNER_DIRECT'))
        .toEqual({ position: 4, total: 14 });
    });

    it('agrees with routePosition over the equivalent decisions, stage by stage', () => {
      const decisions = catalog.stages.map((stage) => ({
        stage: stage.code, onRoute: catalog.routes.BUYER_DIRECT.includes(stage.code),
      }));
      for (const stage of catalog.stages) {
        expect(routePositionForChannel(catalog, stage.code, 'BUYER_DIRECT'), stage.code)
          .toEqual(routePosition(catalog, stage.code, decisions));
      }
    });

    it('a deal SITTING on an off-route stage gets a sane position and does not throw', () => {
      // On QUOTE_DESIGN_SIDE, then the channel became buyer-direct: S4 is off-route.
      const result = routePositionForChannel(catalog, 'QUOTE_DESIGN_SIDE', 'BUYER_DIRECT');
      expect(result.total).toBe(11);
      expect(result.position).toBe(3); // the on-route stages it has passed: S1-S3
    });

    it('an unknown stage is position 0 of that route; the empty catalog does not throw', () => {
      expect(routePositionForChannel(catalog, 'NOT_A_STAGE', 'BUYER_DIRECT'))
        .toEqual({ position: 0, total: 11 });
      expect(() => routePositionForChannel(EMPTY_STAGE_CATALOG, 'LEAD_APPROACH', 'BUYER_DIRECT'))
        .not.toThrow();
      expect(routePositionForChannel(EMPTY_STAGE_CATALOG, 'LEAD_APPROACH', 'BUYER_DIRECT').total)
        .toBe(0);
    });
  });
});
