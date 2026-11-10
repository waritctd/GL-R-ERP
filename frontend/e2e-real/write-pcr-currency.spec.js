import { test, expect } from '@playwright/test';
import { apiSessionsFor, apiWrite, disposeSessions } from './helpers/api.js';
import { createDeal, cancelDeal, submitPricingRequest, pickupPricingRequest, generateFactoryEmailDrafts } from './helpers/sales.js';

test('Import changes Sales currency, sees live baht, and persists the supplier response', async ({ browser }, testInfo) => {
  test.setTimeout(120_000);
  process.env.E2E_RUN_ID ??= `E2E-PCR-CURRENCY-${Date.now()}`;
  const sessions = await apiSessionsFor(['sales', 'import']);
  let deal;
  let context;
  try {
    deal = await createDeal(sessions, { caseName: 'PCR currency', entryChannel: 'DESIGNER_LED' });
    const factoryResponse = await sessions.import.get('/api/factory-configs');
    const { factories } = await factoryResponse.json();
    const factory = factories.find((f) => f.factoryName.includes('Panaria')) ?? factories[0];
    const created = await apiWrite(sessions.sales, 'post', `/api/tickets/${deal.id}/pricing-requests`, {
      recipientType: 'DESIGNER', recipientLabel: 'Currency regression', targetCurrency: 'THB', clientRequestId: crypto.randomUUID(),
      items: [{
        brand: 'Panaria', model: 'Stone Collection', color: 'Ivory', texture: 'Matt', size: '60x120', factory: factory.factoryName,
        thicknessMm: 9, sqmPerPiece: 0.72, quantityMode: 'PIECES', piecesInput: 100, wastageMode: 'NONE', piecesPerBox: 2,
        roundToFullBox: true, originCountry: 'อิตาลี', leadTimeMinDays: 75, leadTimeMaxDays: 90, quantityType: 'CONFIRMED',
        requestedCurrency: 'EUR', requestedPriceUnitBasis: 'PER_SQM', productCode: 'PCR-CURRENCY-01',
      }],
    });
    expect(created.status(), await created.text()).toBe(201);
    const { pricingRequest } = await created.json();
    const id = pricingRequest.summary.id;
    await submitPricingRequest(sessions, id);
    await pickupPricingRequest(sessions, id);
    const [quote] = await generateFactoryEmailDrafts(sessions, id);
    const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bangkok' }).format(new Date());
    const contacted = await apiWrite(sessions.import, 'post', `/api/factory-quotes/${quote.id}/contacted`, { contactedOn: today });
    expect(contacted.status()).toBe(200);
    context = await browser.newContext({ baseURL: testInfo.project.use.baseURL, storageState: await sessions.import.storageState(), viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    await page.goto(`/pricing-requests/${id}`);
    const currency = page.getByRole('combobox', { name: 'สกุลเงินที่โรงงานเสนอ' });
    await expect(currency).toHaveValue('EUR');
    await page.getByRole('textbox', { name: /^ราคาที่เสนอ/ }).fill('20');
    await currency.selectOption('USD');
    const { fxRates } = await (await sessions.import.get('/api/fx-rates')).json();
    const usd = fxRates.find((fx) => fx.currency === 'USD').rateToThb;
    const baht = page.getByTestId(`pcr-quote-thb-${quote.id}-${pricingRequest.items[0].id}`);
    await expect(baht).toContainText((20 * usd).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }));
    await expect(page.getByText('ฝ่ายขายขอ: EUR')).toBeVisible();
    for (const width of [320, 375, 768, 1024, 1440, 1920]) {
      await page.setViewportSize({ width, height: 1000 });
      await expect(currency).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), `overflow at ${width}`).toBe(true);
      await page.screenshot({ path: testInfo.outputPath(`currency-${width}.png`), fullPage: true });
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.getByRole('button', { name: 'ยืนยันราคาเสนอ', exact: true }).click();
    await expect(page.getByText('ยืนยันราคาเสนอแล้ว', { exact: true }).first()).toBeVisible();
    await page.reload();
    await expect(currency).toHaveValue('USD');
    await expect(page.getByRole('textbox', { name: /^ราคาที่เสนอ/ })).toHaveValue('20');
    const { items: quotes } = await (await sessions.import.get(`/api/pricing-requests/${id}/factory-quotes`)).json();
    const saved = quotes.find((q) => q.current);
    expect(saved.status).toBe('READY_FOR_COSTING');
    expect(saved.defaultCurrency).toBe('USD');
    expect(saved.items[0].currency).toBe('USD');
    const { pricingRequest: original } = await (await sessions.sales.get(`/api/pricing-requests/${id}`)).json();
    expect(original.items[0].requestedCurrency).toBe('EUR');
    // A fresh edit can be cancelled, restoring the saved supplier currency and price.
    await currency.selectOption('THB');
    await expect(baht).toContainText('20.00');
    await page.getByRole('button', { name: 'ยกเลิก', exact: true }).click();
    await expect(currency).toHaveValue('USD');
  } finally {
    await context?.close();
    if (deal) await cancelDeal(sessions, deal.id).catch(() => {});
    await disposeSessions(sessions);
  }
});
