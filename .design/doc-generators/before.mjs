import { open, go, shoot, head } from './lib.mjs';
const out = process.argv[2];
const { browser, page } = await open('ฝ่ายขาย');
await go(page, '/quotations/1'); await shoot(page, `${out}/b_qt_draft.png`);
await go(page, '/quotations/2'); await shoot(page, `${out}/b_qt_view.png`);
await go(page, '/tickets/19/deposit'); await shoot(page, `${out}/b_dn_issued.png`);
const keys = await page.evaluate(async () => {
  const url = performance.getEntriesByType('resource').map((r) => r.name).find((n) => n.includes('/src/api/mockApi.js'));
  const m = await import(url);
  if (m.setDepositNoticeStatusesForTests) m.setDepositNoticeStatusesForTests(20, 'DRAFT');
  return url + ' ' + Object.keys(m).filter((k) => /Deposit/.test(k)).join(',');
});
console.log('mock:', keys);
await go(page, '/tickets/20/deposit'); await shoot(page, `${out}/b_dn_draft.png`);
console.log('DN20:', await head(page, 300));
const prev = page.getByRole('button', { name: /Preview/ });
if (await prev.count()) { await prev.first().click(); await page.waitForTimeout(2500); await shoot(page, `${out}/b_dn_draft_preview.png`); }
else console.log('no Preview button');
await browser.close();
