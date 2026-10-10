import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DealMoneyStatusStrip } from './DealMoneyStatusStrip.jsx';

globalThis.React = React;

function baseSummary(overrides = {}) {
  return {
    billingDate: null,
    dueDate: null,
    nextFollowUpAt: null,
    overdue: false,
    paymentStatus: null,
    depositPolicy: 'REQUIRED',
    ...overrides,
  };
}

describe('DealMoneyStatusStrip', () => {
  it('renders "-" for every unset date field', () => {
    render(<DealMoneyStatusStrip summary={baseSummary()} />);
    // formatThaiDate(null) === '-', and three date chips are unset here.
    expect(screen.getAllByText('-')).toHaveLength(3);
  });

  it('shows มัดจำ as "ยังไม่แจ้ง" when no deposit notice has been issued', () => {
    render(<DealMoneyStatusStrip summary={baseSummary({ paymentStatus: null })} />);
    expect(screen.getByText('ยังไม่แจ้ง')).toBeTruthy();
  });

  it('shows มัดจำ as "รอชำระ" once the deposit notice is issued but unpaid', () => {
    render(<DealMoneyStatusStrip summary={baseSummary({ paymentStatus: 'DEPOSIT_NOTICE_ISSUED' })} />);
    expect(screen.getByText('รอชำระ')).toBeTruthy();
  });

  // Wrong-way-round-adjacent: all three "already paid" statuses must read the same, not just
  // the literal DEPOSIT_PAID one — a regression that narrowed this to one status would silently
  // show "รอชำระ" for a deal already past AWAITING_FINAL_PAYMENT/FULLY_PAID.
  it.each(['DEPOSIT_PAID', 'AWAITING_FINAL_PAYMENT', 'FULLY_PAID'])(
    'shows มัดจำ as "ชำระแล้ว" for paymentStatus %s',
    (paymentStatus) => {
      render(<DealMoneyStatusStrip summary={baseSummary({ paymentStatus })} />);
      expect(screen.getByText('ชำระแล้ว')).toBeTruthy();
    },
  );

  // Review round 1 (2026-09-23): the bug this pins — a bypass-policy deal (TicketService
  // #recordPayment skips DEPOSIT_PAID entirely for these, advancing straight to
  // AWAITING_FINAL_PAYMENT) used to read มัดจำ from paymentStatus alone, so a credit customer who
  // simply paid their invoice showed "มัดจำ: ชำระแล้ว" though no deposit was ever requested or
  // paid. The bypass check must outrank the paid-statuses check.
  it.each(['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'])(
    'shows มัดจำ as "ไม่ต้องมัดจำ" for a %s deal even once paymentStatus reaches AWAITING_FINAL_PAYMENT',
    (depositPolicy) => {
      render(<DealMoneyStatusStrip summary={baseSummary({ depositPolicy, paymentStatus: 'AWAITING_FINAL_PAYMENT' })} />);
      expect(screen.getByText('ไม่ต้องมัดจำ')).toBeTruthy();
      expect(screen.queryByText('ชำระแล้ว')).toBeNull();
    },
  );

  it('formats set dates in Thai Buddhist-era form', () => {
    render(<DealMoneyStatusStrip summary={baseSummary({ billingDate: '2026-08-11' })} />);
    expect(screen.getByText('11 ส.ค. 2569')).toBeTruthy();
  });

  it('renders ครบกำหนดชำระ as a danger-tone badge when overdue, plain text otherwise', () => {
    const { container: onTime } = render(<DealMoneyStatusStrip summary={baseSummary({ dueDate: '2026-08-11', overdue: false })} />);
    expect(onTime.querySelector('.status-danger')).toBeNull();

    const { container: overdue } = render(<DealMoneyStatusStrip summary={baseSummary({ dueDate: '2026-08-11', overdue: true })} />);
    expect(overdue.querySelector('.status-danger')?.textContent).toBe('11 ส.ค. 2569');
  });
});
