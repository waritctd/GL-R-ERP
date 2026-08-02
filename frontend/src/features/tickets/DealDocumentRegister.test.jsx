import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DealDocumentRegister } from './DealDocumentRegister.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: {
        downloadQuotationXlsx: vi.fn(),
        downloadQuotationPdf: vi.fn(),
        downloadRemainingInvoice: vi.fn(),
      },
      pricingRequests: {
        listCustomerQuotations: vi.fn(),
        downloadCustomerQuotationPdf: vi.fn(),
        downloadCustomerQuotationXlsx: vi.fn(),
      },
      depositNotices: {
        listByTicket: vi.fn(),
        downloadXlsx: vi.fn(),
        downloadPdf: vi.fn(),
      },
      attachments: {
        fileUrl: (id) => `#mock-file-${id}`,
      },
    },
  };
});

// Mirrors salesViewScope.js's own section shape closely enough for this
// component's own gates (`sections?.dealQuotation`/`quotation`/`depositNotice`)
// — this file does not re-derive salesViewScope's per-role logic (out of
// bounds for this branch), it only feeds the component the exact shape that
// module already produces for each role under test.
const SALES_SECTIONS = { dealQuotation: true, quotation: true, depositNotice: true };
const IMPORT_SECTIONS = { dealQuotation: false, quotation: false, depositNotice: false };
const ACCOUNT_SECTIONS = { dealQuotation: true, quotation: true, depositNotice: true };

function renderRegister(props) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <DealDocumentRegister
        ticketId={701}
        summary={{ status: 'price_proposed', fulfillmentStatus: null }}
        pricingRequests={[]}
        legacyQuotations={[]}
        attachments={[]}
        attachLoading={false}
        {...props}
      />
    </QueryClientProvider>,
  );
}

describe('DealDocumentRegister', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] });
  });

  it('renders one honest empty state — not three silently-omitted sections — for a viewer admitted to none of the three row families', async () => {
    renderRegister({
      user: { id: 99, role: 'import' },
      sections: IMPORT_SECTIONS,
      canViewPricingRequests: true, // role-list includes import; sections still zero it out
      canViewDocumentsTab: false, // non-participant, not in canViewTicketDocuments
    });

    expect(await screen.findByTestId('deal-document-register')).not.toBeNull();
    expect(screen.getByText('ไม่มีเอกสารให้แสดงในมุมมองนี้')).not.toBeNull();
    expect(screen.queryByTestId('register-quotations')).toBeNull();
    expect(screen.queryByTestId('register-deposit-and-invoice')).toBeNull();
    expect(screen.queryByTestId('register-attachments')).toBeNull();
    // Never fire a query that would 403 — a swallowed 403 rendering an empty
    // register reads as "no documents exist", which is worse than the
    // honest "not available in this view" above.
    expect(api.pricingRequests.listCustomerQuotations).not.toHaveBeenCalled();
    expect(api.depositNotices.listByTicket).not.toHaveBeenCalled();
  });

  it('a sales owner sees all three row families', async () => {
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
    renderRegister({
      user: { id: 1, role: 'sales' },
      sections: SALES_SECTIONS,
      canViewPricingRequests: true,
      canViewDocumentsTab: true,
      pricingRequests: [{ id: 501, ticketCreatedById: 1, status: 'APPROVED_FOR_QUOTATION' }],
      legacyQuotations: [{
        id: 9001, number: 'QT-2026-0901', docStatus: 'ISSUED', totalAmount: 1000, issuedAt: '2026-07-03T09:00:00.000Z',
      }],
      attachments: [{ id: 1, fileName: 'po.pdf', attachType: 'PO', uploadedBy: 1 }],
    });

    expect(await screen.findByTestId('register-quotations')).not.toBeNull();
    expect(screen.getByText('QT-2026-0901')).not.toBeNull();
    expect(await screen.findByTestId('register-deposit-and-invoice')).not.toBeNull();
    expect(await screen.findByTestId('register-attachments')).not.toBeNull();
    expect(screen.getByText('po.pdf')).not.toBeNull();
    await waitFor(() => expect(api.pricingRequests.listCustomerQuotations).toHaveBeenCalledWith(501));
  });

  // REQUIRED CASE (coordinator addendum): an import rep who IS this deal's
  // assignee is a genuine PARTICIPANT (canViewDocumentsTab true, passed down
  // unchanged from TicketDetailPage's own identity-aware gate) — it reaches
  // the attachments roll-up, but `sections.depositNotice`/`dealQuotation`/
  // `quotation` stay false for `import` regardless of participation, so it
  // must see ZERO quotation and ZERO deposit/invoice rows. This is the row
  // where the two predicates (participant-based vs role+section-based)
  // visibly disagree — the most likely future regression in this slice.
  it('an import assignee reaches attachments but sees zero quotation and zero deposit/invoice rows', async () => {
    renderRegister({
      user: { id: 7, role: 'import' },
      sections: IMPORT_SECTIONS,
      canViewPricingRequests: true,
      canViewDocumentsTab: true, // participant (assignedToId === user.id)
      pricingRequests: [{ id: 501, ticketCreatedById: 1, status: 'APPROVED_FOR_QUOTATION' }],
      legacyQuotations: [{ id: 9001, number: 'QT-2026-0901', docStatus: 'ISSUED', totalAmount: 1000 }],
      attachments: [{ id: 1, fileName: 'po.pdf', attachType: 'PO', uploadedBy: 1 }],
    });

    expect(await screen.findByTestId('register-attachments')).not.toBeNull();
    expect(screen.getByText('po.pdf')).not.toBeNull();
    expect(screen.queryByTestId('register-quotations')).toBeNull();
    expect(screen.queryByTestId('register-deposit-and-invoice')).toBeNull();
    expect(screen.queryByText('QT-2026-0901')).toBeNull();
    expect(api.pricingRequests.listCustomerQuotations).not.toHaveBeenCalled();
    expect(api.depositNotices.listByTicket).not.toHaveBeenCalled();
  });

  it('account sees deposit/invoice and attachment rows but zero quotation rows', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ id: 1, status: 'ISSUED', docNumber: 'DN-2026-0001', depositPercent: 0.5 }],
    });
    renderRegister({
      user: { id: 5, role: 'account' },
      sections: ACCOUNT_SECTIONS,
      canViewPricingRequests: false, // TicketDetailPage's own canViewPricingRequests excludes account
      canViewDocumentsTab: true,
      attachments: [{ id: 2, fileName: 'invoice.pdf', attachType: 'INVOICE', uploadedBy: 1 }],
    });

    expect(screen.queryByTestId('register-quotations')).toBeNull();
    expect(await screen.findByText('DN-2026-0001')).not.toBeNull();
    expect(await screen.findByText('invoice.pdf')).not.toBeNull();
    expect(api.pricingRequests.listCustomerQuotations).not.toHaveBeenCalled();
  });

  it('shows the remaining-invoice row as ready only once quotation_issued + GOODS_RECEIVED, still under the deposit/invoice gate', async () => {
    const { rerender } = renderRegister({
      user: { id: 5, role: 'account' },
      sections: ACCOUNT_SECTIONS,
      canViewDocumentsTab: true,
      summary: { status: 'price_proposed', fulfillmentStatus: null },
    });
    const section = within(await screen.findByTestId('register-deposit-and-invoice'));
    expect(section.getByText('รอขั้นตอน')).not.toBeNull();
    expect(section.queryByRole('button', { name: 'Excel' })).toBeNull();

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    rerender(
      <QueryClientProvider client={queryClient}>
        <DealDocumentRegister
          ticketId={701}
          user={{ id: 5, role: 'account' }}
          sections={ACCOUNT_SECTIONS}
          canViewDocumentsTab
          canViewPricingRequests={false}
          pricingRequests={[]}
          legacyQuotations={[]}
          attachments={[]}
          attachLoading={false}
          summary={{ status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED' }}
        />
      </QueryClientProvider>,
    );
    const readySection = within(await screen.findByTestId('register-deposit-and-invoice'));
    expect(readySection.getByText('พร้อมใช้งาน')).not.toBeNull();
    expect(readySection.getByRole('button', { name: 'Excel' })).not.toBeNull();
  });
});
