import React from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { CompactStatRow } from './CompactStatRow.jsx';

globalThis.React = React;

const ITEMS = [
  { key: 'base', label: 'ฐานค่าคอมเดือนนี้', value: '฿120,000', helper: 'Commissionable base' },
  { key: 'approved', label: 'อนุมัติแล้ว', value: 12, helper: 'Approved records' },
  { key: 'submitted', label: 'รอผู้จัดการ', value: 3, helper: 'Submitted records' },
];

describe('CompactStatRow', () => {
  it('renders every label, value and helper as plain text (no card chrome)', () => {
    render(<CompactStatRow items={ITEMS} />);

    expect(screen.getByText('฿120,000')).toBeTruthy();
    expect(screen.getByText('12')).toBeTruthy();
    expect(screen.getByText('3')).toBeTruthy();
    expect(screen.getByText('ฐานค่าคอมเดือนนี้')).toBeTruthy();
    expect(screen.getByText('· Commissionable base')).toBeTruthy();
  });

  it('renders as a definition list with one dt/dd pair per item', () => {
    const { container } = render(<CompactStatRow items={ITEMS} />);

    expect(container.querySelector('dl')).toBeTruthy();
    expect(container.querySelectorAll('dl > div > dt').length).toBe(ITEMS.length);
    expect(container.querySelectorAll('dl > div > dd').length).toBe(ITEMS.length);
  });

  it('never renders a button, icon tile, or per-item border/background — passive readouts only', () => {
    const { container } = render(<CompactStatRow items={ITEMS} />);

    expect(container.querySelectorAll('button').length).toBe(0);
    expect(container.querySelector('svg')).toBeNull();
  });

  it('omits an item with no helper without leaving a stray separator', () => {
    render(<CompactStatRow items={[{ label: 'จำนวน Sales', value: 5 }]} />);

    expect(screen.getByText('จำนวน Sales')).toBeTruthy();
    expect(screen.queryByText('·', { exact: false })).toBeNull();
  });

  it('renders loadingCount skeleton placeholders and marks the region aria-busy when loading', () => {
    const { container } = render(<CompactStatRow items={[]} loading loadingCount={4} />);

    expect(container.querySelector('[aria-busy="true"]')).toBeTruthy();
    // Loading state has no dl/dt/dd — it is a shimmer placeholder, not real content.
    expect(container.querySelector('dl')).toBeNull();
    expect(container.querySelectorAll('.skeleton').length).toBeGreaterThan(0);
  });

  it('does not accept or render a tone/color signal on individual items', () => {
    // A caller passing a leftover `tone` (copy-pasted from a StatCard call site)
    // must not leak into any className — this component has no tone prop.
    const { container } = render(<CompactStatRow items={[{ label: 'ทดสอบ', value: 1, tone: 'rose' }]} />);
    expect(container.querySelector('[class*="stat-rose"]')).toBeNull();
    expect(container.querySelector('[class*="status-rose"]')).toBeNull();
  });
});
