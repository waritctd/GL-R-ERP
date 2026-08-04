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

// Matches useIsMobile.test.js's own mock shape — OverflowMenu's mobile flip
// is backed by that same shared `useIsMobile` hook.
function mockMobileMatchMedia() {
  window.matchMedia = vi.fn().mockReturnValue({
    matches: true,
    media: '(max-width: 720px)',
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
}

describe('OverflowMenu', () => {
  afterEach(() => {
    delete window.matchMedia;
  });


  it('renders no trigger at all when there are no items', () => {
    const { container } = render(<OverflowMenu items={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders no trigger when items is undefined', () => {
    const { container } = render(<OverflowMenu items={undefined} />);
    expect(container.firstChild).toBeNull();
  });

  it('opens the menu on trigger click, showing every item', () => {
    render(<OverflowMenu items={items()} />);
    expect(screen.queryByRole('menu')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));

    const menu = screen.getByRole('menu');
    expect(menu).not.toBeNull();
    expect(screen.getAllByRole('menuitem')).toHaveLength(3);
  });

  it('can render a visible trigger label while keeping the menu label as the accessible name', () => {
    render(<OverflowMenu items={items()} label="เอกสาร" triggerLabel="เอกสาร" triggerIcon="fileText" />);

    const trigger = screen.getByRole('button', { name: 'เอกสาร' });
    expect(trigger.textContent).toContain('เอกสาร');
  });

  it('keeps menu items at the mobile 44px touch target floor without changing desktop density', () => {
    render(<OverflowMenu items={items()} />);
    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));

    for (const item of screen.getAllByRole('menuitem')) {
      expect(item.className).toContain('mobile:min-h-[44px]');
      expect(item.className).toContain('mobile:px-4');
      expect(item.className).toContain('mobile:py-3');
      expect(item.className).toContain('justify-center');
    }
  });

  it('can flip upward on mobile for fixed bottom action bars', () => {
    mockMobileMatchMedia();
    render(<OverflowMenu items={items()} mobilePlacement="up" />);
    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));

    // Position moved from the `mobile:` Tailwind variant to a JS-computed
    // inline style (see OverflowMenu.jsx's doc comment on `createPortal`) —
    // flipping up now means `bottom` is set and `top` isn't, rather than a
    // `mobile:bottom-full` class.
    const menu = screen.getByRole('menu');
    expect(menu.style.bottom).not.toBe('');
    expect(menu.style.top).toBe('');
    expect(menu.className).toContain('mobile:max-h-[calc(100dvh-7rem)]');
  });

  it('anchors downward from the trigger when not on mobile', () => {
    render(<OverflowMenu items={items()} mobilePlacement="up" />);
    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));

    const menu = screen.getByRole('menu');
    expect(menu.style.top).not.toBe('');
    expect(menu.style.bottom).toBe('');
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

  it('Escape closes the menu and returns focus to the trigger', () => {
    render(<OverflowMenu items={items()} />);
    const trigger = screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' });
    fireEvent.click(trigger);
    expect(screen.getByRole('menu')).not.toBeNull();

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('a click outside the menu closes it', () => {
    render(
      <div>
        <OverflowMenu items={items()} />
        <button type="button">elsewhere</button>
      </div>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
    expect(screen.getByRole('menu')).not.toBeNull();

    fireEvent.mouseDown(screen.getByRole('button', { name: 'elsewhere' }));

    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('ArrowDown/ArrowUp cycle focus between menu items, wrapping at both ends', () => {
    render(<OverflowMenu items={items()} />);
    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
    const menuItems = screen.getAllByRole('menuitem');
    expect(document.activeElement).toBe(menuItems[0]);

    fireEvent.keyDown(document, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(menuItems[1]);

    fireEvent.keyDown(document, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(menuItems[0]);

    // Wraps backward from the first item to the last.
    fireEvent.keyDown(document, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(menuItems[2]);

    // Wraps forward from the last item back to the first.
    fireEvent.keyDown(document, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(menuItems[0]);
  });

  it('Home/End jump focus to the first/last menu item', () => {
    render(<OverflowMenu items={items()} />);
    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
    const menuItems = screen.getAllByRole('menuitem');

    fireEvent.keyDown(document, { key: 'End' });
    expect(document.activeElement).toBe(menuItems[2]);

    fireEvent.keyDown(document, { key: 'Home' });
    expect(document.activeElement).toBe(menuItems[0]);
  });

  it('focuses the first item automatically on open', () => {
    render(<OverflowMenu items={items()} />);
    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
    expect(document.activeElement).toBe(screen.getAllByRole('menuitem')[0]);
  });

  // P3 (review round 2): every item is tabIndex={-1} (reachable only via
  // Arrow/Home/End, see the component's own doc comment on the button
  // element) so a plain Tab from within the menu skips every OTHER item and
  // lands on whatever follows this component in the DOM. Before this fix
  // there was no roving tabindex at all AND nothing closed the menu on
  // Tab-out, so an open menu stayed open, still interactive, while focus
  // moved into the page behind it — an orphaned popover.
  it('items are out of the normal Tab order (tabIndex -1), and Tab closes the menu', () => {
    render(<OverflowMenu items={items()} />);
    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
    const menuItems = screen.getAllByRole('menuitem');
    menuItems.forEach((item) => expect(item.tabIndex).toBe(-1));

    fireEvent.keyDown(document, { key: 'Tab' });

    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('Shift+Tab also closes the menu', () => {
    render(<OverflowMenu items={items()} />);
    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
    expect(screen.getByRole('menu')).not.toBeNull();

    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });

    expect(screen.queryByRole('menu')).toBeNull();
  });

  // P3 (review round 2): a disabled item is deliberately NOT given the native
  // `disabled` attribute (see the component's own doc comment — it has to
  // stay in the roving-focus set), so its no-op guard lives entirely in the
  // onClick handler. Per the HTML button-activation spec, Enter/Space on a
  // focused native <button> dispatch a 'click' event — the same event this
  // handler already gates on `item.disabled` — so a click event is the
  // faithful jsdom stand-in for "activated via keyboard" here (jsdom does
  // not itself synthesize that native keyboard-to-click translation the way
  // a real browser does, so firing 'click' directly is the correct way to
  // exercise it under fireEvent, same convention already used elsewhere in
  // this codebase — see TicketDetailPage.test.jsx's own "disabled item" note).
  it('a disabled item no-ops on keyboard activation (Enter/Space), same as a mouse click', () => {
    const onSelect = vi.fn();
    const list = items([{ key: 'd', label: 'เลื่อนไป: ถัดไป', disabled: true, disabledReason: 'ยังไม่พร้อม', onSelect }]);
    render(<OverflowMenu items={list} />);
    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
    const disabledItem = screen.getByRole('menuitem', { name: /เลื่อนไป: ถัดไป/ });
    disabledItem.focus();

    fireEvent.click(disabledItem);

    expect(onSelect).not.toHaveBeenCalled();
    // Present-but-inert, not silently gone — the menu stays open and the
    // item stays visible with its reason, per the IA rule an unavailable
    // action must say why rather than vanish.
    expect(screen.getByRole('menu')).not.toBeNull();
  });
});
