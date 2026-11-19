import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../api/index.js';
import { WhatsNewPage } from './WhatsNewPage.jsx';

globalThis.React = React;

// Every real release has an everyone entry, so "mine" is never empty with real data; this forces the empty state.
const audience = vi.hoisted(() => ({ emptyMine: false }));
vi.mock('./audiences.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, entriesForViewer: (...args) => (audience.emptyMine ? [] : actual.entriesForViewer(...args)) };
});

vi.mock('../../api/index.js', () => ({
  api: {
    notifications: { list: vi.fn(), markRead: vi.fn() },
    releaseNotes: { audience: vi.fn(), publishWithEmail: vi.fn(), email: vi.fn() },
  },
}));

const sales = { id: 6, role: 'sales', employeeId: 41, name: 'ยา' };
const hr = { id: 2, role: 'hr', employeeId: 47, name: 'บุคคล' };
const ceo = { id: 8, role: 'ceo', employeeId: 48, name: 'ซีอีโอ' };

function renderPage(user, path = '/whats-new/2026-11-16', showToast = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const { unmount } = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/whats-new" element={<WhatsNewPage user={user} showToast={showToast} />} />
          <Route path="/whats-new/:releaseId" element={<WhatsNewPage user={user} showToast={showToast} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { showToast, unmount };
}

describe('WhatsNewPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.notifications.list.mockResolvedValue({ notifications: [] });
    api.notifications.markRead.mockResolvedValue({});
  });

  it('"ทั้งหมด" is for hr, ceo and the owner (admin flag) only — every other role has just their own tab', async () => {
    for (const role of ['sales', 'sales_manager', 'account', 'import', 'warehouse']) {
      const { unmount } = renderPage({ id: 9, role, employeeId: 50, name: 'x' });
      await screen.findByRole('tab', { name: /สำหรับแผนกของคุณ/ });
      expect(screen.queryByRole('tab', { name: /ทั้งหมด/ }), role).toBeNull();
      unmount();
    }
    // The owner is gated on the admin flag, not a role: a plain-role admin still sees it.
    for (const user of [hr, ceo, { id: 1, role: 'employee', employeeId: 1, name: 'เจ้าของ', admin: true }]) {
      const { unmount } = renderPage(user);
      expect(await screen.findByRole('tab', { name: /ทั้งหมด/ }), user.role).toBeTruthy();
      unmount();
    }
  });

  it('marks this release’s unread bell notice read on open, and nothing else', async () => {
    api.notifications.list.mockResolvedValue({
      notifications: [
        { id: 11, type: 'RELEASE_NOTE', link: '/whats-new/2026-11-16', read: false, message: 'm', createdAt: '2026-11-16T01:00:00Z' },
        { id: 12, type: 'RELEASE_NOTE', link: '/whats-new/2026-01-01', read: false, message: 'm', createdAt: '2026-01-01T01:00:00Z' },
        { id: 13, type: 'APPROVED', link: '/whats-new/2026-11-16', read: false, message: 'm', createdAt: '2026-11-16T01:00:00Z' },
        { id: 14, type: 'RELEASE_NOTE', link: '/whats-new/2026-11-16', read: true, message: 'm', createdAt: '2026-11-16T01:00:00Z' },
      ],
    });

    renderPage(sales);

    await waitFor(() => expect(api.notifications.markRead).toHaveBeenCalledWith(11));
    expect(api.notifications.markRead).toHaveBeenCalledTimes(1);
  });

  describe('admin control (CEO / HR)', () => {
    // The per-person email truth (Audience.email): every targeted person in exactly one bucket.
    const emailStatus = {
      sent: 0, failed: 0, sending: 0, unconfirmed: 0, notYetEmailed: 8, withoutEmail: 1,
      withoutEmailNames: ['หมวย'], failedNames: [], unconfirmedNames: [],
    };
    const audience = {
      targeted: 9, alreadyNotified: 3, pendingNotification: 6, alreadyEmailed: 0, pendingEmail: 8, withoutEmail: 1,
      email: emailStatus,
    };

    beforeEach(() => {
      api.releaseNotes.audience.mockResolvedValue(audience);
      api.releaseNotes.publishWithEmail.mockResolvedValue({
        targeted: 9, inserted: 6, alreadyNotified: 3,
        email: { targeted: 9, queued: 8, alreadyEmailed: 0, withoutEmail: 1 }, emailError: null,
      });
      api.releaseNotes.email.mockResolvedValue({ targeted: 9, queued: 2, alreadyEmailed: 6, withoutEmail: 1 });
    });

    it('is not shown to anyone outside CEO / HR', async () => {
      for (const role of ['sales', 'sales_manager', 'account', 'import', 'warehouse', 'qc', 'employee']) {
        const { unmount } = render(
          <QueryClientProvider client={new QueryClient()}>
            <MemoryRouter initialEntries={['/whats-new/2026-11-16']}>
              <Routes>
                <Route path="/whats-new/:releaseId" element={<WhatsNewPage user={{ ...sales, role }} showToast={vi.fn()} />} />
              </Routes>
            </MemoryRouter>
          </QueryClientProvider>,
        );
        await screen.findAllByRole('tab');
        expect(screen.queryByRole('button', { name: 'เผยแพร่ประกาศ' }), role).toBeNull();
        expect(screen.queryByRole('button', { name: 'ส่งอีเมลให้คนที่ยังไม่ได้รับ' }), role).toBeNull();
        unmount();
      }
      expect(api.releaseNotes.audience).not.toHaveBeenCalled();
    });

    it('shows the true email outcome per person: sent, failed and no address, by name', async () => {
      api.releaseNotes.audience.mockResolvedValue({
        ...audience, alreadyEmailed: 5, pendingEmail: 2,
        email: {
          ...emailStatus, sent: 5, failed: 1, unconfirmed: 1, notYetEmailed: 1,
          failedNames: ['วาริส'], unconfirmedNames: ['ยา'],
        },
      });
      renderPage(hr);

      const status = await screen.findByRole('region', { name: 'ผลการส่งอีเมล' });
      await within(status).findByText(/ส่งสำเร็จ/);
      expect(status.textContent).toMatch(/ส่งสำเร็จ\s*5/);
      expect(status.textContent).toMatch(/ส่งไม่สำเร็จ\s*1/);
      expect(status.textContent).toMatch(/ไม่มีอีเมล\s*1/);
      expect(status.textContent).toMatch(/ไม่ทราบผล\s*1/);
      expect(status.textContent).toContain('วาริส');
      expect(status.textContent).toContain('หมวย');
      expect(status.textContent).toContain('ยา');
    });
  });
});

