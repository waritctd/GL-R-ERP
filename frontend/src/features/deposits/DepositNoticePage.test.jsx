import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DepositNoticePage } from './DepositNoticePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    depositNotices: {
      noteTemplates: vi.fn(),
      listByTicket: vi.fn(),
      createDraft: vi.fn(),
      update: vi.fn(),
      preview: vi.fn(),
      issue: vi.fn(),
      downloadXlsx: vi.fn(),
      downloadPdf: vi.fn(),
    },
    customers: {
      search: vi.fn(),
    },
  },
}));

function renderDepositNoticePage(props = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <DepositNoticePage
        ticketId="701"
        onBack={vi.fn()}
        onNavigateTickets={vi.fn()}
        showToast={vi.fn()}
        {...props}
      />
    </QueryClientProvider>,
  );
}

describe('DepositNoticePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.depositNotices.noteTemplates.mockResolvedValue({
      templates: [{ id: 1, text: 'หมายเหตุมาตรฐาน', defaultSelected: true }],
    });
    api.customers.search.mockResolvedValue({ customers: [] });
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] });
    api.depositNotices.createDraft.mockResolvedValue({
      depositNotice: {
        id: 9001, ticketId: 701, version: 1, status: 'DRAFT', docNumber: null,
        customerName: 'บริษัท ทดสอบ จำกัด', customerTaxId: '', customerAddress: '',
        projectName: '', reference: '', depositPercent: 0.5, notes: [], items: [],
      },
    });
  });

  it('renders the empty-state and lets the user create a draft', async () => {
    renderDepositNoticePage();

    expect(await screen.findByText('ยังไม่มีใบแจ้งยอดเงินรับมัดจำ')).not.toBeNull();

    const createButton = screen.getByRole('button', { name: /สร้างเอกสารฉบับร่าง/ });
    fireEvent.click(createButton);

    await waitFor(() => expect(api.depositNotices.createDraft).toHaveBeenCalledTimes(1));
    expect(api.depositNotices.createDraft).toHaveBeenCalledWith('701', {
      notes: ['หมายเหตุมาตรฐาน'],
      depositPercent: 0.5,
    });

    // Mutation succeeding invalidates depositNotices(ticketId), which refetches
    // listByTicket — the second call is that background refetch.
    await waitFor(() => expect(api.depositNotices.listByTicket).toHaveBeenCalledTimes(2));
  });

  it('renders the existing draft document instead of the empty state', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{
        id: 9002, ticketId: 701, version: 1, status: 'DRAFT', docNumber: null,
        customerName: 'บริษัท ทดสอบ จำกัด', customerTaxId: '1234567890123', customerAddress: 'กรุงเทพฯ',
        projectName: 'โครงการ A', reference: 'PO-001', depositPercent: 0.5, notes: [], items: [],
      }],
    });

    renderDepositNoticePage();

    await waitFor(() => expect(screen.queryByText('ยังไม่มีใบแจ้งยอดเงินรับมัดจำ')).toBeNull());
    expect(await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด')).not.toBeNull();
  });
});
