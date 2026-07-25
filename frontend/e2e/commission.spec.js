import { test, expect } from '@playwright/test';
import { loginAs, switchRole, spaGoto } from './helpers/auth.js';

// Stage K2 Phase 2 — commission.spec.js: manual commission create (sales_manager)
// -> CEO two-step approve -> view.
//
// There is no seeded commission of any kind (mockApi.js's `db.commissions`
// starts as an empty array with no seed block, unlike leave/overtime) — this
// spec creates its own record via the manual-create form before it can
// approve anything.
//
// sales_manager and ceo can both manually create a commission
// (ROLE_PERMISSIONS.canCreateManualCommission — src/api/routes.js). A record
// created by sales_manager lands at MANAGER_APPROVED (needs only the CEO's
// sign-off, not a manager review first — CommissionService#createManualCommission),
// so this spec still exercises a real CEO-side approval step even though the
// manager->CEO SUBMITTED->MANAGER_APPROVED transition itself isn't hit by a
// manual entry from sales_manager.
//
// Commission LIST/approve authorization (canCreateManualCommission, review
// gates) is enforced by the mock only — per CLAUDE.md this is NOT a backend
// authz proof; this spec drives the UI only.

test('sales_manager creates a manual commission, ceo approves it', async ({ page }) => {
  test.setTimeout(60_000);

  // A distinctive reason string doubles as the search key to re-find this
  // exact row later (DataTable's searchAccessor includes manualReason).
  const reason = `e2e-commission-${Date.now()}`;

  await loginAs(page, 'sales_manager');
  await spaGoto(page, '/commissions');

  await page.getByRole('button', { name: 'เพิ่มค่าคอมด้วยตนเอง' }).click();
  // ประเภท defaults to ADJUSTMENT — leave it. Employee id 9 is a real seeded
  // employee (employee@glr.co.th's linked row — demoData.js).
  await page.getByLabel(/รหัสพนักงาน \(Employee ID\)/).fill('9');
  await page.getByLabel(/จำนวนเงิน \(บาท\)/).fill('1000');
  await page.getByLabel('เหตุผล *').fill(reason);
  await page.getByRole('button', { name: 'บันทึกค่าคอม' }).click();

  await expect(page.getByRole('button', { name: 'เพิ่มค่าคอมด้วยตนเอง' })).toBeVisible();
  await page.getByPlaceholder(/ค้นหา/).fill(reason);
  await expect(page.getByText(reason).first()).toBeVisible();
  // Created by sales_manager -> MANAGER_APPROVED (only the CEO still needs
  // to sign off), per commissionStatusLabel.
  await expect(page.getByText('รอ CEO')).toBeVisible();

  await switchRole(page, 'ceo');
  await spaGoto(page, '/commissions');
  await page.getByPlaceholder(/ค้นหา/).fill(reason);
  await expect(page.getByText(reason).first()).toBeVisible();

  await page.getByTestId('commission-approve').click();
  await expect(page.getByRole('dialog', { name: 'ยืนยันการอนุมัติค่าคอม' })).toBeVisible();
  await page.getByRole('dialog', { name: 'ยืนยันการอนุมัติค่าคอม' })
    .getByRole('button', { name: 'อนุมัติ' }).click();

  await expect(page.getByText(reason).first()).toBeVisible();
  await expect(page.getByText('อนุมัติแล้ว').first()).toBeVisible();
});
