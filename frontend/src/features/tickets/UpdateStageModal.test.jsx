import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { UpdateStageModal } from './UpdateStageModal.jsx';

globalThis.React = React;

const owner = { id: 6, role: 'sales' };
const accountUser = { id: 11, role: 'account' };
const deal = { salesStage: 'NEGOTIATION', createdById: 6 };

describe('UpdateStageModal', () => {
  it('offers the sales owner only sales-gated stages', () => {
    render(<UpdateStageModal user={owner} deal={deal} onClose={() => {}} onSubmit={() => {}} />);
    const options = screen.getAllByRole('option').map((o) => o.value);
    expect(options).toContain('SPEC_APPROVED');
    expect(options).toContain('DELIVERED');
    expect(options).not.toContain('DEPOSIT_RECEIVED');
    expect(options).not.toContain('PROCUREMENT');
    expect(options).not.toContain('NEGOTIATION'); // current stage excluded
  });

  it('offers account only the money stages', () => {
    render(<UpdateStageModal user={accountUser} deal={deal} onClose={() => {}} onSubmit={() => {}} />);
    const options = screen.getAllByRole('option').map((o) => o.value);
    expect(options).toEqual(['DEPOSIT_RECEIVED', 'CLOSED_PAID']);
  });

  it('requires a note when moving backward, then submits', () => {
    const onSubmit = vi.fn();
    render(<UpdateStageModal user={owner} deal={deal} onClose={() => {}} onSubmit={onSubmit} />);
    const select = screen.getByRole('combobox');

    // Backward move (NEGOTIATION → PRESENTATION): warning shown, save disabled.
    fireEvent.change(select, { target: { value: 'PRESENTATION' } });
    expect(screen.getByText(/กำลังย้อนสถานะกลับ/)).toBeTruthy();
    const save = screen.getByRole('button', { name: 'บันทึก' });
    expect(save.disabled).toBe(true);

    fireEvent.change(screen.getByPlaceholderText('ระบุรายละเอียด…'), {
      target: { value: 'ลูกค้าเปลี่ยนผู้ออกแบบ' },
    });
    expect(save.disabled).toBe(false);
    fireEvent.click(save);
    expect(onSubmit).toHaveBeenCalledWith({ stage: 'PRESENTATION', note: 'ลูกค้าเปลี่ยนผู้ออกแบบ' });
  });

  it('forward move needs no note', () => {
    const onSubmit = vi.fn();
    render(<UpdateStageModal user={owner} deal={deal} onClose={() => {}} onSubmit={onSubmit} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'ORDER_RECEIVED' } });
    expect(screen.queryByText(/กำลังย้อนสถานะกลับ/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));
    expect(onSubmit).toHaveBeenCalledWith({ stage: 'ORDER_RECEIVED', note: undefined });
  });
});
