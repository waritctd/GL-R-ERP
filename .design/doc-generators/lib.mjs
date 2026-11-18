import { createRequire } from 'node:module';
const require = createRequire(process.env.FE + '/');
const { chromium } = require('playwright-core');
export async function open(role, width = 1440) {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width, height: 900 }, deviceScaleFactor: 1 });
  await page.goto('http://localhost:5204/');
  await page.getByRole('button', { name: new RegExp('^' + role) }).first().click();
  await page.waitForTimeout(1500);
  return { browser, page };
}
export async function go(page, path) {
  await page.evaluate((p) => { window.history.pushState({}, '', p); window.dispatchEvent(new PopStateEvent('popstate')); }, path);
  await page.waitForTimeout(2200);
}
// The app scrolls inside a container, so grow the viewport until nothing scrolls, then shoot.
export async function shoot(page, file, width = 1440) {
  await page.setViewportSize({ width, height: 900 });
  await page.waitForTimeout(200);
  const need = await page.evaluate(() => {
    let extra = 0;
    for (const el of document.querySelectorAll('*')) {
      const s = getComputedStyle(el);
      if (/(auto|scroll)/.test(s.overflowY) && el.scrollHeight > el.clientHeight + 4 && el.clientHeight > 300) extra = Math.max(extra, el.scrollHeight - el.clientHeight);
    }
    return Math.max(document.documentElement.scrollHeight, innerHeight + extra);
  });
  await page.setViewportSize({ width, height: Math.min(need + 8, 9000) });
  await page.waitForTimeout(500);
  await page.screenshot({ path: file });
  await page.setViewportSize({ width, height: 900 });
}
export const head = (page, n = 500) => page.evaluate((n) => (document.querySelector('main')?.innerText ?? '').replace(/\n+/g, ' | ').slice(0, n), n);
