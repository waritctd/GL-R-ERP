import { test, expect } from '@playwright/test';
import { loginAs } from './helpers/auth.js';
import { apiSessionFor, apiWrite, disposeSessions } from './helpers/api.js';

// ─────────────────────────────────────────────────────────────────────────────
// V164 UI coverage: the leave-request COMPOSER and the approver REVIEW QUEUE both learned to
// render a non-blocking §5 WARN_UNPAID_* verdict (LeaveRuleCode.enforcement() ==
// WARN_UNPAID_ALL/WARN_UNPAID_EXCESS) -- 10 of the 17 LeaveRuleCode gates stopped auto-rejecting
// and instead let the request through as SUBMITTED, carrying ruleWarnings/unpaidByRuleDays. 7
// gates still BLOCK outright. Backend behaviour is covered by integration tests elsewhere (a
// sibling branch is closing the remaining gaps); THIS file is deliberately UI-only: what a real
// browser renders for a warning vs a block, nothing about the rule engine itself.
//
// `write-leave-review.spec.js` in this same directory is API-driven and never opens
// LeaveRequestPage.jsx or ReviewQueueTab.jsx at all -- this file is genuinely new ground, not a
// duplicate of it.
//
// HOW A WARNING IS RAISED: PERSONAL (ลากิจ) requires 1 day of advance notice, VACATION (ลาพักร้อน)
// requires 3 -- see V116__leave_type_rule_columns.sql. A same-day PERSONAL request therefore always
// trips ADVANCE_NOTICE (WARN_UNPAID_ALL, LeaveRuleCode.java) for the seeded `employee` persona,
// whose hire_date (V139) is three years back -- well past every other categorical gate (probation,
// min-service, once-per-employment), so ADVANCE_NOTICE is the ONE gate this persona can trip via a
// simple same-day submission with no other state to engineer.
//
// HOW A BLOCK IS RAISED (the contrast case): CONTIGUOUS_LEAVE_PAIR (still BLOCK, V164 owner
// ruling -- "stops leave-stringing, which docking pay does not achieve") pairs VACATION and
// PERSONAL (LeaveService.CONTIGUOUS_LEAVE_PAIR). Seeding a SUBMITTED VACATION on day D and then
// submitting PERSONAL on day D+1 (both real working days, zero gap between them) trips it via the
// real backend, without touching hire_date, probation, or any other employee-record state.
// ─────────────────────────────────────────────────────────────────────────────

const OWNER_ROLE = 'employee'; // DEMO-EMP01

// Design tokens actually rendered for each LeaveRulePanel tone (frontend/src/index.css) -- read
// as real computed `color` on the panel's own heading (`style.accent`, LeaveRulePanel.jsx), never
// as a class-name match. This is what makes "amber, not danger" a real assertion instead of one
// that would still pass if the two tones' colours were swapped but the wrapping markup untouched.
const WARNING_HEADING_COLOR = 'rgb(146, 64, 14)'; // --color-warning-dark
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

/**
 * The first real weekday (Mon-Fri) at or after `+days`, whose OWN next calendar day is also a
 * weekday -- i.e. a Mon/Tue/Wed/Thu. Needed only for the contiguous-block setup: VACATION on D and
 * PERSONAL on D+1 must both land on days the employee would actually work, or the rule's own
 * "no intervening workday" test (LeaveService#isContiguous) has nothing to compare. See
 * write-leave-review.spec.js's `workingDayFromToday` for the same Bangkok-TZ weekend-flake
 * reasoning this helper inherits verbatim (public holidays are NOT skipped here either).
 */
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
 * why cases 1, 3 and 6 failed every weekend (first seen 2026-09-12, a Saturday -- and the suite
 * had never run on a weekend before, so it went unnoticed).
 *
 * Using this as the END date satisfies both: the period still starts today, and it now reaches a
 * working day. On a WEEKDAY this returns today, so the period stays today..today and behaviour is
 * byte-for-byte what it was. On a Sat/Sun it returns the coming Monday, giving today..Monday --
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

/** Step 2: set dates (defaults are already today/today -- only call this to change them), fill
 *  the required reason, and optionally set the PERSONAL purpose select. */
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

/** The real computed heading colour of a rendered LeaveRulePanel, located by its RULE_META label
 *  text (`getByText`, never a class name -- see this file's header). */
function panelHeadingColor(page, label) {
  return page.getByText(label, { exact: true }).evaluate((el) => getComputedStyle(el).color);
}

/**
 * Neither MyLeaveTab nor ReviewQueueTab passes `searchable` to DataTable, so there is no search
 * box to filter with -- the row for a given (unique) reason string has to be located directly.
 * Matches EITHER row shape DataTable can render: a desktop `<tr class="data-row">` (>=721px) or a
 * mobile `<li class="record-card">` (<721px) -- see DataTable.jsx's own branch for `asCards`.
 * `.first()` is deliberate, not a shortcut: once a desktop row is expanded, DataTable renders a
 * SECOND sibling `<tr>` for the expanded detail panel, which repeats the same reason text
 * (renderLeaveRequestExpanded's own "เหตุผล" field) -- the real row is always the first of the two
 * in DOM order, the expanded detail second. On mobile the expanded content lives inside the SAME
 * `<li>`, so there is only ever one match there regardless of expand state.
 */
function rowFor(page, reason) {
  return page.locator('tr, li').filter({ hasText: reason }).first();
}

test.describe('leave composer: §5 WARN_UNPAID_* rendering (V164 UI)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, OWNER_ROLE);
  });

  test('case 1: a warning renders amber (not danger), submit stays enabled, and the button label states the pay consequence', async ({ page }) => {
    await gotoComposer(page);
    await chooseTypeAndAdvance(page, 'ลากิจ'); // PERSONAL, advance_notice_days = 1
    // The start date defaults to today (defaultForm) -- 0 days' notice, violating PERSONAL's
    // 1-day requirement, so ADVANCE_NOTICE (WARN_UNPAID_ALL) fires. The END date is stretched to
    // the first working day so the period is not all-weekend, which the service rejects outright;
    // on a weekday that IS today, leaving this exactly as it was. See
    // firstWorkingDayOnOrAfterToday.
    await fillStep2({
      page,
      endDate: firstWorkingDayOnOrAfterToday(),
      reason: 'e2e-real: case1 amber warning, enabled submit',
    });
    await advanceToStep3(page);

    // The ADVANCE_NOTICE panel is rendered (RULE_META label), amber-toned, and NOT wrapped in the
    // role="alert" the BLOCK path uses (LeaveRequestPage.jsx: only step3Blocking/step3Error get
    // role="alert" -- see case 5 below for the block-path contrast).
    const label = 'แจ้งล่วงหน้าไม่ครบกำหนด';
    await expect(page.getByText(label, { exact: true })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    expect(await panelHeadingColor(page, label)).toBe(WARNING_HEADING_COLOR);

    const submit = page.getByRole('button', { name: /ส่งคำขอ/ });
    await expect(submit).toBeEnabled();
    await expect(submit).toHaveText('ส่งคำขอ (ไม่รับค่าจ้าง)');
  });

  test('a clean request (no warning) shows the plain submit label', async ({ page }) => {
    // Contrast half of case 1's label assertion: VACATION booked with full advance notice trips
    // no gate at all, so the button must read the PLAIN label, not the unpaid one.
    await gotoComposer(page);
    await chooseTypeAndAdvance(page, 'ลาพักร้อน'); // VACATION, advance_notice_days = 3
    const cleanDay = adjacentWeekdayPairStart(120);
    await fillStep2({
      page, startDate: cleanDay, endDate: cleanDay, reason: 'e2e-real: clean submit label',
    });
    await advanceToStep3(page);

    await expect(page.getByText('แจ้งล่วงหน้าไม่ครบกำหนด')).toHaveCount(0);
    const submit = page.getByRole('button', { name: 'ส่งคำขอ', exact: true });
    await expect(submit).toBeEnabled();
  });

  test('case 2: multiple simultaneous warnings all render, not just the first', async ({ page }) => {
    // PERSONAL + purposeCode=WEDDING, requested for MORE than the 3-day wedding cap (§5.2,
    // WEDDING_MAX_DAYS, hardcoded WEDDING_LEAVE_MAX_DAYS=3.00) AND submitted same-day (violates
    // the 1-day notice, ADVANCE_NOTICE) -- LeaveService#autoRejectNote evaluates the wedding cap
    // first, appends it to `warnings`, and (being WARN_UNPAID_ALL/EXCESS, never BLOCK since V164)
    // keeps going rather than returning -- so ADVANCE_NOTICE fires too and BOTH accumulate on one
    // response. PERSONAL's generic max_consecutive_days column is NULL since V120 (superseded by
    // FIRST_YEAR_MAX_DAYS -- LeaveRuleCode's own class Javadoc), so only WEDDING_MAX_DAYS's own
    // hardcoded 3-day cap is in play here, not a second, redundant consecutive-days warning.
    await gotoComposer(page);
    await chooseTypeAndAdvance(page, 'ลากิจ');
    const start = todayInBangkok();
    const end = daysFromToday(3); // start..start+3 = 4 calendar days, 1 day over the wedding cap.
    await fillStep2({
      page, startDate: start, endDate: end, purposeCode: 'WEDDING',
      reason: 'e2e-real: case2 multiple warnings',
    });

    const weddingLabel = 'เพดานวันลาพิธีสมรส';
    const noticeLabel = 'แจ้งล่วงหน้าไม่ครบกำหนด';
    await expect(page.getByText(weddingLabel, { exact: true })).toBeVisible();
    await expect(page.getByText(noticeLabel, { exact: true })).toBeVisible();

    // The count that matters (not merely "one is present" -- a regression that kept only the
    // FIRST warning would still pass two separate `toBeVisible()` calls if it kept the wrong one
    // twice; this counts the actual number of rendered panel headings).
    const warningHeadings = page.locator('strong').filter({ hasText: new RegExp(`^(${weddingLabel}|${noticeLabel})$`) });
    await expect(warningHeadings).toHaveCount(2);
  });

  test('case 3: the quick-check "passed" line is suppressed when a warning fires, shown when clean', async ({ page }) => {
    const passedLine = page.getByText(/ผ่านเงื่อนไขที่ตรวจแบบเร็วแล้ว/);

    // Half A: a warning fires (same-day PERSONAL) -> the green line must be ABSENT, not merely
    // pushed below the amber panel -- LeaveRequestPage.jsx gates it on
    // `!step2Blocking && step2Warnings.length === 0`.
    await gotoComposer(page);
    await chooseTypeAndAdvance(page, 'ลากิจ');
    await fillStep2({
      page,
      endDate: firstWorkingDayOnOrAfterToday(),
      reason: 'e2e-real: case3 warning suppresses passed line',
    });
    await expect(page.getByText('แจ้งล่วงหน้าไม่ครบกำหนด', { exact: true })).toBeVisible();
    await expect(passedLine).toHaveCount(0);

    // Half B: a clean request -> the green line IS shown. VACATION with full advance notice, no
    // wedding/probation/etc gate in play.
    await gotoComposer(page);
    await chooseTypeAndAdvance(page, 'ลาพักร้อน');
    const cleanDay = adjacentWeekdayPairStart(130);
    await fillStep2({
      page, startDate: cleanDay, endDate: cleanDay, reason: 'e2e-real: case3 clean shows passed line',
    });
    await expect(passedLine).toBeVisible();
  });

  test('case 4: a dateless preview renders no warning at all, and never a fabricated "0 วัน"', async ({ page }) => {
    await gotoComposer(page);
    await chooseTypeAndAdvance(page, 'ลากิจ');
    // Dates default to today/today -- clear the start date so step2Params becomes null
    // (LeaveRequestPage.jsx: `if (!employeeId || !leaveTypeCode || !startDate || !endDate) return
    // null`), which disables the preview query entirely rather than fetching for an empty range.
    await page.locator('#leave-start-date').fill('');
    await page.locator('#leave-reason').fill('e2e-real: case4 dateless preview');

    const mainRegion = page.locator('main');
    await expect(mainRegion.getByText('แจ้งล่วงหน้าไม่ครบกำหนด')).toHaveCount(0);
    await expect(mainRegion.getByText(/ผ่านเงื่อนไขที่ตรวจแบบเร็วแล้ว/)).toHaveCount(0);
    // The fabrication this case exists to catch: a stale/zeroed preview rendering "0 วัน" as
    // though it were a real answer for a request with no dates at all.
    await expect(mainRegion.getByText('0 วัน', { exact: true })).toHaveCount(0);
  });
});

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
      // BLOCK renders inside role="alert" (step3Blocking) -- the structural contrast with every
      // WARN_UNPAID_* case above, none of which ever gets that wrapper.
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

test.describe.serial('leave approver: warning visibility, pay-consequence confirm, quota, and layout', () => {
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
    const personalBefore = balances.find((b) => b.leaveTypeCode === 'PERSONAL');
    expect(personalBefore, 'PERSONAL balance must be present in the baseline read').toBeTruthy();
    quotaRemainingBeforeCreate = Number(personalBefore.remainingDays);

    // PERSONAL starting today -> ADVANCE_NOTICE (WARN_UNPAID_ALL): the request becomes unpaid by
    // rule if approved, which is what makes "quota has not moved" a meaningful assertion below --
    // LeaveRepository#sumUsedDays deliberately subtracts unpaid_by_rule_days from quota
    // consumption (see that method's own Javadoc).
    const created = await apiWrite(sessions[OWNER_ROLE], 'post', '/api/leave', {
      leaveTypeCode: 'PERSONAL',
      startDate: todayInBangkok(),
      endDate: firstWorkingDayOnOrAfterToday(),
      reason,
    });
    const body = await created.text();
    expect(created.status(), `setup POST /api/leave expected 200: ${body}`).toBe(200);
    const { request: request_ } = JSON.parse(body);
    expect(request_.status).toBe('SUBMITTED');
    requestId = request_.id;
    totalDays = Number(request_.totalDays);
    // Still exactly 1 even when the end date is stretched over a weekend: totalDays counts
    // WORKING days, so today..Monday on a Saturday is the single Monday.
    expect(totalDays, 'this request must resolve to exactly 1 working day').toBe(1);
  });

  test.afterAll(async () => {
    if (requestId) {
      // Reviewer cancel works from either SUBMITTED (if case 6 never got to approve) or APPROVED
      // (the normal path) -- see write-leave-review.spec.js's own cleanup for the same reasoning.
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

  test('case 7: after approving, the paid/unpaid split is right and the employee quota has not moved', async ({}) => {
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
    const personalAfter = balances.find((b) => b.leaveTypeCode === 'PERSONAL');
    expect(
      Number(personalAfter.remainingDays),
      'a fully unpaid-by-rule day must not consume quota -- LeaveRepository#sumUsedDays subtracts it'
    ).toBe(quotaRemainingBeforeCreate);
  });

  // Case 8: status badge + "ไม่รับค่าจ้าง N วัน" badge are stacked vertically (never inline), with
  // the day count spelled out (never truncated), at 375/820/1280 -- asserted via REAL geometry
  // (boundingBox comparison + scrollWidth<=clientWidth), never a class-name check: a class can be
  // present while flex-direction/order/wrapping regresses underneath it.
  const VIEWPORTS = [
    { width: 375, height: 812, label: '375 (mobile card)' },
    { width: 820, height: 1180, label: '820 (desktop grid)' },
    { width: 1280, height: 900, label: '1280 (desktop grid)' },
  ];

  async function assertBadgesStackedAndUnclipped(page, statusLabel) {
    const row = rowFor(page, reason);
    await expect(row).toBeVisible();
    const statusBadge = row.getByText(statusLabel, { exact: true });
    const unpaidBadge = row.getByText('ไม่รับค่าจ้าง 1 วัน', { exact: true });
    await expect(statusBadge).toBeVisible();
    await expect(unpaidBadge).toBeVisible();

    const statusBox = await statusBadge.boundingBox();
    const unpaidBox = await unpaidBadge.boundingBox();
    expect(statusBox, 'status badge must have real geometry').toBeTruthy();
    expect(unpaidBox, 'unpaid badge must have real geometry').toBeTruthy();

    // STACKED, not inline: the unpaid badge's top must sit at or below the status badge's own
    // bottom (allowing a hairline of anti-aliasing/rounding slack), and the two must be
    // left-aligned within a few px -- an inline layout would instead sit them side by side at
    // roughly the SAME y with different x.
    expect(unpaidBox.y, 'unpaid badge must sit BELOW the status badge, not beside it').toBeGreaterThanOrEqual(statusBox.y + statusBox.height - 2);
    expect(Math.abs(unpaidBox.x - statusBox.x), 'the two badges must be left-aligned (stacked column)').toBeLessThanOrEqual(4);

    // UNCLIPPED: the day count must be fully readable, not cut off by an overflow:clip ancestor.
    for (const locator of [statusBadge, unpaidBadge]) {
      const overflow = await locator.evaluate((el) => ({ scrollWidth: el.scrollWidth, clientWidth: el.clientWidth }));
      expect(overflow.scrollWidth, 'badge text must not be clipped').toBeLessThanOrEqual(overflow.clientWidth);
    }
  }

  test('case 8: the queue row stacks the status/unpaid badges at every width', async ({ page }) => {
    await loginAs(page, 'hr');
    for (const viewport of VIEWPORTS) {
      await page.setViewportSize(viewport);
      await page.goto('/leave?tab=review');
      await assertBadgesStackedAndUnclipped(page, 'อนุมัติแล้ว');
    }
  });

  test('case 8: the history (own) row stacks the status/unpaid badges at every width', async ({ page }) => {
    await loginAs(page, OWNER_ROLE);
    for (const viewport of VIEWPORTS) {
      await page.setViewportSize(viewport);
      await page.goto('/leave?tab=me');
      await assertBadgesStackedAndUnclipped(page, 'อนุมัติแล้ว');
    }
  });
});
