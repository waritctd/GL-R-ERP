import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { EmployeeFormModal } from './EmployeeFormModal.jsx';

globalThis.React = React;

describe('EmployeeFormModal form validation', () => {
  it('sends the existing create payload shape for a valid submit', async () => {
    const onSubmit = vi.fn();
    render(<EmployeeFormModal employees={[]} onClose={vi.fn()} onSubmit={onSubmit} />);

    fireEvent.change(screen.getByLabelText(/ชื่อ-นามสกุล/), { target: { value: 'ทดสอบ ระบบ' } });
    fireEvent.change(screen.getByLabelText(/อีเมล/), { target: { value: 'test@example.com' } });
    fireEvent.change(screen.getByLabelText(/เบอร์โทร/), { target: { value: '0812345678' } });

    fireEvent.click(screen.getByRole('button', { name: /บันทึก/ }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith({
      nameTh: 'ทดสอบ ระบบ',
      nameEn: '',
      nickName: '',
      email: 'test@example.com',
      phone: '0812345678',
      divisionId: '10',
      departmentTh: '',
      positionTh: 'เจ้าหน้าที่',
      level: 'O2',
      salary: 0,
      directorRemuneration: 0,
      // Blank standing withholding override submits as null ("compute automatically"), NOT 0.
      withholdingTaxOverride: null,
      statusId: 'ACT',
      hireDate: '',
      locationTh: 'สำนักงานใหญ่ กรุงเทพฯ',
      address: '',
      emergencyName: '',
      emergencyPhone: '',
      divisionTh: 'AC-ฝ่ายบัญชี',
    });
  });

  it('submits a typed withholding-tax override as a number, and 0 as 0 (not null)', async () => {
    const onSubmit = vi.fn();
    render(<EmployeeFormModal employees={[]} onClose={vi.fn()} onSubmit={onSubmit} />);

    fireEvent.change(screen.getByLabelText(/ชื่อ-นามสกุล/), { target: { value: 'ทดสอบ ระบบ' } });
    fireEvent.change(screen.getByLabelText(/อีเมล/), { target: { value: 'test@example.com' } });
    fireEvent.change(screen.getByLabelText(/เบอร์โทร/), { target: { value: '0812345678' } });
    fireEvent.change(screen.getByLabelText(/ภาษีหัก ณ ที่จ่าย \(กำหนดเอง\)/), { target: { value: '5000' } });

    fireEvent.click(screen.getByRole('button', { name: /บันทึก/ }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].withholdingTaxOverride).toBe(5000);

    // A typed 0 is a meaningful override (withhold nothing) and must NOT collapse to null.
    onSubmit.mockClear();
    fireEvent.change(screen.getByLabelText(/ภาษีหัก ณ ที่จ่าย \(กำหนดเอง\)/), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: /บันทึก/ }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].withholdingTaxOverride).toBe(0);
  });

  it('clearing a stored withholding-tax override submits null (round-trips back to auto-compute)', async () => {
    const onSubmit = vi.fn();
    const employee = {
      id: 11,
      code: 'GLR-011',
      nameTh: 'ทดสอบ ล้างค่า',
      email: 'clear@example.com',
      phone: '0800000011',
      withholdingTaxOverride: 5000,
    };
    render(<EmployeeFormModal employee={employee} employees={[]} onClose={vi.fn()} onSubmit={onSubmit} />);

    const input = screen.getByLabelText(/ภาษีหัก ณ ที่จ่าย \(กำหนดเอง\)/);
    // Pre-fills the existing standing override.
    expect(input.value).toBe('5000');

    // HR clears the field to restore automatic computation.
    fireEvent.change(input, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: /บันทึก/ }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    // Blank must submit null, NOT 0 -- 0 is a distinct "withhold nothing" override.
    expect(onSubmit.mock.calls[0][0].withholdingTaxOverride).toBeNull();
  });
});
