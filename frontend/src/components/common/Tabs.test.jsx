import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Tabs } from './Tabs.jsx';

globalThis.React = React;

const originalScrollIntoView = Element.prototype.scrollIntoView;

const items = [
  { id: 'overview', label: 'ภาพรวม', helper: 'ข้อมูลดีล' },
  { id: 'pricing', label: 'ราคา', helper: 'คำขอราคา' },
  { id: 'documents', label: 'เอกสาร', helper: 'ไฟล์แนบ' },
  { id: 'activity', label: 'กิจกรรม', helper: 'ประวัติ' },
];

afterEach(() => {
  Element.prototype.scrollIntoView = originalScrollIntoView;
});

describe('Tabs', () => {
  it('uses the shared horizontal-scroll affordance classes without exposing scrollbars', () => {
    render(<Tabs items={items} value="overview" onChange={() => {}} ariaLabel="รายละเอียดดีล" idPrefix="ticket-detail" />);

    const tablist = screen.getByRole('tablist', { name: 'รายละเอียดดีล' });
    expect(tablist.className).toContain('scroll-smooth');
    expect(tablist.className).toContain('scroll-px-1');
    expect(tablist.className).toContain('[scrollbar-width:none]');
    expect(tablist.className).toContain('[&::-webkit-scrollbar]:hidden');
  });

  it('shows an edge affordance when tabs overflow to the right', () => {
    render(<Tabs items={items} value="overview" onChange={() => {}} ariaLabel="รายละเอียดดีล" idPrefix="ticket-detail" />);

    const tablist = screen.getByRole('tablist', { name: 'รายละเอียดดีล' });
    Object.defineProperties(tablist, {
      scrollLeft: { configurable: true, value: 0 },
      clientWidth: { configurable: true, value: 300 },
      scrollWidth: { configurable: true, value: 520 },
    });

    fireEvent.scroll(tablist);

    expect(screen.getByTestId('tabs-scroll-right-affordance')).not.toBeNull();
  });

  it('scrolls the selected tab into view on mount and after roving-keyboard changes', () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const onChange = vi.fn();

    render(<Tabs items={items} value="overview" onChange={onChange} ariaLabel="รายละเอียดดีล" idPrefix="ticket-detail" />);

    expect(scrollIntoView).toHaveBeenCalledWith({ inline: 'nearest', block: 'nearest', behavior: 'instant' });

    fireEvent.keyDown(screen.getByRole('tab', { name: /ภาพรวม/ }), { key: 'End' });

    expect(onChange).toHaveBeenCalledWith('activity');
    expect(scrollIntoView).toHaveBeenCalledWith({ inline: 'nearest', block: 'nearest', behavior: 'instant' });
  });

  // Regression coverage for a real incident: ActivityLogPage.jsx shipped `items: [{ value: 'x',
  // label }]` instead of `{ id: 'x', label }` (this component reads `item.id`, not `value` — see
  // fix/activity-log-page-load-failure). Nothing caught it for ~2 weeks. These assert the dev-only
  // invariant fires loudly and immediately for the mistake it exists to catch, and stays silent for
  // correctly-shaped items so it never becomes console noise on every other page that uses Tabs.
  describe('dev-only missing/duplicate id invariant', () => {
    let consoleError;

    beforeEach(() => {
      consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    });

    afterEach(() => {
      consoleError.mockRestore();
    });

    it('stays silent for correctly-shaped items (id on every item, all unique)', () => {
      render(<Tabs items={items} value="overview" onChange={() => {}} ariaLabel="รายละเอียดดีล" idPrefix="ticket-detail" />);
      expect(consoleError).not.toHaveBeenCalled();
    });

    it('warns loudly when an item is missing id — the exact shape of the real incident', () => {
      const brokenItems = [
        { value: 'summary', label: 'สรุป' },
        { value: 'actions', label: 'การดำเนินการ' },
      ];

      render(<Tabs items={brokenItems} value="summary" onChange={() => {}} ariaLabel="บันทึกการใช้งาน" idPrefix="activity-log" />);

      // Not asserting an exact call count: with every item.id undefined, React ALSO logs its own
      // "two children with the same key" warning for the duplicate `key={item.id}` — that is a
      // welcome side effect of the same underlying bug, not noise to suppress. Find our message
      // among whatever console.error logged.
      const messages = consoleError.mock.calls.map(([message]) => message);
      const ours = messages.find((message) => message.includes('missing an `id`'));
      expect(ours).toBeDefined();
      expect(ours).toContain('2 of 2');
      expect(ours).toContain('activity-log');
    });

    it('warns loudly when items share a duplicate id', () => {
      const duplicateItems = [
        { id: 'overview', label: 'ภาพรวม' },
        { id: 'overview', label: 'ภาพรวม (ซ้ำ)' },
      ];

      render(<Tabs items={duplicateItems} value="overview" onChange={() => {}} ariaLabel="รายละเอียดดีล" idPrefix="ticket-detail" />);

      // Same reasoning as the missing-id case above: React's own duplicate-`key` warning also
      // fires here, so find our message rather than assume an exact call count.
      const messages = consoleError.mock.calls.map(([message]) => message);
      const ours = messages.find((message) => message.includes('duplicate id(s)'));
      expect(ours).toBeDefined();
      expect(ours).toContain('overview');
    });

    it('does not re-warn on a re-render with the same items reference', () => {
      const { rerender } = render(
        <Tabs items={items} value="overview" onChange={() => {}} ariaLabel="รายละเอียดดีล" idPrefix="ticket-detail" />,
      );
      rerender(<Tabs items={items} value="pricing" onChange={() => {}} ariaLabel="รายละเอียดดีล" idPrefix="ticket-detail" />);

      expect(consoleError).not.toHaveBeenCalled();
    });
  });
});
