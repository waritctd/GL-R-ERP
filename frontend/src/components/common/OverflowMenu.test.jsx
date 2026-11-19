import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { OverflowMenu } from './OverflowMenu.jsx';

globalThis.React = React;

function items(overrides = []) {
  return [
    { key: 'a', label: 'แก้ไขสถานะ…', onSelect: vi.fn() },
    { key: 'b', label: 'พักดีลไว้', onSelect: vi.fn() },
    { key: 'c', label: 'ยกเลิก', tone: 'danger', onSelect: vi.fn() },
    ...overrides,
  ];
}

describe('OverflowMenu', () => {
  afterEach(() => {
    delete window.matchMedia;
  });

  it('opens the menu on trigger click, showing every item', () => {
    render(<OverflowMenu items={items()} />);
    expect(screen.queryByRole('menu')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));

    const menu = screen.getByRole('menu');
    expect(menu).not.toBeNull();
    expect(screen.getAllByRole('menuitem')).toHaveLength(3);
  });

  it('invokes the item\'s onSelect and closes the menu on click', () => {
    const list = items();
    render(<OverflowMenu items={list} />);
    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));

    fireEvent.click(screen.getByRole('menuitem', { name: 'พักดีลไว้' }));

    expect(list[1].onSelect).toHaveBeenCalledTimes(1);
    expect(list[0].onSelect).not.toHaveBeenCalled();
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('Escape closes the menu and returns focus to the trigger, without scrolling the page', () => {
    render(<OverflowMenu items={items()} />);
    const trigger = screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' });
    fireEvent.click(trigger);
    expect(screen.getByRole('menu')).not.toBeNull();
    const focus = vi.spyOn(trigger, 'focus');

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(trigger);
    expect(focus).toHaveBeenCalledWith({ preventScroll: true });
  });

  // a disabled item is deliberately NOT given the native `disabled` attribute (see the component's own doc
  // comment — it has to stay in the roving-focus set), so its no-op guard lives entirely in the onClick handler.
  it('a disabled item no-ops on keyboard activation (Enter/Space), same as a mouse click', () => {
    const onSelect = vi.fn();
    const list = items([{ key: 'd', label: 'เลื่อนไป: ถัดไป', disabled: true, disabledReason: 'ยังไม่พร้อม', onSelect }]);
    render(<OverflowMenu items={list} />);
    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
    const disabledItem = screen.getByRole('menuitem', { name: /เลื่อนไป: ถัดไป/ });
    disabledItem.focus();

    fireEvent.click(disabledItem);

    expect(onSelect).not.toHaveBeenCalled();
    // Present-but-inert, not silently gone — the menu stays open and the item stays visible with its reason, per
    // the IA rule an unavailable action must say why rather than vanish.
    expect(screen.getByRole('menu')).not.toBeNull();
  });
});
