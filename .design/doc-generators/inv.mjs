import { open, go, shoot, head } from './lib.mjs';
const out = process.argv[2];
const { browser, page } = await open('ฝ่ายขาย');
for (const id of [21, 22, 23, 24, 25, 14, 13, 12]) {
  await go(page, `/tickets/${id}`);
  const tab = page.getByRole('tab', { name: /การเงิน/ });
  if (await tab.count()) { await tab.first().click(); await page.waitForTimeout(800); }
  const btn = page.getByRole('button', { name: /ใบแจ้งหนี้ส่วนที่เหลือ/ });
  const n = await btn.count();
  console.log(id, 'buttons:', n, n ? await btn.first().innerText() : '');
  if (n) {
    await btn.first().click(); await page.waitForTimeout(2500);
    await page.screenshot({ path: `${out}/b_inv_dialog.png` });
    console.log(await page.evaluate(() => (document.querySelector('[role=dialog]')?.innerText ?? 'NO DIALOG').replace(/\n+/g, ' | ').slice(0, 700)));
    break;
  }
}
await browser.close();
