import { test, expect } from '@playwright/test';
import { loginAs, spaGoto } from './helpers/auth.js';

// Stage K2 Phase 2 — deal-creation.spec.js: sales walks the 6-section
// TicketCreateModal (ลูกค้า / โครงการ / ผู้ติดต่อ & ช่องทางดีล / รายการสินค้า /
// รายละเอียดดีล / ตรวจสอบ & บันทึก) end to end and lands on a fresh DRAFT deal.
//
// Only `sales` may create tickets (ROLE_PERMISSIONS.canCreateTickets —
// src/api/routes.js) — this is asserted implicitly by driving the create
// button through the sales persona only; no other role is exercised here
// (rbac.spec.js already covers /tickets route gating for every role).
//
// Seeded fixture data used (frontend/src/api/mockApi.js's mockCustomers/
// mockProjects, lines ~452-476): customer id 1 "บริษัท ก้าวหน้า คอนสตรัคชั่น
// จำกัด" with project id 1 "โครงการ Central Ladprao ชั้น B1".

test('sales creates a deal through the 6-section TicketCreateModal', async ({ page }) => {
  test.setTimeout(60_000);
  await loginAs(page, 'sales');
  await spaGoto(page, '/tickets');

  await page.getByRole('button', { name: 'สร้างดีลใหม่' }).click();
  const modal = page.getByTestId('ticket-create-modal');
  await expect(modal).toBeVisible();

  // ── section 1: ลูกค้า ──────────────────────────────────────────────
  await modal.getByRole('button', { name: 'ลูกค้า', exact: false }).first().click();
  const customerInput = modal.locator('#customer-select');
  await customerInput.fill('ก้าวหน้า');
  await expect(modal.getByText('บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด')).toBeVisible();
  await modal.getByText('บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด').click();
  await modal.getByRole('button', { name: 'กลับ' }).click();

  // ── section 2: โครงการ ────────────────────────────────────────────
  await modal.getByRole('button', { name: 'โครงการ', exact: false }).first().click();
  await modal.getByRole('button', { name: 'โครงการ Central Ladprao ชั้น B1' }).click();
  await modal.getByRole('button', { name: 'กลับ' }).click();

  // ── section 3: ผู้ติดต่อ & ช่องทางดีล ──────────────────────────────
  await modal.getByRole('button', { name: 'ผู้ติดต่อ & ช่องทางดีล', exact: false }).first().click();
  await modal.getByRole('radio', { name: 'เจ้าของตรง' }).click();
  await modal.getByRole('button', { name: 'กลับ' }).click();

  // ── section 4: รายการสินค้า (optional since V50 — add one anyway to
  //    exercise the full 6-section flow end to end) ───────────────────
  await modal.getByRole('button', { name: 'รายการสินค้า', exact: false }).first().click();
  // Empty-state items view offers "ค้นหาสินค้า" / "เพิ่มสินค้าเอง (custom)" —
  // both call addItem(); the "เพิ่มรายการสินค้า" button only appears once at
  // least one item row already exists (for adding a 2nd+ row).
  await modal.getByRole('button', { name: 'เพิ่มสินค้าเอง (custom)' }).click();
  await modal.locator('#item-0-brand').fill('SCG');
  await modal.locator('#item-0-model').fill('Metro White');
  await modal.locator('#item-0-color').fill('ขาว');
  await modal.locator('#item-0-texture').fill('ด้าน');
  await modal.locator('#item-0-size').fill('60x60');
  await modal.getByRole('button', { name: 'บันทึกรายการ' }).click();
  // "บันทึกรายการ" closes the item editor back to the (now non-empty) items
  // list view, not the hub — one more "กลับ" is needed to reach the hub.
  await modal.getByRole('button', { name: 'กลับ' }).click();

  // ── section 5: รายละเอียดดีล ───────────────────────────────────────
  await modal.getByRole('button', { name: 'รายละเอียดดีล', exact: false }).first().click();
  // Placeholder falls back to the selected customer's name once a customer
  // is picked (renderDetailsView: `placeholder={selectedCustomer?.name ||
  // 'ชื่อดีล'}`) — target by the wrapping label's accessible name instead.
  await modal.getByLabel(/^ชื่อดีล/).fill('E2E deal-creation smoke');
  await modal.getByRole('button', { name: 'เสร็จสิ้น' }).click();

  // ── section 6: ตรวจสอบ & บันทึก ────────────────────────────────────
  await modal.getByRole('button', { name: 'ตรวจสอบ & บันทึก', exact: false }).first().click();
  await expect(modal.getByText('ยังกรอกไม่ครบ')).toHaveCount(0);

  await modal.getByTestId('ticket-create-submit').click();

  // Modal closes and the app navigates straight to the new deal's page
  // (TicketListPage.handleCreate -> navigate(`/tickets/${id}`)).
  await expect(modal).toHaveCount(0);
  await expect(page.getByText(/^PR-2026-\d+$/).first()).toBeVisible();
  // A brand-new deal's legacy ticket.status is 'draft' — shown on the
  // detail page's own StatusBadge (ticketStatusLabel('draft') = 'แบบร่าง').
  await expect(page.getByText('แบบร่าง')).toBeVisible();

  // Confirm the DRAFT deal also shows up back on the list (TicketListPage
  // hides the operational 'แบบร่าง' text for draft rows — see
  // DealStageCell's showOperational comment — but the row itself, found by
  // its code via the searchable DataTable, must be present).
  const codeText = await page.getByText(/^PR-2026-\d+$/).first().textContent();
  await spaGoto(page, '/tickets');
  await page.getByPlaceholder('ค้นหาเลขที่ / บริษัท / โครงการ / ผู้ดูแล').fill(codeText.trim());
  await expect(page.getByText(codeText.trim())).toBeVisible();
});
