import React from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { QuotationChecklist } from './QuotationChecklist.jsx';

globalThis.React = React;

const entries = [
  { check: 'contact', message: 'กรุณาระบุผู้สั่งซื้อ', targetId: 'quotation-contact', blocking: true },
  { check: 'customerAddress', message: 'ยังไม่ได้กรอกที่อยู่ลูกค้า', targetId: 'deal-customer-address', blocking: false },
  { check: 'dealProject', message: 'ดีลนี้ยังไม่มีโครงการ (แก้ได้ที่หน้ารายละเอียดดีล)', targetId: null, blocking: false },
];

describe('QuotationChecklist', () => {
  it('splits blocking entries from warnings, under their own headings', () => {
    render(<QuotationChecklist entries={entries} />);
    const blocking = screen.getByTestId('checklist-blocking');
    const warnings = screen.getByTestId('checklist-warnings');
    expect(within(blocking).getByText('กรุณาระบุผู้สั่งซื้อ')).not.toBeNull();
    expect(within(blocking).queryByText('ยังไม่ได้กรอกที่อยู่ลูกค้า')).toBeNull();
    expect(within(warnings).getByText('ยังไม่ได้กรอกที่อยู่ลูกค้า')).not.toBeNull();
    expect(screen.getByText('ต้องกรอกก่อนบันทึกหรือส่งขออนุมัติ')).not.toBeNull();
  });

  it('clicking an entry moves focus to ITS field', () => {
    render(
      <>
        <textarea id="deal-customer-address" />
        <select id="quotation-contact" />
        <QuotationChecklist entries={entries} />
      </>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'ยังไม่ได้กรอกที่อยู่ลูกค้า' }));
    expect(document.activeElement?.id).toBe('deal-customer-address');
    fireEvent.click(screen.getByRole('button', { name: 'กรุณาระบุผู้สั่งซื้อ' }));
    expect(document.activeElement?.id).toBe('quotation-contact');
  });

  it('an entry with no field on this page is plain text, not a button that does nothing', () => {
    render(<QuotationChecklist entries={entries} />);
    expect(screen.queryByRole('button', { name: /ดีลนี้ยังไม่มีโครงการ/ })).toBeNull();
    expect(screen.getByText('ดีลนี้ยังไม่มีโครงการ (แก้ได้ที่หน้ารายละเอียดดีล)')).not.toBeNull();
  });

  it('shows no blocking heading when only warnings remain', () => {
    render(<QuotationChecklist entries={entries.filter((e) => !e.blocking)} />);
    expect(screen.queryByTestId('checklist-blocking')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('says so when nothing is missing', () => {
    render(<QuotationChecklist entries={[]} />);
    expect(screen.getByRole('status').textContent).toContain('ข้อมูลในเอกสารครบแล้ว');
  });
});
