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
