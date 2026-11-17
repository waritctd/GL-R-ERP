import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, within } from '@testing-library/react';
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

  it('say นัดไว้, who is being met, when, by whom, and the purpose', () => {
    renderPanel({ plannedVisits: [planned()] });

    const row = screen.getByTestId('timeline-planned-visit');
    expect(within(row).getByText('นัดไว้')).toBeTruthy();
    expect(within(row).getByText('นัดเข้าพบ · คุณอรุณ · สตูดิโอเส้นตรง')).toBeTruthy();
    expect(row.textContent).toContain('พฤ. 19 พ.ย. · 10:00');
    expect(row.textContent).toContain('คุณสมหมาย ขายดี');
    expect(within(row).getByText('นำตัวอย่างไปเทียบสี')).toBeTruthy();
  });

  it('an untimed visit shows the day only', () => {
    renderPanel({ plannedVisits: [planned({ plannedTime: null, plannedDate: '2026-11-24' })] });
    const row = screen.getByTestId('timeline-planned-visit');
    expect(row.textContent).toContain('อ. 24 พ.ย.');
    expect(row.textContent).not.toMatch(/\d\d:\d\d/);
  });

  it('a planned visit whose day has passed without a tick says เลยวันนัด, not นัดไว้', () => {
    renderPanel({ plannedVisits: [planned({ plannedDate: '2026-11-16' })] });
    const row = screen.getByTestId('timeline-planned-visit');
    expect(within(row).getByText('เลยวันนัด')).toBeTruthy();
    expect(within(row).queryByText('นัดไว้')).toBeNull();
  });

  it('stay under ทั้งหมด and เข้าพบ, and leave under เอกสาร', () => {
    renderPanel({ plannedVisits: [planned()] });

    fireEvent.click(screen.getByRole('button', { name: 'เข้าพบ' }));
    expect(screen.getByTestId('timeline-planned-visit')).toBeTruthy();
    expect(rows()).toHaveLength(2);

    fireEvent.click(screen.getByRole('button', { name: 'เอกสาร' }));
    expect(screen.queryByTestId('timeline-planned-visit')).toBeNull();
    expect(rows()).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: 'ทั้งหมด' }));
    expect(screen.getByTestId('timeline-planned-visit')).toBeTruthy();
  });

  it('each row links to the นัดเข้าพบ page when the viewer may open it', () => {
    renderPanel({ plannedVisits: [planned()], visitsHref: '/visits' });
    const link = within(screen.getByTestId('timeline-planned-visit')).getByRole('link');
    expect(link.getAttribute('href')).toBe('/visits');
    expect(link.textContent).toContain('นัดเข้าพบ · คุณอรุณ');
  });

  it('no link when the viewer cannot open the นัดเข้าพบ page', () => {
    renderPanel({ plannedVisits: [planned()], visitsHref: null });
    expect(within(screen.getByTestId('timeline-planned-visit')).queryByRole('link')).toBeNull();
  });

  it('a deal with only a planned visit does not claim the history is empty', () => {
    renderPanel({ events: [], activities: [], plannedVisits: [planned()] });
    expect(screen.queryByText('ยังไม่มีประวัติ')).toBeNull();
    expect(screen.getByTestId('timeline-planned-visit')).toBeTruthy();
  });

  it('with no planned visits the history is exactly as before', () => {
    renderPanel({ plannedVisits: [] });
    expect(screen.queryByTestId('timeline-planned-visit')).toBeNull();
    expect(rows()).toHaveLength(2);
  });
});
