import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DealHistoryPanel } from './DealHistoryPanel.jsx';

globalThis.React = React;

// Slice 6.5: หน้างาน · นัดเข้าพบที่นัดไว้ในประวัติ (prototype screen D). The page passes the deal's PLANNED visits —
// already narrowed by the server (GET /api/sales/visits/deal/{id}/planned) to the ones this viewer may read; the
// panel never filters by owner itself.
const TODAY = '2026-11-17';
const planned = (over) => ({
  id: 41, ownerId: 6, ownerName: 'คุณสมหมาย ขายดี', ticketId: 7, ticketTitle: 'โรงแรมริมทะเล',
  contactName: 'คุณอรุณ · สตูดิโอเส้นตรง', plannedDate: '2026-11-19', plannedTime: '10:00:00', status: 'PLANNED',
  doneAt: null, note: 'นำตัวอย่างไปเทียบสี', dealMovedToName: null, dealMoved: false, ...over,
});
const events = [{ id: 1, kind: 'QUOTATION_ISSUED', actorName: 'นายสมชาย', createdAt: '2026-11-17T03:00:00.000Z' }];
const activities = [
  { id: 5, kind: 'SITE_VISIT', activityDate: '2026-11-10', createdByName: 'คุณสมหมาย', note: 'ดูหน้างาน' },
];

function renderPanel(props = {}) {
  return render(
    <MemoryRouter>
      <DealHistoryPanel events={events} activities={activities} todayIso={TODAY} {...props} />
    </MemoryRouter>,
  );
}

const rows = () => screen.getAllByRole('listitem');

describe('DealHistoryPanel — planned visits', () => {
  it('lead the history as upcoming rows, above every past event and activity', () => {
    renderPanel({ plannedVisits: [planned(), planned({ id: 42, plannedDate: '2026-11-24', plannedTime: null })] });

    const all = rows();
    expect(all).toHaveLength(4);
    expect(all[0].getAttribute('data-testid')).toBe('timeline-planned-visit');
    expect(all[1].getAttribute('data-testid')).toBe('timeline-planned-visit');
    expect(all[2].textContent).toContain('ออกใบเสนอราคา');
  });

  it('links to the นัดเข้าพบ page only when the viewer can open it', () => {
    const first = renderPanel({ plannedVisits: [planned()], visitsHref: null });
    expect(within(screen.getByTestId('timeline-planned-visit')).queryByRole('link')).toBeNull();
    first.unmount();
    renderPanel({ plannedVisits: [planned()], visitsHref: '/visits' });
    expect(within(screen.getByTestId('timeline-planned-visit')).getByRole('link')).toBeTruthy();
  });
});
