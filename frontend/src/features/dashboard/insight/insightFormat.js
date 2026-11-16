export const isNum = (v) => typeof v === 'number' && Number.isFinite(v);

/** 1.2 ล้าน — the snapshot's headline unit; "—" for a missing figure. */
/** A number, or a non-blank numeric string; '' / ' ' / text / null are "missing", never 0. */
const numeric = (v) => (typeof v === 'number' || (typeof v === 'string' && v.trim() !== '')) && isNum(Number(v));
export const ml = (n) => (numeric(n) ? `${(Number(n) / 1e6).toFixed(1)} ล้าน` : '—');
export const pct = (v) => (numeric(v) ? `${Math.round(Number(v) * 100)}%` : '—');
/** Signed percent for a month-over-month style delta; null means "no comparison month". */
export const signed = (v) => (isNum(v) ? `${v > 0 ? '+' : ''}${v}%` : '—');

/** An optional number with its unit suffix, or "—" when the snapshot did not carry it (never "undefined" / "NaN"). */
export const num = (v, suffix = '') => (isNum(v) ? `${v}${suffix}` : '—');
/** The "from – to" months of the 12-month window, '' when absent or not a list. */
export const rangeText = (K) => (Array.isArray(K.t12_range) ? K.t12_range.join(' – ') : '');
/** `K.quarter` only when it can be printed whole (labels and a numeric change); the quarter UI is omitted otherwise. */
export const quarterOf = (K) => {
  const q = K.quarter;
  return q && typeof q === 'object' && q.label && q.prev_label && isNum(q.chg) ? q : null;
};

/** The one ratio guard: part/whole as a number, or null when the whole is 0 / not a number. */
export function ratio(part, whole) {
  const a = Number(part);
  const b = Number(whole);
  return Number.isFinite(a) && Number.isFinite(b) && b !== 0 ? a / b : null;
}
/** part/whole as a percent string with `digits` decimals ("—" when undefined). */
export function pctOf(part, whole, digits = 0) {
  const r = ratio(part, whole);
  return r == null ? '—' : `${(r * 100).toFixed(digits)}%`;
}
/** Bar width 0–100: 0 for nothing (or an undefined ratio), at least `min` (default 2) for something so it stays visible. */
export function barWidth(part, whole, min = 2) {
  const r = ratio(part, whole);
  if (r == null || r <= 0) return 0;
  return Math.min(100, Math.max(min, r * 100));
}

/** A snapshot the page can render: months and the two month series agree, every month has a numeric total and reps. */
export function isUsable(K) {
  if (!K || typeof K !== 'object' || !Array.isArray(K.months) || K.months.length === 0) return false;
  if (!Array.isArray(K.monthly) || !Array.isArray(K.monthly_total) || !Array.isArray(K.reps)) return false;
  if (K.monthly.length !== K.months.length || K.monthly_total.length !== K.months.length) return false;
  const repRows = (rows) => rows.every((r) => r && typeof r === 'object' && typeof r.rep === 'string');
  return K.monthly.every((m) => m && typeof m === 'object' && isNum(m.total) && Array.isArray(m.reps) && repRows(m.reps))
    && K.monthly_total.every(isNum) && repRows(K.reps);
}

/** Section keys in display order per role: the CEO reads sales first, a sales manager reads the pipeline first. */
export const SECTION_ORDER = {
  ceo: ['sales', 'comm', 'cust', 'pipe', 'des'],
  sales_manager: ['pipe', 'des', 'sales', 'cust', 'comm'],
};
export const SECTION_LABEL = {
  sales: 'ยอดขาย',
  comm: 'ค่าคอมมิชชัน',
  cust: 'ลูกค้า',
  pipe: 'งานในมือและประมาณการ',
  des: 'ผู้ออกแบบและการใช้เวลา',
};

const obj = (v) => v && typeof v === 'object' && !Array.isArray(v);

/** Which sections the snapshot has data for (a section whose source keys are absent is omitted, not blank). */
export function availableSections(K) {
  return {
    sales: K.reps.length > 0,
    comm: obj(K.comm),
    cust: obj(K.cust),
    pipe: obj(K.pipe),
    des: Array.isArray(K.designers) || obj(K.time),
  };
}

const repPct = (map) => Object.entries(obj(map) ? map : {}).map(([rep, v]) => `${rep} ${pct(v)}`).join(' · ');
const moreLess = (chg, more, less) => (chg < 0 ? less : more);
const LOW_FILL = 80;
// Only the three rates the recommendation names decide it.
const lowFill = (fill) => obj(fill) && [fill.prob, fill.q, fill.stage].some((v) => isNum(v) && v < LOW_FILL);
const offTarget = (map) => obj(map) && Object.values(map).some((v) => isNum(v) && (v < 0.85 || v > 1.15));

/** Tone class of the finding dot, from the project's colour tokens. */
const DOT = { bad: 'bg-danger', warn: 'bg-warning', info: 'bg-text-muted' };

/** [dot class, headline, detail] rows — each built only from the figures the snapshot actually carries. */
export function buildFindings(K) {
  const out = [];
  const q = quarterOf(K);
  if (q) {
    out.push([q.chg < 0 ? DOT.bad : DOT.info, `${q.label} รับเงิน ${ml(q.cur)} ${moreLess(q.chg, 'สูงกว่า', 'ต่ำกว่า')}${q.prev_label} ${Math.abs(q.chg)}%`, `${q.prev_label} รับ ${ml(q.prev)}`]);
  }
  if (K.reps.length >= 2) {
    const [a, b] = K.reps.slice().sort((x, y) => y.rev - x.rev);
    if (isNum(a.share) && isNum(b.share)) {
      out.push([DOT.warn, `${a.rep} และ ${b.rep} ทำ ${(a.share + b.share).toFixed(1)}% ของรายรับ 12 เดือน`,
        `${a.rep} ${ml(a.rev)} · ${b.rep} ${ml(b.rev)}${K.t12_total ? ` จากทั้งหมด ${ml(K.t12_total)}` : ''}`]);
    }
  }
  const p = K.pipe;
  if (obj(p) && isNum(p.top10)) {
    const prob = obj(p.fill) && isNum(p.fill.prob) ? p.fill.prob : null;
    const heavy = p.top10 >= 50;
    out.push([heavy ? DOT.bad : DOT.info, `งานคาดหวัง ${ml(p.value)}${heavy ? ' แต่' : ' โดย'} ${p.top10}% อยู่ใน 10 งาน`,
      `งานใหญ่สุดงานเดียว ${num(p.largest, '%')}${prob != null ? ` · กรอก "จะขายได้ %"${prob < LOW_FILL ? ' แค่' : ''} ${prob}% ของงาน` : ''}`]);
  }
  if (obj(K.fc) && isNum(K.fc.mae)) {
    const byRep = repPct(K.fc.by_rep);
    out.push([DOT.warn, `ประมาณการต้นเดือนคลาดเคลื่อนกลาง ๆ ${K.fc.mae}%`, byRep ? `รับจริงต่อที่ประมาณ: ${byRep}` : '']);
  }
  if (obj(K.comm) && ratio(K.comm.paid, K.comm.formula) != null) {
    const over = Math.round((ratio(K.comm.paid, K.comm.formula) - 1) * 100);
    out.push([K.comm.paid > K.comm.formula ? DOT.warn : DOT.info, `ค่าคอมจ่ายจริง ${ml(K.comm.paid)} ${moreLess(over, 'สูงกว่า', 'ต่ำกว่า')}สูตรขั้นบันได ${Math.abs(over)}%`,
      `${num(K.comm.corr)} จาก ${num(K.comm.n)} รายการต้องแจ้งแก้ไข`]);
  }
  const b = obj(K.cust) && Array.isArray(K.cust.buckets) ? K.cust.buckets : [];
  if (b.length >= 2 && [b[0], b[1]].every((x) => obj(x) && x.b && isNum(x.cnt) && isNum(x.val)) && isNum(K.cust.n80) && isNum(K.cust.n)) {
    out.push([DOT.info, `ลูกค้า ${K.cust.n80} จาก ${K.cust.n} รายให้รายรับ 80%`,
      `ใบเสร็จ ${b[0].b} และ ${b[1].b} มี ${(b[0].cnt + b[1].cnt).toFixed(1)}% ของจำนวน แต่ให้ยอด ${(b[0].val + b[1].val).toFixed(1)}%`]);
  }
  return out;
}

export function buildRecommendations(K) {
  const out = [];
  if (obj(K.pipe) && lowFill(K.pipe.fill)) {
    const f = K.pipe.fill;
    out.push(['ให้ "จะขายได้ %", ไตรมาส และขั้นงานขาย เป็นช่องบังคับในดีล',
      `ตอนนี้กรอก ${num(f.prob, '%')} / ${num(f.q, '%')} / ${num(f.stage, '%')} จึงถ่วงน้ำหนักยอดคาดหวังไม่ได้`]);
  }
  if (obj(K.pipe) && isNum(K.pipe.top10)) out.push(['ทบทวน 10 งานใหญ่ทุกสัปดาห์', `10 งานนี้คือ ${K.pipe.top10}% ของงานคาดหวังทั้งหมด`]);
  if (obj(K.fc) && offTarget(K.fc.by_rep)) out.push(['ปรับฐานประมาณการรายคนตามยอดรับจริง', repPct(K.fc.by_rep)]);
  if (obj(K.comm) && ratio(K.comm.corr, K.comm.n) != null) {
    out.push(['คำนวณค่าคอมใน Portal เพื่อลดการแจ้งแก้ไข', `${pctOf(K.comm.corr, K.comm.n)} ของรายการค่าคอม 12 เดือนมีการแก้ไข`]);
  }
  return out;
}
