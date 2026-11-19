import React from 'react';
import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { invalidateDealDocuments } from './documentChain.js';
import { queryKeys } from '../../api/queryKeys.js';

globalThis.React = React;

// "Always up to date with the งาน tab": the quotation page's chain card and the deal page's เงิน tab are mounted on
// ONE query client, an action is taken on one, and the other is asserted to show the new state — with no manual
// refetch, no remount. What makes it pass is each mutation's invalidateDealDocuments (documentChain.js).

vi.mock('../../utils/download.js', () => ({ downloadBlob: vi.fn() }));
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { get: vi.fn() },
      dealQuotations: {
        get: vi.fn(), promoteToDeal: vi.fn(), displayNameOptions: vi.fn().mockResolvedValue({ items: [] }),
        calculateLine: vi.fn(), downloadPdf: vi.fn(), downloadXlsx: vi.fn(),
      },
      documentChain: { get: vi.fn() },
      depositNotices: { listByTicket: vi.fn(), createDraft: vi.fn(), issue: vi.fn(), preview: vi.fn(), downloadPdf: vi.fn(), downloadXlsx: vi.fn() },
      pricingRequests: { listForTicket: vi.fn() },
      storedRemainingInvoices: { listForTicket: vi.fn(), download: vi.fn() },
      catalog: { prices: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
      customers: { search: vi.fn().mockResolvedValue({ customers: [] }), projects: vi.fn(), contacts: vi.fn() },
    },
  };
});

describe('invalidateDealDocuments — the keys every chain mutation invalidates', () => {
  it('marks every key both pages read as stale, for a string or a numeric ticket id', async () => {
    const queryClient = new QueryClient();
    const keys = [
      queryKeys.documentChain(18), queryKeys.depositNotices(18), queryKeys.depositNotices('18'),
      queryKeys.storedRemainingInvoices(18), queryKeys.storedRemainingInvoices('18'), queryKeys.ticketDetail(18),
      [...queryKeys.ticketDetail(18), 'promotionSummary'], queryKeys.ticketActions(18), ['tickets', 'list', {}],
      queryKeys.dealQuotationsList({}), queryKeys.dealQuotationDetail(5),
    ];
    for (const key of keys) queryClient.setQueryData(key, { seeded: true });
    await invalidateDealDocuments(queryClient, '18');
    for (const key of keys) expect(queryClient.getQueryState(key).isInvalidated, JSON.stringify(key)).toBe(true);
    const untouched = ['payroll', 'x'];
    queryClient.setQueryData(untouched, 1);
    await invalidateDealDocuments(queryClient, 18);
    expect(queryClient.getQueryState(untouched).isInvalidated).toBe(false);
  });
});
