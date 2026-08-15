import { test, expect } from '@playwright/test';
import { apiWrite, uatSessionFor, disposeSessions } from './helpers/api.js';
import { UAT_SALES_ROLES } from './helpers/uat-accounts.js';
import {
  advanceStage,
  cancelDeal,
  createDeal,
  readDeal,
  runId,
  setTracking,
  stageDecisions,
  stageHistory,
} from './helpers/sales.js';

// ─────────────────────────────────────────────────────────────────────────────────────────────
// SLICE 4 — the REFUSAL matrix: proving the things that must NOT be allowed are actually refused.
//
// Slices 2-3 only ever prove a deal CAN reach a stage. CLAUDE.md: "Write the cases wrong-way-
// round. Assert the caller cannot reach what they shouldn't." This is that half.
//
// ── The ordering trap ────────────────────────────────────────────────────────────────────────
// TicketService.updateStage (ticket/TicketService.java:1497-1534) evaluates, IN THIS ORDER:
//
//     write-access -> lifecycle -> same-stage -> FACT GATE -> NOTE RULE -> READINESS
//
// (requireStageMoveAllowed, :1555-1574, runs the first four; updateStage itself runs the note
// check right after it returns, :1512-1516, then the readiness gate only for a forward move,
// :1527-1529.) Two consequences shape every test below:
//
//   - A FACT-GATED target (ORDER_RECEIVED, DEPOSIT_RECEIVED, DELIVERED, CLOSED_PAID) always
//     returns the 409 fact message, never the 400 note message, even when a note would also have
//     been required — requireStageFactsHold runs before the note check ever does. Note-rule tests
//     below deliberately target stages OUTSIDE those four.
//   - A missing note beats a missing follow-up/activity: the note check (400) runs before the
//     readiness check (400), so a test of one must not incidentally trip the other.
//
// ── The note rule, precisely (DealStage.requiresJustification, DealStage.java:279-300) ────────
//   - Forward: a note is required ONLY if a MANDATORY stage lies STRICTLY BETWEEN from and to
//     (ORDER.subList(from+1, to) — exclusive of `to`, so the target itself is never "skipped").
//     MANDATORY = {NEGOTIATION, ORDER_RECEIVED, DELIVERY_SCHEDULING, DELIVERED, CLOSED_PAID}.
//   - Backward: always required, EXCEPT the one allowlisted pair QUOTE_DESIGN_SIDE -> SPEC_APPROVED
//     (DealStage.isRoutineBackwardMove, :254-256).
//
// ── Every test creates its own deal (never touches UAT-TKT-*/UAT-GOLD-01) and re-reads after ───
// A refusal that silently mutated the row would be worse than no rule at all, so every test below
// re-reads the deal (or the specific field a call could have changed) after a refused call and
// asserts nothing moved. `advanceStage` (helpers/sales.js) is the one function used for both the
// refused and the accepted call in a pair — same request shape, only the answer differs — which is
// what proves a refusal is the RULE, not an artefact of a malformed request.
// ─────────────────────────────────────────────────────────────────────────────────────────────

// A literal mirror of DealStage.java, for refusal #6 ONLY. Deliberately NOT imported from
// helpers/sales.js: the point of #6 is an INDEPENDENT recomputation to cross-check against what
// the live server actually returns from GET /api/tickets/{id}/actions, so this belongs in the
// test that checks agreement, not in a shared helper the server-calling code could take a
// shortcut through. Transcribed from backend/src/main/java/th/co/glr/hr/ticket/DealStage.java at
// the revision this spec was written against — ORDER :67-73, MANDATORY :103-104,
// isRoutineBackwardMove :254-256, requiresJustification :279-300. If DealStage.java's constants
// ever change, this block must be updated by hand; that hand-update is exactly the kind of drift
// refusal #6 exists to catch (a change on one side with no corresponding change on the other).
const DEAL_STAGE_ORDER = [
  'LEAD_APPROACH', 'PRESENTATION',
  'SPEC_APPROVED', 'QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF',
  'AWAITING_BUYER', 'QUOTE_BUYER', 'NEGOTIATION',
  'ORDER_RECEIVED', 'DEPOSIT_RECEIVED', 'PROCUREMENT',
  'DELIVERY_SCHEDULING', 'DELIVERED', 'CLOSED_PAID',
];
const MANDATORY_STAGES = new Set([
  'NEGOTIATION', 'ORDER_RECEIVED', 'DELIVERY_SCHEDULING', 'DELIVERED', 'CLOSED_PAID',
]);

/** DealStage.isRoutineBackwardMove (DealStage.java:254-256) — the one allowlisted backward pair. */
function isRoutineBackwardMove(fromStage, toStage) {
  return fromStage === 'QUOTE_DESIGN_SIDE' && toStage === 'SPEC_APPROVED';
}

/** DealStage.requiresJustification (DealStage.java:279-300), transcribed literally. */
function requiresJustification(fromStage, toStage) {
  const from = DEAL_STAGE_ORDER.indexOf(fromStage);
  const to = DEAL_STAGE_ORDER.indexOf(toStage);
  if (from < 0 || to < 0 || from === to) {
    return false;
  }
  if (to < from) {
    return !isRoutineBackwardMove(fromStage, toStage);
  }
  // Strictly between: the stages the deal steps OVER. `to` itself is being landed on, not
  // skipped, and `from` has already happened.
  return DEAL_STAGE_ORDER.slice(from + 1, to).some((stage) => MANDATORY_STAGES.has(stage));
}

test.describe('@uat-sales refusal matrix — stage/note/readiness/role guards', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  const sessions = {};
  /** Every deal this file creates, so afterAll can take them back even if a test threw. */
  const created = [];

  test.beforeAll(async () => {
    for (const role of UAT_SALES_ROLES) {
      sessions[role] = await uatSessionFor(role);
    }
  });

  test.afterAll(async () => {
    // Best-effort, outside any expect(): teardown must never turn a passing assertion red, and
    // must never mask a real failure with a second one. Mirrors slices 2-3.
    for (const ticketId of created) {
      await cancelDeal(sessions, ticketId);
    }
    await disposeSessions(sessions);
  });

  // ── 1 + 2. Note rule, forward: refused without a note, positive control WITH one ────────────
  test('note rule: forward across a MANDATORY stage requires a note; the identical move WITH one succeeds', async () => {
    test.setTimeout(90_000);
    const deal = await createDeal(sessions, { caseName: 'refusal1-2 note forward' });
    created.push(deal.id);

    // LEAD_APPROACH -> DELIVERY_SCHEDULING steps over NEGOTIATION and ORDER_RECEIVED, both
    // MANDATORY — and DELIVERY_SCHEDULING itself is NOT one of the four fact-gated targets
    // (ORDER_RECEIVED/DEPOSIT_RECEIVED/DELIVERED/CLOSED_PAID), so this trips the NOTE rule
    // cleanly: requireStageFactsHold (which runs first) has nothing to say about this target.
    const refused = await advanceStage(sessions, 'sales', deal.id, 'DELIVERY_SCHEDULING', {
      note: null,
      expect: 400,
    });
    const refusedBody = await refused.json();
    expect(refusedBody.message, 'DealStage.requiresJustification forward message').toContain(
      'การข้ามขั้นตอนต้องระบุเหตุผล'
    );

    // A refusal must not have mutated anything.
    const untouched = await readDeal(sessions, 'sales', deal.id);
    expect(untouched.summary.salesStage, 'a refused note-rule move must not move the stage').toBe(
      'LEAD_APPROACH'
    );
    const untouchedHistory = await stageHistory(sessions, 'sales', deal.id);
    expect(untouchedHistory, 'a refused move must emit no STAGE_CHANGED event').toEqual([]);

    // Positive control: the SAME move, WITH a note, must succeed — proves the refusal above was
    // caused by the missing note specifically, not by some other malformed part of the request.
    // ⚠️ MUTATION-CHECK TARGET: refusal #1 is the `note: null` call above. Supplying a note there
    // instead (satisfying the rule) should turn this whole test red — see the PR body / final
    // report for the recorded run.
    await advanceStage(sessions, 'sales', deal.id, 'DELIVERY_SCHEDULING', {
      note: `${runId()} refusal1-2 justification`,
    });
    const moved = await readDeal(sessions, 'sales', deal.id);
    expect(moved.summary.salesStage).toBe('DELIVERY_SCHEDULING');
  });

  // ── 3. Note rule, backward: a DIFFERENT message than the forward one ────────────────────────
  test('note rule: backward requires a note, and the message differs from the forward one', async () => {
    test.setTimeout(90_000);
    const deal = await createDeal(sessions, { caseName: 'refusal3 note backward' });
    created.push(deal.id);

    // LEAD_APPROACH -> PRESENTATION is adjacent (no MANDATORY stage strictly between), so this
    // setup hop needs no note.
    await advanceStage(sessions, 'sales', deal.id, 'PRESENTATION');

    // PRESENTATION -> LEAD_APPROACH is backward and NOT the allowlisted QUOTE_DESIGN_SIDE ->
    // SPEC_APPROVED pair, so DealStage.requiresJustification is true here too — but the message
    // must be the BACKWARD one, not the forward one from refusal #1.
    const refused = await advanceStage(sessions, 'sales', deal.id, 'LEAD_APPROACH', {
      note: null,
      expect: 400,
    });
    const refusedBody = await refused.json();
    expect(refusedBody.message, 'DealStage.requiresJustification backward message').toContain(
      'การย้อนสถานะกลับต้องระบุเหตุผล'
    );
    expect(refusedBody.message, 'must NOT be the forward message').not.toContain(
      'การข้ามขั้นตอนต้องระบุเหตุผล'
    );

    const untouched = await readDeal(sessions, 'sales', deal.id);
    expect(untouched.summary.salesStage, 'a refused backward move must not move the stage').toBe(
      'PRESENTATION'
    );
  });

  // ── 4. The one routine backward exception ───────────────────────────────────────────────────
  test('note rule: QUOTE_DESIGN_SIDE -> SPEC_APPROVED is the single hand-patched backward exception', async () => {
    test.setTimeout(90_000);
    const deal = await createDeal(sessions, { caseName: 'refusal4 routine backward' });
    created.push(deal.id);

    // LEAD_APPROACH -> QUOTE_DESIGN_SIDE steps over only PRESENTATION and SPEC_APPROVED, neither
    // MANDATORY, so this setup hop needs no note either.
    await advanceStage(sessions, 'sales', deal.id, 'QUOTE_DESIGN_SIDE');

    // The allowlisted pair (DealStage.isRoutineBackwardMove) — no note needed even though it is
    // backward.
    await advanceStage(sessions, 'sales', deal.id, 'SPEC_APPROVED', { note: null });

    const moved = await readDeal(sessions, 'sales', deal.id);
    expect(moved.summary.salesStage).toBe('SPEC_APPROVED');
    const history = await stageHistory(sessions, 'sales', deal.id);
    expect(history, 'both hops recorded, neither carrying a note').toEqual([
      { from: 'LEAD_APPROACH', to: 'QUOTE_DESIGN_SIDE', message: null },
      { from: 'QUOTE_DESIGN_SIDE', to: 'SPEC_APPROVED', message: null },
    ]);
  });

  // ── 5. Fact gate: no CEO break-glass ─────────────────────────────────────────────────────────
  test('fact gate: ORDER_RECEIVED cannot be claimed manually without confirmCustomer — no CEO break-glass', async () => {
    test.setTimeout(90_000);
    const deal = await createDeal(sessions, { caseName: 'refusal5 fact gate order received' });
    created.push(deal.id);

    // requireStageFactsHold (TicketService.java:1701-1718) runs BEFORE the note rule inside
    // requireStageMoveAllowed, so this 409s on the fact even though LEAD_APPROACH -> ORDER_RECEIVED
    // also crosses NEGOTIATION (MANDATORY) and would otherwise need a note too — the fact gate
    // wins and the note-rule message is never reached. customerOrderVerified (:1736-1738) requires
    // a valid paymentStatus, which a freshly created deal does not have (null).
    const salesRefused = await advanceStage(sessions, 'sales', deal.id, 'ORDER_RECEIVED', {
      note: `${runId()} a note that would satisfy the rule but not the fact`,
      expect: 409,
    });
    const salesBody = await salesRefused.json();
    expect(salesBody.message).toContain('ยังไม่ได้ยืนยันคำสั่งซื้อของลูกค้า');

    // Deliberately no CEO break-glass (requireStageFactsHold's own Javadoc says so explicitly,
    // TicketService.java:1695-1699). requireStageWriteAccess lets the CEO through for ANY target
    // stage (:2061-2063), but requireStageFactsHold runs on every actor identically — it never
    // consults the role at all.
    const ceoRefused = await advanceStage(sessions, 'ceo', deal.id, 'ORDER_RECEIVED', {
      note: `${runId()} ceo has no break-glass either`,
      expect: 409,
    });
    const ceoBody = await ceoRefused.json();
    expect(ceoBody.message).toContain('ยังไม่ได้ยืนยันคำสั่งซื้อของลูกค้า');

    const untouched = await readDeal(sessions, 'sales', deal.id);
    expect(untouched.summary.salesStage, 'neither refused fact-gate attempt may move the stage').toBe(
      'LEAD_APPROACH'
    );
  });

  // ── 6. stageDecisions.requiresReason agrees with DealStage.requiresJustification ────────────
  test('stageDecisions.requiresReason agrees with an independent DealStage.requiresJustification recomputation, for all 15 stages', async () => {
    test.setTimeout(60_000);
    const deal = await createDeal(sessions, { caseName: 'refusal6 stagedecisions mirror' });
    created.push(deal.id);

    const decisions = await stageDecisions(sessions, 'sales', deal.id);
    expect(decisions, 'one verdict per DealStage.ORDER entry').toHaveLength(DEAL_STAGE_ORDER.length);

    // Do NOT skip an entry reported as blocked (allowed:false) — TicketService.stageDecisions
    // calls DealStage.requiresJustification unconditionally (ticket/TicketService.java:1643), so
    // requiresReason is populated for a blocked stage too, and this loop checks every one of the
    // 15 without filtering on `allowed`.
    for (const target of DEAL_STAGE_ORDER) {
      const decision = decisions.find((d) => d.stage === target);
      expect(decision, `stageDecisions must include an entry for ${target}`).toBeTruthy();
      expect(
        decision.requiresReason,
        `${target}: server requiresReason vs requiresJustification('LEAD_APPROACH', '${target}') recomputed here`
      ).toBe(requiresJustification('LEAD_APPROACH', target));
    }
  });

  // ── 7. Readiness: no nextFollowUpAt blocks any forward move ─────────────────────────────────
  test('readiness: no nextFollowUpAt blocks any forward move', async () => {
    test.setTimeout(60_000);
    const deal = await createDeal(sessions, { caseName: 'refusal7 readiness followup' });
    created.push(deal.id);

    // PUT /tracking is a FULL REPLACE — send exactly {nextFollowUpAt: null} to clear it and
    // nothing else (see setTracking's own header in helpers/sales.js).
    await setTracking(sessions, deal.id, { nextFollowUpAt: null });

    // LEAD_APPROACH -> PRESENTATION needs no note (adjacent, nothing MANDATORY between), so this
    // isolates the refusal to the readiness gate alone. advanceStage logs a fresh activity before
    // every call, which further isolates this 400 to the missing follow-up date specifically —
    // if the activity clock were also stale, this 400 would be ambiguous about which half of the
    // gate's OR condition tripped.
    const response = await advanceStage(sessions, 'sales', deal.id, 'PRESENTATION', { expect: 400 });
    const body = await response.json();
    expect(body.message).toContain('ต้องระบุวันติดตามครั้งถัดไป');

    const untouched = await readDeal(sessions, 'sales', deal.id);
    expect(untouched.summary.salesStage, 'a refused readiness move must not move the stage').toBe(
      'LEAD_APPROACH'
    );
  });

  // ── 8. Readiness: no NEW activity since the last stage change ───────────────────────────────
  test('readiness: no NEW activity since the last stage change blocks the next forward move; logging one unblocks it', async () => {
    test.setTimeout(90_000);
    const deal = await createDeal(sessions, { caseName: 'refusal8 readiness activity clock' });
    created.push(deal.id);

    // Establish a STAGE_CHANGED baseline: LEAD_APPROACH -> PRESENTATION (adjacent, no note
    // needed). advanceStage's own activity-log satisfies THIS move's readiness gate.
    await advanceStage(sessions, 'sales', deal.id, 'PRESENTATION');

    // Immediately try again with NO new activity logged since that STAGE_CHANGED event.
    // Deliberately bypasses advanceStage here — it always logs an activity first (see its own
    // doc) and would make exactly this case unreachable. PRESENTATION -> SPEC_APPROVED needs no
    // note (adjacent), so this isolates the refusal to the activity-clock half of the readiness
    // gate: TicketRepository.hasActivitySinceLastStageChange (TicketRepository.java:1945-1960)
    // compares against MAX(STAGE_CHANGED.created_at), which the move above just advanced past
    // the one activity this deal has ever had logged.
    const blocked = await apiWrite(sessions.sales, 'post', `/api/tickets/${deal.id}/stage`, {
      stage: 'SPEC_APPROVED',
      note: null,
    });
    expect(blocked.status(), 'forward move with no activity since the last STAGE_CHANGED').toBe(400);
    const blockedBody = await blocked.json();
    expect(blockedBody.message).toContain('บันทึกกิจกรรมอย่างน้อย 1 รายการ');

    const stillStuck = await readDeal(sessions, 'sales', deal.id);
    expect(stillStuck.summary.salesStage, 'a refused readiness move must not move the stage').toBe(
      'PRESENTATION'
    );

    // Now retry the SAME move through advanceStage, whose own activity-log satisfies the gate —
    // the positive control that isolates the refusal above to the activity clock specifically.
    await advanceStage(sessions, 'sales', deal.id, 'SPEC_APPROVED');
    const moved = await readDeal(sessions, 'sales', deal.id);
    expect(moved.summary.salesStage).toBe('SPEC_APPROVED');
  });

  // ── 9. Role: import cannot move a SALES-gated stage ─────────────────────────────────────────
  test('role: import cannot move a SALES-gated stage', async () => {
    test.setTimeout(60_000);
    const deal = await createDeal(sessions, { caseName: 'refusal9 import sales stage' });
    created.push(deal.id);

    // PRESENTATION is in DealStage.SALES_TARGET_STAGES; `import` is in none of the three target
    // sets requireStageWriteAccess checks for it (TicketService.java:2059-2081), so it falls
    // through to the trailing 403 regardless of ownership — this deal is owned by `sales` anyway.
    const response = await advanceStage(sessions, 'import', deal.id, 'PRESENTATION', { expect: 403 });
    const body = await response.json();
    expect(body.message).toContain('ไม่มีสิทธิ์เข้าถึงรายการนี้');

    const untouched = await readDeal(sessions, 'sales', deal.id);
    expect(untouched.summary.salesStage, 'a refused role move must not move the stage').toBe(
      'LEAD_APPROACH'
    );
  });

  // ── 10. Role: non-owner sales_manager CAN move a sales stage (deliberate asymmetry) ─────────
  test('role: sales_manager may move a sales-gated stage on a deal it does not own', async () => {
    test.setTimeout(60_000);
    // Owned by `sales` (createDeal always uses sessions.sales), moved by `sales_manager` — a
    // DIFFERENT actor with no ownership relationship to this deal at all. requireStageWriteAccess
    // grants `sales_manager` any SALES_TARGET_STAGES move unconditionally (TicketService.java:
    // 2064-2067) — no ownership check, unlike the plain `sales` branch right below it.
    const deal = await createDeal(sessions, { caseName: 'refusal10 non-owner manager' });
    created.push(deal.id);

    await advanceStage(sessions, 'sales_manager', deal.id, 'PRESENTATION');

    const moved = await readDeal(sessions, 'sales', deal.id);
    expect(moved.summary.salesStage).toBe('PRESENTATION');
  });

  // ── 11. Role: cancel is owner-ONLY — sales_manager is refused (unlike markLost) ─────────────
  test('role: cancel is owner-only — sales_manager is refused although markLost would allow it', async () => {
    test.setTimeout(60_000);
    const deal = await createDeal(sessions, { caseName: 'refusal11 cancel owner only' });
    created.push(deal.id);

    // TicketService.cancel (ticket/TicketService.java:2113-2146) compares createdById to the
    // actor directly (:2123-2125) — no requireRole call, and NOT requireDealOwnership (:2084-2090)
    // either, which DOES allow sales_manager/ceo. Cancel and markLost sit in the same UI danger
    // zone but are deliberately not symmetric.
    const response = await apiWrite(sessions.sales_manager, 'post', `/api/tickets/${deal.id}/cancel`, {
      reason: 'OTHER',
      note: `${runId()} refusal11 sales_manager should not be able to do this`,
    });
    expect(
      response.status(),
      'sales_manager POST /cancel — owner-only, sales_manager is not the owner'
    ).toBe(403);
    const body = await response.json();
    expect(body.message).toContain('ไม่มีสิทธิ์เข้าถึงรายการนี้');

    const untouched = await readDeal(sessions, 'sales', deal.id);
    expect(untouched.summary.lifecycle, 'a refused cancel must not touch lifecycle').toBe('ACTIVE');
  });

  // ── 12. Role: CEO cannot self-confirm close-readiness ───────────────────────────────────────
  test('role: CEO cannot confirm close-readiness — that step belongs to account alone', async () => {
    test.setTimeout(60_000);
    const deal = await createDeal(sessions, { caseName: 'refusal12 ceo close confirm' });
    created.push(deal.id);

    // CLOSE_CONFIRM_ROLES = Set.of("account") (TicketService.java:57); confirmCloseReady checks
    // it FIRST, before even fetching the ticket (:605-606), so this 403s regardless of the deal's
    // state. Deliberately excludes CEO so the two-signature close (account confirms, then CEO
    // verifies — verifyClose, :642-644, requires CEO_ROLES) cannot collapse into one person
    // confirming their own review.
    const response = await apiWrite(sessions.ceo, 'post', `/api/tickets/${deal.id}/close/confirm`);
    expect(response.status(), 'ceo POST /close/confirm').toBe(403);
    const body = await response.json();
    expect(body.message).toContain('ไม่มีสิทธิ์เข้าถึงรายการนี้');

    const untouched = await readDeal(sessions, 'sales', deal.id);
    expect(
      untouched.summary.closeConfirmedAt ?? null,
      'a refused confirm must not stamp closeConfirmedAt'
    ).toBeNull();
  });

  // ── 13. Entry channel: UNSPECIFIED is legal as stored, illegal as input ─────────────────────
  test('entry channel: UNSPECIFIED is legal as stored but illegal as input', async () => {
    test.setTimeout(60_000);
    const deal = await createDeal(sessions, { caseName: 'refusal13 entry channel unspecified' });
    created.push(deal.id);

    // EntryChannel.isValid(UNSPECIFIED) is true — it IS one of the four VALID literals — but
    // setEntryChannel excludes it a second time explicitly (TicketService.java:2005): "once a
    // channel has been stated it must not be possible to un-state it." createDeal omits
    // entryChannel entirely, so this deal already stores UNSPECIFIED as the V144 default
    // (TicketRepository.java:257-262) — this call attempts to (re-)state the same unstated value.
    const response = await apiWrite(sessions.sales, 'post', `/api/tickets/${deal.id}/entry-channel`, {
      value: 'UNSPECIFIED',
      note: null,
    });
    expect(response.status(), 'sales POST /entry-channel value=UNSPECIFIED').toBe(400);
    const body = await response.json();
    expect(body.message).toContain('ไม่รองรับช่องทางรับงาน');

    const untouched = await readDeal(sessions, 'sales', deal.id);
    expect(
      untouched.summary.entryChannel,
      'a refused entry-channel call must not change the stored value'
    ).toBe('UNSPECIFIED');
  });
});
