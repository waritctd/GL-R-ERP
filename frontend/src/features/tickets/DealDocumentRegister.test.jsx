import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
      // GLA-123 slice S3 MAJOR 4 fix — directQuotationsQuery's own source (backs the "ใบเสนอราคา"
      // panel this register only counts/points to). Absent from this mock before this fix;
      // harmless while it was never asserted on, but the new test below needs a real value.
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
      // CR-1 (R9): the stored ใบขอซื้อ aggregate — one row per factory, listed in a 5th section.
      storedImportRequests: {
        listForTicket: vi.fn().mockResolvedValue({ importRequests: [] }),
        download: vi.fn(),
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
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] });
    api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
  });

  it('renders one honest empty state — not silently-omitted sections — for a viewer admitted to none of the row families', async () => {
    renderRegister({
      // CR-1: import is admitted to the ใบขอซื้อ family now (it may read the stored IRs), so the
      // "admitted to nothing" viewer is a non-participant account here instead.
      user: { id: 99, role: 'account' },
      sections: IMPORT_SECTIONS,
      canViewPricingRequests: true, // role-list includes import; sections still zero it out
      canViewDocumentsTab: false, // non-participant, not in canViewTicketDocuments
    });

    expect(await screen.findByTestId('deal-document-register')).not.toBeNull();
    expect(screen.getByText('ไม่มีเอกสารให้แสดงในมุมมองนี้')).not.toBeNull();
    expect(screen.queryByTestId('register-quotations')).toBeNull();
    expect(screen.queryByTestId('register-deposit')).toBeNull();
    expect(screen.queryByTestId('register-remaining-invoice')).toBeNull();
    expect(screen.queryByTestId('register-attachments')).toBeNull();
    expect(screen.queryByTestId('register-import-requests')).toBeNull();
    expect(api.storedImportRequests.listForTicket).not.toHaveBeenCalled();
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
    // Legacy rows are held back until the chain queries settle (so a chain-duplicated row never flashes).
    expect(await screen.findByText('QT-2026-0901')).not.toBeNull();
    expect(await screen.findByTestId('register-deposit')).not.toBeNull();
    expect(await screen.findByTestId('register-remaining-invoice')).not.toBeNull();
    expect(await screen.findByTestId('register-attachments')).not.toBeNull();
    expect(screen.getByText('po.pdf')).not.toBeNull();
    await waitFor(() => expect(api.pricingRequests.listCustomerQuotations).toHaveBeenCalledWith(501));
  });

  // GLA-123 item 8 / slice S3 MAJOR 4 fix — DealQuotationRepository#findByTicket used to be
  // DEAL_DIRECT-only, so a deal whose only quotation was PRICING_REQUEST-origin showed the false
  // "ยังไม่มีใบเสนอราคาสำหรับดีลนี้" empty state even with one ISSUED. The register now LISTS the
  // rows of that endpoint itself (versions grouped) instead of pointing at the panel below, so the
  // row must be visible here and the old pointer sentence must be gone.
  // CHANGED from the pre-versions test: it asserted the "แสดงอยู่ในแผง" pointer sentence, which the
  // owner's redesign replaces with the real rows.
  it('a deal with only a PRICING_REQUEST-origin quotation lists it (no false empty state, no pointer sentence)', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [{ id: 9101, number: 'QT-2026-0101-1', docStatus: 'ISSUED', origin: 'PRICING_REQUEST', revisionNo: 1, grandTotal: 500 }],
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
    expect(await screen.findByText('QT-2026-0101-1')).not.toBeNull();
    expect(screen.queryByText('ยังไม่มีใบเสนอราคาสำหรับดีลนี้')).toBeNull();
    expect(screen.queryByText(/แสดงอยู่ในแผง/)).toBeNull();
  });

  // ── Document versions (owner redesign): all versions per document, newest first ─────────────
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

  it('renders a legacy row that shares an id with a chain row exactly once', async () => {
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({
      items: [{ id: 3, number: 'QT-2026-0016-3', docStatus: 'ISSUED', quotationRevisionNo: 3, grandTotal: 300 }],
    });
    renderRegister({
      ...SALES,
      pricingRequests: CHAIN_PR,
      legacyQuotations: [{ id: 3, number: 'QT-2026-0016-3', docStatus: 'ISSUED', totalAmount: 300, issuedAt: '2026-07-03T09:00:00.000Z' }],
    });

    await screen.findAllByText('QT-2026-0016-3');
    await screen.findByTestId('register-deposit'); // let every query settle
    expect(screen.getAllByText('QT-2026-0016-3')).toHaveLength(1);
    expect(screen.queryByTestId('document-version-legacy-3')).toBeNull();
  });

  it('lists direct quotations grouped by document, newest revision first, and downloads PDF by id', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [
        { id: 21, number: 'QT-2026-0200-1', docStatus: 'SUPERSEDED', revisionNo: 1, grandTotal: 100, origin: 'DEAL_DIRECT' },
        { id: 22, number: 'QT-2026-0200-2', docStatus: 'APPROVED', revisionNo: 2, grandTotal: 200, origin: 'DEAL_DIRECT' },
      ],
    });
    api.dealQuotations.downloadPdf.mockResolvedValue(new Blob(['pdf']));
    renderRegister({ ...SALES });

    await screen.findByText('QT-2026-0200-2');
    expect(screen.getByText('QT-2026-0200-1')).not.toBeNull();
    const [p2, p1] = positionsInDom(['document-version-direct-22', 'document-version-direct-21']);
    expect(p2).toBeGreaterThanOrEqual(0);
    expect(p2).toBeLessThan(p1);
    expect(screen.queryByText(/แสดงอยู่ในแผง/)).toBeNull();

    // Mirrors DealDirectQuotationPanel: PDF + Excel offered for every status, incl. SUPERSEDED.
    const oldRow = within(screen.getByTestId('document-version-direct-21'));
    fireEvent.click(oldRow.getByRole('button', { name: 'PDF' }));
    await waitFor(() => expect(api.dealQuotations.downloadPdf).toHaveBeenCalledWith(21));
    expect(oldRow.getByRole('button', { name: 'Excel' })).not.toBeNull();
  });

  // ── Round 2 ────────────────────────────────────────────────────────────────────────────────
  // Real backend: sales.quotation.number is UNIQUE from one sequence, so a direct row can never
  // legitimately share a chain row's number. In mock mode (separate counters) it can — and a
  // filter keyed on number silently hid a real document.
  it('still shows a direct row whose number equals a chain row number (no number-based dedupe)', async () => {
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({
      items: [{ id: 3, number: 'QT-X-1', docStatus: 'ISSUED', quotationRevisionNo: 1, grandTotal: 1 }],
    });
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [{ id: 77, number: 'QT-X-1', docStatus: 'APPROVED', revisionNo: 1, grandTotal: 2, origin: 'DEAL_DIRECT' }],
    });
    renderRegister({ ...SALES, pricingRequests: CHAIN_PR });

    expect(await screen.findByTestId('document-version-chain-3')).not.toBeNull();
    expect(await screen.findByTestId('document-version-direct-77')).not.toBeNull();
  });

  it('groups chain revisions of one pricing request together even when their numbers are unrelated', async () => {
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({
      items: [
        { id: 10, number: 'QT-2026-0010', docStatus: 'SUPERSEDED', quotationRevisionNo: 1, grandTotal: 1 },
        { id: 11, number: 'QT-2026-0044', docStatus: 'ISSUED', quotationRevisionNo: 2, grandTotal: 2 },
      ],
    });
    renderRegister({ ...SALES, pricingRequests: CHAIN_PR });

    await screen.findByText('QT-2026-0044');
    expect(screen.getAllByTestId('document-group')).toHaveLength(1);
    const [p11, p10] = positionsInDom(['document-version-chain-11', 'document-version-chain-10']);
    expect(p11).toBeGreaterThanOrEqual(0);
    expect(p11).toBeLessThan(p10);
  });

  it('groups legacy versions by recipientType (bare numbers differ), higher quotationVersion first; another recipient stays separate', async () => {
    renderRegister({
      ...SALES,
      legacyQuotations: [
        { id: 61, number: 'QT-2026-0601', recipientType: 'OWNER', quotationVersion: 1, docStatus: 'SUPERSEDED', totalAmount: 1, issuedAt: '2026-06-01T00:00:00Z' },
        { id: 62, number: 'QT-2026-0655', recipientType: 'OWNER', quotationVersion: 2, docStatus: 'ISSUED', totalAmount: 2, issuedAt: '2026-06-10T00:00:00Z' },
        { id: 63, number: 'QT-2026-0700', recipientType: 'DESIGNER', quotationVersion: 1, docStatus: 'ISSUED', totalAmount: 3, issuedAt: '2026-06-05T00:00:00Z' },
      ],
    });

    await screen.findByText('QT-2026-0655');
    expect(screen.getAllByTestId('document-group')).toHaveLength(2);
    const [p62, p61] = positionsInDom(['document-version-legacy-62', 'document-version-legacy-61']);
    expect(p62).toBeGreaterThanOrEqual(0);
    expect(p62).toBeLessThan(p61);
    const ownerGroup = screen.getByTestId('document-version-legacy-61').closest('[data-testid="document-group"]');
    expect(within(ownerGroup).getByTestId('document-version-legacy-62')).not.toBeNull();
  });

  it('merges chain, legacy and direct documents and orders them by newest version date, not by family', async () => {
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({
      items: [{ id: 3, number: 'QT-C-1', docStatus: 'ISSUED', quotationRevisionNo: 1, grandTotal: 1, issuedAt: '2026-01-01T00:00:00Z' }],
    });
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [{ id: 77, number: 'QT-D-1', docStatus: 'APPROVED', revisionNo: 1, grandTotal: 1, origin: 'DEAL_DIRECT', createdAt: '2026-05-01T00:00:00Z' }],
    });
    renderRegister({
      ...SALES,
      pricingRequests: CHAIN_PR,
      legacyQuotations: [{ id: 88, number: 'QT-L-1', recipientType: 'OWNER', quotationVersion: 1, docStatus: 'ISSUED', totalAmount: 1, issuedAt: '2026-03-01T00:00:00Z' }],
    });

    await screen.findByTestId('document-version-direct-77');
    await screen.findByTestId('document-version-chain-3');
    const [pDirect, pLegacy, pChain] = positionsInDom(['document-version-direct-77', 'document-version-legacy-88', 'document-version-chain-3']);
    expect(pDirect).toBeLessThan(pLegacy);
    expect(pLegacy).toBeLessThan(pChain);
  });

  it('does not render legacy rows while the chain queries are still loading (no flash of a duplicate)', () => {
    api.pricingRequests.listCustomerQuotations.mockReturnValue(new Promise(() => {}));
    renderRegister({
      ...SALES,
      pricingRequests: CHAIN_PR,
      legacyQuotations: [{ id: 3, number: 'QT-2026-0016-3', docStatus: 'ISSUED', totalAmount: 300 }],
    });
    expect(screen.queryByText('QT-2026-0016-3')).toBeNull();
  });

  // Download gates are unchanged by the versions redesign — regression pins, wrong-way-round.
  it.each(['DRAFT', 'SUPERSEDED'])('a %s chain quotation row has no PDF/Excel buttons', async (docStatus) => {
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({
      items: [{ id: 3, number: 'QT-G-1', docStatus, quotationRevisionNo: 1, grandTotal: 1 }],
    });
    renderRegister({ ...SALES, pricingRequests: CHAIN_PR });
    const row = within(await screen.findByTestId('document-version-chain-3'));
    expect(row.queryByRole('button')).toBeNull();
  });

  it('a DRAFT deposit notice has no PDF/Excel buttons', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ id: 51, status: 'DRAFT', docNumber: null, version: 1, depositPercent: 0.5 }],
    });
    renderRegister({ user: { id: 5, role: 'account' }, sections: ACCOUNT_SECTIONS, canViewPricingRequests: false, canViewDocumentsTab: true });
    const row = within(await screen.findByTestId('document-version-deposit-51'));
    expect(row.queryByRole('button')).toBeNull();
  });

  // Backend RemainingInvoiceRepository#lockIssuedPredecessor continues a base ONLY from an ISSUED
  // row with the same customer_quotation_id (null matches null). issue() supersedes every other
  // ISSUED row, so a SUPERSEDED-only group must NOT adopt a draft.
  const ACCOUNT = { user: { id: 5, role: 'account' }, sections: ACCOUNT_SECTIONS, canViewPricingRequests: false, canViewDocumentsTab: true };
  const groupOf = (id) => screen.getByTestId(`document-version-remaining-${id}`).closest('[data-testid="document-group"]');
  const idsIn = (group) => within(group).getAllByTestId(/^document-version-remaining-/).map((e) => e.getAttribute('data-testid'));

  it('places a DRAFT remaining invoice on top of the group holding an ISSUED row with the same customerQuotationId', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({
      remainingInvoices: [
        { id: 41, baseNumber: 'GLR6900001', version: 1, docNumber: 'GLR6900001-1', status: 'SUPERSEDED', customerQuotationId: 9 },
        { id: 42, baseNumber: 'GLR6900001', version: 2, docNumber: 'GLR6900001-2', status: 'ISSUED', customerQuotationId: 9 },
        { id: 43, baseNumber: null, version: 3, docNumber: null, status: 'DRAFT', customerQuotationId: 9 },
        { id: 44, baseNumber: null, version: 1, docNumber: null, status: 'DRAFT', customerQuotationId: 5 },
      ],
    });
    renderRegister(ACCOUNT);

    await screen.findByText('GLR6900001-2');
    expect(idsIn(groupOf(42))).toEqual(['document-version-remaining-43', 'document-version-remaining-42', 'document-version-remaining-41']);
    expect(groupOf(44)).not.toBe(groupOf(42)); // no ISSUED match -> stands alone
  });

  it('a DRAFT does NOT join a group whose rows are all SUPERSEDED, even with a matching customerQuotationId', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({
      remainingInvoices: [
        { id: 41, baseNumber: 'GLR6900001', version: 1, docNumber: 'GLR6900001-1', status: 'SUPERSEDED', customerQuotationId: 9 },
        { id: 43, baseNumber: null, version: 2, docNumber: null, status: 'DRAFT', customerQuotationId: 9 },
      ],
    });
    renderRegister(ACCOUNT);

    await screen.findByText('GLR6900001-1');
    expect(groupOf(43)).not.toBe(groupOf(41));
  });

  it('a null-customerQuotationId DRAFT joins an ISSUED null-customerQuotationId group (null matches null)', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({
      remainingInvoices: [
        { id: 42, baseNumber: 'GLR6900001', version: 1, docNumber: 'GLR6900001-1', status: 'ISSUED', customerQuotationId: null },
        { id: 43, baseNumber: null, version: 2, docNumber: null, status: 'DRAFT', customerQuotationId: null },
      ],
    });
    renderRegister(ACCOUNT);

    await screen.findByText('GLR6900001-1');
    expect(idsIn(groupOf(42))).toEqual(['document-version-remaining-43', 'document-version-remaining-42']);
  });

  it('direct: revisions -1/-2 of one base form ONE document; a different base is a separate document', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [
        { id: 21, number: 'QT-2026-0200-1', docStatus: 'SUPERSEDED', revisionNo: 1, grandTotal: 1, createdAt: '2026-05-01T00:00:00Z' },
        { id: 22, number: 'QT-2026-0200-2', docStatus: 'APPROVED', revisionNo: 2, grandTotal: 2, createdAt: '2026-05-02T00:00:00Z' },
        { id: 23, number: 'QT-2026-0300-1', docStatus: 'APPROVED', revisionNo: 1, grandTotal: 3, createdAt: '2026-05-03T00:00:00Z' },
      ],
    });
    renderRegister({ ...SALES });

    await screen.findByText('QT-2026-0300-1');
    expect(screen.getAllByTestId('document-group')).toHaveLength(2);
    const g = screen.getByTestId('document-version-direct-22').closest('[data-testid="document-group"]');
    expect(within(g).getByTestId('document-version-direct-21')).not.toBeNull();
  });

  it('direct base number strips exactly the row own revisionNo (a 3-digit revision still groups)', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [
        { id: 31, number: 'QT-A-100', docStatus: 'SUPERSEDED', revisionNo: 100, grandTotal: 1 },
        { id: 32, number: 'QT-A-101', docStatus: 'APPROVED', revisionNo: 101, grandTotal: 2 },
      ],
    });
    renderRegister({ ...SALES });
    await screen.findByText('QT-A-101');
    expect(screen.getAllByTestId('document-group')).toHaveLength(1);
  });

  // The id tie-break would mask a broken version sort, so the HIGHER id carries the LOWER version.
  it('orders versions by version, not id (chain revisionNo / legacy quotationVersion / direct revisionNo)', async () => {
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({
      items: [
        { id: 20, number: 'QT-C-1', docStatus: 'SUPERSEDED', quotationRevisionNo: 1, grandTotal: 1 },
        { id: 10, number: 'QT-C-2', docStatus: 'ISSUED', quotationRevisionNo: 2, grandTotal: 2 },
      ],
    });
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [
        { id: 95, number: 'QT-B-1', docStatus: 'SUPERSEDED', revisionNo: 1, grandTotal: 1 },
        { id: 85, number: 'QT-B-2', docStatus: 'APPROVED', revisionNo: 2, grandTotal: 2 },
      ],
    });
    renderRegister({
      ...SALES,
      pricingRequests: CHAIN_PR,
      legacyQuotations: [
        { id: 90, number: 'QT-L-01', recipientType: 'OWNER', quotationVersion: 1, docStatus: 'SUPERSEDED', totalAmount: 1 },
        { id: 80, number: 'QT-L-02', recipientType: 'OWNER', quotationVersion: 2, docStatus: 'ISSUED', totalAmount: 2 },
      ],
    });

    await screen.findByTestId('document-version-direct-85');
    await screen.findByTestId('document-version-chain-10');
    await screen.findByTestId('document-version-legacy-80');
    const [c2, c1, l2, l1, d2, d1] = positionsInDom([
      'document-version-chain-10', 'document-version-chain-20', 'document-version-legacy-80',
      'document-version-legacy-90', 'document-version-direct-85', 'document-version-direct-95',
    ]);
    expect(c2).toBeLessThan(c1);
    expect(l2).toBeLessThan(l1);
    expect(d2).toBeLessThan(d1);
  });

  it('groups legacy rows with no recipientType together as UNSPECIFIED', async () => {
    renderRegister({
      ...SALES,
      legacyQuotations: [
        { id: 71, number: 'QT-2026-0711', recipientType: null, quotationVersion: 1, docStatus: 'SUPERSEDED', totalAmount: 1 },
        { id: 72, number: 'QT-2026-0722', recipientType: null, quotationVersion: 2, docStatus: 'ISSUED', totalAmount: 2 },
      ],
    });
    await screen.findByText('QT-2026-0722');
    expect(screen.getAllByTestId('document-group')).toHaveLength(1);
  });

  it('pins the UNSPECIFIED fallback: a null-recipientType legacy row and an explicit UNSPECIFIED one are ONE document', async () => {
    renderRegister({
      ...SALES,
      legacyQuotations: [
        { id: 71, number: 'QT-2026-0711', recipientType: null, quotationVersion: 1, docStatus: 'SUPERSEDED', totalAmount: 1 },
        { id: 72, number: 'QT-2026-0722', recipientType: 'UNSPECIFIED', quotationVersion: 2, docStatus: 'ISSUED', totalAmount: 2 },
      ],
    });
    await screen.findByText('QT-2026-0722');
    expect(screen.getAllByTestId('document-group')).toHaveLength(1);
  });

  it('holds legacy rows back while the pricing requests themselves are still loading, then shows a chain-shared row exactly once', async () => {
    let resolveChain;
    api.pricingRequests.listCustomerQuotations.mockReturnValue(new Promise((r) => { resolveChain = r; }));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const legacy = [{ id: 3, number: 'QT-2026-0016-3', docStatus: 'ISSUED', totalAmount: 300 }];
    const ui = (prs, prLoading) => (
      <QueryClientProvider client={queryClient}>
        <DealDocumentRegister ticketId={701} summary={{ status: 'price_proposed', fulfillmentStatus: null }}
          {...SALES} pricingRequests={prs} pricingRequestsLoading={prLoading} legacyQuotations={legacy}
          attachments={[]} attachLoading={false} />
      </QueryClientProvider>
    );
    const { rerender } = render(ui([], true));
    await screen.findByTestId('register-quotations');
    expect(screen.queryByText('QT-2026-0016-3')).toBeNull();

    rerender(ui(CHAIN_PR, false)); // PRs arrive, chain query pending
    expect(screen.queryByText('QT-2026-0016-3')).toBeNull();

    resolveChain({ items: [{ id: 3, number: 'QT-2026-0016-3', docStatus: 'ISSUED', quotationRevisionNo: 3, grandTotal: 300 }] });
    await screen.findByTestId('document-version-chain-3');
    expect(screen.getAllByText('QT-2026-0016-3')).toHaveLength(1);
  });

  it('does not stay stuck when pricing requests finish loading with none eligible (legacy rows show)', async () => {
    renderRegister({ ...SALES, pricingRequests: [], pricingRequestsLoading: false,
      legacyQuotations: [{ id: 5, number: 'QT-2026-0500', docStatus: 'ISSUED', totalAmount: 1 }] });
    expect(await screen.findByText('QT-2026-0500')).not.toBeNull();
  });

  // ── Recipient chip: who each quotation DOCUMENT is for (current/top row only) ──────────────
  it('chain: a DESIGNER document shows one ผู้ออกแบบ chip on its current row, not on older versions', async () => {
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({
      items: [
        { id: 1, number: 'QT-R-1', docStatus: 'SUPERSEDED', quotationRevisionNo: 1, recipientType: 'DESIGNER', grandTotal: 1 },
        { id: 2, number: 'QT-R-2', docStatus: 'ISSUED', quotationRevisionNo: 2, recipientType: 'DESIGNER', grandTotal: 2 },
      ],
    });
    renderRegister({ ...SALES, pricingRequests: CHAIN_PR });

    await screen.findByTestId('document-version-chain-2');
    expect(screen.getAllByTestId('document-recipient')).toHaveLength(1);
    const chip = within(screen.getByTestId('document-version-chain-2')).getByTestId('document-recipient');
    expect(chip.textContent).toBe('สำหรับ: ผู้ออกแบบ');
    expect(within(screen.getByTestId('document-version-chain-1')).queryByTestId('document-recipient')).toBeNull();
  });

  it('legacy: OWNER shows เจ้าของ; a null recipientType shows ไม่ระบุ', async () => {
    renderRegister({
      ...SALES,
      legacyQuotations: [
        { id: 81, number: 'QT-2026-0810', recipientType: 'OWNER', quotationVersion: 1, docStatus: 'ISSUED', totalAmount: 1 },
        { id: 82, number: 'QT-2026-0820', recipientType: null, quotationVersion: 1, docStatus: 'ISSUED', totalAmount: 2 },
      ],
    });
    await screen.findByTestId('document-version-legacy-81');
    expect(within(screen.getByTestId('document-version-legacy-81')).getByTestId('document-recipient').textContent).toContain('เจ้าของ');
    expect(within(screen.getByTestId('document-version-legacy-82')).getByTestId('document-recipient').textContent).toContain('ไม่ระบุ');
  });

  it('direct: a PRICING_REQUEST-origin row shows its own recipient (ผู้ออกแบบ)', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [{ id: 91, number: 'QT-2026-0910-1', docStatus: 'APPROVED', revisionNo: 1, grandTotal: 1, origin: 'PRICING_REQUEST', recipientType: 'DESIGNER', recipientLabel: 'X' }],
    });
    renderRegister({ ...SALES });
    const row = within(await screen.findByTestId('document-version-direct-91'));
    expect(row.getByTestId('document-recipient').textContent).toContain('ผู้ออกแบบ');
  });

  it('direct: a DEAL_DIRECT row (UNSPECIFIED) shows ไม่ระบุ, same wording as the other families', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [{ id: 92, number: 'QT-2026-0920-1', docStatus: 'APPROVED', revisionNo: 1, grandTotal: 1, origin: 'DEAL_DIRECT', recipientType: 'UNSPECIFIED' }],
    });
    renderRegister({ ...SALES });
    const row = within(await screen.findByTestId('document-version-direct-92'));
    expect(row.getByTestId('document-recipient').textContent).toContain('ไม่ระบุ');
    expect(row.queryByText(/ไม่ระบุผู้รับ/)).toBeNull();
  });

  // Deploy safety: the frontend ships before the backend image, so an older backend omits the
  // field. No chip is better than a wrong one.
  it('direct: recipientType absent (older backend) renders NO chip', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [{ id: 93, number: 'QT-2026-0930-1', docStatus: 'APPROVED', revisionNo: 1, grandTotal: 1, origin: 'DEAL_DIRECT' }],
    });
    renderRegister({ ...SALES });
    const row = within(await screen.findByTestId('document-version-direct-93'));
    expect(row.queryByTestId('document-recipient')).toBeNull();
  });

  it('the chip exposes its context to screen readers as sr-only text, not an aria-label on a span', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [{ id: 94, number: 'QT-2026-0940-1', docStatus: 'APPROVED', revisionNo: 1, grandTotal: 1, recipientType: 'OWNER' }],
    });
    renderRegister({ ...SALES });
    const chip = (await screen.findByTestId('document-recipient'));
    expect(chip.textContent).toBe('สำหรับ: เจ้าของ');
    expect(chip.getAttribute('aria-label')).toBeNull();
    expect(chip.querySelector('.sr-only').textContent).toBe('สำหรับ: ');
  });

  it('deposit notice, remaining invoice and attachment rows carry no recipient chip', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ id: 31, status: 'ISSUED', docNumber: 'DN-1', version: 1, depositPercent: 0.5, recipientType: 'OWNER' }],
    });
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({
      remainingInvoices: [{ id: 41, baseNumber: 'GLR1', version: 1, docNumber: 'GLR1-1', status: 'ISSUED', recipientType: 'OWNER' }],
    });
    renderRegister({ ...ACCOUNT, attachments: [{ id: 1, fileName: 'po.pdf', attachType: 'PO' }] });
    await screen.findByText('GLR1-1');
    await screen.findByText('DN-1');
    expect(screen.getByText('po.pdf')).not.toBeNull();
    expect(screen.queryAllByTestId('document-recipient')).toHaveLength(0);
  });

  it('keeps already-shown legacy rows visible when a NEW pricing request query starts loading (hold-back is first-settle only)', async () => {
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const legacy = [{ id: 9001, number: 'QT-2026-0901', docStatus: 'ISSUED', totalAmount: 1 }];
    const ui = (prs) => (
      <QueryClientProvider client={queryClient}>
        <DealDocumentRegister ticketId={701} summary={{ status: 'price_proposed', fulfillmentStatus: null }}
          {...SALES} pricingRequests={prs} legacyQuotations={legacy} attachments={[]} attachLoading={false} />
      </QueryClientProvider>
    );
    const { rerender } = render(ui([{ id: 501, ticketCreatedById: 1, status: 'APPROVED_FOR_QUOTATION' }]));
    await screen.findByText('QT-2026-0901');

    api.pricingRequests.listCustomerQuotations.mockReturnValue(new Promise(() => {}));
    rerender(ui([
      { id: 501, ticketCreatedById: 1, status: 'APPROVED_FOR_QUOTATION' },
      { id: 502, ticketCreatedById: 1, status: 'APPROVED_FOR_QUOTATION' },
    ]));
    expect(screen.getByText('QT-2026-0901')).not.toBeNull();
  });

  // Wrong-way-round: viewers failing `canViewQuotations && canViewDealQuotation(user)` must never
  // trigger the direct-quotation fetch (it would 403).
  it.each([
    ['account', { id: 5, role: 'account' }, ACCOUNT_SECTIONS, false],
    ['import', { id: 7, role: 'import' }, IMPORT_SECTIONS, true],
  ])('does not fetch direct quotations for %s', async (_name, user, sections, canViewPricingRequests) => {
    renderRegister({ user, sections, canViewPricingRequests, canViewDocumentsTab: true });
    await screen.findByTestId('deal-document-register');
    await waitFor(() => expect(api.depositNotices.listByTicket).toHaveBeenCalledTimes(user.role === 'account' ? 1 : 0));
    expect(api.dealQuotations.listForTicket).not.toHaveBeenCalled();
  });

  it('lists deposit notice versions newest first even when the API returns them ascending', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [
        { id: 31, status: 'SUPERSEDED', docNumber: 'DN-2026-0001', version: 1, depositPercent: 0.5 },
        { id: 32, status: 'SUPERSEDED', docNumber: 'DN-2026-0007', version: 2, depositPercent: 0.5 },
        { id: 33, status: 'ISSUED', docNumber: 'DN-2026-0011', version: 3, depositPercent: 0.5 },
      ],
    });
    renderRegister({ user: { id: 5, role: 'account' }, sections: ACCOUNT_SECTIONS, canViewPricingRequests: false, canViewDocumentsTab: true });

    await screen.findByText('DN-2026-0011');
    const groups = within(screen.getByTestId('register-deposit')).getAllByTestId('document-group');
    expect(groups).toHaveLength(1); // one deposit notice per deal, many versions
    const [p3, p2, p1] = positionsInDom(['document-version-deposit-33', 'document-version-deposit-32', 'document-version-deposit-31']);
    expect(p3).toBeGreaterThanOrEqual(0);
    expect(p3).toBeLessThan(p2);
    expect(p2).toBeLessThan(p1);
  });

  it('lists stored remaining-invoice versions newest first, downloads by id, and shows a DRAFT as ร่าง without a download', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({
      remainingInvoices: [
        { id: 41, baseNumber: 'GLR6900001', version: 1, docNumber: 'GLR6900001-1', status: 'SUPERSEDED', supersededById: 42, grandTotal: 100 },
        { id: 42, baseNumber: 'GLR6900001', version: 2, docNumber: 'GLR6900001-2', status: 'ISSUED', grandTotal: 200 },
        { id: 43, baseNumber: 'GLR6900002', version: 1, docNumber: null, status: 'DRAFT', grandTotal: 50 },
      ],
    });
    api.storedRemainingInvoices.download.mockResolvedValue(new Blob(['x']));
    renderRegister({ user: { id: 5, role: 'account' }, sections: ACCOUNT_SECTIONS, canViewPricingRequests: false, canViewDocumentsTab: true });

    await screen.findByText('GLR6900001-2');
    expect(screen.getByText('GLR6900001-1')).not.toBeNull();
    const [p2, p1] = positionsInDom(['document-version-remaining-42', 'document-version-remaining-41']);
    expect(p2).toBeGreaterThanOrEqual(0);
    expect(p2).toBeLessThan(p1);

    fireEvent.click(within(screen.getByTestId('document-version-remaining-41')).getByRole('button', { name: 'Excel' }));
    await waitFor(() => expect(api.storedRemainingInvoices.download).toHaveBeenCalledWith(41));

    const draft = within(screen.getByTestId('document-version-remaining-43'));
    expect(draft.getByText(/ร่าง/)).not.toBeNull();
    expect(draft.queryByRole('button')).toBeNull();
  });

  // Wrong-way-round: same gate as the deposit notice (sections.depositNotice) — import is refused
  // outright, so the stored-invoice query must never fire for it.
  it('does not fetch stored remaining invoices for a viewer without sections.depositNotice', async () => {
    renderRegister({ user: { id: 7, role: 'import' }, sections: IMPORT_SECTIONS, canViewPricingRequests: true, canViewDocumentsTab: true });
    expect(await screen.findByTestId('register-attachments')).not.toBeNull();
    expect(api.storedRemainingInvoices.listForTicket).not.toHaveBeenCalled();
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

    const section = within(await screen.findByTestId('register-deposit'));

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

    // Newest version first now (v2 = id 12, then v1 = id 11), regardless of API order.
    fireEvent.click(pdfButtons[0]);
    await waitFor(() => expect(api.depositNotices.downloadPdf).toHaveBeenCalledWith(12));
    fireEvent.click(pdfButtons[1]);
    await waitFor(() => expect(api.depositNotices.downloadPdf).toHaveBeenCalledWith(11));
    fireEvent.click(xlsxButtons[0]);
    await waitFor(() => expect(api.depositNotices.downloadXlsx).toHaveBeenCalledWith(12));
    fireEvent.click(xlsxButtons[1]);
    await waitFor(() => expect(api.depositNotices.downloadXlsx).toHaveBeenCalledWith(11));
  });

  // ── Round 9: two sections instead of one combined block ───────────────────────────────────
  it('account sees TWO sections: ใบแจ้งยอดมัดจำ then ใบแจ้งหนี้ส่วนที่เหลือ, each with its own empty state', async () => {
    renderRegister({ ...ACCOUNT });

    const deposit = await screen.findByTestId('register-deposit');
    const remaining = await screen.findByTestId('register-remaining-invoice');
    expect(deposit.compareDocumentPosition(remaining) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(deposit).getByText('ใบแจ้งยอดมัดจำ')).not.toBeNull();
    expect(within(remaining).getByText('ใบแจ้งหนี้ส่วนที่เหลือ')).not.toBeNull();
    expect(await within(deposit).findByText('ยังไม่มีใบแจ้งยอดมัดจำสำหรับดีลนี้')).not.toBeNull();
    // Empty stored list: the readiness row stays at the top, the empty sentence sits beneath it.
    expect(await within(remaining).findByText('ยังไม่มีใบแจ้งหนี้ส่วนที่เหลือสำหรับดีลนี้')).not.toBeNull();
    expect(within(remaining).getByText('ออกใบแจ้งหนี้ส่วนที่เหลือ')).not.toBeNull();
    expect(screen.queryByTestId('register-deposit-and-invoice')).toBeNull();
  });

  it('deposit groups live only in register-deposit; remaining groups and readiness only in register-remaining-invoice', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ id: 31, status: 'ISSUED', docNumber: 'DN-1', version: 1, depositPercent: 0.5 }],
    });
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({
      remainingInvoices: [
        { id: 41, baseNumber: 'GLR1', version: 1, docNumber: 'GLR1-1', status: 'SUPERSEDED' },
        { id: 42, baseNumber: 'GLR1', version: 2, docNumber: 'GLR1-2', status: 'ISSUED' },
        { id: 43, baseNumber: 'GLR2', version: 1, docNumber: 'GLR2-1', status: 'ISSUED' },
      ],
    });
    renderRegister({ ...ACCOUNT, summary: { status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED' } });

    const deposit = within(await screen.findByTestId('register-deposit'));
    const remaining = within(await screen.findByTestId('register-remaining-invoice'));
    await deposit.findByText('DN-1');
    await remaining.findByText('GLR1-2');
    expect(deposit.queryByText('GLR1-2')).toBeNull();
    expect(remaining.queryByText('DN-1')).toBeNull();
    expect(remaining.getAllByTestId('document-group')).toHaveLength(2);
    expect(deposit.getAllByTestId('document-group')).toHaveLength(1);
    expect(remaining.getByText('พร้อมใช้งาน')).not.toBeNull();
    expect(deposit.queryByText('พร้อมใช้งาน')).toBeNull();
  });

  it('each section shows its own document count (remaining counts stored documents, not the readiness row)', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({
      remainingInvoices: [
        { id: 41, baseNumber: 'GLR1', version: 1, docNumber: 'GLR1-1', status: 'SUPERSEDED' },
        { id: 42, baseNumber: 'GLR1', version: 2, docNumber: 'GLR1-2', status: 'ISSUED' },
        { id: 43, baseNumber: 'GLR2', version: 1, docNumber: 'GLR2-1', status: 'ISSUED' },
      ],
    });
    renderRegister({ ...ACCOUNT });
    const remaining = within(await screen.findByTestId('register-remaining-invoice'));
    expect(await remaining.findByText('2 เอกสาร')).not.toBeNull();
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

    const section = within(await screen.findByTestId('register-remaining-invoice'));
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
    const section = within(await screen.findByTestId('register-remaining-invoice'));
    if (ready) {
      expect(section.getByText('พร้อมใช้งาน')).not.toBeNull();
      expect(section.getByRole('button', { name: 'Excel' })).not.toBeNull();
    } else {
      expect(section.getByText('รอขั้นตอน')).not.toBeNull();
      expect(section.queryByRole('button', { name: 'Excel' })).toBeNull();
    }
  });
});

// CR-1 (GLA-167) R6 / R9: the deal เอกสาร tab gets a 5th section, ใบขอซื้อ (รายโรงงาน) — one row
// per IR (one IR per factory): number or ฉบับร่าง, factory, status, PDF. Read gate mirrors
// ImportRequestService#requireRead: import / CEO / sales_manager, or the owning sales rep.
describe('DealDocumentRegister — ใบขอซื้อ (รายโรงงาน) section (CR-1)', () => {
  const OWNER = { user: { id: 1, role: 'sales' }, sections: SALES_SECTIONS, canViewPricingRequests: true, canViewDocumentsTab: true, summary: { status: 'order_received', createdById: 1 } };
  const ir = (over = {}) => ({
    id: 11, ticketId: 701, factoryId: 1, factoryName: 'Cotto Industry', version: 1, status: 'ISSUED', docNumber: 'IR26001', issuedAt: '2026-09-10T03:00:00Z', ...over,
  });

  beforeEach(() => {
    vi.clearAllMocks();
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] });
    api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] });
    api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
    api.storedImportRequests.download.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }));
    globalThis.URL.createObjectURL = vi.fn(() => 'blob:ir');
    globalThis.URL.revokeObjectURL = vi.fn();
  });

  it('lists one row per factory IR: number (or ฉบับร่าง), factory, status', async () => {
    api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [
      ir(),
      ir({ id: 12, factoryId: 2, factoryName: 'Panaria', status: 'DRAFT', docNumber: null }),
    ] });
    renderRegister(OWNER);

    const section = await screen.findByTestId('register-import-requests');
    expect(within(section).getByText('ใบขอซื้อ (รายโรงงาน)')).not.toBeNull();
    const rows = await within(section).findAllByTestId(/^document-version-ir-/);
    expect(rows).toHaveLength(2);
    const issued = within(section).getByTestId('document-version-ir-11');
    expect(issued.textContent).toContain('IR26001');
    expect(issued.textContent).toContain('Cotto Industry');
    const draft = within(section).getByTestId('document-version-ir-12');
    expect(draft.textContent).toContain('ฉบับร่าง');
    // the draft has no number: the number slot is a dash and ฉบับร่าง appears ONCE (the status chip)
    expect(draft.textContent.match(/ฉบับร่าง/g)).toHaveLength(1);
    expect(draft.textContent).toContain('—');
    expect(draft.textContent).toContain('Panaria');
  });

  it('does not list SUPERSEDED rows (an old revision is not a current IR)', async () => {
    api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [ir(), ir({ id: 13, version: 0, status: 'SUPERSEDED', docNumber: 'IR25999' })] });
    renderRegister(OWNER);
    const section = await screen.findByTestId('register-import-requests');
    await within(section).findByTestId('document-version-ir-11');
    expect(within(section).queryByTestId('document-version-ir-13')).toBeNull();
  });

  it('each row downloads its PDF', async () => {
    api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [ir()] });
    renderRegister(OWNER);
    const row = await screen.findByTestId('document-version-ir-11');
    fireEvent.click(within(row).getByRole('button', { name: 'PDF' }));
    await waitFor(() => expect(api.storedImportRequests.download).toHaveBeenCalledWith(11, undefined));
  });

  it('shows an honest empty line when the deal has no IR yet', async () => {
    renderRegister(OWNER);
    const section = await screen.findByTestId('register-import-requests');
    expect(await within(section).findByText('ยังไม่มีใบขอซื้อสำหรับดีลนี้')).not.toBeNull();
  });

  it('import (read-only), CEO and sales_manager see the section; a non-owning sales rep and account do not, and never fetch', async () => {
    api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [ir()] });
    for (const user of [{ id: 5, role: 'import' }, { id: 6, role: 'ceo' }, { id: 7, role: 'sales_manager' }]) {
      const { unmount } = renderRegister({ ...OWNER, user, sections: IMPORT_SECTIONS, canViewDocumentsTab: false, summary: { status: 'order_received', createdById: 1 } });
      expect(await screen.findByTestId('register-import-requests')).not.toBeNull();
      unmount();
    }
    vi.clearAllMocks();
    for (const user of [{ id: 99, role: 'sales' }, { id: 8, role: 'account' }]) {
      const { unmount } = renderRegister({ ...OWNER, user, sections: IMPORT_SECTIONS, canViewDocumentsTab: false });
      await screen.findByTestId('deal-document-register');
      expect(screen.queryByTestId('register-import-requests')).toBeNull();
      unmount();
    }
    expect(api.storedImportRequests.listForTicket).not.toHaveBeenCalled();
  });
});
