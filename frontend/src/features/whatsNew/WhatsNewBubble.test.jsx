import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../api/index.js';
import { WhatsNewBubble } from './WhatsNewBubble.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: { notifications: { list: vi.fn(), markRead: vi.fn() } },
}));

const notice = (overrides) => ({
  id: 11, type: 'RELEASE_NOTE', link: '/whats-new/2026-11-16', read: false, message: 'มีอะไรใหม่', createdAt: '2026-11-16T01:00:00Z', ...overrides,
});

function renderBubble(user = { role: 'sales', employeeId: 41 }, onNavigate = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <WhatsNewBubble user={user} onNavigate={onNavigate} />
    </QueryClientProvider>,
  );
  return { onNavigate };
}

const trigger = () => screen.getByRole('button', { name: /มีอะไรใหม่/ });

describe('WhatsNewBubble', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.notifications.list.mockResolvedValue({ notifications: [] });
  });

  it('shows the red dot while the viewer has an unread release notice', async () => {
    api.notifications.list.mockResolvedValue({ notifications: [notice()] });
    renderBubble();

    expect(await screen.findByTestId('whats-new-unread-dot')).toBeTruthy();
    expect(trigger().getAttribute('aria-label')).toContain('ยังไม่ได้อ่าน');
  });

  it('shows no dot once the notice is read, when there is none, or for an unrelated unread notification', async () => {
    api.notifications.list.mockResolvedValue({
      notifications: [notice({ read: true }), notice({ id: 12, type: 'APPROVED', link: '/tickets/1' })],
    });
    renderBubble();

    await waitFor(() => expect(api.notifications.list).toHaveBeenCalled());
    await screen.findByRole('button', { name: /มีอะไรใหม่/ });
    expect(screen.queryByTestId('whats-new-unread-dot')).toBeNull();
    expect(trigger().getAttribute('aria-label')).not.toContain('ยังไม่ได้อ่าน');
  });
});

