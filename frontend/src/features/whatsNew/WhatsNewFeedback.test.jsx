import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
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
    feedback: { submit: vi.fn(), listMine: vi.fn(), listAll: vi.fn(), decide: vi.fn(), update: vi.fn(), people: vi.fn(), setRequesters: vi.fn() },
  },
}));

const ceo = { id: 8, role: 'ceo', employeeId: 48, name: 'ซีอีโอ', admin: false };
const admin = { id: 9, role: 'employee', employeeId: 49, name: 'แอดมิน', admin: true };

const TAB_NAME = 'แจ้งปัญหา / ขอฟีเจอร์';

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

describe('feedback tab: staff', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.notifications.list.mockResolvedValue({ notifications: [] });
    api.notifications.markRead.mockResolvedValue({});
    api.feedback.listMine.mockResolvedValue([]);
    recordPage('/somewhere/else');
  });

  it('adds a third tab "แจ้งปัญหา / ขอฟีเจอร์" and never asks the server for the admin list', async () => {
    renderPage(ceo); // ceo is NOT the admin capability

    expect(await screen.findByRole('tab', { name: TAB_NAME })).toBeTruthy();
    expect(screen.getAllByRole('tab')).toHaveLength(3);
    expect(api.feedback.listAll).not.toHaveBeenCalled();
    expect(screen.queryByText(/เรื่องที่แจ้งเข้ามา/)).toBeNull();
  });
});

describe('feedback tab: admin', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.notifications.list.mockResolvedValue({ notifications: [] });
    api.notifications.markRead.mockResolvedValue({});
    api.feedback.listMine.mockResolvedValue([]);
    api.feedback.listAll.mockResolvedValue([
      row({ id: 13, reporterName: 'ผู้ทดสอบ สอง', reporterDepartment: 'ฝ่ายบัญชี', reporterRole: 'employee', area: 'LEAVE', pagePath: '/leave', details: 'อยากเห็นวันลาคงเหลือของทีมในหน้าเดียว' }),
      row({ id: 15, status: 'PENDING', details: 'ปุ่มบันทึกกดไม่ได้บนมือถือ' }),
      row({ id: 14, status: 'IN_PROGRESS', eta: '2026-11-21' }),
    ]);
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

  it('adds a whole team in one tap and saves the reporter plus the team', async () => {
    api.feedback.people.mockResolvedValue([
      { employeeId: 41, name: 'ผู้แจ้ง', department: 'ฝ่ายขาย', role: 'sales' },
      { employeeId: 42, name: 'เซลส์ สอง', department: 'ฝ่ายขาย', role: 'sales' },
      { employeeId: 43, name: 'บัญชี หนึ่ง', department: 'ฝ่ายบัญชี', role: 'account' },
    ]);
    api.feedback.setRequesters.mockResolvedValue(row({ id: 15 }));
    renderPage(admin);

    await screen.findByText('เรื่องที่แจ้งเข้ามา (3)');
    const item = screen.getAllByRole('listitem').find((li) => li.textContent.includes('#15'));
    fireEvent.click(within(item).getByRole('button', { name: 'แก้ไขผู้ขอ' }));
    fireEvent.click(await within(item).findByRole('button', { name: 'ทั้งฝ่ายบัญชี (1)' }));
    fireEvent.click(within(item).getByRole('button', { name: 'บันทึก (2 คน)' }));

    await waitFor(() => expect(api.feedback.setRequesters).toHaveBeenCalledWith(15, expect.arrayContaining([41, 43])));
  });
});
