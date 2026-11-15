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

const renderBoard = (deals) => render(<MemoryRouter><DealBoard deals={deals} /></MemoryRouter>);

describe('DealBoard', () => {
  it('shows the five columns with the reps\' S-codes, and the auto tag only on columns 3–5', () => {
    renderBoard([]);

    const labels = ['เสนอสินค้า · S1', 'สเปคผ่าน · S2', 'เสนอราคาแล้ว · S3–5', 'รอมัดจำ · S6', 'ส่งของ · เก็บเงิน · S7–9'];
    labels.forEach((label, i) => {
      const column = screen.getByTestId(`board-column-${i + 1}`);
      expect(column.getAttribute('aria-label')).toBe(label);
      const auto = within(column).queryByText('อัปเดตอัตโนมัติ');
      if (i < 2) expect(auto).toBeNull();
      else expect(auto).not.toBeNull();
    });
  });

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

  it('a card shows designer, expected quarter, win% and th-TH baht when present', () => {
    renderBoard([deal({
      ticketId: 21, title: 'BMW Showroom', designerName: 'DWP', expectedQuarter: '2026-Q4', winProbability: 70,
      value: 1560650,
    })]);

    const card = screen.getByTestId('board-card-21');
    expect(within(card).getByText('DWP · Q4/69')).toBeTruthy();
    expect(within(card).getByText('70%')).toBeTruthy();
    expect(within(card).getByText('฿1,560,650')).toBeTruthy();
    expect(within(card).getByRole('link', { name: 'BMW Showroom' }).getAttribute('href')).toBe('/tickets/21');
  });

  it('omits designer, quarter and win% when null, and shows a dash for no value', () => {
    renderBoard([deal({ ticketId: 22, title: 'ร้านกาแฟ' })]);

    const card = screen.getByTestId('board-card-22');
    expect(within(card).queryByText(/%$/)).toBeNull();
    expect(within(card).queryByText(/Q\d\//)).toBeNull();
    expect(within(card).getByText('—')).toBeTruthy();
  });

  it('flags a quiet card with its days', () => {
    renderBoard([deal({ ticketId: 23, quiet: true, daysQuiet: 27 })]);

    expect(within(screen.getByTestId('board-card-23')).getByText('เงียบ 27 วัน')).toBeTruthy();
  });

  it('stacks to one column on a phone instead of scrolling the page sideways', () => {
    renderBoard([]);

    const board = screen.getByTestId('deal-board');
    expect(board.className).toContain('mobile:grid-cols-1');
    expect(board.className).not.toMatch(/overflow-x|min-w-\[/);
  });
});
