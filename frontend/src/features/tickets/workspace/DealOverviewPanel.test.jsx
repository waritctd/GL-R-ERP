import React from 'react';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DealOverviewPanel } from './DealOverviewPanel.jsx';

globalThis.React = React;

const summary = {
  code: 'PR-2026-0006',
  title: 'EU Trading Co.',
  customerName: 'EU Trading Co.',
  projectName: 'โครงการ Central',
  contactName: 'คุณลูกค้า',
  createdByName: 'คุณสมหมาย ขายดี',
  createdAt: '2026-06-01T09:00:00.000Z',
  updatedAt: '2026-06-10T09:00:00.000Z',
  salesStage: 'SUPPLIER_ORDER_PLACED',
  lifecycle: 'ACTIVE',
  depositPolicy: 'STANDARD',
  paymentStage: 'AWAITING_FINAL_PAYMENT',
  amountOutstanding: 240000,
  overdue: true,
  fulfillmentStatus: 'IR_SENT',
};

describe('DealOverviewPanel', () => {
  it('renders deal details, one workflow summary, and only three recent events', () => {
    const onSelectTab = vi.fn();
    render(
      <DealOverviewPanel
        summary={summary}
        pricingRequests={[]}
        events={[
          { id: 1, kind: 'CREATED', actorName: 'A', createdAt: '2026-06-01T09:00:00.000Z' },
          { id: 2, kind: 'SUBMITTED', actorName: 'B', createdAt: '2026-06-02T09:00:00.000Z' },
          { id: 3, kind: 'PAYMENT_RECORDED', actorName: 'C', createdAt: '2026-06-03T09:00:00.000Z' },
          { id: 4, kind: 'QUOTATION_ISSUED', actorName: 'D', createdAt: '2026-06-04T09:00:00.000Z' },
        ]}
        latestQuotation={{ number: 'QT-1', docStatus: 'ISSUED', issuedByName: 'ฝ่ายขาย' }}
        deliveryProgress={{ delivered: 3, ordered: 10 }}
        workState={{ state: 'overdue', labelTh: 'ยอดค้างเกินกำหนด', explanationTh: 'ต้องติดตามยอดชำระ', responsibleRole: 'account' }}
        visibleTabIds={['pricing', 'quotations', 'money', 'fulfilment', 'activity']}
        onSelectTab={onSelectTab}
      />,
    );

    expect(screen.getByTestId('deal-overview-panel')).not.toBeNull();
    expect(screen.getByText('EU Trading Co.')).not.toBeNull();
    expect(screen.getByText('สถานะงานแต่ละสาย')).not.toBeNull();
    expect(within(screen.getByTestId('overview-workflow-summary')).getAllByRole('button', { name: 'ดูรายละเอียด' }).length).toBeGreaterThan(0);
    expect(screen.getByText('ยอดค้างเกินกำหนด')).not.toBeNull();
    expect(screen.queryByText('สร้างดีล')).toBeNull();
    expect(screen.getByText('ออกใบเสนอราคา')).not.toBeNull();
  });
});
