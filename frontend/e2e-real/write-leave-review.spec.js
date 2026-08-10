import { test, expect } from '@playwright/test';
import { apiSessionFor, apiWrite, disposeSessions } from './helpers/api.js';

// ─────────────────────────────────────────────────────────────────────────────
// LEAVE: the quota gate, and the review gate's refusals.
//
// TWO THINGS BLOCKED LEAVE COVERAGE, AND ONLY ONE OF THEM WAS THE SEED.
//
// 1. The seed. Every demo employee had `hire_date IS NULL`, and
//    LeaveService#employeeAnnualQuota returns ZERO when findHireDate is empty, so VACATION
//    (annual_quota_days 6.00) and PERSONAL (3.00) prorated to nothing and every request failed
//    closed on quota. V139__demo_missing_role_personas_and_hire_dates.sql backfills a hire date
//    three years back, past FULL_SERVICE_MONTHS, so the prorating branch returns the full quota.
//    `submitting VACATION now succeeds` below is the regression guard for that.
//
// 2. The service, which the README previously mis-attributed to the seed. This half is now
//    HISTORY on this branch, and the correction matters because the paragraph below used to be
//    the reason this file avoids the review path.
//
//    It USED to read
//
//        LeaveStatus status = systemNote == null ? LeaveStatus.APPROVED : LeaveStatus.AUTO_REJECTED;
//
//    i.e. a submission was auto-approved or auto-rejected on the spot and there was no pending
//    state to review; fixing the hire date changed VACATION from AUTO_REJECTED to APPROVED and
//    could not, by construction, produce anything reviewable.
//
//    The 2026-08-05 "leave requires approval" change replaced that: LeaveService#submit now reads
//
//        LeaveStatus status = systemNote == null ? LeaveStatus.SUBMITTED : LeaveStatus.AUTO_REJECTED;
//
//    so a rule-passing request DOES land SUBMITTED and wait for #approve/#reject. (See
//    #notifyAfterSubmit's javadoc, which removed the then-dead LEAVE_AUTO_APPROVED branch.)
//    Consequences for this file: the guard below no longer pins a status, and the "one consumable
//    SUBMITTED row" constraint described next is now a floor rather than a ceiling — a submission
//    can create reviewable rows, so a future test CAN drive approve → APPROVED without a database
//    reset. Nothing here does that yet; it is recorded so the next person does not re-derive it
//    from the paragraph above and conclude, wrongly, that the path is unreachable.
//
// WHAT THAT LEAVES TESTABLE, AND WHY IT IS STILL WORTH HAVING.
//
// The only SUBMITTED row in the demo database is the one V21 seeds for DEMO-EMP01, and it is
// singular and consumable: approving it once would leave every later run with nothing, which is
// exactly the shared-database trap the other write specs are built to avoid. So this file asserts
// the review gate in the two directions that mutate nothing:
//
//   • the REFUSALS — a non-reviewer's approve/reject is 403 AND leaves the row untouched. Per this
//     suite's standing rule those are the assertions that count anyway: "role X cannot reach what
//     it shouldn't" is what catches a widened gate.
//   • HR's CAPABILITY — the `canReview` flag LeaveService#withCanReviewFlag stamps onto every
//     listed request. That flag is not a role check dressed up: its Javadoc is explicit that it is
//     computed from the SAME decision #approve/#reject gate on (canReviewAll(user) OR
//     isDirectManager). Asserting HR sees `canReview: true` where a peer sees `false` therefore
//     exercises the real gate, without spending the one reviewable row in the database.
//
// This is the counterpart to #199: LeaveService.REVIEW_ALL_ROLES is {hr}, so HR is a leave
// reviewer while OvertimeService refuses HR an overtime approval outright — two adjacent HR
// surfaces with opposite answers for the same actor, which is precisely what an approximating mock
// gets wrong.
//
// STILL NOT COVERED: the successful approve → APPROVED transition. This was deliberate while
// #submit could not produce a reviewable row; since the 2026-08-05 change it is simply not written
// yet, and it is now writable (submit a request, approve it as HR, cancel it back). Recorded in
// e2e-real/README.md rather than faked here.
// ─────────────────────────────────────────────────────────────────────────────

const OWNER = 'employee'; // DEMO-EMP01 — the employee V21's SUBMITTED row belongs to.

/** Today in Asia/Bangkok, the zone the app runs leave periods in. */
function todayInBangkok() {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Bangkok' });
}

/** An ISO date `days` after today in Asia/Bangkok. Never hardcoded — a fixed date eventually
 *  falls into a closed period and fails for a reason unrelated to the gate. */
function daysFromToday(days) {
  const base = new Date(`${todayInBangkok()}T00:00:00Z`);
  base.setUTCDate(base.getUTCDate() + days);
  return base.toISOString().slice(0, 10);
}

/** The seeded SUBMITTED request, read through HR (who can see every employee's). */
async function seededSubmittedRequest(hrSession) {
  const response = await hrSession.get('/api/leave?status=SUBMITTED');
  expect(response.status(), 'GET /api/leave?status=SUBMITTED as hr').toBe(200);
  const { requests } = await response.json();
  const pending = requests.find((request) => request.status === 'SUBMITTED');
  expect(
    pending,
    'no SUBMITTED leave request in the database — V21 seeds exactly one for DEMO-EMP01, and it ' +
      'is consumable: if an earlier run approved or cancelled it, re-seed before relying on this file'
  ).toBeTruthy();
  return pending;
}

test.describe('leave quota and review authorization (real LeaveService)', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  const sessions = {};

  test.beforeAll(async () => {
    for (const role of [OWNER, 'hr', 'ceo', 'import', 'sales']) {
      sessions[role] = await apiSessionFor(role);
    }
  });

  test.afterAll(async () => {
    await disposeSessions(sessions);
  });

  test('submitting VACATION now succeeds — the V139 hire-date regression guard', async () => {
    // Before V139 this returned 400: hire_date was NULL, employeeAnnualQuota returned zero, and
    // the request failed closed with no quota. It now succeeds and lands in a live status.
    // Cancelled immediately so the run leaves no live rows behind.
    const day = daysFromToday(45);
    const response = await apiWrite(sessions[OWNER], 'post', '/api/leave', {
      leaveTypeCode: 'VACATION',
      startDate: day,
      endDate: day,
      reason: 'e2e-real: V139 hire-date quota guard',
    });

    expect(
      response.status(),
      'a 400 here means the hire_date backfill is missing and the prorated quota is zero again'
    ).toBe(200);

    const { request: created } = await response.json();
    // The resulting status is deliberately NOT pinned to one value, and that is a fix rather than a
    // weakening. This guard is about the HIRE-DATE BACKFILL — "quota is non-zero, so the request is
    // accepted at all" — and pinning `APPROVED` additionally encoded which workflow #submit runs.
    // Those two moved apart: leave used to be auto-approved on the spot, and the 2026-08-05 "leave
    // requires approval" change made a rule-passing request land SUBMITTED and wait for
    // #approve/#reject (LeaveService#submit, `status = systemNote == null ? SUBMITTED :
    // AUTO_REJECTED`, and see #notifyAfterSubmit's javadoc on removing the dead LEAVE_AUTO_APPROVED
    // branch). A branch carrying the old service and one carrying the new both satisfy this test's
    // actual subject while disagreeing on that one field, which is exactly how it failed on the
    // main→uat sync for a reason that had nothing to do with hire dates.
    //
    // AUTO_REJECTED is the value that must NOT appear: that is the pre-V139 symptom (a systemNote
    // was attached because the prorated quota was zero), so this assertion still fails loudly if
    // the backfill regresses.
    expect(
      ['SUBMITTED', 'APPROVED'],
      'a rule-passing request must land in a live status; AUTO_REJECTED means the quota gate fired '
        + 'again, which is the hire-date regression this test exists to catch'
    ).toContain(created.status);
    expect(
      Number(created.quotaRemainingBefore),
      'quota must be non-zero — that is the whole point of the backfill'
    ).toBeGreaterThan(0);

    // Cancel as HR, NOT as the owner — for the same reason the status above is not pinned: HR is
    // the one canceller that works whichever status #submit produced. LeaveService#cancel lets a
    // non-reviewer cancel only a SUBMITTED request (`if (!reviewer && !"SUBMITTED".equals(...)) ->
    // 409`), so an owner-issued cancel 409s against an auto-APPROVED row and silently leaves it
    // live; a reviewer may cancel from either SUBMITTED or APPROVED.
    // That leak is not cosmetic: VACATION's annual quota is 6.00 days, and CANCELLED is outside
    // ACTIVE_QUOTA_STATUSES while SUBMITTED and APPROVED are both inside it, so six leaked runs
    // exhaust the quota and every later run fails on a zero balance for a reason that looks
    // nothing like the cause.
    // Asserted rather than fired and forgotten, so a future change to the cancel gate surfaces
    // here instead of as a slow quota leak.
    const cleanup = await apiWrite(sessions.hr, 'post', `/api/leave/${created.id}/cancel`, {});
    expect(cleanup.status(), 'cleanup cancel must succeed or this spec is not re-runnable').toBe(200);
  });

  test('HR is a leave reviewer while an unrelated peer is not', async () => {
    // The positive half, read off the flag #approve/#reject themselves gate on.
    const pending = await seededSubmittedRequest(sessions.hr);
    expect(
      pending.canReview,
      'LeaveService.REVIEW_ALL_ROLES is {hr} — the same role OvertimeService refuses outright (#199)'
    ).toBe(true);

    // The owner is not a reviewer of their own request, which is the easy thing to get wrong.
    const ownerView = await sessions[OWNER].get('/api/leave?status=SUBMITTED');
    expect(ownerView.status()).toBe(200);
    const { requests: own } = await ownerView.json();
    const sameRequest = own.find((request) => request.id === pending.id);
    expect(sameRequest, "the owner can see their own request").toBeTruthy();
    expect(sameRequest.canReview, 'an employee may not review their own leave').toBe(false);
  });

  // The assertions that matter, written wrong-way-round. Each role gets its own test so one
  // failure does not hide the others. None of these mutates: a 403 is refused before it writes,
  // and the status is re-read afterwards to prove it.
  for (const role of ['ceo', 'import', 'sales']) {
    test(`${role} can neither approve nor reject someone else's leave`, async () => {
      const pending = await seededSubmittedRequest(sessions.hr);

      expect(
        (await apiWrite(sessions[role], 'post', `/api/leave/${pending.id}/approve`, {})).status(),
        `${role} is neither in REVIEW_ALL_ROLES ({hr}) nor this employee's manager of record`
      ).toBe(403);
      // Reject travels the same gate; asserting only approve would leave the cheaper-to-get-wrong
      // direction untested.
      expect(
        (await apiWrite(sessions[role], 'post', `/api/leave/${pending.id}/reject`, {})).status(),
        `${role} must not be able to reject either`
      ).toBe(403);

      // A 403 that still mutated would look safe and not be — the trap write-overtime.spec.js
      // guards against too. The request must be untouched, not merely un-approved.
      const after = await seededSubmittedRequest(sessions.hr);
      expect(after.id, 'the refused request must still be SUBMITTED').toBe(pending.id);
    });
  }
});
