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

  // FIX 2 (2026-07 review): `dt` used to be `truncate`d, which clips label AND helper
  // onto one nowrap line -- at the ~150px columns this grid hits on a phone, a Thai
  // label alone eats the column and the helper silently ellipses into nothing. `dt`
  // must be free to wrap instead.
  it('never truncates the label/helper line (dt), even when it is long', () => {
    const { container } = render(
      <CompactStatRow
        items={[{ key: 'status', label: 'ฐานค่าคอมเดือนนี้', value: '฿120,000', helper: 'สถานะรอบเดือน: PAYROLL_READY (พร้อมนำเข้า)' }]}
      />,
    );
    const dt = container.querySelector('dl > div > dt');
    expect(dt.className).not.toMatch(/\btruncate\b/);
    // Full label + helper text must be present in the DOM (no ellipsis substitution).
    expect(dt.textContent).toBe('ฐานค่าคอมเดือนนี้· สถานะรอบเดือน: PAYROLL_READY (พร้อมนำเข้า)');
  });

  // FIX 3: DESIGN.md §18 requires truncated content to carry a tooltip. `dd` (the
  // value) is the only part of this component still allowed to truncate, so it must
  // always carry a `title` with the full value.
  it('gives the still-truncating value (dd) a title tooltip with its full text', () => {
    const { container } = render(<CompactStatRow items={[{ key: 'net', label: 'ยอดโอนสุทธิ', value: '฿1,234,567.89' }]} />);
    const dd = container.querySelector('dl > div > dd');
    expect(dd.className).toMatch(/\btruncate\b/);
    expect(dd.getAttribute('title')).toBe('฿1,234,567.89');
  });

  // FIX 4: the loading skeleton must use the same item gap as the loaded row so the
  // strip does not change height (and shift whatever sits below it) the moment data
  // lands. Before the fix, loading used `gap-2` (8px) against the loaded row's
  // `gap-0.5` (2px).
  it('uses the same inter-line gap in the loading skeleton as in the loaded row', () => {
    const loaded = render(<CompactStatRow items={ITEMS} />);
    const loadedGapClass = loaded.container.querySelector('dl > div').className.match(/gap-\S+/)[0];

    const loadingRender = render(<CompactStatRow items={[]} loading loadingCount={2} />);
    const loadingGapClass = loadingRender.container.querySelector('[aria-busy="true"] > div').className.match(/gap-\S+/)[0];

    expect(loadingGapClass).toBe(loadedGapClass);
  });

  // FIX 5: Skeleton.jsx's own contract says the *caller* must label the aria-busy
  // region. A caller that passes only `loading` (no aria-label) must still ship an
  // announced busy region instead of a silent one.
  it('gives the aria-busy loading region a default aria-label when the caller supplies none', () => {
    const { container } = render(<CompactStatRow items={[]} loading />);
    const busyRegion = container.querySelector('[aria-busy="true"]');
    expect(busyRegion.getAttribute('aria-label')).toBeTruthy();
  });

  it('lets a caller override the default loading aria-label', () => {
    const { container } = render(<CompactStatRow items={[]} loading aria-label="กำลังโหลดสรุปค่าคอมมิชชัน" />);
    const busyRegion = container.querySelector('[aria-busy="true"]');
    expect(busyRegion.getAttribute('aria-label')).toBe('กำลังโหลดสรุปค่าคอมมิชชัน');
  });
});
