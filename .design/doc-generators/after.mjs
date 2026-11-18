import { open, go, shoot, head } from './lib.mjs';
const out = process.argv[2];
const { browser, page } = await open('ฝ่ายขาย');
page.on('pageerror', (e) => console.log('PAGEERROR', e.message.slice(0, 200)));
await go(page, '/tickets/19/deposit'); await shoot(page, `${out}/a_dn_issued.png`);
await page.evaluate(async () => {
  const url = performance.getEntriesByType('resource').map((r) => r.name).find((n) => n.includes('/src/api/mockApi.js'));
  (await import(url)).setDepositNoticeStatusesForTests(20, 'DRAFT');
});
await go(page, '/tickets/20/deposit'); await shoot(page, `${out}/a_dn_draft.png`);
console.log('DN20:', await head(page, 400));
await shoot(page, `${out}/a_dn_draft_m.png`, 390);
await browser.close();
