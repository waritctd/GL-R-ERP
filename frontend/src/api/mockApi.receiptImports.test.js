import { beforeEach, describe, it, expect } from 'vitest';
import { api, setMockReceiptUploadEnabled } from './mockApi.js';

// Mirrors ReceiptImportService / ReceiptMatcher (V213/V214). The mock's authz is NOT evidence about the Java service —
// ReceiptImportIntegrationTest is. This pins the SHAPE, the off switch, account-only, non-cash rows, re-upload
// idempotency and un-ignore, so the mock-driven screens behave like the server. Mock mode reads .csv only.

const csv = (rows) => new File([`${String.fromCharCode(0xfeff)}เลขที่เอกสาร,วันที่,ลูกค้า,จำนวนเงิน,อ้างอิง\n${rows.join('\n')}\n`],
  'week.csv', { type: 'text/csv' });

describe('mockApi.receiptImports', () => {
  beforeEach(() => setMockReceiptUploadEnabled(true));

  it('is off by default: upload refused, latest says so', async () => {
    setMockReceiptUploadEnabled(false);
    await api.auth.login({ role: 'account' });
    await expect(api.receiptImports.upload(csv(['RE-M-0,2026-11-12,ใคร,1,']))).rejects.toMatchObject({ status: 409 });
    expect((await api.receiptImports.latest()).uploadEnabled).toBe(false);
  });

  it('account only — not the CEO, not sales, not the sales manager', async () => {
    for (const role of ['ceo', 'sales', 'sales_manager']) {
      await api.auth.login({ role });
      await expect(api.receiptImports.upload(csv(['RE-M-1,2026-11-12,ใคร,1,']))).rejects.toMatchObject({ status: 403 });
      await expect(api.receiptImports.unmatched()).rejects.toMatchObject({ status: 403 });
    }
    await api.auth.login({ role: 'sales' });
    await expect(api.receiptImports.managerView()).rejects.toMatchObject({ status: 403 });
    await api.auth.login({ role: 'sales_manager' });
    expect(await api.receiptImports.managerView()).toMatchObject({ openUnmatched: expect.any(Number), flagged: [] });
  });

  it('IV rows are not cash; a re-upload is all duplicates', async () => {
    await api.auth.login({ role: 'account' });
    const file = csv(['RE-M-2,2026-11-12,ไม่มีลูกค้านี้,100,', 'IV-M-3,2026-11-12,ใคร,1,', 'RE-M-4,bad,ใคร,1,']);
    const first = await api.receiptImports.upload(file);
    expect(first).toMatchObject({ unmatched: 1, nonCash: 1, invalid: 1, matched: 0, duplicates: 0, failed: 0 });
    expect(first.errors[0]).toMatchObject({ line: 4 });
    const second = await api.receiptImports.upload(file);
    expect(second).toMatchObject({ unmatched: 0, duplicates: 1, nonCash: 1 });
  });

  it('ignore, then bring back; flag for the manager', async () => {
    await api.auth.login({ role: 'account' });
    await api.receiptImports.upload(csv(['RE-M-9,2026-11-12,ไม่มีลูกค้าเลย,55,']));
    const row = (await api.receiptImports.unmatched()).find((r) => r.docNo === 'RE-M-9');
    expect(row).toMatchObject({ status: 'UNMATCHED', reason: 'NO_CANDIDATE', candidates: [] });
    await api.receiptImports.ignore(row.rowId);
    expect((await api.receiptImports.ignored()).some((r) => r.docNo === 'RE-M-9')).toBe(true);
    expect((await api.receiptImports.unignore(row.rowId)).status).toBe('UNMATCHED');
    expect((await api.receiptImports.flagManager(row.rowId)).managerFlaggedAt).toBeTruthy();
    await api.auth.login({ role: 'sales_manager' });
    expect((await api.receiptImports.managerView()).flagged.some((r) => r.docNo === 'RE-M-9')).toBe(true);
  });

  it('N5: possibleManual.alreadyLinked turns true once another row has linked that manual receipt', async () => {
    await api.auth.login({ role: 'account' });
    const { depositNotices } = await api.depositNotices.listByTicket(19); // seeded ISSUED notice
    const ref = depositNotices.find((d) => d.status === 'ISSUED').docNumber;
    const today = new Date().toISOString().slice(0, 10);
    await api.finance.recordPayment(19, { kind: 'DEPOSIT', amount: 777, allowOverpayment: true, note: 'ทดสอบ' });
    await api.receiptImports.upload(csv([`RE-LK-1,${today},ใคร,777,${ref}`, `RE-LK-2,${today},ใคร,777,${ref}`]));
    const mine = async (no) => (await api.receiptImports.unmatched()).find((r) => r.docNo === no);
    expect((await mine('RE-LK-2')).possibleManual.alreadyLinked).toBe(false);
    await api.receiptImports.sameAsManual((await mine('RE-LK-1')).rowId);
    const second = await mine('RE-LK-2');
    expect(second.possibleManual.alreadyLinked).toBe(true);
    await expect(api.receiptImports.sameAsManual(second.rowId)).rejects.toMatchObject({ status: 409 });
  });
});
