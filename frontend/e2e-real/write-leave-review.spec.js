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
// 2. The service. At authoring time this was the harder blocker, and no seed fix could route
//    around it: **LeaveService#submit never produced a SUBMITTED request at all.** It read
//
//        LeaveStatus status = systemNote == null ? LeaveStatus.APPROVED : LeaveStatus.AUTO_REJECTED;
//
//    so a submission was auto-approved or auto-rejected on the spot — there was no pending state
//    to review, and fixing the hire date only moved VACATION from AUTO_REJECTED to APPROVED; it
//    did not, and could not, produce anything reviewable. No amount of seed work reached the
//    review path through the API, because that path was not reachable from #submit by construction.
//
//    THIS IS NO LONGER TRUE. Leave requires approval (2026-08-05) changed that line to
//
//        LeaveStatus status = systemNote == null ? LeaveStatus.SUBMITTED : LeaveStatus.AUTO_REJECTED;
//
//    A rule-passing submission now lands SUBMITTED — awaiting a human reviewer — instead of
//    auto-APPROVED, so the review path IS reachable through the API. `submitting VACATION now
//    succeeds` below submits a fresh request, asserts SUBMITTED (not APPROVED), and cancels it
//    itself so the run leaves no live row behind.
//
// WHAT THAT LEAVES TESTABLE, AND WHY IT IS STILL WORTH HAVING.
//
// V21 seeds one persistent SUBMITTED row for DEMO-EMP01, and it is singular and consumable:
// approving or rejecting it would leave every later run with nothing, which is exactly the
// shared-database trap the other write specs are built to avoid. (The create-then-cancel test
// below also produces a SUBMITTED row of its own now that #submit can create one on demand — but
// it retires that row itself before the test ends, so it is never a second persistent one.)
// So the REFUSAL and CAPABILITY tests below read V21's row rather than mint their own, and assert
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
// This is the counterpart to #199: LeaveService.REVIEW_ALL_ROLES is {hr, ceo} (ceo joined
// 2026-09-01, PR #885) — hr is a leave reviewer while OvertimeService refuses hr an overtime
// approval outright, two adjacent HR surfaces with opposite answers for the same actor, which is
// precisely what an approximating mock gets wrong. ceo's leave-reviewer reach is new, INTENDED
// behaviour, not a divergence to guard against — "ceo can now approve and reject someone else's
// leave" below pins it with a real mutation, on requests this file mints and retires itself, and
// the denied-roles loop further down shrank to `['import', 'sales']` accordingly: asserting ceo
// gets 403 there would now be pinning the OLD rule.
//
// STILL NOT FULLY COVERED here: the successful approve → APPROVED transition now has ONE driven
// case — "ceo can now approve and reject someone else's leave" below, added alongside ceo joining
// REVIEW_ALL_ROLES. It submits its own request, has ceo approve it, re-reads APPROVED off the
// server (never the write response), and cancels through HR to give the quota back — then repeats
// the same shape for reject, on a second request of its own. REJECTED needs no such cleanup: it
// sits outside ACTIVE_QUOTA_STATUSES, and LeaveService#cancel refuses anything that is not
// SUBMITTED or APPROVED (409), so a REJECTED row is already inert, the same way a CANCELLED one
// is. What is NOT built is the GENERAL case — hr approving, or any actor/path other than this
// one — so the broader submit → approve → reconcile-quota story stays recorded in
// e2e-real/README.md as a follow-up rather than faked here.
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

/**
 * Re-reads one request by id, through `session`, regardless of its current status — used to
 * prove a mutation actually landed rather than trusting the write response. Unlike
 * `seededSubmittedRequest`, this does not filter by status: the whole point of calling it is
 * finding out what status the row is in NOW.
 */
async function requestById(session, id) {
  const response = await session.get('/api/leave');
  expect(response.status(), 'GET /api/leave').toBe(200);
  const { requests } = await response.json();
  const found = requests.find((request) => request.id === id);
  expect(found, `leave request ${id} not found in the default list window`).toBeTruthy();
  return found;
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
    // the request failed closed with no quota. It now lands SUBMITTED, which is #submit's normal
    // outcome for a request that breaks no rule (there IS a SUBMITTED path now — see this file's
    // header). Cancelled in the finally block below so the run leaves no live row behind even if
    // an assertion here throws first.
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

    try {
      expect(created.status).toBe('SUBMITTED');
      expect(
        Number(created.quotaRemainingBefore),
        'quota must be non-zero — that is the whole point of the backfill'
      ).toBeGreaterThan(0);
    } finally {
      // Cancel as HR, NOT as the owner — though both are legal now. LeaveService#cancel lets a
      // non-reviewer (the owner) cancel only while still SUBMITTED (`if (!reviewer &&
      // !"SUBMITTED".equals(...)) -> 409`), and #submit leaves this row exactly there, so an
      // owner-issued cancel would succeed too. HR is kept anyway: a REVIEWER's cancel is valid
      // regardless of status (SUBMITTED or APPROVED), which is the more robust choice in a
      // finally block that must clean up whatever state the assertions above left the row in.
      //
      // That cleanup is not cosmetic: VACATION's annual quota is 6.00 days, and CANCELLED is
      // outside ACTIVE_QUOTA_STATUSES while SUBMITTED (like APPROVED before this branch) is
      // inside it, so a handful of leaked runs exhausts the quota and every later run fails on a
      // zero balance for a reason that looks nothing like the cause. Asserted rather than fired
      // and forgotten, so a future change to the cancel gate surfaces here instead of as a slow
      // quota leak.
      const cleanup = await apiWrite(sessions.hr, 'post', `/api/leave/${created.id}/cancel`, {});
      expect(cleanup.status(), 'cleanup cancel must succeed or this spec is not re-runnable').toBe(200);
    }
  });

  test("HR and CEO are leave reviewers while an unrelated peer is not", async () => {
    // The positive half, read off the flag #approve/#reject themselves gate on.
    const pending = await seededSubmittedRequest(sessions.hr);
    expect(
      pending.canReview,
      'LeaveService.REVIEW_ALL_ROLES is {hr, ceo} — hr is also the role OvertimeService refuses ' +
        'outright (#199)'
    ).toBe(true);

    // ceo joined REVIEW_ALL_ROLES on 2026-09-01 (PR #885) — same flag, same non-mutating read, so
    // this reads V21's shared row without spending it, exactly like the hr check just above.
    const ceoView = await sessions.ceo.get('/api/leave?status=SUBMITTED');
    expect(ceoView.status()).toBe(200);
    const { requests: ceoRequests } = await ceoView.json();
    const ceoSees = ceoRequests.find((request) => request.id === pending.id);
    expect(ceoSees, "ceo can see the pending request too").toBeTruthy();
    expect(
      ceoSees.canReview,
      'ceo is now in REVIEW_ALL_ROLES and must see canReview: true, same as hr'
    ).toBe(true);

    // The owner is not a reviewer of their own request, which is the easy thing to get wrong.
    const ownerView = await sessions[OWNER].get('/api/leave?status=SUBMITTED');
    expect(ownerView.status()).toBe(200);
    const { requests: own } = await ownerView.json();
    const sameRequest = own.find((request) => request.id === pending.id);
    expect(sameRequest, "the owner can see their own request").toBeTruthy();
    expect(sameRequest.canReview, 'an employee may not review their own leave').toBe(false);
  });

  test("ceo can now approve and reject someone else's leave", async () => {
    // Unlike the capability check above, this MUTATES — so unlike seededSubmittedRequest's
    // callers, it cannot read V21's shared row: consuming it here would starve the denied-roles
    // loop below (see this file's header). Each half below mints, acts on, and disposes of its
    // OWN request, on its own date, so the run leaves the database exactly as it found it and the
    // spec stays re-runnable — the same discipline `submitting VACATION now succeeds` uses above.

    // ── approve ──────────────────────────────────────────────────────────────────────────────
    const approveDay = workingDayFromToday(50);
    const approveCreate = await apiWrite(sessions[OWNER], 'post', '/api/leave', {
      leaveTypeCode: 'VACATION',
      startDate: approveDay,
      endDate: approveDay,
      reason: 'e2e-real: ceo review-gate, approve path (PR #885)',
    });
    const approveCreateBody = await approveCreate.text();
    expect(
      approveCreate.status(),
      `POST /api/leave expected 200, got ${approveCreate.status()}: ${approveCreateBody}`
    ).toBe(200);
    const { request: toApprove } = JSON.parse(approveCreateBody);
    expect(toApprove.status).toBe('SUBMITTED');

    try {
      const approveResult = await apiWrite(
        sessions.ceo,
        'post',
        `/api/leave/${toApprove.id}/approve`,
        {}
      );
      expect(
        approveResult.status(),
        "ceo joined REVIEW_ALL_ROLES (PR #885) and must now be able to approve someone else's " +
          'leave'
      ).toBe(200);

      // Re-read rather than trust the write response — proves the mutation actually landed, the
      // same discipline the denied-roles loop below applies to a refusal NOT mutating.
      const approved = await requestById(sessions.hr, toApprove.id);
      expect(approved.status, "ceo's approve must actually move the row to APPROVED").toBe(
        'APPROVED'
      );
    } finally {
      // APPROVED sits inside ACTIVE_QUOTA_STATUSES (see this file's header) — cancel gives the
      // quota back, the same cleanup `submitting VACATION now succeeds` uses above. It works
      // whether approve above succeeded (row is APPROVED) or failed (row is still SUBMITTED):
      // LeaveService#cancel accepts a reviewer's cancel from either status.
      const cleanup = await apiWrite(sessions.hr, 'post', `/api/leave/${toApprove.id}/cancel`, {});
      expect(
        cleanup.status(),
        'cleanup cancel must succeed or this spec is not re-runnable'
      ).toBe(200);
    }

    // ── reject ───────────────────────────────────────────────────────────────────────────────
    // Reject travels the same gate as approve; asserting only approve would leave the
    // cheaper-to-get-wrong direction untested — the same reasoning the denied-roles loop below
    // states for its own two calls. A SEPARATE request, not the one just approved-and-cancelled
    // above: LeaveService#approve/#reject both require status == SUBMITTED, so reusing the
    // already-CANCELLED row here would 409 for a reason that has nothing to do with what this
    // half tests.
    const rejectDay = workingDayFromToday(60);
    const rejectCreate = await apiWrite(sessions[OWNER], 'post', '/api/leave', {
      leaveTypeCode: 'VACATION',
      startDate: rejectDay,
      endDate: rejectDay,
      reason: 'e2e-real: ceo review-gate, reject path (PR #885)',
    });
    const rejectCreateBody = await rejectCreate.text();
    expect(
      rejectCreate.status(),
      `POST /api/leave expected 200, got ${rejectCreate.status()}: ${rejectCreateBody}`
    ).toBe(200);
    const { request: toReject } = JSON.parse(rejectCreateBody);
    expect(toReject.status).toBe('SUBMITTED');

    // NOT a try/finally with an unconditional cancel like the approve half above: once reject
    // succeeds the row is REJECTED, and LeaveService#cancel refuses anything that is not
    // SUBMITTED or APPROVED (409 "ยกเลิกได้เฉพาะคำขอลาที่ยังอยู่ระหว่างพิจารณาเท่านั้น") — an
    // unconditional cancel would 409 on the happy path. REJECTED needs no rescue anyway: it sits
    // outside ACTIVE_QUOTA_STATUSES, so leaving it behind leaks no quota, and it is not SUBMITTED
    // so seededSubmittedRequest's `.find` above can never pick it up. The flag below exists only
    // to cover the OTHER path: reject failing outright and leaking a quota-holding SUBMITTED row.
    let rejectSucceeded = false;
    try {
      const rejectResult = await apiWrite(
        sessions.ceo,
        'post',
        `/api/leave/${toReject.id}/reject`,
        {}
      );
      expect(rejectResult.status(), 'ceo must be able to reject too, not only approve').toBe(200);
      rejectSucceeded = true;

      const rejected = await requestById(sessions.hr, toReject.id);
      expect(rejected.status, "ceo's reject must actually move the row to REJECTED").toBe(
        'REJECTED'
      );
    } finally {
      if (!rejectSucceeded) {
        const cleanup = await apiWrite(
          sessions.hr,
          'post',
          `/api/leave/${toReject.id}/cancel`,
          {}
        );
        expect(
          cleanup.status(),
          'best-effort cleanup of a leaked SUBMITTED row after an unexpected reject failure'
        ).toBe(200);
      }
    }
  });

  // The assertions that matter, written wrong-way-round. Each role gets its own test so one
  // failure does not hide the others. None of these mutates: a 403 is refused before it writes,
  // and the status is re-read afterwards to prove it.
  //
  // ceo is deliberately NOT in this list any more: REVIEW_ALL_ROLES gained ceo on 2026-09-01
  // (PR #885), so asserting 403 for ceo here would pin the OLD rule. Its positive coverage is
  // the "ceo can now approve and reject someone else's leave" test above.
  for (const role of ['import', 'sales']) {
    test(`${role} can neither approve nor reject someone else's leave`, async () => {
      const pending = await seededSubmittedRequest(sessions.hr);

      expect(
        (await apiWrite(sessions[role], 'post', `/api/leave/${pending.id}/approve`, {})).status(),
        `${role} is neither in REVIEW_ALL_ROLES ({hr, ceo}) nor this employee's manager of record`
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
