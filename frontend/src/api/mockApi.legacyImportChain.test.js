import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// W3-04 (owner ruling 2026-10-29): mockApi mirrors TicketService — the one-click "issue IR" answers
// 409 with a Thai reason for every deal, and ISSUE_IMPORT_REQUEST is never advertised.
describe('mockApi legacy import chain', () => {
  it('tickets.issueImportRequest rejects 409 with the per-factory reason on any deal', async () => {
    await api.auth.login({ role: 'import' });
    const { tickets } = await api.tickets.list({});
    const attempt = api.tickets.issueImportRequest(tickets[0].id);
    await expect(attempt).rejects.toMatchObject({ status: 409 });
    await expect(attempt).rejects.toThrow(/ใบขอซื้อรายโรงงาน/);
  });
});
