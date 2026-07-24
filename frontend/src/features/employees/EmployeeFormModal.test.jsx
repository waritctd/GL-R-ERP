import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { EmployeeFormModal } from './EmployeeFormModal.jsx';

globalThis.React = React;

describe('EmployeeFormModal form validation', () => {
  it('blocks submit when required fields are empty', async () => {
    const onSubmit = vi.fn();
    render(<EmployeeFormModal employees={[]} onClose={vi.fn()} onSubmit={onSubmit} />);

    // Clear the name field (all required fields start empty in create mode already,
    // but we still touch one to trigger onChange validation).
    fireEvent.change(screen.getByLabelText(/ชื่อ-นามสกุล/), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText(/อีเมล/), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText(/เบอร์โทร/), { target: { value: '' } });

    fireEvent.click(screen.getByRole('button', { name: /บันทึก/ }));

    expect(await screen.findByText('กรุณาระบุชื่อ-นามสกุล')).not.toBeNull();
    expect(screen.getByText('กรุณาระบุอีเมล')).not.toBeNull();
    expect(screen.getByText('กรุณาระบุเบอร์โทร')).not.toBeNull();

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('blocks submit when email format is invalid', async () => {
    const onSubmit = vi.fn();
    render(<EmployeeFormModal employees={[]} onClose={vi.fn()} onSubmit={onSubmit} />);

    fireEvent.change(screen.getByLabelText(/ชื่อ-นามสกุล/), { target: { value: 'ทดสอบ ระบบ' } });
    fireEvent.change(screen.getByLabelText(/อีเมล/), { target: { value: 'not-an-email' } });
    fireEvent.change(screen.getByLabelText(/เบอร์โทร/), { target: { value: '0812345678' } });

    fireEvent.click(screen.getByRole('button', { name: /บันทึก/ }));

    expect(await screen.findByText('รูปแบบอีเมลไม่ถูกต้อง')).not.toBeNull();
    expect(onSubmit).not.toHaveBeenCalled();
  });

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

  it('pre-fills a stored withholding-tax override of 0 as "0" (distinct from no override)', async () => {
    const onSubmit = vi.fn();
    const employee = {
      id: 9,
      code: 'GLR-009',
      nameTh: 'ทดสอบ ศูนย์',
      email: 'zero@example.com',
      phone: '0800000000',
      withholdingTaxOverride: 0,
    };
    render(<EmployeeFormModal employee={employee} employees={[]} onClose={vi.fn()} onSubmit={onSubmit} />);
    expect(screen.getByLabelText(/ภาษีหัก ณ ที่จ่าย \(กำหนดเอง\)/).value).toBe('0');
  });

  it('clears the department when the division changes (parity with the old update() reset)', async () => {
    const onSubmit = vi.fn();
    const employee = {
      id: 7,
      code: 'GLR-007',
      nameTh: 'ทดสอบ แผนก',
      email: 'dept@example.com',
      phone: '0800000000',
      divisionId: '17',
      divisionTh: 'HR-ฝ่ายบุคคล',
      departmentTh: 'สรรหาบุคลากร',
    };

    render(<EmployeeFormModal employee={employee} employees={[]} onClose={vi.fn()} onSubmit={onSubmit} />);

    const departmentInput = screen.getByLabelText('แผนก');
    expect(departmentInput.value).toBe('สรรหาบุคลากร');

    // Switch the division to a different ฝ่าย — the department must reset to ''.
    fireEvent.change(screen.getByLabelText('ฝ่าย'), { target: { value: '10' } });
    await waitFor(() => expect(departmentInput.value).toBe(''));

    // And the stale department must not flow into the submitted payload.
    fireEvent.click(screen.getByRole('button', { name: /บันทึก/ }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0].departmentTh).toBe('');
    expect(onSubmit.mock.calls[0][0].divisionId).toBe('10');
    expect(onSubmit.mock.calls[0][0].divisionTh).toBe('AC-ฝ่ายบัญชี');
  });

  it('seeds edit-mode defaults from the existing employee and preserves them on submit', async () => {
    const onSubmit = vi.fn();
    const employee = {
      id: 42,
      code: 'GLR-042',
      nameTh: 'สมชาย ใจดี',
      nameEn: 'Somchai Jaidee',
      nickName: 'ชาย',
      email: 'somchai@example.com',
      phone: '0898765432',
      divisionId: '17',
      divisionTh: 'HR-ฝ่ายบุคคล',
      departmentTh: 'สรรหาบุคลากร',
      positionTh: 'หัวหน้างาน',
      level: 'O4',
      salary: 45000,
      directorRemuneration: 50000,
      withholdingTaxOverride: null,
      statusId: 'ACT',
      hireDate: '2099-01-15',
      locationTh: 'สำนักงานใหญ่ กรุงเทพฯ',
      currentAddress: { line1: '123 ถนนสุขุมวิท' },
      emergencyContact: { name: 'สมหญิง ใจดี', phone: '0811112222' },
    };

    render(<EmployeeFormModal employee={employee} employees={[]} onClose={vi.fn()} onSubmit={onSubmit} />);

    expect(screen.getByLabelText(/ชื่อ-นามสกุล/).value).toBe('สมชาย ใจดี');
    expect(screen.getByLabelText(/อีเมล/).value).toBe('somchai@example.com');

    fireEvent.click(screen.getByRole('button', { name: /บันทึก/ }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith({
      nameTh: 'สมชาย ใจดี',
      nameEn: 'Somchai Jaidee',
      nickName: 'ชาย',
      email: 'somchai@example.com',
      phone: '0898765432',
      divisionId: '17',
      departmentTh: 'สรรหาบุคลากร',
      positionTh: 'หัวหน้างาน',
      level: 'O4',
      salary: 45000,
      directorRemuneration: 50000,
      withholdingTaxOverride: null,
      statusId: 'ACT',
      hireDate: '2099-01-15',
      locationTh: 'สำนักงานใหญ่ กรุงเทพฯ',
      address: '123 ถนนสุขุมวิท',
      emergencyName: 'สมหญิง ใจดี',
      emergencyPhone: '0811112222',
      divisionTh: 'HR-ฝ่ายบุคคล',
    });
  });
});
