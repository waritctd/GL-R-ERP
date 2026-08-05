import { expect, test } from '@playwright/test';
import { loginAs, SEEDED_ROLES } from './helpers/auth.js';

const DESKTOP = { label: 'desktop', width: 1366, height: 768 };
const TABLET = { label: 'tablet', width: 1024, height: 768 };
const MOBILE = { label: 'mobile', width: 390, height: 844 };
const VIEWPORTS = [DESKTOP, TABLET, MOBILE];

const KNOWN_NOISE = [/\/api\/auth\/login/, /ERR_ABORTED/, /502/, /Failed to load resource/];

function watchForProblems(page) {
  const problems = [];
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    const text = message.text();
    if (KNOWN_NOISE.some((pattern) => pattern.test(text))) return;
    problems.push(`console: ${text}`);
  });
  page.on('pageerror', (error) => problems.push(`pageerror: ${error.message}`));
  return problems;
}

async function assertNoShellOverflow(page, label) {
  const overflow = await page.evaluate(() => {
    const root = document.documentElement;
    const content = document.querySelector('.content-scroll');
    return {
      rootDelta: root.scrollWidth - root.clientWidth,
      contentDelta: content ? content.scrollWidth - content.clientWidth : 0,
    };
  });

  expect(overflow.rootDelta, `${label} document overflows horizontally`).toBeLessThanOrEqual(1);
  expect(overflow.contentDelta, `${label} content container overflows horizontally`).toBeLessThanOrEqual(1);
}

async function assertUserMenuContained(page, label) {
  const userTrigger = page.getByRole('button', { name: 'เมนูผู้ใช้' });
  await userTrigger.click();

  const menu = page.getByRole('menu', { name: 'เมนูผู้ใช้' });
  await expect(menu).toBeVisible();

  const geometry = await page.evaluate(() => {
    const menu = document.querySelector('#user-menu-panel');
    const topbar = document.querySelector('.topbar');
    const menuRect = menu.getBoundingClientRect();
    const topbarRect = topbar.getBoundingClientRect();
    return {
      left: menuRect.left,
      right: menuRect.right,
      top: menuRect.top,
      topbarBottom: topbarRect.bottom,
      viewportWidth: window.innerWidth,
    };
  });

  expect(geometry.left, `${label} user menu crosses left viewport edge`).toBeGreaterThanOrEqual(0);
  expect(geometry.right, `${label} user menu crosses right viewport edge`).toBeLessThanOrEqual(geometry.viewportWidth + 1);
  expect(geometry.top, `${label} user menu overlaps the topbar`).toBeGreaterThanOrEqual(geometry.topbarBottom - 1);

  await page.keyboard.press('Escape');
  await expect(menu).toHaveCount(0);
  await expect(userTrigger).toBeFocused();
}

async function assertTopbarPopoversDoNotStack(page) {
  const bell = page.getByRole('button', { name: /การแจ้งเตือน/ });
  const user = page.getByRole('button', { name: 'เมนูผู้ใช้' });

  await bell.click();
  await expect(page.getByRole('dialog', { name: 'การแจ้งเตือน' })).toBeVisible();

  await user.click();
  await expect(page.getByRole('menu', { name: 'เมนูผู้ใช้' })).toBeVisible();
  await expect(page.getByRole('dialog', { name: 'การแจ้งเตือน' })).toHaveCount(0);
}

test.describe('Batch 2 shared shell', () => {
  for (const role of SEEDED_ROLES) {
    for (const viewport of VIEWPORTS) {
      test(`${role} shell is usable at ${viewport.label}`, async ({ page }) => {
        const problems = watchForProblems(page);

        await page.setViewportSize(viewport);
        await loginAs(page, role);

        await expect(page.locator('main#main-content')).toBeVisible();
        await expect(page.locator('.topbar-title')).toContainText('GL&R ERP');
        await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
        await assertNoShellOverflow(page, `${role}/${viewport.label}`);

        const drawerTrigger = page.getByRole('button', { name: 'เปิดเมนูนำทาง' });
        if (viewport.width <= 1040) {
          await expect(drawerTrigger).toBeVisible();
          await drawerTrigger.focus();
          await drawerTrigger.click();

          const drawer = page.locator('.sidebar.is-mobile-drawer-open');
          await expect(drawer).toBeVisible();
          await expect(drawer.getByRole('link', { name: 'GL&R ERP home' })).toBeVisible();

          await expect
            .poll(async () => {
              const box = await drawer.boundingBox();
              return Math.round(box?.x ?? -999);
            }, { message: `${role}/${viewport.label} drawer finishes onscreen` })
            .toBeGreaterThanOrEqual(0);

          const drawerBox = await drawer.boundingBox();
          // Rounded, matching the poll above. The poll settles on
          // `Math.round(box.x) >= 0`, so asserting the RAW float here contradicts
          // it: the drawer arrives via a CSS transform and the compositor leaves
          // a sub-pixel residue at rest (observed -0.00039), which satisfies the
          // poll and then fails this line. That made the test fail at random on
          // PRs touching nothing near the nav drawer.
          expect(Math.round(drawerBox.x), `${role}/${viewport.label} drawer starts offscreen`).toBeGreaterThanOrEqual(0);
          expect(drawerBox.width, `${role}/${viewport.label} drawer is wider than viewport`).toBeLessThanOrEqual(viewport.width);

          await page.keyboard.press('Escape');
          await expect(page.locator('.sidebar')).not.toHaveClass(/is-mobile-drawer-open/);
          await expect(drawerTrigger).toBeFocused();
        } else {
          await expect(drawerTrigger).toBeHidden();
          await expect(page.locator('.sidebar')).toBeVisible();
        }

        await assertUserMenuContained(page, `${role}/${viewport.label}`);
        await assertTopbarPopoversDoNotStack(page);
        await assertNoShellOverflow(page, `${role}/${viewport.label} after overlays`);

        expect(problems, `unexpected console/page errors: ${problems.join(' | ')}`).toEqual([]);
      });
    }
  }
});
