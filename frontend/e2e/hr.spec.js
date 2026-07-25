import { test, expect } from '@playwright/test';
import { loginAs, loginWithCredentials, switchRole, spaGoto, logout } from './helpers/auth.js';

// Stage K2 Phase 2 — hr.spec.js: OT create -> two-step approve
// (manager -> CEO), leave create (auto-decision), and a render smoke check
// for attendance/dashboard views.
//
// Leave note (correcting the task brief's assumption — verified directly
// against the current mockApi.js, not the stale runbook note it was
// quoting): `leave.approve`/`leave.reject` ARE implemented (mockApi.js
// ~3608/3623), but they are UNREACHABLE via the UI today. leave.create()
// (mockApi.js ~3549-3606) always resolves synchronously to either
// 'APPROVED' or 'AUTO_REJECTED' — there is no code path that ever produces
// a 'SUBMITTED' leave request, and both seeded leave requests are already
// APPROVED. This is a deliberate redesign (commit "feat: auto approve leave
// with upload" replaced manager review with a same-day auto-decision
// engine), not a bug — so this spec exercises the real, reachable path
// (create -> auto-decision) instead of the unreachable manual approve/
// reject UI, and documents the divergence here rather than silently
// asserting something that cannot happen.

// A guaranteed weekday at least 8 days out — VACATION requests need >= 7
// days' notice (leave.create's own gate) to avoid AUTO_REJECTED, and a
// same-day start/end must land on a weekday for workingDaysBetween to
// count 1 day (a weekend start/end would count 0 and behave oddly).
function nextWeekdayAtLeast(daysAhead) {
  const d = new Date();
  d.setDate(d.getDate() + daysAhead);
  while (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() + 1);
  // Format from the SAME local getDate()/getMonth()/getFullYear() used for the
  // weekday check above — d.toISOString() converts to UTC first, which can
  // silently roll onto a different calendar date (and an unchecked weekday)
  // depending on the local timezone offset. mockApi.js's own workingDaysBetween
  // parses this string as local midnight (`${date}T00:00:00`, no "Z"), so the
  // string returned here must name the exact calendar date already verified.
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

test.describe('HR — overtime, leave, attendance', () => {
  test('overtime: employee creates a request, manager then CEO approve it', async ({ page }) => {
    test.setTimeout(60_000);
    const reason = `e2e-ot-${Date.now()}`;

    await loginAs(page, 'employee');
    await spaGoto(page, '/employee-requests?tab=ot');

    const today = new Date().toISOString().slice(0, 10);
    await page.locator('#ot-work-date').fill(today);
    await page.locator('#ot-day-type').selectOption('WORKDAY');
    await page.locator('#ot-planned-start').fill(`${today}T19:00`);
    await page.locator('#ot-planned-end').fill(`${today}T21:00`);
    await page.locator('#ot-reason').fill(reason);
    await page.getByRole('button', { name: 'ส่งคำขอ' }).click();

    const row = page.locator('.data-row', { hasText: reason });
    await expect(row).toBeVisible();
    await expect(row.getByText('รอผู้จัดการ')).toBeVisible();

    // warehouse.manager@glr.co.th (employee 9's direct manager, per
    // demoData.js) has no quick-login button — only hr/employee/sales/
    // sales_manager/import/account/ceo do — so this step goes through the
    // credential form.
    await logout(page);
    await loginWithCredentials(page, 'warehouse.manager@glr.co.th', 'demo1234');
    await spaGoto(page, '/employee-requests?tab=ot');
    const managerRow = page.locator('.data-row', { hasText: reason });
    await expect(managerRow).toBeVisible();
    await managerRow.getByRole('button', { name: 'ผู้จัดการอนุมัติ' }).click();
    await expect(page.getByRole('dialog', { name: 'ยืนยันการอนุมัติ OT' })).toBeVisible();
    await page.getByRole('dialog', { name: 'ยืนยันการอนุมัติ OT' })
      .getByRole('button', { name: 'อนุมัติ' }).click();
    await expect(managerRow.getByText('รอ CEO')).toBeVisible();

    await switchRole(page, 'ceo');
    await spaGoto(page, '/employee-requests?tab=ot');
    const ceoRow = page.locator('.data-row', { hasText: reason });
    await expect(ceoRow).toBeVisible();
    await ceoRow.getByRole('button', { name: 'CEO อนุมัติ' }).click();
    await expect(page.getByRole('dialog', { name: 'ยืนยันการอนุมัติ OT' })).toBeVisible();
    await page.getByRole('dialog', { name: 'ยืนยันการอนุมัติ OT' })
      .getByRole('button', { name: 'อนุมัติ' }).click();
    await expect(ceoRow.getByText('อนุมัติแล้ว')).toBeVisible();
  });

  test('leave: employee submits a request and it is auto-decided', async ({ page }) => {
    test.setTimeout(30_000);
    await loginAs(page, 'employee');
    await spaGoto(page, '/leave');

    const reason = `e2e-leave-${Date.now()}`;
    const start = nextWeekdayAtLeast(10);
    await page.locator('#leave-type-code').selectOption('VACATION');
    await page.locator('#leave-start-date').fill(start);
    await page.locator('#leave-end-date').fill(start);
    await page.locator('#leave-reason').fill(reason);
    await page.getByRole('button', { name: 'ส่งคำขอ' }).click();

    // leave.create() resolves synchronously to APPROVED or AUTO_REJECTED —
    // never SUBMITTED (see file header note) — so the success toast IS the
    // approval, not just an acknowledgement.
    await expect(page.getByText('ส่งคำขอลาและอนุมัติอัตโนมัติแล้ว')).toBeVisible();

    // The request list's default filter is [start of this month, today] —
    // it deliberately excludes future-dated requests. Our VACATION request
    // must start >= 7 days out (see nextWeekdayAtLeast's own comment), so it
    // falls outside that default window; widen "ถึงวันที่" to see it.
    await page.getByLabel('ถึงวันที่').fill(start);
    await page.getByRole('button', { name: 'ค้นหา' }).click();
    const row = page.locator('.data-row', { hasText: reason });
    await expect(row).toBeVisible();
    await expect(row.getByText('อนุมัติแล้ว')).toBeVisible();
  });

  test('attendance view renders for hr and employee without error', async ({ page }) => {
    test.setTimeout(30_000);
    await loginAs(page, 'hr');
    await spaGoto(page, '/attendance');
    await expect(page.getByRole('heading', { level: 1, name: 'เวลาทำงาน' })).toBeVisible();

    await switchRole(page, 'employee');
    await spaGoto(page, '/attendance');
    await expect(page.getByRole('heading', { level: 1, name: 'เวลาทำงาน' })).toBeVisible();
  });
});
