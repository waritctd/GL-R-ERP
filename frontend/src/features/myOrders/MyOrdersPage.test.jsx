import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MyOrdersPage } from './MyOrdersPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      myIncoming: { get: vi.fn() },
      noProjectOrders: { create: vi.fn(), withdraw: vi.fn(), pairToDeal: vi.fn() },
      tickets: { comment: vi.fn(), list: vi.fn(), get: vi.fn() },
      storedImportRequests: { get: vi.fn() },
      factoryConfigs: { names: vi.fn() },
    },
  };
});

const lot = (over = {}) => ({ lotId: 1, product: 'Slate Grey 60x60', unit: 'แผ่น', qty: 12, receivedQty: null, lotNo: 1, lotCount: 1,
  step: 'IN_TRANSIT', expectedOn: '2026-11-22', vessel: 'TEST VESSEL', ...over });
const base = { requesterName: null, factoryName: 'Test Stone Co.', wantedBy: null };
const data = { rows: [
  { ...base, kind: 'PROJECT', ticketId: 101, ticketCode: 'PR-TEST-0001', who: 'โครงการ ทดสอบ', docNumber: 'IR-TEST-001', importRequestId: 11, waiting: false, product: 'Slate Grey', unit: 'แผ่น', qty: 800, lot: lot({ lotId: 1, qty: 800 }) },
  { ...base, kind: 'SAMPLE', ticketId: null, ticketCode: null, who: 'ให้ผู้ออกแบบ (ทดสอบ)', requesterName: 'คุณ ทดสอบ', docNumber: 'IR-TEST-002', importRequestId: 12, waiting: false, product: 'Slate Grey 60x60', unit: 'แผ่น', qty: 12, wantedBy: '30 พ.ย. 2569', lot: lot({ lotId: 2 }) },
  { ...base, kind: 'SHOWROOM', ticketId: null, ticketCode: null, who: 'โชว์รูมสาขา ทดสอบ', docNumber: 'IR-TEST-003', importRequestId: 13, waiting: true, product: 'Stone White', unit: 'แผ่น', qty: 24, lot: null, canWithdraw: true },
  { ...base, kind: 'STOCK', ticketId: null, ticketCode: null, who: 'สต็อกกลาง', docNumber: 'IR-TEST-004', importRequestId: 14, waiting: false, product: 'Forest Brown', unit: 'แผ่น', qty: 60, lot: lot({ lotId: 4, qty: 60, step: 'RECEIVED', receivedOn: '2026-11-12' }) },
] };

function renderPage(user = { id: 5, name: 'คุณ ทดสอบ', role: 'sales' }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const showToast = vi.fn();
  render(<QueryClientProvider client={client}><MemoryRouter><MyOrdersPage user={user} showToast={showToast} /></MemoryRouter></QueryClientProvider>);
  return showToast;
}

afterEach(() => vi.clearAllMocks());

describe('ของที่ฉันสั่ง', () => {
  it('lists project and no-project rows in กำลังมา and ถึงโกดังแล้ว; a project row opens its deal tab, a no-project row the lot sheet; chips filter', async () => {
    vi.mocked(api.myIncoming.get).mockResolvedValue(data);
    renderPage();
    const incoming = await screen.findByTestId('orders-incoming');
    expect(within(incoming).getAllByText('IR-TEST-001').length).toBeGreaterThan(0);
    expect(within(screen.getByTestId('orders-arrived')).getAllByText('IR-TEST-004').length).toBeGreaterThan(0);
    expect(within(incoming).getAllByText('รอจัดรอบ').length).toBeGreaterThan(0);
    expect(within(incoming).getAllByRole('link', { name: 'เปิดงาน' })[0].getAttribute('href')).toBe('/tickets/101?tab=ship');

    fireEvent.click(within(incoming).getByTestId('order-open-2'));
    const sheet = await screen.findByTestId('incoming-lot-sheet');
    expect(within(sheet).getByText('30 พ.ย. 2569')).toBeTruthy(); // the wanted-by date
    fireEvent.click(within(sheet).getByRole('button', { name: 'ปิด' }));

    fireEvent.click(screen.getByTestId('orders-chip-SAMPLE'));
    expect(screen.queryByText('IR-TEST-001')).toBeNull();
    expect(screen.getAllByText('IR-TEST-002').length).toBeGreaterThan(0);
  });

  it('from the desktop breakpoint the create form is a side panel beside the list, not a sheet', async () => {
    const original = window.matchMedia;
    window.matchMedia = (query) => ({ matches: query.includes('min-width: 1041px'), media: query, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {} });
    try {
      vi.mocked(api.myIncoming.get).mockResolvedValue({ rows: [] });
      vi.mocked(api.factoryConfigs.names).mockResolvedValue({ factories: [] });
      renderPage();
      const panel = await screen.findByTestId('new-order-panel');
      expect(within(panel).getByTestId('new-order-send').disabled).toBe(true);
      expect(within(panel).getByTestId('new-order-missing').textContent).toContain('ยังขาด');
      expect(screen.queryByTestId('new-order')).toBeNull(); // no header button: the form is already there
    } finally {
      window.matchMedia = original;
    }
  });

  it('ยกเลิกคำขอ: a still-waiting no-project order can be withdrawn after a confirm', async () => {
    vi.mocked(api.myIncoming.get).mockResolvedValue(data);
    vi.mocked(api.noProjectOrders.withdraw).mockResolvedValue({ importRequest: {} });
    renderPage();
    fireEvent.click((await screen.findAllByTestId('order-withdraw-13'))[0]);
    expect(api.noProjectOrders.withdraw).not.toHaveBeenCalled(); // asks first
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิกคำขอ' }));
    await waitFor(() => expect(api.noProjectOrders.withdraw).toHaveBeenCalledWith(13));
  });

  it("shows the whole team's rows: filters by rep, hides the open control on a deal the rep cannot open", async () => {
    const row = (over) => ({ ...base, kind: 'PROJECT', ticketId: 101, ticketCode: 'PR-TEST-0001', who: 'โครงการ ก', docNumber: 'IR-TEST-001', importRequestId: 11,
      waiting: true, product: 'Slate Grey 60x60', unit: 'แผ่น', qty: 5, lot: null, canWithdraw: false, repId: 5, repName: 'คุณ ทดสอบ', canOpenDeal: true, ...over });
    vi.mocked(api.myIncoming.get).mockResolvedValue({ rows: [row({}),
      row({ ticketId: 202, ticketCode: 'PR-TEST-0002', who: 'โครงการ ข', docNumber: 'IR-TEST-002', importRequestId: 12, repId: 9, repName: 'คุณ อื่น', canOpenDeal: false })] });
    renderPage();
    const incoming = await screen.findByTestId('orders-incoming');
    expect(within(incoming).getAllByRole('link', { name: 'เปิดงาน' })).toHaveLength(1); // the other rep's deal has no link
    expect(within(incoming).getAllByText('IR-TEST-002').length).toBeGreaterThan(0);
    fireEvent.change(screen.getByTestId('orders-filter-rep'), { target: { value: '9' } });
    expect(screen.queryByText('IR-TEST-001')).toBeNull();
    expect(screen.getAllByText('IR-TEST-002').length).toBeGreaterThan(0);
  });

  it('pairs a project order that has no deal yet to a deal of the same project, line by line', async () => {
    vi.mocked(api.myIncoming.get).mockResolvedValue({ rows: [{ ...base, kind: 'PROJECT', ticketId: null, ticketCode: null, who: 'โครงการ ทดสอบ', docNumber: 'IR-TEST-009',
      importRequestId: 19, waiting: true, product: 'Slate Grey 60x60', unit: 'แผ่น', qty: 5, lot: null, canWithdraw: false, canPair: true, repId: 5, repName: 'คุณ ทดสอบ' }] });
    vi.mocked(api.tickets.list).mockResolvedValue({ tickets: [
      { id: 301, code: 'PR-TEST-0301', projectName: 'โครงการ ทดสอบ', lifecycle: 'ACTIVE' }, { id: 302, code: 'PR-TEST-0302', projectName: 'โครงการ อื่น', lifecycle: 'ACTIVE' }] });
    vi.mocked(api.storedImportRequests.get).mockResolvedValue({ importRequest: { items: [{ id: 91, code: 'Slate Grey', size: '60x60', qty: 5, unit: 'แผ่น' }] } });
    vi.mocked(api.tickets.get).mockResolvedValue({ ticket: { items: [{ id: 701, brand: 'Slate Grey', size: '60x60', qty: 5 }] } });
    vi.mocked(api.noProjectOrders.pairToDeal).mockResolvedValue({ paired: true });
    renderPage();
    fireEvent.click(await screen.findByTestId('order-pair-19'));
    const sheet = await screen.findByTestId('pair-sheet');
    const deal = await within(sheet).findByTestId('pair-deal');
    expect(within(deal).queryByText(/PR-TEST-0302/)).toBeNull(); // only the same project is offered
    fireEvent.change(deal, { target: { value: '301' } });
    const line = await within(sheet).findByTestId('pair-line-91');
    await within(line).findByRole('option', { name: /Slate Grey/ });
    fireEvent.change(line, { target: { value: '701' } });
    fireEvent.click(within(sheet).getByTestId('pair-send'));
    await waitFor(() => expect(api.noProjectOrders.pairToDeal).toHaveBeenCalledWith(19, { ticketId: 301, lines: [{ importRequestItemId: 91, ticketItemId: 701 }] }));
  });

  it('สั่งของไม่มีโครงการ: the send button stays disabled and names what is missing until every field is filled', async () => {
    vi.mocked(api.myIncoming.get).mockResolvedValue({ rows: [] });
    vi.mocked(api.factoryConfigs.names).mockResolvedValue({ factories: [{ id: 3, factoryName: 'Test Stone Co.' }] });
    vi.mocked(api.noProjectOrders.create).mockResolvedValue({ importRequest: {} });
    renderPage();
    fireEvent.click(await screen.findByTestId('new-order'));
    const sheet = await screen.findByTestId('new-order-sheet');
    const send = within(sheet).getByTestId('new-order-send');
    expect(send.disabled).toBe(true);
    expect(within(sheet).getByTestId('new-order-missing').textContent).toContain('สั่งเพื่ออะไร');

    fireEvent.click(within(sheet).getByTestId('order-purpose-SAMPLE'));
    fireEvent.change(within(sheet).getByLabelText(/ให้ใคร/), { target: { value: 'ให้ผู้ออกแบบ' } });
    await waitFor(() => expect(within(sheet).getByRole('option', { name: 'Test Stone Co.' })).toBeTruthy());
    fireEvent.change(within(sheet).getByLabelText('โรงงาน *'), { target: { value: '3' } });
    fireEvent.change(within(sheet).getByLabelText('รหัส / ชื่อสินค้า'), { target: { value: 'Slate Grey' } });
    fireEvent.change(within(sheet).getByLabelText('จำนวน'), { target: { value: '12' } });
    expect(send.disabled).toBe(true); // still no wanted-by date
    expect(within(sheet).getByTestId('new-order-missing').textContent).toContain('ต้องการภายใน');
    fireEvent.change(within(sheet).getByLabelText('ต้องการภายใน *'), { target: { value: '2099-01-02' } });
    fireEvent.blur(within(sheet).getByLabelText('ต้องการภายใน *'));
    await waitFor(() => expect(send.disabled).toBe(false));
    fireEvent.click(send);
    await waitFor(() => expect(api.noProjectOrders.create).toHaveBeenCalledWith({
      purpose: 'SAMPLE', purposeNote: 'ให้ผู้ออกแบบ', factoryId: 3, wantedBy: '2099-01-02',
      lines: [{ code: 'Slate Grey', size: null, qty: 12, unit: 'แผ่น' }] }));
  });
});
