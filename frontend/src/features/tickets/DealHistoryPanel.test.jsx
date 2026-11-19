import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DealHistoryPanel } from './DealHistoryPanel.jsx';

globalThis.React = React;

// D5: sales.ticket_event.created_at defaults to Postgres now (transaction_timestamp), so every event written
// in the same transaction — e.g. TicketService.confirmCustomer writes CUSTOMER_CONFIRMED then STAGE_CHANGED in
// one transaction — shares one identical instant.
describe('DealHistoryPanel', () => {
  it('D5 regression: two events sharing one timestamp render newest-first by event id, not ticket_event query order', () => {
    const sameInstant = '2026-07-20T10:00:00.000Z';
    render(
      <DealHistoryPanel
        events={[
          // Sent in the repository's own ORDER BY created_at ASC, event_id ASC — the exact order that used to leak
          // straight through into the rendered list under the old comparator.
          { id: 10, kind: 'CUSTOMER_CONFIRMED', actorName: 'พนักงานขาย', createdAt: sameInstant },
          { id: 11, kind: 'STAGE_CHANGED', actorName: 'พนักงานขาย', createdAt: sameInstant },
        ]}
        activities={[]}
      />,
    );

    const items = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(items).toHaveLength(2);
    // id 11 (STAGE_CHANGED) was written later in the same transaction, so it belongs first in a newest-first list
    // — even though it was passed second.
    expect(items[0]).toContain('เปลี่ยนสถานะงาน');
    expect(items[1]).toContain('ลูกค้ายืนยันคำสั่งซื้อ');
  });
});
