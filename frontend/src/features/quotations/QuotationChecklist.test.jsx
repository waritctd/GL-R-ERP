import React from 'react';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { QuotationChecklist } from './QuotationChecklist.jsx';

globalThis.React = React;

const entries = [
  { check: 'contact', message: 'กรุณาระบุผู้สั่งซื้อ', targetId: 'quotation-contact', blocking: true },
  { check: 'customerAddress', message: 'ยังไม่ได้กรอกที่อยู่ลูกค้า', targetId: 'deal-customer-address', blocking: false },
  { check: 'dealProject', message: 'ดีลนี้ยังไม่มีโครงการ (แก้ได้ที่หน้ารายละเอียดดีล)', targetId: null, blocking: false },
  // QA-34: บันทึกร่าง still works, but ส่งขออนุมัติ is disabled until it is filled.
  {
    check: 'fullPaymentTerm', message: 'มัดจำ 0% กรุณาเลือกเงื่อนไขการชำระเงิน', targetId: 'fullPaymentTerm',
    blocking: false, submitBlocking: true,
  },
];

describe('QuotationChecklist', () => {
  it('splits blocking entries, submit-blocking entries and warnings, under their own headings', () => {
    render(<QuotationChecklist entries={entries} />);
    const blocking = screen.getByTestId('checklist-blocking');
    const warnings = screen.getByTestId('checklist-warnings');
    expect(within(blocking).getByText('กรุณาระบุผู้สั่งซื้อ')).not.toBeNull();
    expect(within(blocking).queryByText('ยังไม่ได้กรอกที่อยู่ลูกค้า')).toBeNull();
    expect(within(warnings).getByText('ยังไม่ได้กรอกที่อยู่ลูกค้า')).not.toBeNull();
    expect(screen.getByText('ต้องกรอกก่อนบันทึกหรือส่งขออนุมัติ')).not.toBeNull();

    // QA-34: an entry that only blocks ส่งขออนุมัติ gets its OWN group, not the "can still submit"
    // ควรกรอก one (which used to promise ส่งขออนุมัติได้ for it).
    const submitGroup = screen.getByText('ต้องกรอกก่อนส่งขออนุมัติ — ยังบันทึกร่างได้').parentElement;
    // Announced like the blocking group: it disables ส่งขออนุมัติ.
    expect(submitGroup.getAttribute('role')).toBe('alert');
    expect(within(submitGroup).getByText('มัดจำ 0% กรุณาเลือกเงื่อนไขการชำระเงิน')).not.toBeNull();
    expect(within(submitGroup).queryByText('ยังไม่ได้กรอกที่อยู่ลูกค้า')).toBeNull();
    expect(within(blocking).queryByText('มัดจำ 0% กรุณาเลือกเงื่อนไขการชำระเงิน')).toBeNull();
    expect(within(warnings).queryByText('มัดจำ 0% กรุณาเลือกเงื่อนไขการชำระเงิน')).toBeNull();
    expect(screen.getByText('ควรกรอก — ไม่บังคับ ยังบันทึกและส่งขออนุมัติได้')).not.toBeNull();
  });

});

