import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountFinancePage } from './AccountFinancePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Review F3: simulates the day nextAccountAction gains a step no drawn group lists. That deal must still be on the
// grouped view (last group, button เปิด) and the ต้องดำเนินการ badge must still equal the sum of the group counts.
vi.mock('../tickets/accountActions.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    nextAccountAction: (ticket, role) => (ticket?.id === 901
      ? { key: 'somethingNew', label: 'ขั้นใหม่', to: `/finance/deals/${ticket.id}`, urgent: false }
      : actual.nextAccountAction(ticket, role)),
  };
});

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
      dashboard: { summary: vi.fn() },
      receiptImports: { latest: vi.fn() },
    },
  };
});

const base = {
  status: 'quotation_issued', fulfillmentStatus: null, lifecycle: 'ACTIVE', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false, overdue: false, paymentDueDate: null,
};
const unknownStep = { ...base, id: 901, code: 'PR-2026-0901', customerName: 'บริษัท ใหม่ จำกัด', paymentStatus: 'CUSTOMER_CONFIRMED', salesStage: 'PROCUREMENT', amountPayable: 11000, amountPaid: 1000, amountOutstanding: 10000 };
const deposit = { ...base, id: 902, code: 'PR-2026-0902', customerName: 'บริษัท มัดจำ จำกัด', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', salesStage: 'NEGOTIATION', amountPayable: 21000, amountPaid: 1000, amountOutstanding: 20000 };

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/finance']}>
        <AccountFinancePage user={{ role: 'account' }} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AccountFinancePage — a step no group lists (fallback group)', () => {
  beforeEach(() => {
    api.dashboard.summary.mockResolvedValue({ summary: { financeOutstanding: 1 } });
    api.receiptImports.latest.mockResolvedValue({ uploadEnabled: false });
    api.tickets.list.mockImplementation((p = {}) => Promise.resolve({ tickets: p.salesStage === 'CLOSED_PAID' ? [] : [unknownStep, deposit] }));
  });

  it('lands in a last "อื่น ๆ ที่ต้องดำเนินการ" group with its step as text and a เปิด button; the badge equals the group sum', async () => {
    renderPage();
    const other = await screen.findByRole('region', { name: 'อื่น ๆ ที่ต้องดำเนินการ' });
    const sections = screen.getAllByRole('region').filter((r) => r.dataset.testid?.startsWith('fin-group-'));
    expect(sections[sections.length - 1]).toBe(other);
    const row = within(other).getByTestId('fin-work-row-901');
    expect(within(row).getByRole('link').textContent).toBe('เปิด');
    expect(within(row).getByRole('link').getAttribute('href')).toBe('/finance/deals/901');
    expect(within(row).getByText('ขั้นใหม่')).not.toBeNull();
    expect(within(other).getByTestId('group-count').textContent).toBe('1');
    const sum = screen.getAllByTestId('group-count').reduce((n, el) => n + Number(el.textContent), 0);
    expect(sum).toBe(2);
    expect(screen.getByRole('tab', { name: /ต้องดำเนินการ.*2 งาน/ })).not.toBeNull();
  });
});
