import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ShippingPage } from './ShippingPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => ({
  ...(await importOriginal()),
  api: { tickets: { list: vi.fn() }, shipmentRounds: { list: vi.fn() }, deliveryTrips: { home: vi.fn() }, myIncoming: { get: vi.fn() } },
}));

const waitingRound = { roundId: 5, ticketId: 9, roundNo: 1, status: 'INVOICED', displayCode: 'PR-TEST-0009', customerName: 'ลูกค้า ทดสอบ', remaining: 10, state: null };
const home = { today: '2026-11-27', toCheck: [], toInvoice: [], waiting: [waitingRound], pickups: [], trips: [], routes: [] };

function show(user, sales = false) {
  vi.clearAllMocks();
  vi.mocked(api.shipmentRounds.list).mockResolvedValue({ rounds: [], waiting: [] });
  vi.mocked(api.tickets.list).mockResolvedValue({ tickets: [] });
  vi.mocked(api.deliveryTrips.home).mockResolvedValue(home);
  vi.mocked(api.myIncoming.get).mockResolvedValue({ rows: [] });
  render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><ShippingPage user={user} showToast={() => {}} sales={sales} /></MemoryRouter></QueryClientProvider>);
}

describe('ShippingPage', () => {
  it('import gets its rounds page with no ขาออก leg', async () => {
    show({ id: 1, role: 'import' });
    expect((await screen.findAllByText('ทั้งหมด')).length).toBeGreaterThan(0);
    expect(screen.queryByTestId('leg-out')).toBeNull();
    expect(api.deliveryTrips.home).not.toHaveBeenCalled();
  });

  it('the warehouse planner lands on ขาออก with checkboxes, and sees ขาเข้า read-only', async () => {
    show({ id: 2, role: 'warehouse', canPlanDeliveries: true });
    expect((await screen.findAllByRole('checkbox')).length).toBe(1);
    expect(screen.queryByTestId('leg-readonly-note')).toBeNull();
    fireEvent.click(screen.getByTestId('leg-in'));
    expect((await screen.findByTestId('leg-readonly-note')).textContent).toContain('ขาเข้าดูได้อย่างเดียว');
    expect(screen.queryByTestId('plan-round')).toBeNull();
    expect(api.tickets.list).not.toHaveBeenCalled();
  });

  it('a sales rep lands on their own ขาเข้า list and reads ขาออก without any action', async () => {
    show({ id: 3, role: 'sales' }, true);
    expect(await screen.findByText('ยังไม่มีของที่สั่ง')).toBeTruthy();
    expect(api.shipmentRounds.list).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('leg-out'));
    expect((await screen.findByTestId('leg-readonly-note')).textContent).toContain('แท็บ ส่งของ');
    expect(await screen.findByText('ลูกค้า ทดสอบ')).toBeTruthy();
    expect(screen.queryByRole('checkbox')).toBeNull();
  });
});
