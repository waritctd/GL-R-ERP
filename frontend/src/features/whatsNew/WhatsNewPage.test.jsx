import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../api/index.js';
import { WhatsNewPage } from './WhatsNewPage.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    notifications: { list: vi.fn(), markRead: vi.fn() },
    releaseNotes: { audience: vi.fn(), publish: vi.fn(), email: vi.fn() },
  },
}));

const sales = { id: 6, role: 'sales', employeeId: 41, name: 'ยา' };
const hr = { id: 2, role: 'hr', employeeId: 47, name: 'บุคคล' };
const ceo = { id: 8, role: 'ceo', employeeId: 48, name: 'ซีอีโอ' };

function renderPage(user, path = '/whats-new/2026-11-16', showToast = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/whats-new" element={<WhatsNewPage user={user} showToast={showToast} />} />
          <Route path="/whats-new/:releaseId" element={<WhatsNewPage user={user} showToast={showToast} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { showToast };
}

const changeHeadings = () => screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);

describe('WhatsNewPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.notifications.list.mockResolvedValue({ notifications: [] });
    api.notifications.markRead.mockResolvedValue({});
  });

  it('opens on "สำหรับแผนกของคุณ" showing only the viewer’s changes', async () => {
    renderPage(sales);

    const mine = await screen.findByRole('tab', { name: /สำหรับแผนกของคุณ/ });
    expect(mine.getAttribute('aria-selected')).toBe('true');
    expect(mine.textContent).toContain('4');
    const headings = changeHeadings();
    expect(headings).toHaveLength(4);
    // Wrong-way-round: what a rep must NOT be shown on their own tab.
    expect(headings.join('|')).not.toContain('บันทึกรับชำระ');
    expect(headings.join('|')).not.toContain('ภาพรวมทีมขาย');
    expect(headings.join('|')).not.toContain('การขนส่ง');
  });

  it('"ทั้งหมด" shows a section per role, page by page, and names the address-only roles', async () => {
    renderPage(sales);

    fireEvent.click(await screen.findByRole('tab', { name: /ทั้งหมด/ }));

    const sections = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(sections).toEqual(['ทุกแผนก', 'ฝ่ายขาย', 'ผู้จัดการฝ่ายขาย', 'CEO', 'ฝ่ายบัญชี', 'ฝ่ายนำเข้า']);
    expect(changeHeadings().join('|')).toContain('บันทึกรับชำระ');
    // The published 16 Nov page, word for word: a second release must not change what this one says.
    expect(screen.getByText(/ฝ่ายบุคคล · คลังสินค้า · QC · พนักงานทั่วไป/).textContent)
      .toBe('ฝ่ายบุคคล · คลังสินค้า · QC · พนักงานทั่วไป: ฉบับนี้มีเฉพาะการย้ายที่อยู่เว็บ (ด้านบน)');
  });

  it('the 16 Nov page is unchanged for a role a later release has nothing for', async () => {
    renderPage(hr, '/whats-new/2026-11-16');

    const mine = await screen.findByRole('tab', { name: /สำหรับแผนกของคุณ/ });
    expect(mine.textContent).toContain('1');
    expect(changeHeadings()).toEqual(['พอร์ทัลย้ายมาที่ glr-hub.com']);
    expect(screen.queryByText('ฉบับนี้ไม่มีเรื่องเฉพาะแผนกของคุณ')).toBeNull();
  });

  it('each entry names its page and menu path', async () => {
    renderPage(sales);

    const article = (await screen.findByRole('heading', { level: 3, name: /งานของฉัน/ })).closest('article');
    expect(within(article).getByText(/เมนู: แดชบอร์ด/)).toBeTruthy();
  });

  it('renders each change with tags, why, numbered steps with a screenshot each, and "ถ้าเจอปัญหา"', async () => {
    renderPage(sales);

    const article = (await screen.findByRole('heading', { level: 3, name: /glr-hub\.com/ })).closest('article');
    expect(within(article).getByText('ทุกคน')).toBeTruthy();
    expect(within(article).getByText('เปลี่ยนแปลง')).toBeTruthy();
    const steps = within(article).getAllByRole('listitem');
    expect(steps).toHaveLength(2);
    expect(within(steps[0]).getByText('1')).toBeTruthy();
    expect(within(steps[1]).getByText('2')).toBeTruthy();
    const shot = within(steps[0]).getByRole('img');
    expect(shot.getAttribute('src')).toBe('/whats-new/2026-11-16/address-1.png');
    expect(within(article).getByText(/ถ้าเจอปัญหา/)).toBeTruthy();
  });

  it('shows a placeholder, not a broken image, when a screenshot file is missing', async () => {
    renderPage(sales);

    const article = (await screen.findByRole('heading', { level: 3, name: /glr-hub\.com/ })).closest('article');
    const [firstStep] = within(article).getAllByRole('listitem');
    fireEvent.error(within(firstStep).getByRole('img'));

    expect(within(firstStep).queryByRole('img', { name: /เปิด glr-hub\.com/ })).toBeNull();
    expect(within(firstStep).getByText('ภาพหน้าจอจะเพิ่มเร็ว ๆ นี้')).toBeTruthy();
  });

  it('/whats-new with no id shows the latest release', async () => {
    renderPage(sales, '/whats-new');

    expect(await screen.findByRole('heading', { level: 1, name: /17 พ\.ย\. 2569/ })).toBeTruthy();
  });

  it('the 16 Nov release stays reachable by its own link after a newer one ships', async () => {
    renderPage(sales, '/whats-new/2026-11-16');

    expect(await screen.findByRole('heading', { level: 1, name: /16 พ\.ย\. 2569/ })).toBeTruthy();
  });

  it('a role this release has nothing for gets the empty state, not an empty list', async () => {
    renderPage(hr, '/whats-new/2026-11-17');

    const mine = await screen.findByRole('tab', { name: /สำหรับแผนกของคุณ/ });
    expect(mine.textContent).toContain('0');
    expect(screen.getByText('ฉบับนี้ไม่มีเรื่องเฉพาะแผนกของคุณ')).toBeTruthy();
    expect(screen.queryAllByRole('heading', { level: 3 })).toHaveLength(0);
  });

  it('"ทั้งหมด" of a release with no everyone entry shows no empty ทุกแผนก section and no address-move note', async () => {
    renderPage(hr, '/whats-new/2026-11-17');

    fireEvent.click(await screen.findByRole('tab', { name: /ทั้งหมด/ }));

    const sections = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(sections).toEqual(['ฝ่ายขาย', 'ผู้จัดการฝ่ายขาย', 'CEO', 'ฝ่ายบัญชี', 'ฝ่ายนำเข้า']);
    expect(screen.getByText(/ฝ่ายบุคคล · คลังสินค้า · QC · พนักงานทั่วไป/).textContent)
      .toBe('ฝ่ายบุคคล · คลังสินค้า · QC · พนักงานทั่วไป: ฉบับนี้ไม่มีเรื่องเฉพาะแผนกเหล่านี้');
  });

  it('an unknown release id says so instead of rendering an empty page', async () => {
    renderPage(sales, '/whats-new/2099-01-01');

    expect(await screen.findByText('ไม่พบประกาศนี้')).toBeTruthy();
    expect(screen.queryByRole('tab')).toBeNull();
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
    const audience = { targeted: 9, alreadyNotified: 3, pendingNotification: 6, alreadyEmailed: 0, pendingEmail: 8, withoutEmail: 1 };

    beforeEach(() => {
      api.releaseNotes.audience.mockResolvedValue(audience);
      api.releaseNotes.publish.mockResolvedValue({ targeted: 9, inserted: 6, alreadyNotified: 3 });
      api.releaseNotes.email.mockResolvedValue({ targeted: 9, sent: 8, alreadyEmailed: 0, withoutEmail: 1 });
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
        expect(screen.queryByRole('button', { name: 'ส่งอีเมล' }), role).toBeNull();
        unmount();
      }
      expect(api.releaseNotes.audience).not.toHaveBeenCalled();
    });

    it('publish confirms with the number of people, then publishes, and never emails', async () => {
      const { showToast } = renderPage(hr);

      fireEvent.click(await screen.findByRole('button', { name: 'เผยแพร่ประกาศ' }));
      const dialog = await screen.findByRole('dialog');
      await within(dialog).findByText(/6 คน/);
      expect(api.releaseNotes.audience).toHaveBeenCalledWith(expect.objectContaining({ releaseId: '2026-11-16' }));
      fireEvent.click(within(dialog).getByRole('button', { name: 'เผยแพร่' }));

      await waitFor(() => expect(api.releaseNotes.publish).toHaveBeenCalledTimes(1));
      const payload = api.releaseNotes.publish.mock.calls[0][0];
      expect(payload.releaseId).toBe('2026-11-16');
      expect(payload.changes).toHaveLength(13);
      expect(api.releaseNotes.email).not.toHaveBeenCalled();
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', expect.stringContaining('6')));
    });

    it('email is a separate button with its own confirm showing who will receive it', async () => {
      renderPage(ceo);

      fireEvent.click(await screen.findByRole('button', { name: 'ส่งอีเมล' }));
      const dialog = await screen.findByRole('dialog');
      await within(dialog).findByText(/8 คน/);
      expect(within(dialog).getByText(/ไม่มีอีเมล 1 คน/)).toBeTruthy();
      expect(api.releaseNotes.email).not.toHaveBeenCalled();
      fireEvent.click(within(dialog).getByRole('button', { name: 'ส่งอีเมล' }));

      await waitFor(() => expect(api.releaseNotes.email).toHaveBeenCalledTimes(1));
      expect(api.releaseNotes.publish).not.toHaveBeenCalled();
    });

    it('cancelling the confirm sends nothing', async () => {
      renderPage(hr);

      fireEvent.click(await screen.findByRole('button', { name: 'ส่งอีเมล' }));
      const dialog = await screen.findByRole('dialog');
      await within(dialog).findByText(/8 คน/);
      fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิก' }));

      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      expect(api.releaseNotes.email).not.toHaveBeenCalled();
      expect(api.releaseNotes.publish).not.toHaveBeenCalled();
    });
  });
});

// Motion (owner request): cards rise in as they enter the viewport, but content must REST visible when the observer
// never fires (no IntersectionObserver in jsdom, a blocked script, a browser that never reports intersection).
describe('WhatsNewPage motion never hides content', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.notifications.list.mockResolvedValue({ notifications: [] });
  });

  function expectNothingHidden() {
    const articles = screen.getAllByRole('article');
    expect(articles.length).toBeGreaterThan(0);
    for (const node of [...articles, ...screen.getAllByRole('listitem')]) {
      expect(node.className).not.toMatch(/(^|\s)(opacity-0|invisible|hidden)(\s|$)/);
      expect(node.style.opacity).not.toBe('0');
      expect(node.style.visibility).not.toBe('hidden');
    }
  }

  it('without IntersectionObserver at all', async () => {
    const original = globalThis.IntersectionObserver;
    delete globalThis.IntersectionObserver;
    try {
      renderPage(sales);
      await screen.findAllByRole('article');
      expectNothingHidden();
    } finally {
      globalThis.IntersectionObserver = original;
    }
  });

  it('with an observer that never fires', async () => {
    const original = globalThis.IntersectionObserver;
    const observe = vi.fn();
    globalThis.IntersectionObserver = class {
      observe = observe;
      unobserve() {}
      disconnect() {}
    };
    try {
      renderPage(sales);
      await screen.findAllByRole('article');
      expect(observe).toHaveBeenCalled();
      expectNothingHidden();
    } finally {
      globalThis.IntersectionObserver = original;
    }
  });
});

