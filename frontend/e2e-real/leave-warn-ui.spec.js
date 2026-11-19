import { test, expect } from '@playwright/test';
import { loginAs } from './helpers/auth.js';
import { apiSessionFor, apiWrite, disposeSessions } from './helpers/api.js';

// ─────────────────────────────────────────────────────────────────────────────
// UI coverage: the leave-request COMPOSER and the approver REVIEW QUEUE render a non-blocking §5
// WARN_UNPAID_* verdict (LeaveRuleCode.enforcement() == WARN_UNPAID_ALL/WARN_UNPAID_EXCESS) --
// 10 of the 17 LeaveRuleCode gates do not auto-reject and instead let the request through as
// SUBMITTED, carrying ruleWarnings/unpaidByRuleDays. 7 gates still BLOCK outright. Backend
// behaviour is covered by integration tests elsewhere; THIS file is deliberately UI-only: what a
// real browser renders for a warning vs a block, nothing about the rule engine itself.
//
// `write-leave-review.spec.js` in this same directory is API-driven and never opens
// LeaveRequestPage.jsx or ReviewQueueTab.jsx at all, so this file does not duplicate it.
//
// HOW A WARNING IS RAISED: PERSONAL (ลากิจ) requires 1 day of advance notice, VACATION (ลาพักร้อน)
// requires 3 -- see V116__leave_type_rule_columns.sql. A same-day PERSONAL request on a weekday
// (or a next-working-day VACATION one on a weekend, see NOTICE_SHORT) therefore always
// trips ADVANCE_NOTICE (WARN_UNPAID_ALL, LeaveRuleCode.java) for the seeded `employee` persona,
// whose hire_date (V139) is three years back -- well past every other categorical gate (probation,
// min-service, once-per-employment), so ADVANCE_NOTICE is the ONE gate this persona can trip via a
// simple same-day submission with no other state to engineer.
//
// HOW A BLOCK IS RAISED (the contrast case): CONTIGUOUS_LEAVE_PAIR (a BLOCK because it "stops
// leave-stringing, which docking pay does not achieve") pairs VACATION and
// PERSONAL (LeaveService.CONTIGUOUS_LEAVE_PAIR). Seeding a SUBMITTED VACATION on day D and then
// submitting PERSONAL on day D+1 (both real working days, zero gap between them) trips it via the
// real backend, without touching hire_date, probation, or any other employee-record state.
// ─────────────────────────────────────────────────────────────────────────────

const OWNER_ROLE = 'employee'; // DEMO-EMP01

// Design tokens actually rendered for each LeaveRulePanel tone (frontend/src/index.css) -- read as real
// computed `color` on the panel's own heading (`style.accent`, LeaveRulePanel.jsx), never as a class-name
// match.
const DANGER_HEADING_COLOR = 'rgb(220, 38, 38)'; // --color-danger

/** Today in Asia/Bangkok, the zone the app runs leave periods in (mirrors write-leave-review.spec.js). */
function todayInBangkok() {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Bangkok' });
}

/** An ISO date `days` after today in Asia/Bangkok. */
function daysFromToday(days) {
  const base = new Date(`${todayInBangkok()}T00:00:00Z`);
  base.setUTCDate(base.getUTCDate() + days);
  return base.toISOString().slice(0, 10);
}

/** The first real weekday (Mon-Fri) at or after `+days`, whose OWN next calendar day is also a weekday -- i.e.
 * a Mon/Tue/Wed/Thu. */
function adjacentWeekdayPairStart(days) {
  let iso = daysFromToday(days);
  for (let i = 0; i < 10; i += 1) {
    const dow = new Date(`${iso}T00:00:00Z`).getUTCDay();
    if (dow >= 1 && dow <= 4) return iso; // Mon-Thu: tomorrow is guaranteed Tue-Fri, still a weekday.
    iso = daysFromToday(days + i + 1);
  }
  throw new Error(`no Mon-Thu day found within 10 days of +${days}`);
}

function addOneDay(iso) {
  const date = new Date(`${iso}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}

/**
 * The first Mon-Fri day at or after today, in Asia/Bangkok.
 *
 * The same-day ADVANCE_NOTICE cases need a period that STARTS today -- that is what makes the
 * notice 0 days, so PERSONAL's 1-day requirement is violated and the rule fires. But the period
 * must ALSO contain at least one working day: `LeaveService` refuses one that does not, with
 * `400 ช่วงวันลาต้องมีวันทำงานอย่างน้อย 1 วัน`. Those two demands conflict on a Sat/Sun, which is
 * why the approver flow would fail every weekend without it.
 *
 * Using this as the END date satisfies both: the period still starts today, and it now reaches a
 * working day. On a WEEKDAY this returns today, so the period stays today..today. On a Sat/Sun it returns the coming Monday, giving today..Monday --
 * which still counts exactly ONE working day, because totalDays counts working days only. Every
 * downstream "1 วัน" assertion therefore holds unchanged on either kind of day.
 *
 * Public holidays are NOT skipped, matching `adjacentWeekdayPairStart`'s own caveat above: if the
 * next working day is a holiday this is still wrong, but it is wrong the same way the rest of this
 * file already is, rather than a new mechanism.
 */
function firstWorkingDayOnOrAfterToday() {
  for (let i = 0; i < 10; i += 1) {
    const iso = daysFromToday(i);
    const dow = new Date(`${iso}T00:00:00Z`).getUTCDay();
    if (dow >= 1 && dow <= 5) return iso;
  }
  throw new Error('no Mon-Fri day found within 10 days of today');
}

/** Today in Asia/Bangkok is a Saturday or Sunday. */
function weekendInBangkok() {
  const dow = new Date(`${todayInBangkok()}T00:00:00Z`).getUTCDay();
  return dow === 0 || dow === 6;
}

/**
 * A one-working-day request that is short of its type's advance notice, so ADVANCE_NOTICE fires.
 *
 * Notice counts the employee's WORKING days after today up to the leave's first working day
 * (LeaveService). On a weekday a PERSONAL request starting today has 0
 * days -- under PERSONAL's 1. On a Sat/Sun there is no such PERSONAL request: the earliest working
 * day is Monday, and Monday itself already counts as 1 day of notice. So on a weekend this uses
 * VACATION (3 days' notice) on that Monday instead: 1 < 3, the same warning and pay consequence.
 */
const NOTICE_SHORT = weekendInBangkok()
  ? { typeCode: 'VACATION', typeNameTh: 'ลาพักร้อน', date: firstWorkingDayOnOrAfterToday() }
  : { typeCode: 'PERSONAL', typeNameTh: 'ลากิจ', date: todayInBangkok() };

// ── Composer navigation helpers ─────────────────────────────────────────────

async function gotoComposer(page) {
  await page.goto('/leave/new');
  await expect(page.getByRole('heading', { name: /เลือกประเภทการลา/ })).toBeVisible();
}

/** Step 1: pick a type by its exact Thai name and advance to step 2. */
async function chooseTypeAndAdvance(page, typeNameTh) {
  await page.getByRole('button', { name: typeNameTh, exact: true }).click();
  await page.getByRole('button', { name: 'ถัดไป', exact: true }).click();
  await expect(page.getByRole('heading', { name: /วันที่และรายละเอียด/ })).toBeVisible();
}

/** Step 2: set dates (defaults are already today/today -- only call this to change them), fill the required
 * reason, and optionally set the PERSONAL purpose select. */
async function fillStep2({
  page, startDate, endDate, reason, purposeCode,
}) {
  if (startDate) await page.locator('#leave-start-date').fill(startDate);
  if (endDate) await page.locator('#leave-end-date').fill(endDate);
  if (purposeCode) await page.locator('#leave-purpose-code').selectOption(purposeCode);
  await page.locator('#leave-reason').fill(reason);
}

/** Step 2 -> Step 3, waiting for the terminal FULL preview to resolve past its skeleton. */
async function advanceToStep3(page) {
  await page.getByRole('button', { name: 'ถัดไป: ตรวจสอบก่อนส่ง', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'ตรวจสอบก่อนส่ง' })).toBeVisible();
  await expect(page.getByLabel('กำลังตรวจสอบคำขอ')).toHaveCount(0);
}

/** The real computed heading colour of a rendered LeaveRulePanel, located by its RULE_META label text
 * (`getByText`, never a class name -- see this file's header). */
function panelHeadingColor(page, label) {
  return page.getByText(label, { exact: true }).evaluate((el) => getComputedStyle(el).color);
}

/** Neither MyLeaveTab nor ReviewQueueTab passes `searchable` to DataTable, so there is no search box to filter
 * with -- the row for a given (unique) reason string has to be located directly. */
function rowFor(page, reason) {
  return page.locator('tr, li').filter({ hasText: reason }).first();
}

test.describe('leave composer: a BLOCK gate still blocks (contrast case)', () => {
  test('case 5: CONTIGUOUS_LEAVE_PAIR still BLOCKs -- red panel, submit disabled', async ({ page }) => {
    const owner = await apiSessionFor(OWNER_ROLE);
    const vacationDay = adjacentWeekdayPairStart(150);
    const personalDay = addOneDay(vacationDay);
    let vacationRequestId;

    try {
      const created = await apiWrite(owner, 'post', '/api/leave', {
        leaveTypeCode: 'VACATION',
        startDate: vacationDay,
        endDate: vacationDay,
        reason: 'e2e-real: case5 contiguous-block setup (VACATION half)',
      });
      const body = await created.text();
      expect(created.status(), `setup POST /api/leave (VACATION) expected 200: ${body}`).toBe(200);
      vacationRequestId = JSON.parse(body).request.id;

      await loginAs(page, OWNER_ROLE);
      await gotoComposer(page);
      await chooseTypeAndAdvance(page, 'ลากิจ'); // PERSONAL -- CONTIGUOUS_LEAVE_PAIR's other half
      await fillStep2({
        page, startDate: personalDay, endDate: personalDay,
        reason: 'e2e-real: case5 contiguous-block PERSONAL attempt',
      });
      await advanceToStep3(page);

      const label = 'ลาต่อเนื่องกับวันลาอื่น';
      await expect(page.getByText(label, { exact: true })).toBeVisible();
      // BLOCK renders inside role="alert" (step3Blocking) -- the structural contrast with the WARN_UNPAID_* path,
      // which never gets that wrapper.
      await expect(page.getByRole('alert').getByText(label, { exact: true })).toBeVisible();
      expect(await panelHeadingColor(page, label)).toBe(DANGER_HEADING_COLOR);

      await expect(page.getByRole('button', { name: /ส่งคำขอ/ })).toBeDisabled();
    } finally {
      if (vacationRequestId) {
        const cleanup = await apiWrite(owner, 'post', `/api/leave/${vacationRequestId}/cancel`, {});
        expect(cleanup.status(), 'cleanup cancel of the setup VACATION request must succeed').toBe(200);
      }
      await disposeSessions({ owner });
    }
  });
});

test.describe.serial('leave approver: warning visibility, pay-consequence confirm and quota', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  const sessions = {};
  const reason = `e2e-real: approver-flow warn request ${Date.now()}`;
  let requestId;
  let totalDays;
  let quotaRemainingBeforeCreate;

  test.beforeAll(async () => {
    sessions[OWNER_ROLE] = await apiSessionFor(OWNER_ROLE);
    sessions.hr = await apiSessionFor('hr');

    const year = Number(todayInBangkok().slice(0, 4));
    const balancesBefore = await sessions[OWNER_ROLE].get(`/api/leave/balances?year=${year}`);
    expect(balancesBefore.status(), 'GET /api/leave/balances (baseline)').toBe(200);
    const { balances } = await balancesBefore.json();
    const quotaBefore = balances.find((b) => b.leaveTypeCode === NOTICE_SHORT.typeCode);
    expect(quotaBefore, `${NOTICE_SHORT.typeCode} balance must be present in the baseline read`).toBeTruthy();
    quotaRemainingBeforeCreate = Number(quotaBefore.remainingDays);

    // NOTICE_SHORT -> ADVANCE_NOTICE (WARN_UNPAID_ALL): the request becomes unpaid by
    // rule if approved, which is what makes "quota has not moved" a meaningful assertion below --
    // LeaveRepository#sumUsedDays deliberately subtracts unpaid_by_rule_days from quota
    // consumption (see that method's own Javadoc).
    const created = await apiWrite(sessions[OWNER_ROLE], 'post', '/api/leave', {
      leaveTypeCode: NOTICE_SHORT.typeCode,
      startDate: NOTICE_SHORT.date,
      endDate: NOTICE_SHORT.date,
      reason,
    });
    const body = await created.text();
    expect(created.status(), `setup POST /api/leave expected 200: ${body}`).toBe(200);
    const { request: request_ } = JSON.parse(body);
    expect(request_.status).toBe('SUBMITTED');
    requestId = request_.id;
    totalDays = Number(request_.totalDays);
    // Still exactly 1 even when the end date is stretched over a weekend: totalDays counts WORKING days, so
    // today..Monday on a Saturday is the single Monday.
    expect(totalDays, 'this request must resolve to exactly 1 working day').toBe(1);
  });

  test.afterAll(async () => {
    if (requestId) {
      // Reviewer cancel works from either SUBMITTED (if case 6 never got to approve) or APPROVED (the normal path)
      // -- see write-leave-review.spec.js's own cleanup for the same reasoning.
      const cleanup = await apiWrite(sessions.hr, 'post', `/api/leave/${requestId}/cancel`, {});
      expect(cleanup.status(), 'cleanup cancel must succeed or this spec is not re-runnable').toBe(200);
    }
    await disposeSessions(sessions);
  });

  test('case 6: the approver sees the warning before approving, and the confirm button states the unpaid days', async ({ page }) => {
    await loginAs(page, 'hr');
    await page.goto('/leave?tab=review');

    const row = rowFor(page, reason);
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: /ดูรายละเอียด/ }).click();

    // BEFORE approving: the warning is visible in the expanded row.
    await expect(page.getByText('แจ้งล่วงหน้าไม่ครบกำหนด', { exact: true })).toBeVisible();

    await row.getByRole('button', { name: 'อนุมัติ', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'อนุมัติ (ไม่จ่ายค่าจ้าง 1 วัน)', exact: true })).toBeVisible();

    await dialog.getByRole('button', { name: 'อนุมัติ (ไม่จ่ายค่าจ้าง 1 วัน)', exact: true }).click();
    await expect(dialog).toHaveCount(0);
  });

  test('case 7: after approving, the paid/unpaid split is right and the employee quota has not moved', async () => {
    const after = await sessions.hr.get('/api/leave');
    expect(after.status()).toBe(200);
    const { requests } = await after.json();
    const approved = requests.find((r) => r.id === requestId);
    expect(approved, 'the approved request must still be found').toBeTruthy();
    expect(approved.status, 'case 6 must have actually approved it').toBe('APPROVED');
    expect(Number(approved.paidDays), 'ADVANCE_NOTICE is WARN_UNPAID_ALL -- 0 of the day is paid').toBe(0);
    expect(Number(approved.unpaidDays)).toBe(totalDays);
    expect(Number(approved.unpaidByRuleDays)).toBe(totalDays);

    const year = Number(todayInBangkok().slice(0, 4));
    const balancesAfter = await sessions[OWNER_ROLE].get(`/api/leave/balances?year=${year}`);
    expect(balancesAfter.status()).toBe(200);
    const { balances } = await balancesAfter.json();
    const quotaAfter = balances.find((b) => b.leaveTypeCode === NOTICE_SHORT.typeCode);
    expect(
      Number(quotaAfter.remainingDays),
      'a fully unpaid-by-rule day must not consume quota -- LeaveRepository#sumUsedDays subtracts it'
    ).toBe(quotaRemainingBeforeCreate);
  });
});
