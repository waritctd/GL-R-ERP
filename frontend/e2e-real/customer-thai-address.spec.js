import { test, expect } from '@playwright/test';
import { loginAs, spaGoto } from './helpers/auth.js';

// Own synthetic customers only. No fixture/global counts and no external address service.
for (const width of [1366, 390]) {
  test(`structured customer address survives a real database round trip at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await loginAs(page, 'sales');
    await spaGoto(page, '/quotations/new');
    const name = `Thai address e2e ${width} ${Date.now()}`;
    await page.getByPlaceholder('พิมพ์ค้นหาชื่อบริษัท / ลูกค้า…').fill(name);
    await page.getByRole('button', { name: 'เพิ่มลูกค้าใหม่', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'เพิ่มลูกค้าใหม่', exact: true });
    await expect(dialog.getByRole('button', { name: 'บันทึกลูกค้าใหม่' })).toBeDisabled();
    await dialog.getByLabel('เลขที่ / อาคาร / หมู่ / ซอย / ถนน').fill('88/8 ถนนสุขุมวิท 21');
    for (const [label, selection] of [['จังหวัด', 'กรุงเทพมหานคร'], ['เขต', 'วัฒนา'], ['แขวง', 'คลองเตยเหนือ']]) {
      await dialog.getByRole('combobox', { name: label, exact: true }).fill(selection);
      await dialog.getByRole('option', { name: selection, exact: true }).click();
    }
    await expect(dialog.getByLabel('รหัสไปรษณีย์')).toHaveValue('10110');
    await dialog.getByRole('button', { name: 'บันทึกลูกค้าใหม่' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(page.getByLabel('ที่อยู่', { exact: true })).toHaveValue('88/8 ถนนสุขุมวิท 21 แขวงคลองเตยเหนือ เขตวัฒนา กรุงเทพมหานคร 10110');
    await page.reload();
    await page.getByPlaceholder('พิมพ์ค้นหาชื่อบริษัท / ลูกค้า…').fill(name);
    await page.getByRole('option', { name, exact: true }).click();
    await expect(page.getByLabel('ที่อยู่', { exact: true })).toHaveValue('88/8 ถนนสุขุมวิท 21 แขวงคลองเตยเหนือ เขตวัฒนา กรุงเทพมหานคร 10110');
  });
}
