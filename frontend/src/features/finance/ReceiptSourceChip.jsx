/**
 * Where a receipt came from (spec 2026-11-15 §7.11 item 7): Express = uploaded from account's Express file
 * (payment_receipt.source EXPRESS, V213); บันทึกเอง = typed by account. A receipt with no source predates V213 and
 * was typed, so it reads บันทึกเอง. TAX_INVOICE = the money of a confirmed delivery-round tax invoice (V236): ใบกำกับ.
 * PRE_PORTAL = the deposit a rep entered for a deal paid before the portal: ก่อนใช้ระบบ.
 */
export function ReceiptSourceChip({ source }) {
  const express = source === 'EXPRESS';
  const invoice = source === 'TAX_INVOICE';
  const prePortal = source === 'PRE_PORTAL';
  return (
    <span
      className={`inline-flex items-center whitespace-nowrap rounded-full px-2 py-px text-2xs font-bold ${express || invoice || prePortal
        ? 'bg-primary-bg text-primary'
        : 'bg-surface-subtle text-text-muted'}`}
    >
      {express ? 'Express' : invoice ? 'ใบกำกับ' : prePortal ? 'ก่อนใช้ระบบ' : 'บันทึกเอง'}
    </span>
  );
}
