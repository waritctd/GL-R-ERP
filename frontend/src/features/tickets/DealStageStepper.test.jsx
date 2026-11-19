import React from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import {
  DealStageStepper, PhaseSummary, 
} from './DealStageStepper.jsx';

globalThis.React = React;

const catalog = DEAL_STAGE_CATALOG;
const OWNER_OFF = ['QUOTE_DESIGN_SIDE'];
const BUYER_OFF = ['QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'AWAITING_BUYER'];
// The server's own refusal, one distinct sentence per stage so a test can prove it is rendered VERBATIM per
// row (DealRoute.refusalMessage's shape).
const reasonFor = (stage) => `ช่องทางรับงานของงานนี้คือผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง — ขั้นที่ ${stage.no} ไม่อยู่ในเส้นทางนี้ — แก้ช่องทางรับงานก่อน`;

// ⚠️ Built here and passed DIRECTLY as props. mockApi.js does not emit `onRoute`, so driving these through the
// mock would see every stage on-route and pass vacuously.
const decisionsFor = (off) => catalog.stages.map((stage) => ({
  stage: stage.code,
  no: stage.no,
  allowed: !off.includes(stage.code),
  requiresReason: false,
  blockedReason: off.includes(stage.code) ? reasonFor(stage) : null,
  onRoute: !off.includes(stage.code),
}));

const pipCodes = () => screen.getAllByTestId('route-pip').map((pip) => pip.getAttribute('data-stage'));

describe('PhaseSummary — the denominator is THIS deal\'s route, not the catalog', () => {
  const barWidth = (container) => container.querySelector('[style*="width"]').getAttribute('style');

  it('buyer-direct at QUOTE_BUYER (S8) reads ผ่านมาแล้ว 4 จาก 11 ขั้น and fills ~36%, not 8/15 = 53%', () => {
    const { container } = render(
      <PhaseSummary catalog={catalog} salesStage="QUOTE_BUYER" stageDecisions={decisionsFor(BUYER_OFF)} />,
    );
    expect(screen.getByText('ผ่านมาแล้ว 4 จาก 11 ขั้น')).not.toBeNull();
    expect(screen.queryByText('ผ่านมาแล้ว 8 จาก 15 ขั้น')).toBeNull();
    expect(barWidth(container)).toContain('36.36');
  });
});

describe('DealStageStepper route ribbon', () => {
  it('buyer-direct renders one pip per ON-route stage — eleven — and none for the four off-route ones', () => {
    render(
      <DealStageStepper catalog={catalog} salesStage="QUOTE_BUYER" stageDecisions={decisionsFor(BUYER_OFF)} />,
    );
    const codes = pipCodes();
    expect(codes).toHaveLength(11);
    for (const off of BUYER_OFF) expect(codes).not.toContain(off);
    // catalog order preserved
    expect(codes).toEqual(catalog.stages.map((s) => s.code).filter((c) => !BUYER_OFF.includes(c)));
  });

  it('owner-direct renders fourteen pips, absent S4 only', () => {
    render(
      <DealStageStepper catalog={catalog} salesStage="SPEC_APPROVED" stageDecisions={decisionsFor(OWNER_OFF)} />,
    );
    expect(pipCodes()).toHaveLength(14);
    expect(pipCodes()).not.toContain('QUOTE_DESIGN_SIDE');
  });
});

describe('DealStageStepper off-route group', () => {
  it('lists off-route stages under "ไม่อยู่ในเส้นทางนี้ (N)" with the server\'s blockedReason verbatim', () => {
    render(
      <DealStageStepper catalog={catalog} salesStage="QUOTE_BUYER" stageDecisions={decisionsFor(BUYER_OFF)} />,
    );
    const group = screen.getByTestId('off-route-group');
    expect(within(group).getByText('ไม่อยู่ในเส้นทางนี้ (4)')).not.toBeNull();
    for (const code of BUYER_OFF) {
      const stage = catalog.stages.find((s) => s.code === code);
      expect(within(group).getByText(reasonFor(stage))).not.toBeNull();
    }
    expect(within(group).getByText('เสนอราคาผู้ออกแบบ')).not.toBeNull();
    expect(within(group).getByText('เสนอราคาเจ้าของโครงการ')).not.toBeNull();
  });
});

/** The remedy for the gate's own refusal. The server's blockedReason ends "— แก้ช่องทางดีลก่อน", and GLA-156
 * had deleted the only control that could do that. It lives WHERE THE REFUSAL IS READ — on each off-route row
 * — as an inline disclosure, never a permanent row and never a modal. */
describe('DealStageStepper off-route rows — แก้ช่องทางดีล', () => {
  const FIRST = { action: 'SET_ENTRY_CHANNEL', kind: 'policy', requiredFields: ['value'] };
  const renderBuyer = (props = {}) => render(
    <DealStageStepper
      catalog={catalog}
      salesStage="QUOTE_BUYER"
      entryChannel="BUYER_DIRECT"
      stageDecisions={decisionsFor(BUYER_OFF)}
      entryChannelAction={FIRST}
      onSetEntryChannel={vi.fn()}
      {...props}
    />,
  );
  const toggles = () => screen.queryAllByRole('button', { name: /แก้ช่องทาง/ });

  it('renders no remedy at all when SET_ENTRY_CHANNEL is not advertised (handler wired, wrong-way-round)', () => {
    renderBuyer({ entryChannelAction: undefined });
    expect(toggles()).toHaveLength(0);
    expect(screen.queryByTestId('entry-channel-fix')).toBeNull();
  });
});

// SK-05 (owner ruling 2026-10-29): a stage the deal never sat on reads ข้าม, not เสร็จแล้ว. "Sat on"
// comes from the deal's STAGE_CHANGED events (fromStatus → toStatus, oldest first, as the server
// sends them); stages before the first recorded change are pre-history and count as done.
describe('DealStageStepper marks the stages a deal skipped', () => {
  const stageChange = (id, from, to) => ({
    id, kind: 'STAGE_CHANGED', fromStatus: from, toStatus: to, createdAt: `2026-10-0${id}T09:00:00.000Z`,
  });
  const pipFor = (code) => screen.getAllByTestId('route-pip').find((pip) => pip.getAttribute('data-stage') === code);
  const rowFor = (code) => screen.getAllByTestId('stage-row').find((row) => row.getAttribute('data-stage') === code);

  it('an owner skip (4 → 8) reads ข้าม on 5, 6 and 7 in the ribbon, the phase headers and the rows', () => {
    const events = [
      { id: 9, kind: 'SUBMITTED', fromStatus: null, toStatus: 'OWNER_SIGNOFF', createdAt: '2026-09-30T09:00:00.000Z' },
      stageChange(1, 'LEAD_APPROACH', 'PRESENTATION'),
      stageChange(2, 'PRESENTATION', 'SPEC_APPROVED'),
      stageChange(3, 'SPEC_APPROVED', 'QUOTE_DESIGN_SIDE'),
      stageChange(4, 'QUOTE_DESIGN_SIDE', 'QUOTE_BUYER'),
    ];
    render(<DealStageStepper catalog={catalog} salesStage="QUOTE_BUYER" stageDecisions={decisionsFor([])}
      entryChannel="DESIGNER_LED" events={events} />);

    expect(within(pipFor('LEAD_APPROACH')).getByText('ขั้นที่ 1 เสร็จแล้ว')).not.toBeNull();
    expect(within(pipFor('QUOTE_DESIGN_SIDE')).getByText('ขั้นที่ 4 เสร็จแล้ว')).not.toBeNull();
    expect(within(pipFor('QUOTE_OWNER')).getByText('ขั้นที่ 5 ข้าม')).not.toBeNull();
    expect(within(pipFor('OWNER_SIGNOFF')).getByText('ขั้นที่ 6 ข้าม')).not.toBeNull();
    expect(within(pipFor('AWAITING_BUYER')).getByText('ขั้นที่ 7 ข้าม')).not.toBeNull();
    expect(within(pipFor('QUOTE_BUYER')).getByText('ขั้นที่ 8 ขั้นปัจจุบัน')).not.toBeNull();

    // Phase 2 (S3–S6) is behind the deal: done, with its two skipped stages counted. Phase 3 (S7–S9)
    // is current: S8 is the one step reached, S7 is skipped.
    expect(screen.getByText('เสร็จแล้ว · ข้าม 2')).not.toBeNull();
    expect(screen.getByText('1/3 ขั้นตอน · ข้าม 1')).not.toBeNull();
    expect(rowFor('AWAITING_BUYER').textContent).toContain('ข้าม');
    expect(rowFor('QUOTE_BUYER').textContent).not.toContain('ข้าม');

    fireEvent.click(screen.getByRole('button', { name: /เฟส 2/ }));
    expect(rowFor('QUOTE_OWNER').textContent).toContain('ข้าม');
    expect(rowFor('OWNER_SIGNOFF').textContent).toContain('ข้าม');
    expect(rowFor('QUOTE_DESIGN_SIDE').textContent).not.toContain('ข้าม');
  });
});
