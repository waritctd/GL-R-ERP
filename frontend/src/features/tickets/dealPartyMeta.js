// Deal parties (docs/decisions/deal-party-model.md): the Thai wording of a party's role, status and
// not-awarded reason, shared by the deal page, the bid-outcome dialog, the quotation list and the
// quotation editor so all four say the same thing. Codes mirror sales.deal_party's CHECK lists.

export const PARTY_ROLE_LABEL = Object.freeze({
  DESIGNER: 'ผู้ออกแบบ',
  OWNER: 'เจ้าของโครงการ',
  BUYER: 'ผู้รับเหมา',
});

export const NOT_AWARDED_REASON_LABEL = Object.freeze({
  OTHER_BIDDER: 'ผู้รับเหมารายอื่นได้งาน',
  PRICE: 'ราคา',
  LEAD_TIME: 'ระยะเวลาส่ง',
  PAYMENT_TERMS: 'เงื่อนไขชำระ',
  RELATIONSHIP: 'ความสัมพันธ์',
  PRODUCT_FIT: 'สินค้าไม่ตรงความต้องการ',
});

// The four reasons the bid-outcome dialog offers, in its order (prototype screen 3).
export const BID_OUTCOME_REASONS = Object.freeze(['OTHER_BIDDER', 'PRICE', 'LEAD_TIME', 'PAYMENT_TERMS']);

const STATUS_META = {
  ACTIVE: { label: 'ยังไม่เสนอราคา', tone: 'neutral' },
  QUOTED: { label: 'เสนอราคาแล้ว', tone: 'neutral' },
  NEGOTIATING: { label: 'กำลังต่อรอง', tone: 'warning' },
  AWARDED: { label: 'ได้งาน', tone: 'success' },
  AWARDED_SPLIT: { label: 'ได้งาน · แยกดีล', tone: 'success' },
  NOT_AWARDED: { label: 'ไม่ได้งาน', tone: 'neutral' },
  WITHDRAWN: { label: 'ถอนตัว', tone: 'neutral' },
};

/** `{ label, tone }` for a party's outcome chip; NOT_AWARDED carries its reason ("ไม่ได้งาน · ราคา"). */
export function partyStatusLabel(status, notAwardedReason = null) {
  const meta = STATUS_META[status] ?? { label: status ?? '-', tone: 'neutral' };
  if (status === 'NOT_AWARDED' && notAwardedReason) {
    return { ...meta, label: `${meta.label} · ${NOT_AWARDED_REASON_LABEL[notAwardedReason] ?? notAwardedReason}` };
  }
  return meta;
}

/** Still in the bid: may be ticked as a winner (given an APPROVED latest quotation). */
export function isBiddingParty(party) {
  return party?.role === 'BUYER' && ['QUOTED', 'NEGOTIATING'].includes(party.status);
}

export function buyerParties(parties) {
  return (parties ?? []).filter((p) => p.role === 'BUYER');
}

/** The deal is still waiting on its bid: more than one contractor and none awarded yet. */
export function isAwaitingBidOutcome(parties) {
  const buyers = buyerParties(parties);
  return buyers.length > 1 && !buyers.some((p) => p.status === 'AWARDED');
}

/** `<code>-A`, `-B`, … for the n-th (0-based) child deal a bid outcome forks. */
export function childDealCode(code, index) {
  return `${code}-${String.fromCharCode(65 + index)}`;
}
