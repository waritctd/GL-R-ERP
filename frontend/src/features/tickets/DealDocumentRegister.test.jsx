import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
        remainingInvoiceOptions: vi.fn(),
      },
      pricingRequests: {
        listCustomerQuotations: vi.fn(),
        downloadCustomerQuotationPdf: vi.fn(),
        downloadCustomerQuotationXlsx: vi.fn(),
      },
      // GLA-123 slice S3 MAJOR 4 fix — directQuotationsQuery's own source (backs the "ใบเสนอราคา"
      // panel this register only counts/points to). Absent from this mock before this fix;
      // harmless while it was never asserted on, but the new test below needs a real value.
      dealQuotations: {
        listForTicket: vi.fn().mockResolvedValue({ items: [] }),
      },
      depositNotices: {
        listByTicket: vi.fn(),
        downloadXlsx: vi.fn(),
        downloadPdf: vi.fn(),
        noteTemplates: vi.fn(),
      },
      // The STORED remaining invoice aggregate (V188, GLA-99 step 2) — RemainingInvoiceDialog
      // checks this before it ever reaches the stateless preview these tests exercise. Defaults
      // to "no live document yet", matching every fixture below.
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
    api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
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

  // GLA-123 item 8 / slice S3 MAJOR 4 fix — DealQuotationRepository#findByTicket used to be
  // DEAL_DIRECT-only, so a deal whose only quotation was PRICING_REQUEST-origin showed the false
  // "ยังไม่มีใบเสนอราคาสำหรับดีลนี้" empty state even with one ISSUED. With the backend fix, the
  // SAME endpoint (dealQuotations.listForTicket) now returns that row, so this register — which
  // only counts/points to it, the full row renders in DealDirectQuotationPanel below — must stop
  // claiming there are no quotations.
  it('a deal with only a PRICING_REQUEST-origin quotation does not show the false "no quotations" empty state', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [{ id: 9101, number: 'QT-2026-0101-1', docStatus: 'ISSUED', origin: 'PRICING_REQUEST' }],
    });
    renderRegister({
      user: { id: 1, role: 'sales' },
      sections: SALES_SECTIONS,
      canViewPricingRequests: true,
      canViewDocumentsTab: true,
      pricingRequests: [],
      legacyQuotations: [],
    });

    expect(await screen.findByTestId('register-quotations')).not.toBeNull();
    expect(await screen.findByText(/ใบเสนอราคาของดีลนี้ 1 ฉบับ/)).not.toBeNull();
    expect(screen.queryByText('ยังไม่มีใบเสนอราคาสำหรับดีลนี้')).toBeNull();
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

  // GLA-117: DealDocumentRegister lists EVERY deposit notice for the ticket (unlike
  // DealDepositPanel/DepositNoticePage, which only ever surface the current DRAFT or latest
  // ISSUED row via a draft ?? latestIssued memo), so a revised deal's original, superseded
  // notice is the one place this bug was actually user-visible: the old inline
  // `doc.status === 'ISSUED' ? 'ออกแล้ว' : 'ฉบับร่าง'` ternary rendered SUPERSEDED as "ฉบับร่าง"
  // (draft), making an already-issued document look untouched and still editable. The old
  // ternary ALSO gated download actions to ISSUED-only, so a SUPERSEDED notice lost its PDF/Excel
  // buttons entirely — the ticket's acceptance criterion is that both stay downloadable.
  it('a SUPERSEDED deposit notice renders ถูกแทนที่ (not ฉบับร่าง) and both it and the ISSUED notice stay downloadable', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [
        { id: 11, status: 'SUPERSEDED', docNumber: 'DN-2026-0001', depositPercent: 0.5, version: 1 },
        { id: 12, status: 'ISSUED', docNumber: 'DN-2026-0001-R2', depositPercent: 0.5, version: 2 },
      ],
    });
    api.depositNotices.downloadPdf.mockResolvedValue(new Blob(['pdf']));
    api.depositNotices.downloadXlsx.mockResolvedValue(new Blob(['xlsx']));

    renderRegister({
      user: { id: 5, role: 'account' },
      sections: ACCOUNT_SECTIONS,
      canViewPricingRequests: false,
      canViewDocumentsTab: true,
    });

    const section = within(await screen.findByTestId('register-deposit-and-invoice'));

    // The bug: SUPERSEDED must read ถูกแทนที่, and never fall back to ฉบับร่าง. Wait for the
    // deposit-notice rows themselves (the section testid mounts immediately in its loading
    // skeleton, before the listByTicket query resolves).
    expect(await section.findByText('ถูกแทนที่')).not.toBeNull();
    expect(section.getByText('ออกแล้ว')).not.toBeNull();
    expect(section.queryByText('ฉบับร่าง')).toBeNull();

    // The acceptance criterion: both remain downloadable, not gated to ISSUED-only.
    const pdfButtons = section.getAllByRole('button', { name: 'PDF' });
    const xlsxButtons = section.getAllByRole('button', { name: 'Excel' });
    expect(pdfButtons).toHaveLength(2);
    expect(xlsxButtons).toHaveLength(2);

    fireEvent.click(pdfButtons[0]);
    await waitFor(() => expect(api.depositNotices.downloadPdf).toHaveBeenCalledWith(11));
    fireEvent.click(pdfButtons[1]);
    await waitFor(() => expect(api.depositNotices.downloadPdf).toHaveBeenCalledWith(12));
    fireEvent.click(xlsxButtons[0]);
    await waitFor(() => expect(api.depositNotices.downloadXlsx).toHaveBeenCalledWith(11));
    fireEvent.click(xlsxButtons[1]);
    await waitFor(() => expect(api.depositNotices.downloadXlsx).toHaveBeenCalledWith(12));
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

  // Finding 6 (both entry points must open the dialog, not download directly): this is one of
  // the two entry points — TicketDetailPage.test.jsx pins the other.
  it('clicking the Excel action opens the RemainingInvoiceDialog rather than downloading directly', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({
      options: {
        docNumber: 'GLRI69001', defaultIssueDate: '2026-09-01', defaultReference: null,
        referenceOptions: [], defaultDepositReference: null, depositReferenceOptions: [],
        noteTemplates: [], itemCount: 1, maxItems: 22, itemsTotal: 100, depositAmount: 0,
        netAmount: 100, vatAmount: 7, totalPayable: 107,
        quotationOptions: [], defaultQuotationId: null, blockingReason: null,
      },
    });
    renderRegister({
      user: { id: 5, role: 'account' },
      sections: ACCOUNT_SECTIONS,
      canViewDocumentsTab: true,
      summary: { status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED' },
    });

    const section = within(await screen.findByTestId('register-deposit-and-invoice'));
    fireEvent.click(section.getByRole('button', { name: 'Excel' }));

    // account is not this deal's owning sales rep, so R4 shows the "waiting for sales" state
    // rather than the create-flow options preview — either way, the register itself never
    // downloads directly; it only ever opens the dialog.
    expect(await screen.findByTestId('remaining-invoice-dialog')).not.toBeNull();
  });

  // A from-stock deal never writes GOODS_RECEIVED (import-axis only), so the old gate left this row
  // at รอขั้นตอน for its whole life. Ready states and the wrong-way-round transit states both pinned.
  it.each([
    ['FROM_STOCK', true],
    ['PARTIALLY_DELIVERED', true],
    ['FULLY_DELIVERED', true],
    ['SHIPPING', false],
    ['IR_SENT', false],
    [null, false],
  ])('remaining-invoice row with fulfilment %s → ready=%s', async (fulfillmentStatus, ready) => {
    renderRegister({
      user: { id: 5, role: 'account' },
      sections: ACCOUNT_SECTIONS,
      canViewDocumentsTab: true,
      summary: { status: 'quotation_issued', fulfillmentStatus },
    });
    const section = within(await screen.findByTestId('register-deposit-and-invoice'));
    if (ready) {
      expect(section.getByText('พร้อมใช้งาน')).not.toBeNull();
      expect(section.getByRole('button', { name: 'Excel' })).not.toBeNull();
    } else {
      expect(section.getByText('รอขั้นตอน')).not.toBeNull();
      expect(section.queryByRole('button', { name: 'Excel' })).toBeNull();
    }
  });
});
