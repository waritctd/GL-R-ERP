import React from 'react';
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PhaseTracker } from './DealStageStepper.jsx';

globalThis.React = React;

describe('PhaseTracker', () => {
  it('uses equal-width top-aligned phase columns so the five progress bars stay collinear', () => {
    const { container } = render(<PhaseTracker salesStage="QUOTE_DESIGN_SIDE" />);

    const tracker = container.firstElementChild;
    expect(tracker.className).toContain('items-start');
    expect(tracker.className).not.toContain('items-end');

    for (const phase of Array.from(tracker.children)) {
      expect(phase.className).toContain('flex-1');
      expect(phase.className).toContain('basis-0');
      expect(phase.getAttribute('style') || '').not.toContain('flex');
    }
  });
});
