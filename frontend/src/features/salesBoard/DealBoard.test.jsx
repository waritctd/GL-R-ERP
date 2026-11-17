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
  // Adapted (slice 6.1): the bare "อัปเดตอัตโนมัติ" tag became each column's own move hint (MOVE_HINTS below). The
  // guarantee is the same — columns 1–2 carry no auto tag, columns 3–5 do — now with the words that say WHAT moves a
  // deal in.
  it('shows the five columns with the reps\' S-codes, and the auto tag only on columns 3–5', () => {
    renderBoard([]);

    const labels = ['เสนอสินค้า · S1', 'สเปคผ่าน · S2', 'เสนอราคาแล้ว · S3–5', 'รอมัดจำ · S6', 'ส่งของ · เก็บเงิน · S7–9'];
    labels.forEach((label, i) => {
      const column = screen.getByTestId(`board-column-${i + 1}`);
      expect(column.getAttribute('aria-label')).toBe(label);
      const auto = within(column).queryByText(/^เข้าเองเมื่อ/);
      if (i < 2) expect(auto).toBeNull();
      else expect(auto).not.toBeNull();
    });
    expect(screen.queryByText('อัปเดตอัตโนมัติ')).toBeNull();
  });

  /*
   * The move hints, pinned against where TicketService (backend/src/main/java/th/co/glr/hr/ticket/TicketService.java)
   * calls autoAdvanceStage — a column's hint must name what moves a deal INTO it, and nothing else does:
   *   col 3 เสนอราคาแล้ว (QUOTE_DESIGN_SIDE / QUOTE_OWNER / OWNER_SIGNOFF / AWAITING_BUYER): applyApprovedQuotation →
   *     advanceStageForCustomerQuotationIssue → stageForQuotationRecipient(DESIGNER → QUOTE_DESIGN_SIDE, OWNER →
   *     QUOTE_OWNER); generateQuotation does the same on a legacy issue. "ใบเสนอราคาอนุมัติ".
   *   col 4 รอมัดจำ (QUOTE_BUYER / NEGOTIATION / ORDER_RECEIVED): the same quotation path for a BUYER (or an
   *     UNSPECIFIED recipient, stageForUnspecifiedRecipient, "like a BUYER one") → QUOTE_BUYER; and the order confirm
   *     → autoAdvanceStage(ORDER_RECEIVED). Both are S6's own step, "ทราบผู้ซื้อแล้ว" (dealSteps.js). "ทราบผู้ซื้อ".
   *   col 5 ส่งของ · เก็บเงิน (DEPOSIT_RECEIVED … DELIVERED): the deposit confirmed → DEPOSIT_RECEIVED; the first IR
   *     issued → PROCUREMENT; and routeAllStockDealToDelivery → DELIVERY_SCHEDULING when every line is covered from
   *     stock (at a no-deposit order confirm, a deposit confirm, or reserveStock). "รับมัดจำ ออกใบขอซื้อ หรือของครบจาก
   *     สต็อก" — the prototype's "รับมัดจำหรือออกใบขอซื้อ" left out the all-stock path, which skips both.
   */
  it('each auto column says what moves a deal into it, in the words checked against TicketService', () => {
    renderBoard([]);

    expect(within(screen.getByTestId('board-column-3')).getByText('เข้าเองเมื่อใบเสนอราคาอนุมัติ')).toBeTruthy();
    expect(within(screen.getByTestId('board-column-4')).getByText('เข้าเองเมื่อทราบผู้ซื้อ')).toBeTruthy();
    expect(within(screen.getByTestId('board-column-5')).getByText('เข้าเองเมื่อรับมัดจำ ออกใบขอซื้อ หรือของครบจากสต็อก'))
      .toBeTruthy();
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
  it('counts each column in งาน and says ไม่มีงาน for an empty one (owner ruling: the sales screens say งาน)', () => {
    renderBoard([deal({ ticketId: 31, title: 'ร้านกาแฟ', value: 1000 })]);

    expect(within(screen.getByTestId('board-column-1')).getByText(/^1 งาน · ฿1,000$/)).toBeTruthy();
    expect(within(screen.getByTestId('board-column-2')).getByText('ไม่มีงาน')).toBeTruthy();
    expect(screen.queryByText(/ดีล/)).toBeNull();
  });

  it('names the owning rep on each card only when asked (managers), by first name with the full name kept', () => {
    const { unmount } = renderBoard([deal({ ticketId: 41, ownerName: 'สมหมาย ขายดี' })]);
    expect(within(screen.getByTestId('board-card-41')).queryByTestId('board-card-owner')).toBeNull();
    unmount();

    renderBoard([deal({ ticketId: 41, ownerName: 'สมหมาย ขายดี' })], { showOwner: true });

    const owner = within(screen.getByTestId('board-card-41')).getByTestId('board-card-owner');
    expect(owner.textContent).toContain('สมหมาย');
    expect(owner.textContent).not.toContain('ขายดี');
    expect(owner.getAttribute('title')).toBe('ผู้ดูแล: สมหมาย ขายดี');
  });

  // ── slice 6.2: the card's drawn details ──────────────────────────────────────────────────────────────────────

  const moneyCard = (over) => deal({ ticketId: 61, salesStage: 'DELIVERED', repCode: 'S9', boardColumn: 5,
    winProbability: 90, ...over });

  it('a เสนอราคาแล้ว card names the value quotation\'s number after its meta, kept on one piece', () => {
    renderBoard([deal({ ticketId: 71, salesStage: 'QUOTE_OWNER', repCode: 'S3', boardColumn: 3, designerName: 'DWP',
      quotationNumber: 'QT-2026-0005-1', winProbability: 50 })]);

    const number = within(screen.getByTestId('board-card-71')).getByText('QT-2026-0005-1', { exact: false });
    expect(number.className).toContain('whitespace-nowrap');
    // column 3 keeps its % chip: the money chip is a รอมัดจำ / ส่งของ · เก็บเงิน thing only
    expect(within(screen.getByTestId('board-card-71')).getByText('50%')).toBeTruthy();
  });

  it('only a เสนอราคาแล้ว card shows the quotation number', () => {
    renderBoard([moneyCard({ quotationNumber: 'QT-2026-0009' })]);

    expect(within(screen.getByTestId('board-card-61')).queryByText(/QT-2026-0009/)).toBeNull();
  });

  it('a รอมัดจำ card names the buyer instead of the designer, on ONE truncated line that keeps the full name', () => {
    const long = 'บริษัท ตัวอย่างวิศวกรรมและการก่อสร้างระหว่างประเทศ จำกัด (มหาชน)';
    renderBoard([deal({ ticketId: 72, salesStage: 'QUOTE_BUYER', repCode: 'S6', boardColumn: 4, designerName: 'DWP',
      buyerName: long, expectedQuarter: '2026-Q4' })]);

    const card = screen.getByTestId('board-card-72');
    const buyer = within(card).getByText(`ผู้ซื้อ: ${long}`);
    expect(buyer.className).toContain('truncate');
    expect(buyer.getAttribute('title')).toBe(`ผู้ซื้อ: ${long}`);
    // The card link's ::after overlay covers the buyer line, so the pointer is over the LINK there: the full name must
    // be the link's tooltip too, or no mouse user can ever see it.
    expect(within(card).getByRole('link').getAttribute('title')).toBe(`ผู้ซื้อ: ${long}`);
    expect(within(card).queryByText(/DWP/)).toBeNull();
    expect(within(card).getByText(/Q4\/69/)).toBeTruthy();
  });

  it('a รอมัดจำ card with no buyer yet keeps the designer', () => {
    renderBoard([deal({ ticketId: 73, salesStage: 'QUOTE_BUYER', repCode: 'S6', boardColumn: 4, designerName: 'DWP' })]);

    expect(within(screen.getByTestId('board-card-73')).getByText('DWP')).toBeTruthy();
  });

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

  it('the money chip replaces the % chip in รอมัดจำ too', () => {
    renderBoard([deal({ ticketId: 74, salesStage: 'QUOTE_BUYER', repCode: 'S6', boardColumn: 4, winProbability: 60,
      expectedDepositWeek: '2026-11-16' })]);

    const card = screen.getByTestId('board-card-74');
    expect(within(card).getByText('นัดรับ ถึง 20 พ.ย.')).toBeTruthy();
    expect(within(card).queryByText('60%')).toBeNull();
  });

  it('a card in รอมัดจำ / ส่งของ with no money state keeps its % chip', () => {
    renderBoard([moneyCard({})]);

    expect(within(screen.getByTestId('board-card-61')).getByText('90%')).toBeTruthy();
  });

  it('never shows a money chip outside รอมัดจำ / ส่งของ · เก็บเงิน, even when the fields are set', () => {
    renderBoard([deal({ ticketId: 75, salesStage: 'QUOTE_OWNER', repCode: 'S3', boardColumn: 3, winProbability: 40,
      depositReceived: true, paymentDueDate: '2026-07-28', overdue: true, expectedDepositWeek: '2026-11-30' })]);

    const card = screen.getByTestId('board-card-75');
    expect(within(card).getByText('40%')).toBeTruthy();
    expect(within(card).queryByText(/เกินกำหนด|ครบกำหนด|ได้มัดจำ|นัดรับ/)).toBeNull();
  });

  it('an overdue chip is the danger tone and the due chip is the warning tone (the finance worklist\'s colours)', () => {
    renderBoard([
      moneyCard({ ticketId: 81, paymentDueDate: '2026-07-28', overdue: true }),
      moneyCard({ ticketId: 82, paymentDueDate: '2026-11-23', overdue: false }),
    ]);

    expect(within(screen.getByTestId('board-card-81')).getByText('เกินกำหนด 28 ก.ค.').className).toContain('text-danger-dark');
    expect(within(screen.getByTestId('board-card-82')).getByText('ครบกำหนด 23 พ.ย.').className).toContain('text-warning-dark');
  });

  it('makes the whole card the tap target, not just the title text', () => {
    renderBoard([deal({ ticketId: 51, title: 'Landmark' })]);

    const card = screen.getByTestId('board-card-51');
    const link = within(card).getByRole('link', { name: 'Landmark' });
    expect(card.className).toContain('relative');
    expect(link.className).toContain('after:absolute');
    expect(link.className).toContain('after:inset-0');
  });
});
