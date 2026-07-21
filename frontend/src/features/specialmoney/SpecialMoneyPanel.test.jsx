import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SpecialMoneyPanel } from './SpecialMoneyPanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    specialMoney: {
      employees: vi.fn(),
      types: vi.fn(),
      list: vi.fn(),
      usage: vi.fn(),
      create: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
      cancel: vi.fn(),
    },
  },
}));

const user = {
  employeeId: 1,
  name: 'พนักงาน ทดสอบ',
  role: 'employee',
  manager: false,
};

const currentEmployee = {
  id: 1,
  nameTh: 'พนักงาน ทดสอบ',
};

// Matches SpecialMoneyController's GET /types shape (thin subset covering
// every category the type <select>'s <optgroup> groups on).
const TYPES = [
  { requestType: 'TRAVEL_PER_DIEM', thaiLabel: 'เบี้ยเลี้ยงเดินทาง', payrollBucket: 'PER_DIEM', evidenceRequired: false },
  { requestType: 'MEDICAL', thaiLabel: 'ค่ารักษาพยาบาล', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'AID_WEDDING', thaiLabel: 'เงินช่วยเหลืองานแต่งงาน', payrollBucket: 'AID', evidenceRequired: true },
  { requestType: 'UNIFORM_ANNUAL', thaiLabel: 'ชุดฟอร์มประจำปี', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'OTHER', thaiLabel: 'อื่นๆ', payrollBucket: 'AID', evidenceRequired: true },
];

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <SpecialMoneyPanel user={user} currentEmployee={currentEmployee} showToast={vi.fn()} />
    </QueryClientProvider>,
  );
}

async function selectType(value) {
  const select = await screen.findByLabelText(/ประเภทคำขอ/);
  // The <select> exists immediately, but its <option>s only render once
  // GET /types resolves — wait for the target option to actually be in the
  // DOM before firing change, or a <select>'s .value silently stays at ""
  // when set to a value with no matching <option> (jsdom, same as real
  // browsers).
  await waitFor(() => expect(select.querySelector(`option[value="${value}"]`)).not.toBeNull());
  fireEvent.change(select, { target: { value } });
  return select;
}

describe('SpecialMoneyPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.specialMoney.employees.mockResolvedValue({
      employees: [{
        employeeId: 1,
        employeeName: 'พนักงาน ทดสอบ',
        employeeCode: 'GLR-001',
        self: true,
        directReport: false,
      }],
    });
    api.specialMoney.types.mockResolvedValue({ types: TYPES });
    api.specialMoney.list.mockResolvedValue({ requests: [] });
    api.specialMoney.usage.mockResolvedValue({
      usage: {
        employeeId: 1,
        year: new Date().getFullYear(),
        approvedAmountThisYearByType: {},
        approvedCountLifetimeByType: {},
      },
    });
    api.specialMoney.create.mockResolvedValue({ request: { id: 3001 } });
  });

  it('swaps the visible fields when the request type changes', async () => {
    renderPanel();

    await selectType('TRAVEL_PER_DIEM');
    expect(await screen.findByLabelText(/จังหวัดปลายทาง/)).not.toBeNull();
    expect(screen.getByLabelText(/^บทบาท/)).not.toBeNull();
    expect(screen.queryByLabelText(/วันที่ใบเสร็จ/)).toBeNull();

    await selectType('MEDICAL');
    await waitFor(() => expect(screen.queryByLabelText(/จังหวัดปลายทาง/)).toBeNull());
    expect(screen.getByLabelText(/วันที่ใบเสร็จ/)).not.toBeNull();
  });

  it('computes per-diem as days x role rate', async () => {
    renderPanel();

    await selectType('TRAVEL_PER_DIEM');
    const startInput = await screen.findByLabelText(/วันที่เริ่มเดินทาง/);
    const endInput = screen.getByLabelText(/วันที่สิ้นสุด/);
    fireEvent.change(startInput, { target: { value: '2026-07-10' } });
    fireEvent.change(endInput, { target: { value: '2026-07-12' } });
    fireEvent.change(screen.getByLabelText(/^บทบาท/), { target: { value: 'driver' } });

    await waitFor(() => {
      expect(screen.getByTestId('smr-computed-amount').textContent).toBe('฿1,200');
    });
    expect(screen.getByText(/3 วัน × ฿400/)).not.toBeNull();
  });

  it('zeroes the amount and warns when the destination province is excluded', async () => {
    renderPanel();

    await selectType('TRAVEL_PER_DIEM');
    fireEvent.change(screen.getByLabelText(/^บทบาท/), { target: { value: 'driver' } });
    fireEvent.change(await screen.findByLabelText(/จังหวัดปลายทาง/), { target: { value: 'นนทบุรี' } });

    await waitFor(() => {
      expect(screen.getByTestId('smr-computed-amount').textContent).toBe('฿0');
    });
    expect(await screen.findByText(/จังหวัดที่ถือเป็นการเดินทางในพื้นที่/)).not.toBeNull();
    const submitButton = screen.getByRole('button', { name: /ส่งคำขอ/ });
    expect(submitButton.disabled).toBe(true);
  });

  it('flips the tax chip with the selected type', async () => {
    renderPanel();

    await selectType('MEDICAL');
    expect(await screen.findByText('ไม่คิดภาษี')).not.toBeNull();

    await selectType('AID_WEDDING');
    await waitFor(() => expect(screen.getByText('คิดภาษี')).not.toBeNull());
  });

  it('submits the expected payload shape for a fixed-aid type', async () => {
    renderPanel();

    await selectType('AID_WEDDING');
    fireEvent.change(await screen.findByLabelText(/วันที่เกิดเหตุการณ์/), { target: { value: '2026-07-01' } });
    fireEvent.change(screen.getByLabelText(/เหตุผล/), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.specialMoney.create).toHaveBeenCalledTimes(1));
    expect(api.specialMoney.create).toHaveBeenCalledWith({
      requestType: 'AID_WEDDING',
      employeeId: 1,
      eventDate: '2026-07-01',
      eventEndDate: null,
      receiptDate: null,
      requestedAmount: 5000,
      reason: 'ทดสอบระบบ',
      detail: {},
    });
  });
});
