import { test, expect } from '@playwright/test';
import { apiSessionFor, apiWrite, disposeSessions } from './helpers/api.js';

// ─────────────────────────────────────────────────────────────────────────────
// feat/ot-nonworkday-rate-suggestion (branch B of the OT UAT defect #2 fix) rewrote the decision
// table this file pins. THREE distinct values now exist where there used to be one:
//
//   suggested day type | system: hr.holiday OR the caller's resolved WorkSchedule non-workday | never pay
//   requested day type | the employee's own claim -- pre-filled from the suggestion, freely     | never pay
//                       | editable, accepted EITHER way (see the removed-refusal note below)    |
//   approved day type  | the approver's decision (ApproveOvertimeRequest.dayType), defaulted to | BECOMES PAY
//                       | the suggestion when absent                                            |
//
// Old decision table (pre-branch, what this file used to pin -- see git history):
//
//   calendar loaded     | claim        | outcome
//   ------------------- | ------------ | ------------------------------------------------------
//   yes (>=1 row/year)  | HOLIDAY      | date IS a holiday  -> accept, HOLIDAY / 3.00
//   yes                 | HOLIDAY      | date is NOT one    -> 400, NO row created   <- REMOVED
//   yes                 | WORKDAY      | date IS a holiday  -> accept, HOLIDAY / 3.00
//   yes                 | WORKDAY/none | ordinary day       -> accept, WORKDAY / 1.50, no flag
//   zero rows for year  | (any)        | --                 -> accept + "[รอตรวจสอบ]" flag
//
// What changed, on explicit owner ruling (2026-08-08):
//   - The "claim contradicted -> 400" row is GONE. A HOLIDAY claim the calendar can actively
//     disprove is now ACCEPTED and FLAGGED ("[ไม่ตรงกับที่ระบบแนะนำ]"), never refused -- the
//     employee's field is a REQUEST, not a pay input, so there is nothing for it to get away
//     with by disagreeing.
//   - A weekend (or any other day the caller's OWN resolved WorkSchedule marks as a non-workday)
//     now ALSO suggests HOLIDAY, with no hr.holiday row needed at all -- this is new territory
//     this file's PREDECESSOR never exercised (grep this directory for "dayType", "holiday",
//     "multiplier", "3.00" or "1.50" before this file existed and none of them touch weekends).
//   - The "[รอตรวจสอบ]" calendar-unverified flag is NARROWED: it now fires only when the
//     SUGGESTION is WORKDAY (schedule says ordinary workday) and the calendar year has zero
//     rows -- a weekend's HOLIDAY suggestion no longer needs the calendar to be certain.
//   - What actually becomes pay at approval is the APPROVER's decision
//     (ApproveOvertimeRequest.dayType), defaulted to the suggestion -- see Cases 6-7 below.
//
// The Java-level proof (including its own mutation-check) lives in
// OvertimeDayTypeDerivedFromCalendarIntegrationTest; this file's job is proving the same
// properties survive the real HTTP layer (JSON serialization of the new `suggestedDayType`
// field, the real HolidayController CRUD, the real OvertimeController approve endpoint), not
// re-deriving the whole Java-level proof a second time.
//
// day_type is re-derived and FROZEN at managerApprove/ceoDirectApprove (Case 5 below); ceoApprove
// deliberately does NOT re-derive, and the SECOND approve call (the final CEO sign-off on the
// two-stage route) deliberately never honours an ApproveOvertimeRequest.dayType either -- both
// already proven at the Java level
// (OvertimeDayTypeDerivedFromCalendarIntegrationTest#aCalendarChangeAfterManagerApprovalIsNotPickedUpByTheFinalCeoSignOff
// / #theCeosFinalSignOffCannotOverrideWhatTheManagerAlreadyFroze) and not repeated here.
//
// THE PRECONDITION THIS FILE LIVES OR DIES ON: hr.holiday SHIPS EMPTY (V115's own Javadoc -- HR
// populates it once the BOT list is available, or manually through this same CRUD). If the
// calendar genuinely has zero rows for the work date's year, HolidayCalendar#hasHolidaysForYear
// is false and the calendar-unverified flag fires instead of a clean "no flag" answer. So every
// case below that needs "the calendar is loaded for this year" creates a real row through the
// real HolidayController first and reads it back through a SEPARATE GET before relying on it --
// never trusts its own create response, same discipline write-overtime.spec.js applies to
// readOvertime. If that read-back ever came up empty, the precondition assertion fails loudly
// right there, at setup, instead of the real assertion later failing for a reason that looks
// unrelated to the actual defect.
//
// SECOND PRECONDITION, NEW IN THIS BRANCH: demo.sales's division (ฝ่ายขาย) is OFFICE_5D (V115
// seed) -- an ordinary Mon-Fri schedule, no weekend assignment. That means whether "today" reads
// as an ordinary workday or a weekend-suggested-HOLIDAY now depends on which day of the week the
// suite happens to run on, which this file's own "always use today" design (below) cannot avoid.
// isWeekendInBangkok/expectedSuggestionFor compute the RIGHT answer for whichever day it is,
// rather than assuming one -- see their own comments. Two cases (the over-claim-disagreement case
// and the "frozen at approval" case) instead need a genuinely ORDINARY day to isolate what they
// are testing from the weekend rule; those use nearestWeekdayOnOrBefore, documented at its
// definition, which accepts a small, LOUD (409, never silent) residual risk of crossing a
// payroll-month boundary rather than adding more complexity to dodge it entirely.
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
//   • the work date is TODAY (or the nearest ordinary day on/before it) in the payroll timezone,
//     for the same reason write-overtime.spec.js gives: approval is gated on the payroll month
//     still being open, and a hardcoded date passes until that month is processed and then fails
//     for a reason that has nothing to do with the code under test. Where a case needs a HOLIDAY
//     date, the holiday is created ON that date rather than picked from the real Thai calendar,
//     for the same reason.
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
 * True when `dateIso` (a Bangkok CALENDAR date, e.g. "2026-08-08") falls on a Saturday or Sunday.
 * Built by constructing the instant of NOON Bangkok time on that date (`T12:00:00+07:00`, an
 * explicit offset, never a bare/UTC parse) and reading the weekday back out formatted in the SAME
 * timezone -- so the answer is correct regardless of what timezone the machine running this suite
 * happens to be in. This is the exact class of bug `frontend/src/api/mockApi.js`'s own comments
 * warn about for the identical problem (bangkokTodayIso): a naive `new Date(dateIso).getDay()`
 * parses the bare string as UTC midnight, which is the PREVIOUS Bangkok-local day whenever the
 * runner's zone is west of UTC+0 for part of the day -- silently computing the wrong weekday.
 */
function isWeekendInBangkok(dateIso) {
  const weekday = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Bangkok', weekday: 'short' })
    .format(new Date(`${dateIso}T12:00:00+07:00`));
  return weekday === 'Sat' || weekday === 'Sun';
}

/** Shifts a Bangkok calendar date by `days` (may be negative), in pure calendar arithmetic (no timezone involved). */
function shiftDateIso(dateIso, days) {
  const date = new Date(`${dateIso}T12:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/**
 * demo.sales's division (ฝ่ายขาย) is OFFICE_5D (V115 seed) -- Mon-Fri, no weekend assignment --
 * so this is the suggestion OvertimeService#suggestDayType actually produces for this persona
 * on an ordinary day with no hr.holiday row: WORKDAY on a weekday, HOLIDAY on a weekend.
 */
function expectedSuggestionFor(workDate) {
  return isWeekendInBangkok(workDate) ? 'HOLIDAY' : 'WORKDAY';
}

function expectedMultiplierFor(workDate) {
  return isWeekendInBangkok(workDate) ? 3 : 1.5;
}

/**
 * The nearest date on/before `dateIso` that is NOT a Saturday/Sunday -- nudges back at most 2
 * days. Used ONLY by the two cases below that specifically need a genuinely ORDINARY (non-
 * weekend) day to isolate what they are testing (a claim disagreeing with the suggestion; a
 * calendar change picked up between submit and approval) from the weekend-suggests-HOLIDAY rule
 * feat/ot-nonworkday-rate-suggestion adds -- every other case in this file uses todayInBangkok()
 * unmodified, per this file's header ("always use today", the payroll-month-open reason). Nudging
 * BACKWARD, not forward, keeps the date retroactive rather than risking one that has not arrived
 * yet; capping at 2 days means this only risks crossing a payroll-month boundary when the 1st or
 * 2nd of a month lands on a Saturday/Sunday -- a rare combination this file accepts as a residual,
 * LOUD (409 "already processed", never a silent wrong answer) risk rather than a reason to add
 * more machinery here.
 */
function nearestWeekdayOnOrBefore(dateIso) {
  let candidate = dateIso;
  let guard = 0;
  while (isWeekendInBangkok(candidate) && guard < 2) {
    candidate = shiftDateIso(candidate, -1);
    guard += 1;
  }
  return candidate;
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

function overtimePayload(reason, dayType, workDate = todayInBangkok()) {
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

test.describe('overtime day type: system suggestion, employee request, approver decision (real OvertimeService + HolidayCalendar + WorkScheduleResolver)', () => {
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

  /** Own-requests count, for the wrong-way-round "no row was created" assertion where still relevant. */
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

  /** Approves with an explicit approver decision -- {@code ApproveOvertimeRequest}'s shape. `dayType` omitted means "use the suggestion". */
  async function approve(session, id, dayType) {
    return apiWrite(session, 'POST', `/api/overtime/${id}/approve`, dayType ? { dayType } : {});
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
  // Case 1: THE row this branch changes. A HOLIDAY claim the calendar can actively disprove --
  // the year is loaded (a sentinel proves it) but the work date specifically is not in it, AND
  // the work date is an ordinary weekday (nearestWeekdayOnOrBefore, so the weekend rule cannot
  // also explain a HOLIDAY suggestion here) -- used to be refused outright (400, no row). Owner
  // ruling 2026-08-08: accepted and flagged instead. Wrong-way-round through to a full approval
  // with NO override: the claimed 3.00 must never reach pay_rate_multiplier.
  // -------------------------------------------------------------------------------------------
  test('a HOLIDAY claim contradicted by the calendar is accepted, flagged, and never becomes pay', async () => {
    const workDate = nearestWeekdayOnOrBefore(todayInBangkok());
    const sentinelDate = sentinelDateInSameYear(workDate);
    let created = null;

    await deleteHolidayBestEffort(sentinelDate);
    await createHoliday(sentinelDate, 'e2e: sentinel holiday, loads the calendar year');
    try {
      // Precondition #1: the year is genuinely loaded (>=1 row) -- read back independently, per
      // this file's header. If this silently failed, the derivation below would carry the
      // calendar-unverified flag instead of the claim-disagreement flag this test is about.
      await assertHolidayPersisted(sentinelDate);
      // Precondition #2: the work date genuinely is NOT a holiday, or this proves nothing about
      // a disagreeing claim -- only that something else was wrong.
      await assertNotHoliday(workDate);

      const before = await countOwnOvertime();

      const response = await apiWrite(
        sessions[OWNER],
        'POST',
        '/api/overtime',
        overtimePayload('e2e: HOLIDAY claim contradicted by the calendar, must be accepted and flagged', 'HOLIDAY', workDate)
      );

      // THE assertion this test exists for: accepted, not refused.
      expect(response.status(), await response.text()).toBe(200);
      ({ request: created } = await response.json());

      // A row WAS created (contrast with the old 400 behaviour this replaces).
      const after = await countOwnOvertime();
      expect(after, 'the claim is accepted -- exactly one new row').toBe(before + 1);

      expect(created.suggestedDayType, 'the suggestion is WORKDAY -- an ordinary weekday, no holiday').toBe('WORKDAY');
      expect(created.dayType, 'the stored day_type is the SUGGESTION, never the claim').toBe('WORKDAY');
      expect(created.payRateMultiplier).toBe(1.5);
      expect(created.calculationNote, 'the disagreement is flagged for the approver').toContain('ไม่ตรงกับที่ระบบแนะนำ');

      // Wrong-way-round: approve with NO override (the approver's default is the suggestion) and
      // confirm the claimed 3.00 never reaches pay. Stopping at MANAGER_APPROVED is sufficient --
      // that IS the freeze point (OvertimeService#managerApprove/#calculate); the final CEO
      // sign-off deliberately never re-derives (proved separately, see this file's header), so it
      // could not change this answer either way.
      const approveResponse = await approve(sessions[MANAGER], created.id);
      expect(approveResponse.status(), await approveResponse.text()).toBe(200);
      const { request: approved } = await approveResponse.json();

      expect(approved.status).toBe('MANAGER_APPROVED');
      expect(approved.dayType, 'still WORKDAY after manager approval -- the claim never won').toBe('WORKDAY');
      expect(approved.payRateMultiplier).toBe(1.5);

      const persisted = await readOvertime(created.id);
      expect(persisted.dayType).toBe('WORKDAY');
      expect(persisted.payRateMultiplier).toBe(1.5);
    } finally {
      if (created) {
        await cancelOvertime(created.id);
      }
      await deleteHolidayBestEffort(sentinelDate);
    }
  });

  // -------------------------------------------------------------------------------------------
  // Case 2: the claim IS corroborated by the calendar -- accepted at the real holiday rate, with
  // no flag. A recorded holiday always wins regardless of day-of-week, so this needs no weekday
  // isolation -- todayInBangkok() is fine whatever day it happens to be.
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
        overtimePayload('e2e: HOLIDAY claim corroborated by the calendar', 'HOLIDAY', workDate)
      );
      expect(response.status(), await response.text()).toBe(200);
      ({ request: created } = await response.json());

      expect(created.suggestedDayType).toBe('HOLIDAY');
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
  // Case 3: the claim UNDER-states the true day type -- suggestDayType corrects it upward
  // regardless. The claim can never suppress real holiday pay, only (harmlessly) fail to inflate
  // it -- see SubmitOvertimeRequest's Javadoc. Same day-of-week independence as Case 2.
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
        overtimePayload('e2e: WORKDAY claim on a genuine holiday, must not suppress pay', 'WORKDAY', workDate)
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
  // Case 4: the base case -- no claim at all, calendar genuinely LOADED for the year (a sentinel,
  // same technique as Case 1). Asserts the SCHEDULE-AWARE suggestion for whichever day today
  // happens to be, rather than assuming a weekday -- expectedSuggestionFor/expectedMultiplierFor
  // compute the answer demo.sales's real OFFICE_5D schedule actually produces. Asserting the note
  // too (not just the multiplier) is what tells "genuinely unflagged" apart from "the flag
  // silently regressed to always-false" -- and, on a weekday specifically, from "the calendar-
  // unverified flag silently regressed to always-true".
  // -------------------------------------------------------------------------------------------
  test('a day with no claim stores the schedule-derived suggestion, unflagged when the calendar is loaded', async () => {
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
        overtimePayload('e2e: no day-type claim at all', undefined, workDate)
      );
      expect(response.status(), await response.text()).toBe(200);
      ({ request: created } = await response.json());

      const expectedDayType = expectedSuggestionFor(workDate);
      expect(created.suggestedDayType).toBe(expectedDayType);
      expect(created.dayType).toBe(expectedDayType);
      expect(created.payRateMultiplier).toBe(expectedMultiplierFor(workDate));
      expect(
        created.calculationNote,
        'the year IS loaded (sentinel above): a WORKDAY suggestion must not carry the calendar-unverified flag, and a schedule-derived HOLIDAY suggestion never needs the calendar at all'
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
  // stored at submit. Needs a genuinely ORDINARY (non-weekend) work date to isolate "a holiday
  // added after submit" from the weekend rule -- nearestWeekdayOnOrBefore, see its own comment.
  // Mirrors
  // OvertimeDayTypeDerivedFromCalendarIntegrationTest#aHolidayAddedToTheCalendarAfterSubmitIsPickedUpAndFrozenAtManagerApproval
  // at the HTTP layer, through the real HolidayController instead of a direct INSERT.
  // -------------------------------------------------------------------------------------------
  test('day_type is frozen at approval: a holiday HR adds after submit is picked up at manager approval', async () => {
    const workDate = nearestWeekdayOnOrBefore(todayInBangkok());
    let created = null;

    await deleteHolidayBestEffort(workDate);
    try {
      await assertNotHoliday(workDate);

      const submitResponse = await apiWrite(
        sessions[OWNER],
        'POST',
        '/api/overtime',
        overtimePayload('e2e: submitted before any holiday existed for this date', undefined, workDate)
      );
      expect(submitResponse.status(), await submitResponse.text()).toBe(200);
      ({ request: created } = await submitResponse.json());
      expect(created.dayType, 'must start out WORKDAY -- an ordinary weekday, no holiday exists yet at submit time').toBe('WORKDAY');
      expect(created.suggestedDayType).toBe('WORKDAY');
      // hasManagerApprover is what routes this to managerApprove (rather than ceoDirectApprove),
      // which is the stage this case needs -- see write-overtime.spec.js for why this OWNER/
      // MANAGER pairing guarantees a manager stage exists for demo.sales.
      expect(created.hasManagerApprover).toBe(true);

      // HR adds a holiday for the SAME date, strictly after the request already exists.
      await createHoliday(workDate, 'e2e: added after submit, before approval');
      await assertHolidayPersisted(workDate);

      const approveResponse = await approve(sessions[MANAGER], created.id);
      expect(approveResponse.status(), await approveResponse.text()).toBe(200);
      const { request: approved } = await approveResponse.json();

      expect(approved.status).toBe('MANAGER_APPROVED');
      expect(
        approved.dayType,
        'suggestDayType must re-run at manager approval, not trust the submit-time WORKDAY value'
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

  // -------------------------------------------------------------------------------------------
  // Case 6 (feat/ot-nonworkday-rate-suggestion, NEW): the approver's explicit decision wins over
  // the suggestion, in whichever direction actually differs from it -- proved generically so it
  // holds regardless of which day of the week the suite runs on.
  // -------------------------------------------------------------------------------------------
  test("the approver's explicit dayType overrides the suggestion", async () => {
    const workDate = todayInBangkok();
    const suggested = expectedSuggestionFor(workDate);
    const override = suggested === 'HOLIDAY' ? 'WORKDAY' : 'HOLIDAY';
    let created = null;

    await deleteHolidayBestEffort(workDate);
    try {
      const submitResponse = await apiWrite(
        sessions[OWNER],
        'POST',
        '/api/overtime',
        overtimePayload('e2e: approver overrides the suggestion', undefined, workDate)
      );
      expect(submitResponse.status(), await submitResponse.text()).toBe(200);
      ({ request: created } = await submitResponse.json());
      expect(created.suggestedDayType).toBe(suggested);

      const approveResponse = await approve(sessions[MANAGER], created.id, override);
      expect(approveResponse.status(), await approveResponse.text()).toBe(200);
      const { request: approved } = await approveResponse.json();

      expect(approved.dayType, "the approver's explicit choice must win over the suggestion").toBe(override);
      expect(approved.payRateMultiplier).toBe(override === 'HOLIDAY' ? 3 : 1.5);
    } finally {
      if (created) {
        await cancelOvertime(created.id);
      }
      await deleteHolidayBestEffort(workDate);
    }
  });

  // -------------------------------------------------------------------------------------------
  // Case 7 (feat/ot-nonworkday-rate-suggestion, NEW): an actor forbidden from approving this
  // request cannot move money through the new dayType field either -- wrong-way-round. The
  // submitter attempting to approve their OWN request must be refused before dayType is ever
  // read, leaving the row completely untouched.
  // -------------------------------------------------------------------------------------------
  test('the submitter cannot move their own money through the new approver dayType field', async () => {
    const workDate = todayInBangkok();
    let created = null;

    await deleteHolidayBestEffort(workDate);
    try {
      const submitResponse = await apiWrite(
        sessions[OWNER],
        'POST',
        '/api/overtime',
        overtimePayload('e2e: self-approve attempt with a dayType override', undefined, workDate)
      );
      expect(submitResponse.status(), await submitResponse.text()).toBe(200);
      ({ request: created } = await submitResponse.json());
      const suggested = created.dayType;

      // The exact P0 shape this suite exists to close, replayed through the NEW field: the
      // submitter tries to approve their OWN request, forcing HOLIDAY to inflate their own pay.
      const selfApproveResponse = await approve(sessions[OWNER], created.id, 'HOLIDAY');
      expect(selfApproveResponse.status(), await selfApproveResponse.text()).toBe(403);

      const persisted = await readOvertime(created.id);
      expect(persisted.status, 'a rejected self-approve attempt must not move the request at all').toBe('SUBMITTED');
      expect(persisted.dayType, 'the HOLIDAY override never took effect').toBe(suggested);
    } finally {
      if (created) {
        await cancelOvertime(created.id);
      }
      await deleteHolidayBestEffort(workDate);
    }
  });
});
