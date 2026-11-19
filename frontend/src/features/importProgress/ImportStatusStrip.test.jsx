import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ImportStatusStrip } from './ImportStatusStrip.jsx';

globalThis.React = React;

describe('ImportStatusStrip', () => {
  it('still renders a 0% delivery chip (0 is a value, not an absence)', () => {
    render(<ImportStatusStrip testIdPrefix="x" fulfilment={null} deliveryPct={0} />);
    expect(screen.getByTestId('x-delivery-chip').textContent).toBe('ส่งมอบ 0%');
  });
});
