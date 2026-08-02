import React from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DealMoneyTimeline } from './DealMoneyTimeline.jsx';

globalThis.React = React;

function baseSummary(overrides = {}) {
  return {
    paymentStage: 'PARTIALLY_PAID',
    paymentStatus: 'DEPOSIT_PAID',
    depositPolicy: 'REQUIRED',
    amountPayable: 1000,
    amountPaid: 400,
    amountOutstanding: 600,
    billingDate: '2026-07-01',
    dueDate: '2026-07-31',
    overdue: false,
    nextFollowUpAt: null,
    ...overrides,
  };
}

describe('DealMoneyTimeline', () => {
  // The core defect this component exists to fix: a timeline built from only
  // one of its two sources would silently drop the other, and a test that
  // only ever supplies one source would never catch that. Supplying BOTH a
  // money-track event and a receipt, at genuinely different timestamps, and
  // asserting on their relative order is what makes this a real merge test.
  it('renders rows from both the money-track events and the payment receipts, newest first', () => {
    render(
      <DealMoneyTimeline
        events={[
          // Earlier: DEPOSIT_PAID event.
          { id: 1, kind: 'DEPOSIT_PAID', actorName: 'ฝ่ายบัญชี', createdAt: '2026-06-20T09:00:00.000Z' },
          // Not on the payment track — must be filtered out entirely.
          { id: 2, kind: 'STAGE_CHANGED', actorName: 'พนักงานขาย', createdAt: '2026-06-25T09:00:00.000Z' },
        ]}
        paymentReceipts={[
          // Later: the actual receipt row for that same deposit.
          {
            receiptId: 501, kind: 'DEPOSIT', amount: 400, receivedAt: '2026-07-10T09:00:00.000Z',
            recordedByName: 'คุณบัญชี', note: 'โอนแล้ว',
          },
        ]}
        summary={baseSummary()}
      />,
    );

    const items = screen.getAllByRole('listitem').map((li) => li.textContent);
    // STAGE_CHANGED must never appear — it isn't on the payment track.
    expect(items.some((t) => t.includes('เปลี่ยนสถานะดีล'))).toBe(false);
    // Exactly the two payment-track rows: the receipt (newer) before the event (older).
    expect(items).toHaveLength(2);
    expect(items[0]).toContain('คุณบัญชี');
    expect(items[1]).toContain('รับมัดจำแล้ว');
  });

  // Same-instant tiebreak discipline (mirrors DealHistoryPanel's own "FIX 4"
  // regression test): one fixed secondary key applies across BOTH sources,
  // not a same-kind-only special case, and a null/unparseable timestamp
  // sorts last instead of floating to the top or crashing the sort.
  it('gives event rows and receipt rows a consistent order at a shared instant, and sorts an unparseable timestamp last', () => {
    const sameInstant = '2026-07-20T10:00:00.000Z';
    render(
      <DealMoneyTimeline
        events={[
          { id: 10, kind: 'CUSTOMER_CONFIRMED', actorName: 'พนักงานขาย', createdAt: sameInstant },
        ]}
        paymentReceipts={[
          { receiptId: 1, kind: 'DEPOSIT', amount: 400, receivedAt: sameInstant, recordedByName: 'คุณบัญชี' },
          { receiptId: 2, kind: 'BALANCE', amount: 600, receivedAt: null, recordedByName: 'คุณบัญชี' },
        ]}
        summary={baseSummary()}
      />,
    );

    const items = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(items).toHaveLength(3);
    // Fixed rule: events sort before receipts at a shared instant.
    expect(items[0]).toContain('ลูกค้ายืนยันคำสั่งซื้อ');
    expect(items[1]).toContain('DEPOSIT');
    // The unparseable-timestamp receipt (BALANCE) sorts last, not first.
    expect(items[2]).toContain('BALANCE');
  });

  it('still shows the balance figures — de-emphasised, not removed', () => {
    render(<DealMoneyTimeline events={[]} paymentReceipts={[]} summary={baseSummary()} />);

    const payableTile = screen.getByText('ยอดที่ต้องชำระ').parentElement;
    expect(within(payableTile).getByText('฿1,000.00')).not.toBeNull();
    const paidTile = screen.getByText('ชำระแล้ว').parentElement;
    expect(within(paidTile).getByText('฿400.00')).not.toBeNull();
    const outstandingTile = screen.getByText('คงเหลือ').parentElement;
    expect(within(outstandingTile).getByText('฿600.00')).not.toBeNull();
  });

  it('shows an empty state when there are no money events and no receipts', () => {
    render(<DealMoneyTimeline events={[]} paymentReceipts={[]} summary={baseSummary()} />);
    expect(screen.getByText('ยังไม่มีความเคลื่อนไหว')).not.toBeNull();
    expect(screen.queryAllByRole('listitem')).toHaveLength(0);
  });

  it('renders บันทึกรับชำระเงิน / ตั้งค่าการวางบิล when their gates are true, and calls the right handler', () => {
    const onRecordPayment = vi.fn();
    const onSetBilling = vi.fn();
    render(
      <DealMoneyTimeline
        events={[]}
        paymentReceipts={[]}
        summary={baseSummary()}
        canRecordPayment
        canSetBilling
        onRecordPayment={onRecordPayment}
        onSetBilling={onSetBilling}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกรับชำระเงิน' }));
    expect(onRecordPayment).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: 'ตั้งค่าการวางบิล' }));
    expect(onSetBilling).toHaveBeenCalledTimes(1);
  });

  // Wrong-way-round: a viewer without either gate must not see either
  // action, not merely "the test never checked for it".
  it('wrong-way-round: hides both บันทึกรับชำระเงิน and ตั้งค่าการวางบิล when neither gate is granted', () => {
    render(
      <DealMoneyTimeline
        events={[]}
        paymentReceipts={[]}
        summary={baseSummary()}
        canRecordPayment={false}
        canSetBilling={false}
      />,
    );

    expect(screen.queryByRole('button', { name: 'บันทึกรับชำระเงิน' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ตั้งค่าการวางบิล' })).toBeNull();
  });

  it('renders the payment-stage and overdue badges', () => {
    render(
      <DealMoneyTimeline
        events={[]}
        paymentReceipts={[]}
        summary={baseSummary({ paymentStage: 'PARTIALLY_PAID', overdue: true })}
      />,
    );

    expect(screen.getByText('ชำระบางส่วน')).not.toBeNull();
    expect(screen.getByText('เกินกำหนด')).not.toBeNull();
  });

  it('does not render the overdue badge when the deal is not overdue', () => {
    render(
      <DealMoneyTimeline
        events={[]}
        paymentReceipts={[]}
        summary={baseSummary({ overdue: false })}
      />,
    );

    expect(screen.queryByText('เกินกำหนด')).toBeNull();
  });
});
