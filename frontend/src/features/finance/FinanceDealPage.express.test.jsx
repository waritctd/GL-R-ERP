import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FinanceDealPage } from './FinanceDealPage.jsx';
import { makeFinanceDeal } from '../../test/fixtures/financeDeal.js';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
      finance: {
        getDeal: vi.fn(), addComment: vi.fn(), confirmDepositPaid: vi.fn(), confirmFinalPayment: vi.fn(),
        recordPayment: vi.fn(), confirmCloseReady: vi.fn(), revokeCloseConfirmation: vi.fn(), updateStage: vi.fn(),
      },
      commissions: { createFromDeal: vi.fn() },
      receiptImports: { latest: vi.fn() },
    },
  };
});

const action = (name, label) => ({ action: name, label, targetStage: null, requiredFields: [] });

function renderPage(role) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/finance/deals/501']}>
        <Routes>
          <Route path="/finance/deals/:id" element={<FinanceDealPage user={{ role, name: 'ผู้ใช้' }} showToast={vi.fn()} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('FinanceDealPage — Express upload flag', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.finance.getDeal.mockResolvedValue({
      deal: makeFinanceDeal({ availableActions: [action('DEPOSIT_PAID', 'รับมัดจำ'), action('RECORD_PAYMENT', 'บันทึกรับชำระเงิน')] }),
    });
  });

  it('the CEO never issues the account-only receipt-imports query', async () => {
    renderPage('ceo');
    await screen.findByRole('button', { name: 'ยืนยันรับมัดจำ' });
    expect(api.receiptImports.latest).not.toHaveBeenCalled();
  });

  it('account issues it, and with the flag ON the deposit confirm mentions the Express match', async () => {
    api.receiptImports.latest.mockResolvedValue({ uploadEnabled: true });
    renderPage('account');
    await waitFor(() => expect(api.receiptImports.latest).toHaveBeenCalled());
    fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันรับมัดจำ' }));
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(within(dialog).getByText(/จับคู่รายการจาก Express/)).not.toBeNull());
  });
});
