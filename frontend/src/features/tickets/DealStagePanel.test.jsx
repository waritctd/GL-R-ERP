import React from 'react';
import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { DealStagePanel } from './DealStagePanel.jsx';

globalThis.React = React;

// The panel no longer takes a `user` and no longer decides anything: it renders the server's
// availableActions plus its per-stage stageDecisions (TicketService.stageDecisions), over the
// backend-served stage catalog. Both are supplied here as the API supplies them.
const catalog = DEAL_STAGE_CATALOG;

// "Everything the server would allow" — enough for the UPDATE_STAGE gate, which now asks whether
// ANY decision is allowed rather than recomputing allowedTargetStages.
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
  );
  return { ref, ...utils };
}

// Ticket-detail IA rebuild Phase 1: แก้ไขสถานะ…/เสียงาน/พักดีลไว้/พัก dormant
// no longer render inline here — TicketDetailPage's header overflow menu and
// bottom danger zone trigger them via this forwardRef instead (see the
// component's own doc comment). Each exposed opener re-checks its own gate
// before acting, so a caller invoking one this deal doesn't actually allow
// is a no-op rather than a forced action.
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

  it('openHold() is a no-op without PLACE_ON_HOLD, and opens the note modal with it', () => {
    const { ref, rerender } = renderPanelWithRef({ availableActions: [] });
    act(() => ref.current.openHold());
    expect(screen.queryByText('พักดีลไว้', { selector: 'h2' })).toBeNull();

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
    expect(screen.getByText('พักดีลไว้', { selector: 'h2' })).not.toBeNull();
  });

  it('openDormant() is a no-op without MARK_DORMANT, and opens the note modal with it', () => {
    const { ref, rerender } = renderPanelWithRef({ availableActions: [] });
    act(() => ref.current.openDormant());
    expect(screen.queryByText('พัก dormant', { selector: 'h2' })).toBeNull();

    rerender(
      <DealStagePanel
        ref={ref}
        summary={baseSummary()}
        stageDecisions={allAllowed}
        catalog={catalog}
        availableActions={[{ action: 'MARK_DORMANT' }]}
        {...noopHandlers}
      />,
    );
    act(() => ref.current.openDormant());
    expect(screen.getByText('พัก dormant', { selector: 'h2' })).not.toBeNull();
  });

  it('openMarkLost() is a no-op without MARK_LOST, and opens MarkLostModal with it', () => {
    const { ref, rerender } = renderPanelWithRef({ availableActions: [] });
    act(() => ref.current.openMarkLost());
    expect(screen.queryByRole('dialog')).toBeNull();

    rerender(
      <DealStagePanel
        ref={ref}
        summary={baseSummary()}
        stageDecisions={allAllowed}
        catalog={catalog}
        availableActions={[{ action: 'MARK_LOST' }]}
        {...noopHandlers}
      />,
    );
    act(() => ref.current.openMarkLost());
    expect(screen.getByRole('dialog')).not.toBeNull();
  });

  // FIX 2 (ticket-detail IA rebuild Phase 1 clutter follow-up): "เลื่อนไป"
  // moved out of this panel's own JSX into TicketDetailPage's header overflow
  // menu — openAdvance() is now the only way this deal actually advances.
  // baseSummary's salesStage 'PRESENTATION' -> nextStage is 'SPEC_APPROVED'
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

  // FIX 3 (P2, clutter-follow-up review round 2): the old inline buttons
  // these openers replaced were each `disabled={actionLoading}` — a mutation
  // already in flight blocked a second click on the same action. That
  // native-attribute guard was lost when the buttons moved into the overflow
  // menu (whose items use aria-disabled, not the native attribute, so they
  // stay reachable — see OverflowMenu's own doc comment); these openers are
  // where it has to come back, since TicketDetailPage's overflow item only
  // disables on its OWN precondition (readyToAdvance for เลื่อนไป, nothing
  // at all for the other three) and never re-derives actionLoading itself.
  it('openAdvance() is a no-op while actionLoading is true, even though the gate and advanceReady both pass', () => {
    // A fresh local spy, not the shared noopHandlers.onUpdateStage — that
    // mock already has a call recorded from the "gate and advanceReady both
    // satisfied" test above it (this file never resets mocks between tests),
    // so asserting against it here would pass or fail on stale state instead
    // of this test's own action.
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

  it('openEditStage() is a no-op while actionLoading is true, even though UPDATE_STAGE is available', () => {
    const { ref } = renderPanelWithRef({ availableActions: [{ action: 'UPDATE_STAGE' }], actionLoading: true });
    act(() => ref.current.openEditStage());
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('openHold() is a no-op while actionLoading is true, even though PLACE_ON_HOLD is available', () => {
    const { ref } = renderPanelWithRef({ availableActions: [{ action: 'PLACE_ON_HOLD' }], actionLoading: true });
    act(() => ref.current.openHold());
    expect(screen.queryByText('พักดีลไว้', { selector: 'h2' })).toBeNull();
  });

  it('openDormant() is a no-op while actionLoading is true, even though MARK_DORMANT is available', () => {
    const { ref } = renderPanelWithRef({ availableActions: [{ action: 'MARK_DORMANT' }], actionLoading: true });
    act(() => ref.current.openDormant());
    expect(screen.queryByText('พัก dormant', { selector: 'h2' })).toBeNull();
  });

  it('does not render an inline "เลื่อนไป" button any more (moved to the header overflow menu)', () => {
    renderPanel({
      availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'SPEC_APPROVED' }],
      advanceReady: true,
    });
    expect(screen.queryByTestId('deal-stage-advance')).toBeNull();
    expect(screen.queryByText(/เลื่อนไป:/)).toBeNull();
  });
});

// Slice A "chip diet": the pricing-request roll-up strip (commit 6 / Fix 3 of
// the review-remediation plan — formerly tested right here), the "ยอดชำระ" /
// "นโยบายมัดจำ" payment badges, the PAYMENT_SUBSTEPS chip row, and the
// PROCUREMENT_SUBSTEPS chip row + "ส่งมอบ x/y" badge were all removed from
// this panel (see its own doc comment for where each one went — deleted as a
// duplicate, or moved to the money tab). These are wrong-way-round regression
// guards, not the positive "it renders" tests the old suite had: each one
// feeds in summary/pricingRequests data that used to trigger the removed row,
// and asserts it stays gone. The `pricingRequests` prop itself no longer
// exists on this component (it's still accepted as an extra prop here only to
// prove a stale caller passing it can't resurrect the strip).
describe('DealStagePanel Slice A "chip diet" — removed sub-status rows stay gone', () => {
  it('never renders the pricing-request roll-up strip, regardless of pricingRequests data passed in', () => {
    renderPanel({
      pricingRequests: [
        { id: 1, status: 'DRAFT', recipientType: 'DESIGNER' },
        { id: 2, status: 'SUBMITTED', recipientType: 'OWNER' },
        { id: 3, status: 'IMPORT_REVIEWING', recipientType: 'BUYER' },
      ],
    });
    expect(screen.queryByText('การขอราคา:')).toBeNull();
    expect(screen.queryByText(/คำขอราคา \d+ รายการ/)).toBeNull();
  });

  it('never renders the "ยอดชำระ" / "นโยบายมัดจำ" payment badges or the PAYMENT_SUBSTEPS chip row, even with paymentStage/paymentStatus/depositPolicy data present', () => {
    renderPanel({
      summary: baseSummary({
        paymentStage: 'PARTIALLY_PAID',
        paymentStatus: 'DEPOSIT_PAID',
        depositPolicy: 'WAIVED',
        depositPolicyReason: 'ลูกค้าประจำ',
        overdue: true,
      }),
    });
    expect(screen.queryByText('ยอดชำระ:')).toBeNull();
    expect(screen.queryByText('นโยบายมัดจำ:')).toBeNull();
    // PAYMENT_SUBSTEPS moved to the money tab (TicketDetailPage), not
    // deleted — but it never renders from inside THIS panel either way.
    expect(screen.queryByText('การชำระเงิน:')).toBeNull();
    expect(screen.queryByText('ลูกค้ายืนยัน')).toBeNull();
  });

  it('never renders the PROCUREMENT_SUBSTEPS chip row or the "ส่งมอบ:" badge, even with fulfillmentStatus data present (DealFulfilmentPanel owns this now)', () => {
    renderPanel({ summary: baseSummary({ fulfillmentStatus: 'PARTIALLY_DELIVERED' }) });
    expect(screen.queryByText('การนำเข้า:')).toBeNull();
    expect(screen.queryByText('ส่งมอบ:')).toBeNull();
    expect(screen.queryByText('ส่งมอบบางส่วน')).toBeNull();
  });

  it('still renders the pipeline\'s own stage content (this panel is not empty — only the duplicated rows are gone)', () => {
    renderPanel({ summary: baseSummary({ salesStage: 'QUOTE_DESIGN_SIDE' }) });
    // V143 gave the project owner their own stage (QUOTE_OWNER, S5), so S4's wording narrowed to
    // the designer alone — it used to read "เสนอราคาผู้ออกแบบ/เจ้าของ", which after the split named
    // a recipient this stage no longer covers.
    expect(screen.getByText('เสนอราคาผู้ออกแบบ')).not.toBeNull();
  });
});
