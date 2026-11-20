import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RecordPaymentModal } from './FinanceActionModals.jsx';

globalThis.React = React;
afterEach(cleanup);

// Design m-payment (spec §7.11 item 6): the fallback form opens on the issued document — its outstanding amount and
// kind already filled — and keeps the note out of the way until asked for.
const prefill = { kind: 'BALANCE', amount: 456150, docLabel: 'ใบแจ้งหนี้ส่วนที่เหลือ INV-2026-0031', basisHint: '= ยอดคงค้างตามใบแจ้งหนี้' };

function renderModal(props = {}) {
  const onSubmit = vi.fn();
  render(<RecordPaymentModal prefill={prefill} busy={false} error={null} onSubmit={onSubmit} onClose={() => {}} {...props} />);
  return { onSubmit, dialog: screen.getByRole('dialog') };
}

describe('RecordPaymentModal — prefilled from the open document', () => {
  it('submits the prefilled values untouched', () => {
    const { dialog, onSubmit } = renderModal();
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกรับชำระ' }));
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ kind: 'BALANCE', amount: 456150, note: null }));
  });
});
