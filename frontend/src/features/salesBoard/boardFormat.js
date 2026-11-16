// Display helpers shared by งานของฉัน and ภาพรวมทีมขาย. Labels only — every number is served by SalesBoardService.

/** The five board columns (DealStage.boardColumnOf), with the reps' own S-codes. */
export const BOARD_COLUMNS = [
  { column: 1, label: 'เสนอสินค้า', code: 'S1', auto: false },
  { column: 2, label: 'สเปคผ่าน', code: 'S2', auto: false },
  { column: 3, label: 'เสนอราคาแล้ว', code: 'S3–5', auto: true },
  { column: 4, label: 'รอมัดจำ', code: 'S6', auto: true },
  { column: 5, label: 'ส่งของ · เก็บเงิน', code: 'S7–9', auto: true },
];

export const columnLabel = (column) => BOARD_COLUMNS.find((c) => c.column === column)?.label ?? '';

const BAHT = new Intl.NumberFormat('th-TH', { maximumFractionDigits: 0 });

/** ฿ with th-TH grouping, whole baht. Null stays a dash: no quotation yet means no value, not ฿0. */
export function baht(value) {
  if (value === null || value === undefined || value === '') return '—';
  const amount = Number(value);
  return Number.isFinite(amount) ? `฿${BAHT.format(amount)}` : '—';
}

/** "2026-Q4" → "Q4/69" — the reps' workbook style (Buddhist-era year, two digits). */
export function quarterLabel(expectedQuarter) {
  const match = /^(\d{4})-Q([1-4])$/.exec(expectedQuarter ?? '');
  if (!match) return null;
  return `Q${match[2]}/${String(Number(match[1]) + 543).slice(-2)}`;
}

/** วันนี้ / เมื่อวาน / n วันก่อน, from an ISO instant. */
export function sinceLabel(iso, now = new Date()) {
  if (!iso) return '—';
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return '—';
  const day = (d) => d.toLocaleDateString('sv-SE', { timeZone: 'Asia/Bangkok' });
  const days = Math.round((new Date(day(now)) - new Date(day(then))) / 86_400_000);
  if (days <= 0) return 'วันนี้';
  if (days === 1) return 'เมื่อวาน';
  return `${days} วันก่อน`;
}

const isThb = (currency) => !currency || currency === 'THB';

/** A deal value in its own currency: ฿ for THB, otherwise the ISO code ("USD 1,000"). */
export function money(value, currency) {
  if (isThb(currency)) return baht(value);
  if (value === null || value === undefined || value === '') return '—';
  const amount = Number(value);
  return Number.isFinite(amount) ? `${currency} ${BAHT.format(amount)}` : '—';
}

/** The THB sum only — another currency is never added into a ฿ figure (review M1). */
export const sumValue = (deals) => deals.reduce((sum, d) => (isThb(d.currency) ? sum + Number(d.value ?? 0) : sum), 0);

/** Non-THB values summed per currency, e.g. { USD: 1000 }. */
export function otherCurrencyTotals(deals) {
  const totals = {};
  deals.forEach((d) => {
    if (!isThb(d.currency) && d.value != null) totals[d.currency] = (totals[d.currency] ?? 0) + Number(d.value);
  });
  return totals;
}

/** "฿2,000 + USD 1,000": the ฿ total, then each other currency on its own. `totals` maps code → amount. */
export function totalLabel(thb, totals = {}) {
  return [baht(thb), ...Object.keys(totals).sort().map((code) => `+ ${money(totals[code], code)}`)].join(' ');
}

/** "2026-Q4" for today in Asia/Bangkok — what the board's ไตรมาสนี้ filter keeps. */
export function currentQuarter(now = new Date()) {
  const [year, month] = now.toLocaleDateString('sv-SE', { timeZone: 'Asia/Bangkok' }).split('-').map(Number);
  return `${year}-Q${Math.floor((month - 1) / 3) + 1}`;
}

/**
 * The board's first filter (round-2 LOW 1): ไตรมาสนี้ only once at least half of the rep's open deals carry an expected
 * quarter — before that most deals have none, and ไตรมาสนี้ would hide most of the board. A rep's own click wins.
 */
export function defaultBoardFilter(openDeals) {
  const deals = openDeals ?? [];
  const withQuarter = deals.filter((d) => d.expectedQuarter).length;
  return deals.length > 0 && withQuarter * 2 >= deals.length ? 'quarter' : 'all';
}

/** The board's owner tag (sales_manager / ceo): the rep's first name, as the review draws it; the card keeps the full
 * name in the tag's title. */
export const ownerShortName = (name) => (name ?? '').trim().split(/\s+/)[0] ?? '';
