import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../../api/index.js';
import { DeliveryRoundsPanel } from './DeliveryRoundsPanel.jsx';
import { RoundFilesSection } from './RoundFilesSection.jsx';
import { deliveryPayload } from './RecordDeliverySheet.jsx';
import { addDaysIso, bangkokTodayIso } from '../../../utils/format.js';

globalThis.React = React;

vi.mock('../../../api/index.js', async (importOriginal) => ({
  ...(await importOriginal()),
  api: {
    deliveryRequests: { list: vi.fn(), createDraft: vi.fn(), update: vi.fn(), addFile: vi.fn(), send: vi.fn(), forward: vi.fn(), sendBack: vi.fn(), attachTaxInvoice: vi.fn(), confirmInvoice: vi.fn(), voidInvoice: vi.fn(), recordDelivery: vi.fn() },
    attachments: { fileUrl: (id) => `/f/${id}`, delete: vi.fn(), fileBlob: vi.fn() },
    tickets: { get: vi.fn(), recordDelivery: vi.fn(), deliveryAllowance: vi.fn() },
    documentChain: { get: vi.fn() },
    dealQuotations: { downloadPdf: vi.fn() },
    storedImportRequests: { listForTicket: vi.fn() },
    depositNotices: { listByTicket: vi.fn() },
    storedRemainingInvoices: { listForTicket: vi.fn() },
  },
}));

const ORDER = { ticketId: 701, code: 'DL-2026-0001', displayCode: 'DL-2026-0001-1', orderNo: 1, customerName: 'ลูกค้า', projectName: 'โครงการ', parentCode: null };
const QUOTATION = { id: 11, number: 'QT-2026-0001-1', total: 107000, docStatus: 'APPROVED', depositBlockedReason: null };
const viewer = (canEdit) => ({ canEdit, canSend: canEdit, canReview: false, canIssue: false, canRecordDelivery: false });
const round = (status, extra = {}) => ({
  id: 5, roundNo: 1, status, quotationId: 11, quotationNumber: QUOTATION.number, note: null, returnReasonCodes: [], returnReasonText: null,
  requestedByName: 'คุณขาย', sentAt: '2026-11-19T03:42:00Z', forwardedByName: null, forwardedAt: null, invoice: null,
  lines: [{ itemId: 1, description: 'กระเบื้อง', unit: 'แผ่น', coveredQty: 300, deliveredQty: 0 }], files: [],
  viewer: viewer(status === 'DRAFT'), ...extra,
});

const CHAIN = {
  deposit: { required: true, state: 'ISSUED', blockedReason: null, issuedNoticeId: 31, docNumber: 'DN-2026-0007', issueDate: '2026-11-10', draftExists: false },
  remaining: { state: 'WAITING', blockedReason: 'DEPOSIT_NOT_RECEIVED', canCreate: false, invoiceId: null, docNumber: null, draftExists: false },
  viewer: { mayWriteDepositNotice: true, mayWriteRemainingInvoice: true },
};

function Where() {
  const loc = useLocation();
  return <output data-testid="where">{loc.pathname}{loc.search}</output>;
}

function renderPanel(props = {}, url = '/') {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[url]}><DeliveryRoundsPanel ticketId={701} {...props} /><Where /></MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  api.tickets.get.mockResolvedValue({ ticket: { items: [{ id: 1, qty: 500, qtyDelivered: 200 }] } });
  api.documentChain.get.mockResolvedValue({ documentChain: CHAIN });
  api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [{ id: 31, status: 'ISSUED', version: 1, docNumber: 'DN-2026-0007', issueDate: '2026-11-10', reference: 'QT-2026-0001-1' }] });
  api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] });
  api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [{ id: 41, status: 'ISSUED', docNumber: 'IR-2026-0021', factoryName: 'โรงงานตัวอย่าง' }] });
});

describe('DeliveryRoundsPanel', () => {
  it('sending the request moves the round to step 2 (คลังตรวจเอกสาร) and the sheet has both attach controls', async () => {
    api.deliveryRequests.list
      .mockResolvedValueOnce({ order: ORDER, rounds: [], approvedQuotations: [QUOTATION], canStart: true, startBlockedReason: null })
      .mockResolvedValue({ order: ORDER, rounds: [round('SENT', { note: 'ส่งหน้างาน' })], approvedQuotations: [QUOTATION], canStart: false, startBlockedReason: null });
    api.deliveryRequests.createDraft.mockResolvedValue({ round: round('DRAFT') });
    api.deliveryRequests.update.mockResolvedValue({ round: round('DRAFT') });
    api.deliveryRequests.send.mockResolvedValue({ round: round('SENT') });

    renderPanel();
    expect(await screen.findByTestId('rounds-empty')).toBeTruthy();
    fireEvent.click(screen.getByTestId('request-delivery-button'));

    const sheet = await screen.findByTestId('delivery-request-sheet');
    // Phone: the camera input opens the camera; the picker takes several files.
    expect(within(sheet).getByTestId('camera-input').getAttribute('capture')).toBe('environment');
    expect(within(sheet).getByTestId('file-input').hasAttribute('multiple')).toBe(true);
    await waitFor(() => expect(within(sheet).getByTestId('send-to-warehouse').disabled).toBe(false));
    fireEvent.click(within(sheet).getByTestId('send-to-warehouse'));

    await waitFor(() => expect(api.deliveryRequests.send).toHaveBeenCalledWith(5, { systemDocs: [{ kind: 'QUOTATION', id: 11 }, { kind: 'DEPOSIT_NOTICE', id: 31 }, { kind: 'IMPORT_REQUEST', id: 41 }] }));
    await waitFor(() => expect(screen.getByTestId('round-step-2').getAttribute('data-state')).toBe('now'));
    expect(screen.getByTestId('round-step-1').getAttribute('data-state')).toBe('done');
    expect(screen.getByText('ฝ่ายขายส่งเรื่อง')).toBeTruthy();
    expect(screen.queryByTestId('request-delivery-button')).toBeNull();
  });

  it('an unconfirmed old deal: the draft has no lines yet, the sheet asks the deposit already received and sends it', async () => {
    const OLD = { ...ORDER, prePortal: false, unconfirmed: true, asksPreDeposit: true };
    const noLines = round('DRAFT', { lines: [] });
    api.deliveryRequests.list.mockResolvedValue({ order: OLD, rounds: [], approvedQuotations: [{ ...QUOTATION, preDepositMax: 100000 }], canStart: true, startBlockedReason: null });
    api.deliveryRequests.createDraft.mockResolvedValue({ round: noLines });
    api.deliveryRequests.update.mockResolvedValue({ round: noLines });
    api.deliveryRequests.send.mockResolvedValue({ round: round('SENT') });

    renderPanel();
    fireEvent.click(await screen.findByTestId('request-delivery-button'));
    const sheet = await screen.findByTestId('delivery-request-sheet');
    // Not the "every line is covered" dead end: the lines come from the quotation when sent.
    expect(await within(sheet).findByTestId('lines-from-quotation')).toBeTruthy();
    // The deposit is required, 0 is allowed, and more than the quotation total is refused before sending.
    await waitFor(() => expect(within(sheet).getByTestId('send-blocked-reason').textContent).toContain('ใส่มัดจำที่รับแล้ว'));
    expect(within(sheet).getByTestId('send-to-warehouse').disabled).toBe(true);
    const input = within(sheet).getByLabelText('มัดจำที่รับแล้วก่อนใช้ระบบ (บาท)');
    fireEvent.change(input, { target: { value: '999999' } });
    expect(within(sheet).getByTestId('send-blocked-reason').textContent).toContain('เกินยอดใบเสนอราคา');
    // An amount above 0 also needs the date it was received (พ.ศ. field, committed on blur).
    fireEvent.change(input, { target: { value: '500' } });
    expect(within(sheet).getByTestId('send-blocked-reason').textContent).toContain('ใส่วันที่ได้รับมัดจำ');
    fireEvent.change(input, { target: { value: '0' } });
    await waitFor(() => expect(within(sheet).getByTestId('send-to-warehouse').disabled).toBe(false));
    fireEvent.click(within(sheet).getByTestId('send-to-warehouse'));
    await waitFor(() => expect(api.deliveryRequests.send).toHaveBeenCalledWith(5, expect.objectContaining({ preDeposit: 0 })));
  });

  it('a blocked start shows its reason instead of the button', async () => {
    api.deliveryRequests.list.mockResolvedValue({
      order: ORDER, rounds: [], approvedQuotations: [], canStart: false, startBlockedReason: 'ยังไม่มีใบเสนอราคาที่อนุมัติแล้ว',
    });
    renderPanel();
    expect(await screen.findByTestId('request-blocked-reason')).toBeTruthy();
    expect(screen.queryByTestId('request-delivery-button')).toBeNull();
  });

  it('with the host showing the action, the panel has no button of its own and ?request=open opens the sheet', async () => {
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [], approvedQuotations: [QUOTATION], canStart: true, startBlockedReason: null });
    api.deliveryRequests.createDraft.mockResolvedValue({ round: round('DRAFT') });
    renderPanel({ showRequestButton: false }, '/?request=open');
    expect(await screen.findByTestId('delivery-request-sheet')).toBeTruthy();
    expect(screen.queryByTestId('request-delivery-button')).toBeNull();
  });

  it('existing documents are ticked and sent; a missing one has สร้าง, a blocked one shows its reason, and สร้าง leaves with returnTo', async () => {
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [], approvedQuotations: [QUOTATION], canStart: true, startBlockedReason: null });
    api.deliveryRequests.createDraft.mockResolvedValue({ round: round('DRAFT') });
    api.deliveryRequests.update.mockResolvedValue({ round: round('DRAFT') });
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] });
    api.documentChain.get.mockResolvedValue({ documentChain: { ...CHAIN, deposit: { ...CHAIN.deposit, issuedNoticeId: null, state: 'TO_CREATE', docNumber: null } } });
    renderPanel({ showRequestButton: false }, '/?request=open');

    const sheet = await screen.findByTestId('delivery-request-sheet');
    // The remaining invoice's generator refuses (deposit not yet received): its reason, no สร้าง.
    expect((await within(sheet).findByTestId('doc-blocked-REMAINING_INVOICE')).textContent).toContain('ออกได้หลังฝ่ายบัญชียืนยันรับมัดจำ');
    expect(within(sheet).queryByTestId('doc-create-REMAINING_INVOICE')).toBeNull();
    expect(within(sheet).getByLabelText('แนบ ใบเสนอราคา QT-2026-0001-1').checked).toBe(true);
    expect(within(sheet).getByLabelText('แนบ ใบขอซื้อ IR-2026-0021').checked).toBe(true);

    fireEvent.click(within(sheet).getByTestId('doc-create-DEPOSIT_NOTICE'));
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/tickets/701/deposit?returnTo=ship-request&quotation=11'));
    expect(api.deliveryRequests.update).toHaveBeenCalled();
  });

  it('every present document row has ดู that opens its PDF without toggling the tick, and an attached file name opens the file', async () => {
    const file = { attachmentId: 77, fileName: 'po.pdf', mimeType: 'application/pdf', fileSize: 10, kind: 'OTHER', uploaderSide: 'SALES', uploadedById: 1 };
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [], approvedQuotations: [QUOTATION], canStart: true, startBlockedReason: null });
    api.deliveryRequests.createDraft.mockResolvedValue({ round: round('DRAFT', { files: [file] }) });
    api.dealQuotations.downloadPdf.mockResolvedValue(new Blob(['%PDF-'], { type: 'application/pdf' }));
    const fakeTab = { location: { href: '' }, close: vi.fn() };
    const open = vi.spyOn(window, 'open').mockReturnValue(fakeTab);
    URL.createObjectURL = vi.fn(() => 'blob:doc');
    renderPanel({ showRequestButton: false }, '/?request=open');
    const sheet = await screen.findByTestId('delivery-request-sheet');
    const box = await within(sheet).findByLabelText('แนบ ใบเสนอราคา QT-2026-0001-1');
    expect(box.checked).toBe(true);
    fireEvent.click(within(sheet).getByLabelText('ดู ใบเสนอราคา QT-2026-0001-1'));
    await waitFor(() => expect(fakeTab.location.href).toBe('blob:doc'));
    expect(api.dealQuotations.downloadPdf).toHaveBeenCalledWith(11);
    expect(box.checked).toBe(true);
    expect(within(sheet).getByTestId('doc-view-IMPORT_REQUEST')).toBeTruthy();
    expect(within(sheet).getByTestId('attached-file-link').getAttribute('href')).toBe('/f/77');
    expect(within(sheet).getByTestId('attached-file-link').getAttribute('target')).toBe('_blank');
    open.mockRestore();
  });

  it('a notice naming no quotation of an order with two quotations is shown but not attached (the server rule)', async () => {
    const second = { ...QUOTATION, id: 12, number: 'QT-2026-0001-2' };
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [], approvedQuotations: [QUOTATION, second], canStart: true, startBlockedReason: null });
    api.deliveryRequests.createDraft.mockResolvedValue({ round: round('DRAFT') });
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [{ id: 31, status: 'ISSUED', version: 1, docNumber: 'DN-2026-0007', reference: 'PO-123' }] });
    renderPanel({ showRequestButton: false }, '/?request=open');

    const sheet = await screen.findByTestId('delivery-request-sheet');
    const row = await within(sheet).findByTestId('doc-row-DEPOSIT_NOTICE');
    expect(row.getAttribute('data-other')).toBe('true');
    expect(row.textContent).toContain('ไม่ระบุใบเสนอราคา');
    expect(within(sheet).queryByLabelText('แนบ ใบแจ้งยอดเงินรับมัดจำ DN-2026-0007')).toBeNull();
  });

  it('send waits for the order documents: loading and a failed IR read both keep it disabled, with a retry', async () => {
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [], approvedQuotations: [QUOTATION], canStart: true, startBlockedReason: null });
    api.deliveryRequests.createDraft.mockResolvedValue({ round: round('DRAFT') });
    api.storedImportRequests.listForTicket.mockRejectedValueOnce(new Error('boom')).mockResolvedValue({ importRequests: [{ id: 41, status: 'ISSUED', docNumber: 'IR-2026-0021' }] });
    renderPanel({ showRequestButton: false }, '/?request=open');

    const sheet = await screen.findByTestId('delivery-request-sheet');
    expect(await within(sheet).findByTestId('docs-error')).toBeTruthy();
    expect(within(sheet).getByTestId('send-to-warehouse').disabled).toBe(true);
    expect(within(sheet).getByTestId('send-blocked-reason').textContent).toContain('โหลดเอกสารของงานนี้ไม่สำเร็จ');

    fireEvent.click(within(sheet).getByText('ลองใหม่'));
    await waitFor(() => expect(within(sheet).getByLabelText('แนบ ใบขอซื้อ IR-2026-0021')).toBeTruthy());
    await waitFor(() => expect(within(sheet).getByTestId('send-to-warehouse').disabled).toBe(false));
  });

  it('the warehouse reads no deal, sees one primary (forward) and sends back only with a reason', async () => {
    const mine = { attachmentId: 9, fileName: 'หน้างาน.jpg', mimeType: 'image/jpeg', fileSize: 131072, kind: 'OTHER', uploaderSide: 'WAREHOUSE', uploadedById: 77 };
    const sales = { attachmentId: 8, fileName: 'สลิป.jpg', mimeType: 'image/jpeg', fileSize: 2048, kind: 'OTHER', uploaderSide: 'SALES', uploadedById: 5 };
    const reviewing = round('SENT', { files: [sales, mine], viewer: { ...viewer(false), canReview: true } });
    api.deliveryRequests.list.mockResolvedValue({ order: { ...ORDER, ownerName: 'คุณขาย' }, rounds: [reviewing], approvedQuotations: [], canStart: false, startBlockedReason: 'x' });
    api.deliveryRequests.forward.mockResolvedValue({ round: round('FORWARDED') });
    api.deliveryRequests.sendBack.mockResolvedValue({ round: round('RETURNED') });
    renderPanel({ warehouseUserId: 77 });

    expect(await screen.findByTestId('forward-button')).toBeTruthy();
    expect(api.tickets.get).not.toHaveBeenCalled();
    expect(screen.queryByTestId('delivered-summary')).toBeNull();
    expect(screen.getByText('ถึงคิวคุณ')).toBeTruthy();
    expect(screen.getByLabelText('เอา หน้างาน.jpg ออก')).toBeTruthy();
    expect(screen.queryByLabelText('เอา สลิป.jpg ออก')).toBeNull();
    expect(screen.getByText('ฝ่ายขายแนบ · 2 KB')).toBeTruthy();

    // Closing the sheet with its X leaves the panel standing (the close click's event is not a notice to render).
    fireEvent.click(screen.getByTestId('send-back-button'));
    fireEvent.click(within(await screen.findByTestId('send-back-sheet')).getByLabelText('ปิด'));
    await waitFor(() => expect(screen.queryByTestId('send-back-sheet')).toBeNull());
    expect(screen.queryByTestId('forward-error')).toBeNull();
    expect(screen.getByTestId('forward-button')).toBeTruthy();

    fireEvent.click(screen.getByTestId('send-back-button'));
    const sheet = await screen.findByTestId('send-back-sheet');
    expect(within(sheet).getByText(/เรื่องจะกลับไปที่คุณขาย/)).toBeTruthy();
    expect(within(sheet).getByTestId('send-back-submit').disabled).toBe(true);
    fireEvent.click(within(sheet).getByLabelText('หลักฐานการชำระเงิน'));
    fireEvent.click(within(sheet).getByTestId('send-back-submit'));
    await waitFor(() => expect(api.deliveryRequests.sendBack).toHaveBeenCalledWith(5, { reasonCodes: ['PAYMENT_PROOF'], detail: null }));

    // Forward is confirmed first; a 409 stays on screen after the refetch hides the review block.
    api.deliveryRequests.forward.mockRejectedValue(Object.assign(new Error('เรื่องนี้ถูกดำเนินการแล้ว'), { status: 409 }));
    fireEvent.click(screen.getByTestId('forward-button'));
    expect(api.deliveryRequests.forward).not.toHaveBeenCalled();
    fireEvent.click(within(await screen.findByTestId('forward-sheet')).getByTestId('forward-confirm'));
    await waitFor(() => expect(api.deliveryRequests.forward).toHaveBeenCalledWith(5));
    expect((await screen.findByTestId('forward-error')).textContent).toContain('ถูกดำเนินการแล้ว');
    expect(screen.queryByTestId('forward-sheet')).toBeNull();
  });

  it('a warehouse user with no sent round gets a plain message, not a retry', async () => {
    api.deliveryRequests.list.mockRejectedValue(Object.assign(new Error('x'), { status: 403 }));
    renderPanel({ warehouseUserId: 77 });
    expect(await screen.findByTestId('rounds-none')).toBeTruthy();
    expect(screen.queryByText('ลองใหม่')).toBeNull();
  });

  it('a returned round shows sales the reason, and the one primary reads แก้ไขแล้วส่งอีกครั้ง', async () => {
    const returned = round('RETURNED', { returnReasonCodes: ['PAYMENT_PROOF'], returnReasonText: 'สลิปไม่ชัด', viewer: viewer(true) });
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [returned], approvedQuotations: [QUOTATION], canStart: false, startBlockedReason: null });
    api.deliveryRequests.createDraft.mockResolvedValue({ round: returned });
    renderPanel();

    const alert = await screen.findByTestId('returned-reason');
    expect(alert.textContent).toContain('หลักฐานการชำระเงิน · สลิปไม่ชัด');
    expect(within(alert).queryByRole('button')).toBeNull();
    expect(screen.getByTestId('request-delivery-button').textContent).toBe('แก้ไขแล้วส่งอีกครั้ง');
    fireEvent.click(screen.getByTestId('request-delivery-button'));
    const sheet = await screen.findByTestId('delivery-request-sheet');
    expect(within(sheet).getByTestId('sheet-returned-reason').textContent).toContain('หลักฐานการชำระเงิน · สลิปไม่ชัด');
  });

  it('the invoice issuer reads no deal, attaches the PDF, checks what was read, confirms; the round then reads as invoiced', async () => {
    const issuing = { viewer: { ...viewer(false), canIssue: true }, customerName: 'บริษัท ผู้ซื้อจริง จำกัด', taxId: '0100000000999', branch: 'สาขา 2', customerFromDeal: false, forwardedByName: 'คุณคลัง', forwardedAt: '2026-11-19T04:15:00Z' };
    const invoiceFile = { attachmentId: 21, fileName: 'IV2611-0042.pdf', mimeType: 'application/pdf', fileSize: 148480, kind: 'INVOICE', uploaderSide: 'ISSUER', uploadedById: 90 };
    const list = (rounds) => ({ order: ORDER, rounds, approvedQuotations: [], canStart: false, startBlockedReason: 'x' });
    api.deliveryRequests.list
      .mockResolvedValueOnce(list([round('FORWARDED', issuing)]))
      .mockResolvedValueOnce(list([round('FORWARDED', { ...issuing, files: [invoiceFile] })]))
      .mockResolvedValue(list([round('INVOICED', {
        viewer: viewer(false), forwardedByName: 'คุณคลัง', forwardedAt: '2026-11-19T04:15:00Z', files: [invoiceFile],
        invoice: { number: 'IV2611-0042', date: '2026-11-19', total: 85065, recordedAmount: 14177.5, receiptId: 3, attachmentId: 21, invoicedByName: 'คุณออกบิล', invoicedAt: '2026-11-19T07:03:00Z' },
      })]));
    api.deliveryRequests.attachTaxInvoice.mockResolvedValue({ attachmentId: 21, read: { invoiceNumber: 'IV2611-0042', invoiceDate: '2026-11-19', total: 85065 }, readOk: true });
    api.deliveryRequests.confirmInvoice.mockResolvedValue({ round: round('INVOICED') });
    renderPanel({ dealless: true });

    expect(await screen.findByTestId('tax-invoice-step')).toBeTruthy();
    // the buyer to type into Express comes from the quotation: name, tax id and branch, each with its own copy button
    expect(screen.getByTestId('invoice-buyer-name').textContent).toBe('บริษัท ผู้ซื้อจริง จำกัด');
    expect(screen.getByTestId('invoice-buyer-taxId').textContent).toBe('0100000000999');
    expect(screen.getByTestId('invoice-buyer-branch').textContent).toBe('สาขา 2');
    expect(screen.getByTestId('invoice-buyer-copy-taxId')).toBeTruthy();
    expect(api.tickets.get).not.toHaveBeenCalled();
    expect(screen.getByTestId('invoice-confirm').disabled).toBe(true);
    expect(screen.getByTestId('invoice-blocked').textContent).toBe('แนบไฟล์ใบกำกับภาษีก่อน');

    fireEvent.change(screen.getByTestId('invoice-file-input'), { target: { files: [new File(['%PDF-'], 'IV2611-0042.pdf', { type: 'application/pdf' })] } });
    expect((await screen.findByTestId('invoice-read-hint')).textContent).toBe('อ่านจากไฟล์ให้แล้ว ตรวจและแก้ได้ก่อนยืนยัน');
    expect(screen.getByTestId('invoice-number').value).toBe('IV2611-0042');
    expect(screen.getByTestId('invoice-total').value).toBe('85065');
    await waitFor(() => expect(screen.getByTestId('invoice-confirm').disabled).toBe(false));

    // The amount is checked in a sheet before anything is recorded.
    fireEvent.click(screen.getByTestId('invoice-confirm'));
    const sheet = await screen.findByTestId('invoice-confirm-sheet');
    expect(sheet.textContent).toContain('฿85,065.00');
    expect(api.deliveryRequests.confirmInvoice).not.toHaveBeenCalled();
    fireEvent.click(within(sheet).getByTestId('invoice-confirm-go'));
    await waitFor(() => expect(api.deliveryRequests.confirmInvoice).toHaveBeenCalledWith(5, { invoiceNumber: 'IV2611-0042', invoiceDate: '2026-11-19', total: 85065 }));

    const callout = await screen.findByTestId('invoiced-callout');
    expect(callout.textContent).toContain('ออกใบกำกับแล้ว ส่งของได้');
    expect(callout.textContent).toContain('IV2611-0042');
    expect(screen.getByTestId('round-step-3').textContent).toContain('บันทึกรับเงินแล้ว');
    expect(screen.queryByTestId('tax-invoice-step')).toBeNull();
  });

  it('an unreadable PDF falls back to typing, and the confirm button says what is missing', async () => {
    const list = (files) => ({ order: ORDER, rounds: [round('FORWARDED', { viewer: { ...viewer(false), canIssue: true }, files })], approvedQuotations: [], canStart: false, startBlockedReason: 'x' });
    api.deliveryRequests.list.mockResolvedValueOnce(list([])).mockResolvedValue(list([{ attachmentId: 22, fileName: 'scan.pdf', mimeType: 'application/pdf', fileSize: 1024, kind: 'INVOICE', uploaderSide: 'ISSUER', uploadedById: 90 }]));
    api.deliveryRequests.attachTaxInvoice.mockResolvedValue({ attachmentId: 22, read: { invoiceNumber: null, invoiceDate: null, total: null }, readOk: false });
    renderPanel({ dealless: true });
    fireEvent.change(await screen.findByTestId('invoice-file-input'), { target: { files: [new File(['%PDF-'], 'scan.pdf', { type: 'application/pdf' })] } });
    expect((await screen.findByTestId('invoice-read-hint')).textContent).toBe('อ่านจากไฟล์ไม่ได้ กรอกเอง');
    expect(screen.getByTestId('invoice-confirm').disabled).toBe(true);
    expect(screen.getByTestId('invoice-blocked').textContent).toContain('เลขที่ใบกำกับ');
    fireEvent.change(screen.getByTestId('invoice-number'), { target: { value: 'IV2611-0099' } });
    fireEvent.change(screen.getByTestId('invoice-total'), { target: { value: '1,200.50' } });
    expect(screen.getByTestId('invoice-blocked').textContent).toContain('วันที่');
    // A hand-typed date (the date control reports an event, not a string) is accepted: confirm becomes available.
    const dateInput = document.getElementById('invoice-date');
    fireEvent.change(dateInput, { target: { value: '19/11/2569' } });
    fireEvent.blur(dateInput);
    await waitFor(() => expect(screen.getByTestId('invoice-confirm').disabled).toBe(false));
    // What was typed survives replacing the file with another that reads nothing.
    fireEvent.click(screen.getByTestId('invoice-replace'));
    fireEvent.change(screen.getByTestId('invoice-file-input'), { target: { files: [new File(['%PDF-'], 'scan2.pdf', { type: 'application/pdf' })] } });
    await waitFor(() => expect(api.deliveryRequests.attachTaxInvoice).toHaveBeenCalledTimes(2));
    expect(screen.getByTestId('invoice-number').value).toBe('IV2611-0099');
    expect(screen.getByTestId('invoice-total').value).toBe('1,200.50');
    expect(screen.getByTestId('invoice-open').getAttribute('href')).toBe('/f/22');
  });

  it('a guessed total asks the issuer to check it, the remaining invoiceable amount shows, and an over-ceiling refusal stays in the sheet', async () => {
    const file = { attachmentId: 23, fileName: 'x.pdf', mimeType: 'application/pdf', fileSize: 10, kind: 'INVOICE', uploaderSide: 'ISSUER', uploadedById: 90 };
    api.deliveryRequests.list.mockResolvedValue({
      order: ORDER, approvedQuotations: [], canStart: false, startBlockedReason: 'x', invoiceable: 500,
      rounds: [round('FORWARDED', { viewer: { ...viewer(false), canIssue: true }, files: [file] })],
    });
    api.deliveryRequests.attachTaxInvoice.mockResolvedValue({ attachmentId: 23, read: { invoiceNumber: 'IV2611-0001', invoiceDate: '2026-11-19', total: 900 }, readOk: true, totalWeak: true });
    api.deliveryRequests.confirmInvoice.mockRejectedValue(Object.assign(new Error('ยอดใบกำกับรวมของออเดอร์นี้เกินมูลค่างาน ออกใบกำกับได้อีกไม่เกิน ฿500.00'), { status: 400 }));
    renderPanel({ dealless: true });
    fireEvent.change(await screen.findByTestId('invoice-file-input'), { target: { files: [new File(['%PDF-'], 'x.pdf', { type: 'application/pdf' })] } });
    expect((await screen.findByTestId('invoice-read-hint')).textContent).toContain('กรุณาตรวจยอด');
    expect(screen.getByTestId('invoiceable-hint').textContent).toContain('฿500.00');
    fireEvent.click(screen.getByTestId('invoice-confirm'));
    const sheet = await screen.findByTestId('invoice-confirm-sheet');
    expect(within(sheet).getByTestId('invoiceable-line').textContent).toContain('฿500.00');
    fireEvent.click(within(sheet).getByTestId('invoice-confirm-go'));
    expect((await within(sheet).findByTestId('invoice-confirm-error')).textContent).toContain('ออกใบกำกับได้อีกไม่เกิน ฿500.00');
  });

  it('a confirmed invoice can be cancelled behind a sheet that needs a reason, and the call carries it', async () => {
    const invoiced = round('INVOICED', { viewer: { ...viewer(false), canVoid: true }, invoice: { number: 'IV2611-0042', date: '2026-11-19', total: 85065, recordedAmount: 14177.5, receiptId: 3, attachmentId: 21, invoicedByName: 'ก', invoicedAt: '2026-11-19T07:03:00Z' } });
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [invoiced], approvedQuotations: [], canStart: false, startBlockedReason: 'x', invoiceable: 0 });
    api.deliveryRequests.voidInvoice.mockResolvedValue({ round: round('FORWARDED') });
    renderPanel({ dealless: true });
    fireEvent.click(await screen.findByTestId('void-invoice-button'));
    const sheet = await screen.findByTestId('void-invoice-sheet');
    expect(within(sheet).getByTestId('void-go').disabled).toBe(true);
    expect(within(sheet).getByTestId('void-blocked')).toBeTruthy();
    fireEvent.change(within(sheet).getByTestId('void-reason'), { target: { value: 'พิมพ์ยอดผิด' } });
    fireEvent.click(within(sheet).getByTestId('void-go'));
    await waitFor(() => expect(api.deliveryRequests.voidInvoice).toHaveBeenCalledWith(5, { reason: 'พิมพ์ยอดผิด' }));
  });

  it('says in Thai why an invoice cannot be cancelled instead of only hiding the button', async () => {
    const earlier = round('INVOICED', {
      viewer: { ...viewer(false), canVoid: false, voidBlockedReason: 'ยกเลิกได้เฉพาะใบกำกับล่าสุดของออเดอร์ ให้ยกเลิกใบกำกับของรอบส่งที่ 2 ก่อน' },
      invoice: { number: 'IV2611-0042', date: '2026-11-19', total: 85065, recordedAmount: 14177.5, receiptId: 3, attachmentId: 21, invoicedByName: 'ก', invoicedAt: '2026-11-19T07:03:00Z' },
    });
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [earlier], approvedQuotations: [], canStart: false, startBlockedReason: 'x', invoiceable: 0 });
    renderPanel({ dealless: true });
    expect((await screen.findByTestId('void-blocked-reason')).textContent).toContain('รอบส่งที่ 2');
    expect(screen.queryByTestId('void-invoice-button')).toBeNull();
  });

  it('renders EVERY unfinished round: a voided R1 (FORWARDED) beside R2 (SENT) shows for the issuer and for account, one primary action', async () => {
    const r1 = round('FORWARDED', { id: 5, roundNo: 1, forwardedByName: 'คุณคลัง', forwardedAt: '2026-11-19T04:15:00Z' });
    const r2 = round('SENT', { id: 6, roundNo: 2 });
    const list = (v1, v2) => ({ order: ORDER, rounds: [{ ...r2, viewer: v2 }, { ...r1, viewer: v1 }], approvedQuotations: [], canStart: false, startBlockedReason: 'x', invoiceable: 1000 });
    api.deliveryRequests.list.mockResolvedValue(list({ ...viewer(false), canIssue: true }, viewer(false)));
    const issuer = renderPanel({ dealless: true });
    expect((await screen.findAllByTestId('open-round')).map((n) => n.textContent)).toEqual([expect.stringContaining('รอบส่งที่ 1'), expect.stringContaining('รอบส่งที่ 2')]);
    expect(screen.getAllByTestId('invoice-confirm')).toHaveLength(1);
    issuer.unmount();
    api.deliveryRequests.list.mockResolvedValue(list(viewer(false), viewer(false)));
    renderPanel({ dealless: true });
    expect(await screen.findAllByTestId('open-round')).toHaveLength(2);
    expect(screen.queryByTestId('invoice-confirm')).toBeNull();
  });

  it('with two rounds waiting on the issuer the oldest holds the primary button and the other a secondary one', async () => {
    const issuerView = { ...viewer(false), canIssue: true };
    api.deliveryRequests.list.mockResolvedValue({
      order: ORDER, approvedQuotations: [], canStart: false, startBlockedReason: 'x', invoiceable: 1000,
      rounds: [round('FORWARDED', { id: 6, roundNo: 2, viewer: issuerView }), round('FORWARDED', { id: 5, roundNo: 1, viewer: issuerView })],
    });
    renderPanel({ dealless: true });
    const bars = await screen.findAllByTestId('invoice-actions');
    expect(bars).toHaveLength(2);
    expect(bars.filter((b) => b.className.includes('sticky'))).toHaveLength(1);
    expect(bars[0].className).toContain('sticky');
  });

  const invoicedRound = (extra = {}) => round('INVOICED', {
    invoice: { number: 'IV2611-0042', total: 85065, recordedAmount: 14177.5, invoicedByName: 'คุณบัญชี', invoicedAt: '2026-11-19T07:03:00Z' },
    viewer: { ...viewer(false), canRecordDelivery: true }, ...extra,
  });

  it('before the invoice step 4 shows its reason and no record button', async () => {
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [round('SENT')], approvedQuotations: [], canStart: false, startBlockedReason: 'x' });
    renderPanel({ dealless: true });
    expect((await screen.findByTestId('round-step-4')).textContent).toContain('บันทึกได้หลังออกใบกำกับ หรือบันทึกก่อนพร้อมหมายเหตุ');
    expect(screen.queryByTestId('record-delivery-button')).toBeNull();
  });

  it('defaults to what the warehouse may deliver now and shows that limit up front, not after submit', async () => {
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [invoicedRound({ lines: [{ itemId: 1, description: 'กระเบื้อง', unit: 'แผ่น', coveredQty: 140, deliveredQty: 0 }] })], approvedQuotations: [QUOTATION] });
    api.tickets.deliveryAllowance.mockResolvedValue({ lines: [{ itemId: 1, remaining: 70 }] });
    renderPanel({ warehouseUserId: 77 });

    fireEvent.click(await screen.findByTestId('record-delivery-button'));
    const sheet = await screen.findByTestId('record-delivery-sheet');
    expect((await within(sheet).findByTestId('record-limit-1')).textContent).toContain('ส่งได้ไม่เกิน 70');
    expect(within(sheet).getByTestId('record-qty-1').value).toBe('70');
    fireEvent.change(within(sheet).getByTestId('record-qty-1'), { target: { value: '100' } });
    expect(within(sheet).getByTestId('record-delivery-blocked').textContent).toContain('ส่งได้ไม่เกิน 70');
    api.tickets.deliveryAllowance.mockReset();
  });

  it('records against the invoiced round: quantity capped at the remainder, a past day and the recipient are sent, today is not, and the proof photo is attached after', async () => {
    const wh = { warehouseUserId: 77 };
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [invoicedRound({ lines: [{ itemId: 1, description: 'กระเบื้อง', unit: 'แผ่น', coveredQty: 300, deliveredQty: 100 }] })], approvedQuotations: [], canStart: false, startBlockedReason: 'x' });
    api.deliveryRequests.recordDelivery.mockResolvedValue({ round: invoicedRound() });
    api.deliveryRequests.addFile.mockResolvedValue({ round: invoicedRound() });
    renderPanel(wh);

    fireEvent.click(await screen.findByTestId('record-delivery-button'));
    const sheet = await screen.findByTestId('record-delivery-sheet');
    expect(within(sheet).getByTestId('record-delivery-ref').textContent).toContain('รอบส่งที่ 1');
    expect(within(sheet).getByTestId('record-delivery-ref').textContent).toContain('IV2611-0042');
    // 300 covered, 100 already delivered: the sheet offers the remaining 200 and refuses more.
    const qty = within(sheet).getByTestId('record-qty-1');
    expect(qty.value).toBe('200');
    fireEvent.change(qty, { target: { value: '250' } });
    expect(within(sheet).getByTestId('record-delivery-submit').disabled).toBe(true);
    expect(within(sheet).getByTestId('record-delivery-blocked').textContent).toContain('เหลือ 200');
    fireEvent.change(qty, { target: { value: '150' } });
    expect(within(sheet).getByTestId('record-delivery-submit').textContent).toBe('บันทึกส่ง 150 แผ่น');
    fireEvent.change(within(sheet).getByTestId('record-recipient'), { target: { value: 'คุณลูกค้า' } });
    const photo = new File(['x'], 'note.jpg', { type: 'image/jpeg' });
    expect(within(sheet).getByTestId('record-proof-camera').getAttribute('capture')).toBe('environment');
    fireEvent.change(within(sheet).getByTestId('record-proof-input'), { target: { files: [photo] } });
    fireEvent.click(within(sheet).getByTestId('record-delivery-submit'));

    await waitFor(() => expect(api.deliveryRequests.recordDelivery).toHaveBeenCalledWith(5, { lines: [{ itemId: 1, qty: 150 }], recipientName: 'คุณลูกค้า' }));
    await waitFor(() => expect(api.deliveryRequests.addFile).toHaveBeenCalledWith(5, photo));
    expect((await screen.findByTestId('record-done')).textContent).toBe('บันทึกการส่งสินค้าแล้ว');
    expect(screen.queryByTestId('record-delivery-sheet')).toBeNull();
  });

  it('with no invoice the sales side gets a secondary button; the sheet needs a note and sends it with the deal-route record', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: { fulfillmentStatus: 'FROM_STOCK' }, items: [{ id: 1, brand: 'SCG', model: 'A1', size: '60x60', qty: 500, qtyDelivered: 200 }] } });
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [round('SENT')], approvedQuotations: [], canStart: false, startBlockedReason: 'x' });
    api.tickets.recordDelivery.mockResolvedValue({});
    renderPanel({ canRecordNoInvoice: true, hostHasPrimary: true });
    fireEvent.click(await screen.findByTestId('record-no-invoice-button'));
    const sheet = await screen.findByTestId('record-delivery-sheet');
    expect(within(sheet).queryByTestId('record-proof-pick')).toBeNull();
    expect(within(sheet).getByTestId('record-delivery-submit').disabled).toBe(true);
    expect(within(sheet).getByTestId('record-delivery-blocked').textContent).toContain('หมายเหตุหรือเลขอ้างอิง');
    fireEvent.change(within(sheet).getByTestId('record-qty-1'), { target: { value: '50' } });
    fireEvent.change(within(sheet).getByTestId('record-no-invoice-note'), { target: { value: ' PO-77 ' } });
    fireEvent.click(within(sheet).getByTestId('record-delivery-submit'));
    await waitFor(() => expect(api.tickets.recordDelivery).toHaveBeenCalledWith(701, { lines: [{ itemId: 1, qty: 50 }], source: 'STOCK', noInvoiceNote: 'PO-77' }));
    expect(api.deliveryRequests.recordDelivery).not.toHaveBeenCalled();
    // Not offered to a viewer the server did not advertise it to, nor beside an invoiced round that can be delivered.
  });

  it('the warehouse can also record before the invoice, through the sent round, with the same required note', async () => {
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [round('FORWARDED')], approvedQuotations: [], canStart: false, startBlockedReason: 'x' });
    api.deliveryRequests.recordDelivery.mockResolvedValue({ round: round('FORWARDED') });
    renderPanel({ warehouseUserId: 77 });
    fireEvent.click(await screen.findByTestId('record-no-invoice-button'));
    const sheet = await screen.findByTestId('record-delivery-sheet');
    expect(within(sheet).getByTestId('record-delivery-submit').disabled).toBe(true);
    fireEvent.change(within(sheet).getByTestId('record-no-invoice-note'), { target: { value: 'ใบส่งของ 5' } });
    fireEvent.click(within(sheet).getByTestId('record-delivery-submit'));
    await waitFor(() => expect(api.deliveryRequests.recordDelivery).toHaveBeenCalledWith(5, { lines: [{ itemId: 1, qty: 300 }], noInvoiceNote: 'ใบส่งของ 5' }));
    expect(api.tickets.recordDelivery).not.toHaveBeenCalled();
  });

  it('the no-invoice record stays a secondary beside an invoiced round with quantity left; the source picker is back (starts from the deal) and is sent', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: { fulfillmentStatus: 'FROM_STOCK' }, events: [], items: [{ id: 1, brand: 'SCG', model: 'A1', size: '60x60', qty: 500, qtyDelivered: 200 }] } });
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [invoicedRound()], approvedQuotations: [], canStart: false, startBlockedReason: 'x' });
    api.tickets.recordDelivery.mockResolvedValue({});
    renderPanel({ canRecordNoInvoice: true, hostHasPrimary: true });
    fireEvent.click(await screen.findByTestId('record-no-invoice-button'));
    const sheet = await screen.findByTestId('record-delivery-sheet');
    expect(within(sheet).getByTestId('record-source').value).toBe('STOCK');
    fireEvent.change(within(sheet).getByTestId('record-source'), { target: { value: 'WAREHOUSE' } });
    fireEvent.change(within(sheet).getByTestId('record-qty-1'), { target: { value: '50' } });
    fireEvent.change(within(sheet).getByTestId('record-no-invoice-note'), { target: { value: 'PO-9' } });
    fireEvent.click(within(sheet).getByTestId('record-delivery-submit'));
    await waitFor(() => expect(api.tickets.recordDelivery).toHaveBeenCalledWith(701, { lines: [{ itemId: 1, qty: 50 }], source: 'WAREHOUSE', noInvoiceNote: 'PO-9' }));
  });

  it('a round whose goods cannot leave yet shows the reason as text and no record button', async () => {
    const blocked = invoicedRound({ viewer: { ...viewer(false), canRecordDelivery: false, recordBlockedReason: 'ต้องรับสินค้าเข้าโกดังก่อนจึงจะบันทึกการส่งได้' } });
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [blocked], approvedQuotations: [], canStart: false, startBlockedReason: 'x' });
    renderPanel({ warehouseUserId: 77 });
    expect((await screen.findByTestId('record-blocked-reason')).textContent).toContain('ต้องรับสินค้าเข้าโกดังก่อน');
    expect(screen.queryByTestId('record-delivery-button')).toBeNull();
    expect(screen.queryByTestId('record-delivery-secondary-1')).toBeNull();
  });

  it('?record=next (the deal page\'s sticky บันทึกส่งมอบ) opens the oldest recordable round, or the no-invoice sheet when there is none', async () => {
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [invoicedRound()], approvedQuotations: [], canStart: false, startBlockedReason: 'x' });
    const first = renderPanel({ canRecordNoInvoice: true, hostHasPrimary: true }, '/?record=next');
    expect((await screen.findByTestId('record-delivery-ref')).textContent).toContain('IV2611-0042');
    first.unmount();
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [], approvedQuotations: [], canStart: true, startBlockedReason: null });
    renderPanel({ canRecordNoInvoice: true, hostHasPrimary: true }, '/?record=next');
    expect((await screen.findByTestId('record-delivery-ref')).textContent).toContain('ยังไม่มีใบกำกับภาษี');
  });

  it('only a past delivery day is sent as deliveredOn; today leaves it to the server clock', () => {
    const base = { lines: [{ itemId: 1 }], qty: { 1: '5' }, recipient: ' ' };
    expect(deliveryPayload({ ...base, deliveredOn: bangkokTodayIso() })).toEqual({ lines: [{ itemId: 1, qty: 5 }] });
    const past = addDaysIso(bangkokTodayIso(), -3);
    expect(deliveryPayload({ ...base, deliveredOn: past })).toEqual({ lines: [{ itemId: 1, qty: 5 }], deliveredOn: past });
  });

  it('a refused record keeps the sheet open with the server reason (the note-required 409 reads as its sentence)', async () => {
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [invoicedRound()], approvedQuotations: [], canStart: false, startBlockedReason: 'x' });
    api.deliveryRequests.recordDelivery.mockRejectedValue(Object.assign(new Error('ยังไม่มีใบกำกับภาษีของรอบนี้ กรุณาระบุหมายเหตุหรือเลขอ้างอิง'), { status: 409, details: { code: 'NO_INVOICE_NOTE_REQUIRED' } }));
    renderPanel({ warehouseUserId: 77 });
    fireEvent.click(await screen.findByTestId('record-delivery-button'));
    fireEvent.click(within(await screen.findByTestId('record-delivery-sheet')).getByTestId('record-delivery-submit'));
    expect((await screen.findByTestId('record-delivery-error')).textContent).toContain('กรุณาระบุหมายเหตุหรือเลขอ้างอิง');
    expect(screen.getByTestId('record-delivery-sheet')).toBeTruthy();
  });

  it('on the deal page the host owns the primary: the record button is the host\'s, ?record opens the sheet, other rounds get a secondary button; a delivered round folds away', async () => {
    const delivered = invoicedRound({ id: 4, roundNo: 1, lines: [{ itemId: 1, description: 'กระเบื้อง', unit: 'แผ่น', coveredQty: 300, deliveredQty: 300 }], viewer: { ...viewer(false), canRecordDelivery: false } });
    const first = invoicedRound({ id: 5, roundNo: 2 });
    const second = invoicedRound({ id: 6, roundNo: 3, invoice: { number: 'IV2611-0043', total: 100, recordedAmount: 0 } });
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, rounds: [second, first, delivered], approvedQuotations: [], canStart: false, startBlockedReason: 'x' });
    renderPanel({ hostRecordRoundId: 5, hostHasPrimary: true }, '/?record=5');
    expect(await screen.findByTestId('record-delivery-sheet')).toBeTruthy();
    expect(screen.queryByTestId('record-delivery-button')).toBeNull();
    expect(screen.queryByTestId('record-delivery-secondary-2')).toBeNull();
    expect(screen.getByTestId('record-delivery-secondary-3')).toBeTruthy();
    expect(screen.getByTestId('past-round-1').textContent).toContain('ส่งของแล้ว');
    expect(screen.getAllByTestId('open-round')).toHaveLength(2);
  });

  it('the ไฟล์ tab groups round files newest round first, the tax invoice first, and says when there are none', async () => {
    const file = (id, extra) => ({ attachmentId: id, fileName: `f${id}.jpg`, mimeType: 'image/jpeg', fileSize: 2048, kind: 'OTHER', uploaderSide: 'SALES', ...extra });
    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, approvedQuotations: [QUOTATION], rounds: [
      round('INVOICED', { id: 5, roundNo: 1, sentAt: '2026-07-08T03:00:00Z', invoice: { number: 'IV2607-0018', total: 56710, attachmentId: 9 },
        files: [file(8, { uploaderSide: 'WAREHOUSE' }), file(9, { kind: 'INVOICE', fileName: 'iv.pdf', uploaderSide: 'ISSUER' })] }),
      round('SENT', { id: 6, roundNo: 2, sentAt: new Date().toISOString(), files: [file(10)] }),
      round('DRAFT', { id: 7, roundNo: 3, files: [] }),
    ] });
    render(<QueryClientProvider client={new QueryClient()}><RoundFilesSection ticketId={701} /></QueryClientProvider>);
    const blocks = await screen.findAllByTestId(/^round-block-/);
    expect(blocks.map((b) => b.dataset.testid)).toEqual(['round-block-2', 'round-block-1']);
    expect(within(blocks[0]).getByTestId('round-new')).not.toBeNull();
    expect(within(blocks[1]).queryByTestId('round-new')).toBeNull();
    const rows = within(blocks[1]).getAllByRole('link');
    expect(rows[0].textContent).toContain('ใบกำกับภาษี IV2607-0018');
    expect(rows[0].textContent).toContain('฿56,710.00');
    expect(rows[1].textContent).toContain('f8.jpg');

    // A failed fetch closes the blank tab and shows an error; the page is not navigated away.
    const tab = { close: vi.fn(), location: {} };
    vi.spyOn(window, 'open').mockReturnValue(tab);
    api.attachments.fileBlob.mockRejectedValue(new Error('404'));
    fireEvent.click(rows[1]);
    expect(await screen.findByTestId('open-file-error')).not.toBeNull();
    expect(tab.close).toHaveBeenCalled();
    window.open.mockRestore();

    api.deliveryRequests.list.mockResolvedValue({ order: ORDER, approvedQuotations: [], rounds: [] });
    render(<QueryClientProvider client={new QueryClient()}><RoundFilesSection ticketId={702} /></QueryClientProvider>);
    expect(await screen.findByText('ยังไม่มีไฟล์ของรอบส่ง')).not.toBeNull();
  });
});
