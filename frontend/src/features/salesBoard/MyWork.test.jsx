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

  it('heads the page งานของฉัน and lists quiet deals with value and days quiet', async () => {
    renderMyWork();

    expect(screen.getByRole('heading', { name: 'งานของฉัน' })).toBeTruthy();
    const row = await screen.findByTestId('follow-up-31');
    expect(screen.getByText('ควรตามต่อ')).toBeTruthy();
    expect(screen.getByText('งานที่เงียบเกิน 14 วัน')).toBeTruthy();
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

  // Slice 6.4: โทรแล้ว · ตามครั้งถัดไป. Unused, the request has no nextFollowUpAt key at all.
  it('โทรแล้ว without ตามครั้งถัดไป sends exactly the CALL it sent before', async () => {
    renderMyWork();
    const row = await screen.findByTestId('follow-up-31');
    fireEvent.click(within(row).getByRole('button', { name: /โทรแล้ว/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ไม่รับสาย' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledTimes(1));
    const [, payload] = api.tickets.addActivity.mock.calls[0];
    expect(payload).toStrictEqual({ kind: 'CALL', activityDate: bangkokTodayIso(), note: 'ไม่รับสาย' });
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

  // ปิดงานนี้ (was ปิดดีลนี้) now opens the rebuilt ปิดงาน · เสียงาน dialog (drawing m-lost); it used to assert the old
  // 'ทำเครื่องหมายเสียงาน' title. The reason is still required.
  it('ปิดงานนี้ opens the mark-lost dialog, which still requires a reason', async () => {
    renderMyWork();
    const row = await screen.findByTestId('follow-up-31');

    fireEvent.click(within(row).getByRole('button', { name: 'ปิดงานนี้' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('ปิดงาน · เสียงาน')).toBeTruthy();
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

    expect(await screen.findByText('ไม่มีงานค้าง — ทุกงานมีความเคลื่อนไหวใน 14 วันที่ผ่านมา')).toBeTruthy();
  });

  it('renders the board with every deal', async () => {
    renderMyWork();

    await screen.findByTestId('deal-board');
    expect(screen.getByTestId('board-card-31')).toBeTruthy();
    expect(screen.getByTestId('board-card-32')).toBeTruthy();
  });

  // Owner ruling 2026-11-16: the sales screens say งาน; the list page is งานทั้งหมด.
  it('names the board and its links in งาน, never ดีล', async () => {
    renderMyWork();
    await screen.findByTestId('deal-board');

    expect(screen.getByText('งานทั้งหมดของฉัน')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'งานเงียบ' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'ดูงานทั้งหมด' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /รายการดีล/ })).toBeNull();
    expect(screen.queryByText('ดีลทั้งหมดของฉัน')).toBeNull();
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

  it('a failed call log shows the error toast and keeps the dialog open', async () => {
    const showToast = vi.fn();
    api.tickets.addActivity.mockRejectedValue(new Error('ไม่มีสิทธิ์'));
    renderMyWork({ showToast });
    const row = await screen.findByTestId('follow-up-31');
    fireEvent.click(within(row).getByRole('button', { name: /โทรแล้ว/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ไม่รับสาย' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ไม่มีสิทธิ์'));
    expect(screen.getByRole('dialog')).toBeTruthy();
  });

  it('a failed mark-lost shows the error toast', async () => {
    const showToast = vi.fn();
    api.tickets.markLost.mockRejectedValue(new Error('ปิดดีลไม่ได้'));
    renderMyWork({ showToast });
    const row = await screen.findByTestId('follow-up-31');
    fireEvent.click(within(row).getByRole('button', { name: 'ปิดงานนี้' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getAllByRole('radio')[1]);
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยืนยันเสียงาน' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ปิดดีลไม่ได้'));
  });

  // Owner ruling 2026-11-16: a paused deal whose วันที่จะกลับมาดู has arrived is in ควรตามต่อ until resumed.
  it('a returning paused deal can be resumed from ควรตามต่อ', async () => {
    const back = { ...quiet, ticketId: 40, title: 'งานที่พักไว้', quiet: false, returning: true, pauseReturnOn: '2026-11-16' };
    api.salesBoard.myWork.mockResolvedValue({ followUp: [back], board: [fresh], quietDays: 14 });
    api.tickets.resume.mockResolvedValue({});
    renderMyWork();

    const row = await screen.findByTestId('follow-up-40');
    fireEvent.click(within(row).getByRole('button', { name: 'ดำเนินการต่อ' }));

    await waitFor(() => expect(api.tickets.resume).toHaveBeenCalledWith(40, {}));
  });

  it('a follow-up row says what was done last (ครั้งล่าสุด)', async () => {
    api.salesBoard.myWork.mockResolvedValue({
      followUp: [{ ...quiet, lastActivityKind: 'CALL', lastActivityNote: 'ส่งตัวอย่าง' }], board: [quiet], quietDays: 14,
    });
    renderMyWork();

    const row = await screen.findByTestId('follow-up-31');
    expect(within(row).getByText('ครั้งล่าสุด: โทรศัพท์ · ส่งตัวอย่าง')).toBeTruthy();
  });

  it('the board filter ไตรมาสนี้ keeps only deals expected this quarter', async () => {
    const now = new Date();
    const thisQuarter = `${now.getFullYear()}-Q${Math.floor(now.getMonth() / 3) + 1}`;
    api.salesBoard.myWork.mockResolvedValue({
      followUp: [],
      board: [{ ...fresh, ticketId: 41, expectedQuarter: thisQuarter }, { ...fresh, ticketId: 42, expectedQuarter: null }],
      quietDays: 14,
    });
    renderMyWork();
    await screen.findByTestId('board-card-41');

    fireEvent.click(screen.getByRole('button', { name: 'ไตรมาสนี้' }));
    expect(screen.getByTestId('board-card-41')).toBeTruthy();
    expect(screen.queryByTestId('board-card-42')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'ทั้งหมด' }));
    expect(screen.getByTestId('board-card-42')).toBeTruthy();
  });

  // Round-2 LOW 1: most reps have not set expected quarters yet, so ไตรมาสนี้ would hide most of their board.
  it('the board defaults to ทั้งหมด while fewer than half of the open deals have an expected quarter', async () => {
    const now = new Date();
    const thisQuarter = `${now.getFullYear()}-Q${Math.floor(now.getMonth() / 3) + 1}`;
    api.salesBoard.myWork.mockResolvedValue({
      followUp: [],
      board: [
        { ...fresh, ticketId: 71, expectedQuarter: thisQuarter },
        { ...fresh, ticketId: 72, expectedQuarter: null },
        { ...fresh, ticketId: 73, expectedQuarter: null },
      ],
      quietDays: 14,
    });
    renderMyWork();

    await screen.findByTestId('board-card-73');
    expect(screen.getByRole('button', { name: 'ทั้งหมด' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByTestId('board-card-72')).toBeTruthy();
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

  // Round-2 LOW 2: ควรตามต่อ shows at most five; the rest are reachable from the board.
  it('ควรตามต่อ offers ดูทั้งหมด (n) past five quiet deals, opening the board filtered to quiet deals', async () => {
    const quietDeals = [1, 2, 3, 4, 5, 6, 7].map((n) => ({ ...quiet, ticketId: 100 + n, title: `เงียบ ${n}` }));
    api.salesBoard.myWork.mockResolvedValue({
      followUp: quietDeals.slice(0, 5), board: [...quietDeals, fresh], quietDays: 14,
    });
    renderMyWork();

    await screen.findByTestId('follow-up-101');
    fireEvent.click(screen.getByRole('button', { name: 'ดูทั้งหมด (7)' }));

    expect(screen.getByRole('button', { name: 'งานเงียบ' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByTestId('board-card-107')).toBeTruthy();
    expect(screen.queryByTestId('board-card-32')).toBeNull();
  });

  // Fix round 1, ruling 4: returning paused deals are never cut from ควรตามต่อ (the server lists all of them ahead of
  // the five quiet ones), the count includes them, and the board's งานเงียบ view — where ดูทั้งหมด leads — shows them too.
  it('ดูทั้งหมด counts and shows the returning paused deals as well as the quiet ones', async () => {
    const quietDeals = [1, 2, 3, 4, 5, 6].map((n) => ({ ...quiet, ticketId: 100 + n, title: `เงียบ ${n}` }));
    const back = { ...quiet, ticketId: 140, title: 'งานที่พักไว้', quiet: false, returning: true, pauseReturnOn: '2026-11-16' };
    api.salesBoard.myWork.mockResolvedValue({
      followUp: [back, ...quietDeals.slice(0, 5)], board: [...quietDeals, fresh], quietDays: 14,
    });
    renderMyWork();

    await screen.findByTestId('follow-up-140');
    fireEvent.click(screen.getByRole('button', { name: 'ดูทั้งหมด (7)' }));

    expect(screen.getByTestId('board-card-140')).toBeTruthy();
    expect(screen.getByTestId('board-card-106')).toBeTruthy();
  });

  it('ควรตามต่อ has no ดูทั้งหมด link at five quiet deals or fewer', async () => {
    renderMyWork();

    await screen.findByTestId('follow-up-31');
    expect(screen.queryByRole('button', { name: /ดูทั้งหมด/ })).toBeNull();
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

  it('an expired quotation value is flagged หมดอายุ (M3)', async () => {
    api.salesBoard.myWork.mockResolvedValue({
      followUp: [], board: [{ ...fresh, ticketId: 61, value: 5000, currency: 'THB', valueExpired: true }], quietDays: 14,
    });
    renderMyWork();

    const card = await screen.findByTestId('board-card-61');
    expect(within(card).getByText('หมดอายุ')).toBeTruthy();
  });

  it('งานใหม่ opens the existing create-deal modal', async () => {
    renderMyWork();
    await screen.findByTestId('follow-up-31');

    fireEvent.click(screen.getByRole('button', { name: /งานใหม่/ }));
    expect(await screen.findByText('สร้างดีลใหม่ (stub)')).toBeTruthy();
  });

  // Fix round 1, ruling 9: the create toast says งาน, as AllDealsPage and TicketListPage already do.
  it('a created deal is announced as สร้างงานเรียบร้อย', async () => {
    const showToast = vi.fn();
    api.tickets.create.mockResolvedValue({ ticket: { summary: { id: 77 } } });
    renderMyWork({ showToast });
    await screen.findByTestId('follow-up-31');

    fireEvent.click(screen.getByRole('button', { name: /งานใหม่/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'stub submit' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'สร้างงานเรียบร้อย'));
  });
});
