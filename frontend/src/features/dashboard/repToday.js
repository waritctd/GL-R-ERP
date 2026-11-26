import { bangkokTodayIso, formatMoney, formatThaiDate } from '../../utils/format.js';
import { dayLineLabel, timeLabel, visitIsDue } from '../salesBoard/visitWeek.js';
import { addExpectedPaymentPath } from '../salesBoard/cashFormat.js';
import { dealAmountLabel } from '../tickets/movementRange.js';
import { followUpStatus, SALES_ACTION as A } from '../tickets/salesActions.js';

/** รายการที่ต้องอัปเดต, in reading order (spec 0003 slice 3, board TD1). */
export const TODAY_GROUPS = [
  { key: 'money', label: 'เงินที่ยังไม่อยู่ในรายงาน' },
  { key: 'customer', label: 'ลูกค้ารอคำตอบ' },
  { key: 'due', label: 'ถึงกำหนดติดตาม' },
  { key: 'next', label: 'ขั้นตอนถัดไป' },
];

const MONEY = new Set([A.CREATE_DEPOSIT_NOTICE, A.CREATE_REMAINING_INVOICE]);
const CUSTOMER = new Set([A.ISSUE_QUOTATION, A.QUOTE_NEXT_PARTY, A.CONFIRM_ORDER, A.CONFIRM_ORDER_DIRECT, A.RECORD_QUOTATION_OUTCOME]);

/** Which group a next-sales-action reads under; null = not a row (waiting on someone else, or only a nudge to log something). */
export function groupOfAction(key) {
  if (MONEY.has(key)) return 'money';
  if (CUSTOMER.has(key)) return 'customer';
  if (key === A.FOLLOW_UP) return 'due';
  if (key === A.AWAIT_DIRECT_APPROVAL || key === A.LOG_ACTIVITY) return null;
  return 'next';
}

const REASON = {
  [A.CREATE_DEPOSIT_NOTICE]: 'ยืนยันคำสั่งซื้อแล้ว ยังไม่ได้ออกใบแจ้งยอดเงินรับมัดจำ',
  [A.CREATE_REMAINING_INVOICE]: 'ส่งมอบแล้ว ยังไม่ได้ออกใบแจ้งหนี้ส่วนที่เหลือ',
  [A.ISSUE_QUOTATION]: 'ราคาอนุมัติแล้ว พร้อมทำใบเสนอราคา',
  [A.QUOTE_NEXT_PARTY]: 'ส่งใบเสนอราคาแล้ว ถึงคิวเสนอราคาผู้ซื้อรายถัดไป',
  [A.CONFIRM_ORDER]: 'ลูกค้าตอบรับแล้ว รอยืนยันคำสั่งซื้อ',
  [A.CONFIRM_ORDER_DIRECT]: 'ลูกค้าตอบรับแล้ว รอยืนยันคำสั่งซื้อ',
  [A.RECORD_QUOTATION_OUTCOME]: 'ส่งใบเสนอราคาแล้ว รอบันทึกผลจากลูกค้า',
  [A.SUBMIT_DIRECT_QUOTATION]: 'ใบเสนอราคายังไม่ได้ส่งขออนุมัติ',
  [A.REVISE_QUOTATION]: 'ใบเสนอราคาต้องแก้ไข',
  [A.ADVANCE_STAGE]: 'พร้อมเลื่อนไปขั้นถัดไป',
  [A.CREATE_PCR]: 'ยังไม่ได้สร้างคำขอราคา',
  [A.SUBMIT_PRICING_REQUEST]: 'คำขอราคายังไม่ได้ส่งฝ่ายนำเข้า',
  [A.CHOOSE_PROCUREMENT_SOURCE]: 'รับมัดจำแล้ว ต้องเลือกแหล่งจัดซื้อสินค้า',
  [A.CREATE_IMPORT_REQUEST]: 'รับมัดจำแล้ว ต้องออกใบขอซื้อ (IR)',
  [A.RECORD_DELIVERY]: 'พร้อมบันทึกส่งมอบ',
};

const dealName = (d) => d.projectName || d.customerName || d.title || d.code;

/** What a worklist row's button does: `go` navigates, `call` opens the call dialog, `visit` the tick, `resume` resumes. */
function dealRow(deal, action, todayIso) {
  const follow = action.key === A.FOLLOW_UP ? followUpStatus(deal, todayIso) : null;
  const reason = follow ? `${follow === 'overdue' ? 'เลยกำหนดติดตาม' : 'กำหนดติดตามวันนี้'}${follow === 'overdue' ? ` ${formatThaiDate(deal.nextFollowUpAt)}` : ''}` : REASON[action.key] ?? action.label;
  return {
    key: `deal-${deal.id}`, group: groupOfAction(action.key), name: dealName(deal), code: deal.code, to: `/tickets/${deal.id}`,
    reason, amount: dealAmountLabel(deal), button: follow ? 'บันทึกการติดตาม' : action.label,
    act: follow ? { type: 'call', deal: { ticketId: deal.id, title: deal.title, customerName: deal.customerName, designerName: deal.designerName } }
      : { type: 'go', to: `/tickets/${deal.id}` },
  };
}

/**
 * The rows of รายการที่ต้องอัปเดต. `worklist` is SalesOverview's sorted `{ deal, action }` list (nextSalesAction logic
 * untouched); `orders` the weekly report's order rows (one without a next payment date is money outside the report);
 * `visits` the rep's PLANNED visits; `returning` the paused deals whose return date has arrived.
 */
export function buildTodayRows({ worklist, orders, visits, returning, todayIso, nowHm }) {
  const rows = worklist.filter(({ action }) => groupOfAction(action.key)).map(({ deal, action }) => dealRow(deal, action, todayIso));
  const inRows = new Set(rows.map((r) => r.key));
  orders.filter((o) => !o.nextPayment && o.outstanding > 0 && !inRows.has(`deal-${o.ticketId}`)).forEach((o) => rows.push({
    key: `undated-${o.ticketId}`, group: 'money', name: o.projectName || o.customerName || o.title || o.code, code: o.code,
    to: `/tickets/${o.ticketId}`, reason: 'สั่งซื้อแล้ว ยังไม่ระบุวันคาดว่าจะรับเงิน', amount: formatMoney(o.outstanding),
    button: 'ระบุวันที่', act: { type: 'go', to: addExpectedPaymentPath(o.ticketId) },
  }));
  visits.filter((v) => visitIsDue(v, todayIso, nowHm)).forEach((v) => rows.push({
    key: `visit-${v.id}`, group: 'due', name: v.ticketTitle || v.contactName, code: v.ticketId ? v.contactName : null,
    to: v.ticketId ? `/tickets/${v.ticketId}` : null,
    reason: ['นัดเข้าพบ', dayLineLabel(v.plannedDate), timeLabel(v.plannedTime) && `${timeLabel(v.plannedTime)} น.`, 'ยังไม่ได้บันทึกผล'].filter(Boolean).join(' '),
    amount: null, button: 'เข้าพบแล้ว', act: { type: 'visit', visit: v },
  }));
  returning.forEach((d) => rows.push({
    key: `resume-${d.ticketId}`, group: 'due', name: dealName(d), code: d.code ?? null, to: `/tickets/${d.ticketId}`,
    reason: `พักไว้ · ถึงวันกลับมาดู ${formatThaiDate(d.pauseReturnOn)}`, amount: null, button: 'ดำเนินการต่อ', act: { type: 'resume', deal: d },
  }));
  return rows;
}

const WAITING_ON = {
  SUBMITTED: 'ฝ่ายนำเข้า · ตอบราคา',
  IMPORT_REVIEWING: 'ฝ่ายนำเข้า · ตอบราคา',
  AWAITING_FACTORY_RESPONSE: 'ฝ่ายนำเข้า · ตอบราคา',
  READY_FOR_CEO_REVIEW: 'CEO · พิจารณาราคา',
  CEO_REVIEWING: 'CEO · พิจารณาราคา',
};

/** Whole Bangkok days since `iso`; null when unknown. */
export function daysSince(iso, todayIso = bangkokTodayIso()) {
  if (!iso) return null;
  const from = bangkokTodayIso(new Date(iso));
  return Math.round((Date.parse(`${todayIso}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

/** รอฝ่ายอื่นดำเนินการ: quotations awaiting the sales manager, and the rep's pricing requests with import or the CEO. */
export function buildWaitingRows({ worklist, pricingRequests, activeIds, todayIso }) {
  const rows = worklist.filter(({ action }) => action.key === A.AWAIT_DIRECT_APPROVAL).map(({ deal }) => ({
    key: `approve-${deal.id}`, who: 'ผู้จัดการฝ่ายขาย · อนุมัติใบเสนอราคา', name: dealName(deal), code: deal.code, to: `/tickets/${deal.id}`,
    days: null, // the quotation's submission time is not on the deal row
  }));
  pricingRequests.filter((pr) => WAITING_ON[pr.status] && activeIds.has(Number(pr.ticketId))).forEach((pr) => rows.push({
    key: `pr-${pr.id}`, who: WAITING_ON[pr.status], name: pr.projectName || pr.customerName, code: pr.requestCode, to: `/tickets/${pr.ticketId}`,
    days: daysSince(pr.updatedAt ?? pr.submittedAt, todayIso),
  }));
  return rows;
}
