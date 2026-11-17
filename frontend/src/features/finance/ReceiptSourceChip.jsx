/**
 * Where a receipt came from (spec 2026-11-15 §7.11 item 7): Express = uploaded from account's Express file
 * (payment_receipt.source EXPRESS, V213); บันทึกเอง = typed by account. A receipt with no source predates V213 and
 * was typed, so it reads บันทึกเอง.
 */
export function ReceiptSourceChip({ source }) {
  const express = source === 'EXPRESS';
  return (
    <span
      className={`inline-flex items-center whitespace-nowrap rounded-full px-2 py-px text-2xs font-bold ${express
        ? 'bg-primary-bg text-primary'
        : 'bg-surface-subtle text-text-muted'}`}
    >
      {express ? 'Express' : 'บันทึกเอง'}
    </span>
  );
}
