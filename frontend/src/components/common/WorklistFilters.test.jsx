import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { WorklistFilters } from './WorklistFilters.jsx';

globalThis.React = React;

const items = [
  { key: '', label: 'ทั้งหมด', count: 11 },
  { key: 'a', label: 'สถานะ A', count: 3, dotClassName: 'bg-phase-1' },
  { key: 'b', label: 'สถานะ B', count: 4 },
];

describe('WorklistFilters', () => {
  it('renders each chip as a real button with its label and inline count', () => {
    render(<WorklistFilters items={items} activeKey="" onSelect={() => {}} ariaLabel="ตัวกรอง" />);

    const group = screen.getByRole('group', { name: 'ตัวกรอง' });
    const buttons = within(group).getAllByRole('button');
    expect(buttons).toHaveLength(3);
    expect(buttons[0].textContent).toContain('ทั้งหมด');
    expect(buttons[0].textContent).toContain('11');
    expect(buttons[1].textContent).toContain('สถานะ A');
    expect(buttons[1].textContent).toContain('3');
  });

  it('reflects the active chip via aria-pressed', () => {
    render(<WorklistFilters items={items} activeKey="a" onSelect={() => {}} ariaLabel="ตัวกรอง" />);

    expect(screen.getByRole('button', { name: /ทั้งหมด/ }).getAttribute('aria-pressed')).toBe('false');
    expect(screen.getByRole('button', { name: /สถานะ A/ }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: /สถานะ B/ }).getAttribute('aria-pressed')).toBe('false');
  });

  it('calls onSelect with the clicked chip key', () => {
    const onSelect = vi.fn();
    render(<WorklistFilters items={items} activeKey="" onSelect={onSelect} ariaLabel="ตัวกรอง" />);

    fireEvent.click(screen.getByRole('button', { name: /สถานะ B/ }));
    expect(onSelect).toHaveBeenCalledWith('b');
  });

  it('omits the count entirely for a chip whose item has no count', () => {
    const itemsWithoutCount = [
      { key: '', label: 'ทั้งหมด' },
      ...items.slice(1),
    ];
    render(<WorklistFilters items={itemsWithoutCount} activeKey="" onSelect={() => {}} ariaLabel="ตัวกรอง" />);

    const allChip = screen.getByRole('button', { name: 'ทั้งหมด' });
    expect(allChip.textContent.trim()).toBe('ทั้งหมด');
  });

  it('meets the 44px mobile touch floor via the shared Button.jsx convention', () => {
    render(<WorklistFilters items={items} activeKey="" onSelect={() => {}} ariaLabel="ตัวกรอง" />);

    for (const button of screen.getAllByRole('button')) {
      expect(button.className).toContain('max-[720px]:min-h-[44px]');
    }
  });

  // FIX F2 (review-remediation): `overflow-x-auto` forces `overflow-y` to
  // compute as `auto` too, so the scroller clips at its own padding box.
  // `scroll-px-1`/`px-1`/`py-1` give a chip's focus ring room on every side
  // (including the first/last chip against the scroller's own edge) instead
  // of being clipped there.
  it('gives the scroller room via scroll-px-1/px-1/py-1 so a chip focus ring is not clipped at the scroll edge (FIX F2)', () => {
    render(<WorklistFilters items={items} activeKey="" onSelect={() => {}} ariaLabel="ตัวกรอง" />);

    const scroller = screen.getByRole('group', { name: 'ตัวกรอง' });
    expect(scroller.className).toContain('scroll-px-1');
    expect(scroller.className).toContain('px-1');
    expect(scroller.className).toContain('py-1');
  });

  it('shows a scroll affordance only on the edge(s) that actually have more content', () => {
    const { container, rerender } = render(
      <WorklistFilters items={items} activeKey="" onSelect={() => {}} ariaLabel="ตัวกรอง" />,
    );
    const scroller = container.querySelector('[role="group"]');

    // jsdom never lays out real box metrics, so the affordance is exercised
    // by driving the same scroll geometry the component reads (scrollLeft /
    // clientWidth / scrollWidth) and firing the scroll listener it attaches.
    Object.defineProperty(scroller, 'clientWidth', { configurable: true, value: 100 });
    Object.defineProperty(scroller, 'scrollWidth', { configurable: true, value: 400 });
    Object.defineProperty(scroller, 'scrollLeft', { configurable: true, value: 0, writable: true });
    fireEvent.scroll(scroller);
    expect(container.querySelector('[aria-hidden="true"].bg-gradient-to-l')).toBeTruthy();
    expect(container.querySelector('[aria-hidden="true"].bg-gradient-to-r')).toBeNull();

    scroller.scrollLeft = 150;
    fireEvent.scroll(scroller);
    expect(container.querySelector('[aria-hidden="true"].bg-gradient-to-r')).toBeTruthy();
    expect(container.querySelector('[aria-hidden="true"].bg-gradient-to-l')).toBeTruthy();

    scroller.scrollLeft = 300;
    fireEvent.scroll(scroller);
    expect(container.querySelector('[aria-hidden="true"].bg-gradient-to-r')).toBeTruthy();
    expect(container.querySelector('[aria-hidden="true"].bg-gradient-to-l')).toBeNull();

    rerender(<WorklistFilters items={items.slice(0, 1)} activeKey="" onSelect={() => {}} ariaLabel="ตัวกรอง" />);
  });
});
