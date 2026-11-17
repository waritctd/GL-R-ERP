// What บันทึกรับชำระ opens on (spec 2026-11-15 §7.11 item 6, plan task 5.5). The form is the fallback for receipts the
// Express upload could not match, so it starts from the document sales already issued instead of a blank amount:
//
//   - the deposit is still open (paymentStatus DEPOSIT_NOTICE_ISSUED) and a notice is live → DEPOSIT, the notice's
//     total (VAT-inclusive, like the payable) less the deposit already taken: DEPOSIT receipts net of ADJUSTMENT
//     ones (a refund / correction — while the deposit is the only thing billed, it can only concern the deposit);
//   - otherwise a remaining invoice is live → BALANCE, THAT invoice's own outstanding: its grand total less the
//     BALANCE receipts (not money.amountOutstanding, which is deal-wide). ADJUSTMENTs are not netted here: once both
//     documents exist one cannot be attributed to either, and account reviews the amount anyway;
//   - otherwise nothing is guessed: no amount, and the kind the page always defaulted to.
//
// `whtHint` asks account to correct the amount when the customer may withhold tax. Only commissionInvoice
// .withholdingTax carries WHT in the finance DTO today (no deal-level WHT setting and no WHT on the remaining-invoice
// document exist yet); an invoice-level `withholdingTax` is honoured if the DTO ever grows one.
//
// A live document is the highest ISSUED version (DRAFTs never reach the finance DTO; SUPERSEDED is history).
// The amount is a starting point — account can still type a part payment over it.

const WHT_HINT = 'ถ้าลูกค้าหัก ณ ที่จ่าย ให้แก้ยอด';

const liveIssued = (docs) => (docs ?? [])
  .filter((d) => d.status === 'ISSUED')
  .reduce((best, d) => (best == null || Number(d.version) > Number(best.version) ? d : best), null);

const positiveOrNull = (n) => (Number.isFinite(n) && n > 0 ? Math.round(n * 100) / 100 : null);

const sumOf = (payments, kind) => (payments ?? [])
  .filter((p) => p.kind === kind)
  .reduce((sum, p) => sum + (Number(p.amount) || 0), 0);

function legacyDefaultKind(deal) {
  // Something already paid -> BALANCE, plus a skipped deposit (nothing to collect up front).
  return (Number(deal.money?.amountPaid) > 0 || (deal.milestoneTrack ?? []).some((step) => step.skipped))
    ? 'BALANCE' : 'DEPOSIT';
}

export function paymentPrefill(deal) {
  const money = deal.money ?? {};
  const payments = money.payments ?? [];
  const notice = liveIssued(deal.documents?.depositNotices);
  const invoice = liveIssued(deal.documents?.remainingInvoices);
  const whtHint = Number(deal.commissionInvoice?.withholdingTax) > 0 || Number(invoice?.withholdingTax) > 0
    ? WHT_HINT : null;
  if (money.paymentStatus === 'DEPOSIT_NOTICE_ISSUED' && notice) {
    const depositTaken = sumOf(payments, 'DEPOSIT') - sumOf(payments, 'ADJUSTMENT');
    return {
      kind: 'DEPOSIT',
      amount: positiveOrNull(Number(notice.totalPayable) - depositTaken),
      docLabel: `ใบแจ้งมัดจำ ${notice.docNumber}`,
      basisHint: '= ยอดคงค้างตามใบแจ้งมัดจำ',
      whtHint,
    };
  }
  if (invoice) {
    return {
      kind: 'BALANCE',
      amount: positiveOrNull(Number(invoice.grandTotal) - sumOf(payments, 'BALANCE')),
      docLabel: `ใบแจ้งหนี้ส่วนที่เหลือ ${invoice.docNumber}`,
      basisHint: '= ยอดคงค้างตามใบแจ้งหนี้',
      whtHint,
    };
  }
  return { kind: legacyDefaultKind(deal), amount: null, docLabel: null, basisHint: null, whtHint };
}
