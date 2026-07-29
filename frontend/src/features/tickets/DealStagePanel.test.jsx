import React from 'react';
import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DealStagePanel } from './DealStagePanel.jsx';

globalThis.React = React;

const salesOwner = { id: 1, name: 'พนักงานขาย', role: 'sales' };

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
      user={salesOwner}
      summary={baseSummary()}
      availableActions={[]}
      pricingRequests={[]}
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
      user={salesOwner}
      summary={baseSummary()}
      availableActions={[]}
      pricingRequests={[]}
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
        user={salesOwner}
        summary={baseSummary()}
        availableActions={[{ action: 'PLACE_ON_HOLD' }]}
        pricingRequests={[]}
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
        user={salesOwner}
        summary={baseSummary()}
        availableActions={[{ action: 'MARK_DORMANT' }]}
        pricingRequests={[]}
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
        user={salesOwner}
        summary={baseSummary()}
        availableActions={[{ action: 'MARK_LOST' }]}
        pricingRequests={[]}
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

// commit 6 / Fix 3 (review-remediation plan): the "การขอราคา" strip used to be
// keyed off ticket.status (PRICING_SUBSTEPS), which is now permanently 'draft'
// since ticket creation no longer auto-submits (TicketService.create/submit,
// commit 5). It moved to being keyed off the deal's PricingRequests, first as
// "the highest-id (most recent) request wins" — but that reduction was itself
// a real bug: a DRAFT created after an active IMPORT_REVIEWING request has a
// higher id and hid the request Import is actually working on, and a
// cancelled newest request hid the whole strip even when an older request
// was still live. Fix 3 replaces the single-request reduction with a strip
// that surfaces every non-CANCELLED request.
describe('DealStagePanel pricing-request summary strip', () => {
  it('renders nothing when the deal has no pricing requests', () => {
    renderPanel({ pricingRequests: [] });
    expect(screen.queryByText('การขอราคา:')).toBeNull();
  });

  it('renders nothing once every pricing request is cancelled', () => {
    renderPanel({
      pricingRequests: [
        { id: 1, status: 'CANCELLED', recipientType: 'DESIGNER' },
        { id: 2, status: 'CANCELLED', recipientType: 'OWNER' },
      ],
    });
    expect(screen.queryByText('การขอราคา:')).toBeNull();
  });

  it('reflects a SUBMITTED pricing request even though the ticket itself is still draft', () => {
    renderPanel({
      summary: baseSummary({ status: 'draft' }),
      pricingRequests: [{ id: 1, status: 'SUBMITTED', recipientType: 'DESIGNER' }],
    });
    expect(screen.getByText('การขอราคา:')).not.toBeNull();
    expect(screen.getByText('รอ Import รับเรื่อง')).not.toBeNull();
  });

  // Inverts the old "keys off the highest-id (most recent) pricing request"
  // test, which asserted the bug directly: a DRAFT created after an active
  // IMPORT_REVIEWING request has a higher id, so the old reduction picked the
  // DRAFT and hid the work actually in flight. Both must now be visible.
  it('shows a newer DRAFT alongside an older still-active IMPORT_REVIEWING request', () => {
    renderPanel({
      pricingRequests: [
        { id: 2, status: 'IMPORT_REVIEWING', recipientType: 'DESIGNER' },
        { id: 3, status: 'DRAFT', recipientType: 'OWNER' },
      ],
    });
    expect(screen.getByText('การขอราคา:')).not.toBeNull();
    // Financial-integrity review remediation (COMMIT 3, af1aef4) deliberately
    // split IMPORT_REVIEWING's label from the pre-Step-2 "Import กำลังเสนอราคา"
    // ("Import is currently quoting") to "Import ตรวจคำขอราคา" ("Import is
    // reviewing the request") once AWAITING_FACTORY_RESPONSE ("รอราคาโรงงาน")
    // became its own distinct status for the factory-quoting phase — see
    // utils/format.js's pricingRequestStatusLabel. This test predates that
    // relabel and was never updated; asserting the old text was asserting
    // stale copy, not a regression.
    expect(screen.getByText('Import ตรวจคำขอราคา')).not.toBeNull();
    expect(screen.getByText('แบบร่าง')).not.toBeNull();
  });

  // Inverts the old "does not render the strip once the latest pricing
  // request is cancelled" test, which asserted the OTHER bug directly: a
  // newer CANCELLED request made the strip vanish even though an older
  // request was still live and needed attention. The active one must stay
  // visible.
  it('keeps an older active request visible even when the newest one is cancelled', () => {
    renderPanel({
      pricingRequests: [
        { id: 1, status: 'IMPORT_REVIEWING', recipientType: 'DESIGNER' },
        { id: 2, status: 'CANCELLED', recipientType: 'OWNER' },
      ],
    });
    expect(screen.getByText('การขอราคา:')).not.toBeNull();
    // See the label-relabel note above — 'Import ตรวจคำขอราคา' is the current,
    // deliberately narrowed IMPORT_REVIEWING copy, not 'Import กำลังเสนอราคา'.
    expect(screen.getByText('Import ตรวจคำขอราคา')).not.toBeNull();
  });

  it('renders a roll-up count alongside the per-request lines', () => {
    renderPanel({
      pricingRequests: [
        { id: 1, status: 'DRAFT', recipientType: 'DESIGNER' },
        { id: 2, status: 'SUBMITTED', recipientType: 'OWNER' },
        { id: 3, status: 'IMPORT_REVIEWING', recipientType: 'BUYER' },
      ],
    });
    expect(screen.getByText(/ใบขอราคา 3 รายการ/)).not.toBeNull();
  });
});
