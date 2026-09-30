import React from 'react';
import {
  act, fireEvent, render, screen, waitFor, within,
} from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { api } from '../../api/index.js';
import { STAGE_ADVANCE_GATE_MESSAGE } from './dealTrackingMeta.js';
import { DealStagePanel } from './DealStagePanel.jsx';
import { nextStageIn } from './stageCatalog.js';
import { AUTO_STAGE_HINT, GATE_LABEL } from './stageMeta.js';

// The guided-advance flow calls api.tickets.{updateTracking,addActivity} directly (then defers the
// actual stage move to the parent's onUpdateStage), so the module is mocked here (vitest hoists
// vi.mock above the imports). Other tests in this file are presentational and never touch it.
vi.mock('../../api/index.js', () => ({
  api: {
    tickets: {
      updateTracking: vi.fn().mockResolvedValue({}),
      addActivity: vi.fn().mockResolvedValue({}),
    },
  },
}));

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

// DealStagePanel now calls useQueryClient/useMutation (the guided-advance flow), so it must render
// inside a QueryClientProvider — at runtime the app root supplies one. A fresh client per render
// keeps tests isolated; rerender() inherits this wrapper automatically.
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

/**
 * The stage headline says who the stage is dealing with, in the title itself — there is no separate
 * counterparty row. Stages 4-8 already name the party in their own label; ORDER_RECEIVED did not
 * ("ได้รับใบสั่งซื้อ"), so this panel's headline for it names who the order came from.
 *
 * This also replaced ช่องทางรับงาน (entry channel, once an editable select — issue #740), which is no
 * longer shown here, and never offers a control.
 */
describe('DealStagePanel headline names the counterparty', () => {
  const owner = [{ action: 'SET_ENTRY_CHANNEL', kind: 'policy', label: 'ตั้งค่า entry channel', requiredFields: ['value'] }];

  it('reads "ได้รับใบสั่งซื้อจากผู้รับเหมา/ผู้ซื้อ" at ORDER_RECEIVED', () => {
    renderPanel({ summary: baseSummary({ salesStage: 'ORDER_RECEIVED' }) });
    expect(screen.getByText('ได้รับใบสั่งซื้อจากผู้รับเหมา/ผู้ซื้อ')).not.toBeNull();
    expect(screen.queryByText('ได้รับใบสั่งซื้อ')).toBeNull();
  });

  // Wrong-way-round: the override is for ONE stage; every other headline is the stage's own label.
  it.each(['QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'QUOTE_BUYER', 'PRESENTATION', 'DEPOSIT_RECEIVED'])(
    'leaves the %s headline as the stage label',
    (salesStage) => {
      renderPanel({ summary: baseSummary({ salesStage }) });
      expect(screen.queryByText(/จากผู้รับเหมา\/ผู้ซื้อ/)).toBeNull();
    },
  );

  // One concise line under the title: the department that owns the stage, read off the catalog gate.
  it.each(['PRESENTATION', 'ORDER_RECEIVED', 'DEPOSIT_RECEIVED', 'PROCUREMENT', 'CLOSED_PAID'])(
    'shows "ผู้รับผิดชอบ: <department>" under the title at %s',
    (salesStage) => {
      renderPanel({ summary: baseSummary({ salesStage }) });
      const gate = catalog.stages.find((stage) => stage.code === salesStage).gate;
      expect(screen.getByTestId('deal-stage-owner').textContent).toBe(`ผู้รับผิดชอบ: ${GATE_LABEL[gate]}`);
    },
  );

  it('shows no owner line while the stage catalog has not loaded (nothing to read the gate from)', () => {
    renderPanel({ catalog: { stages: [], phases: [] } });
    expect(screen.queryByTestId('deal-stage-owner')).toBeNull();
  });

  // The primary action lives in the sticky header, so with no `primaryAction` the panel must not
  // leave an empty flex row behind — each empty row still costs a full gap-3 of dead space.
  it('renders no empty placeholder rows when there is no primaryAction', () => {
    renderPanel({ summary: baseSummary({ salesStage: 'ORDER_RECEIVED' }), docActions: <a href="#doc">doc</a> });
    expect(screen.getByTestId('deal-stage-panel').querySelectorAll('div:empty')).toHaveLength(0);
  });

  it('has no separate counterparty / approver / channel row', () => {
    renderPanel({ summary: baseSummary({ salesStage: 'QUOTE_DESIGN_SIDE', entryChannel: 'OWNER_DIRECT' }) });
    expect(screen.queryByTestId('deal-stage-counterparty')).toBeNull();
    expect(screen.queryByText(/ขั้นนี้ติดต่อ|อนุมัติโดย|ช่องทางรับงาน/)).toBeNull();
    expect(screen.queryByText('เจ้าของติดต่อโดยตรง')).toBeNull();
  });

  it('renders no permanent channel control on the default view, even when the server offers SET_ENTRY_CHANNEL', () => {
    renderPanel({
      summary: baseSummary({ salesStage: 'QUOTE_DESIGN_SIDE', entryChannel: 'BUYER_DIRECT' }),
      availableActions: owner,
      onSetEntryChannel: vi.fn(),
    });
    expect(screen.queryByRole('combobox')).toBeNull();
    expect(screen.queryByRole('button', { name: 'เปลี่ยน' })).toBeNull();
  });
});

/**
 * Redesign (GLA-156 follow-up): the default view answers three questions — which stage, whose move,
 * what comes next — and everything else is one click away. These pin what was taken OFF the default
 * view so it cannot creep back, plus the two controls that were demoted rather than removed.
 */
describe('DealStagePanel focused default view', () => {
  const phaseCount = catalog.phases.length;
  const meta = catalog.stages.find((stage) => stage.code === 'PRESENTATION');

  it('shows ONE "เฟส N จาก M" summary, not a per-phase bar for every phase', () => {
    renderPanel();
    expect(screen.getByText(`เฟส ${meta.phase} จาก ${phaseCount}`, { exact: false })).not.toBeNull();
    // The old tracker and badge each rendered a bare "เฟส N" span.
    expect(screen.queryAllByText(/^เฟส \d$/)).toHaveLength(0);
  });

  it('brings the full phase tracker back inside "ดูขั้นตอนทั้งหมด"', () => {
    renderPanel();
    act(() => { screen.getByRole('button', { name: /ดูขั้นตอนทั้งหมด/ }).click(); });
    expect(screen.getAllByText(/^เฟส \d$/).length).toBeGreaterThanOrEqual(phaseCount);
  });

  it('does not render the unlabeled gate chip next to the stage name', () => {
    renderPanel({ summary: baseSummary({ salesStage: 'ORDER_RECEIVED' }) });
    // Exact match: the merged next-step line may still MENTION a department, the chip was the
    // bare label on its own.
    expect(screen.queryByText('ฝ่ายขาย')).toBeNull();
    expect(screen.queryByText('ฝ่ายบัญชี')).toBeNull();
  });

  it('says what comes next and who moves it in ONE element, once', () => {
    const { container } = renderPanel({ summary: baseSummary({ salesStage: 'ORDER_RECEIVED' }) });
    const next = nextStageIn(catalog, 'ORDER_RECEIVED');
    const hint = AUTO_STAGE_HINT[next.code];
    expect(hint).toBeTruthy();
    const line = screen.getByTestId('deal-stage-next');
    expect(line.textContent).toContain('ถัดไป');
    expect(line.textContent).toContain(hint);
    expect(container.textContent.split(hint)).toHaveLength(2); // exactly one occurrence
  });
});

// Bug 4 (fix/stage-advance-readiness-ux): when the ONLY thing blocking the next stage is the
// readiness gate (a follow-up date + a logged activity since the last stage change), the panel used
// to hide the advance button and show the misleading "ขั้นถัดไปอัปเดตโดยฝ่ายขาย" hint — dead text
// the rep is actually allowed to act on. The guided-advance flow replaces that with a button that
// collects exactly what the gate needs, then satisfies it (updateTracking → addActivity) and moves
// the stage through the parent's onUpdateStage, so the gate/business-logic is unchanged.
describe('DealStagePanel guided advance (readiness gate)', () => {
  // Next after PRESENTATION (S2) is SPEC_APPROVED (S3), gate=sales, auto=false — a manual sales
  // move, exactly the case the readiness gate applies to.
  const readinessBlocked = allAllowed.map((decision) => (
    decision.stage === 'SPEC_APPROVED'
      ? {
        ...decision, allowed: false, requiresReason: false, blockedReason: STAGE_ADVANCE_GATE_MESSAGE,
      }
      : decision
  ));

  it('replaces the dead hint with a guided button that fills the gate then advances', async () => {
    api.tickets.updateTracking.mockClear();
    api.tickets.addActivity.mockClear();
    const onUpdateStage = vi.fn().mockResolvedValue({});
    renderPanel({
      summary: baseSummary({ id: 501 }),
      availableActions: [],
      stageDecisions: readinessBlocked,
      onUpdateStage,
    });

    // The misleading "อัปเดตโดย…" hint is gone; the guided opener stands in its place.
    expect(screen.queryByText(/อัปเดตโดย/)).toBeNull();
    fireEvent.click(screen.getByTestId('guided-advance-open'));
    expect(screen.getByTestId('guided-advance-modal')).not.toBeNull();

    // Submit stays disabled until both gate inputs are present.
    expect(screen.getByTestId('guided-advance-submit').disabled).toBe(true);
    fireEvent.change(screen.getByTestId('guided-advance-followup'), { target: { value: '2026-10-05' } });
    fireEvent.change(screen.getByTestId('guided-advance-note'), { target: { value: 'โทรติดตามลูกค้า' } });
    expect(screen.getByTestId('guided-advance-submit').disabled).toBe(false);

    fireEvent.click(screen.getByTestId('guided-advance-submit'));

    // The move goes through the parent (preserving its invalidation/toast) with the next stage code…
    await waitFor(() => expect(onUpdateStage).toHaveBeenCalledWith({ stage: 'SPEC_APPROVED' }));
    // …and only after the gate was satisfied first.
    expect(api.tickets.updateTracking).toHaveBeenCalledWith(
      501,
      expect.objectContaining({ nextFollowUpAt: '2026-10-05', winProbability: null }),
    );
    expect(api.tickets.addActivity).toHaveBeenCalledWith(
      501,
      expect.objectContaining({ kind: 'CALL', note: 'โทรติดตามลูกค้า' }),
    );
  });

  it('does not show the guided opener when the next stage is blocked for another reason', () => {
    const otherBlock = allAllowed.map((decision) => (
      decision.stage === 'SPEC_APPROVED'
        ? {
          ...decision, allowed: false, requiresReason: false, blockedReason: 'ดีลถูกพักไว้',
        }
        : decision
    ));
    renderPanel({
      summary: baseSummary({ id: 502 }),
      availableActions: [],
      stageDecisions: otherBlock,
      onUpdateStage: vi.fn(),
    });
    expect(screen.queryByTestId('guided-advance-open')).toBeNull();
  });
});

/**
 * Route-aware navigation (deal-route-staging). The deal's entry channel decides which stages it
 * visits; the server serves the verdict per stage as `onRoute` on stageDecisions (StageDecisionDto)
 * and `entryChannel` on the summary. These tests build both by hand and pass them DIRECTLY as
 * props — never through mockApi.js, which does not emit `onRoute`: a mock-driven test would see
 * every stage on-route and pass vacuously.
 */
describe('DealStagePanel route awareness', () => {
  const OWNER_OFF = ['QUOTE_DESIGN_SIDE'];
  const BUYER_OFF = ['QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'AWAITING_BUYER'];
  const routeDecisions = (off) => catalog.stages.map((stage, index) => ({
    stage: stage.code,
    no: index + 1,
    allowed: !off.includes(stage.code),
    requiresReason: false,
    blockedReason: off.includes(stage.code)
      ? `ดีลนี้ไม่ผ่านเส้นทาง — ขั้นที่ ${index + 1} ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน`
      : null,
    onRoute: !off.includes(stage.code),
  }));
  const routeLine = () => screen.queryByTestId('deal-route-line');

  describe('route line', () => {
    it.each([
      ['DESIGNER_LED', [], 'QUOTE_BUYER', 'เส้นทาง · ผู้ออกแบบนำดีล', 'ขั้นที่ 8 จาก 15'],
      ['OWNER_DIRECT', OWNER_OFF, 'SPEC_APPROVED', 'เส้นทาง · เจ้าของติดต่อโดยตรง', 'ขั้นที่ 3 จาก 14'],
      ['BUYER_DIRECT', BUYER_OFF, 'QUOTE_BUYER', 'เส้นทาง · ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง', 'ขั้นที่ 4 จาก 11'],
    ])('%s names the route and the honest position', (channel, off, salesStage, name, position) => {
      renderPanel({
        summary: baseSummary({ salesStage, entryChannel: channel }),
        stageDecisions: routeDecisions(off),
      });
      expect(routeLine()).not.toBeNull();
      expect(routeLine().textContent).toContain(name);
      expect(routeLine().textContent).toContain(position);
    });

    // Never invent a route: UNSPECIFIED, an unknown value and an absent field all render NOTHING.
    it.each([
      ['UNSPECIFIED', { entryChannel: 'UNSPECIFIED' }],
      ['an unknown value', { entryChannel: 'SOMETHING_NEW' }],
      ['null', { entryChannel: null }],
      ['absent', {}],
    ])('renders no route line at all when the channel is %s', (_name, extra) => {
      renderPanel({ summary: baseSummary({ salesStage: 'SPEC_APPROVED', ...extra }) });
      expect(routeLine()).toBeNull();
      expect(screen.queryByText(/เส้นทาง ·/)).toBeNull();
    });
  });

  it('threads the route to PhaseSummary: buyer-direct at S8 reads ขั้นที่ 4 จาก 11, not 8 จาก 15', () => {
    renderPanel({
      summary: baseSummary({ salesStage: 'QUOTE_BUYER', entryChannel: 'BUYER_DIRECT' }),
      stageDecisions: routeDecisions(BUYER_OFF),
    });
    // The bar's own text (PhaseSummary), distinct from the route line's longer sentence.
    expect(screen.getByText('ขั้นที่ 4 จาก 11')).not.toBeNull();
    expect(screen.queryByText('ขั้นที่ 8 จาก 15')).toBeNull();
  });

  describe('ถัดไป follows the route', () => {
    const nextText = () => screen.getByTestId('deal-stage-next').textContent;

    it('owner-direct at S3 offers S5 (เสนอราคาเจ้าของโครงการ), not S4', () => {
      renderPanel({
        summary: baseSummary({ salesStage: 'SPEC_APPROVED', entryChannel: 'OWNER_DIRECT' }),
        stageDecisions: routeDecisions(OWNER_OFF),
      });
      expect(nextText()).toContain('5. เสนอราคาเจ้าของโครงการ');
      expect(nextText()).not.toContain('เสนอราคาผู้ออกแบบ');
    });

    it('buyer-direct at S3 offers S8 (เสนอราคาผู้ซื้อ/ผู้รับเหมา)', () => {
      renderPanel({
        summary: baseSummary({ salesStage: 'SPEC_APPROVED', entryChannel: 'BUYER_DIRECT' }),
        stageDecisions: routeDecisions(BUYER_OFF),
      });
      expect(nextText()).toContain('8. เสนอราคาผู้ซื้อ/ผู้รับเหมา');
    });

    it('designer-led at S3 still offers S4 (majority-route regression guard)', () => {
      renderPanel({
        summary: baseSummary({ salesStage: 'SPEC_APPROVED', entryChannel: 'DESIGNER_LED' }),
        stageDecisions: routeDecisions([]),
      });
      expect(nextText()).toContain('4. เสนอราคาผู้ออกแบบ');
    });

    it('the guided-advance opener names the ROUTE\'s next stage when the readiness gate is the only block', () => {
      const gated = routeDecisions(OWNER_OFF).map((decision) => (
        decision.stage === 'QUOTE_OWNER'
          ? { ...decision, allowed: false, blockedReason: STAGE_ADVANCE_GATE_MESSAGE }
          : decision
      ));
      renderPanel({
        summary: baseSummary({ id: 601, salesStage: 'SPEC_APPROVED', entryChannel: 'OWNER_DIRECT' }),
        availableActions: [],
        stageDecisions: gated,
      });
      expect(screen.getByTestId('guided-advance-open').textContent).toContain('5. เสนอราคาเจ้าของโครงการ');
    });
  });

  describe('an off-route target is never one click away', () => {
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

    it('openAdvance() moves to the ON-route S5 when that is the stage the server offers', () => {
      noopHandlers.onUpdateStage.mockClear();
      const { ref } = renderPanelWithRef({
        summary: baseSummary({ salesStage: 'SPEC_APPROVED', entryChannel: 'OWNER_DIRECT' }),
        stageDecisions: routeDecisions(OWNER_OFF),
        availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'QUOTE_OWNER' }],
      });
      act(() => ref.current.openAdvance());
      expect(noopHandlers.onUpdateStage).toHaveBeenCalledWith({ stage: 'QUOTE_OWNER' });
    });
  });

  describe('S3 wording follows the channel', () => {
    it.each([
      ['OWNER_DIRECT', 'เจ้าของตกลงตามสเปคแล้ว'],
      ['BUYER_DIRECT', 'ผู้ซื้อ/ผู้รับเหมาตกลงตามสเปคแล้ว'],
      ['DESIGNER_LED', 'ผู้ออกแบบอนุมัติสเปค'],
      ['UNSPECIFIED', 'ผู้ออกแบบอนุมัติสเปค'],
      [undefined, 'ผู้ออกแบบอนุมัติสเปค'],
    ])('the headline at S3 for %s reads %s', (channel, wording) => {
      renderPanel({ summary: baseSummary({ salesStage: 'SPEC_APPROVED', entryChannel: channel }) });
      expect(screen.getByText(wording)).not.toBeNull();
    });

    it('also words S3 inside the ON_HOLD branch, which builds its own label', () => {
      renderPanel({
        summary: baseSummary({ salesStage: 'SPEC_APPROVED', lifecycle: 'ON_HOLD', entryChannel: 'OWNER_DIRECT' }),
      });
      expect(screen.getByText(/เจ้าของตกลงตามสเปคแล้ว/)).not.toBeNull();
      expect(screen.queryByText(/ผู้ออกแบบอนุมัติสเปค/)).toBeNull();
    });
  });

  describe('ดูขั้นตอนทั้งหมด is threaded with the route', () => {
    it('buyer-direct: the expander shows the eleven-pip ribbon and the labelled off-route group', () => {
      renderPanel({
        summary: baseSummary({ salesStage: 'QUOTE_BUYER', entryChannel: 'BUYER_DIRECT' }),
        stageDecisions: routeDecisions(BUYER_OFF),
      });
      fireEvent.click(screen.getByRole('button', { name: /ดูขั้นตอนทั้งหมด/ }));
      expect(screen.getAllByTestId('route-pip')).toHaveLength(11);
      expect(within(screen.getByTestId('off-route-group')).getByText('ไม่อยู่ในเส้นทางนี้ (4)')).not.toBeNull();
    });

    it('the expander button counts the route\'s stages, not the catalog\'s', () => {
      renderPanel({
        summary: baseSummary({ salesStage: 'QUOTE_BUYER', entryChannel: 'BUYER_DIRECT' }),
        stageDecisions: routeDecisions(BUYER_OFF),
      });
      expect(screen.getByRole('button', { name: 'ดูขั้นตอนทั้งหมด (11 ขั้น)' })).not.toBeNull();
    });

    it('designer-led / no decisions: the button still says 15 and there is no off-route group', () => {
      renderPanel({ summary: baseSummary({ salesStage: 'QUOTE_BUYER', entryChannel: 'DESIGNER_LED' }) });
      fireEvent.click(screen.getByRole('button', { name: /ดูขั้นตอนทั้งหมด/ }));
      expect(screen.getAllByTestId('route-pip')).toHaveLength(15);
      expect(screen.queryByTestId('off-route-group')).toBeNull();
    });
  });
});

/**
 * แก้ช่องทางดีล — the remedy for the route gate's own refusal ("… — แก้ช่องทางดีลก่อน"). GLA-156 took
 * the standing ช่องทางรับงาน row off this panel on purpose and that stays true: nothing renders on
 * the default view. The remedy is threaded down to where the refusal is READ — the stepper's
 * off-route rows and UpdateStageModal's blocked list — and only when the server advertised
 * SET_ENTRY_CHANNEL. `onRoute` is built by hand (mockApi does not emit it).
 */
describe('DealStagePanel แก้ช่องทางดีล wiring', () => {
  const OWNER_OFF = ['QUOTE_DESIGN_SIDE'];
  const decisionsOff = (off) => catalog.stages.map((stage, index) => ({
    stage: stage.code,
    no: index + 1,
    allowed: !off.includes(stage.code),
    requiresReason: false,
    blockedReason: off.includes(stage.code)
      ? `ดีลนี้เป็นเจ้าของติดต่อโดยตรง — ขั้นที่ ${index + 1} ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน`
      : null,
    onRoute: !off.includes(stage.code),
  }));
  const FIRST = { action: 'SET_ENTRY_CHANNEL', kind: 'policy', requiredFields: ['value'] };
  const STATED = { action: 'SET_ENTRY_CHANNEL', kind: 'policy', requiredFields: ['value', 'note'] };
  const UPDATE = { action: 'UPDATE_STAGE' };
  const ownerDeal = () => baseSummary({ salesStage: 'SPEC_APPROVED', entryChannel: 'OWNER_DIRECT' });
  const toggles = () => screen.queryAllByRole('button', { name: /แก้ช่องทางดีล/ });
  // Asserts the control exists BEFORE clicking, so a missing remedy fails as an assertion.
  const firstToggle = () => {
    expect(toggles().length).toBeGreaterThan(0);
    return toggles()[0];
  };
  const showSteps = () => fireEvent.click(screen.getByRole('button', { name: /ดูขั้นตอนทั้งหมด/ }));

  it('adds nothing to the default view (the GLA-156 diet stands)', () => {
    renderPanel({
      summary: ownerDeal(),
      availableActions: [UPDATE, FIRST],
      stageDecisions: decisionsOff(OWNER_OFF),
      onSetEntryChannel: vi.fn(),
    });
    expect(toggles()).toHaveLength(0);
    expect(screen.queryByText(/ช่องทางรับงาน/)).toBeNull();
  });

  it('puts the remedy on the off-route row once the steps are shown, and saves through the handler', () => {
    const onSetEntryChannel = vi.fn();
    renderPanel({
      summary: ownerDeal(),
      availableActions: [UPDATE, FIRST],
      stageDecisions: decisionsOff(OWNER_OFF),
      onSetEntryChannel,
    });
    showSteps();
    expect(within(screen.getByTestId('off-route-group')).getAllByRole('button', { name: /แก้ช่องทางดีล/ })).toHaveLength(1);
    fireEvent.click(firstToggle());
    fireEvent.click(screen.getByRole('radio', { name: /ผู้ออกแบบนำดีล/ }));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' }));
    expect(onSetEntryChannel).toHaveBeenCalledWith({ value: 'DESIGNER_LED', note: null });
  });

  it('renders it nowhere when SET_ENTRY_CHANNEL is not advertised — even with a handler and off-route rows', () => {
    renderPanel({
      summary: ownerDeal(),
      availableActions: [UPDATE],
      stageDecisions: decisionsOff(OWNER_OFF),
      onSetEntryChannel: vi.fn(),
    });
    showSteps();
    expect(screen.getByTestId('off-route-group')).toBeTruthy();
    expect(toggles()).toHaveLength(0);
  });

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

  it('is reachable from UpdateStageModal\'s blocked list too, inline inside that one dialog', () => {
    const onSetEntryChannel = vi.fn();
    const { ref } = renderPanelWithRef({
      summary: ownerDeal(),
      availableActions: [UPDATE, STATED],
      stageDecisions: decisionsOff(OWNER_OFF),
      onSetEntryChannel,
    });
    act(() => ref.current.openEditStage());
    fireEvent.click(screen.getByTestId('update-stage-blocked-toggle'));
    fireEvent.click(within(screen.getByTestId('update-stage-blocked-list')).getByRole('button', { name: /แก้ช่องทางดีล/ }));
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    fireEvent.click(screen.getByRole('radio', { name: /ผู้ออกแบบนำดีล/ }));
    fireEvent.change(screen.getByTestId('entry-channel-fix-reason'), { target: { value: 'มีผู้ออกแบบจริง' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' }));
    expect(onSetEntryChannel).toHaveBeenCalledWith({ value: 'DESIGNER_LED', note: 'มีผู้ออกแบบจริง' });
  });

  it('is absent from UpdateStageModal when not advertised', () => {
    const { ref } = renderPanelWithRef({
      summary: ownerDeal(),
      availableActions: [UPDATE],
      stageDecisions: decisionsOff(OWNER_OFF),
      onSetEntryChannel: vi.fn(),
    });
    act(() => ref.current.openEditStage());
    fireEvent.click(screen.getByTestId('update-stage-blocked-toggle'));
    expect(toggles()).toHaveLength(0);
  });
});
