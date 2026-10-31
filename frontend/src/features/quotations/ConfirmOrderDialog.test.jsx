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
