import { describe, it, expect, beforeEach } from 'vitest';
import { api } from './mockApi.js';

// Guards `specialMoney.list()` against SpecialMoneyService.list() + SpecialMoneyRepository
// .findRequests(). Three things that mock diverged on, all in the direction that hides bugs:
//
//   1. The date window is NOT optional. Omitting from/to does not mean "everything"; the service
//      computes `effectiveTo = to ?? today(Asia/Bangkok)` and `effectiveFrom = from ?? first of
//      that month`, and the repository filters `event_date BETWEEN`. The mock applied no window at
//      all, so it returned MORE rows than production -- a screen depending on the scoping looked
//      right in mock mode and came up empty in prod.
//   2. Ordering. `contract.test.js` compares parameter COUNTS only and cannot see an ORDER BY.
//      Same rows in a different order is the mechanism behind #434.
//   3. `to` before `from` is a 400 from the service, before the repository is reached.
//
// These call the mock through its public surface rather than reaching into `db`, so they also fail
// if the seed's shape changes underneath them.

const THIS_YEAR = new Date().getFullYear();

async function listAll(params) {
  const { requests } = await api.specialMoney.list(params);
  return requests;
}

describe('mockApi specialMoney.list — window, ordering, validation', () => {
  beforeEach(async () => {
    await api.auth.login({ role: 'ceo' });
  });

  it('defaults to the current calendar month rather than returning everything', async () => {
    // A window wide enough to contain the whole seed. If this is empty the assertions below are
    // vacuous -- there would be nothing for the default window to exclude.
    const everything = await listAll({ from: '2000-01-01', to: '2999-12-31' });
    expect(everything.length).toBeGreaterThan(0);

    const defaulted = await listAll({});
    const bangkokToday = new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Asia/Bangkok', year: 'numeric', month: '2-digit', day: '2-digit',
    }).format(new Date());
    const monthPrefix = bangkokToday.slice(0, 7);

    // Every row that comes back is inside this month...
    defaulted.forEach((request) => {
      expect(request.eventDate >= `${monthPrefix}-01`).toBe(true);
      expect(request.eventDate <= bangkokToday).toBe(true);
    });
    // ...and rows outside it were genuinely dropped, which is the whole point.
    expect(defaulted.length).toBeLessThan(everything.length);
  });

  it('honours an explicit window, which is how a caller opts out of the month default', async () => {
    const wide = await listAll({ from: `${THIS_YEAR}-01-01`, to: `${THIS_YEAR}-12-31` });
    const narrow = await listAll({ from: `${THIS_YEAR}-06-01`, to: `${THIS_YEAR}-06-30` });

    expect(wide.length).toBeGreaterThan(narrow.length);
    narrow.forEach((request) => {
      expect(request.eventDate >= `${THIS_YEAR}-06-01`).toBe(true);
      expect(request.eventDate <= `${THIS_YEAR}-06-30`).toBe(true);
    });
  });

  it('rejects a window that ends before it starts, as the service does before querying', async () => {
    await expect(api.specialMoney.list({ from: '2026-07-01', to: '2026-06-01' }))
      .rejects.toThrow('วันที่สิ้นสุดต้องไม่มาก่อนวันที่เริ่มต้น');
  });

  it('orders by event_date DESC, mirroring findRequests', async () => {
    const requests = await listAll({ from: '2000-01-01', to: '2999-12-31' });
    // Needs at least two DIFFERENT dates or "sorted" is trivially true.
    const distinctDates = new Set(requests.map((request) => request.eventDate));
    expect(distinctDates.size).toBeGreaterThan(1);

    const dates = requests.map((request) => request.eventDate);
    expect(dates).toEqual([...dates].sort().reverse());
  });

  it('still applies the status filter inside the window', async () => {
    const window = { from: '2000-01-01', to: '2999-12-31' };
    const all = await listAll(window);
    const submitted = await listAll({ ...window, status: 'SUBMITTED' });

    expect(submitted.length).toBeGreaterThan(0);
    expect(submitted.length).toBeLessThan(all.length);
    submitted.forEach((request) => expect(request.status).toBe('SUBMITTED'));
  });
});
