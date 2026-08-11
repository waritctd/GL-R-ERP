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
// 2. The service, which the README previously mis-attributed to the seed. **LeaveService#submit
//    never produces a SUBMITTED request at all.** Line ~286 reads
//
//        LeaveStatus status = systemNote == null ? LeaveStatus.APPROVED : LeaveStatus.AUTO_REJECTED;
//
//    so a submission is auto-approved or auto-rejected on the spot; there is no pending state to
//    review. Fixing the hire date changed VACATION from AUTO_REJECTED to APPROVED — it did not,
//    and could not, produce anything reviewable. No amount of seed work reaches the review path
//    through the API, because that path is not reachable from #submit by construction.
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
// STILL NOT COVERED, deliberately: the successful approve → APPROVED transition. Driving it needs
// either a reviewable row the API cannot create, or a per-test database reset this suite does not
// have. Recorded in e2e-real/README.md rather than faked here.
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

/**
 * `daysFromToday`, then rolled forward to the next Mon–Fri.
 *
 * WHY THIS EXISTS. A leave request whose range contains no working day is rejected 400 by
 * LeaveService#workingDaysBetween ("ช่วงวันลาต้องมีวันทำงานอย่างน้อย 1 วัน"), so a single-day
 * request landing on a weekend fails for a reason that has nothing to do with what these tests
 * assert. A FIXED offset lands on a weekend roughly two days in every seven, which made this file
 * fail on a rolling ~2-day-a-week schedule — and the failure moved with the clock, not the code.
 *
 * The Bangkok base is what makes that non-obvious: after 17:00 UTC the Bangkok date is already
 * tomorrow, so the same CI job picks a different weekday depending on the hour it happens to run.
 * On 2026-08-11 the runs before 17:00Z chose Fri 25 Sep and passed; the runs after chose Sat 26
 * Sep and failed, with no code change between them. Confirmed against PRs #690, #691 and #692.
 *
 * Weekends only. Public holidays are NOT skipped: they live in hr.holiday, vary by year, and
 * would need an API round-trip to resolve. If this ever fails on a Mon–Fri, check the calendar
 * before assuming the gate under test broke — and prefer fixing it here over widening the
 * assertion below.
 */
function workingDayFromToday(days) {
  let iso = daysFromToday(days);
  for (let i = 0; i < 7; i += 1) {
    const dow = new Date(`${iso}T00:00:00Z`).getUTCDay();
    if (dow !== 0 && dow !== 6) return iso;
    iso = daysFromToday(days + i + 1);
  }
  throw new Error(`no weekday found within 7 days of +${days}`);
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
    // the request failed closed with no quota. It now lands APPROVED, which is #submit's normal
    // outcome for a request that breaks no rule (there is no SUBMITTED path — see this file's
    // header). Cancelled immediately so the run leaves no live rows behind.
    const day = workingDayFromToday(45);
    const response = await apiWrite(sessions[OWNER], 'post', '/api/leave', {
      leaveTypeCode: 'VACATION',
      startDate: day,
      endDate: day,
      reason: 'e2e-real: V139 hire-date quota guard',
    });

    // Read the body BEFORE asserting: on a 400 the assertion below is what fails the test, and
    // without this the response is discarded unread, which is why no run ever logged why.
    const body = await response.text();

    expect(
      response.status(),
      // The message reports the SERVER's reason rather than guessing one. The previous version
      // asserted "the hire_date backfill is missing and the prorated quota is zero again" — a
      // single hypothesis stated as fact. When this test began failing for an unrelated reason
      // (the date landed on a Saturday, see workingDayFromToday), that message sent three separate
      // investigations after a backfill the CI logs showed had applied correctly. A failure
      // message that names the wrong cause is worse than one that names none.
      `POST /api/leave expected 200, got ${response.status()}: ${body}`
    ).toBe(200);

    // Parsed from the text already read above rather than a second response.json() — one read,
    // one source of truth for what the server actually returned.
    const { request: created } = JSON.parse(body);
    expect(created.status).toBe('APPROVED');
    expect(
      Number(created.quotaRemainingBefore),
      'quota must be non-zero — that is the whole point of the backfill'
    ).toBeGreaterThan(0);

    // Cancel as HR, NOT as the owner. LeaveService#cancel lets a non-reviewer cancel only a
    // SUBMITTED request (`if (!reviewer && !"SUBMITTED".equals(...)) -> 409`), and #submit just
    // auto-APPROVED this one — so an owner-issued cancel 409s and silently leaves the row live.
    // That leak is not cosmetic: VACATION's annual quota is 6.00 days, and CANCELLED is outside
    // ACTIVE_QUOTA_STATUSES while APPROVED is inside it, so six leaked runs exhaust the quota and
    // every later run fails on a zero balance for a reason that looks nothing like the cause.
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
