import React from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import {
  DealStageStepper, PhaseSummary, PhaseTracker, StageProgressBar,
} from './DealStageStepper.jsx';

globalThis.React = React;

const catalog = DEAL_STAGE_CATALOG;
const OWNER_OFF = ['QUOTE_DESIGN_SIDE'];
const BUYER_OFF = ['QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'AWAITING_BUYER'];
// The server's own refusal, one distinct sentence per stage so a test can prove it is rendered
// VERBATIM per row (DealRoute.refusalMessage's shape).
const reasonFor = (stage) => `ดีลนี้เป็นผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง — ขั้นที่ ${stage.no} ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน`;

// ⚠️ Built here and passed DIRECTLY as props. mockApi.js does not emit `onRoute`, so driving these
// through the mock would see every stage on-route and pass vacuously.
const decisionsFor = (off) => catalog.stages.map((stage) => ({
  stage: stage.code,
  no: stage.no,
  allowed: !off.includes(stage.code),
  requiresReason: false,
  blockedReason: off.includes(stage.code) ? reasonFor(stage) : null,
  onRoute: !off.includes(stage.code),
}));

const pipCodes = () => screen.getAllByTestId('route-pip').map((pip) => pip.getAttribute('data-stage'));

// Owner ruling 2026-09-30: pips carry the ROUTE'S OWN ordinal (1..N), not the business stage number,
// so the ribbon and the `ขั้นที่ p จาก t` line can never disagree. On a buyer-direct deal the current
// stage is S8 but the 4th step of an 11-step route — the rep must read "4" in both places.
// Reads the VISIBLE glyph only (the aria-hidden span) — a done pip shows a tick and contributes
// nothing, and the sr-only sentence is excluded so it cannot smear into the digits.
const pipNumbers = () => screen.getAllByTestId('route-pip')
  .map((pip) => pip.querySelector('[aria-hidden="true"]')?.textContent?.trim() ?? '')
  .filter((text) => /^[0-9]+$/.test(text));

describe('RouteRibbon — ordinal numbering', () => {
  const BUYER_OFF = ['QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'AWAITING_BUYER'];

  it('numbers pips by the ROUTE ordinal, not the business stage number', () => {
    render(<DealStageStepper salesStage="QUOTE_BUYER" catalog={catalog}
      stageDecisions={decisionsFor(BUYER_OFF)} entryChannel="BUYER_DIRECT" />);
    // 11-stage route, current is its 4th step. Steps 1–3 are done and render a tick, so the first
    // NUMBER shown is the current step: 4, then 5…11. The business numbers (8, 9, 10…) must be gone.
    expect(pipNumbers()).toEqual(['4', '5', '6', '7', '8', '9', '10', '11']);
  });

  it('marks the 4th pip current, agreeing with ขั้นที่ 4 จาก 11', () => {
    render(<DealStageStepper salesStage="QUOTE_BUYER" catalog={catalog}
      stageDecisions={decisionsFor(BUYER_OFF)} entryChannel="BUYER_DIRECT" />);
    const pips = screen.getAllByTestId('route-pip');
    const currentIndex = pips.findIndex((pip) => pip.getAttribute('aria-current') === 'step');
    expect(currentIndex).toBe(3);                       // zero-based: the 4th pip
    expect(pips[currentIndex].textContent).toContain('4');
    expect(pips[currentIndex].textContent).not.toContain('8');
  });

  it('announces the route ordinal to screen readers, not the business number', () => {
    render(<DealStageStepper salesStage="QUOTE_BUYER" catalog={catalog}
      stageDecisions={decisionsFor(BUYER_OFF)} entryChannel="BUYER_DIRECT" />);
    const current = screen.getAllByTestId('route-pip')[3];
    expect(current.textContent).toContain('ขั้นที่ 4');
    expect(current.textContent).not.toContain('ขั้นที่ 8');
  });

  it('is unchanged on designer-led, where ordinal and business number coincide', () => {
    render(<DealStageStepper salesStage="QUOTE_BUYER" catalog={catalog}
      stageDecisions={decisionsFor([])} entryChannel="DESIGNER_LED" />);
    expect(pipNumbers()).toEqual(['8', '9', '10', '11', '12', '13', '14', '15']);
  });
});

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

describe('PhaseSummary — the denominator is THIS deal\'s route, not the catalog', () => {
  const barWidth = (container) => container.querySelector('[style*="width"]').getAttribute('style');

  it('buyer-direct at QUOTE_BUYER (S8) reads ขั้นที่ 4 จาก 11 and fills ~36%, not 8/15 = 53%', () => {
    const { container } = render(
      <PhaseSummary catalog={catalog} salesStage="QUOTE_BUYER" stageDecisions={decisionsFor(BUYER_OFF)} />,
    );
    expect(screen.getByText('ขั้นที่ 4 จาก 11')).not.toBeNull();
    expect(screen.queryByText('ขั้นที่ 8 จาก 15')).toBeNull();
    expect(barWidth(container)).toContain('36.36');
  });

  it('designer-led (nothing off-route) at QUOTE_BUYER still reads ขั้นที่ 8 จาก 15 and ~53%', () => {
    const { container } = render(
      <PhaseSummary catalog={catalog} salesStage="QUOTE_BUYER" stageDecisions={decisionsFor([])} />,
    );
    expect(screen.getByText('ขั้นที่ 8 จาก 15')).not.toBeNull();
    expect(barWidth(container)).toContain('53.33');
  });

  it('with no stageDecisions prop at all it degrades to today\'s 8 จาก 15', () => {
    render(<PhaseSummary catalog={catalog} salesStage="QUOTE_BUYER" />);
    expect(screen.getByText('ขั้นที่ 8 จาก 15')).not.toBeNull();
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

  it('designer-led renders all fifteen pips', () => {
    render(
      <DealStageStepper catalog={catalog} salesStage="QUOTE_BUYER" stageDecisions={decisionsFor([])} />,
    );
    expect(pipCodes()).toHaveLength(15);
  });

  it('degrades to all fifteen pips and NO off-route group when stageDecisions is absent or lacks onRoute', () => {
    const { unmount } = render(<DealStageStepper catalog={catalog} salesStage="QUOTE_BUYER" />);
    expect(pipCodes()).toHaveLength(15);
    expect(screen.queryByTestId('off-route-group')).toBeNull();
    unmount();

    const noField = decisionsFor(BUYER_OFF).map(({ onRoute, ...rest }) => rest);
    render(<DealStageStepper catalog={catalog} salesStage="QUOTE_BUYER" stageDecisions={noField} />);
    expect(pipCodes()).toHaveLength(15);
    expect(screen.queryByTestId('off-route-group')).toBeNull();
  });

  it('states each pip\'s status in words, not colour alone, and marks exactly one as current', () => {
    render(
      <DealStageStepper catalog={catalog} salesStage="QUOTE_BUYER" stageDecisions={decisionsFor(BUYER_OFF)} />,
    );
    const pips = screen.getAllByTestId('route-pip');
    // S1 S2 S3 done · S8 current · S9..S15 upcoming
    expect(within(pips[0]).getByText('ขั้นที่ 1 เสร็จแล้ว')).not.toBeNull();
    // Buyer-direct: S8 is the route's 4th step, so the pip announces "ขั้นที่ 4", not "ขั้นที่ 8"
    // (owner ruling 2026-09-30 — the ribbon and the ขั้นที่ p จาก t line must never disagree).
    expect(within(pips[3]).getByText('ขั้นที่ 4 ขั้นปัจจุบัน')).not.toBeNull();
    expect(within(pips[4]).getByText('ขั้นที่ 5 ยังไม่ถึง')).not.toBeNull();
    expect(pips.filter((pip) => pip.getAttribute('aria-current') === 'step')).toHaveLength(1);
  });

  it('marks no pip current for a lost deal', () => {
    render(
      <DealStageStepper catalog={catalog} salesStage="QUOTE_BUYER" lost stageDecisions={decisionsFor(BUYER_OFF)} />,
    );
    expect(screen.queryByText(/ขั้นปัจจุบัน/)).toBeNull();
  });

  it('renders no ribbon before the catalog has loaded', () => {
    render(<DealStageStepper salesStage="QUOTE_BUYER" />);
    expect(screen.queryByTestId('route-ribbon')).toBeNull();
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

  it('is absent for a designer-led deal (nothing is off-route)', () => {
    render(
      <DealStageStepper catalog={catalog} salesStage="QUOTE_BUYER" stageDecisions={decisionsFor([])} />,
    );
    expect(screen.queryByTestId('off-route-group')).toBeNull();
    expect(screen.queryByText(/ไม่อยู่ในเส้นทางนี้/)).toBeNull();
  });

  it('an off-route stage appears ONLY in the group — not also as a normal row in its phase', () => {
    // Phase 2 is open at SPEC_APPROVED and holds S4, which is off-route for owner-direct.
    render(
      <DealStageStepper catalog={catalog} salesStage="SPEC_APPROVED" stageDecisions={decisionsFor(OWNER_OFF)} />,
    );
    expect(screen.getAllByText('เสนอราคาผู้ออกแบบ')).toHaveLength(1);
    expect(within(screen.getByTestId('off-route-group')).getByText('เสนอราคาผู้ออกแบบ')).not.toBeNull();
  });

  it('still says the reason when the server sent none (never a silently dead row)', () => {
    const noReason = decisionsFor(OWNER_OFF).map((d) => ({ ...d, blockedReason: null }));
    render(<DealStageStepper catalog={catalog} salesStage="SPEC_APPROVED" stageDecisions={noReason} />);
    expect(within(screen.getByTestId('off-route-group')).getByText(/ไม่อยู่ในเส้นทางของดีลนี้/)).not.toBeNull();
  });

  it('labels the row a deal is actually SITTING on, so a corrected channel does not read as an error', () => {
    // Sat on S4, then the channel was corrected to owner-direct.
    render(
      <DealStageStepper catalog={catalog} salesStage="QUOTE_DESIGN_SIDE" stageDecisions={decisionsFor(OWNER_OFF)} />,
    );
    expect(within(screen.getByTestId('off-route-group')).getByText('ดีลอยู่ขั้นนี้')).not.toBeNull();
  });
});

describe('DealStageStepper phase list follows the route', () => {
  it('counts only on-route stages in a phase header (buyer-direct at S8: phase 3 is 1/2, not 2/3)', () => {
    render(
      <DealStageStepper catalog={catalog} salesStage="QUOTE_BUYER" stageDecisions={decisionsFor(BUYER_OFF)} />,
    );
    expect(screen.getByText('1/2 ขั้นตอน')).not.toBeNull();
  });

  it('words S3 by the deal\'s channel in the phase list', () => {
    const { unmount } = render(
      <DealStageStepper
        catalog={catalog}
        salesStage="SPEC_APPROVED"
        entryChannel="OWNER_DIRECT"
        stageDecisions={decisionsFor(OWNER_OFF)}
      />,
    );
    expect(screen.getByText('เจ้าของตกลงตามสเปคแล้ว')).not.toBeNull();
    expect(screen.queryByText('ผู้ออกแบบอนุมัติสเปค')).toBeNull();
    unmount();

    render(
      <DealStageStepper
        catalog={catalog}
        salesStage="SPEC_APPROVED"
        entryChannel="BUYER_DIRECT"
        stageDecisions={decisionsFor(BUYER_OFF)}
      />,
    );
    expect(screen.getByText('ผู้ซื้อ/ผู้รับเหมาตกลงตามสเปคแล้ว')).not.toBeNull();
  });

  it('keeps the designer wording with no channel', () => {
    render(<DealStageStepper catalog={catalog} salesStage="SPEC_APPROVED" />);
    expect(screen.getByText('ผู้ออกแบบอนุมัติสเปค')).not.toBeNull();
  });
});

/**
 * The remedy for the gate's own refusal. The server's blockedReason ends "— แก้ช่องทางดีลก่อน", and
 * GLA-156 had deleted the only control that could do that. It lives WHERE THE REFUSAL IS READ — on
 * each off-route row — as an inline disclosure, never a permanent row and never a modal.
 *
 * `entryChannelAction` is the server's own SET_ENTRY_CHANNEL advertisement, passed straight in;
 * the stepper decides nothing about who may correct a channel.
 */
describe('DealStageStepper off-route rows — แก้ช่องทางดีล', () => {
  const FIRST = { action: 'SET_ENTRY_CHANNEL', kind: 'policy', requiredFields: ['value'] };
  const STATED = { action: 'SET_ENTRY_CHANNEL', kind: 'policy', requiredFields: ['value', 'note'] };
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
  const toggles = () => screen.queryAllByRole('button', { name: /แก้ช่องทางดีล/ });
  // Asserts the control exists BEFORE clicking, so a missing remedy fails as an assertion.
  const firstToggle = () => {
    expect(toggles().length).toBeGreaterThan(0);
    return toggles()[0];
  };

  it('puts one remedy on EACH off-route row, and only inside the off-route group', () => {
    renderBuyer();
    const group = within(screen.getByTestId('off-route-group'));
    expect(group.getAllByRole('button', { name: /แก้ช่องทางดีล/ })).toHaveLength(BUYER_OFF.length);
    // Not in the phase list, not in the ribbon: nowhere but beside the refusal it answers.
    expect(toggles()).toHaveLength(BUYER_OFF.length);
  });

  it('renders no remedy at all when SET_ENTRY_CHANNEL is not advertised (handler wired, wrong-way-round)', () => {
    renderBuyer({ entryChannelAction: undefined });
    expect(toggles()).toHaveLength(0);
    expect(screen.queryByTestId('entry-channel-fix')).toBeNull();
  });

  it('renders no remedy when there is no handler, even with the action advertised', () => {
    renderBuyer({ onSetEntryChannel: undefined });
    expect(toggles()).toHaveLength(0);
  });

  it('renders no remedy when nothing is off-route (designer-led: nothing to fix)', () => {
    render(
      <DealStageStepper
        catalog={catalog}
        salesStage="QUOTE_BUYER"
        entryChannel="DESIGNER_LED"
        stageDecisions={decisionsFor([])}
        entryChannelAction={FIRST}
        onSetEntryChannel={vi.fn()}
      />,
    );
    expect(screen.queryByTestId('off-route-group')).toBeNull();
    expect(toggles()).toHaveLength(0);
  });

  it('keeps the server\'s refusal verbatim beside the remedy', () => {
    renderBuyer();
    const group = within(screen.getByTestId('off-route-group'));
    for (const code of BUYER_OFF) {
      const stage = catalog.stages.find((s) => s.code === code);
      expect(group.getByText(reasonFor(stage))).not.toBeNull();
    }
  });

  it('opens inline (no dialog) to exactly the three real channels', () => {
    renderBuyer();
    fireEvent.click(firstToggle());
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getAllByRole('radio').map((radio) => radio.value))
      .toEqual(['DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT']);
  });

  it('asks for a reason only when the advertisement asks, and saves through the handler', () => {
    const onSetEntryChannel = vi.fn();
    const { unmount } = renderBuyer({ entryChannelAction: FIRST, onSetEntryChannel });
    fireEvent.click(firstToggle());
    expect(screen.queryByTestId('entry-channel-fix-reason')).toBeNull();
    fireEvent.click(screen.getByRole('radio', { name: /ผู้ออกแบบนำดีล/ }));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' }));
    expect(onSetEntryChannel).toHaveBeenCalledWith({ value: 'DESIGNER_LED', note: null });
    unmount();

    const stated = vi.fn();
    renderBuyer({ entryChannelAction: STATED, onSetEntryChannel: stated });
    fireEvent.click(firstToggle());
    fireEvent.click(screen.getByRole('radio', { name: /ผู้ออกแบบนำดีล/ }));
    expect(screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' }).disabled).toBe(true);
    fireEvent.change(screen.getByTestId('entry-channel-fix-reason'), { target: { value: 'มีผู้ออกแบบจริง' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' }));
    expect(stated).toHaveBeenCalledWith({ value: 'DESIGNER_LED', note: 'มีผู้ออกแบบจริง' });
  });

  it('disables saving while an action is in flight', () => {
    renderBuyer({ actionLoading: true });
    fireEvent.click(firstToggle());
    fireEvent.click(screen.getByRole('radio', { name: /ผู้ออกแบบนำดีล/ }));
    expect(screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' }).disabled).toBe(true);
  });
});

// The list's segmented bar had the same catalog-wide denominator as its text: one flex-weighted
// segment per phase, sized by the phase's stage count (2,4,3,3,3 = 15). A buyer-direct deal's bar
// now counts only that channel's on-route stages, so the graphic and "ขั้นตอน 4/11" cannot disagree.
describe('StageProgressBar — per-phase segments count only the deal\'s on-route stages', () => {
  const segments = (container) => Array.from(container.firstChild.children);
  const grows = (container) => segments(container).map((seg) => Number(seg.style.flexGrow));
  const fills = (container) => segments(container)
    .map((seg) => parseFloat(seg.firstChild.style.width));

  it('buyer-direct: segment weights 2,1,2,3,3 (11 stages); QUOTE_BUYER fills phases 1-2 and half of 3', () => {
    const { container } = render(
      <StageProgressBar catalog={catalog} salesStage="QUOTE_BUYER" entryChannel="BUYER_DIRECT" />,
    );
    expect(grows(container)).toEqual([2, 1, 2, 3, 3]);
    expect(fills(container).map(Math.round)).toEqual([100, 100, 50, 0, 0]);
  });

  it('owner-direct: 2,3,3,3,3 (14 stages)', () => {
    const { container } = render(
      <StageProgressBar catalog={catalog} salesStage="QUOTE_OWNER" entryChannel="OWNER_DIRECT" />,
    );
    expect(grows(container)).toEqual([2, 3, 3, 3, 3]);
  });

  it.each([['DESIGNER_LED'], ['UNSPECIFIED'], ['SOMETHING_NEW'], [null], [undefined]])(
    'channel %s is exactly today\'s bar: 2,4,3,3,3 and QUOTE_BUYER fills 2/3 of phase 3',
    (channel) => {
      const { container } = render(
        <StageProgressBar catalog={catalog} salesStage="QUOTE_BUYER" entryChannel={channel} />,
      );
      expect(grows(container)).toEqual([2, 4, 3, 3, 3]);
      expect(fills(container).map(Math.round)).toEqual([100, 100, 67, 0, 0]);
    },
  );

  it('with no entryChannel prop at all it is unchanged (optional, defaults to today\'s behaviour)', () => {
    const { container } = render(<StageProgressBar catalog={catalog} salesStage="QUOTE_BUYER" />);
    expect(grows(container)).toEqual([2, 4, 3, 3, 3]);
  });

  it('a lost deal is an empty track on every route', () => {
    const { container } = render(
      <StageProgressBar catalog={catalog} salesStage="QUOTE_BUYER" entryChannel="BUYER_DIRECT" lost />,
    );
    expect(fills(container)).toEqual([0, 0, 0, 0, 0]);
  });

  it('a deal sitting on a since-off-route stage does not throw and never over-fills', () => {
    const { container } = render(
      <StageProgressBar catalog={catalog} salesStage="QUOTE_DESIGN_SIDE" entryChannel="BUYER_DIRECT" />,
    );
    expect(fills(container).every((width) => width >= 0 && width <= 100)).toBe(true);
    expect(fills(container).map(Math.round)).toEqual([100, 100, 0, 0, 0]);
  });

  it('an unknown stage or the pre-load empty catalog renders without throwing', () => {
    expect(() => render(
      <StageProgressBar catalog={catalog} salesStage="NOT_A_STAGE" entryChannel="BUYER_DIRECT" />,
    )).not.toThrow();
    expect(() => render(
      <StageProgressBar salesStage="QUOTE_BUYER" entryChannel="BUYER_DIRECT" />,
    )).not.toThrow();
  });
});
