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

  // Slice 5 review M4: an undo is logged as IMPORT_STEP_ADVANCED with a "เลิกทำ" message (ImportRequestService#undoStep),
  // so the history must not call it an advance.
  it('an IMPORT_STEP_ADVANCED whose message is an undo reads ฝ่ายนำเข้าเลิกทำการเลื่อนขั้นตอน; a real advance keeps its label', () => {
    render(<DealHistoryPanel events={[
      { id: 2, kind: 'IMPORT_STEP_ADVANCED', actorName: 'นำเข้า', createdAt: '2026-11-10T09:01:00.000Z', message: 'โรงงาน Mutina: เลิกทำ สั่งซื้อแล้ว → ติดต่อโรงงาน' },
      { id: 1, kind: 'IMPORT_STEP_ADVANCED', actorName: 'นำเข้า', createdAt: '2026-11-10T09:00:00.000Z', message: 'โรงงาน Mutina: ติดต่อโรงงาน → สั่งซื้อแล้ว' },
    ]} activities={[]} />);
    const [undo, advance] = screen.getAllByRole('listitem').map((li) => li.querySelector('strong').textContent);
    expect(undo).toBe('ฝ่ายนำเข้าเลิกทำการเลื่อนขั้นตอน');
    expect(advance).toBe('ฝ่ายนำเข้าเลื่อนขั้นตอน');
  });

  // TicketService.cancel / markLost write "ยกเลิกดีล (CODE) — note" / "เสียงาน (CODE) — note". The reason code is the
  // only place a cancelled deal's reason is displayed, so it reads as its label — the drawn new codes AND the three
  // retired on 2026-11-16 that already-cancelled deals carry. An unknown code is shown as written.
  describe('cancel / lost reason in the message', () => {
    const one = (kind, message) => render(
      <DealHistoryPanel events={[{ id: 1, kind, actorName: 'พนักงานขาย', message, createdAt: '2026-11-16T09:00:00.000Z' }]} activities={[]} />,
    );

    it('a MARKED_LOST message reads its F-code and label', () => {
      one('MARKED_LOST', 'เสียงาน (PRICE) — คู่แข่งถูกกว่า');
      expect(screen.getByRole('listitem').textContent).toContain('F2 ราคา — คู่แข่งถูกกว่า');
    });
  });
});
