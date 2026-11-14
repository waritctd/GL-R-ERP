import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MyWork } from './MyWork.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      salesBoard: { myWork: vi.fn() },
      tickets: { addActivity: vi.fn(), markLost: vi.fn() },
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
    },
  };
});

const quiet = {
  ticketId: 31, title: 'สยามพารากอน · ห้องน้ำชั้น 3', customerName: 'สยามพิวรรธน์', projectName: null, ownerId: 6,
  ownerName: 'คุณสมหมาย', salesStage: 'QUOTE_DESIGN_SIDE', repCode: 'S3', boardColumn: 3, value: 1240000,
  designerName: 'เทพสตูดิโอ ดีไซน์', winProbability: 40, expectedQuarter: null,
  lastTouchedAt: '2026-10-19T00:00:00Z', daysQuiet: 27, quiet: true,
};
const fresh = { ...quiet, ticketId: 32, title: 'BMW Showroom', daysQuiet: 2, quiet: false };

function renderMyWork() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><MyWork /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('MyWork', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.salesBoard.myWork.mockResolvedValue({ followUp: [quiet], board: [quiet, fresh], quietDays: 14 });
    api.tickets.addActivity.mockResolvedValue({ id: 1 });
    api.tickets.markLost.mockResolvedValue({});
  });

  it('heads the page งานของฉัน and lists quiet deals with value and days quiet', async () => {
    renderMyWork();

    expect(screen.getByRole('heading', { name: 'งานของฉัน' })).toBeTruthy();
    const row = await screen.findByTestId('follow-up-31');
    expect(screen.getByText('ควรตามต่อ')).toBeTruthy();
    expect(screen.getByText('ดีลที่เงียบเกิน 14 วัน')).toBeTruthy();
    expect(within(row).getByText('฿1,240,000')).toBeTruthy();
    expect(within(row).getByText('เงียบ 27 วัน')).toBeTruthy();
    expect(screen.queryByTestId('follow-up-32')).toBeNull();
  });

  it('โทรแล้ว logs a CALL activity and the deal leaves the list after the refetch', async () => {
    renderMyWork();
    const row = await screen.findByTestId('follow-up-31');
    api.salesBoard.myWork.mockResolvedValue({ followUp: [], board: [{ ...quiet, quiet: false, daysQuiet: 0 }, fresh],
      quietDays: 14 });

    fireEvent.click(within(row).getByRole('button', { name: /โทรแล้ว/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ไม่รับสาย' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledTimes(1));
    const [id, payload] = api.tickets.addActivity.mock.calls[0];
    expect(id).toBe(31);
    expect(payload).toMatchObject({ kind: 'CALL', note: 'ไม่รับสาย' });
    expect(payload.activityDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    await waitFor(() => expect(screen.queryByTestId('follow-up-31')).toBeNull());
    expect(api.salesBoard.myWork).toHaveBeenCalledTimes(2);
  });

  it('ปิดดีลนี้ opens the mark-lost dialog, which still requires a reason', async () => {
    renderMyWork();
    const row = await screen.findByTestId('follow-up-31');

    fireEvent.click(within(row).getByRole('button', { name: 'ปิดดีลนี้' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('ทำเครื่องหมายเสียงาน')).toBeTruthy();
    const confirm = within(dialog).getByRole('button', { name: 'ยืนยันเสียงาน' });
    expect(confirm.disabled).toBe(true);

    fireEvent.click(within(dialog).getAllByRole('radio')[1]);
    fireEvent.click(confirm);
    await waitFor(() => expect(api.tickets.markLost).toHaveBeenCalledTimes(1));
    expect(api.tickets.markLost.mock.calls[0][0]).toBe(31);
    expect(api.tickets.markLost.mock.calls[0][1].reason).toBeTruthy();
  });

  it('says there is nothing to chase when no deal is quiet', async () => {
    api.salesBoard.myWork.mockResolvedValue({ followUp: [], board: [fresh], quietDays: 14 });
    renderMyWork();

    expect(await screen.findByText('ไม่มีดีลค้าง — ทุกดีลมีความเคลื่อนไหวใน 14 วันที่ผ่านมา')).toBeTruthy();
  });

  it('renders the board with every deal', async () => {
    renderMyWork();

    await screen.findByTestId('deal-board');
    expect(screen.getByTestId('board-card-31')).toBeTruthy();
    expect(screen.getByTestId('board-card-32')).toBeTruthy();
  });
});
