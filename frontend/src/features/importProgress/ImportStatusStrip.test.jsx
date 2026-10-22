import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ImportStatusStrip } from './ImportStatusStrip.jsx';

globalThis.React = React;

const FULFILMENT = { label: 'ออกคำขอนำเข้าแล้ว', tone: 'info' };

describe('ImportStatusStrip', () => {
  it('renders status, rollup and delivery chips under the surface-specific testids', () => {
    render(<ImportStatusStrip testIdPrefix="x" fulfilment={FULFILMENT} arrivedCount={1} issuedCount={2} deliveryPct={17} />);
    expect(screen.getByTestId('x-status-strip')).not.toBeNull();
    expect(screen.getByText('ออกคำขอนำเข้าแล้ว')).not.toBeNull();
    expect(screen.getByTestId('x-ir-rollup-chip').textContent).toBe('ถึงไทย 1/2 โรงงาน');
    expect(screen.getByTestId('x-delivery-chip').textContent).toBe('ส่งมอบ 17%');
  });

  it('omits the rollup chip when nothing is issued and the delivery chip when there is no percentage', () => {
    render(<ImportStatusStrip testIdPrefix="x" fulfilment={FULFILMENT} issuedCount={0} deliveryPct={null} />);
    expect(screen.queryByTestId('x-ir-rollup-chip')).toBeNull();
    expect(screen.queryByTestId('x-delivery-chip')).toBeNull();
  });

  it('still renders a 0% delivery chip (0 is a value, not an absence)', () => {
    render(<ImportStatusStrip testIdPrefix="x" fulfilment={null} deliveryPct={0} />);
    expect(screen.getByTestId('x-delivery-chip').textContent).toBe('ส่งมอบ 0%');
  });

  it('carries no money: renders only what it is handed', () => {
    render(<ImportStatusStrip testIdPrefix="x" fulfilment={FULFILMENT} arrivedCount={2} issuedCount={2} deliveryPct={100} />);
    expect(screen.getByTestId('x-status-strip').textContent).not.toMatch(/฿|ราคา|ต้นทุน/);
  });
});
