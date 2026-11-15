import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mirrors ReceiptImportService / ReceiptMatcher (V213). The mock's authz is NOT evidence about the Java service —
// ReceiptImportIntegrationTest is. This pins the SHAPE, the re-upload idempotency, and "ambiguous → unmatched" so the
// mock-driven screens behave like the server. Mock mode reads .csv only.

const csv = (rows) => new File([`${String.fromCharCode(0xfeff)}เลขที่เอกสาร,วันที่,ลูกค้า,จำนวนเงิน,อ้างอิง\n${rows.join('\n')}\n`], 'week.csv',
  { type: 'text/csv' });

describe('mockApi.receiptImports', () => {
  it('only account / ceo upload; a re-upload of the same file is all duplicates', async () => {
    await api.auth.login({ role: 'sales' });
    await expect(api.receiptImports.upload(csv(['RE-M-1,2026-11-12,ไม่มีลูกค้านี้,100,']))).rejects.toMatchObject({ status: 403 });

    await api.auth.login({ role: 'account' });
    const file = csv(['RE-M-1,2026-11-12,ไม่มีลูกค้านี้,100,', 'RE-M-2,bad,ใคร,1,']);
    const first = await api.receiptImports.upload(file);
    expect(first).toMatchObject({ unmatched: 1, invalid: 1, matched: 0, duplicates: 0 });
    expect(first.errors[0]).toMatchObject({ line: 3 });
    const second = await api.receiptImports.upload(file);
    expect(second).toMatchObject({ unmatched: 0, duplicates: 1, matched: 0 });
    expect((await api.receiptImports.latest()).openUnmatched).toBeGreaterThanOrEqual(1);
  });

  it('refuses .xlsx in mock mode with a clear message', async () => {
    await api.auth.login({ role: 'account' });
    await expect(api.receiptImports.upload(new File(['x'], 'a.xlsx'))).rejects.toMatchObject({ status: 400 });
  });

  it('a rep cannot ignore; account can, and the row leaves the queue', async () => {
    await api.auth.login({ role: 'account' });
    await api.receiptImports.upload(csv(['RE-M-9,2026-11-12,ไม่มีลูกค้าเลย,55,']));
    const row = (await api.receiptImports.unmatched()).find((r) => r.docNo === 'RE-M-9');
    expect(row).toMatchObject({ status: 'UNMATCHED', reason: 'NO_CANDIDATE', candidates: [] });

    await api.auth.login({ role: 'sales' });
    await expect(api.receiptImports.ignore(row.rowId)).rejects.toMatchObject({ status: 403 });
    expect((await api.receiptImports.unmatched()).some((r) => r.docNo === 'RE-M-9')).toBe(false); // not theirs

    await api.auth.login({ role: 'account' });
    expect((await api.receiptImports.ignore(row.rowId)).status).toBe('IGNORED');
    expect((await api.receiptImports.unmatched()).some((r) => r.docNo === 'RE-M-9')).toBe(false);
  });
});
