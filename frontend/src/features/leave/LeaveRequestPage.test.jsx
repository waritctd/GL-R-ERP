import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LeaveRequestPage } from './LeaveRequestPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    leave: {
      employees: vi.fn(),
      types: vi.fn(),
      list: vi.fn(),
      balances: vi.fn(),
      contactDefaults: vi.fn(),
      create: vi.fn(),
      cancel: vi.fn(),
      preview: vi.fn(),
    },
  },
}));

const user = { employeeId: 1, name: 'พนักงาน ทดสอบ', role: 'employee', manager: false };
const currentEmployee = { id: 1, nameTh: 'พนักงาน ทดสอบ' };

const emptyContactDefaults = {
  contactDefaults: {
    employeeId: 1, positionTh: null, departmentTh: null, divisionTh: null,
    contactHouseNo: null, contactSubdistrict: null, contactDistrict: null, contactProvince: null, contactPhone: null,
  },
};

const NO_COUNTERS = { emergencyFilingsRemaining: 3, noCertificateOccasionsRemaining: 3 };

function approvedPreview({ depth } = {}) {
  return {
    preview: {
      blocking: null,
      datesEvaluated: true,
      coverageEvaluated: depth === 'FULL',
      totalDays: 1,
      paidDays: 1,
      unpaidDays: 0,
      quotaYearSplits: [{
        quotaYear: 2099, totalDays: 1, paidDays: 1, unpaidDays: 0, quotaRemainingBefore: 6, quotaRemainingAfter: 5,
      }],
      counters: NO_COUNTERS,
    },
  };
}

const dateless_ok_preview = {
  preview: {
    blocking: null, datesEvaluated: false, coverageEvaluated: false,
    totalDays: null, paidDays: null, unpaidDays: null, quotaYearSplits: [], counters: NO_COUNTERS,
  },
};

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}{location.search}</div>;
}

function renderComposer(initialEntries = ['/leave/new']) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const utils = render(
    <MemoryRouter initialEntries={initialEntries}>
      <LocationProbe />
      <QueryClientProvider client={queryClient}>
        <LeaveRequestPage user={user} currentEmployee={currentEmployee} showToast={vi.fn()} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
  return { ...utils, queryClient };
}

async function goToStep2ForVacation() {
  renderComposer();
  const vacationButton = await screen.findByRole('button', { name: /ลาพักร้อน/ });
  fireEvent.click(vacationButton);
  fireEvent.click(screen.getByRole('button', { name: 'ถัดไป' }));
  await screen.findByText(/ขั้นตอนที่ 2\/3/);
}

describe('LeaveRequestPage (Phase A2, #485)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.employees.mockResolvedValue({
      employees: [{
        employeeId: 1, employeeName: 'พนักงาน ทดสอบ', employeeCode: 'GLR-001', self: true, directReport: false,
      }],
    });
    api.leave.types.mockResolvedValue({
      leaveTypes: [
        { code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation', annualQuotaDays: 6 },
        { code: 'SICK', nameTh: 'ลาป่วย', nameEn: 'Sick', annualQuotaDays: 30 },
        { code: 'PERSONAL', nameTh: 'ลากิจ', nameEn: 'Personal', annualQuotaDays: 7 },
      ],
    });
    api.leave.balances.mockResolvedValue({ balances: [] });
    api.leave.contactDefaults.mockResolvedValue(emptyContactDefaults);
    api.leave.create.mockResolvedValue({ request: { id: 2001, status: 'SUBMITTED' } });
    api.leave.preview.mockImplementation((payload) => Promise.resolve(
      payload?.startDate ? approvedPreview(payload) : dateless_ok_preview,
    ));
  });

  it('step 1: a categorically-blocked type is disabled and shows the real backend reason', async () => {
    const blockedOutcome = {
      code: 'ONCE_PER_EMPLOYMENT',
      params: { leaveTypeNameTh: 'ลาอุปสมบท' },
      messageTh: 'การลาอุปสมบทสามารถใช้สิทธิ์ได้เพียงครั้งเดียวตลอดระยะเวลาที่เป็นพนักงาน',
    };
    api.leave.types.mockResolvedValue({
      leaveTypes: [
        { code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation', annualQuotaDays: 6 },
        { code: 'ORDINATION', nameTh: 'ลาอุปสมบท', nameEn: 'Ordination', oncePerEmployment: true, annualQuotaDays: 60 },
      ],
    });
    api.leave.preview.mockImplementation((payload) => {
      if (payload?.leaveTypeCode === 'ORDINATION') {
        return Promise.resolve({
          preview: {
            blocking: blockedOutcome, datesEvaluated: false, coverageEvaluated: false,
            totalDays: null, paidDays: null, unpaidDays: null, quotaYearSplits: [], counters: NO_COUNTERS,
          },
        });
      }
      return Promise.resolve(dateless_ok_preview);
    });

    renderComposer();
    fireEvent.click(await screen.findByRole('button', { name: /ประเภทอื่นๆ/ }));

    // Two buttons match /ลาอุปสมบท/: the "ประเภทอื่นๆ" disclosure toggle (its subtitle lists
    // "ลาคลอด ลาทหาร ลาอุปสมบท") and the actual type-choice button -- disambiguate by picking the
    // one that is itself the disabled type button, not the toggle.
    await waitFor(() => {
      const matches = screen.getAllByRole('button', { name: /ลาอุปสมบท/ });
      const ordinationButton = matches.find((button) => button.hasAttribute('disabled'));
      expect(ordinationButton).toBeTruthy();
    });
    expect(await screen.findByText(/ใช้สิทธิ์ได้เพียงครั้งเดียวตลอดระยะเวลาที่เป็นพนักงาน/)).not.toBeNull();
  });

  it('step 1 -> 2: blocks advancing until a type is chosen, and moves focus to the step-2 heading', async () => {
    renderComposer();
    const nextButton = await screen.findByRole('button', { name: 'ถัดไป' });
    expect(nextButton.disabled).toBe(true);

    fireEvent.click(await screen.findByRole('button', { name: /ลาพักร้อน/ }));
    await waitFor(() => expect(nextButton.disabled).toBe(false));
    fireEvent.click(nextButton);

    const heading = await screen.findByText(/ขั้นตอนที่ 2\/3/);
    await waitFor(() => expect(heading.closest('h2')).toBe(document.activeElement));
  });

  it('step 2: start date before today is rejected and cannot advance to step 3', async () => {
    await goToStep2ForVacation();

    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: '2020-01-01' } });
    expect(await screen.findByText('วันที่เริ่มลาต้องไม่ก่อนวันนี้')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
    expect(screen.queryByText(/ขั้นตอนที่ 3\/3/)).toBeNull();
  });

  it('step 2: honestly surfaces coverageEvaluated=false under the debounced QUICK preview', async () => {
    await goToStep2ForVacation();
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: '2099-12-31' } });
    fireEvent.change(screen.getByLabelText(/วันที่สิ้นสุด/), { target: { value: '2099-12-31' } });

    expect(screen.getByText(/ยังไม่ตรวจภาระงานของแผนก/)).not.toBeNull();
    await waitFor(() => expect(api.leave.preview).toHaveBeenCalledWith(
      expect.objectContaining({ depth: 'QUICK' }),
      expect.anything(),
    ));
  });

  it('full happy path: sends the same create() payload shape the pre-A2 form sent, via step 3', async () => {
    await goToStep2ForVacation();

    const futureDate = '2099-12-31';
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบระบบ' } });

    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
    await screen.findByText(/ขั้นตอนที่ 3\/3/);

    const submitButton = await screen.findByRole('button', { name: /ส่งคำขอ/ });
    await waitFor(() => expect(submitButton.disabled).toBe(false));
    fireEvent.click(submitButton);

    await waitFor(() => expect(api.leave.create).toHaveBeenCalledTimes(1));
    expect(api.leave.create).toHaveBeenCalledWith({
      employeeId: 1,
      leaveTypeCode: 'VACATION',
      startDate: futureDate,
      endDate: futureDate,
      reason: 'ทดสอบระบบ',
      startTime: null,
      endTime: null,
      contactHouseNo: null,
      contactSubdistrict: null,
      contactDistrict: null,
      contactProvince: null,
      contactPhone: null,
      purposeCode: null,
      requestedAsEmergency: null,
      attachmentFile: null,
    });
  });

  it('toggling sub-day leave forces endDate to startDate and sends the chosen times', async () => {
    await goToStep2ForVacation();

    const futureDate = '2099-12-31';
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'หาหมอครึ่งวัน' } });
    fireEvent.click(screen.getByLabelText(/ลาบางส่วนของวัน/));

    expect(screen.getByLabelText(/วันที่สิ้นสุด/).value).toBe(futureDate);
    fireEvent.change(screen.getByLabelText(/เวลาเริ่ม/), { target: { value: '08:30' } });
    fireEvent.change(screen.getByLabelText(/เวลาสิ้นสุด/), { target: { value: '12:30' } });

    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));
    await screen.findByText(/ขั้นตอนที่ 3\/3/);
    const submitButton = await screen.findByRole('button', { name: /ส่งคำขอ/ });
    await waitFor(() => expect(submitButton.disabled).toBe(false));
    fireEvent.click(submitButton);

    await waitFor(() => expect(api.leave.create).toHaveBeenCalledTimes(1));
    const payload = api.leave.create.mock.calls[0][0];
    expect(payload.startDate).toBe(futureDate);
    expect(payload.endDate).toBe(futureDate);
    expect(payload.startTime).toBe('08:30');
    expect(payload.endTime).toBe('12:30');
  });

  it('deep link (?type=&start=&end=) lands directly on step 2 with the type/dates prefilled', async () => {
    renderComposer(['/leave/new?type=SICK&start=2099-06-01&end=2099-06-02']);
    await screen.findByText(/ขั้นตอนที่ 2\/3/);
    expect(screen.getByLabelText(/วันที่เริ่ม/).value).toBe('2099-06-01');
    expect(screen.getByLabelText(/วันที่สิ้นสุด/).value).toBe('2099-06-02');
  });

  it('step 3: a blocking verdict renders role="alert" and disables submit', async () => {
    const blockingOutcome = {
      code: 'DEPARTMENT_COVERAGE',
      params: { uncoveredDate: '2099-12-31' },
      messageTh: 'ไม่มีพนักงานคนอื่นในแผนกทำงานในวันที่เลือก',
    };
    api.leave.preview.mockImplementation((payload) => {
      if (!payload?.startDate) return Promise.resolve(dateless_ok_preview);
      if (payload.depth === 'FULL') {
        return Promise.resolve({
          preview: {
            blocking: blockingOutcome, datesEvaluated: true, coverageEvaluated: true,
            totalDays: 1, paidDays: null, unpaidDays: null, quotaYearSplits: [], counters: NO_COUNTERS,
          },
        });
      }
      return Promise.resolve(approvedPreview(payload));
    });

    await goToStep2ForVacation();
    const futureDate = '2099-12-31';
    fireEvent.change(screen.getByLabelText(/วันที่เริ่ม/), { target: { value: futureDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ถัดไป: ตรวจสอบก่อนส่ง/ }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toMatch(/ไม่มีพนักงานคนอื่นในแผนกทำงานในวันที่เลือก/);
    expect(screen.getByRole('button', { name: /ส่งคำขอ/ }).disabled).toBe(true);
  });

  it('cancel from step 1 navigates back to /leave with the returnTab carried through', async () => {
    renderComposer(['/leave/new?returnTab=review']);
    fireEvent.click(await screen.findByRole('button', { name: 'ยกเลิก' }));
    await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/leave?tab=review'));
  });
});
