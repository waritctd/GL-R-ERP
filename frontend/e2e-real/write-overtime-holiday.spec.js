import { test, expect } from '@playwright/test';
import { apiSessionFor, apiWrite, disposeSessions } from './helpers/api.js';

// ─────────────────────────────────────────────────────────────────────────────
// day_type / pay_rate_multiplier ALWAYS derive from hr.holiday (V115) -- never from the client's
// SubmitOvertimeRequest.dayType claim. That is OvertimeService#deriveDayType and
// #resolveDayTypeSubmitNote (PRs #586/#587), and it has ZERO coverage anywhere in this suite
// before this file: write-overtime.spec.js drives the approval CHAIN end to end, but every one
// of its submits leaves dayType unclaimed and never touches hr.holiday at all -- grep this
// directory for "dayType", "holiday", "multiplier", "3.00" or "1.50" before this file and none
// of them exist outside a handful of unrelated hits. This file closes that gap.
//
// The decision table under test (OvertimeService#resolveDayTypeSubmitNote's own Javadoc has the
// authoritative version; OvertimeDayTypeDerivedFromCalendarIntegrationTest is the Java-level
// proof this file mirrors at the HTTP layer, through the real HolidayController CRUD instead of
// a direct INSERT/DELETE):
//
//   calendar loaded     | claim        | outcome
//   ------------------- | ------------ | ------------------------------------------------------
//   yes (>=1 row/year)  | HOLIDAY      | date IS a holiday  -> accept, HOLIDAY / 3.00      (Case 2)
//   yes                 | HOLIDAY      | date is NOT one    -> 400, NO row created          (Case 1)
//   yes                 | WORKDAY      | date IS a holiday  -> accept, HOLIDAY / 3.00       (Case 3)
//                        |              |   (the claim cannot suppress real holiday pay)
//   yes                 | WORKDAY/none | ordinary day       -> accept, WORKDAY / 1.50, no flag (Case 4)
//   zero rows for year  | (any)        | --                 -> accept + "[รอตรวจสอบ]" flag
//
// day_type is re-derived and FROZEN at managerApprove/ceoDirectApprove (Case 5 below); ceoApprove
// deliberately does NOT re-derive a second time. That last half is already proven at the Java
// level (OvertimeDayTypeDerivedFromCalendarIntegrationTest
// #aCalendarChangeAfterManagerApprovalIsNotPickedUpByTheFinalCeoSignOff) and is not repeated
// here -- this file's job is proving the derivation and the freeze survive the real
// HolidayController CRUD + real OvertimeController approve endpoint, not re-deriving the whole
// Java-level proof a second time.
//
// THE PRECONDITION THIS FILE LIVES OR DIES ON: hr.holiday SHIPS EMPTY (V115's own Javadoc -- HR
// populates it once the BOT list is available, or manually through this same CRUD). If the
// calendar genuinely has zero rows for the work date's year, HolidayCalendar#hasHolidaysForYear
// is false and the "claim contradicted -> 400" branch above can never fire -- submission falls
// through to "accept + flag" instead, silently. A test asserting 400 would then fail for the
// WRONG reason (not "the claim was refused" but "the endpoint answered 200"), and a test
// asserting acceptance would pass VACUOUSLY (right answer, wrong branch, for a reason unrelated
// to what it claims to prove). So every case below that needs "the calendar is loaded for this
// year" creates a real row through the real HolidayController first and reads it back through a
// SEPARATE GET before relying on it -- never trusts its own create response, same discipline
// write-overtime.spec.js applies to readOvertime. If that read-back ever came up empty, the
// precondition assertion fails loudly right there, at setup, instead of the real assertion later
// failing for a reason that looks unrelated to the actual defect.
//
// SAFETY / RE-RUNNABILITY, same rules as write-overtime.spec.js (this file mutates a real,
// possibly shared database too):
//   • every test creates its OWN overtime request and its OWN holiday row(s), and touches only
//     what it created -- never a row it did not create, never a global count;
//   • every holiday row this file creates is on a date this file itself chose (today, or a fixed
//     same-year sentinel) and is deleted again before the test returns, win or lose;
//   • a best-effort delete of that exact date runs BEFORE each create too, so a stray row left by
//     a previous run of these same tests that crashed mid-test (create succeeded, cleanup never
//     ran) cannot 409 a later run -- it never deletes a date this file did not choose itself;
//   • the work date is always TODAY in the payroll timezone, for the same reason
//     write-overtime.spec.js gives: approval is gated on the payroll month still being open, and
//     a hardcoded date passes until that month is processed and then fails for a reason that has
//     nothing to do with the code under test. Where a case needs a HOLIDAY date, the holiday is
//     created ON today rather than picked from the real Thai calendar, for the same reason.
// ─────────────────────────────────────────────────────────────────────────────

// Same pairing as write-overtime.spec.js, and for the same reason: demo.sales sits in a division
// with demo.salesmanager as its ผู้จัดการ, so approve() takes the two-stage managerApprove route
// that Case 5 needs -- managerApprove is one of the two places dayType re-derives and freezes.
const OWNER = 'sales';
const MANAGER = 'sales_manager';

/**
 * Today in Asia/Bangkok. Duplicated from write-overtime.spec.js rather than imported, so this
 * file stays readable standalone -- see that file's copy for the full rationale (en-CA renders
 * ISO-8601, which is what LocalDate parses and what HolidayCreateRequest's @DateTimeFormat needs
 * for the path/body dates below too).
 */
function todayInBangkok() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bangkok' }).format(new Date());
}

/**
 * A date in the SAME calendar year as `date` that is never equal to `date` itself -- used to make
 * HolidayCalendar#hasHolidaysForYear true (the year is "loaded") without making `date` itself a
 * holiday. Mirrors OvertimeDayTypeDerivedFromCalendarIntegrationTest's own sentinel fixture,
 * including its January-1st edge case: a plain "one day earlier/later" rolls over into the WRONG
 * year whenever `date` itself is January 1st (or December 31st), which would silently make the
 * year look unloaded and change which row of the table above actually fires.
 */
function sentinelDateInSameYear(date) {
  const year = date.slice(0, 4);
  return date === `${year}-01-01` ? `${year}-01-02` : `${year}-01-01`;
}

function overtimePayload(reason, dayType) {
  const workDate = todayInBangkok();
  return {
    workDate,
    // An evening window on the work date, same shape as write-overtime.spec.js.
    plannedStartAt: `${workDate}T18:00:00+07:00`,
    plannedEndAt: `${workDate}T20:00:00+07:00`,
    reason,
    // Omit the key entirely for "no claim" rather than sending an explicit null/blank --
    // SubmitOvertimeRequest.dayType must accept that shape too, per its own Javadoc, even though
    // OvertimePanel.jsx's dropdown never produces it (it always sends WORKDAY or HOLIDAY).
    ...(dayType === undefined ? {} : { dayType }),
  };
}

test.describe('overtime day type derives from hr.holiday, never the client claim (real OvertimeService + HolidayCalendar)', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  let sessions;

  test.beforeAll(async () => {
    sessions = {
      [OWNER]: await apiSessionFor(OWNER),
      [MANAGER]: await apiSessionFor(MANAGER),
      hr: await apiSessionFor('hr'),
    };
  });

  test.afterAll(async () => {
    await disposeSessions(sessions);
  });

  // ── overtime helpers, same shapes as write-overtime.spec.js ────────────────────────────────

  async function readOvertime(id) {
    const response = await sessions[OWNER].get('/api/overtime');
    expect(response.status()).toBe(200);
    const { requests } = await response.json();
    return requests.find((row) => row.id === id) ?? null;
  }

  /** Own-requests count, for the wrong-way-round "no row was created" assertion in Case 1. */
  async function countOwnOvertime() {
    const response = await sessions[OWNER].get('/api/overtime');
    expect(response.status()).toBe(200);
    const { requests } = await response.json();
    return requests.length;
  }

  /**
   * Best-effort teardown, same contract as write-overtime.spec.js's cancelOvertime: never assert
   * inside a finally -- a cleanup failure must never mask the real assertion's result.
   */
  async function cancelOvertime(id) {
    await apiWrite(sessions[MANAGER], 'POST', `/api/overtime/${id}/cancel`, {});
  }

  // ── holiday helpers, through the real HolidayController CRUD (HR/CEO-gated) ────────────────

  /**
   * Required setup, not best-effort: if this fails, the whole test's precondition is unmet, so it
   * asserts loudly HERE rather than letting a later, unrelated-looking assertion fail instead.
   */
  async function createHoliday(date, nameTh) {
    const response = await apiWrite(sessions.hr, 'POST', '/api/holidays', { holidayDate: date, nameTh });
    expect(response.status(), `HR creating holiday ${date}: ${await response.text()}`).toBe(200);
    return response.json();
  }

  /**
   * Reads holidays back through a SEPARATE request -- the point is that a row was persisted, not
   * that the create endpoint echoed what it was sent, same discipline as write-overtime.spec.js's
   * readOvertime.
   */
  async function listHolidays(from, to) {
    const response = await sessions.hr.get(`/api/holidays?from=${from}&to=${to}`);
    expect(response.status(), `listing holidays ${from}..${to}: ${await response.text()}`).toBe(200);
    const { holidays } = await response.json();
    return holidays;
  }

  /**
   * Best-effort delete, used BOTH to pre-clean a date this file is about to own (guards against a
   * stray row from a previous run that crashed between create and cleanup) AND to tear down after
   * a test -- never asserted, same contract as cancelOvertime above.
   */
  async function deleteHolidayBestEffort(date) {
    await apiWrite(sessions.hr, 'DELETE', `/api/holidays/${date}`, undefined);
  }

  /** Asserts `date` is NOT already a holiday -- guards a precondition several cases below rely on. */
  async function assertNotHoliday(date) {
    const holidays = await listHolidays(date, date);
    expect(holidays, `${date} must not already be a holiday, or this case would be vacuous`).toEqual([]);
  }

  /** Asserts `date` IS persisted as a holiday, read back independently of whatever created it. */
  async function assertHolidayPersisted(date) {
    const holidays = await listHolidays(date, date);
    expect(
      holidays.map((holiday) => holiday.holidayDate),
      `${date} must be persisted as a holiday (read back independently) before relying on it`
    ).toContain(date);
  }

  // -------------------------------------------------------------------------------------------
  // Case 1 (decision table row 2): the claim IS actively disprovable -- the calendar is loaded
  // for the year (a sentinel row elsewhere in the same year proves it) but today specifically is
  // not in it. Wrong-way-round: asserts not just the 400, but that NO row was created at all.
  // -------------------------------------------------------------------------------------------
  test('a HOLIDAY claim on an ordinary day is refused, and creates no row', async () => {
    const workDate = todayInBangkok();
    const sentinelDate = sentinelDateInSameYear(workDate);

    await deleteHolidayBestEffort(sentinelDate);
    await createHoliday(sentinelDate, 'e2e: sentinel holiday, loads the calendar year');
    try {
      // Precondition #1: the year is genuinely loaded (>=1 row) -- read back independently, per
      // this file's header. If this silently failed, the 400 below would degrade into a 200
      // accept-and-flag instead, and this assertion is what catches that HERE, not there.
      await assertHolidayPersisted(sentinelDate);
      // Precondition #2: today itself genuinely is NOT a holiday, or a 400 below would prove
      // nothing about the claim being disprovABLE -- only that something else was wrong.
      await assertNotHoliday(workDate);

      const before = await countOwnOvertime();

      const response = await apiWrite(
        sessions[OWNER],
        'POST',
        '/api/overtime',
        overtimePayload('e2e: HOLIDAY claim on an ordinary day, must be refused', 'HOLIDAY')
      );
      const responseText = await response.text();

      // THE assertion this test exists for: the calendar can actively disprove the claim, so the
      // request is refused outright, naming the date -- OvertimeService#resolveDayTypeSubmitNote.
      expect(response.status(), responseText).toBe(400);
      expect(responseText).toContain(workDate);

      // And -- the half that a status check alone would miss, and the one the task brief calls
      // out as easy to omit -- the refusal created NOTHING. overtimeRepository.create() sits
      // AFTER resolveDayTypeSubmitNote() in OvertimeService#submit, so a 400 here should mean the
      // repository call was never reached, not merely that some row was rolled back afterwards.
      const after = await countOwnOvertime();
      expect(after, 'a refused submit must create NO row at all').toBe(before);
    } finally {
      await deleteHolidayBestEffort(sentinelDate);
    }
  });

  // -------------------------------------------------------------------------------------------
  // Case 2 (decision table row 1): the claim IS corroborated by the calendar -- accepted at the
  // real holiday rate, with no flag (contrast with Case 1's contradiction, and the "zero rows"
  // flag that Case 4 below is careful to rule out).
  // -------------------------------------------------------------------------------------------
  test('a HOLIDAY claim on a genuine holiday is accepted at the 3.00 holiday rate', async () => {
    const workDate = todayInBangkok();
    let created = null;

    await deleteHolidayBestEffort(workDate);
    await createHoliday(workDate, 'e2e: genuine holiday, HOLIDAY claim corroborated');
    try {
      await assertHolidayPersisted(workDate);

      const response = await apiWrite(
        sessions[OWNER],
        'POST',
        '/api/overtime',
        overtimePayload('e2e: HOLIDAY claim corroborated by the calendar', 'HOLIDAY')
      );
      expect(response.status(), await response.text()).toBe(200);
      ({ request: created } = await response.json());

      expect(created.dayType).toBe('HOLIDAY');
      expect(created.payRateMultiplier).toBe(3);
      expect(created.calculationNote, 'a corroborated claim needs no flag').toBeNull();

      // Read back independently, not just the create response.
      const persisted = await readOvertime(created.id);
      expect(persisted.dayType).toBe('HOLIDAY');
      expect(persisted.payRateMultiplier).toBe(3);
    } finally {
      if (created) {
        await cancelOvertime(created.id);
      }
      await deleteHolidayBestEffort(workDate);
    }
  });

  // -------------------------------------------------------------------------------------------
  // Case 3 (decision table row 3): the claim UNDER-states the true day type -- deriveDayType
  // corrects it upward regardless. The claim can never suppress real holiday pay, only
  // (harmlessly) fail to inflate it -- see SubmitOvertimeRequest's Javadoc.
  // -------------------------------------------------------------------------------------------
  test('a WORKDAY claim on a genuine holiday is still stored as HOLIDAY/3.00', async () => {
    const workDate = todayInBangkok();
    let created = null;

    await deleteHolidayBestEffort(workDate);
    await createHoliday(workDate, 'e2e: genuine holiday, under-claimed as WORKDAY');
    try {
      await assertHolidayPersisted(workDate);

      const response = await apiWrite(
        sessions[OWNER],
        'POST',
        '/api/overtime',
        overtimePayload('e2e: WORKDAY claim on a genuine holiday, must not suppress pay', 'WORKDAY')
      );
      expect(response.status(), await response.text()).toBe(200);
      ({ request: created } = await response.json());

      expect(created.dayType, 'the WORKDAY claim must not suppress the real holiday rate').toBe('HOLIDAY');
      expect(created.payRateMultiplier).toBe(3);

      const persisted = await readOvertime(created.id);
      expect(persisted.dayType).toBe('HOLIDAY');
      expect(persisted.payRateMultiplier).toBe(3);
    } finally {
      if (created) {
        await cancelOvertime(created.id);
      }
      await deleteHolidayBestEffort(workDate);
    }
  });

  // -------------------------------------------------------------------------------------------
  // Case 4 (decision table row 4): the base case -- an ordinary day, no claim at all, with the
  // calendar genuinely LOADED for the year (a sentinel, same technique as Case 1). That last part
  // is what makes this provably the "yes / ordinary day" row and not a false-positive from the
  // "zero rows -> flagged" row: both resolve to WORKDAY/1.50, but only one of them leaves
  // calculation_note null. Asserting the note too is what tells the two branches apart --
  // asserting only the multiplier would still pass even if hasHolidaysForYear silently regressed
  // to always-false.
  // -------------------------------------------------------------------------------------------
  test('an ordinary day with no claim stores WORKDAY/1.50, unflagged', async () => {
    const workDate = todayInBangkok();
    const sentinelDate = sentinelDateInSameYear(workDate);
    let created = null;

    await deleteHolidayBestEffort(sentinelDate);
    await createHoliday(sentinelDate, 'e2e: sentinel holiday, loads the calendar year');
    try {
      await assertHolidayPersisted(sentinelDate);
      await assertNotHoliday(workDate);

      const response = await apiWrite(
        sessions[OWNER],
        'POST',
        '/api/overtime',
        overtimePayload('e2e: ordinary day, no day-type claim at all')
      );
      expect(response.status(), await response.text()).toBe(200);
      ({ request: created } = await response.json());

      expect(created.dayType).toBe('WORKDAY');
      expect(created.payRateMultiplier).toBe(1.5);
      expect(
        created.calculationNote,
        'the year IS loaded (sentinel above), so this must NOT carry the "calendar unverified" flag'
      ).toBeNull();
    } finally {
      if (created) {
        await cancelOvertime(created.id);
      }
      await deleteHolidayBestEffort(sentinelDate);
    }
  });

  // -------------------------------------------------------------------------------------------
  // Case 5: day_type is re-derived and FROZEN at the approval stage that first leaves SUBMITTED
  // (OvertimeService#calculate, called from managerApprove) -- never trusted from whatever was
  // stored at submit. Submits while the date is ordinary, then HR adds a holiday for that SAME
  // date before anyone approves, then asserts the manager-approval response itself re-derived
  // HOLIDAY/3.00. Mirrors
  // OvertimeDayTypeDerivedFromCalendarIntegrationTest#aHolidayAddedToTheCalendarAfterSubmitIsPickedUpAndFrozenAtManagerApproval
  // at the HTTP layer, through the real HolidayController instead of a direct INSERT.
  // -------------------------------------------------------------------------------------------
  test('day_type is frozen at approval: a holiday HR adds after submit is picked up at manager approval', async () => {
    const workDate = todayInBangkok();
    let created = null;

    await deleteHolidayBestEffort(workDate);
    try {
      await assertNotHoliday(workDate);

      const submitResponse = await apiWrite(
        sessions[OWNER],
        'POST',
        '/api/overtime',
        overtimePayload('e2e: submitted before any holiday existed for this date')
      );
      expect(submitResponse.status(), await submitResponse.text()).toBe(200);
      ({ request: created } = await submitResponse.json());
      expect(created.dayType, 'must start out WORKDAY -- no holiday exists yet at submit time').toBe('WORKDAY');
      // hasManagerApprover is what routes this to managerApprove (rather than ceoDirectApprove),
      // which is the stage this case needs -- see write-overtime.spec.js for why this OWNER/
      // MANAGER pairing guarantees a manager stage exists for demo.sales.
      expect(created.hasManagerApprover).toBe(true);

      // HR adds a holiday for the SAME date, strictly after the request already exists.
      await createHoliday(workDate, 'e2e: added after submit, before approval');
      await assertHolidayPersisted(workDate);

      const approveResponse = await apiWrite(
        sessions[MANAGER],
        'POST',
        `/api/overtime/${created.id}/approve`,
        {}
      );
      expect(approveResponse.status(), await approveResponse.text()).toBe(200);
      const { request: approved } = await approveResponse.json();

      expect(approved.status).toBe('MANAGER_APPROVED');
      expect(
        approved.dayType,
        'deriveDayType must re-run at manager approval, not trust the submit-time WORKDAY value'
      ).toBe('HOLIDAY');
      expect(approved.payRateMultiplier).toBe(3);

      // Read back independently too, not just the approve response.
      const persisted = await readOvertime(created.id);
      expect(persisted.dayType).toBe('HOLIDAY');
      expect(persisted.payRateMultiplier).toBe(3);
    } finally {
      if (created) {
        await cancelOvertime(created.id);
      }
      await deleteHolidayBestEffort(workDate);
    }
  });
});
