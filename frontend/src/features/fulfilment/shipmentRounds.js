import { formatThaiDate } from '../../utils/format.js';
import { IMPORT_STEPS, importStepIndex, importStepMeta } from '../importProgress/importSteps.js';

/** The facts the advance sheet asks for, per TARGET step and nothing else (N03). `RECEIVED` also carries the
 * complete-per-invoice tick, handled by the sheet itself. Document numbers are separate: see STEP_DOC_KIND. */
export const STEP_FACT_FIELDS = {
  CONTACTED: [],
  ORDERED: [],
  PICKED_UP: [{ key: 'goodsReadyOn', label: 'วันที่ของพร้อมที่โรงงาน', type: 'date' }],
  IN_TRANSIT: [
    { key: 'vessel', label: 'ชื่อเรือ', type: 'text' },
    { key: 'etdActualOn', label: 'เรือออกจริง', type: 'date' },
    { key: 'etaOn', label: 'คาดว่าถึงไทย', type: 'date' },
    { key: 'port', label: 'ท่าเรือ', type: 'text' },
  ],
  AWAITING_CUSTOMS: [{ key: 'arrivedOn', label: 'วันที่ของถึงไทย', type: 'date' }],
  RECEIVED: [
    { key: 'arrivedOn', label: 'วันที่ของถึงไทย', type: 'date' },
    { key: 'customsReleasedOn', label: 'วันที่ออกของ', type: 'date' },
    { key: 'grNumber', label: 'เลขที่ใบตรวจรับ (GR)', type: 'text' },
  ],
};

/** An OPTIONAL document number the advance sheet offers for a step (no warning when left empty). */
export const STEP_DOC_KIND = { ORDERED: 'PROFORMA', IN_TRANSIT: 'INVOICE' };

export const TRANSPORT_MODES = [{ code: 'SEA', label: 'ทางเรือ' }, { code: 'AIR', label: 'ทางอากาศ' }, { code: 'LAND', label: 'ทางบก' }];

export const DOC_KINDS = [
  { code: 'PROFORMA', label: 'Proforma invoice' },
  { code: 'INVOICE', label: 'Invoice' },
  { code: 'PACKING_LIST', label: 'Packing list' },
  { code: 'BILL_OF_LADING', label: 'Bill of Lading / AWB' },
  { code: 'CERT_ORIGIN', label: 'ใบรับรองแหล่งกำเนิด' },
  { code: 'IMPORT_ENTRY', label: 'ใบขนขาเข้า' },
  { code: 'OTHER', label: 'อื่น ๆ' },
];
export const docKindLabel = (code) => DOC_KINDS.find((k) => k.code === code)?.label ?? code;

/** Whether the round already carries a document of this kind with a number. */
export const hasNumberedDoc = (round, kind) => (round.documents ?? []).some((d) => d.kind === kind && d.docNo);

/** Every shipment fact the facts editor shows, in the order of the round page (N02 "ข้อมูลรอบนี้"). */
export const ALL_FACT_FIELDS = [
  { key: 'label', label: 'ชื่อรอบ (เช่น รอบ พ.ย.)', type: 'text' },
  { key: 'vessel', label: 'ชื่อเรือ', type: 'text' },
  { key: 'voyage', label: 'เที่ยวเรือ / เลขที่เที่ยว', type: 'text' },
  { key: 'forwarder', label: 'ตัวแทนขนส่ง (Forwarder)', type: 'text' },
  { key: 'containerNos', label: 'เลขตู้คอนเทนเนอร์', type: 'text' },
  { key: 'goodsReadyOn', label: 'ของพร้อมที่โรงงาน', type: 'date' },
  { key: 'etdPlannedOn', label: 'เรือออกตามแผน', type: 'date' },
  { key: 'etdActualOn', label: 'เรือออกจริง', type: 'date' },
  { key: 'etaOn', label: 'คาดว่าถึงไทย', type: 'date' },
  { key: 'port', label: 'ท่าเรือ', type: 'text' },
  { key: 'arrivedOn', label: 'วันที่ของถึงไทย', type: 'date' },
  { key: 'customsReleasedOn', label: 'วันที่ออกของ', type: 'date' },
  { key: 'grNumber', label: 'เลขที่ใบตรวจรับ (GR)', type: 'text' },
  { key: 'note', label: 'หมายเหตุของรอบ', type: 'text' },
];

const MODE_LABELS = {
  SEA: {},
  AIR: { vessel: 'เที่ยวบิน', port: 'สนามบิน', etdPlannedOn: 'ออกจากต้นทางตามแผน', etdActualOn: 'ออกจากต้นทางจริง', voyage: 'เลขที่ AWB' },
  LAND: { vessel: 'ทะเบียนรถ', port: 'ด่าน / จุดผ่านแดน', etdPlannedOn: 'ออกจากต้นทางตามแผน', etdActualOn: 'ออกจากต้นทางจริง', voyage: 'เลขที่เที่ยวรถ' },
};
/** The label of a fact for the round's transport mode: vessel becomes เที่ยวบิน / ทะเบียนรถ, "เรือออก" becomes ออกจากต้นทาง. */
export const factLabel = (field, mode) => MODE_LABELS[mode ?? 'SEA']?.[field.key]
  ?? field.label ?? ALL_FACT_FIELDS.find((f) => f.key === field.key)?.label;

export const STEP_DATE_KEY = {
  CONTACTED: 'contactedOn', ORDERED: 'orderedOn', PICKED_UP: 'pickedUpOn', IN_TRANSIT: 'inTransitOn',
  AWAITING_CUSTOMS: 'awaitingCustomsOn', RECEIVED: 'receivedOn',
};

export const stepLabel = (code) => importStepMeta(code)?.label ?? code;
export const stepTone = (code) => (code === 'RECEIVED' ? 'success' : code === 'CONTACTED' ? 'warning' : 'info');
export const stepPillText = (code) => `${importStepMeta(code)?.s ?? ''} · ${stepLabel(code)}`;

export function formatQty(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n.toLocaleString('en-US') : '-';
}

// An imported PROJECT order can carry the workbook name before it is linked to a deal.
export const lotProjectName = (lot) => (
  lot.ticketId == null && lot.orderPurpose === 'PROJECT' ? lot.purposeNote : lot.projectName
)?.trim();

export function roundProjectNames(round) {
  return [...new Set((round.lots ?? []).map(lotProjectName).filter(Boolean))].join(' / ');
}

export function roundTitle(round) {
  return [round.factoryName, roundProjectNames(round), round.facts?.label].filter(Boolean).join(' · ') || `รอบขนส่ง #${round.id}`;
}

/** Orders rounds by Thailand ETA; rounds with no ETA stay last in either direction. */
export function compareRoundEta(a, b, descending = false) {
  const first = a.facts?.etaOn;
  const second = b.facts?.etaOn;
  if (!first || !second) return Number(!first) - Number(!second);
  return descending ? second.localeCompare(first) : first.localeCompare(second);
}

/** "ล็อต 1 จาก 2" when the line is split (or part of it still waits for a round), else "ครบทั้งใบ". */
export const lotLabel = (lot, roundStep) => (lot.lotCount > 1 || lotArrivalText(lot, roundStep)
  ? `ล็อต ${lot.lotNo} จาก ${Math.max(lot.lotCount, 1)}` : 'ครบทั้งใบ'); // a short lot is never "ครบทั้งใบ"

/** A short arrival on a received round: "ถึงโกดัง 130 จาก 140 · ขาด 10"; null when it arrived in full or the round has not received. */
export const lotArrivalText = (lot, roundStep) => {
  if (roundStep !== 'RECEIVED' || lot.receivedQty == null || Number(lot.receivedQty) >= Number(lot.qty)) return null;
  const unit = lot.unit ? ` ${lot.unit}` : '';
  return `ถึงโกดัง ${formatQty(lot.receivedQty)} จาก ${formatQty(lot.qty)}${unit} · ขาด ${formatQty(Number(lot.qty) - Number(lot.receivedQty))}${unit}`;
};

export const lotProduct = (lot) => [[lot.brand, lot.model].filter(Boolean).join(' ') || lot.code, lot.size].filter(Boolean).join(' ');

/** Who the lot is for: the deal's customer/project. */
export const PURPOSE_LABELS = { SAMPLE: 'ตัวอย่าง', SHOWROOM: 'โชว์รูม', STOCK: 'สต็อก', PROJECT: 'โครงการ' };

/** Who a lot (or a waiting IR) is for: the deal's project/customer, or for an order with no project "ตัวอย่าง · requester". */
export const lotWho = (lot) => lotProjectName(lot) || (lot.ticketId == null
  ? [PURPOSE_LABELS[lot.orderPurpose], lot.requesterName].filter(Boolean).join(' · ') || lot.purposeNote
  : lot.customerName || lot.ticketCode);

/** The second line under who: the deal's code, or "ไม่มีโครงการ" (with who it is for) for an order with no project. */
export function lotWhoSub(lot) {
  if (lot.ticketId != null) return `งาน ${lot.ticketCode}`;
  if (lot.orderPurpose === 'PROJECT') return lot.requesterName;
  return ['ไม่มีโครงการ', lot.purposeNote].filter(Boolean).join(' · ');
}

/** The vessel and date line on the queue card. */
export const roundSummaryLine = (round) => [
  round.facts?.vessel && `${MODE_LABELS[round.facts.transportMode]?.vessel ?? 'เรือ'} ${round.facts.vessel}`,
  roundDatesLine(round),
].filter(Boolean).join(' · ');

/** Shipping milestones shown alongside the queue's Thailand ETA. */
export function roundDatesLine(round) {
  const f = round.facts ?? {};
  const parts = [];
  if (f.etdActualOn) parts.push(`${MODE_LABELS[f.transportMode]?.etdActualOn ? 'ออกจากต้นทาง' : 'เรือออก'} ${formatThaiDate(f.etdActualOn)}`);
  if (round.importStep === 'RECEIVED') {
    if (f.customsReleasedOn || round.receivedOn) parts.push(`ออกของ ${formatThaiDate(f.customsReleasedOn ?? round.receivedOn)}`);
    if (f.grNumber) parts.push(`ใบตรวจรับ ${f.grNumber}`);
  }
  return parts.join(' · ');
}

/** Under a round's name on the desktop table: its first proforma number, else the goods-receipt number. */
export const roundSubline = (round) => (round.documents ?? []).find((d) => d.kind === 'PROFORMA' && d.docNo)?.docNo
  ?? round.facts?.grNumber ?? '';

/** What tells two rounds of one project apart: the round label and the proforma (or GR) number. */
export const roundReference = (round) => [...new Set([round.facts?.label, roundSubline(round)].filter(Boolean))].join(' · ');

/** The quantity ("n รายการ" unless every line shares a unit) and "x โครงการ · y ตัวอย่าง" for the IRs on a round. */
export function roundIrSummary(round) {
  const kinds = new Map();
  const seen = new Set();
  (round.lots ?? []).forEach((l) => {
    if (seen.has(l.importRequestId)) return;
    seen.add(l.importRequestId);
    const kind = l.ticketId == null ? (PURPOSE_LABELS[l.orderPurpose] ?? 'ไม่มีโครงการ') : 'โครงการ';
    kinds.set(kind, (kinds.get(kind) ?? 0) + 1);
  });
  const units = new Set((round.lots ?? []).map((l) => l.unit ?? ''));
  const planned = (round.lots ?? []).reduce((sum, l) => sum + Number(l.qty), 0);
  const got = (round.lots ?? []).reduce((sum, l) => sum + Number(l.receivedQty ?? l.qty), 0);
  const unit = [...units][0];
  const qty = units.size !== 1 ? null
    : round.importStep === 'RECEIVED' && got < planned
      ? `ถึงโกดัง ${formatQty(got)} จาก ${formatQty(planned)} ${unit} · ขาด ${formatQty(planned - got)}`.replace(/\s+·/, ' ·')
      : `${formatQty(planned)} ${unit}`.trim();
  return {
    qty: qty ?? `${(round.lots ?? []).length} รายการ`,
    mix: [...kinds].map(([k, n]) => `${n} ${k}`).join(' · '),
  };
}

export const roundIrCount = (round) => new Set((round.lots ?? []).map((l) => l.importRequestId)).size;

export const STEP_CHIPS = [{ key: 'ALL', label: 'ทั้งหมด', s: 'ทุกขั้น' },
  ...IMPORT_STEPS.map((s) => ({ key: s.code, label: s.label, s: s.s }))];

/** Lots can be planned, changed, removed or moved only until the round leaves (before IN_TRANSIT). */
export const lotsEditable = (round) => importStepIndex(round.importStep) < importStepIndex('IN_TRANSIT');

/** One row per IR on a round (queue card): who, IR no., the lot label, and the summed quantity when every line shares a
 * unit, else "n รายการ". */
export function irRows(round) {
  const byIr = new Map();
  (round.lots ?? []).forEach((l) => {
    if (!byIr.has(l.importRequestId)) byIr.set(l.importRequestId, []);
    byIr.get(l.importRequestId).push(l);
  });
  return [...byIr.values()].map((lots) => {
    const first = lots[0];
    const split = lots.find((l) => l.lotCount > 1);
    const units = new Set(lots.map((l) => l.unit ?? ''));
    const planned = lots.reduce((sum, l) => sum + Number(l.qty), 0);
    const got = lots.reduce((sum, l) => sum + Number(l.receivedQty ?? l.qty), 0);
    const short = round.importStep === 'RECEIVED' && units.size === 1 && got < planned;
    return {
      key: first.importRequestId, who: lotWho(first), docNumber: first.docNumber, ticketId: first.ticketId,
      lotText: split ? `ล็อต ${split.lotNo} จาก ${split.lotCount}`
        : round.importStep === 'RECEIVED' && got < planned ? `ล็อต ${first.lotNo} จาก 1` : 'ครบทั้งใบ',
      qtyText: short
        ? `ถึงโกดัง ${formatQty(got)} จาก ${formatQty(planned)} ${first.unit ?? ''} · ขาด ${formatQty(planned - got)}`.replace(/\s+·/, ' ·')
        : units.size === 1
        ? `${formatQty(planned)} ${first.unit ?? ''}`.trim()
        : `${lots.length} รายการ`,
    };
  });
}

/** Waiting IRs grouped by factory (only factories with an id can be planned), for the N04 picker. */
export function waitingFactories(waiting) {
  const map = new Map();
  (waiting ?? []).filter((w) => w.factoryId != null).forEach((w) => {
    if (!map.has(w.factoryId)) map.set(w.factoryId, { factoryId: w.factoryId, factoryName: w.factoryName, irs: [] });
    map.get(w.factoryId).irs.push(w);
  });
  return [...map.values()].sort((a, b) => (a.factoryName ?? '').localeCompare(b.factoryName ?? '', 'th'));
}
