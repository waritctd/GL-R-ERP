import { test, expect } from '@playwright/test';
import { apiSessionFor, apiWrite, apiWriteWithoutCsrf, disposeSessions } from './helpers/api.js';

// ─────────────────────────────────────────────────────────────────────────────
// THE FIRST WRITE FLOW ASSERTED AGAINST THE REAL BACKEND.
//
// Everything else in e2e-real/ is read-only: GETs, plus anonymous writes the filter chain
// rejects before they run. That covers the *deny* direction of every gate and says nothing
// about whether an authorised write does the right thing. This file closes that gap for the
// one workflow where the gap is documented to have caused a wrong answer.
//
// Issue #199: mockApi.js let HR approve an overtime request. The real OvertimeService refuses
// it — HR is not in the approval chain at all — and an agent reported "the backend would accept
// an HR approval" after reading the mock's authz as the backend's. The assertion at the centre
// of this file is that HR gets a 403, from the real service, on a real request, in a real
// database.
//
// The chain OvertimeService actually implements, verified end to end here:
//
//     SUBMITTED ──(division manager)──▶ MANAGER_APPROVED ──(CEO)──▶ APPROVED
//
// with three refusals that are easy to get wrong and are asserted individually:
//   • HR cannot approve at either stage                     (#199)
//   • the CEO cannot skip the manager stage                 (counterintuitive: the CEO
//     outranks everyone and is still refused while a manager approval is outstanding)
//   • the manager cannot perform the CEO's final approval
//
// SAFETY / RE-RUNNABILITY. This spec mutates a real database, so it is written to be safe to
// run repeatedly against a shared one:
//   • every test creates its OWN request and touches only that id — never a global count,
//     never a row it did not create;
//   • each test cancels what it created, including from APPROVED (a division manager may
//     cancel at any live status, which also unwinds the attendance minutes the approval
//     credited — see OvertimeService#cancel → syncAttendanceDay);
//   • the work date is always TODAY in the payroll timezone, because approval is gated on the
//     payroll month still being open (OvertimeService#requirePayrollMonthOpen). A hardcoded
//     date would pass until that month was processed and then fail for a reason having nothing
//     to do with the code under test.
// ─────────────────────────────────────────────────────────────────────────────

// demo.sales sits in SA-ฝ่ายขาย with demo.salesmanager as its ผู้จัดการ, so its requests take
// the full two-stage route. That pairing is the reason this flow is built on these two personas
// and not, say, demo.employee — which has no division, therefore no manager, and skips straight
// to a CEO-only approval (asserted separately at the end).
const OWNER = 'sales';
const MANAGER = 'sales_manager';

/** Today in Asia/Bangkok — the zone app.attendance.schedule.zone runs payroll months in. */
function todayInBangkok() {
  // en-CA renders ISO-8601 (YYYY-MM-DD), which is what LocalDate parses.
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bangkok' }).format(new Date());
}

function overtimePayload(reason) {
  const workDate = todayInBangkok();
  return {
    workDate,
    // An evening window on the work date. +07:00 is Asia/Bangkok's fixed offset (Thailand has
    // no DST), so this is unambiguous without pulling in a timezone library.
    plannedStartAt: `${workDate}T18:00:00+07:00`,
    plannedEndAt: `${workDate}T20:00:00+07:00`,
    reason,
  };
}

test.describe('overtime approval chain (real OvertimeService)', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  let sessions;

  test.beforeAll(async () => {
    sessions = {
      [OWNER]: await apiSessionFor(OWNER),
      [MANAGER]: await apiSessionFor(MANAGER),
      hr: await apiSessionFor('hr'),
      ceo: await apiSessionFor('ceo'),
      employee: await apiSessionFor('employee'),
    };
  });

  test.afterAll(async () => {
    await disposeSessions(sessions);
  });

  /** Submits a request as its owner and returns the created row. Fails loudly, never silently. */
  async function submitOvertime(reason) {
    const response = await apiWrite(sessions[OWNER], 'POST', '/api/overtime', overtimePayload(reason));
    expect(response.status(), `submitting overtime: ${await response.text()}`).toBe(200);
    const { request } = await response.json();
    expect(request.status).toBe('SUBMITTED');
    // hasManagerApprover is what routes the request; if the seed ever stopped giving demo.sales
    // a manager this whole spec would be testing the managerless path under the wrong name.
    expect(
      request.hasManagerApprover,
      'demo.sales must have a division manager for the two-stage chain to apply'
    ).toBe(true);
    return request;
  }

  /** Best-effort teardown: a manager may cancel at any live status. */
  async function cancelOvertime(id) {
    await apiWrite(sessions[MANAGER], 'POST', `/api/overtime/${id}/cancel`, {});
  }

  async function readOvertime(id) {
    const response = await sessions[OWNER].get('/api/overtime');
    expect(response.status()).toBe(200);
    const { requests } = await response.json();
    return requests.find((row) => row.id === id) ?? null;
  }

  test('an employee submits, and the request lands SUBMITTED awaiting its manager', async () => {
    const created = await submitOvertime('e2e: submit lands in SUBMITTED');
    try {
      expect(created.pendingApproverRole).toBe('manager');
      // Read it back through a separate request rather than trusting the create response:
      // the point is that it was persisted, not that the service echoed what we sent.
      const persisted = await readOvertime(created.id);
      expect(persisted, 'the submitted request must be readable by its owner').not.toBeNull();
      expect(persisted.status).toBe('SUBMITTED');
      expect(persisted.plannedMinutes).toBe(120);
    } finally {
      await cancelOvertime(created.id);
    }
  });

  test('HR cannot approve overtime — the real service refuses it (issue #199)', async () => {
    const created = await submitOvertime('e2e: HR must not be able to approve this');
    try {
      const response = await apiWrite(sessions.hr, 'POST', `/api/overtime/${created.id}/approve`, {});

      // THE assertion this file exists for. mockApi.js answered 200 here; OvertimeService
      // answers 403 with requireManager's message, because HR is not in the chain at all.
      expect(response.status(), 'HR approving overtime').toBe(403);
      expect(await response.text()).toContain(
        'เฉพาะหัวหน้างานของพนักงานเท่านั้นที่สามารถพิจารณาคำขอทำงานล่วงเวลาได้'
      );

      // And — the half that a status check alone would miss — the refusal actually stuck.
      // A 403 that still mutated would be worse than no gate, because it would look safe.
      const persisted = await readOvertime(created.id);
      expect(persisted.status, 'the refused approval must not have advanced the request').toBe(
        'SUBMITTED'
      );
    } finally {
      await cancelOvertime(created.id);
    }
  });

  test('the CEO cannot skip the manager stage', async () => {
    const created = await submitOvertime('e2e: CEO must not bypass the manager stage');
    try {
      const response = await apiWrite(sessions.ceo, 'POST', `/api/overtime/${created.id}/approve`, {});

      // Counterintuitive and therefore worth pinning: the CEO is the most privileged role in
      // the system and is still refused while a manager approval is outstanding. The CEO's
      // authority applies at the MANAGER_APPROVED stage (below) and to managerless requests,
      // not as a general override.
      expect(response.status(), 'CEO approving a request still awaiting its manager').toBe(403);
      expect((await readOvertime(created.id)).status).toBe('SUBMITTED');
    } finally {
      await cancelOvertime(created.id);
    }
  });

  test('manager → CEO drives a request all the way to APPROVED, and it persists', async () => {
    const created = await submitOvertime('e2e: full two-stage approval');
    try {
      // Stage 1 — the employee's own division manager.
      const managerApproval = await apiWrite(
        sessions[MANAGER],
        'POST',
        `/api/overtime/${created.id}/approve`,
        {}
      );
      expect(managerApproval.status(), await managerApproval.text()).toBe(200);
      expect((await managerApproval.json()).request.status).toBe('MANAGER_APPROVED');

      // The manager's authority ends here — the second stage is the CEO's alone.
      const managerAgain = await apiWrite(
        sessions[MANAGER],
        'POST',
        `/api/overtime/${created.id}/approve`,
        {}
      );
      expect(managerAgain.status(), 'manager performing the CEO stage').toBe(403);

      // ...and HR is refused at this stage too, not just the first.
      const hrAtSecondStage = await apiWrite(
        sessions.hr,
        'POST',
        `/api/overtime/${created.id}/approve`,
        {}
      );
      expect(hrAtSecondStage.status(), 'HR performing the CEO stage').toBe(403);

      // Stage 2 — the CEO.
      const ceoApproval = await apiWrite(
        sessions.ceo,
        'POST',
        `/api/overtime/${created.id}/approve`,
        {}
      );
      expect(ceoApproval.status(), await ceoApproval.text()).toBe(200);

      const persisted = await readOvertime(created.id);
      expect(persisted.status).toBe('APPROVED');
      expect(persisted.ceoApprovedBy, 'the approving CEO must be recorded on the row').not.toBeNull();
      expect(
        persisted.managerApprovedBy,
        'the manager stage must remain recorded after the CEO stage'
      ).not.toBeNull();
      // No attendance punches exist for this window in the demo seed, so the payable figure is
      // 0 with an explanatory note rather than an error — OvertimeService#calculate's
      // orElseGet branch. Asserted so a future change that started REQUIRING punches to
      // approve would surface here rather than as a mysterious 4xx.
      expect(persisted.actualMinutes).toBe(0);
    } finally {
      await cancelOvertime(created.id);
    }
  });

  test('cancelling an approved request unwinds it', async () => {
    const created = await submitOvertime('e2e: cancel after approval');
    await apiWrite(sessions[MANAGER], 'POST', `/api/overtime/${created.id}/approve`, {});
    await apiWrite(sessions.ceo, 'POST', `/api/overtime/${created.id}/approve`, {});
    expect((await readOvertime(created.id)).status).toBe('APPROVED');

    const cancellation = await apiWrite(
      sessions[MANAGER],
      'POST',
      `/api/overtime/${created.id}/cancel`,
      {}
    );
    expect(cancellation.status(), await cancellation.text()).toBe(200);
    expect((await readOvertime(created.id)).status).toBe('CANCELLED');
  });

  test('an unrelated employee cannot approve or cancel someone else\'s request', async () => {
    const created = await submitOvertime('e2e: unrelated employee must be refused');
    try {
      // demo.employee has no division, so it manages nobody and owns nothing here. Both the
      // approve and the cancel paths must refuse it — cancel matters independently because it
      // is the one write an ordinary employee CAN perform, on their own request.
      const approve = await apiWrite(
        sessions.employee,
        'POST',
        `/api/overtime/${created.id}/approve`,
        {}
      );
      expect(approve.status(), 'unrelated employee approving').toBe(403);

      const cancel = await apiWrite(
        sessions.employee,
        'POST',
        `/api/overtime/${created.id}/cancel`,
        {}
      );
      expect(cancel.status(), "unrelated employee cancelling someone else's request").toBe(403);

      expect((await readOvertime(created.id)).status).toBe('SUBMITTED');
    } finally {
      await cancelOvertime(created.id);
    }
  });
});

test.describe('CSRF is enforced on writes', () => {
  // Not reachable from the read-only specs: Spring Security's chain (order -100) rejects an
  // anonymous write with 401 before CsrfCookieFilter (order 0) runs, so the filter only has an
  // effect once a caller is authenticated. This is the one place the suite proves the
  // double-submit control is actually live rather than merely configured.
  test('an authenticated write without the X-XSRF-TOKEN header is refused', async () => {
    const session = await apiSessionFor(OWNER);
    try {
      const withoutToken = await apiWriteWithoutCsrf(
        session,
        'POST',
        '/api/overtime',
        overtimePayload('e2e: this must never be created')
      );
      expect(withoutToken.status(), 'write with a valid session but no CSRF header').toBe(403);
      expect(await withoutToken.text()).toContain('Invalid CSRF token');

      // The same request WITH the header succeeds — so the 403 above is the CSRF control
      // firing, not the request being malformed or the session being unusable.
      const withToken = await apiWrite(
        session,
        'POST',
        '/api/overtime',
        overtimePayload('e2e: CSRF positive control')
      );
      expect(withToken.status()).toBe(200);
      const { request } = await withToken.json();

      const manager = await apiSessionFor(MANAGER);
      await apiWrite(manager, 'POST', `/api/overtime/${request.id}/cancel`, {});
      await manager.dispose();
    } finally {
      await session.dispose();
    }
  });
});
