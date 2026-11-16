const isNum = (v) => typeof v === 'number' && Number.isFinite(v);

/** 1.2 ล้าน — the snapshot's headline unit; "—" for a missing figure. */
export const ml = (n) => (isNum(Number(n)) && n != null ? `${(Number(n) / 1e6).toFixed(1)} ล้าน` : '—');
export const pct = (v) => (isNum(Number(v)) && v != null ? `${Math.round(Number(v) * 100)}%` : '—');
/** Signed percent for a month-over-month style delta; null means "no comparison month". */
export const signed = (v) => (isNum(v) ? `${v > 0 ? '+' : ''}${v}%` : '—');

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
/** Bar width 0–100: 0 for nothing (or an undefined ratio), at least 2 for something so it stays visible. */
export function barWidth(part, whole) {
  const r = ratio(part, whole);
  if (r == null || r <= 0) return 0;
  return Math.min(100, Math.max(2, r * 100));
}

/** A snapshot the page can render: months and the two month series agree, every month has a numeric total and reps. */
export function isUsable(K) {
  if (!K || typeof K !== 'object' || !Array.isArray(K.months) || K.months.length === 0) return false;
  if (!Array.isArray(K.monthly) || !Array.isArray(K.monthly_total) || !Array.isArray(K.reps)) return false;
  if (K.monthly.length !== K.months.length || K.monthly_total.length !== K.months.length) return false;
  return K.monthly.every((m) => m && typeof m === 'object' && isNum(m.total) && Array.isArray(m.reps))
    && K.monthly_total.every(isNum);
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

/** Tone class of the finding dot, from the project's colour tokens. */
const DOT = { bad: 'bg-danger', warn: 'bg-warning', info: 'bg-text-muted' };

/** [dot class, headline, detail] rows — each built only from the figures the snapshot actually carries. */
export function buildFindings(K) {
  const out = [];
  const q = K.quarter;
  if (obj(q)) {
    out.push([DOT.bad, `${q.label} รับเงิน ${ml(q.cur)} ${moreLess(q.chg, 'สูงกว่า', 'ต่ำกว่า')}${q.prev_label} ${Math.abs(q.chg)}%`, `${q.prev_label} รับ ${ml(q.prev)}`]);
  }
  if (K.reps.length >= 2) {
    const [a, b] = K.reps.slice().sort((x, y) => y.rev - x.rev);
    out.push([DOT.warn, `${a.rep} และ ${b.rep} ทำ ${(a.share + b.share).toFixed(1)}% ของรายรับ 12 เดือน`,
      `${a.rep} ${ml(a.rev)} · ${b.rep} ${ml(b.rev)}${K.t12_total ? ` จากทั้งหมด ${ml(K.t12_total)}` : ''}`]);
  }
  if (obj(K.pipe)) {
    out.push([DOT.bad, `งานคาดหวัง ${ml(K.pipe.value)} แต่ ${K.pipe.top10}% อยู่ใน 10 งาน`,
      `งานใหญ่สุดงานเดียว ${K.pipe.largest}%${obj(K.pipe.fill) ? ` · กรอก "จะขายได้ %" แค่ ${K.pipe.fill.prob}% ของงาน` : ''}`]);
  }
  if (obj(K.fc)) {
    out.push([DOT.warn, `ประมาณการต้นเดือนคลาดเคลื่อนกลาง ๆ ${K.fc.mae}%`, `รับจริงต่อที่ประมาณ: ${repPct(K.fc.by_rep)}`]);
  }
  if (obj(K.comm) && ratio(K.comm.paid, K.comm.formula) != null) {
    const over = Math.round((ratio(K.comm.paid, K.comm.formula) - 1) * 100);
    out.push([DOT.warn, `ค่าคอมจ่ายจริง ${ml(K.comm.paid)} ${moreLess(-over, 'สูงกว่า', 'ต่ำกว่า')}สูตรขั้นบันได ${Math.abs(over)}%`,
      `${K.comm.corr} จาก ${K.comm.n} รายการต้องแจ้งแก้ไข`]);
  }
  if (obj(K.cust)) {
    const b = Array.isArray(K.cust.buckets) ? K.cust.buckets : [];
    const small = (b[0]?.cnt ?? 0) + (b[1]?.cnt ?? 0);
    const smallV = (b[0]?.val ?? 0) + (b[1]?.val ?? 0);
    out.push([DOT.info, `ลูกค้า ${K.cust.n80} จาก ${K.cust.n} รายให้รายรับ 80%`,
      `ใบเสร็จต่ำกว่า 20,000 บาทมี ${small.toFixed(1)}% ของจำนวน แต่ให้ยอด ${smallV.toFixed(1)}%`]);
  }
  return out;
}

export function buildRecommendations(K) {
  const out = [];
  if (obj(K.pipe) && obj(K.pipe.fill)) {
    out.push(['ให้ "จะขายได้ %", ไตรมาส และขั้นงานขาย เป็นช่องบังคับในดีล',
      `ตอนนี้กรอก ${K.pipe.fill.prob}% / ${K.pipe.fill.q}% / ${K.pipe.fill.stage}% จึงถ่วงน้ำหนักยอดคาดหวังไม่ได้`]);
  }
  if (obj(K.pipe)) out.push(['ทบทวน 10 งานใหญ่ทุกสัปดาห์', `10 งานนี้คือ ${K.pipe.top10}% ของงานคาดหวังทั้งหมด`]);
  if (obj(K.fc) && obj(K.fc.by_rep)) out.push(['ปรับฐานประมาณการรายคนตามยอดรับจริง', repPct(K.fc.by_rep)]);
  if (obj(K.comm) && ratio(K.comm.corr, K.comm.n) != null) {
    out.push(['คำนวณค่าคอมใน Portal เพื่อลดการแจ้งแก้ไข', `${pctOf(K.comm.corr, K.comm.n)} ของรายการค่าคอม 12 เดือนมีการแก้ไข`]);
  }
  return out;
}
