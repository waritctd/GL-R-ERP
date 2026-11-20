import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DealBoard } from './DealBoard.jsx';

globalThis.React = React;

const deal = (over) => ({
  ticketId: 1, title: 'ดีล', customerName: 'ลูกค้า', projectName: null, ownerId: 6, ownerName: 'คุณสมหมาย',
  salesStage: 'LEAD_APPROACH', repCode: 'S1', boardColumn: 1, value: null, designerName: null, winProbability: null,
  expectedQuarter: null, lastTouchedAt: '2026-11-10T00:00:00Z', daysQuiet: 5, quiet: false, ...over,
});

const renderBoard = (deals, props = {}) => render(<MemoryRouter><DealBoard deals={deals} {...props} /></MemoryRouter>);

describe('DealBoard', () => {
  it('puts a QUOTE_OWNER deal in column 3 and a DELIVERED deal in column 5', () => {
    renderBoard([
      deal({ ticketId: 11, title: 'สยามพารากอน', salesStage: 'QUOTE_OWNER', repCode: 'S3', boardColumn: 3 }),
      deal({ ticketId: 12, title: 'Landmark Residence', salesStage: 'DELIVERED', repCode: 'S9', boardColumn: 5 }),
    ]);

    expect(within(screen.getByTestId('board-column-3')).getByText('สยามพารากอน')).toBeTruthy();
    expect(within(screen.getByTestId('board-column-5')).getByText('Landmark Residence')).toBeTruthy();
    expect(within(screen.getByTestId('board-column-1')).queryByText('สยามพารากอน')).toBeNull();
    expect(within(screen.getByTestId('board-column-3')).queryByText('Landmark Residence')).toBeNull();
    expect(within(screen.getByTestId('board-column-4')).queryByText('Landmark Residence')).toBeNull();
  });

  it('names the awarded buyer of the quotation on a รอมัดจำ card, over the typed buyer name', () => {
    renderBoard([deal({ ticketId: 71, salesStage: 'ORDER_RECEIVED', repCode: 'S6', boardColumn: 4,
      buyerName: 'พิมพ์เอง', awardedBuyerName: 'ผู้ซื้อที่ได้งาน +1' })]);

    expect(within(screen.getByTestId('board-card-71')).getByText('ผู้ซื้อ: ผู้ซื้อที่ได้งาน +1')).toBeTruthy();
  });

  // ── slice 6.2: the card's drawn details ──────────────────────────────────────────────────────────────────────

  const moneyCard = (over) => deal({ ticketId: 61, salesStage: 'DELIVERED', repCode: 'S9', boardColumn: 5,
    winProbability: 90, ...over });

  it.each([
    ['overdue', { paymentDueDate: '2026-07-28', overdue: true, depositReceived: true, expectedDepositWeek: '2026-11-30' },
      'เกินกำหนด 28 ก.ค.'],
    ['due (not overdue)', { paymentDueDate: '2026-11-23', overdue: false, depositReceived: true }, 'ครบกำหนด 23 พ.ย.'],
    ['deposit received', { depositReceived: true, expectedDepositWeek: '2026-11-30' }, 'ได้มัดจำ'],
    ['expected deposit week (its Friday)', { expectedDepositWeek: '2026-11-30' }, 'นัดรับ ถึง 4 ธ.ค.'],
  ])('one money chip per card, by priority — %s', (_, fields, text) => {
    renderBoard([moneyCard(fields)]);

    const card = screen.getByTestId('board-card-61');
    expect(within(card).getByText(text)).toBeTruthy();
    // the chip REPLACES the % chip in this column, and it is the only money chip
    expect(within(card).queryByText('90%')).toBeNull();
    expect(within(card).queryAllByText(/^(เกินกำหนด|ครบกำหนด|ได้มัดจำ|นัดรับ)/)).toHaveLength(1);
  });

  it('never shows a money chip outside รอมัดจำ / ส่งของ · เก็บเงิน, even when the fields are set', () => {
    renderBoard([deal({ ticketId: 75, salesStage: 'QUOTE_OWNER', repCode: 'S3', boardColumn: 3, winProbability: 40,
      depositReceived: true, paymentDueDate: '2026-07-28', overdue: true, expectedDepositWeek: '2026-11-30' })]);

    const card = screen.getByTestId('board-card-75');
    expect(within(card).getByText('40%')).toBeTruthy();
    expect(within(card).queryByText(/เกินกำหนด|ครบกำหนด|ได้มัดจำ|นัดรับ/)).toBeNull();
  });
});
