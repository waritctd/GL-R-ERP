import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
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
});
