import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

  it('opens a popover listing only the viewer’s changes, and the button goes to the release page', async () => {
    const { onNavigate } = renderBubble();

    fireEvent.click(await screen.findByRole('button', { name: /มีอะไรใหม่/ }));
    const popover = screen.getByRole('dialog', { name: 'มีอะไรใหม่' });
    expect(popover.textContent).toContain('17 พ.ย. 2569');
    const items = within(popover).getAllByRole('listitem');
    expect(items).toHaveLength(7);
    // Wrong-way-round: a rep is not shown the finance worklist or the team versions.
    expect(popover.textContent).not.toContain('งานการเงิน');
    expect(popover.textContent).not.toContain('ทุกคนในทีม');

    fireEvent.click(within(popover).getByRole('button', { name: 'ดูรายละเอียดและวิธีใช้' }));
    expect(onNavigate).toHaveBeenCalledWith('/whats-new/2026-11-17');
  });

  it('an unread notice for the older release still opens that release', async () => {
    api.notifications.list.mockResolvedValue({ notifications: [notice()] });
    const { onNavigate } = renderBubble();

    await screen.findByTestId('whats-new-unread-dot');
    fireEvent.click(trigger());
    const popover = screen.getByRole('dialog', { name: 'มีอะไรใหม่' });
    expect(popover.textContent).toContain('16 พ.ย. 2569');
    expect(within(popover).getAllByRole('listitem')).toHaveLength(4);
    expect(popover.textContent).not.toContain('บันทึกรับชำระ');

    fireEvent.click(within(popover).getByRole('button', { name: 'ดูรายละเอียดและวิธีใช้' }));
    expect(onNavigate).toHaveBeenCalledWith('/whats-new/2026-11-16');
  });

  it('a role the new release has nothing for, holding an unread 16 Nov notice, still lands on 16 Nov', async () => {
    api.notifications.list.mockResolvedValue({ notifications: [notice()] });
    const { onNavigate } = renderBubble({ role: 'hr', employeeId: 47 });

    await screen.findByTestId('whats-new-unread-dot');
    fireEvent.click(trigger());
    const popover = screen.getByRole('dialog', { name: 'มีอะไรใหม่' });
    expect(popover.textContent).toContain('16 พ.ย. 2569');
    const items = within(popover).getAllByRole('listitem');
    expect(items).toHaveLength(1);
    expect(items[0].textContent).toContain('glr-hub.com');
    expect(within(popover).queryByText('ฉบับนี้ไม่มีเรื่องเฉพาะแผนกของคุณ')).toBeNull();

    fireEvent.click(within(popover).getByRole('button', { name: 'ดูรายละเอียดและวิธีใช้' }));
    expect(onNavigate).toHaveBeenCalledWith('/whats-new/2026-11-16');
  });

  it('a role with no sales pages is shown the 17 Nov launcher change alone', async () => {
    renderBubble({ role: 'hr', employeeId: 47 });

    fireEvent.click(await screen.findByRole('button', { name: /มีอะไรใหม่/ }));
    const popover = screen.getByRole('dialog', { name: 'มีอะไรใหม่' });
    expect(popover.textContent).toContain('17 พ.ย. 2569');
    const items = within(popover).getAllByRole('listitem');
    expect(items).toHaveLength(1);
    expect(items[0].textContent).toContain('เมนู');
  });

  it('"ไว้ทีหลัง" and Escape close the popover without navigating', async () => {
    const { onNavigate } = renderBubble();

    fireEvent.click(await screen.findByRole('button', { name: /มีอะไรใหม่/ }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'ไว้ทีหลัง' }));
    expect(screen.queryByRole('dialog')).toBeNull();

    fireEvent.click(trigger());
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(onNavigate).not.toHaveBeenCalled();
  });

  it('collapses the text label to the icon on mobile (the accessible name stays)', async () => {
    renderBubble();

    const label = within(await screen.findByRole('button', { name: /มีอะไรใหม่/ })).getByText('มีอะไรใหม่');
    expect(label.className).toContain('mobile:hidden');
  });

  // Motion (owner request): one gentle pulse on first render — never an endless one — and none at all under
  // prefers-reduced-motion. jsdom cannot run the animation; this pins the classes that decide both.
  it('the dot pulses once, and not under reduced motion', async () => {
    api.notifications.list.mockResolvedValue({ notifications: [notice()] });
    renderBubble();

    const dot = await screen.findByTestId('whats-new-unread-dot');
    expect(dot.className).toContain('motion-safe:animate-wn-pulse-once');
    expect(dot.className).not.toMatch(/animate-(ping|pulse)(\s|$)/);
  });

  it('the popover enters with a short motion-safe animation', async () => {
    renderBubble();

    fireEvent.click(await screen.findByRole('button', { name: /มีอะไรใหม่/ }));
    expect(screen.getByRole('dialog').className).toContain('motion-safe:animate-wn-pop');
  });
});

