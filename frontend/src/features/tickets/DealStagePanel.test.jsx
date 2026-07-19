import React from 'react';
import { render, screen } from '@testing-library/react';
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
  onSetDepositPolicy: vi.fn(),
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

// commit 6: the "การขอราคา" substep strip used to be keyed off ticket.status
// (PRICING_SUBSTEPS), which is now permanently 'draft' since ticket creation
// no longer auto-submits (TicketService.create/submit, commit 5). It must now
// key off the deal's most recent PricingRequest instead.
describe('DealStagePanel pricing-request substep strip', () => {
  it('renders no strip when the deal has no pricing requests', () => {
    renderPanel({ pricingRequests: [] });
    expect(screen.queryByText('การขอราคา:')).toBeNull();
  });

  it('reflects a SUBMITTED pricing request even though the ticket itself is still draft', () => {
    renderPanel({
      summary: baseSummary({ status: 'draft' }),
      pricingRequests: [{ id: 1, status: 'SUBMITTED' }],
    });
    expect(screen.getByText('การขอราคา:')).not.toBeNull();
    expect(screen.getByText('ส่งให้ Import แล้ว')).not.toBeNull();
  });

  it('does not render the strip once the latest pricing request is cancelled', () => {
    renderPanel({ pricingRequests: [{ id: 1, status: 'CANCELLED' }] });
    expect(screen.queryByText('การขอราคา:')).toBeNull();
  });

  it('keys off the highest-id (most recent) pricing request when several exist', () => {
    renderPanel({
      pricingRequests: [
        { id: 1, status: 'CANCELLED' },
        { id: 2, status: 'IMPORT_REVIEWING' },
      ],
    });
    expect(screen.getByText('การขอราคา:')).not.toBeNull();
    expect(screen.getByText('Import กำลังเสนอราคา')).not.toBeNull();
  });
});
