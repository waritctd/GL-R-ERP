import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ConfirmOrderDialog } from './ConfirmOrderDialog.jsx';

globalThis.React = React;

const open = (props = {}) => {
  render(<ConfirmOrderDialog open number="QT-2026-0101-1" onCancel={() => {}} onConfirm={() => {}} {...props} />);
  return screen.getByRole('dialog', { name: 'ยืนยันคำสั่งซื้อ' }).textContent;
};

// W6-03: the dialog's tail follows the quotation's deposit %, not a fixed "then the deposit notice".
describe('ConfirmOrderDialog deposit wording', () => {
  it('depositPercent 0: says the quotation takes no deposit, with no deposit-notice wording', () => {
    const text = open({ depositPercent: 0 });
    expect(text).toContain('ไม่เก็บมัดจำ');
    expect(text).not.toContain('ใบแจ้งรับมัดจำ');
    expect(text).toContain('ย้อนกลับไม่ได้');
  });

  it.each([30, 50, 100])('depositPercent %i: keeps the ใบแจ้งรับมัดจำ wording', (depositPercent) => {
    const text = open({ depositPercent });
    expect(text).toContain('จากนั้นออกใบแจ้งรับมัดจำ');
    expect(text).not.toContain('ไม่เก็บมัดจำ');
  });

  it.each([undefined, null])('depositPercent %s (unknown): keeps today\'s text', (depositPercent) => {
    const text = open({ depositPercent });
    expect(text).toContain('จากนั้นออกใบแจ้งรับมัดจำ');
    expect(text).not.toContain('ไม่เก็บมัดจำ');
  });
});

// Confirming auto-cancels the deal's OTHER open direct quotations (DealQuotationService#confirmOrderFromDirectQuotation):
// the dialog names them before the irreversible click.
describe('ConfirmOrderDialog auto-cancelled quotations', () => {
  it('names every open quotation that will be cancelled', () => {
    const text = open({ depositPercent: 30, cancelledNumbers: ['QT-2026-0078-1', 'QT-2026-0080-1'] });
    expect(text).toContain('จะถูกยกเลิกอัตโนมัติ');
    expect(text).toContain('QT-2026-0078-1');
    expect(text).toContain('QT-2026-0080-1');
    expect(text).toContain('ย้อนกลับไม่ได้');
  });

  it.each([undefined, []])('cancelledNumbers %j: the copy is unchanged', (cancelledNumbers) => {
    const text = open({ depositPercent: 30, cancelledNumbers });
    expect(text).not.toContain('อัตโนมัติ');
    expect(text).toContain('จากนั้นออกใบแจ้งรับมัดจำ');
  });
});

// The direct-quotation doors can take the confirmation back (undo-confirm-order); the pricing-request doors cannot,
// so only they may say "ย้อนกลับไม่ได้".
describe('ConfirmOrderDialog undo wording', () => {
  it.each([0, 50])('undoable, depositPercent %i: names the undo and its cut-off, never "ย้อนกลับไม่ได้"', (depositPercent) => {
    const text = open({ depositPercent, undoable: true });
    expect(text).toContain('ยกเลิกการยืนยันคำสั่งซื้อ" ในหน้าใบเสนอราคา'); // the button lives only on the quotation page
    expect(text).toContain('เดินหน้าต่อ');
    expect(text).not.toContain('ย้อนกลับไม่ได้');
  });

  it('undoable with a deposit: the cut-off names the deposit notice', () => {
    expect(open({ depositPercent: 50, undoable: true })).toContain('จนกว่าจะออกใบแจ้งรับมัดจำ');
  });

  it('not undoable (pricing-request door): still says it cannot be reversed and names no undo', () => {
    const text = open({ depositPercent: 50 });
    expect(text).toContain('ย้อนกลับไม่ได้');
    expect(text).not.toContain('ยกเลิกการยืนยันคำสั่งซื้อ');
  });
});
