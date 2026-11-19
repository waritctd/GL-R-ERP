import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { hasDealStageLabel } from '../../utils/format.js';
import {
  nextOnRoute, routePath, routePosition,
} from './stageCatalog.js';
import { GATE_LABEL } from './stageMeta.js';
import { MOCK_FACT_GATED_STAGES } from '../../api/mockApi.js';

const JAVA_RELATIVE_PATH = 'backend/src/main/java/th/co/glr/hr/ticket/DealStage.java';
const TICKET_SERVICE_RELATIVE_PATH = 'backend/src/main/java/th/co/glr/hr/ticket/TicketService.java';
const DEAL_ROUTE_RELATIVE_PATH = 'backend/src/main/java/th/co/glr/hr/ticket/DealRoute.java';

/** Walk up from the working directory to the repo root and read the Java. */
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
 * The stages a quote-stage gate can refuse, derived from the ONE recipient → stage mapping it is
 * built on: `TicketService.stageForQuotationRecipient`'s `return DealStage.X;` lines.
 *
 * Read this way rather than hardcoded because the Java deliberately has no fourth copy of the
 * mapping — `quotationRecipientForStage` inverts `stageForQuotationRecipient` at runtime instead
 * of restating it, for exactly the drift reason that method's own Javadoc records. A guard that
 * listed the three stages itself would be the copy the Java refused to make.
 */
function javaQuoteRecipientStages() {
  const method = ticketServiceSource.match(
    /private static String stageForQuotationRecipient\([^)]*\)\s*\{([\s\S]*?)\n {4}\}/,
  );
  if (!method) throw new Error('could not find stageForQuotationRecipient in TicketService.java');
  const stages = [...method[1].matchAll(/return DealStage\.([A-Z_]+);/g)].map((hit) => hit[1]);
  if (stages.length !== 3) {
    throw new Error(`expected 3 recipient stages, parsed ${stages.length}: ${stages}`);
  }
  return stages;
}

/**
 * The stages `TicketService.requireStageFactsHold` actually refuses a manual move INTO.
 *
 * Read from the method body rather than from any named set, because there IS no named set — the
 * gates are `if` statements, and the absence of a constant to import is precisely why the mock
 * reached for `AUTO_ADVANCED` instead and got a different four (issue #742). The body is isolated
 * first so an `equals(targetStage)` elsewhere in this 2000-line service cannot leak in.
 *
 * TWO shapes are matched, because the method now has two:
 *
 *   1. the original four, each a literal `DealStage.X.equals(targetStage)`;
 *   2. the three quote stages (owner ruling 2026-10-16), gated as ONE check —
 *      `quotationRecipientForStage(targetStage)` returning non-null — rather than three literals.
 *
 * Matching only shape 1 is how this guard goes BLIND: the quote-stage gate landed in the service
 * and the mock stub stayed at four, which is #742 recurring exactly. So the presence of the
 * inverse-mapping call is what pulls {@link javaQuoteRecipientStages} in. If a future gate invents
 * a third shape, this function must learn it too — a gate the guard cannot see is a mock that
 * drifts silently.
 */
function javaFactGatedStages() {
  const method = ticketServiceSource.match(
    // The closing `\n {4}\}` is the method's own dedented brace at four-space indentation — that is what bounds
    // the body, so an `equals(targetStage)` later in the file cannot leak in.
    //
    // Since slice 4 (pipeline import, review round 1) the gate's `if`s live in the pure `stageFactRefusal`, which
    // `requireStageFactsHold` only throws around, so the import asks the SAME question without a copy. Read the
    // body where the gates now are.
    /public static String stageFactRefusal\([^)]*\)\s*\{([\s\S]*?)\n {4}\}/,
  );
  if (!method) throw new Error('could not find stageFactRefusal in TicketService.java');
  // ...and ALSO the thin `requireStageFactsHold` wrapper around it, so a gate someone adds to the wrapper instead
  // of to stageFactRefusal is still seen here (review round 2). Today the wrapper contributes no stage.
  const wrapper = ticketServiceSource.match(
    /private void requireStageFactsHold\([^)]*\)\s*\{([\s\S]*?)\n {4}\}/,
  );
  if (!wrapper) throw new Error('could not find requireStageFactsHold in TicketService.java');
  const body = `${method[1]}\n${wrapper[1]}`;
  const stages = new Set();
  for (const hit of body.matchAll(/DealStage\.([A-Z_]+)\.equals\(targetStage\)/g)) {
    stages.add(hit[1]);
  }
  if (/quotationRecipientForStage\(targetStage\)/.test(body)) {
    for (const stage of javaQuoteRecipientStages()) stages.add(stage);
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
    // Without this, deleting the ORDER block (or a regex that stopped matching) would make every assertion below
    // trivially true against an empty list.
    expect(javaOrder.length).toBeGreaterThanOrEqual(15);
    expect(javaOrder).toContain('QUOTE_OWNER');
    expect(javaSalesGate.length).toBeGreaterThanOrEqual(12);
    expect(javaAccountGate.length).toBeGreaterThanOrEqual(2);
    expect(javaImportGate.length).toBeGreaterThanOrEqual(1);
    // #742's four additions get the same anti-vacuity floor. Without these an empty parse would make every new
    // assertion below trivially true — which is the failure this whole file exists to make impossible.
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

  it('`auto` is exactly DealStage.AUTO_ADVANCED — in both directions', () => {
    // The pre-existing auto assertion only checked that each `auto: true` stage HAS a hint. It could not notice a
    // stage wrongly marked auto, nor an AUTO_ADVANCED stage the fixture had left false. Both matter: `auto` drives
    // whether the UI offers a one-click advance at all.
    expect(
      DEAL_STAGE_CATALOG.stages.filter((stage) => stage.auto).map((stage) => stage.code).sort(),
    ).toEqual([...javaAutoAdvanced].sort());
    // DELIVERY_SCHEDULING is deliberately NOT auto although recordDelivery/reserveStock advance into it — the rep
    // also reaches it by hand as a normal part of the flow. Named explicitly so a future "surely this belongs in
    // AUTO_ADVANCED" edit has to argue with the Javadoc.
    expect(javaAutoAdvanced).not.toContain('DELIVERY_SCHEDULING');
  });
});

/** THE TRIPWIRE #742 IS ACTUALLY ABOUT. The mock's own comment asserted the stub was "STRICTER than production,
 * never looser" and named this as "the exact hazard CLAUDE.md names" — for DELIVERED that claim was simply
 * false. */
describe('the mock fact-gate stub tracks TicketService.requireStageFactsHold', () => {
  it('closes exactly the stages the real service refuses a manual move into', () => {
    expect([...MOCK_FACT_GATED_STAGES].sort()).toEqual(javaFactGated);
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
});

/** Route-aware lookups (deal-route-staging). The backend serves `onRoute` per stage on GET
 * /api/tickets/{id}/actions (StageDecisionDto); these helpers only READ it. ⚠️ The decisions below are built
 * here and passed in DIRECTLY. */
describe('route-aware lookups', () => {
  const catalog = DEAL_STAGE_CATALOG;
  const OWNER_OFF = ['QUOTE_DESIGN_SIDE'];
  const BUYER_OFF = ['QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'AWAITING_BUYER'];
  const decisionsFor = (off) => catalog.stages.map((stage) => ({
    stage: stage.code,
    no: stage.no,
    allowed: !off.includes(stage.code),
    requiresReason: false,
    blockedReason: off.includes(stage.code) ? `ขั้นที่ ${stage.no} ไม่อยู่ในเส้นทางนี้` : null,
    onRoute: !off.includes(stage.code),
  }));

  describe('routePath', () => {
    it('excludes onRoute:false stages and keeps catalog order (buyer-direct)', () => {
      const path = routePath(catalog, decisionsFor(BUYER_OFF));
      expect(path).toHaveLength(11);
      expect(path.map((s) => s.code)).toEqual(
        catalog.stages.map((s) => s.code).filter((code) => !BUYER_OFF.includes(code)),
      );
    });
  });

  describe('routePosition', () => {
    it('buyer-direct at QUOTE_BUYER (S8) is the 4th of 11 — not 8 of 15', () => {
      expect(routePosition(catalog, 'QUOTE_BUYER', decisionsFor(BUYER_OFF)))
        .toEqual({ position: 4, total: 11 });
    });

    it('owner-direct: 14 stages, and S5 is the 4th on the route', () => {
      expect(routePosition(catalog, 'QUOTE_OWNER', decisionsFor(OWNER_OFF)))
        .toEqual({ position: 4, total: 14 });
    });
  });

  describe('nextOnRoute', () => {
    it('buyer-direct at SPEC_APPROVED (S3) offers QUOTE_BUYER (S8)', () => {
      expect(nextOnRoute(catalog, 'SPEC_APPROVED', decisionsFor(BUYER_OFF))?.code).toBe('QUOTE_BUYER');
    });
  });
});

/** Stage identifiers named inside a Java fragment, in order: `DealStage.X` -> X. */
function dealStageNames(fragment) {
  return [...fragment.matchAll(/DealStage\.([A-Z_]+)/g)].map((match) => match[1]);
}

/** DealRoute.OFF_ROUTE: channel -> list of off-route stage codes, parsed from the Java. */
function javaOffRouteTable() {
  const match = dealRouteSource.match(/OFF_ROUTE\s*=\s*Map\.of\(([\s\S]*?)\)\);/);
  if (!match) throw new Error('could not find OFF_ROUTE in DealRoute.java');
  // The lazy match stops at the `));` that closes `Map.of(`, which consumes the LAST `Set.of(`'s own closing
  // paren — restore it, or the final channel's entry silently fails to parse.
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

  it('each served route is DealStage.ORDER minus that channel\'s off-route stages, in order', () => {
    for (const [channel, off] of Object.entries(javaOffRoute)) {
      expect(routes?.[channel], `route of ${channel}`).toEqual(
        javaOrder.filter((code) => !off.includes(code)),
      );
    }
  });
});
