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

// MyWork hosts the flows; its page body (SalesOverview) is replaced by one button per opener.
function renderMyWork(props = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MyWork showToast={vi.fn()} {...props}>
          {(ctx) => <button type="button" onClick={ctx.openCallPicker}>บันทึกการโทร</button>}
        </MyWork>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** บันทึกการโทร → pick the งาน → the call dialog. */
async function openCallDialog(name = /สยามพารากอน/) {
  fireEvent.click(await screen.findByRole('button', { name: 'บันทึกการโทร' }));
  const picker = await screen.findByRole('dialog');
  await waitFor(() => expect(within(picker).getAllByRole('button', { name }).length).toBeGreaterThan(0));
  fireEvent.click(within(picker).getAllByRole('button', { name })[0]);
  return screen.findByRole('dialog', { name: /โทรแล้ว/ });
}

describe('MyWork', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.salesBoard.myWork.mockResolvedValue({ followUp: [quiet], board: [quiet, fresh], quietDays: 14 });
    api.tickets.addActivity.mockResolvedValue({ id: 1 });
    api.tickets.markLost.mockResolvedValue({});
  });

  it('โทรแล้ว logs a CALL activity and refetches the work', async () => {
    renderMyWork();
    const dialog = await openCallDialog();
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ไม่รับสาย' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledTimes(1));
    const [id, payload] = api.tickets.addActivity.mock.calls[0];
    expect(id).toBe(31);
    expect(payload).toMatchObject({ kind: 'CALL', note: 'ไม่รับสาย' });
    expect(payload.activityDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    await waitFor(() => expect(api.salesBoard.myWork).toHaveBeenCalledTimes(2));
  });

  it('โทรแล้ว on a folded project card logs the call on its lead order, not the root', async () => {
    const folded = { ...quiet, leadTicketId: 77, orderCount: 2 };
    api.salesBoard.myWork.mockResolvedValue({ followUp: [folded], board: [folded], quietDays: 14 });
    renderMyWork();
    const dialog = await openCallDialog();
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ไม่รับสาย' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledTimes(1));
    expect(api.tickets.addActivity.mock.calls[0][0]).toBe(77);
  });

  it('โทรแล้ว with ตามครั้งถัดไป 2 สัปดาห์ sends the deal\'s next follow-up date with the CALL', async () => {
    renderMyWork();
    const dialog = await openCallDialog();
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
    const dialog = await openCallDialog();

    const save = within(dialog).getByRole('button', { name: 'บันทึก' });
    expect(save.disabled).toBe(true);
    fireEvent.click(within(dialog).getByRole('radio', { name: 'คุยแล้ว ยังสนใจ' }));
    expect(save.disabled).toBe(false);
  });
});
