// Synthetic sales-analytics snapshot (the PUT /api/sales-analytics/snapshot document). Every name and number is
// made up — the repo is public and the real snapshot holds customers and internal figures. Shape mirrors what
// SalesInsights reads; months 0..2 are distinguishable by their top customer and by their revenue.
const rep = (name, rev) => ({
  rep: name, rev, share: 50, cv: 0.2, max: rev, maxm: 'ก.พ. 69', med_ticket: 1000, comm: 1000, rate: 1, formula: 900,
  smooth: 800, corr: 0, n: 3, calcdiff: 0, q_prev: 100, q_cur: 90, qchg: -10, corr_pct: 0,
});
const buckets = [{ b: '<20k', cnt: 40, val: 5 }, { b: '20-50k', cnt: 30, val: 15 }, { b: '50-100k', cnt: 20, val: 30 },
  { b: '100-500k', cnt: 8, val: 30 }, { b: '>500k', cnt: 2, val: 20 }];
const monthDetail = (total, topCustomer) => ({
  total, mom: null, yoy: null, comm: 1000, corr: 0, cust: 2, lines: 4, med: 500, top1: 50, top5: 100, big: 0, buckets,
  reps: [{ rep: 'Rep A', rev: total * 0.6, comm: 600, corr: 0 }, { rep: 'Rep B', rev: total * 0.4, comm: 400, corr: 0 }],
  fc: [{ rep: 'Rep A', fc: total, act: total * 0.6, ratio: 0.6, xl: false }],
  topc: [{ name: topCustomer, amt: total / 2, n: 2, reps: 'Rep A', share: 50 }],
});

export const SNAPSHOT = {
  months: ['ม.ค. 69', 'ก.พ. 69', 'มี.ค. 69'],
  monthly_total: [1000000, 2000000, 1500000],
  monthly_by_rep: { 'Rep A': [600000, 1200000, 900000], 'Rep B': [400000, 800000, 600000] },
  t12_total: 4500000,
  t12_range: ['ม.ค. 69', 'มี.ค. 69'],
  quarter: { label: 'ไตรมาส 1/70', prev_label: 'ไตรมาส 1/69', cur: 1000000, prev: 1100000, chg: -9.1 },
  reps: [rep('Rep A', 2700000), rep('Rep B', 1800000)],
  comm: { paid: 45000, formula: 40000, smooth: 38000, rate: 1, corr: 1, n: 10 },
  cust: { n: 20, top10: 60, n80: 6, repeat_n: 8, repeat_share: 70, lines: 100, med: 5000, buckets },
  fc: { rows: [], overall: 0.9, mae: 15, by_rep: { 'Rep A': 0.8, 'Rep B': 1.1 } },
  coverage: { 'Rep A': { weeks: 10, of: 12 } },
  pipe: {
    value: 9000000, n: 12, coverage: 2, fill: { prob: 50, q: 40, stage: 60, designer: 30 }, largest: 20, top10: 80, buckets,
    by_rep: [{ rep: 'Rep A', exp: 5000000, n: 7, ordered: 1000000, lost: 0, lost_n: 0, cover: 1.5 }],
    stages: [], over1m: {},
  },
  designers: [{ name: 'บริษัทออกแบบ ก', reps: 'Rep A', ordered: 1000, expected: 2000, n: 2, visits: 3 }],
  designer_meta: { top5: 70, n: 9 },
  time: { 'Rep A': { visit: 5, logistics: 3, internal: 2 } },
  plan: { 'Rep A': { plan: 4, actual: 3 } },
  recon: { date: '2026-03-31' },
  dups: { 'Rep A': 1 },
  monthly: [monthDetail(1000000, 'ลูกค้า ก'), monthDetail(2000000, 'ลูกค้า ข'), monthDetail(1500000, 'ลูกค้า ค')],
  top_customers: [{ name: 'ลูกค้า ก', amt: 500000, n: 2, reps: 'Rep A', share: 11 }],
  fc_stats: {}, pipe_quality: [], top_deals: [],
};

// The optional month in progress: rep B has not reported a received figure yet (recv null).
export const SNAPSHOT_WITH_NOW = {
  ...SNAPSHOT,
  now: { label: 'เม.ย. 69', asof: '15 เม.ย. 69', rows: [{ rep: 'Rep A', fc: 1000000, recv: 400000 }, { rep: 'Rep B', fc: 500000, recv: null }] },
};

// Only the four required keys + months detail: every optional section is absent.
export const SNAPSHOT_MINIMAL = {
  months: SNAPSHOT.months, monthly_total: SNAPSHOT.monthly_total, monthly: SNAPSHOT.monthly, reps: SNAPSHOT.reps,
};

// Required keys only inside every optional section: each section object is present but carries none of its numbers,
// so the page must print "—" (or omit the sentence), never "undefined" / "NaN".
export const SNAPSHOT_SPARSE = {
  months: ['ม.ค. 69', 'ก.พ. 69'],
  monthly_total: [100, 200],
  monthly: [{ total: 100, reps: [{ rep: 'Rep A' }] }, { total: 200, reps: [{ rep: 'Rep A' }], fc: [{ rep: 'Rep A' }], topc: [{}] }],
  reps: [{ rep: 'Rep A' }, { rep: 'Rep B' }],
  quarter: {}, comm: {}, cust: { buckets: [{}, {}] }, pipe: { by_rep: [{ rep: 'Rep A' }], fill: {}, buckets: [{}] },
  fc: {}, designers: [{}], designer_meta: {}, time: { 'Rep A': {} }, coverage: { 'Rep A': {} },
  fc_stats: [{ rep: 'Rep A' }], pipe_quality: [{ rep: 'Rep A' }], top_deals: [{}], top_customers: [{}],
  now: { rows: [{ rep: 'Rep A' }] },
};
