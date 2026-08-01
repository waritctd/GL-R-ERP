import { test, expect } from '@playwright/test';
import { loginAs, spaGoto } from './helpers/auth.js';

const VIEWPORTS = [
  { name: 'desktop', width: 1366, height: 768 },
  { name: 'mobile', width: 390, height: 844 },
];

const LOGIN_VIEWPORT = { width: 1366, height: 768 };

async function loginAtViewport(page, role, viewport) {
  await page.setViewportSize(LOGIN_VIEWPORT);
  await loginAs(page, role);
  await page.setViewportSize(viewport);
}

async function expectNoHorizontalPageOverflow(page) {
  await expect.poll(() => page.evaluate(() => ({
    body: document.body.scrollWidth <= document.body.clientWidth,
    root: document.documentElement.scrollWidth <= document.documentElement.clientWidth,
  }))).toEqual({ body: true, root: true });
}

test.describe('Batch 1 safety foundations', () => {
  for (const viewport of VIEWPORTS) {
    test(`access denial is explained without leaking restricted content @ ${viewport.name}`, async ({ page }) => {
      await loginAtViewport(page, 'sales', viewport);

      await spaGoto(page, '/payroll');

      await expect.poll(() => new URL(page.url()).pathname).toBe('/');
      await expect(page.getByText('ยังเปิดหน้านี้ไม่ได้')).toBeVisible();
      await expect(page.getByText('ระบบพากลับมาหน้าหลักแล้ว เนื้อหานี้จะเปิดได้เฉพาะบทบาทที่ได้รับสิทธิ์')).toBeVisible();
      await expect(page.getByText('เงินเดือนพนักงาน')).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'รับทราบ' })).toBeVisible();
      await expectNoHorizontalPageOverflow(page);
    });

    test(`notification popover stays usable and restores focus @ ${viewport.name}`, async ({ page }) => {
      await loginAtViewport(page, 'sales', viewport);

      const trigger = page.getByRole('button', { name: /การแจ้งเตือน/ });
      await trigger.click();

      const panel = page.getByRole('dialog', { name: 'การแจ้งเตือน' });
      await expect(panel).toBeVisible();
      const box = await panel.boundingBox();
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);

      await page.keyboard.press('Escape');
      await expect(panel).toHaveCount(0);
      await expect(trigger).toBeFocused();
      await expectNoHorizontalPageOverflow(page);
    });
  }

  test('login toast is announced and remains a dismiss button', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('login-role-sales').click();

    await expect(page.getByRole('status').filter({ hasText: 'เข้าสู่ระบบสำเร็จ' })).toBeVisible();
    const dismiss = page.getByRole('button', { name: 'ปิดข้อความ: เข้าสู่ระบบสำเร็จ' });
    await expect(dismiss).toBeVisible();
    await dismiss.click();
    await expect(dismiss).toHaveCount(0);
  });

  test('pricing-request detail not-found state uses a safe return action for sales', async ({ page }) => {
    await loginAs(page, 'sales');

    await spaGoto(page, '/pricing-requests/not-a-number');

    await expect(page.getByText('ไม่พบใบขอราคานี้')).toBeVisible();
    await expect(page.getByRole('button', { name: 'กลับ' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'กลับไปที่คิวใบขอราคา' })).toHaveCount(0);
  });
});
