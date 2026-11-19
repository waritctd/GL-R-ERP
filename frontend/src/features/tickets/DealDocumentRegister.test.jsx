import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DealDocumentRegister } from './DealDocumentRegister.jsx';
import { api } from '../../api/index.js';
import { formatMoney } from '../../utils/format.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: {
        downloadQuotationXlsx: vi.fn(),
        downloadQuotationPdf: vi.fn(),
        remainingInvoiceOptions: vi.fn(),
      },
      pricingRequests: {
        listCustomerQuotations: vi.fn(),
        downloadCustomerQuotationPdf: vi.fn(),
        downloadCustomerQuotationXlsx: vi.fn(),
      },
      // directQuotationsQuery's own source (backs the "ใบเสนอราคา" panel this register only
      // counts/points to).
      dealQuotations: {
        listForTicket: vi.fn().mockResolvedValue({ items: [] }),
        downloadPdf: vi.fn(),
        downloadXlsx: vi.fn(),
      },
      depositNotices: {
        listByTicket: vi.fn(),
        downloadXlsx: vi.fn(),
        downloadPdf: vi.fn(),
        noteTemplates: vi.fn(),
      },
      // The STORED remaining invoice aggregate: RemainingInvoiceDialog checks this before it reaches
      // the stateless preview these tests exercise. Defaults to "no live document yet", matching
      // every fixture below.
      storedRemainingInvoices: {
        listForTicket: vi.fn().mockResolvedValue({ remainingInvoices: [] }),
        createDraft: vi.fn(),
        update: vi.fn(),
        issue: vi.fn(),
        revise: vi.fn(),
        deleteDraft: vi.fn(),
        download: vi.fn(),
      },
      attachments: {
        fileUrl: (id) => `#mock-file-${id}`,
      },
      // The stored ใบขอซื้อ aggregate — one row per factory, listed in a 5th section.
      storedImportRequests: {
        listForTicket: vi.fn().mockResolvedValue({ importRequests: [] }),
        download: vi.fn(),
      },
    },
  };
});

// Mirrors salesViewScope.js's own section shape closely enough for this component's own gates
// (`sections?.dealQuotation`/`quotation`/`depositNotice`); it does not re-derive salesViewScope's
// per-role logic, only feeds the component the exact shape that module produces for each role.
const SALES_SECTIONS = { dealQuotation: true, quotation: true, depositNotice: true };
const IMPORT_SECTIONS = { dealQuotation: false, quotation: false, depositNotice: false };
const ACCOUNT_SECTIONS = { dealQuotation: true, quotation: true, depositNotice: true };

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}</div>;
}

function renderRegister(props) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/tickets/701']}>
      <LocationProbe />
      <DealDocumentRegister
        ticketId={701}
        summary={{ status: 'price_proposed', fulfillmentStatus: null }}
        pricingRequests={[]}
        legacyQuotations={[]}
        attachments={[]}
        attachLoading={false}
        {...props}
      />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DealDocumentRegister', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] });
    api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] });
    api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
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
    // Legacy rows are held back until the chain queries settle (so a chain-duplicated row never flashes).
    expect(await screen.findByText('QT-2026-0901')).not.toBeNull();
    expect(await screen.findByTestId('register-deposit')).not.toBeNull();
    expect(await screen.findByTestId('register-remaining-invoice')).not.toBeNull();
    expect(await screen.findByTestId('register-attachments')).not.toBeNull();
    expect(screen.getByText('po.pdf')).not.toBeNull();
    await waitFor(() => expect(api.pricingRequests.listCustomerQuotations).toHaveBeenCalledWith(501));
  });

  // ── Document versions: all versions per document, newest first ─────────────
  const CHAIN_PR = [{ id: 501, ticketCreatedById: 1, status: 'APPROVED_FOR_QUOTATION' }];
  const SALES = { user: { id: 1, role: 'sales' }, sections: SALES_SECTIONS, canViewPricingRequests: true, canViewDocumentsTab: true };

  function positionsInDom(testIds) {
    const all = screen.getAllByTestId(/^document-version-/).map((el) => el.getAttribute('data-testid'));
    return testIds.map((id) => all.indexOf(id));
  }

  it('groups chain quotation revisions under one document, newest first, older revisions still visible', async () => {
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({
      items: [
        { id: 1, number: 'QT-2026-0016-1', docStatus: 'SUPERSEDED', quotationRevisionNo: 1, grandTotal: 100 },
        { id: 3, number: 'QT-2026-0016-3', docStatus: 'ISSUED', quotationRevisionNo: 3, grandTotal: 300 },
        { id: 2, number: 'QT-2026-0016-2', docStatus: 'SUPERSEDED', quotationRevisionNo: 2, grandTotal: 200 },
      ],
    });
    renderRegister({ ...SALES, pricingRequests: CHAIN_PR });

    await screen.findByText('QT-2026-0016-3');
    const groups = screen.getAllByTestId('document-group');
    expect(groups).toHaveLength(1);
    const g = within(groups[0]);
    expect(g.getByText('QT-2026-0016-2')).not.toBeNull();
    expect(g.getByText('QT-2026-0016-1')).not.toBeNull();
    const [p3, p2, p1] = positionsInDom(['document-version-chain-3', 'document-version-chain-2', 'document-version-chain-1']);
    expect(p3).toBeGreaterThanOrEqual(0);
    expect(p3).toBeLessThan(p2);
    expect(p2).toBeLessThan(p1);
  });

  it('shows the chain quotation grandTotal (the row has no totalAmount field)', async () => {
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({
      items: [{ id: 3, number: 'QT-2026-0016-3', docStatus: 'ISSUED', quotationRevisionNo: 3, grandTotal: 12345.6 }],
    });
    renderRegister({ ...SALES, pricingRequests: CHAIN_PR });

    const row = await screen.findByTestId('document-version-chain-3');
    expect(row.textContent).toContain(formatMoney(12345.6));
  });

  it('a DRAFT deposit notice has no PDF/Excel buttons', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ id: 51, status: 'DRAFT', docNumber: null, version: 1, depositPercent: 0.5 }],
    });
    renderRegister({ user: { id: 5, role: 'account' }, sections: ACCOUNT_SECTIONS, canViewPricingRequests: false, canViewDocumentsTab: true });
    const row = within(await screen.findByTestId('document-version-deposit-51'));
    expect(row.queryByRole('button')).toBeNull();
  });

  // Wrong-way-round: same gate as the deposit notice (sections.depositNotice) — import is refused outright, so
  // the stored-invoice query must never fire for it.
  it('does not fetch stored remaining invoices for a viewer without sections.depositNotice', async () => {
    renderRegister({ user: { id: 7, role: 'import' }, sections: IMPORT_SECTIONS, canViewPricingRequests: true, canViewDocumentsTab: true });
    expect(await screen.findByTestId('register-attachments')).not.toBeNull();
    expect(api.storedRemainingInvoices.listForTicket).not.toHaveBeenCalled();
  });

  // An import rep who IS this deal's assignee is a genuine PARTICIPANT (canViewDocumentsTab true,
  // from TicketDetailPage's identity-aware gate): it reaches the attachments roll-up, but
  // `sections.depositNotice`/`dealQuotation`/`quotation` stay false for `import` regardless, so it
  // must see ZERO quotation and ZERO deposit/invoice rows. This is where the two predicates
  // (participant-based vs role+section-based) visibly disagree.
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
    expect(screen.queryByTestId('register-deposit')).toBeNull();
    expect(screen.queryByTestId('register-remaining-invoice')).toBeNull();
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
    const section = within(await screen.findByTestId('register-remaining-invoice'));
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
    const readySection = within(await screen.findByTestId('register-remaining-invoice'));
    expect(readySection.getByText('พร้อมใช้งาน')).not.toBeNull();
    expect(readySection.getByRole('button', { name: 'Excel' })).not.toBeNull();
  });
});
