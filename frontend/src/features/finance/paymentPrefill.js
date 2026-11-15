// What บันทึกรับชำระ opens on (spec 2026-11-15 §7.11 item 6, plan task 5.5). The form is the fallback for receipts the
// Express upload could not match, so it starts from the document sales already issued instead of a blank amount:
//
//   - the deposit is still open (paymentStatus DEPOSIT_NOTICE_ISSUED) and a notice is live → DEPOSIT, the notice's
//     total (VAT-inclusive, like the payable) less any deposit already taken;
//   - otherwise a remaining invoice is live → BALANCE, the deal's outstanding amount (the invoice's own grand total
//     less every payment — money.amountOutstanding is exactly that, server-computed);
//   - otherwise nothing is guessed: no amount, and the kind the page always defaulted to.
//
// A live document is the highest ISSUED version (DRAFTs never reach the finance DTO; SUPERSEDED is history).
// The amount is a starting point — account can still type a part payment over it.

const liveIssued = (docs) => (docs ?? [])
  .filter((d) => d.status === 'ISSUED')
  .reduce((best, d) => (best == null || Number(d.version) > Number(best.version) ? d : best), null);

const positiveOrNull = (n) => (Number.isFinite(n) && n > 0 ? Math.round(n * 100) / 100 : null);

function legacyDefaultKind(deal) {
  // Something already paid -> BALANCE, plus a skipped deposit (nothing to collect up front).
  return (Number(deal.money?.amountPaid) > 0 || (deal.milestoneTrack ?? []).some((step) => step.skipped))
    ? 'BALANCE' : 'DEPOSIT';
}

export function paymentPrefill(deal) {
  const money = deal.money ?? {};
  const notice = liveIssued(deal.documents?.depositNotices);
  if (money.paymentStatus === 'DEPOSIT_NOTICE_ISSUED' && notice) {
    const depositTaken = (money.payments ?? [])
      .filter((p) => p.kind === 'DEPOSIT')
      .reduce((sum, p) => sum + (Number(p.amount) || 0), 0);
    return {
      kind: 'DEPOSIT',
      amount: positiveOrNull(Number(notice.totalPayable) - depositTaken),
      docLabel: `ใบแจ้งมัดจำ ${notice.docNumber}`,
      basisHint: '= ยอดคงค้างตามใบแจ้งมัดจำ',
    };
  }
  const invoice = liveIssued(deal.documents?.remainingInvoices);
  if (invoice) {
    return {
      kind: 'BALANCE',
      amount: positiveOrNull(Number(money.amountOutstanding)),
      docLabel: `ใบแจ้งหนี้ส่วนที่เหลือ ${invoice.docNumber}`,
      basisHint: '= ยอดคงค้างตามใบแจ้งหนี้',
    };
  }
  return { kind: legacyDefaultKind(deal), amount: null, docLabel: null, basisHint: null };
}
