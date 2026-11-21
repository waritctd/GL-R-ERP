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

export const roundTitle = (round) => [round.factoryName, round.facts?.label].filter(Boolean).join(' · ') || `รอบขนส่ง #${round.id}`;

/** "ล็อต 1 จาก 2" when the line is split (or part of it still waits for a round), else "ครบทั้งใบ". */
export const lotLabel = (lot) => (lot.lotCount > 1 ? `ล็อต ${lot.lotNo} จาก ${lot.lotCount}` : 'ครบทั้งใบ');

export const lotProduct = (lot) => [[lot.brand, lot.model].filter(Boolean).join(' ') || lot.code, lot.size].filter(Boolean).join(' ');

/** Who the lot is for: the deal's customer/project. */
export const lotWho = (lot) => lot.projectName || lot.customerName || lot.ticketCode;

/** The date line under a round's step on the queue card (vessel / departure / ETA / arrival). */
export function roundSummaryLine(round) {
  const f = round.facts ?? {};
  const parts = [];
  if (f.vessel) parts.push(`เรือ ${f.vessel}`);
  if (f.etdActualOn) parts.push(`เรือออก ${formatThaiDate(f.etdActualOn)}`);
  if (f.etaOn && round.importStep !== 'RECEIVED') parts.push(`คาดว่าถึง ${formatThaiDate(f.etaOn)}`);
  if (round.importStep === 'RECEIVED') {
    if (f.customsReleasedOn || round.receivedOn) parts.push(`ออกของ ${formatThaiDate(f.customsReleasedOn ?? round.receivedOn)}`);
    if (f.grNumber) parts.push(`ใบตรวจรับ ${f.grNumber}`);
  }
  return parts.join(' · ');
}

export const roundIrCount = (round) => new Set((round.lots ?? []).map((l) => l.importRequestId)).size;

export const STEP_CHIPS = [{ key: 'ALL', label: 'ทั้งหมด' }, { key: 'WAITING', label: 'รอจัดรอบ' },
  ...IMPORT_STEPS.map((s) => ({ key: s.code, label: s.label }))];

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
    return {
      key: first.importRequestId, who: lotWho(first), docNumber: first.docNumber, ticketId: first.ticketId,
      lotText: split ? `ล็อต ${split.lotNo} จาก ${split.lotCount}` : 'ครบทั้งใบ',
      qtyText: units.size === 1
        ? `${formatQty(lots.reduce((sum, l) => sum + Number(l.qty), 0))} ${first.unit ?? ''}`.trim()
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
