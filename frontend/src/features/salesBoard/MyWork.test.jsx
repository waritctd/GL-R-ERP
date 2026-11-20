import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MyWork } from './MyWork.jsx';
import { api } from '../../api/index.js';
import { addDaysIso, bangkokTodayIso } from '../../utils/format.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      salesBoard: { myWork: vi.fn() },
      tickets: { addActivity: vi.fn(), markLost: vi.fn(), create: vi.fn(), resume: vi.fn() },
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
    },
  };
});

// The real modal fetches customers/projects; this page only has to open it.
vi.mock('../tickets/TicketCreateModal.jsx', () => ({
  // The submit button lets the toast test below drive onSubmit without the real form.
  TicketCreateModal: ({ onSubmit }) => (
    <div role="dialog">
      สร้างดีลใหม่ (stub)
      <button type="button" onClick={() => onSubmit({ title: 'stub' })}>stub submit</button>
    </div>
  ),
}));

const quiet = {
  ticketId: 31, title: 'สยามพารากอน · ห้องน้ำชั้น 3', customerName: 'สยามพิวรรธน์', projectName: null, ownerId: 6,
  ownerName: 'คุณสมหมาย', salesStage: 'QUOTE_DESIGN_SIDE', repCode: 'S3', boardColumn: 3, value: 1240000,
  designerName: 'เทพสตูดิโอ ดีไซน์', winProbability: 40, expectedQuarter: null,
  lastTouchedAt: '2026-10-19T00:00:00Z', daysQuiet: 27, quiet: true, currency: 'THB', valueExpired: false,
  lastActivityKind: null, lastActivityNote: null,
};
const fresh = { ...quiet, ticketId: 32, title: 'BMW Showroom', daysQuiet: 2, quiet: false };

function renderMyWork(props = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><MyWork showToast={vi.fn()} {...props} /></MemoryRouter>
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

  it('โทรแล้ว on a folded project card logs the call on its lead order, not the root', async () => {
    const folded = { ...quiet, leadTicketId: 77, orderCount: 2 };
    api.salesBoard.myWork.mockResolvedValue({ followUp: [folded], board: [folded], quietDays: 14 });
    renderMyWork();
    const row = await screen.findByTestId('follow-up-31');
    fireEvent.click(within(row).getByRole('button', { name: /โทรแล้ว/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ไม่รับสาย' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledTimes(1));
    expect(api.tickets.addActivity.mock.calls[0][0]).toBe(77);
  });

  it('โทรแล้ว with ตามครั้งถัดไป 2 สัปดาห์ sends the deal\'s next follow-up date with the CALL', async () => {
    renderMyWork();
    const row = await screen.findByTestId('follow-up-31');
    fireEvent.click(within(row).getByRole('button', { name: /โทรแล้ว/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('radio', { name: 'คุยแล้ว ยังสนใจ' }));
    fireEvent.click(within(dialog).getByRole('radio', { name: '2 สัปดาห์' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledTimes(1));
    const [id, payload] = api.tickets.addActivity.mock.calls[0];
    expect(id).toBe(31);
    expect(payload).toStrictEqual({
      kind: 'CALL', activityDate: bangkokTodayIso(), note: 'คุยแล้ว ยังสนใจ',
      nextFollowUpAt: addDaysIso(bangkokTodayIso(), 14),
    });
  });
});

// ── review fixes (M1, M3, M4, LOW error toasts, design gaps) ─────────────────────────────────

describe('MyWork — review fixes', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.salesBoard.myWork.mockResolvedValue({ followUp: [quiet], board: [quiet, fresh], quietDays: 14 });
    api.tickets.addActivity.mockResolvedValue({ id: 1 });
    api.tickets.markLost.mockResolvedValue({});
  });

  it('the call dialog cannot save without an outcome (M4)', async () => {
    renderMyWork();
    const row = await screen.findByTestId('follow-up-31');
    fireEvent.click(within(row).getByRole('button', { name: /โทรแล้ว/ }));
    const dialog = await screen.findByRole('dialog');

    const save = within(dialog).getByRole('button', { name: 'บันทึก' });
    expect(save.disabled).toBe(true);
    fireEvent.click(within(dialog).getByRole('radio', { name: 'คุยแล้ว ยังสนใจ' }));
    expect(save.disabled).toBe(false);
  });

  it('the board defaults to ไตรมาสนี้ once at least half of the open deals have an expected quarter', async () => {
    const now = new Date();
    const thisQuarter = `${now.getFullYear()}-Q${Math.floor(now.getMonth() / 3) + 1}`;
    api.salesBoard.myWork.mockResolvedValue({
      followUp: [],
      board: [{ ...fresh, ticketId: 81, expectedQuarter: thisQuarter }, { ...fresh, ticketId: 82, expectedQuarter: null }],
      quietDays: 14,
    });
    renderMyWork();

    await screen.findByTestId('board-card-81');
    expect(screen.getByRole('button', { name: 'ไตรมาสนี้' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.queryByTestId('board-card-82')).toBeNull();
  });

  it('a USD deal shows USD and stays out of the ฿ total (M1)', async () => {
    api.salesBoard.myWork.mockResolvedValue({
      followUp: [],
      board: [{ ...fresh, ticketId: 51, value: 1000, currency: 'USD' }, { ...fresh, ticketId: 52, value: 2000, currency: 'THB' }],
      quietDays: 14,
    });
    renderMyWork();

    const card = await screen.findByTestId('board-card-51');
    expect(within(card).getByText('USD 1,000')).toBeTruthy();
    expect(screen.getByTestId('board-total').textContent).toBe('฿2,000 + USD 1,000');
  });
});
