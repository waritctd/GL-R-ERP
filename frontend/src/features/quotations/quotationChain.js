// The quotation page's document chain card (owner-approved design 2026-11-17): which quotation shows the chain, and
// what each of its four steps says. Pure: the inputs are the quotation and the server's chain summary
// (GET /tickets/{id}/document-chain); the states and reasons are the server's, the words documentChainText.js's.
import { formatThaiDate } from '../../utils/format.js';
import { blockedText, CHAIN_STEP_TITLES, chainNextStep } from '../documentChain/documentChainText.js';

const PAUSED = ['ON_HOLD', 'DORMANT'];

const notice = (reason, text, link) => ({ mode: 'notice', reason, text, link });

/**
 * `{ mode: 'chain' }`, `{ mode: 'notice', reason, text, link }` or `{ mode: 'none' }`.
 *
 * The chain shows on an APPROVED direct quotation whose deal can still take the order, or that the order was confirmed
 * from, on a deal that is neither lost, cancelled nor paused. Every other quotation that has a story to tell gets ONE
 * line and a link to where the chain lives — never a chain whose buttons the server would refuse:
 *   OTHER_QUOTATION      the order was confirmed from another quotation (linked)
 *   SUPERSEDED           a superseded revision (linked to the confirmed quotation, else the deal)
 *   CANCELLED            a cancelled quotation
 *   DEAL_LOST / DEAL_CANCELLED / DEAL_PAUSED   the deal is not going anywhere now
 *   CONFIRMED_ELSEWHERE  the deal's order was confirmed another way, or from a quotation this viewer cannot see
 *   DEAL_PAST_DRAFT      the deal left draft without a confirm (it can no longer take one here)
 * `dealHref` is the deal link this viewer may follow (null: the line carries no deal link).
 */
export function quotationChainView(quotation, chain, { dealHref = null } = {}) {
  if (!quotation || (quotation.origin ?? 'DEAL_DIRECT') !== 'DEAL_DIRECT' || quotation.ticketId == null) return { mode: 'none' };
  if (!['APPROVED', 'SUPERSEDED', 'CANCELLED'].includes(quotation.docStatus) || !chain) return { mode: 'none' };

  const dealLink = dealHref ? { to: dealHref, label: 'เปิดงาน' } : null;
  const confirmedId = chain.order?.confirmedFromQuotationId ?? null;
  const confirmedNumber = chain.order?.confirmedFromQuotationNumber ?? 'ใบเสนอราคาที่ยืนยัน';
  const otherLink = confirmedId != null && Number(confirmedId) !== Number(quotation.id)
    ? { to: `/quotations/${confirmedId}`, label: `เปิด ${confirmedNumber}` } : null;

  if (quotation.docStatus === 'SUPERSEDED') {
    return notice('SUPERSEDED', otherLink
      ? `ฉบับนี้ถูกแทนที่แล้ว — เอกสารต่อจากใบเสนอราคาอยู่ที่ ${confirmedNumber}`
      : 'ฉบับนี้ถูกแทนที่ด้วยฉบับแก้ไขแล้ว — ดูเอกสารต่อที่หน้ารายละเอียดงาน', otherLink ?? dealLink);
  }
  if (quotation.docStatus === 'CANCELLED') {
    return notice('CANCELLED', 'ใบเสนอราคานี้ถูกยกเลิกแล้ว — ไม่มีเอกสารต่อจากฉบับนี้', otherLink ?? dealLink);
  }
  if (otherLink) {
    return notice('OTHER_QUOTATION', `งานนี้ยืนยันคำสั่งซื้อจากใบเสนอราคา ${confirmedNumber} แล้ว — เอกสารต่ออยู่ที่ฉบับนั้น`, otherLink);
  }
  if (chain.lifecycle === 'CLOSED_LOST') return notice('DEAL_LOST', 'งานนี้เสียแล้ว — ไม่มีเอกสารต่อให้ทำ', dealLink);
  if (chain.lifecycle === 'CANCELLED' || chain.dealStatus === 'cancelled') {
    return notice('DEAL_CANCELLED', 'งานนี้ยกเลิกแล้ว — ไม่มีเอกสารต่อให้ทำ', dealLink);
  }
  if (PAUSED.includes(chain.lifecycle)) {
    return notice('DEAL_PAUSED', 'งานนี้พักไว้ — ดำเนินการต่อที่หน้ารายละเอียดงานก่อน แล้วเอกสารต่อจะกลับมาที่นี่', dealLink);
  }
  if (confirmedId != null && Number(confirmedId) === Number(quotation.id)) return { mode: 'chain' };
  if (chain.order?.confirmed) {
    return notice('CONFIRMED_ELSEWHERE', 'งานนี้ยืนยันคำสั่งซื้อแล้ว — ดูเอกสารต่อที่หน้ารายละเอียดงาน', dealLink);
  }
  if (chain.order?.confirmable && quotation.docStatus === 'APPROVED') return { mode: 'chain' };
  return notice('DEAL_PAST_DRAFT', 'งานนี้ผ่านขั้นยืนยันคำสั่งซื้อไปแล้ว — ดูต่อที่หน้ารายละเอียดงาน', dealLink);
}

/** Who a waiting step waits on, as the card prints it. */
const WAITING_ON_TEXT = { sales: 'ฝ่ายขายเจ้าของงาน', account: 'ฝ่ายบัญชี' };

/**
 * The four steps: `{ title, state, detail?, meta?, locked?, waitingOn?, action?, actionLabel?, download? }`, state one
 * of done | current | waiting | todo | skipped. `current` only where THIS viewer can act now (the card's one primary
 * button: `action` ∈ confirm | deposit | remaining); the chain's next step when someone else holds it is `waiting`,
 * naming who (`waitingOn`) or why (`locked`).
 */
export function quotationChainSteps(quotation, chain) {
  const { order = {}, deposit = {}, remaining = {} } = chain ?? {};
  const next = chainNextStep(chain);
  const atNext = (index) => next.index === index;
  const turn = (index) => (atNext(index) ? (next.viewerCanAct ? 'current' : 'waiting') : 'todo');

  const quotationStep = {
    title: CHAIN_STEP_TITLES[0], state: 'done', detail: quotation.number,
    meta: `อนุมัติแล้ว${quotation.approvedAt ? ` · ${formatThaiDate(quotation.approvedAt)}` : ''}`,
  };

  const confirmStep = order.confirmed
    ? { title: CHAIN_STEP_TITLES[1], state: 'done', meta: `ยืนยันแล้ว${order.confirmedAt ? ` · ${formatThaiDate(order.confirmedAt)}` : ''}` }
    : {
      title: CHAIN_STEP_TITLES[1], state: turn(1), meta: 'เมื่อลูกค้าสั่งซื้อตามใบเสนอราคานี้',
      ...(turn(1) === 'current' ? { action: 'confirm', actionLabel: 'ยืนยันคำสั่งซื้อ' } : {}),
      ...(turn(1) === 'waiting' ? { waitingOn: WAITING_ON_TEXT.sales } : {}),
    };

  // Before the order is confirmed the deal's policy has not followed the quotation yet: the quotation's own 0% is
  // what confirming will set (TicketService#followQuotationDepositPolicy). A BLANK percent is not 0%: the server reads
  // it as the default 30% (DealQuotationRenderAdapter.effectiveDepositPercent), as quotationMeta's confirm copy does.
  const zeroOnQuotation = quotation.depositPercent != null && quotation.depositPercent !== ''
    && Number(quotation.depositPercent) === 0;
  const noDeposit = deposit.state === 'NOT_REQUIRED' || (!order.confirmed && zeroOnQuotation);
  let depositStep;
  if (noDeposit) {
    depositStep = { title: CHAIN_STEP_TITLES[2], state: 'skipped', detail: 'ไม่รับมัดจำ', meta: 'ไม่รับมัดจำ — ข้ามขั้นนี้' };
  } else if (deposit.state === 'ISSUED' || deposit.state === 'PAID') {
    depositStep = {
      title: CHAIN_STEP_TITLES[2], state: 'done', detail: deposit.docNumber ?? null,
      meta: `ออกแล้ว${deposit.issueDate ? ` · ${formatThaiDate(deposit.issueDate)}` : ''}${deposit.received ? ' · รับเงินแล้ว' : ''}`,
      download: deposit.issuedNoticeId != null ? 'notice' : undefined,
    };
  } else if (deposit.state === 'TO_CREATE' || deposit.state === 'DRAFT') {
    const state = turn(2);
    // The server's own percent (the notice's, else the confirmed quotation's effective one); none → no figure.
    const percent = deposit.percent;
    depositStep = {
      title: CHAIN_STEP_TITLES[2], state,
      meta: deposit.state === 'DRAFT' ? 'ฉบับร่าง — ยังไม่ออกเลขที่เอกสาร'
        : percent != null ? `มัดจำ ${Number(percent)}% ตามใบเสนอราคา` : 'มัดจำตามใบเสนอราคา',
      ...(deposit.blockedReason ? { locked: blockedText(deposit.blockedReason) } : {}),
      ...(state === 'current'
        ? { action: 'deposit', actionLabel: deposit.state === 'DRAFT' ? 'ออกใบแจ้งยอดเงินรับมัดจำ' : 'สร้างใบแจ้งยอดเงินรับมัดจำ' }
        : {}),
      ...(state === 'waiting' && !deposit.blockedReason ? { waitingOn: WAITING_ON_TEXT.sales } : {}),
    };
  } else {
    depositStep = { title: CHAIN_STEP_TITLES[2], state: 'todo', locked: blockedText('ORDER_NOT_CONFIRMED') };
  }

  let remainingStep;
  if (remaining.state === 'ISSUED' || remaining.state === 'PAID') {
    remainingStep = {
      title: CHAIN_STEP_TITLES[3], state: 'done', detail: remaining.docNumber ?? null,
      meta: `ออกแล้ว${remaining.docDate ? ` · ${formatThaiDate(remaining.docDate)}` : ''}${remaining.state === 'PAID' ? ' · รับเงินครบแล้ว' : ''}`,
      download: remaining.invoiceId != null ? 'invoice' : undefined,
    };
  } else if (remaining.state === 'NOT_NEEDED') {
    remainingStep = { title: CHAIN_STEP_TITLES[3], state: 'skipped', detail: 'ไม่ต้องออก — ชำระครบจากมัดจำ', meta: 'ไม่ต้องออก — ชำระครบจากมัดจำ' };
  } else {
    const state = turn(3);
    remainingStep = {
      title: CHAIN_STEP_TITLES[3], state,
      ...(remaining.blockedReason ? { locked: blockedText(remaining.blockedReason) } : {}),
      ...(remaining.state === 'DRAFT' ? { meta: 'ฉบับร่าง — ยังไม่ออกเลขที่เอกสาร' } : {}),
      ...(state === 'current'
        ? { action: 'remaining', actionLabel: remaining.state === 'DRAFT' ? 'ออกใบแจ้งหนี้ส่วนที่เหลือ' : 'สร้างใบแจ้งหนี้ส่วนที่เหลือ' }
        : {}),
      ...(state === 'waiting' && WAITING_ON_TEXT[next.waitingOn] ? { waitingOn: WAITING_ON_TEXT[next.waitingOn] } : {}),
    };
  }

  return [quotationStep, confirmStep, depositStep, remainingStep];
}
