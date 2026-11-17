import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../api/index.js';
import { recordPage } from './lastPage.js';
import { WhatsNewPage } from './WhatsNewPage.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    notifications: { list: vi.fn(), markRead: vi.fn() },
    releaseNotes: { audience: vi.fn(), publish: vi.fn(), email: vi.fn() },
    feedback: { submit: vi.fn(), listMine: vi.fn(), listAll: vi.fn(), decide: vi.fn(), update: vi.fn() },
  },
}));

const sales = { id: 6, role: 'sales', employeeId: 41, name: 'ผู้ทดสอบ หนึ่ง' };
const ceo = { id: 8, role: 'ceo', employeeId: 48, name: 'ซีอีโอ', admin: false };
const admin = { id: 9, role: 'employee', employeeId: 49, name: 'แอดมิน', admin: true };

const TAB_NAME = 'แจ้งปัญหา / ขอฟีเจอร์';
const DETAILS = 'กดพิมพ์ใบเสนอราคาแล้ว PDF ตัดบรรทัดสุดท้ายของตาราง';

function renderPage(user, path = '/whats-new/2026-11-16?tab=feedback') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/whats-new" element={<WhatsNewPage user={user} showToast={vi.fn()} />} />
          <Route path="/whats-new/:releaseId" element={<WhatsNewPage user={user} showToast={vi.fn()} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const row = (over) => ({
  id: 14, kind: 'BUG', area: 'QUOTATIONS', details: 'PDF ตัดบรรทัดสุดท้ายของตารางสินค้า', extra: null,
  impact: 'WORKAROUND', pagePath: '/quotations/812', userAgent: 'Chrome', viewport: '390x844',
  reporterEmployeeId: 41, reporterName: 'ผู้ทดสอบ หนึ่ง', reporterDepartment: 'ฝ่ายขาย', reporterRole: 'sales',
  status: 'PENDING', eta: null, denyReason: null, createdAt: '2026-11-14T03:42:00Z', updatedAt: '2026-11-14T03:42:00Z',
  ...over,
});

const type = (label, value) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const typeDate = (scope, label, beDate) => {
  const input = within(scope).getByLabelText(label);
  fireEvent.change(input, { target: { value: beDate } });
  fireEvent.blur(input);
};
const sendButton = () => screen.getByRole('button', { name: /^ส่ง$/ });

function Jump({ to }) {
  const navigate = useNavigate();
  return <button type="button" onClick={() => navigate(to)}>jump</button>;
}

describe('feedback tab: staff', () => {
  let mine;

  beforeEach(() => {
    vi.clearAllMocks();
    mine = [];
    api.notifications.list.mockResolvedValue({ notifications: [] });
    api.notifications.markRead.mockResolvedValue({});
    api.feedback.listMine.mockImplementation(async () => mine);
    api.feedback.submit.mockImplementation(async (payload) => {
      const saved = row({ id: 15, ...payload, status: 'PENDING' });
      mine = [saved, ...mine];
      return saved;
    });
    recordPage('/somewhere/else');
  });

  it('adds a third tab "แจ้งปัญหา / ขอฟีเจอร์" and never asks the server for the admin list', async () => {
    renderPage(ceo); // ceo is NOT the admin capability

    expect(await screen.findByRole('tab', { name: TAB_NAME })).toBeTruthy();
    expect(screen.getAllByRole('tab')).toHaveLength(3);
    expect(api.feedback.listAll).not.toHaveBeenCalled();
    expect(screen.queryByText(/เรื่องที่แจ้งเข้ามา/)).toBeNull();
  });

  it('opens on the feedback tab for ?tab=feedback (the bell link), and on the release for a bare URL', async () => {
    renderPage(sales, '/whats-new?tab=feedback');
    expect((await screen.findByRole('tab', { name: TAB_NAME })).getAttribute('aria-selected')).toBe('true');
  });

  it('follows the URL when a bell row links to ?tab=feedback while the page is already open', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/whats-new']}>
          <Jump to="/whats-new?tab=feedback" />
          <Routes>
            <Route path="/whats-new" element={<WhatsNewPage user={sales} showToast={vi.fn()} />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect((await screen.findByRole('tab', { name: /สำหรับแผนกของคุณ/ })).getAttribute('aria-selected')).toBe('true');

    fireEvent.click(screen.getByRole('button', { name: 'jump' }));

    await waitFor(() => expect(screen.getByRole('tab', { name: TAB_NAME }).getAttribute('aria-selected')).toBe('true'));
    // A manual click still works afterwards.
    fireEvent.click(screen.getByRole('tab', { name: /สำหรับแผนกของคุณ/ }));
    expect(screen.getByRole('tab', { name: /สำหรับแผนกของคุณ/ }).getAttribute('aria-selected')).toBe('true');
  });

  it('counts emoji as one character each: 5 emoji are too short, like 5 Thai characters', async () => {
    renderPage(sales);
    await screen.findByRole('tab', { name: TAB_NAME });
    type('เกี่ยวกับหน้าไหน', 'LEAVE');
    type('เกิดอะไรขึ้น', '😀😀😀😀😀');

    fireEvent.click(sendButton());

    expect(await screen.findByText('เล่าอย่างน้อยหนึ่งประโยค เพื่อให้คนรับเรื่องเข้าใจ')).toBeTruthy();
    expect(api.feedback.submit).not.toHaveBeenCalled();
  });

  it('a bare /whats-new still opens on the release tab', async () => {
    renderPage(sales, '/whats-new');
    expect((await screen.findByRole('tab', { name: /สำหรับแผนกของคุณ/ })).getAttribute('aria-selected')).toBe('true');
  });

  it('refuses an empty submit with the two required-field messages, focuses the first, and sends nothing', async () => {
    renderPage(sales);
    await screen.findByRole('tab', { name: TAB_NAME });

    fireEvent.click(sendButton());

    expect(await screen.findByText('เลือกหน้าที่เกี่ยวข้อง ถ้าไม่แน่ใจเลือก "อื่น ๆ / ไม่แน่ใจ"')).toBeTruthy();
    expect(screen.getByText('เล่าอย่างน้อยหนึ่งประโยค เพื่อให้คนรับเรื่องเข้าใจ')).toBeTruthy();
    expect(document.activeElement).toBe(screen.getByLabelText('เกี่ยวกับหน้าไหน'));
    expect(api.feedback.submit).not.toHaveBeenCalled();
  });

  it('details shorter than 10 characters is refused even when the area is chosen', async () => {
    renderPage(sales);
    await screen.findByRole('tab', { name: TAB_NAME });
    type('เกี่ยวกับหน้าไหน', 'LEAVE');
    type('เกิดอะไรขึ้น', 'สั้นไป');

    fireEvent.click(sendButton());

    expect(await screen.findByText('เล่าอย่างน้อยหนึ่งประโยค เพื่อให้คนรับเรื่องเข้าใจ')).toBeTruthy();
    expect(screen.queryByText(/เลือกหน้าที่เกี่ยวข้อง/)).toBeNull();
    expect(api.feedback.submit).not.toHaveBeenCalled();
  });

  it('pre-selects the area from the page they came from, and sends that path', async () => {
    recordPage('/quotations/812');
    renderPage(sales);
    await screen.findByRole('tab', { name: TAB_NAME });

    expect(screen.getByLabelText('เกี่ยวกับหน้าไหน').value).toBe('QUOTATIONS');
    type('เกิดอะไรขึ้น', DETAILS);
    fireEvent.click(sendButton());

    await waitFor(() => expect(api.feedback.submit).toHaveBeenCalledTimes(1));
    expect(api.feedback.submit.mock.calls[0][0]).toMatchObject({
      kind: 'BUG', area: 'QUOTATIONS', details: DETAILS, pagePath: '/quotations/812',
    });
  });

  it('leaves the area blank when the previous page maps to nothing', async () => {
    recordPage('/somewhere/else');
    renderPage(sales);
    await screen.findByRole('tab', { name: TAB_NAME });

    expect(screen.getByLabelText('เกี่ยวกับหน้าไหน').value).toBe('');
  });

  it('never sends identity: the payload has no name, department, role or status', async () => {
    recordPage('/leave');
    renderPage(sales);
    await screen.findByRole('tab', { name: TAB_NAME });
    type('เกิดอะไรขึ้น', DETAILS);
    fireEvent.click(sendButton());

    await waitFor(() => expect(api.feedback.submit).toHaveBeenCalled());
    const payload = api.feedback.submit.mock.calls[0][0];
    expect(Object.keys(payload).sort()).toEqual(
      ['area', 'details', 'extra', 'impact', 'kind', 'pagePath', 'userAgent', 'viewport'].sort(),
    );
  });

  it('submits the optional fields, shows the confirmation, clears the form and lists it under "ที่ฉันแจ้ง"', async () => {
    recordPage('/quotations/812');
    renderPage(sales);
    expect(await screen.findByText('ที่ฉันแจ้ง (0)')).toBeTruthy();
    type('เกิดอะไรขึ้น', DETAILS);
    type(/ควรเป็นอย่างไร/, 'ตารางต้องแสดงครบทุกบรรทัด');
    fireEvent.click(screen.getByRole('radio', { name: 'ทำได้ แต่ต้องอ้อมหรือช้าลง' }));

    fireEvent.click(sendButton());

    const confirmation = await screen.findByRole('status');
    expect(confirmation.textContent).toContain('ส่งแล้ว เลขที่ #15');
    expect(api.feedback.submit.mock.calls[0][0]).toMatchObject({
      extra: 'ตารางต้องแสดงครบทุกบรรทัด', impact: 'WORKAROUND',
    });
    expect(screen.getByLabelText('เกิดอะไรขึ้น').value).toBe('');
    expect(await screen.findByText('ที่ฉันแจ้ง (1)')).toBeTruthy();
    const list = screen.getByRole('list', { name: 'ที่ฉันแจ้ง' });
    expect(within(list).getByText(/#15/)).toBeTruthy();
    expect(within(list).getByText('รอพิจารณา')).toBeTruthy();
  });

  it('switching kind to ขอฟีเจอร์ changes the questions and impact scale, keeps the text, drops the impact', async () => {
    renderPage(sales);
    await screen.findByRole('tab', { name: TAB_NAME });
    type('เกิดอะไรขึ้น', DETAILS);
    fireEvent.click(screen.getByRole('radio', { name: 'ทำได้ แต่ต้องอ้อมหรือช้าลง' }));

    fireEvent.click(screen.getByRole('radio', { name: /ขอฟีเจอร์/ }));

    expect(screen.queryByLabelText('เกิดอะไรขึ้น')).toBeNull();
    expect(screen.getByLabelText('อยากให้ทำอะไรได้').value).toBe(DETAILS);
    expect(screen.getByLabelText(/ตอนนี้ทำอย่างไรอยู่/)).toBeTruthy();
    expect(screen.getByText(/จะได้ใช้บ่อยแค่ไหน/)).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'ต้องใช้ทุกวัน' })).toBeTruthy();
    expect(screen.queryByRole('radio', { name: 'ทำได้ แต่ต้องอ้อมหรือช้าลง' })).toBeNull();
    expect(screen.getAllByRole('radio', { checked: true }).map((r) => r.getAttribute('name') ?? r.name))
      .not.toContain('imp');
    fireEvent.click(screen.getByRole('radio', { name: 'ต้องใช้ทุกวัน' }));
    fireEvent.change(screen.getByLabelText('เกี่ยวกับหน้าไหน'), { target: { value: 'CATALOG' } });
    fireEvent.click(sendButton());
    await waitFor(() => expect(api.feedback.submit).toHaveBeenCalled());
    expect(api.feedback.submit.mock.calls[0][0]).toMatchObject({ kind: 'FEATURE', impact: 'DAILY' });
  });

  it('shows the reporter ETA with the 4-step progress, "เสร็จแล้ว" for RELEASED, and the deny reason', async () => {
    mine = [
      row({ id: 14, status: 'IN_PROGRESS', eta: '2026-11-21' }),
      row({ id: 11, status: 'RELEASED', eta: '2026-11-12', details: 'วันที่ 9 ขึ้นว่าขาดงานทั้งที่สแกนแล้ว' }),
      row({ id: 12, kind: 'FEATURE', status: 'DENIED', denyReason: 'ราคาทุนเปิดให้เฉพาะผู้บริหารตามนโยบาย', details: 'อยากให้แคตตาล็อกแสดงราคาทุน' }),
    ];
    renderPage(sales);

    expect(await screen.findByText('ที่ฉันแจ้ง (3)')).toBeTruthy();
    const items = within(screen.getByRole('list', { name: 'ที่ฉันแจ้ง' })).getAllByRole('listitem')
      .filter((li) => li.textContent.includes('#'));

    const active = items.find((li) => li.textContent.includes('#14'));
    expect(within(active).getByText('รับเรื่องแล้ว')).toBeTruthy();
    expect(active.textContent).toMatch(/กำหนดเสร็จ.*21 พ\.ย\./);
    expect(within(active).getByRole('list', { name: 'ความคืบหน้า ขั้นที่ 2 จาก 4: กำลังทำ' })).toBeTruthy();

    const released = items.find((li) => li.textContent.includes('#11'));
    expect(within(released).getByText('เสร็จแล้ว')).toBeTruthy();
    expect(within(released).getByRole('list', { name: 'ความคืบหน้า ขั้นที่ 4 จาก 4: ใช้งานได้แล้ว' })).toBeTruthy();

    const denied = items.find((li) => li.textContent.includes('#12'));
    expect(within(denied).getByText('ไม่รับ')).toBeTruthy();
    expect(denied.textContent).toContain('เหตุผลที่ไม่รับ: ราคาทุนเปิดให้เฉพาะผู้บริหารตามนโยบาย');
    expect(within(denied).queryByRole('list', { name: /ความคืบหน้า/ })).toBeNull();
  });

  it('renders user text as text, never as markup', async () => {
    mine = [row({ details: '<img src=x onerror=alert(1)> ปัญหา' })];
    renderPage(sales);

    expect(await screen.findByText(/<img src=x onerror=alert\(1\)> ปัญหา/)).toBeTruthy();
    expect(document.querySelector('img[src="x"]')).toBeNull();
  });
});

describe('feedback tab: admin', () => {
  let all;

  beforeEach(() => {
    vi.clearAllMocks();
    api.notifications.list.mockResolvedValue({ notifications: [] });
    api.notifications.markRead.mockResolvedValue({});
    api.feedback.listMine.mockResolvedValue([]);
    all = [
      row({ id: 13, reporterName: 'ผู้ทดสอบ สอง', reporterDepartment: 'ฝ่ายบัญชี', reporterRole: 'employee', area: 'LEAVE', pagePath: '/leave', details: 'อยากเห็นวันลาคงเหลือของทีมในหน้าเดียว' }),
      row({ id: 15, status: 'PENDING', details: 'ปุ่มบันทึกกดไม่ได้บนมือถือ' }),
      row({ id: 14, status: 'IN_PROGRESS', eta: '2026-11-21' }),
    ];
    api.feedback.listAll.mockImplementation(async () => all);
    api.feedback.decide.mockResolvedValue(row({ status: 'ACCEPTED' }));
    api.feedback.update.mockResolvedValue(row({ status: 'TESTING' }));
  });

  it('labels the tab "เรื่องที่แจ้งเข้ามา (N รอพิจารณา)" with N = the PENDING count', async () => {
    renderPage(admin);

    expect(await screen.findByRole('tab', { name: 'เรื่องที่แจ้งเข้ามา (2 รอพิจารณา)' })).toBeTruthy();
    expect(screen.queryByRole('tab', { name: TAB_NAME })).toBeNull();
    expect(api.feedback.listAll).toHaveBeenCalled();
  });

  it('lists every submission with who/department/role/page, and offers the decision only on PENDING ones', async () => {
    renderPage(admin);

    expect(await screen.findByText('เรื่องที่แจ้งเข้ามา (3)')).toBeTruthy();
    const items = screen.getAllByRole('listitem').filter((li) => li.textContent.includes('#1'));
    const first = items.find((li) => li.textContent.includes('#13'));
    expect(first.textContent).toContain('ผู้ทดสอบ สอง');
    expect(first.textContent).toContain('ฝ่ายบัญชี');
    expect(first.textContent).toContain('/leave');
    // Thai role label, not the stored code.
    expect(first.textContent).toContain('พนักงานทั่วไป');
    expect(first.textContent).not.toMatch(/·\s*employee/);
    const rep = items.find((li) => li.textContent.includes('#15'));
    expect(rep.textContent).toContain('ฝ่ายขาย');
    expect(rep.textContent).not.toMatch(/·\s*sales(\s|$)/);
    expect(within(first).getByRole('button', { name: /รับเรื่อง/ })).toBeTruthy();
    expect(within(first).getByRole('button', { name: 'ไม่รับ' })).toBeTruthy();
    const inProgress = items.find((li) => li.textContent.includes('#14'));
    expect(within(inProgress).queryByRole('button', { name: 'ไม่รับ' })).toBeNull();
  });

  it('accepting needs an ETA: no date shows "เลือกวันกำหนดเสร็จ" and sends nothing; a date sends ACCEPT + ISO eta', async () => {
    renderPage(admin);
    const item = (await screen.findByText(/อยากเห็นวันลาคงเหลือ/)).closest('li');
    fireEvent.click(within(item).getByRole('button', { name: /รับเรื่อง/ }));

    fireEvent.click(within(item).getByRole('button', { name: 'ยืนยันรับเรื่อง' }));
    expect(await within(item).findByText('เลือกวันกำหนดเสร็จ')).toBeTruthy();
    expect(api.feedback.decide).not.toHaveBeenCalled();

    typeDate(item, 'กำหนดเสร็จ', '05/12/2569');
    fireEvent.click(within(item).getByRole('button', { name: 'ยืนยันรับเรื่อง' }));

    await waitFor(() => expect(api.feedback.decide).toHaveBeenCalledWith(13, { decision: 'ACCEPT', eta: '2026-12-05' }));
  });

  it('denying needs a reason of at least 5 characters, then sends DENY + the reason', async () => {
    renderPage(admin);
    const item = (await screen.findByText(/อยากเห็นวันลาคงเหลือ/)).closest('li');
    fireEvent.click(within(item).getByRole('button', { name: 'ไม่รับ' }));

    fireEvent.change(within(item).getByLabelText('เหตุผลที่ไม่รับ'), { target: { value: 'สั้น' } });
    fireEvent.click(within(item).getByRole('button', { name: 'ยืนยันไม่รับ' }));
    expect(await within(item).findByText('เขียนเหตุผลให้ผู้แจ้งเข้าใจ')).toBeTruthy();
    expect(api.feedback.decide).not.toHaveBeenCalled();

    fireEvent.change(within(item).getByLabelText('เหตุผลที่ไม่รับ'), { target: { value: 'ไม่อยู่ในแผนปีนี้' } });
    fireEvent.click(within(item).getByRole('button', { name: 'ยืนยันไม่รับ' }));

    await waitFor(() => expect(api.feedback.decide).toHaveBeenCalledWith(13, { decision: 'DENY', reason: 'ไม่อยู่ในแผนปีนี้' }));
  });

  it('an accepted item can change progress (any step) and ETA, each as its own update call', async () => {
    renderPage(admin);
    const item = (await screen.findByText(/PDF ตัดบรรทัดสุดท้าย/)).closest('li');

    fireEvent.change(within(item).getByLabelText('ความคืบหน้า'), { target: { value: 'TESTING' } });
    await waitFor(() => expect(api.feedback.update).toHaveBeenCalledWith(14, { status: 'TESTING' }));

    typeDate(item, 'กำหนดเสร็จ', '28/11/2569');
    await waitFor(() => expect(api.feedback.update).toHaveBeenCalledWith(14, { eta: '2026-11-28' }));
    expect(within(item).getAllByRole('option').map((o) => o.textContent))
      .toEqual(['1. รับเรื่อง', '2. กำลังทำ', '3. ทดสอบ', '4. ใช้งานได้แล้ว']);
  });

  it('a DENIED item is final: no decision buttons, no progress control', async () => {
    all = [row({ id: 12, status: 'DENIED', denyReason: 'ไม่อยู่ในแผนปีนี้', details: 'อยากให้แคตตาล็อกแสดงราคาทุน' })];
    renderPage(admin);

    const item = (await screen.findByText(/อยากให้แคตตาล็อกแสดงราคาทุน/)).closest('li');
    expect(item.textContent).toContain('ไม่อยู่ในแผนปีนี้');
    expect(within(item).queryByRole('button')).toBeNull();
    expect(within(item).queryByLabelText('ความคืบหน้า')).toBeNull();
  });

  it('the admin tab is the inbox only: no submit form', async () => {
    renderPage(admin);
    await screen.findByText('เรื่องที่แจ้งเข้ามา (3)');

    expect(screen.queryByRole('button', { name: /^ส่ง$/ })).toBeNull();
  });
});
