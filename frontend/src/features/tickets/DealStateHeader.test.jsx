import React from 'react';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { dealStageLabel } from '../../utils/format.js';
import { DealStateHeader } from './DealStateHeader.jsx';

globalThis.React = React;

// The header's stage chip used to read dealStageLabel(code) with ONE argument, so on an
// owner-direct deal at S3 it said "ผู้ออกแบบอนุมัติสเปค" while DealStagePanel, on the same screen,
// said "เจ้าของตกลงตามสเปคแล้ว". These tests pin the chip to the same route-aware wording.
const OWNER_S3 = 'เจ้าของตกลงตามสเปคแล้ว';
const BUYER_S3 = 'ผู้ซื้อ/ผู้รับเหมาตกลงตามสเปคแล้ว';
const DESIGNER_S3 = 'ผู้ออกแบบอนุมัติสเปค';

function summary(overrides = {}) {
  return {
    code: 'TK-0001',
    customerName: 'บริษัท ทดสอบ จำกัด',
    title: 'ทดสอบ',
    lifecycle: 'ACTIVE',
    status: 'draft',
    salesStage: 'SPEC_APPROVED',
    paymentStage: null,
    fulfillmentStatus: null,
    amountPayable: 0,
    updatedAt: '2026-07-01T09:00:00.000Z',
    ...overrides,
  };
}

// The VIEWER's role, not an ARIA role — DealStateHeader takes `role` as a prop and production
// passes it as an expression (TicketDetailPage.jsx:1471). A string literal here trips
// jsx-a11y/aria-role, which reads any literal `role=` as the DOM attribute.
const VIEWER_ROLE = 'sales';

function renderHeader(summaryOverrides, props = {}) {
  return render(<DealStateHeader summary={summary(summaryOverrides)} role={VIEWER_ROLE} {...props} />);
}

// The expanded header renders the stage as the value of the "ขั้นตอนดีล" chip.
function stageChipText() {
  const dt = screen.getByText('ขั้นตอนดีล');
  return within(dt.parentElement).getByText(/./, { selector: '.status-badge' }).textContent;
}

describe('DealStateHeader — stage chip is route-aware', () => {
  it('reads เจ้าของตกลงตามสเปคแล้ว for an owner-direct deal at SPEC_APPROVED', () => {
    renderHeader({ entryChannel: 'OWNER_DIRECT' });
    expect(stageChipText()).toBe(OWNER_S3);
    expect(screen.queryByText(DESIGNER_S3)).toBeNull();
  });

  it('reads ผู้ซื้อ/ผู้รับเหมาตกลงตามสเปคแล้ว for a buyer-direct deal at SPEC_APPROVED', () => {
    renderHeader({ entryChannel: 'BUYER_DIRECT' });
    expect(stageChipText()).toBe(BUYER_S3);
    expect(screen.queryByText(DESIGNER_S3)).toBeNull();
  });

  it.each([
    ['DESIGNER_LED', 'DESIGNER_LED'],
    ['UNSPECIFIED', 'UNSPECIFIED'],
    ['an unknown channel', 'SOMETHING_NEW'],
    ['an absent channel', undefined],
  ])('keeps ผู้ออกแบบอนุมัติสเปค for %s', (_name, channel) => {
    renderHeader({ entryChannel: channel });
    expect(stageChipText()).toBe(DESIGNER_S3);
  });

  it('the condensed variant is route-aware too', () => {
    renderHeader({ entryChannel: 'OWNER_DIRECT' }, { condensed: true });
    expect(screen.getByTestId('deal-state-header').dataset.condensed).toBe('true');
    expect(screen.getByText(OWNER_S3)).toBeTruthy();
    expect(screen.queryByText(DESIGNER_S3)).toBeNull();
  });

  it('the condensed variant keeps ผู้ออกแบบอนุมัติสเปค for designer-led and absent channels', () => {
    const { unmount } = renderHeader({ entryChannel: 'DESIGNER_LED' }, { condensed: true });
    expect(screen.getByText(DESIGNER_S3)).toBeTruthy();
    unmount();
    renderHeader({ entryChannel: undefined }, { condensed: true });
    expect(screen.getByText(DESIGNER_S3)).toBeTruthy();
  });
});

describe('DealStateHeader — every non-S3 stage is identical regardless of channel', () => {
  const channels = ['DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT', 'UNSPECIFIED', undefined];
  const stages = DEAL_STAGE_CATALOG.stages.map((s) => s.code).filter((c) => c !== 'SPEC_APPROVED');

  it('has stages to loop over (guard against a vacuous loop)', () => {
    expect(stages.length).toBeGreaterThan(10);
  });

  it.each(stages)('%s renders the one-argument label for every channel', (code) => {
    const expected = dealStageLabel(code).label;
    for (const entryChannel of channels) {
      const { unmount } = renderHeader({ salesStage: code, entryChannel });
      expect(stageChipText()).toBe(expected);
      unmount();
    }
  });
});
