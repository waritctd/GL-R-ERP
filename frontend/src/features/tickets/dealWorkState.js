import {
  canActOnPricingDecision,
  canConfirmOrder,
  canCreateCustomerQuotation,
  canCreatePricingRequest,
  canPickupPricingRequest,
  canRespondInformation,
  canStartCeoReview,
  canSubmitPricingRequest,
} from '../pricingRequests/pricingRequestMeta.js';
import { dealLostReasonLabel, dealStageLabel } from '../../utils/format.js';

export const DEAL_WORK_STATES = {
  NEEDS_ACTION: 'needs-action',
  WAITING: 'waiting',
  BLOCKED: 'blocked',
  RETURNED: 'returned',
  OVERDUE: 'overdue',
  DRAFT: 'draft',
  COMPLETE: 'complete',
  CANCELLED: 'cancelled',
  INFORMATIONAL: 'informational',
};

const ROLE_LABEL_TH = {
  sales: 'ฝ่ายขาย',
  sales_manager: 'ผู้จัดการฝ่ายขาย',
  import: 'ฝ่ายนำเข้า',
  account: 'ฝ่ายบัญชี',
  ceo: 'CEO',
};

const TERMINAL_TICKET_STATUSES = new Set(['closed', 'cancelled']);
const INACTIVE_PRICING_REQUEST_STATUSES = new Set(['CANCELLED', 'SUPERSEDED']);

const TICKET_ACTIONS = [
  ['CONFIRM_CUSTOMER', {
    labelTh: 'ยืนยันว่าลูกค้าตอบรับแล้ว',
    explanationTh: 'ดีลพร้อมเข้าสู่ขั้นคำสั่งซื้อและมัดจำ',
    responsibleRole: 'sales',
  }],
  ['ISSUE_DEPOSIT_NOTICE', {
    labelTh: 'ออกใบแจ้งมัดจำ',
    explanationTh: 'ลูกค้ายืนยันแล้วและดีลต้องออกเอกสารมัดจำ',
    responsibleRole: 'sales',
  }],
  ['DEPOSIT_PAID', {
    labelTh: 'ยืนยันรับมัดจำ',
    explanationTh: 'มีใบแจ้งมัดจำแล้วและรอฝ่ายบัญชียืนยันการรับเงิน',
    responsibleRole: 'account',
  }],
  ['FINAL_PAYMENT', {
    labelTh: 'ยืนยันรับเงินครบ',
    explanationTh: 'ดีลอยู่ในขั้นรับชำระส่วนที่เหลือ',
    responsibleRole: 'account',
  }],
  ['RECORD_PAYMENT', {
    labelTh: 'บันทึกรับชำระเงิน',
    explanationTh: 'ดีลมีรายการชำระเงินที่ฝ่ายบัญชีต้องบันทึก',
    responsibleRole: 'account',
  }],
  ['CONFIRM_CLOSE', {
    labelTh: 'ยืนยันพร้อมปิดงาน',
    explanationTh: 'รับเงินครบ ส่งมอบครบ และมีใบกำกับภาษีแล้ว รอฝ่ายบัญชียืนยันก่อน CEO ตรวจสอบ',
    responsibleRole: 'account',
  }],
  ['VERIFY_CLOSE', {
    labelTh: 'ตรวจสอบและปิดงาน',
    explanationTh: 'ฝ่ายบัญชียืนยันพร้อมปิดงานแล้ว รอ CEO ตรวจสอบขั้นสุดท้าย',
    responsibleRole: 'ceo',
  }],
  ['ISSUE_IMPORT_REQUEST', {
    labelTh: 'ออกใบขอซื้อ (IR)',
    explanationTh: 'ดีลพร้อมเข้าสู่ขั้นจัดซื้อและนำเข้าสินค้า',
    responsibleRole: null,
  }],
  ['IR_SENT', {
    labelTh: 'ส่ง IR ไปยังผู้ผลิต',
    explanationTh: 'ออก IR แล้วและรอบันทึกการส่งคำสั่งซื้อไปยังผู้ผลิต',
    responsibleRole: null,
  }],
  ['SHIPPING', {
    labelTh: 'บันทึกสินค้าเดินทาง',
    explanationTh: 'ส่ง IR แล้วและรอบันทึกว่าสินค้าอยู่ระหว่างเดินทาง',
    responsibleRole: null,
  }],
  ['GOODS_RECEIVED', {
    labelTh: 'ยืนยันรับสินค้าเข้าคลัง',
    explanationTh: 'สินค้าอยู่ระหว่างเดินทางและรอยืนยันการรับเข้าคลัง',
    responsibleRole: null,
  }],
  ['RESERVE_STOCK', {
    labelTh: 'จองสินค้าจากสต็อก',
    explanationTh: 'มีสินค้าที่สามารถจัดส่งจากสต็อกได้',
    responsibleRole: null,
  }],
  ['RECORD_PARTIAL_DELIVERY', {
    labelTh: 'บันทึกการส่งมอบ',
    explanationTh: 'มีสินค้าพร้อมส่งมอบให้ลูกค้า',
    responsibleRole: null,
  }],
  ['COMPLETE_DELIVERY', {
    labelTh: 'ยืนยันส่งมอบครบ',
    explanationTh: 'ดีลพร้อมบันทึกว่าส่งมอบสินค้าครบแล้ว',
    responsibleRole: null,
  }],
];

function base(state, overrides = {}) {
  return {
    state,
    labelTh: overrides.labelTh ?? 'สถานะดีล',
    explanationTh: overrides.explanationTh ?? 'ยังไม่มีงานเร่งด่วนจากข้อมูลที่โหลดมา',
    responsibleRole: overrides.responsibleRole ?? null,
    blocker: overrides.blocker ?? null,
    primaryActionKey: overrides.primaryActionKey ?? null,
  };
}

function summaryFromInput(input) {
  if (input?.summary) return input.summary;
  if (input?.ticket?.summary) return input.ticket.summary;
  if (input?.ticket && !input.ticket.summary) return input.ticket;
  return null;
}

function actionsFromInput(input) {
  if (Array.isArray(input?.availableActions)) return input.availableActions;
  if (Array.isArray(input?.actions?.availableActions)) return input.actions.availableActions;
  return [];
}

function pricingRequestsFromInput(input, summary) {
  const source = Array.isArray(input?.pricingRequests) ? input.pricingRequests : [];
  if (!summary?.id) return source;
  return source.filter((pr) => pr.ticketId == null || Number(pr.ticketId) === Number(summary.id));
}

function hasAction(actions, key) {
  return actions.some((item) => item?.action === key);
}

function availableAction(actions, key) {
  return actions.find((item) => item?.action === key) ?? null;
}

function activePricingRequests(pricingRequests) {
  return pricingRequests.filter((pr) => !INACTIVE_PRICING_REQUEST_STATUSES.has(pr?.status));
}

function newestFirst(items) {
  return [...items].sort((a, b) => {
    const aTime = new Date(a?.updatedAt ?? a?.createdAt ?? 0).getTime();
    const bTime = new Date(b?.updatedAt ?? b?.createdAt ?? 0).getTime();
    if (Number.isFinite(aTime) && Number.isFinite(bTime) && aTime !== bTime) return bTime - aTime;
    return Number(b?.id ?? 0) - Number(a?.id ?? 0);
  });
}

function amountOutstanding(summary) {
  if (summary?.amountOutstanding == null || summary.amountOutstanding === '') return 0;
  const value = Number(summary.amountOutstanding);
  return Number.isFinite(value) ? value : 0;
}

function returnReason(pr) {
  return pr?.returnReason
    ?? pr?.latestReturnReason
    ?? pr?.infoRequestMessage
    ?? pr?.moreInfoReason
    ?? pr?.message
    ?? pr?.outcomeNote
    ?? null;
}

function quotationStatus(pr) {
  return pr?.quotationStatus ?? pr?.customerQuotationStatus ?? pr?.docStatus ?? null;
}

function waitingRoleForPricingRequest(pr) {
  if (pr.status === 'SUBMITTED') return 'import';
  if (['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS'].includes(pr.status)) {
    return pr.assignedImportId || pr.assignedImportName ? 'import' : null;
  }
  if (['READY_FOR_CEO_REVIEW', 'CEO_REVIEWING'].includes(pr.status)) return 'ceo';
  if (['APPROVED_FOR_QUOTATION', 'MORE_INFO_REQUIRED', 'QUOTATION_ACCEPTED'].includes(pr.status)) return 'sales';
  if (pr.status === 'QUOTATION_ISSUED') return null;
  if (pr.status === 'COSTING_REVISION_REQUIRED') return 'import';
  return null;
}

function pricingRequestLabel(pr) {
  return pr?.requestCode ? `ใบขอราคา ${pr.requestCode}` : 'ใบขอราคา';
}

function pricingReturnedState(pr, responsibleRole, primaryActionKey = null) {
  const reason = returnReason(pr);
  if (!reason) {
    return base(DEAL_WORK_STATES.INFORMATIONAL, {
      labelTh: 'มีงานถูกส่งกลับ',
      explanationTh: `${pricingRequestLabel(pr)} ถูกส่งกลับ แต่ข้อมูลที่โหลดมายังไม่มีเหตุผลประกอบ`,
      responsibleRole: null,
    });
  }
  return base(DEAL_WORK_STATES.RETURNED, {
    labelTh: 'งานถูกส่งกลับ',
    explanationTh: `${pricingRequestLabel(pr)} ถูกส่งกลับ: ${reason}`,
    responsibleRole,
    blocker: reason,
    primaryActionKey,
  });
}

function resolvePricingRequestForViewer(user, pr) {
  if (quotationStatus(pr) === 'REVISION_REQUESTED') {
    return pricingReturnedState(pr, 'sales');
  }
  if (canRespondInformation(user, pr)) {
    return pricingReturnedState(pr, 'sales', 'RESPOND_PRICING_INFO');
  }
  if (pr.status === 'COSTING_REVISION_REQUIRED' && user?.role === 'import') {
    return pricingReturnedState(pr, 'import', 'REVISE_COSTING');
  }
  if (canSubmitPricingRequest(user, pr)) {
    return base(DEAL_WORK_STATES.DRAFT, {
      labelTh: 'ร่างใบขอราคา',
      explanationTh: `${pricingRequestLabel(pr)} ยังเป็นร่างและพร้อมส่งให้ฝ่ายนำเข้า`,
      responsibleRole: 'sales',
      primaryActionKey: 'SUBMIT_PRICING_REQUEST',
    });
  }
  if (canPickupPricingRequest(user, pr)) {
    return base(DEAL_WORK_STATES.NEEDS_ACTION, {
      labelTh: 'รับงานขอราคา',
      explanationTh: `${pricingRequestLabel(pr)} รอฝ่ายนำเข้ารับเรื่อง`,
      responsibleRole: 'import',
      primaryActionKey: 'PICKUP_PRICING_REQUEST',
    });
  }
  if (canStartCeoReview(user, pr)) {
    return base(DEAL_WORK_STATES.NEEDS_ACTION, {
      labelTh: 'เริ่มตรวจราคาขาย',
      explanationTh: `${pricingRequestLabel(pr)} ส่งให้ CEO ตรวจแล้ว`,
      responsibleRole: 'ceo',
      primaryActionKey: 'START_CEO_REVIEW',
    });
  }
  if (canActOnPricingDecision(user, pr)) {
    return base(DEAL_WORK_STATES.NEEDS_ACTION, {
      labelTh: 'ตัดสินใจราคาขาย',
      explanationTh: `${pricingRequestLabel(pr)} อยู่ระหว่าง CEO พิจารณาราคาขาย`,
      responsibleRole: 'ceo',
      primaryActionKey: 'DECIDE_PRICING',
    });
  }
  if (canCreateCustomerQuotation(user, pr)) {
    return base(DEAL_WORK_STATES.NEEDS_ACTION, {
      labelTh: 'สร้างใบเสนอราคาลูกค้า',
      explanationTh: `${pricingRequestLabel(pr)} ได้รับอนุมัติราคาขายแล้ว`,
      responsibleRole: 'sales',
      primaryActionKey: 'CREATE_CUSTOMER_QUOTATION',
    });
  }
  if (canConfirmOrder(user, pr)) {
    return base(DEAL_WORK_STATES.NEEDS_ACTION, {
      labelTh: 'ยืนยันคำสั่งซื้อ',
      explanationTh: `${pricingRequestLabel(pr)} ลูกค้ายอมรับใบเสนอราคาแล้ว`,
      responsibleRole: 'sales',
      primaryActionKey: 'CONFIRM_ORDER',
    });
  }
  return null;
}

function resolvePricingWaiting(pr) {
  if (quotationStatus(pr) === 'REVISION_REQUESTED') {
    return pricingReturnedState(pr, 'sales');
  }
  if (pr.status === 'MORE_INFO_REQUIRED') {
    return pricingReturnedState(pr, 'sales');
  }
  if (pr.status === 'COSTING_REVISION_REQUIRED') {
    return pricingReturnedState(pr, 'import');
  }
  const role = waitingRoleForPricingRequest(pr);
  const roleText = role ? ROLE_LABEL_TH[role] : null;

  if (pr.status === 'QUOTATION_ISSUED') {
    return base(DEAL_WORK_STATES.WAITING, {
      labelTh: 'รอลูกค้าตัดสินใจ',
      explanationTh: `${pricingRequestLabel(pr)} ออกใบเสนอราคาแล้วและรอผลจากลูกค้า`,
      responsibleRole: null,
    });
  }

  if (role) {
    return base(DEAL_WORK_STATES.WAITING, {
      labelTh: `รอ${roleText}`,
      explanationTh: `${pricingRequestLabel(pr)} อยู่ในขั้นที่${roleText}รับผิดชอบ`,
      responsibleRole: role,
    });
  }

  return null;
}

function resolveTicketAction(actions) {
  for (const [key, config] of TICKET_ACTIONS) {
    if (hasAction(actions, key)) {
      const offered = availableAction(actions, key);
      const target = offered?.targetStage ? dealStageLabel(offered.targetStage).label : null;
      return base(DEAL_WORK_STATES.NEEDS_ACTION, {
        ...config,
        labelTh: target ? `${config.labelTh}: ${target}` : config.labelTh,
        primaryActionKey: key,
      });
    }
  }
  return null;
}

function resolveTerminal(summary, actions) {
  const lifecycle = summary.lifecycle ?? 'ACTIVE';
  if (lifecycle === 'CANCELLED' || summary.status === 'cancelled') {
    const reason = summary.cancelReason ? ` เหตุผล: ${summary.cancelReason}` : '';
    return base(DEAL_WORK_STATES.CANCELLED, {
      labelTh: 'ดีลถูกยกเลิก',
      explanationTh: `ดีลนี้เป็นสถานะสิ้นสุดแล้ว${reason}`,
      blocker: summary.cancelReason ?? null,
    });
  }
  if (lifecycle === 'CLOSED_LOST') {
    const lost = dealLostReasonLabel(summary.lostReason).label;
    const hasReason = summary.lostReason != null && lost !== '-';
    return base(DEAL_WORK_STATES.CANCELLED, {
      labelTh: 'เสียงาน',
      explanationTh: hasReason ? `ดีลนี้เสียงานแล้ว เหตุผล: ${lost}` : 'ดีลนี้เสียงานแล้ว',
      blocker: hasReason ? lost : null,
      primaryActionKey: hasAction(actions, 'REOPEN') ? 'REOPEN' : null,
    });
  }
  if (lifecycle === 'COMPLETED' || TERMINAL_TICKET_STATUSES.has(summary.status)) {
    return base(DEAL_WORK_STATES.COMPLETE, {
      labelTh: 'ปิดงานแล้ว',
      explanationTh: 'ดีลนี้เสร็จสมบูรณ์และผ่านการตรวจปิดงานแล้ว',
    });
  }
  return null;
}

function resolvePaused(summary, actions, user) {
  if (summary.lifecycle !== 'ON_HOLD' && summary.lifecycle !== 'DORMANT') return null;
  const isDormant = summary.lifecycle === 'DORMANT';
  const actionKey = hasAction(actions, 'RESUME') ? 'RESUME' : null;
  return base(DEAL_WORK_STATES.BLOCKED, {
    labelTh: isDormant ? 'พักดีลระยะยาว' : 'พักดีลไว้',
    explanationTh: isDormant
      ? 'ดีลนี้ถูกพักระยะยาว จึงไม่มีงานเดินต่อจนกว่าจะดำเนินการต่อ'
      : 'ดีลนี้ถูกพักไว้ จึงไม่มีงานเดินต่อจนกว่าจะดำเนินการต่อ',
    responsibleRole: actionKey ? user?.role ?? null : null,
    blocker: isDormant ? 'DORMANT' : 'ON_HOLD',
    primaryActionKey: actionKey,
  });
}

function resolveNoPricingRequest(summary, user) {
  if ((summary.lifecycle ?? 'ACTIVE') !== 'ACTIVE') return null;
  if (canCreatePricingRequest(user, summary)) {
    const isDraftTicket = summary.status === 'draft';
    return base(DEAL_WORK_STATES.DRAFT, {
      labelTh: isDraftTicket ? 'แบบร่าง' : 'ยังไม่มีใบขอราคา',
      explanationTh: isDraftTicket
        ? 'ดีลยังเป็นร่างและพร้อมสร้างใบขอราคา'
        : 'เจ้าของดีลยังไม่ได้สร้างใบขอราคาสำหรับดีลนี้',
      responsibleRole: 'sales',
      primaryActionKey: 'CREATE_PRICING_REQUEST',
    });
  }
  if (summary.createdById != null && ['LEAD_APPROACH', 'PRESENTATION', 'SPEC_APPROVED', 'QUOTE_DESIGN_SIDE'].includes(summary.salesStage)) {
    return base(DEAL_WORK_STATES.WAITING, {
      labelTh: 'รอฝ่ายขาย',
      explanationTh: 'ดีลยังอยู่ช่วงเตรียมข้อมูลก่อนส่งขอราคา',
      responsibleRole: 'sales',
    });
  }
  return null;
}

function resolveOverdue(summary, actions) {
  if (!summary.overdue || amountOutstanding(summary) <= 0) return null;
  const accountAction = ['FINAL_PAYMENT', 'RECORD_PAYMENT', 'SET_BILLING'].find((key) => hasAction(actions, key));
  return base(DEAL_WORK_STATES.OVERDUE, {
    labelTh: 'ยอดค้างเกินกำหนด',
    explanationTh: 'ดีลมียอดค้างชำระที่เกินกำหนดติดตาม',
    responsibleRole: 'account',
    blocker: 'OVERDUE_PAYMENT',
    primaryActionKey: accountAction ?? null,
  });
}

function resolvePaymentOrFulfilmentWaiting(summary) {
  if (summary.closeConfirmedAt) {
    return base(DEAL_WORK_STATES.WAITING, {
      labelTh: 'รอ CEO ตรวจปิดงาน',
      explanationTh: summary.closeConfirmedByName
        ? `ฝ่ายบัญชียืนยันพร้อมปิดงานแล้วโดย ${summary.closeConfirmedByName}`
        : 'ฝ่ายบัญชียืนยันพร้อมปิดงานแล้ว',
      responsibleRole: 'ceo',
    });
  }
  if (summary.paymentStatus === 'DEPOSIT_NOTICE_ISSUED') {
    return base(DEAL_WORK_STATES.WAITING, {
      labelTh: 'รอฝ่ายบัญชี',
      explanationTh: 'ออกใบแจ้งมัดจำแล้วและรอฝ่ายบัญชียืนยันรับมัดจำ',
      responsibleRole: 'account',
    });
  }
  if (summary.paymentStatus === 'AWAITING_FINAL_PAYMENT') {
    return base(DEAL_WORK_STATES.WAITING, {
      labelTh: 'รอฝ่ายบัญชี',
      explanationTh: 'ดีลอยู่ในขั้นรอรับชำระส่วนที่เหลือ',
      responsibleRole: 'account',
    });
  }
  if (summary.paymentStatus === 'FULLY_PAID' && summary.fulfillmentStatus !== 'FULLY_DELIVERED') {
    return base(DEAL_WORK_STATES.WAITING, {
      labelTh: 'รอการส่งมอบ',
      explanationTh: 'รับเงินครบแล้วแต่ยังไม่พบข้อมูลว่าส่งมอบครบ',
      responsibleRole: null,
    });
  }
  if (summary.fulfillmentStatus === 'FULLY_DELIVERED' && summary.paymentStatus !== 'FULLY_PAID') {
    return base(DEAL_WORK_STATES.WAITING, {
      labelTh: 'รอการชำระเงิน',
      explanationTh: 'ส่งมอบครบแล้วแต่ยังไม่พบข้อมูลว่ารับเงินครบ',
      responsibleRole: 'account',
    });
  }
  return null;
}

function fallback(summary) {
  const stage = dealStageLabel(summary.salesStage).label;
  return base(DEAL_WORK_STATES.INFORMATIONAL, {
    labelTh: 'ติดตามสถานะดีล',
    explanationTh: stage && stage !== '-' ? `ดีลอยู่ที่ขั้น ${stage}` : 'ยังไม่มีงานเร่งด่วนจากข้อมูลที่โหลดมา',
  });
}

/**
 * Resolve the presentation-only work state for the current viewer. This never
 * mutates backend state and never grants permission; primary actions come from
 * TicketService `availableActions` or exported PricingRequest gates that mirror
 * their Java services.
 */
export function resolveDealWorkState(input = {}) {
  const summary = summaryFromInput(input);
  if (!summary) return fallback({});

  const user = input.user ?? null;
  const actions = actionsFromInput(input);
  const pricingRequests = activePricingRequests(pricingRequestsFromInput(input, summary));

  return resolveTerminal(summary, actions)
    ?? resolvePaused(summary, actions, user)
    ?? resolveOverdue(summary, actions)
    ?? newestFirst(pricingRequests).map((pr) => resolvePricingRequestForViewer(user, pr)).find(Boolean)
    ?? resolveTicketAction(actions)
    ?? (pricingRequests.length === 0 ? resolveNoPricingRequest(summary, user) : null)
    ?? newestFirst(pricingRequests).map((pr) => resolvePricingWaiting(pr)).find(Boolean)
    ?? resolvePaymentOrFulfilmentWaiting(summary)
    ?? fallback(summary);
}
