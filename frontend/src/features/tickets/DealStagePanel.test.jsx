import React from 'react';
import {
  act, fireEvent, render, screen, within,
} from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { api } from '../../api/index.js';
import { DealStagePanel } from './DealStagePanel.jsx';

// The guided-advance flow calls api.tickets.{updateTracking,addActivity} directly (then defers the actual
// stage move to the parent's onUpdateStage), so the module is mocked here (vitest hoists vi.mock above the
// imports). Other tests in this file are presentational and never touch it.
vi.mock('../../api/index.js', () => ({
  api: {
    tickets: {
      updateTracking: vi.fn().mockResolvedValue({}),
      addActivity: vi.fn().mockResolvedValue({}),
    },
  },
}));

globalThis.React = React;

// The panel no longer takes a `user` and no longer decides anything: it renders the server's availableActions
// plus its per-stage stageDecisions (TicketService.stageDecisions), over the backend-served stage catalog.
// Both are supplied here as the API supplies them.
const catalog = DEAL_STAGE_CATALOG;

// "Everything the server would allow": enough for the UPDATE_STAGE gate, which asks whether ANY
// decision is allowed rather than recomputing allowedTargetStages.
const allAllowed = catalog.stages.map((stage, index) => ({
  stage: stage.code, no: index + 1, allowed: true, requiresReason: false, blockedReason: null,
}));

function baseSummary(overrides = {}) {
  return {
    createdById: 1,
    lifecycle: 'ACTIVE',
    salesStage: 'PRESENTATION',
    status: 'draft',
    paymentStatus: null,
    paymentStage: null,
    fulfillmentStatus: null,
    stageUpdatedAt: '2026-07-01T09:00:00.000Z',
    tenderRequirement: 'UNKNOWN',
    depositPolicy: 'REQUIRED',
    depositPolicyReason: null,
    overdue: false,
    ...overrides,
  };
}

const noopHandlers = {
  onUpdateStage: vi.fn(),
  onMarkLost: vi.fn(),
  onReopen: vi.fn(),
  onHold: vi.fn(),
  onDormant: vi.fn(),
  onResume: vi.fn(),
  onSetTenderRequirement: vi.fn(),
};

// DealStagePanel calls useQueryClient/useMutation (the guided-advance flow), so it must render
// inside a QueryClientProvider. A fresh client per render keeps tests isolated; rerender() inherits
// this wrapper.
function QueryWrapper({ children }) {
  const [client] = React.useState(() => new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  }));
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function renderPanel(props = {}) {
  return render(
    <DealStagePanel
      summary={baseSummary()}
      availableActions={[]}
      stageDecisions={allAllowed}
      catalog={catalog}
      {...noopHandlers}
      {...props}
    />,
    { wrapper: QueryWrapper },
  );
}

function renderPanelWithRef(props = {}) {
  const ref = React.createRef();
  const utils = render(
    <DealStagePanel
      ref={ref}
      summary={baseSummary()}
      availableActions={[]}
      stageDecisions={allAllowed}
      catalog={catalog}
      {...noopHandlers}
      {...props}
    />,
    { wrapper: QueryWrapper },
  );
  return { ref, ...utils };
}

// Ticket-detail IA rebuild Phase 1: แก้ไขสถานะ…/เสียงาน/พักดีลไว้/พัก dormant no longer render inline here —
// TicketDetailPage's header overflow menu and bottom danger zone trigger them via this forwardRef instead (see
// the component's own doc comment).
describe('DealStagePanel imperative handle (overflow menu / danger zone triggers)', () => {
  it('openEditStage() is a no-op when UPDATE_STAGE is not in availableActions', () => {
    const { ref } = renderPanelWithRef({ availableActions: [] });
    act(() => ref.current.openEditStage());
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('openEditStage() opens UpdateStageModal when the real gate allows it', () => {
    const { ref } = renderPanelWithRef({ availableActions: [{ action: 'UPDATE_STAGE' }] });
    act(() => ref.current.openEditStage());
    expect(screen.getByRole('dialog')).not.toBeNull();
  });

  // A skipped owner stage is final, so แก้ไขสถานะ never offers a stage the track marks ข้าม. The deal
  // went 4 -> 8 via the skip, so 5/6/7 were never visited.
  it('openEditStage() does not offer stages the deal skipped (4 -> 8), but still offers the rest', () => {
    const skipEvents = [{ kind: 'STAGE_CHANGED', fromStatus: 'QUOTE_DESIGN_SIDE', toStatus: 'QUOTE_BUYER' }];
    const { ref } = renderPanelWithRef({
      summary: baseSummary({ salesStage: 'QUOTE_BUYER' }),
      availableActions: [{ action: 'UPDATE_STAGE' }],
      events: skipEvents,
    });
    act(() => ref.current.openEditStage());
    const offered = within(screen.getByRole('dialog')).getAllByRole('option').map((o) => o.value);
    expect(offered).not.toContain('QUOTE_OWNER');
    expect(offered).not.toContain('OWNER_SIGNOFF');
    expect(offered).not.toContain('AWAITING_BUYER');
    expect(offered).toContain('QUOTE_DESIGN_SIDE');
    expect(offered).toContain('NEGOTIATION');
  });

  // พักดีลไว้ / พัก dormant are ONE พักงาน dialog since the m-hold drawing (2026-11-16); these used to assert the
  // two old note-modal titles. openHold preselects พักชั่วคราว, openDormant ไม่เคลื่อนไหว.
  it('openHold() is a no-op without PLACE_ON_HOLD, and opens พักงาน on พักชั่วคราว with it', () => {
    const { ref, rerender } = renderPanelWithRef({ availableActions: [] });
    act(() => ref.current.openHold());
    expect(screen.queryByText('พักงาน', { selector: 'h2' })).toBeNull();

    rerender(
      <DealStagePanel
        ref={ref}
        summary={baseSummary()}
        stageDecisions={allAllowed}
        catalog={catalog}
        availableActions={[{ action: 'PLACE_ON_HOLD' }]}
        {...noopHandlers}
      />,
    );
    act(() => ref.current.openHold());
    expect(screen.getByText('พักงาน', { selector: 'h2' })).not.toBeNull();
    expect(screen.getByRole('radio', { name: /พักชั่วคราว/ }).getAttribute('aria-checked')).toBe('true');
  });

  it('reopen() calls onReopen only on a lost deal with REOPEN (wrong-way: lost without REOPEN, active with REOPEN)', () => {
    const onReopen = vi.fn();
    const lost = baseSummary({ lifecycle: 'CLOSED_LOST' });
    const { ref, rerender } = renderPanelWithRef({ summary: lost, availableActions: [], onReopen });
    act(() => ref.current.reopen());
    rerender(<DealStagePanel ref={ref} summary={baseSummary()} stageDecisions={allAllowed} catalog={catalog} availableActions={[{ action: 'REOPEN' }]} {...noopHandlers} onReopen={onReopen} />);
    act(() => ref.current.reopen());
    expect(onReopen).not.toHaveBeenCalled();

    rerender(<DealStagePanel ref={ref} summary={lost} stageDecisions={allAllowed} catalog={catalog} availableActions={[{ action: 'REOPEN' }]} {...noopHandlers} onReopen={onReopen} />);
    act(() => ref.current.reopen());
    expect(onReopen).toHaveBeenCalledTimes(1);
  });

  // "เลื่อนไป" lives in TicketDetailPage's header overflow menu: openAdvance() is the only way this
  // deal advances. baseSummary's salesStage 'PRESENTATION' -> nextStage is 'SPEC_APPROVED'
  // (SALES_STAGES order), gated to 'sales'; salesOwner is the deal's owner.
  it('openAdvance() is a no-op without ADVANCE_STAGE for the next stage in availableActions', () => {
    const { ref } = renderPanelWithRef({ availableActions: [] });
    act(() => ref.current.openAdvance());
    expect(noopHandlers.onUpdateStage).not.toHaveBeenCalled();
  });

  it('openAdvance() is a no-op when the gate allows it but advanceReady is false (activity/follow-up precondition unmet)', () => {
    const { ref } = renderPanelWithRef({
      availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'SPEC_APPROVED' }],
      advanceReady: false,
    });
    act(() => ref.current.openAdvance());
    expect(noopHandlers.onUpdateStage).not.toHaveBeenCalled();
  });

  it('openAdvance() calls onUpdateStage with the next stage once both the gate and advanceReady are satisfied', () => {
    const { ref } = renderPanelWithRef({
      availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'SPEC_APPROVED' }],
      advanceReady: true,
    });
    act(() => ref.current.openAdvance());
    expect(noopHandlers.onUpdateStage).toHaveBeenCalledWith({ stage: 'SPEC_APPROVED' });
  });

  // The old inline buttons were each `disabled={actionLoading}`, so a mutation in flight blocked a
  // second click on the same action. That native-attribute guard was lost when the buttons moved into
  // the overflow menu (its items use aria-disabled, so they stay reachable; see OverflowMenu's doc
  // comment), so these openers are where it comes back: TicketDetailPage's overflow item disables
  // only on its OWN precondition (readyToAdvance for เลื่อนไป, nothing for the other three) and
  // never re-derives actionLoading.
  it('openAdvance() is a no-op while actionLoading is true, even though the gate and advanceReady both pass', () => {
    // A fresh local spy, not the shared noopHandlers.onUpdateStage — that mock already has a call recorded from
    // the "gate and advanceReady both satisfied" test above it, so asserting against it here would pass or fail on
    // stale state instead of this test's own action.
    const onUpdateStage = vi.fn();
    const { ref } = renderPanelWithRef({
      availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'SPEC_APPROVED' }],
      advanceReady: true,
      actionLoading: true,
      onUpdateStage,
    });
    act(() => ref.current.openAdvance());
    expect(onUpdateStage).not.toHaveBeenCalled();
  });
});

// When the ONLY thing blocking the next stage is the readiness gate (a follow-up date + a logged
// activity since the last stage change), the panel must not hide the advance button and show the
// misleading "ขั้นถัดไปอัปเดตโดยฝ่ายขาย" hint (dead text the rep is allowed to act on). The
// guided-advance flow collects exactly what the gate needs, satisfies it (updateTracking →
// addActivity) and moves the stage through the parent's onUpdateStage, so the gate is unchanged.
describe('DealStagePanel guided advance (readiness gate)', () => {
  // The skip stays (demoted, not hidden), but moving to ขั้น 13 before the goods arrive warns, in the
  // guided modal AND on the ready-to-move path (⋯ -> openAdvance).
  describe('W3-09 goods-not-arrived warning at PROCUREMENT', () => {
    const WARNING = /สินค้าจากโรงงานยังมาไม่ครบ/;
    const procurementProps = (overrides = {}) => ({
      summary: baseSummary({ id: 505, salesStage: 'PROCUREMENT' }),
      availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'DELIVERY_SCHEDULING' }],
      advanceReady: true,
      ...overrides,
    });
    const confirmButton = (dialog) => within(dialog).getByRole('button', { name: 'ข้ามไปขั้น 13' });

    it('openAdvance() at PROCUREMENT asks first with the warning, and moves only after the confirm', () => {
      const onUpdateStage = vi.fn();
      const { ref } = renderPanelWithRef(procurementProps({ onUpdateStage }));
      act(() => ref.current.openAdvance());

      expect(onUpdateStage).not.toHaveBeenCalled();
      const dialog = screen.getByRole('dialog');
      expect(within(dialog).getByText(WARNING)).not.toBeNull();

      fireEvent.click(confirmButton(dialog));
      expect(onUpdateStage).toHaveBeenCalledTimes(1);
      expect(onUpdateStage).toHaveBeenCalledWith({ stage: 'DELIVERY_SCHEDULING' });
    });

    it('cancelling the openAdvance() warning moves nothing and closes the dialog', () => {
      const onUpdateStage = vi.fn();
      const { ref } = renderPanelWithRef(procurementProps({ onUpdateStage }));
      act(() => ref.current.openAdvance());
      fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'ยกเลิก' }));

      expect(onUpdateStage).not.toHaveBeenCalled();
      expect(screen.queryByRole('dialog')).toBeNull();
    });
  });

  // F6: the ขั้น 12 confirm posts the stage captured when it opened; a deal that moved on posts nothing.
  describe('F6 the ขั้น 12 skip confirm is pinned', () => {
    const props = (overrides = {}) => ({
      summary: baseSummary({ id: 511, salesStage: 'PROCUREMENT' }),
      availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'DELIVERY_SCHEDULING' }, { action: 'ADVANCE_STAGE', targetStage: 'DELIVERED' }],
      advanceReady: true,
      ...overrides,
    });
    const rerenderWith = (rerender, ref, overrides) => rerender(
      <DealStagePanel
        ref={ref}
        summary={baseSummary({ id: 511, salesStage: 'DELIVERY_SCHEDULING' })}
        availableActions={[{ action: 'ADVANCE_STAGE', targetStage: 'DELIVERED' }]}
        stageDecisions={allAllowed}
        catalog={catalog}
        advanceReady
        {...noopHandlers}
        {...overrides}
      />,
    );

    it('the deal leaving PROCUREMENT while the dialog is open never posts DELIVERED', () => {
      const onUpdateStage = vi.fn();
      const { ref, rerender } = renderPanelWithRef(props({ onUpdateStage }));
      act(() => ref.current.openAdvance());
      rerenderWith(rerender, ref, { onUpdateStage });
      const confirm = screen.queryByRole('button', { name: /^ข้ามไปขั้น/ });
      if (confirm) fireEvent.click(confirm);
      expect(onUpdateStage).not.toHaveBeenCalledWith({ stage: 'DELIVERED' });
    });

    it('a confirm while an action is loading posts nothing', () => {
      const onUpdateStage = vi.fn();
      const { ref, rerender } = renderPanelWithRef(props({ onUpdateStage }));
      act(() => ref.current.openAdvance());
      rerender(
        <DealStagePanel
          ref={ref}
          summary={baseSummary({ id: 511, salesStage: 'PROCUREMENT' })}
          availableActions={props().availableActions}
          stageDecisions={allAllowed}
          catalog={catalog}
          advanceReady
          actionLoading
          {...noopHandlers}
          onUpdateStage={onUpdateStage}
        />,
      );
      const confirm = screen.queryByRole('button', { name: /^ข้ามไปขั้น/ });
      if (confirm) fireEvent.click(confirm);
      expect(onUpdateStage).not.toHaveBeenCalled();
    });
  });
});

/** Route-aware navigation (deal-route-staging). These tests build both by hand and pass them DIRECTLY as props
 * — never through mockApi.js, which does not emit `onRoute`: a mock-driven test would see every stage on-route
 * and pass vacuously. */
describe('DealStagePanel route awareness', () => {
  const OWNER_OFF = ['QUOTE_DESIGN_SIDE'];
  const BUYER_OFF = ['QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'AWAITING_BUYER'];
  const routeDecisions = (off) => catalog.stages.map((stage, index) => ({
    stage: stage.code,
    no: index + 1,
    allowed: !off.includes(stage.code),
    requiresReason: false,
    blockedReason: off.includes(stage.code)
      ? `งานนี้ไม่ผ่านเส้นทาง — ขั้นที่ ${index + 1} ไม่อยู่ในเส้นทางนี้ — แก้ช่องทางรับงานก่อน`
      : null,
    onRoute: !off.includes(stage.code),
  }));

  const routeLine = () => screen.queryByTestId('deal-route-line');

  describe('route line', () => {
    it.each([
      ['DESIGNER_LED', [], 'QUOTE_BUYER', 'เส้นทาง · ผู้ออกแบบนำงานมา', '15 ขั้น'],
      ['OWNER_DIRECT', OWNER_OFF, 'SPEC_APPROVED', 'เส้นทาง · เจ้าของติดต่อโดยตรง', '14 ขั้น'],
      ['BUYER_DIRECT', BUYER_OFF, 'QUOTE_BUYER', 'เส้นทาง · ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง', '11 ขั้น'],
    ])('%s names the route and its stage count, never a route position (W5-02)', (channel, off, salesStage, name, total) => {
      renderPanel({
        summary: baseSummary({ salesStage, entryChannel: channel }),
        stageDecisions: routeDecisions(off),
      });
      expect(routeLine()).not.toBeNull();
      expect(routeLine().textContent).toContain(`${name} · ${total}`);
      // The panel uses ONE numbering (the stage's own 1-15 number), so no route position here.
      expect(routeLine().textContent).not.toContain('ขั้นที่');
    });
  });

  describe('ถัดไป follows the route', () => {
    const nextText = () => screen.getByTestId('deal-stage-next').textContent;

    // A deal with no deposit never passes ขั้น 11 (nothing for the account team to confirm), so ถัดไป
    // must jump from ขั้น 10 to ขั้น 12 and must not promise "ได้รับเงินมัดจำ".
    it.each(['NOT_REQUIRED', 'WAIVED'])('depositPolicy %s at ORDER_RECEIVED: ถัดไป names 12 (PROCUREMENT), never ขั้น 11 or the account\'s deposit confirmation', (depositPolicy) => {
      renderPanel({
        summary: baseSummary({ salesStage: 'ORDER_RECEIVED', paymentStatus: 'CUSTOMER_CONFIRMED', depositPolicy }),
      });
      const text = nextText();
      expect(text).toContain('12.');
      expect(text).not.toContain('11.');
      expect(text).not.toContain('ได้รับเงินมัดจำ');
      expect(text).not.toContain('ฝ่ายบัญชียืนยันรับมัดจำ');
    });

    it('depositPolicy REQUIRED at ORDER_RECEIVED still names ขั้น 11 and the account\'s confirmation (wrong-way-round)', () => {
      renderPanel({
        summary: baseSummary({ salesStage: 'ORDER_RECEIVED', paymentStatus: 'CUSTOMER_CONFIRMED', depositPolicy: 'REQUIRED' }),
      });
      expect(nextText()).toContain('11.');
      expect(nextText()).toContain('ฝ่ายบัญชียืนยันรับมัดจำ');
    });
  });

  describe('an off-route target is never one click away', () => {
    /**
     * ⚠️ This test passes for TWO independent reasons: S4 is off the owner-direct route AND S4 is
     * `auto`, either of which alone makes `canAdvance` false. A regression that broke the off-route
     * check would still leave it green. The route half is pinned independently by `ถัดไป follows the
     * route` above, which reads `deal-stage-next` and does not consult `auto`: trust that one for
     * route behaviour, and fix it first if this pair ever disagrees.
     */
    it('openAdvance() moves to the ON-route S6 when that is the stage the server offers', () => {
      noopHandlers.onUpdateStage.mockClear();
      const { ref } = renderPanelWithRef({
        summary: baseSummary({ salesStage: 'QUOTE_OWNER', entryChannel: 'OWNER_DIRECT' }),
        stageDecisions: routeDecisions(OWNER_OFF),
        availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'OWNER_SIGNOFF' }],
      });
      act(() => ref.current.openAdvance());
      expect(noopHandlers.onUpdateStage).toHaveBeenCalledWith({ stage: 'OWNER_SIGNOFF' });
    });

    it('openAdvance() does not move to S4 on an owner-direct deal even if the server listed ADVANCE_STAGE for it', () => {
      noopHandlers.onUpdateStage.mockClear();
      const { ref } = renderPanelWithRef({
        summary: baseSummary({ salesStage: 'SPEC_APPROVED', entryChannel: 'OWNER_DIRECT' }),
        stageDecisions: routeDecisions(OWNER_OFF),
        availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'QUOTE_DESIGN_SIDE' }],
      });
      act(() => ref.current.openAdvance());
      expect(noopHandlers.onUpdateStage).not.toHaveBeenCalled();
    });

    /**
     * The POSITIVE control for the refusal above: without it, a panel that refused every advance
     * would pass the whole block. S5 is `auto`, so it can't be the "one click does work" example;
     * S6 (OWNER_SIGNOFF) is on the owner route and still a manual move.
     */
    it('openAdvance() moves to the ON-route S6 when that is the stage the server offers', () => {
      noopHandlers.onUpdateStage.mockClear();
      const { ref } = renderPanelWithRef({
        summary: baseSummary({ salesStage: 'QUOTE_OWNER', entryChannel: 'OWNER_DIRECT' }),
        stageDecisions: routeDecisions(OWNER_OFF),
        availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'OWNER_SIGNOFF' }],
      });
      act(() => ref.current.openAdvance());
      expect(noopHandlers.onUpdateStage).toHaveBeenCalledWith({ stage: 'OWNER_SIGNOFF' });
    });

    /**
     * The click seam: an ON-ROUTE quote stage the server listed `ADVANCE_STAGE` for is still not one
     * click away, because the way into it is a quotation addressed to that party. Distinct from the
     * off-route refusal above: here the route permits the stage and `auto` alone refuses, so this
     * goes red if `auto` is dropped from the quote stages while the backend keeps 409-ing the move.
     */
    it('openAdvance() does not move into an ON-route quote stage, even when the server offers it', () => {
      noopHandlers.onUpdateStage.mockClear();
      const { ref } = renderPanelWithRef({
        summary: baseSummary({ salesStage: 'SPEC_APPROVED', entryChannel: 'OWNER_DIRECT' }),
        stageDecisions: routeDecisions(OWNER_OFF),
        availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'QUOTE_OWNER' }],
      });
      act(() => ref.current.openAdvance());
      expect(noopHandlers.onUpdateStage).not.toHaveBeenCalled();
    });
  });
});

/**
 * แก้ช่องทางดีล: the remedy for the route gate's own refusal ("… — แก้ช่องทางดีลก่อน"). Nothing
 * renders on the default view; the remedy is threaded down to where the refusal is READ (the
 * stepper's off-route rows and UpdateStageModal's blocked list), and only when the server
 * advertised SET_ENTRY_CHANNEL. `onRoute` is built by hand (mockApi does not emit it).
 */
describe('DealStagePanel แก้ช่องทางดีล wiring', () => {
  const OWNER_OFF = ['QUOTE_DESIGN_SIDE'];
  const decisionsOff = (off) => catalog.stages.map((stage, index) => ({
    stage: stage.code,
    no: index + 1,
    allowed: !off.includes(stage.code),
    requiresReason: false,
    blockedReason: off.includes(stage.code)
      ? `ช่องทางรับงานของงานนี้คือเจ้าของติดต่อโดยตรง — ขั้นที่ ${index + 1} ไม่อยู่ในเส้นทางนี้ — แก้ช่องทางรับงานก่อน`
      : null,
    onRoute: !off.includes(stage.code),
  }));
  const FIRST = { action: 'SET_ENTRY_CHANNEL', kind: 'policy', requiredFields: ['value'] };
  const STATED = { action: 'SET_ENTRY_CHANNEL', kind: 'policy', requiredFields: ['value', 'note'] };
  const UPDATE = { action: 'UPDATE_STAGE' };
  const ownerDeal = () => baseSummary({ salesStage: 'SPEC_APPROVED', entryChannel: 'OWNER_DIRECT' });
  const toggles = () => screen.queryAllByRole('button', { name: /แก้ช่องทาง/ });
  // Asserts the control exists BEFORE clicking, so a missing remedy fails as an assertion.
  const firstToggle = () => {
    expect(toggles().length).toBeGreaterThan(0);
    return toggles()[0];
  };
  const showSteps = () => fireEvent.click(screen.getByRole('button', { name: /ดูขั้นตอนทั้งหมด/ }));

  it('asks for the reason only when the server\'s advertisement lists note', () => {
    const { unmount } = renderPanel({
      summary: ownerDeal(),
      availableActions: [UPDATE, FIRST],
      stageDecisions: decisionsOff(OWNER_OFF),
      onSetEntryChannel: vi.fn(),
    });
    showSteps();
    fireEvent.click(firstToggle());
    expect(screen.queryByTestId('entry-channel-fix-reason')).toBeNull();
    unmount();

    renderPanel({
      summary: ownerDeal(),
      availableActions: [UPDATE, STATED],
      stageDecisions: decisionsOff(OWNER_OFF),
      onSetEntryChannel: vi.fn(),
    });
    showSteps();
    fireEvent.click(firstToggle());
    expect(screen.getByTestId('entry-channel-fix-reason')).toBeTruthy();
  });
});

// The rebuilt lifecycle dialogs (owner-approved drawings, 2026-11-16) as the deal page opens them through this panel.
describe('DealStagePanel — พักงาน and ย้ายไป S2 (m-hold / m-stage)', () => {
  const s1Decisions = (blockedReason = null) => allAllowed.map((decision) => (decision.stage === 'SPEC_APPROVED'
    ? { ...decision, allowed: blockedReason == null, blockedReason, onRoute: true } : decision));

  it('from S1 with the gate met, ย้ายไป S2 moves straight to SPEC_APPROVED with the note', async () => {
    const onUpdateStage = vi.fn().mockResolvedValue();
    api.tickets.updateTracking.mockClear();
    api.tickets.addActivity.mockClear();
    const { ref } = renderPanelWithRef({
      summary: baseSummary({ salesStage: 'LEAD_APPROACH', nextFollowUpAt: '2026-12-01' }),
      availableActions: [{ action: 'UPDATE_STAGE' }], stageDecisions: s1Decisions(), onUpdateStage,
    });

    act(() => ref.current.openMoveToS2());
    fireEvent.change(screen.getByPlaceholderText('เช่น ผู้ออกแบบเลือก Raku 20×20 สีขาว'), { target: { value: 'Raku ขาว' } });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'ย้ายไป S2' })); });

    expect(onUpdateStage).toHaveBeenCalledWith({ stage: 'SPEC_APPROVED', note: 'Raku ขาว' });
    expect(api.tickets.updateTracking).not.toHaveBeenCalled();
    expect(api.tickets.addActivity).not.toHaveBeenCalled();
  });
});
