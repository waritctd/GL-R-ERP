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

export const sumValue = (deals) => deals.reduce((sum, d) => sum + Number(d.value ?? 0), 0);
