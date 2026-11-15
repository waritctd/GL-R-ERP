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
  it('names the document and its outstanding amount, with the kind and amount already filled', () => {
    const { dialog } = renderModal();
    expect(within(dialog).getByText(/ใช้เมื่อรายการจาก Express จับคู่ไม่ได้/)).not.toBeNull();
    const summary = within(dialog).getByTestId('payment-prefill-doc');
    expect(summary.textContent).toContain('ใบแจ้งหนี้ส่วนที่เหลือ INV-2026-0031');
    expect(summary.textContent).toContain('คงค้าง ฿456,150.00');
    expect(within(dialog).getByLabelText('ประเภท').value).toBe('BALANCE');
    expect(within(dialog).getByLabelText('จำนวนเงิน').value).toBe('456150');
    expect(within(dialog).getByText('= ยอดคงค้างตามใบแจ้งหนี้')).not.toBeNull();
  });

  it('submits the prefilled values untouched', () => {
    const { dialog, onSubmit } = renderModal();
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกรับชำระ' }));
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ kind: 'BALANCE', amount: 456150, note: null }));
  });

  it('the amount stays editable — a part payment is typed over the prefill', () => {
    const { dialog, onSubmit } = renderModal();
    fireEvent.change(within(dialog).getByLabelText('จำนวนเงิน'), { target: { value: '100000' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกรับชำระ' }));
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ amount: 100000 }));
  });

  it('the note is collapsed behind "+ หมายเหตุ" and opens on click', () => {
    const { dialog, onSubmit } = renderModal();
    expect(within(dialog).queryByLabelText('หมายเหตุ')).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: '+ หมายเหตุ' }));
    fireEvent.change(within(dialog).getByLabelText('หมายเหตุ'), { target: { value: 'เช็คธนาคาร' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกรับชำระ' }));
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ note: 'เช็คธนาคาร' }));
  });

  it('shows the withholding-tax hint only when the prefill carries one', () => {
    const { dialog } = renderModal({ prefill: { ...prefill, whtHint: 'ถ้าลูกค้าหัก ณ ที่จ่าย ให้แก้ยอด' } });
    expect(within(dialog).getByTestId('payment-wht-hint').textContent).toBe('ถ้าลูกค้าหัก ณ ที่จ่าย ให้แก้ยอด');
    cleanup();
    const { dialog: plain } = renderModal();
    expect(within(plain).queryByTestId('payment-wht-hint')).toBeNull();
  });

  it('with no open document: no summary line, a blank amount, and the given kind', () => {
    const { dialog } = renderModal({ prefill: { kind: 'DEPOSIT', amount: null, docLabel: null, basisHint: null } });
    expect(within(dialog).queryByTestId('payment-prefill-doc')).toBeNull();
    expect(within(dialog).getByLabelText('จำนวนเงิน').value).toBe('');
    expect(within(dialog).getByLabelText('ประเภท').value).toBe('DEPOSIT');
  });
});
