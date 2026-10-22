import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { FactoryProgressBar } from './FactoryProgressBar.jsx';

globalThis.React = React;

const baseRow = { id: 7, factoryName: 'Cotto Industry', importStep: 'ORDERED', importStepAt: '2569-09-30' };

describe('FactoryProgressBar ETA', () => {
  it('shows the expected-arrival window when the row carries derived arrival dates', () => {
    render(<FactoryProgressBar row={{ ...baseRow, expectedArrivalFrom: '2569-10-03', expectedArrivalTo: '2569-10-07' }} />);
    const eta = screen.getByTestId('factory-eta-7');
    expect(eta.textContent).toMatch(/ETA/);
    // both ends of the window are present (a range, not a single date)
    expect(eta.textContent).toMatch(/–|-/);
  });

  it('collapses to a single date when from === to', () => {
    render(<FactoryProgressBar row={{ ...baseRow, expectedArrivalFrom: '2569-10-03', expectedArrivalTo: '2569-10-03' }} />);
    const eta = screen.getByTestId('factory-eta-7');
    expect(eta.textContent).toMatch(/ETA/);
    expect(eta.textContent).not.toMatch(/–/);
  });

  it('falls back to the raw lead-time span before the row is issued (no derived dates yet)', () => {
    render(<FactoryProgressBar row={{ ...baseRow, importStepAt: null, leadTimeMinDays: 60, leadTimeMaxDays: 75 }} />);
    const eta = screen.getByTestId('factory-eta-7');
    expect(eta.textContent).toMatch(/60/);
    expect(eta.textContent).toMatch(/75/);
    expect(eta.textContent).toMatch(/วัน/);
  });

  it('renders no ETA element when there is neither arrival nor lead time', () => {
    render(<FactoryProgressBar row={baseRow} />);
    expect(screen.queryByTestId('factory-eta-7')).toBeNull();
  });

  it('shows the ETA to a read-only viewer (sales sees it too — no editable prop)', () => {
    render(<FactoryProgressBar row={{ ...baseRow, expectedArrivalFrom: '2569-10-03', expectedArrivalTo: '2569-10-07' }} />);
    // no advance / email controls for a read-only viewer, but the ETA is present
    expect(screen.queryByTestId('advance-7')).toBeNull();
    expect(screen.getByTestId('factory-eta-7').textContent).toMatch(/ETA/);
  });
});
