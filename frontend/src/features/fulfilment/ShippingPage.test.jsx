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
  api: { tickets: { list: vi.fn() }, shipmentRounds: { list: vi.fn() }, deliveryTrips: { home: vi.fn() }, myIncoming: { get: vi.fn() }, importJobs: { latestRun: vi.fn() } },
}));

const waitingRound = { roundId: 5, ticketId: 9, roundNo: 1, status: 'INVOICED', displayCode: 'PR-TEST-0009', customerName: 'ลูกค้า ทดสอบ', remaining: 10, state: null };
const home = { today: '2026-11-27', toCheck: [], toInvoice: [], waiting: [waitingRound], pickups: [], trips: [], routes: [] };

// A งานนำเข้า (no sales job): one IR waiting for a round, one already on a round.
const jobLine = { itemId: 9, code: 'S1', brand: null, model: 'Slate', size: '60x60', unit: 'แผ่น', remainingQty: 12 };
const jobWaiting = { importRequestId: 31, docNumber: 'IR-TEST-031', factoryId: 2, factoryName: 'Test Ceramics', ticketId: null, orderPurpose: 'STOCK', lines: [jobLine] };
const jobRound = {
  id: 7, factoryId: 2, factoryName: 'Test Ceramics', source: 'PORTAL', importStep: 'IN_TRANSIT', facts: {}, documents: [],
  lots: [{ ...jobLine, lotId: 1, importRequestId: 32, docNumber: 'IR-TEST-032', ticketId: null, orderPurpose: 'STOCK', qty: 5, itemQty: 5, lotNo: 1, lotCount: 1, remainingQty: 0 }],
};
const importer = { id: 1, role: 'import' };
const planner = { id: 2, role: 'warehouse', canPlanDeliveries: true };
const jobHrefs = () => [...document.querySelectorAll('a')].map((a) => a.getAttribute('href')).filter((h) => h?.startsWith('/fulfilment/jobs/'));

function show(user, { sales = false, shipping = { rounds: [], waiting: [] } } = {}) {
  vi.clearAllMocks();
  vi.mocked(api.shipmentRounds.list).mockResolvedValue(shipping);
  vi.mocked(api.importJobs.latestRun).mockResolvedValue({ run: null });
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

  it('import can still create a งานนำเข้า from ขาเข้า on the merged page', async () => {
    show(importer);
    expect(await screen.findByTestId('import-job-new')).toBeTruthy();
  });

  it('the warehouse planner cannot create a งานนำเข้า', async () => {
    show(planner);
    fireEvent.click(await screen.findByTestId('leg-in'));
    await screen.findByTestId('leg-readonly-note');
    expect(screen.queryByTestId('import-job-new')).toBeNull();
  });

  it('import opens a งานนำเข้า from its IR number, waiting or on a round', async () => {
    show(importer, { shipping: { rounds: [jobRound], waiting: [jobWaiting] } });
    await screen.findAllByText('IR-TEST-032'); // the round queue
    expect(jobHrefs()).toContain('/fulfilment/jobs/32');
    fireEvent.click(screen.getByTestId('waiting-toggle'));
    await screen.findAllByText('IR-TEST-031'); // the waiting list
    expect(jobHrefs()).toContain('/fulfilment/jobs/31');
  });

  it('the warehouse planner reads those IR numbers as plain text: the job page is not theirs to open', async () => {
    show(planner, { shipping: { rounds: [jobRound], waiting: [jobWaiting] } });
    fireEvent.click(await screen.findByTestId('leg-in'));
    await screen.findAllByText('IR-TEST-032');
    expect(jobHrefs()).toEqual([]);
    fireEvent.click(screen.getByTestId('waiting-toggle'));
    await screen.findAllByText('IR-TEST-031');
    expect(jobHrefs()).toEqual([]);
  });

  it('the CEO can create a งานนำเข้า from ขาเข้า on the merged page', async () => {
    show({ id: 4, role: 'ceo' });
    expect(await screen.findByTestId('import-job-new')).toBeTruthy();
  });

  it('a sales rep lands on their own ขาเข้า list and reads ขาออก without any action', async () => {
    show({ id: 3, role: 'sales' }, { sales: true });
    expect(await screen.findByText('ยังไม่มีของที่สั่ง')).toBeTruthy();
    expect(api.shipmentRounds.list).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('leg-out'));
    expect((await screen.findByTestId('leg-readonly-note')).textContent).toContain('แท็บ ส่งของ');
    expect(await screen.findByText('ลูกค้า ทดสอบ')).toBeTruthy();
    expect(screen.queryByRole('checkbox')).toBeNull();
  });
});
