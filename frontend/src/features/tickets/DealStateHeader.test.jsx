import React from 'react';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { dealStageLabel, fulfilmentStatusLabel, ticketStatusLabel } from '../../utils/format.js';
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

  // Condensed now renders ONE DOM (so the expand↔condense change can animate) — the stat grid is
  // kept mounted-but-collapsed (aria-hidden) for the transition, so the stage label also exists,
  // hidden, in that grid. Scope the assertion to the VISIBLE bar (deal-state-header-bar) to pin
  // what the condensed user actually sees.
  it('the condensed variant is route-aware too', () => {
    renderHeader({ entryChannel: 'OWNER_DIRECT' }, { condensed: true });
    expect(screen.getByTestId('deal-state-header').dataset.condensed).toBe('true');
    const bar = within(screen.getByTestId('deal-state-header-bar'));
    expect(bar.getByText(OWNER_S3)).toBeTruthy();
    expect(bar.queryByText(DESIGNER_S3)).toBeNull();
  });

  it('the condensed variant keeps ผู้ออกแบบอนุมัติสเปค for designer-led and absent channels', () => {
    const { unmount } = renderHeader({ entryChannel: 'DESIGNER_LED' }, { condensed: true });
    expect(within(screen.getByTestId('deal-state-header-bar')).getByText(DESIGNER_S3)).toBeTruthy();
    unmount();
    renderHeader({ entryChannel: undefined }, { condensed: true });
    expect(within(screen.getByTestId('deal-state-header-bar')).getByText(DESIGNER_S3)).toBeTruthy();
  });
});

describe('DealStateHeader — every non-S3 stage is identical regardless of channel', () => {
  const channels = ['DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT', 'UNSPECIFIED', undefined];
  // The two stages whose WORDING follows the entry channel (format.js STAGE_LABEL_BY_CHANNEL):
// S3, and S1 since the owner ruling of 2026-10-01. Every OTHER stage must stay identical
// across channels — that is what this loop guards.
const CHANNEL_WORDED = ['SPEC_APPROVED', 'LEAD_APPROACH'];
const stages = DEAL_STAGE_CATALOG.stages.map((s) => s.code).filter((c) => !CHANNEL_WORDED.includes(c));

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

// QA-27 / QA-26 (owner ruling 2026-10-29): the legacy sales.ticket.status ("แบบร่าง", "ออกใบเสนอราคาแล้ว"…)
// stopped meaning anything once the deal stage + payment/fulfilment chips took over — it only misled
// (a DELIVERED deal read "ออกใบเสนอราคาแล้ว"). The header shows no badge for it at all.
describe('DealStateHeader — no legacy ticket.status badge', () => {
  it.each(['draft', 'quotation_issued', 'submitted', 'price_proposed', 'closed'])(
    'does not render the label for legacy status %s',
    (status) => {
      renderHeader({ status, salesStage: 'DELIVERED' });
      expect(screen.queryByText(ticketStatusLabel(status).label)).toBeNull();
    },
  );

  it('still renders the rest of the header (stage chip, updated-at line) — the check is not vacuous', () => {
    renderHeader({ status: 'quotation_issued', salesStage: 'DELIVERED' });
    expect(stageChipText()).toBe(dealStageLabel('DELIVERED').label);
    expect(screen.getByText(/อัปเดตล่าสุด/)).toBeTruthy();
  });
});

// QA-22 (owner ruling 2026-10-29): summary.fulfillmentStatus stays IR_ISSUED until EVERY factory is
// RECEIVED, so the การนำเข้า chip follows the per-factory IR steps instead: the SLOWEST live tracked
// factory's step, plus " · ถึงโกดัง X/Y" when more than one factory is tracked.
describe('DealStateHeader — การนำเข้า chip follows the per-factory IR steps', () => {
  const ir = (importStep, extra = {}) => ({ id: Math.floor(Math.random() * 1e6), status: 'ISSUED', importStep, ...extra });
  function importChipText() {
    const dt = screen.getByText('การนำเข้า');
    return within(dt.parentElement).getByText(/./, { selector: '.status-badge' }).textContent;
  }
  const withIrs = (importRequests, summaryOverrides = { fulfillmentStatus: 'IR_ISSUED' }) =>
    renderHeader(summaryOverrides, { importRequests });

  it('two factories, one IN_TRANSIT and one RECEIVED -> the slower step with the arrival count', () => {
    withIrs([ir('IN_TRANSIT'), ir('RECEIVED')]);
    expect(importChipText()).toBe('กำลังเดินทาง · ถึงโกดัง 1/2');
  });

  it('one factory -> the step label alone, no counter', () => {
    withIrs([ir('ORDERED')]);
    expect(importChipText()).toBe('สั่งซื้อผู้ผลิต');
  });

  it('the slowest wins whatever the row order', () => {
    withIrs([ir('RECEIVED'), ir('CONTACTED'), ir('IN_TRANSIT')]);
    expect(importChipText()).toBe('ส่ง IR ให้จัดซื้อ · ถึงโกดัง 1/3');
  });

  it('every tracked factory RECEIVED while the deal rollup is still IR_ISSUED -> ถึงโกดัง alone', () => {
    withIrs([ir('RECEIVED'), ir('RECEIVED')]);
    expect(importChipText()).toBe('ถึงโกดัง');
    // Arrived reads as done (success), like the GOODS_RECEIVED rollup it is about to become — not in-progress.
    expect(screen.getByText('ถึงโกดัง', { selector: '.status-badge' }).className).toContain('status-success');
  });

  it('a factory still on the way keeps the in-progress (info) tone', () => {
    withIrs([ir('IN_TRANSIT'), ir('RECEIVED')]);
    expect(screen.getByText('กำลังเดินทาง · ถึงโกดัง 1/2', { selector: '.status-badge' }).className).toContain('status-info');
  });

  it('a revised factory counts once: v1 SUPERSEDED at ORDERED + v2 ISSUED at RECEIVED -> ถึงโกดัง', () => {
    withIrs([ir('ORDERED', { status: 'SUPERSEDED' }), ir('RECEIVED')]);
    expect(importChipText()).toBe('ถึงโกดัง');
  });

  it('ignores superseded and not-yet-tracked (no importStep) rows when picking the step and counting', () => {
    withIrs([ir('IN_TRANSIT'), ir('ORDERED', { status: 'SUPERSEDED' }), ir(null, { status: 'DRAFT' })]);
    expect(importChipText()).toBe('กำลังเดินทาง');
  });

  it.each([
    ['no importRequests prop and no fulfilment status', undefined, null, 'ยังไม่ออกใบขอซื้อ'],
    ['an empty list and no fulfilment status', [], null, 'ยังไม่ออกใบขอซื้อ'],
    ['an empty list and IR_ISSUED', [], 'IR_ISSUED', fulfilmentStatusLabel('IR_ISSUED').label],
    ['only superseded / untracked rows and IR_ISSUED', [ir('ORDERED', { status: 'SUPERSEDED' }), ir(null, { status: 'DRAFT' })],
      'IR_ISSUED', fulfilmentStatusLabel('IR_ISSUED').label],
  ])('no live tracked IR (%s) -> today\'s behaviour', (_name, importRequests, fulfillmentStatus, expected) => {
    withIrs(importRequests, { fulfillmentStatus });
    expect(importChipText()).toBe(expected);
  });

  it.each(['GOODS_RECEIVED', 'PARTIALLY_DELIVERED', 'FULLY_DELIVERED', 'FROM_STOCK'])(
    'past import (%s) keeps the deal-level fulfilment label even with IR rows on file',
    (fulfillmentStatus) => {
      withIrs([ir('RECEIVED'), ir('IN_TRANSIT')], { fulfillmentStatus });
      expect(importChipText()).toBe(fulfilmentStatusLabel(fulfillmentStatus).label);
    },
  );
});

describe('DealStateHeader — การนำเข้า chip wording (owner ruling 2026-10-29)', () => {
  const chipValue = () => screen.getByText('การนำเข้า').nextElementSibling.textContent;

  it('no fulfilment yet reads ยังไม่ออกใบขอซื้อ, not ยังไม่เริ่ม', () => {
    renderHeader({ fulfillmentStatus: null });
    expect(chipValue()).toBe('ยังไม่ออกใบขอซื้อ');
  });
});
