import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
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
});
