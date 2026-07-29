import { test, expect } from '@playwright/test';
import { loginAs, switchRole, spaGoto } from './helpers/auth.js';

// Stage K2 Phase 2 — pcr-chain.spec.js: one session, role-switched (no hard
// reload — see helpers/auth.js's module doc on why the mock db only
// survives in-session role switches), driving the full PricingRequest (PCR)
// chain end to end:
//
//   sales: create deal + pricing request (submit straight to Import)
//   import: pickup -> factory quote (draft/raw-price/receive/ready) -> costing (draft/recalc/submit to CEO)
//   ceo: start review -> set per-item minimum selling price -> approve
//   sales: create/issue customer quotation -> record ACCEPTED outcome
//
// This exercises the real PricingRequest status machine in
// src/api/mockApi.js's pricingRequests namespace (DRAFT -> SUBMITTED ->
// IMPORT_REVIEWING -> COSTING_IN_PROGRESS -> READY_FOR_CEO_REVIEW ->
// CEO_REVIEWING -> APPROVED_FOR_QUOTATION -> QUOTATION_ISSUED ->
// QUOTATION_ACCEPTED), not just UI navigation.
//
// Seeded fixture data used: customer id 1 "บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด"
// / project id 1 "โครงการ Central Ladprao ชั้น B1" (mockApi.js mockCustomers/
// mockProjects), and price-catalog product "Corner" (priceId 5, factory
// "REFIN", priceUnit 'per_piece' — deliberately PER_PIECE, not PER_SQM/BOX/
// LINEAR_M, so the factory-quote response needs no extra conversion factor
// (sqmPerUnit/piecesPerBox/linearMPerUnit) to cost out; factory REFIN has an
// ACTIVE price-import version, which submit()'s catalog gate requires).
//
// Two real findings from writing this spec against the mock (not gaps —
// documenting per the "don't paper over it" rule):
//
// 1. PricingRequest submit() 422s a custom (non-catalog) item with
//    "ต้องเลือกสินค้าจาก Price Catalog ที่ active ก่อนส่งคำขอราคา"
//    (mockApi.js's submitPricingRequestCatalogGate). Intentional (Finding A,
//    financial-integrity review) and documented in the mock's own comment —
//    this spec drives the PCR item through PricingRequestCreateModal's
//    catalog picker rather than typing a free-text item, matching what a
//    real user must do.
//
// 2. A genuine mock bug, FIXED alongside this spec (src/api/mockApi.js's
//    generateFactoryEmailDrafts, ~line 5639): a new factory-quote item's
//    quotedUnit/unitBasis defaulted to item.requestedUnit (a display LABEL,
//    e.g. "ตร.ม.") instead of item.requestedUnitBasis (the canonical code,
//    e.g. 'PER_SQM', that both the response form's <select> and
//    recalculateCosting's cost math actually key on). Any import officer who
//    filled in only the raw price without re-touching the (seemingly
//    already-selected) unit-basis dropdown would submit an unrecognised
//    code, and recalculateCosting would 422 with "Unsupported factory quote
//    unit basis '<label>'" — silently blocking Submit to CEO with no
//    visible cause. This is exactly the failure this spec hit on its first
//    real run; the fix (seed both fields from requestedUnitBasis) is a
//    one-line, behavior-neutral data-wiring correction, not a business-logic
//    change.
//
// 3. A genuine, 100%-reproducible mock bug, FIXED alongside this spec
//    (src/api/mockApi.js's startPricingDecision, ~line 6028): the `decision`
//    object literal's own `items: costing.items.map(...)` callback
//    referenced `decision?.id` — but that map runs synchronously while
//    `decision` itself is still being constructed, so reading `decision`
//    from inside its own initializer is a temporal-dead-zone violation. It
//    threw "Cannot access 'decision' before initialization" on every single
//    call, unconditionally breaking the CEO's "เริ่มพิจารณาราคาขาย" button —
//    every PCR, every time, for any user, not just this test. The fix drops
//    the premature self-reference (left null); the very next line already
//    fixes item.pricingDecisionId up correctly once decision.id exists
//    (`decision.items.forEach(...)`, unmodified) — that forEach's own
//    comment ("Fix up the self-reference now that decision.id is known")
//    shows this was the original intent, just broken by evaluation order.

test('sales -> import -> ceo -> sales walks a PCR from draft to quotation-accepted', async ({ page }) => {
  test.setTimeout(120_000);

  // ── sales: create the deal ──────────────────────────────────────────
  await loginAs(page, 'sales');
  await spaGoto(page, '/tickets');

  await page.getByRole('button', { name: 'สร้างดีลใหม่' }).click();
  const dealModal = page.getByTestId('ticket-create-modal');
  await expect(dealModal).toBeVisible();

  await dealModal.getByRole('button', { name: 'ลูกค้า', exact: false }).first().click();
  await dealModal.locator('#customer-select').fill('ก้าวหน้า');
  await dealModal.getByText('บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด').click();
  await dealModal.getByRole('button', { name: 'กลับ' }).click();

  await dealModal.getByRole('button', { name: 'โครงการ', exact: false }).first().click();
  await dealModal.getByRole('button', { name: 'โครงการ Central Ladprao ชั้น B1' }).click();
  await dealModal.getByRole('button', { name: 'กลับ' }).click();

  await dealModal.getByRole('button', { name: 'รายการสินค้า', exact: false }).first().click();
  await dealModal.getByRole('button', { name: 'เพิ่มสินค้าเอง (custom)' }).click();
  await dealModal.locator('#item-0-brand').fill('SCG');
  await dealModal.locator('#item-0-model').fill('Metro White');
  await dealModal.locator('#item-0-color').fill('ขาว');
  await dealModal.locator('#item-0-texture').fill('ด้าน');
  await dealModal.locator('#item-0-size').fill('60x60');
  await dealModal.getByRole('button', { name: 'บันทึกรายการ' }).click();
  // "บันทึกรายการ" closes the item editor back to the (now non-empty) items
  // list view, not the hub — one more "กลับ" is needed to reach the hub
  // where the submit button (in the modal footer) is actually clickable.
  await dealModal.getByRole('button', { name: 'กลับ' }).click();

  await dealModal.getByTestId('ticket-create-submit').click();
  await expect(dealModal).toHaveCount(0);
  await expect(page.getByText('แบบร่าง')).toBeVisible();
  const ticketPath = new URL(page.url()).pathname;

  // ── sales: create + submit the pricing request straight to Import ──
  await page.getByRole('button', { name: 'สร้างใบขอราคา' }).click();
  const pcrModal = page.getByRole('dialog', { name: 'สร้างใบขอราคา' });
  await expect(pcrModal).toBeVisible();
  await pcrModal.getByPlaceholder('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ').fill('คุณทดสอบ ผู้รับ');
  // Pick a real catalog product (see the file-header note): submit() 422s
  // an item with no resolved catalog snapshot. "Corner" search narrows to
  // exactly priceId 5 (REFIN / per_piece), whose factory has an ACTIVE
  // price-import version.
  await pcrModal.getByLabel('ค้นหา Catalog').fill('Corner');
  await pcrModal.getByRole('button', { name: /REFIN/ }).click();
  await expect(pcrModal.getByText('Catalog #5')).toBeVisible();
  await pcrModal.getByRole('button', { name: 'ส่งให้ Import' }).click();
  await expect(pcrModal).toHaveCount(0);
  await expect(page.getByText('รอ Import รับเรื่อง').first()).toBeVisible();

  // ── import: pickup -> factory quote -> costing ──────────────────────
  await switchRole(page, 'import');
  await spaGoto(page, '/pricing-requests');
  const pcrLink = page.getByRole('link', { name: /^PCR-2026-\d+$/ });
  await expect(pcrLink).toBeVisible();
  const pcrHref = await pcrLink.getAttribute('href');
  await page.getByTestId('pcr-queue-pickup').click();
  // Pickup moves the request out of the default SUBMITTED filter, so the
  // row disappears from this queue view — navigate straight to its detail
  // page (captured above) rather than re-querying the list.
  await expect(page.getByTestId('pcr-queue-pickup')).toHaveCount(0);
  await spaGoto(page, pcrHref);

  await expect(page.getByText('Import ตรวจคำขอราคา').first()).toBeVisible();
  await page.getByTestId('pcr-generate-drafts').click();
  await expect(page.getByText('REFIN').first()).toBeVisible();
  await page.getByPlaceholder('Raw price').fill('100');
  await page.getByTestId('pcr-quote-save-response').click();
  await expect(page.getByTestId('pcr-quote-ready')).toBeVisible();
  await page.getByTestId('pcr-quote-ready').click();

  await page.getByTestId('pcr-costing-create').click();
  await expect(page.getByTestId('pcr-costing-recalculate')).toBeVisible();
  await page.getByTestId('pcr-costing-recalculate').click();
  await expect(page.getByTestId('pcr-costing-submit')).toBeEnabled();
  await page.getByTestId('pcr-costing-submit').click();
  await page.getByRole('dialog', { name: 'Submit costing to CEO' })
    .getByRole('button', { name: 'Submit to CEO' }).click();
  await expect(page.getByText('ส่งให้ CEO ตรวจแล้ว').first()).toBeVisible();

  // ── ceo: start review -> set minimum selling price -> approve ───────
  await switchRole(page, 'ceo');
  await spaGoto(page, pcrHref);
  await expect(page.getByText('ส่งให้ CEO ตรวจแล้ว').first()).toBeVisible();
  // Default margin field is pre-filled ('0.20') — starting review alone
  // satisfies the per-item margin half of the approve gate; only the
  // minimum-selling-price field still needs a value.
  await page.getByTestId('pcr-ceo-start-review').click();
  await expect(page.getByPlaceholder('ราคาขั้นต่ำ')).toBeVisible();
  await page.getByPlaceholder('ราคาขั้นต่ำ').fill('10');
  await page.getByTestId('pcr-ceo-save-decision-items').click();
  await expect(page.getByTestId('pcr-ceo-approve')).toBeEnabled();
  await page.getByTestId('pcr-ceo-approve').click();
  await page.getByRole('dialog', { name: 'อนุมัติราคาขาย' })
    .getByRole('button', { name: 'อนุมัติ', exact: true }).click();
  await expect(page.getByText('อนุมัติราคาขายแล้ว').first()).toBeVisible();

  // ── sales: create + issue the customer quotation, record ACCEPTED ───
  await switchRole(page, 'sales');
  await spaGoto(page, ticketPath);
  const quotationPanel = page.getByTestId('deal-quotation-panel');
  await expect(quotationPanel).toBeVisible();
  await quotationPanel.getByTestId('deal-quotation-create').click();
  await expect(quotationPanel.getByTestId('deal-quotation-issue')).toBeVisible();
  await quotationPanel.getByTestId('deal-quotation-issue').click();
  await expect(quotationPanel.getByTestId('deal-quotation-accept')).toBeVisible();
  await quotationPanel.getByTestId('deal-quotation-accept').click();
  await expect(page.getByText('ลูกค้ายอมรับใบเสนอราคาแล้ว').first()).toBeVisible();
});
