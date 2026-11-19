import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HolidaysTab } from './HolidaysTab.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    holidays: {
      list: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      remove: vi.fn(),
      fetch: vi.fn(),
    },
  },
}));

function apiError(message, status) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function renderTab(showToast = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <HolidaysTab showToast={showToast} />
    </QueryClientProvider>,
  );
}

const CURRENT_YEAR = new Date().getFullYear();

describe('HolidaysTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.holidays.list.mockResolvedValue({
      holidays: [
        { holidayDate: `${CURRENT_YEAR}-01-01`, nameTh: 'วันขึ้นปีใหม่', source: 'BANK' },
        { holidayDate: `${CURRENT_YEAR}-08-15`, nameTh: 'วันหยุดพิเศษบริษัท', source: 'COMPANY' },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('creates a new holiday and refetches the list', async () => {
    const showToast = vi.fn();
    api.holidays.create.mockResolvedValue({ holidayDate: `${CURRENT_YEAR}-12-05`, nameTh: 'วันพ่อ', source: 'COMPANY' });
    renderTab(showToast);

    await screen.findByText('วันขึ้นปีใหม่');
    fireEvent.click(screen.getByRole('button', { name: 'เพิ่มวันหยุด' }));

    fireEvent.change(screen.getByLabelText(/^วันที่/), { target: { value: `${CURRENT_YEAR}-12-05` } });
    fireEvent.blur(screen.getByLabelText(/^วันที่/));
    fireEvent.change(screen.getByLabelText(/^ชื่อวันหยุด/), { target: { value: 'วันพ่อ' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.holidays.create).toHaveBeenCalledWith({ holidayDate: `${CURRENT_YEAR}-12-05`, nameTh: 'วันพ่อ' }));
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'เพิ่มวันหยุดแล้ว'));
    // Modal closes on success.
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  // 409 duplicate date: HolidayAdminService#create's own message, surfaced inline with an "แก้ไขรายการเดิม"
  // shortcut into edit mode for the already-loaded conflicting row.
  it('shows the 409 duplicate-date message inline and offers "แก้ไขรายการเดิม" for a row already on screen', async () => {
    api.holidays.create.mockRejectedValue(apiError(
      `มีข้อมูลวันหยุดสำหรับวันที่ ${CURRENT_YEAR}-01-01 อยู่แล้ว กรุณาแก้ไขรายการเดิมแทนการสร้างใหม่`,
      409,
    ));
    renderTab();

    await screen.findByText('วันขึ้นปีใหม่');
    fireEvent.click(screen.getByRole('button', { name: 'เพิ่มวันหยุด' }));
    fireEvent.change(screen.getByLabelText(/^วันที่/), { target: { value: `${CURRENT_YEAR}-01-01` } });
    fireEvent.blur(screen.getByLabelText(/^วันที่/));
    fireEvent.change(screen.getByLabelText(/^ชื่อวันหยุด/), { target: { value: 'ปีใหม่ (ซ้ำ)' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    expect(await screen.findByText(/กรุณาแก้ไขรายการเดิมแทนการสร้างใหม่/)).not.toBeNull();
    // The dialog must stay open (not close on error) so the conflict is fixable in place.
    expect(screen.getByRole('dialog')).not.toBeNull();

    const useExisting = screen.getByRole('button', { name: 'แก้ไขรายการเดิม' });
    fireEvent.click(useExisting);

    // Switches into edit mode for the conflicting row -- the date input is now disabled and pre-filled with the
    // existing row's own name, not the just-typed duplicate name.
    expect(screen.getByLabelText(/^ชื่อวันหยุด/).value).toBe('วันขึ้นปีใหม่');
    expect(screen.getByLabelText(/^วันที่/)).toHaveProperty('disabled', true);
  });

  // The consequential case this branch's brief calls out by name: PUT forces source='COMPANY' even on a BANK
  // row. Must go through a named confirm step, and must NOT call update() until that confirm is accepted.
  it('requires a named confirm before reclassifying a BANK holiday to COMPANY, and only updates after confirming', async () => {
    api.holidays.update.mockResolvedValue({ holidayDate: `${CURRENT_YEAR}-01-01`, nameTh: 'วันขึ้นปีใหม่ (แก้ไข)', source: 'COMPANY' });
    renderTab();

    await screen.findByText('วันขึ้นปีใหม่');
    fireEvent.click(screen.getAllByRole('button', { name: /การดำเนินการสำหรับวันหยุด/ })[0]);
    fireEvent.click(screen.getByRole('menuitem', { name: 'แก้ไข' }));

    expect(screen.getByText('ธนาคารแห่งประเทศไทย (BANK)')).not.toBeNull();
    fireEvent.change(screen.getByLabelText(/^ชื่อวันหยุด/), { target: { value: 'วันขึ้นปีใหม่ (แก้ไข)' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    // update() must not fire yet -- the reclassification confirm interposes first.
    expect(api.holidays.update).not.toHaveBeenCalled();
    const confirmHeading = await screen.findByText('แปลงวันหยุดจาก ธปท. เป็นวันหยุดบริษัท');
    expect(confirmHeading).not.toBeNull();
    expect(screen.getByText(/จะเปลี่ยนแหล่งที่มาเป็น/)).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันแก้ไข' }));
    await waitFor(() => expect(api.holidays.update).toHaveBeenCalledWith(`${CURRENT_YEAR}-01-01`, { nameTh: 'วันขึ้นปีใหม่ (แก้ไข)' }));
  });
});
