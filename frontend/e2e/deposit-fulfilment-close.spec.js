import { test, expect } from '@playwright/test';
import { loginAs, switchRole, spaGoto } from './helpers/auth.js';

// Stage K2 Phase 2 — deposit-fulfilment-close.spec.js: deposit-paid ->
// fulfilment (record + complete delivery) -> three-party close ->
// CLOSED_PAID.
//
// No seeded ticket sits at a *PricingRequest-chain-driven* deposit/
// fulfilment status to start from — mockPricingRequests is always empty at
// boot (src/api/mockApi.js `const mockPricingRequests = [];`) and every
// seeded ticket with a non-null paymentStatus/fulfillmentStatus (ids
// 6/11/12/13/14) is a *legacy*, pre-PCR-redesign row whose dual-track
// fields were hand-set in the seed, not reachable via the current
// PricingRequest gates (canCreateDepositNoticeFromQuotation/canConfirmOrder
// both require a PricingRequest at QUOTATION_ACCEPTED, and none exists for
// them). So this spec builds its own deal through the full PCR chain first
// (same proven steps as pcr-chain.spec.js) before it can reach the deposit/
// fulfilment/close panels at all.
//
// A real, deliberate mock behaviour worth calling out (not a bug): a new
// deal's legacy `ticket.status` starts and stays 'draft' — TicketDetailPage's
// three-party-close gates (confirmFinalPayment/confirmClose/verifyClose) all
// require `ticket.status === 'quotation_issued'`, which would make them
// permanently unreachable for a PCR-flow deal — EXCEPT confirmOrder
// deliberately bridges 'draft' -> 'quotation_issued' the first time it runs
// (mockApi.js confirmOrder, "The one deliberate bridge write... guarded
// FROM 'draft' only", mirroring TicketRepository
// .markQuotationIssuedForOrderConfirmation). This spec depends on that
// bridge firing — confirmOrder is called before any close-gated action.

test('deposit paid -> fulfilment -> three-party close -> CLOSED_PAID', async ({ page }) => {
  test.setTimeout(150_000);

  // ── sales: create a 2-unit deal + submit a catalog-backed PCR ───────
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
  // 2 units so fulfilment can exercise a genuine partial delivery (1 of 2)
  // before completing the remaining unit — a 1-unit deal can't split.
  await dealModal.locator('#item-0-qty').fill('2');
  await dealModal.getByRole('button', { name: 'บันทึกรายการ' }).click();
  await dealModal.getByRole('button', { name: 'กลับ' }).click();

  await dealModal.getByTestId('ticket-create-submit').click();
  await expect(dealModal).toHaveCount(0);
  await expect(page.getByText('แบบร่าง')).toBeVisible();
  const ticketPath = new URL(page.url()).pathname;

  await page.getByRole('button', { name: 'สร้างใบขอราคา' }).click();
  const pcrModal = page.getByRole('dialog', { name: 'สร้างใบขอราคา' });
  await expect(pcrModal).toBeVisible();
  await pcrModal.getByPlaceholder('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ').fill('คุณทดสอบ ผู้รับ');
  // Same catalog pick as pcr-chain.spec.js (REFIN / "Corner", priceId 5,
  // PER_PIECE — no conversion factor needed, and submit() 422s a non-catalog
  // item — see that spec's own header note for the full explanation).
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
  await page.getByTestId('pcr-ceo-start-review').click();
  await expect(page.getByPlaceholder('ราคาขั้นต่ำ')).toBeVisible();
  await page.getByPlaceholder('ราคาขั้นต่ำ').fill('10');
  await page.getByTestId('pcr-ceo-save-decision-items').click();
  await expect(page.getByTestId('pcr-ceo-approve')).toBeEnabled();
  await page.getByTestId('pcr-ceo-approve').click();
  await page.getByRole('dialog', { name: 'อนุมัติราคาขาย' })
    .getByRole('button', { name: 'อนุมัติ', exact: true }).click();
  await expect(page.getByText('อนุมัติราคาขายแล้ว').first()).toBeVisible();

  // ── sales: issue + accept the customer quotation, confirm the order ─
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

  await expect(quotationPanel.getByTestId('deal-quotation-confirm-order')).toBeVisible();
  await quotationPanel.getByTestId('deal-quotation-confirm-order').click();
  // This is the "one deliberate bridge write" (see file header note):
  // ticket.status flips 'draft' -> 'quotation_issued' here, unlocking every
  // close-gated action for the rest of this spec.
  await expect(page.getByText('แบบร่าง')).toHaveCount(0);

  // ── sales: create + issue the deposit notice ─────────────────────────
  const depositPanel = page.getByTestId('deal-deposit-panel');
  await expect(depositPanel.getByTestId('deal-deposit-create-notice')).toBeVisible();
  await depositPanel.getByTestId('deal-deposit-create-notice').click();
  // createNoticeMutation's onSuccess navigates to /tickets/:id/deposit (the
  // full deposit-notice editor) — its own "กลับ" button (DepositNoticePage's
  // onBack) is the real in-app way back to the deal page; a raw spaGoto
  // pushState didn't take (this page likely holds router-level state that a
  // bare popstate doesn't unwind). DealDepositPanel reads the same notice
  // via api.depositNotices.listByTicket and now shows the issue action.
  await expect(page.getByRole('heading', { name: 'ใบแจ้งยอดเงินรับมัดจำ' })).toBeVisible();
  await page.getByRole('button', { name: 'กลับ' }).click();
  await expect(depositPanel.getByTestId('deal-deposit-issue-notice')).toBeVisible();
  await depositPanel.getByTestId('deal-deposit-issue-notice').click();
  await expect(depositPanel.getByText('ออกแล้ว')).toBeVisible();

  // ── account: confirm the deposit paid ────────────────────────────────
  await switchRole(page, 'account');
  await spaGoto(page, ticketPath);
  const depositPanelAccount = page.getByTestId('deal-deposit-panel');
  await expect(depositPanelAccount.getByTestId('deal-deposit-confirm')).toBeVisible();
  await depositPanelAccount.getByTestId('deal-deposit-confirm').click();
  await expect(depositPanelAccount.getByText('รับมัดจำแล้ว')).toBeVisible();

  // ── import: IR chain -> partial delivery -> complete delivery ───────
  await switchRole(page, 'import');
  await spaGoto(page, ticketPath);
  const fulfilmentPanel = page.getByTestId('deal-fulfilment-panel');
  await expect(fulfilmentPanel.getByTestId('deal-fulfilment-issue-ir')).toBeVisible();
  await fulfilmentPanel.getByTestId('deal-fulfilment-issue-ir').click();
  await expect(fulfilmentPanel.getByTestId('deal-fulfilment-mark-ir-sent')).toBeVisible();
  await fulfilmentPanel.getByTestId('deal-fulfilment-mark-ir-sent').click();
  await expect(fulfilmentPanel.getByTestId('deal-fulfilment-mark-shipping')).toBeVisible();
  await fulfilmentPanel.getByTestId('deal-fulfilment-mark-shipping').click();
  await expect(fulfilmentPanel.getByTestId('deal-fulfilment-mark-goods-received')).toBeVisible();
  await fulfilmentPanel.getByTestId('deal-fulfilment-mark-goods-received').click();

  // Record a PARTIAL delivery first (1 of the 2 units) — a genuine partial,
  // not a shortcut straight to complete.
  await expect(fulfilmentPanel.getByTestId('deal-fulfilment-record-delivery')).toBeVisible();
  await fulfilmentPanel.getByTestId('deal-fulfilment-record-delivery').click();
  const deliveryModal = page.getByRole('dialog', { name: 'บันทึกการส่งสินค้า' });
  await expect(deliveryModal).toBeVisible();
  await deliveryModal.getByRole('spinbutton').first().fill('1');
  await deliveryModal.getByTestId('deal-fulfilment-record-delivery-submit').click();
  await expect(deliveryModal).toHaveCount(0);
  await expect(fulfilmentPanel.getByText('1 / 2').first()).toBeVisible();

  // Complete the remaining unit.
  await expect(fulfilmentPanel.getByTestId('deal-fulfilment-complete')).toBeVisible();
  await fulfilmentPanel.getByTestId('deal-fulfilment-complete').click();
  await expect(fulfilmentPanel.getByText('2 / 2').first()).toBeVisible();

  // ── account: confirm final payment, attach the invoice, confirm close ─
  await switchRole(page, 'account');
  await spaGoto(page, ticketPath);
  await expect(page.getByTestId('ticket-detail-confirm-final')).toBeVisible();
  await page.getByTestId('ticket-detail-confirm-final').click();
  await page.getByRole('dialog', { name: 'ยืนยันการรับชำระครบถ้วน' })
    .getByRole('button', { name: 'ยืนยันชำระครบ' }).click();
  await expect(page.getByTestId('ticket-detail-confirm-final')).toHaveCount(0);

  // แนบใบกำกับภาษี is a hard precondition for CONFIRM_CLOSE
  // (requireClosePrerequisites' hasInvoiceAttachment check).
  await page.locator('#ticket-invoice-file').setInputFiles({
    name: 'invoice.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('%PDF-1.4 e2e deposit-fulfilment-close test invoice'),
  });
  await expect(page.getByTestId('ticket-detail-confirm-close')).toBeVisible();
  await page.getByTestId('ticket-detail-confirm-close').click();
  await expect(page.getByTestId('ticket-detail-confirm-close')).toHaveCount(0);

  // ── ceo: verify and close ────────────────────────────────────────────
  await switchRole(page, 'ceo');
  await spaGoto(page, ticketPath);
  await expect(page.getByTestId('ticket-detail-verify-close')).toBeVisible();
  await page.getByTestId('ticket-detail-verify-close').click();
  await expect(page.getByText('ปิดแล้ว').first()).toBeVisible();
});
