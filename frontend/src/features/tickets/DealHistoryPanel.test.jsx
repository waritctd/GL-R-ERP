import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { formatMoney } from '../../utils/format.js';
import { DealHistoryPanel } from './DealHistoryPanel.jsx';

globalThis.React = React;

// D5 (Opus review finding, see the branch's handoff): sales.ticket_event.created_at
// defaults to Postgres now() (transaction_timestamp()), so every event written in
// the same transaction — e.g. TicketService.confirmCustomer writes CUSTOMER_CONFIRMED
// then STAGE_CHANGED in one transaction — shares one identical instant. The
// repository's own tiebreak is `ORDER BY created_at ASC, event_id ASC`, and
// Array#sort is stable, so a naive `b.ts - a.ts` comparator that returns 0 on a tie
// lets that ascending sub-order survive inside this otherwise-descending
// (newest-first) list — the two events render reversed.
describe('DealHistoryPanel', () => {
  it('D5 regression: two events sharing one timestamp render newest-first by event id, not ticket_event query order', () => {
    const sameInstant = '2026-07-20T10:00:00.000Z';
    render(
      <DealHistoryPanel
        events={[
          // Sent in the repository's own ORDER BY created_at ASC, event_id ASC —
          // the exact order that used to leak straight through into the rendered
          // list under the old comparator.
          { id: 10, kind: 'CUSTOMER_CONFIRMED', actorName: 'พนักงานขาย', createdAt: sameInstant },
          { id: 11, kind: 'STAGE_CHANGED', actorName: 'พนักงานขาย', createdAt: sameInstant },
        ]}
        activities={[]}
      />,
    );

    const items = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(items).toHaveLength(2);
    // id 11 (STAGE_CHANGED) was written later in the same transaction, so it
    // belongs first in a newest-first list — even though it was passed second.
    expect(items[0]).toContain('เปลี่ยนสถานะดีล');
    expect(items[1]).toContain('ลูกค้ายืนยันคำสั่งซื้อ');
  });

  it('still renders newest-first for events at genuinely different timestamps (unaffected by the tiebreak)', () => {
    render(
      <DealHistoryPanel
        events={[
          { id: 1, kind: 'CREATED', actorName: 'พนักงานขาย', createdAt: '2026-07-01T09:00:00.000Z' },
          { id: 2, kind: 'SUBMITTED', actorName: 'พนักงานขาย', createdAt: '2026-07-05T09:00:00.000Z' },
        ]}
        activities={[]}
      />,
    );

    const items = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(items[0]).toContain('ส่งเรื่องเข้าระบบ');
    expect(items[1]).toContain('สร้างดีล');
  });

  it('a tie that is not between two events (e.g. an unparseable/missing timestamp) keeps a stable order rather than crashing', () => {
    render(
      <DealHistoryPanel
        events={[
          { id: 1, kind: 'CREATED', actorName: 'พนักงานขาย', createdAt: null },
        ]}
        activities={[
          { id: 501, kind: 'CALL', activityDate: null, createdByName: 'พนักงานขาย', note: null },
        ]}
      />,
    );

    // Both rows have an unparseable timestamp — `toTimestamp` returns null for
    // both, so neither is the two-events case the id tiebreak targets. No
    // crash, and both still render (order unaffected by this fix).
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  // The activity form defaults to today in Bangkok, read when the form renders: at 06:30 Bangkok the
  // UTC date is still yesterday, and a date fixed when the module loaded goes stale overnight.
  it('the activity form defaults its date to today in Bangkok, not the UTC date', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-30T23:30:00Z'));
    try {
      const { container } = render(<DealHistoryPanel canAddActivity />);
      expect(container.querySelector('input[type="date"]').value).toBe('2026-10-31');
    } finally {
      vi.useRealTimers();
    }
  });

  // GLA-74 (Opus review finding, 2026-09-19): DEAL_QUOTATION_SUPERSEDED had no EVENT_KIND_LABEL
  // entry, so a missing label falls through to the RAW kind string (`EVENT_KIND_LABEL[event.kind]
  // ?? event.kind`) -- a rep would see "DEAL_QUOTATION_SUPERSEDED" verbatim in the deal history.
  it('labels DEAL_QUOTATION_SUPERSEDED in Thai rather than falling through to the raw kind string', () => {
    render(
      <DealHistoryPanel
        events={[
          { id: 1, kind: 'DEAL_QUOTATION_SUPERSEDED', actorName: 'พนักงานขาย',
            message: 'ใบ QT-2026-0001-1 ถูกแทนที่ด้วย QT-2026-0001-2', createdAt: '2026-09-19T09:00:00.000Z' },
        ]}
        activities={[]}
      />,
    );

    const item = screen.getByRole('listitem').textContent;
    expect(item).toContain('ใบเสนอราคาถูกแทนที่');
    expect(item).not.toContain('DEAL_QUOTATION_SUPERSEDED');
  });

  // Slice 2 (SLICE-2-FLOW-A.md §F, IA §8): the direct quotation's order confirmation is
  // "ยืนยันคำสั่งซื้อ", not "สร้างดีล…" — under the linked design the deal already exists, so the
  // event records the customer's order, and its history label says so.
  it('labels DEAL_PROMOTED_FROM_QUOTATION as ยืนยันคำสั่งซื้อจากใบเสนอราคา', () => {
    render(
      <DealHistoryPanel
        events={[
          { id: 1, kind: 'DEAL_PROMOTED_FROM_QUOTATION', actorName: 'พนักงานขาย',
            message: 'สร้างดีลจากใบเสนอราคา QT-2026-0005-1', createdAt: '2026-09-30T09:00:00.000Z' },
        ]}
        activities={[]}
      />,
    );

    const item = screen.getByRole('listitem').textContent;
    expect(item).toContain('ยืนยันคำสั่งซื้อจากใบเสนอราคา');
    expect(item).not.toContain('DEAL_PROMOTED_FROM_QUOTATION');
  });

  // QA-29 leftover: kinds TicketEventKind writes that EVENT_KIND_LABEL lacked, so the raw enum showed.
  it.each([
    'CLOSE_CONFIRMED', 'CLOSE_CONFIRM_REVOKED', 'ORDER_CONFIRMED_FROM_QUOTATION',
    'DEAL_QUOTATION_SUBMITTED', 'DEAL_QUOTATION_APPROVED', 'DEAL_QUOTATION_REJECTED',
    'DEAL_QUOTATION_REVISION_SUBMITTED', 'IMPORT_STEP_ADVANCED', 'IMPORT_REQUEST_EMAIL_SENT',
  ])('labels %s in Thai rather than the raw kind', (kind) => {
    render(<DealHistoryPanel events={[{ id: 1, kind, actorName: 'พนักงานขาย', createdAt: '2026-10-30T09:00:00.000Z' }]} activities={[]} />);

    const title = screen.getByRole('listitem').querySelector('strong').textContent;
    expect(title).not.toBe(kind);
    expect(title).toMatch(/[\u0E00-\u0E7F]/);
    expect(screen.getByRole('listitem').textContent).not.toContain(kind);
  });

  // The backend writes "kind=…, amount=…, paid=…, payable=… — note"; old rows keep it, so the panel formats it.
  describe('PAYMENT_RECORDED message', () => {
    const payment = (message) => render(
      <DealHistoryPanel events={[{ id: 1, kind: 'PAYMENT_RECORDED', actorName: 'บัญชี', message, createdAt: '2026-10-30T09:00:00.000Z' }]} activities={[]} />,
    );

    it('BALANCE reads ส่วนที่เหลือ with a formatted amount, keeping the note, and shows no kind=/amount= text', () => {
      payment('kind=BALANCE, amount=440647.94, paid=440647.94, payable=881295.88 — ยืนยันชำระส่วนที่เหลือ');
      const text = screen.getByRole('listitem').textContent;
      expect(text).toContain('ส่วนที่เหลือ');
      expect(text).toContain(formatMoney(440647.94));
      expect(text).toContain('ยืนยันชำระส่วนที่เหลือ');
      expect(text).not.toMatch(/kind=|amount=|paid=|payable=|BALANCE/);
    });

    it.each([
      ['DEPOSIT', 'มัดจำ'],
      ['ADJUSTMENT', 'ปรับปรุงยอด/คืนเงิน'],
    ])('%s reads %s with a formatted amount', (kind, label) => {
      payment(`kind=${kind}, amount=1500.5, paid=1500.5, payable=5000`);
      const text = screen.getByRole('listitem').textContent;
      expect(text).toContain(label);
      expect(text).toContain(formatMoney(1500.5));
      expect(text).not.toMatch(new RegExp(`kind=|amount=|${kind}`));
    });

    it('a message that is not in that format is shown as written', () => {
      payment('รับโอนเพิ่มเติมจากลูกค้า');
      expect(screen.getByRole('listitem').textContent).toContain('รับโอนเพิ่มเติมจากลูกค้า');
    });
  });
});
