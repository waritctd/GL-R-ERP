import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DealRemainingInvoiceCard } from './DealRemainingInvoiceCard.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: { storedRemainingInvoices: { listForTicket: vi.fn() } },
  };
});

function renderCard({ remainingInvoices = [], canManage = true, onManage = vi.fn() } = {}) {
  api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <DealRemainingInvoiceCard ticketId={19} canManage={canManage} onManage={onManage} />
    </QueryClientProvider>,
  );
  return { onManage };
}

describe('DealRemainingInvoiceCard', () => {
  it('shows the empty state when no remaining invoice exists yet', async () => {
    renderCard();
    expect(await screen.findByText('ยังไม่มีใบแจ้งหนี้ส่วนที่เหลือ')).toBeTruthy();
  });

  it('prefers the ISSUED row over a DRAFT one when both exist', async () => {
    renderCard({
      remainingInvoices: [
        { id: 1, status: 'DRAFT', docNumber: null, docDate: null },
        { id: 2, status: 'ISSUED', docNumber: 'GLR6900001-1', docDate: '2026-09-01' },
      ],
    });
    expect(await screen.findByText('ออกแล้ว')).toBeTruthy();
    expect(screen.getByText((text) => text.includes('GLR6900001-1'))).toBeTruthy();
  });

  it('falls back to the DRAFT row when no ISSUED row exists', async () => {
    renderCard({ remainingInvoices: [{ id: 1, status: 'DRAFT', docNumber: null, docDate: '2026-09-01' }] });
    expect(await screen.findByText('ร่าง')).toBeTruthy();
  });

  // The single most important wiring fact about this component: it must NOT own its own dialog
  // state (which would risk two RemainingInvoiceDialog instances mounted from two entry points on
  // the same page) — it only ever calls the caller's onManage.
  it('calls onManage when the button is clicked', async () => {
    const { onManage } = renderCard();
    await screen.findByText('ยังไม่มีใบแจ้งหนี้ส่วนที่เหลือ');
    fireEvent.click(screen.getByRole('button', { name: 'จัดการใบแจ้งหนี้ส่วนที่เหลือ' }));
    expect(onManage).toHaveBeenCalledTimes(1);
  });

  // Review round 1 (2026-09-23): this card's status summary is shown to a wider audience
  // (sections.payment: sales/sales_manager/ceo/account) than who may actually manage the
  // document (canManage — the caller passes the SAME can.downloadRemainingInvoice gate the
  // pre-existing sticky-bar entry point uses). The button must disappear, not just disable,
  // when the caller says this viewer/deal-state combination isn't a manage case — otherwise a
  // non-sales viewer or a not-yet-ready deal gets a control that contradicts what the pipeline
  // right above says ("รอขั้นตอนก่อนหน้า").
  it('hides the button entirely when canManage is false, regardless of document state', async () => {
    const { onManage } = renderCard({
      canManage: false,
      remainingInvoices: [{ id: 1, status: 'ISSUED', docNumber: 'GLR6900001-1', docDate: '2026-09-01' }],
    });
    await screen.findByText('ออกแล้ว');
    expect(screen.queryByRole('button', { name: 'จัดการใบแจ้งหนี้ส่วนที่เหลือ' })).toBeNull();
    expect(onManage).not.toHaveBeenCalled();
  });
});
