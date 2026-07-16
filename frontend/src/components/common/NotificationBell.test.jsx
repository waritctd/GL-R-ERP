import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationBell } from './NotificationBell.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    notifications: {
      list: vi.fn(),
      markRead: vi.fn(),
    },
  },
}));

function renderBell(onNavigate = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <NotificationBell onNavigate={onNavigate} />
    </QueryClientProvider>,
  );
}

describe('NotificationBell', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.notifications.list.mockResolvedValue({
      notifications: [
        { id: 1, message: 'ใบขอราคาถูกอนุมัติ', read: false, createdAt: new Date().toISOString(), link: '/tickets/1' },
        { id: 2, message: 'ออกใบแจ้งยอดมัดจำแล้ว', read: true, createdAt: new Date().toISOString(), link: null },
      ],
    });
    api.notifications.markRead.mockResolvedValue({});
  });

  it('renders items and the unread badge from a mocked api.notifications.list', async () => {
    renderBell();

    fireEvent.click(screen.getByRole('button', { name: 'การแจ้งเตือน' }));

    expect(await screen.findByText('ใบขอราคาถูกอนุมัติ')).not.toBeNull();
    expect(screen.getByText('ออกใบแจ้งยอดมัดจำแล้ว')).not.toBeNull();
    // Unread badge shows the count of unread items (1).
    expect(screen.getByText('1')).not.toBeNull();
  });

  it('marks an item read and invalidates the shared notifications cache', async () => {
    renderBell();

    fireEvent.click(screen.getByRole('button', { name: 'การแจ้งเตือน' }));
    const item = await screen.findByText('ใบขอราคาถูกอนุมัติ');
    fireEvent.click(item);

    await waitFor(() => expect(api.notifications.markRead).toHaveBeenCalledWith(1));
    // Invalidating queryKeys.notifications() triggers a background refetch —
    // this is the same key the bell polls with refetchInterval, and the key
    // other pages' mutations invalidate to live-update the bell.
    await waitFor(() => expect(api.notifications.list).toHaveBeenCalledTimes(2));
  });
});
