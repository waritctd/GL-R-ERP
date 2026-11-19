import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AssignmentsTab } from './AssignmentsTab.jsx';
import { api } from '../../api/index.js';
import { todayIso } from './dateHelpers.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    workScheduleAssignments: {
      list: vi.fn(),
      create: vi.fn(),
      end: vi.fn(),
    },
    workSchedules: {
      list: vi.fn(),
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
      <AssignmentsTab showToast={showToast} />
    </QueryClientProvider>,
  );
}

const TODAY = todayIso();

function baseAssignment(overrides = {}) {
  return {
    assignmentId: 1,
    scopeType: 'DEPARTMENT',
    scopeId: 5,
    workScheduleId: 2,
    scheduleCode: 'OFFICE_5D',
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
    ...overrides,
  };
}

const SCHEDULES = [
  { workScheduleId: 2, code: 'OFFICE_5D', nameTh: 'สำนักงาน 5 วัน' },
  { workScheduleId: 3, code: 'OPS_6D', nameTh: 'ปฏิบัติการ 6 วัน' },
];

describe('AssignmentsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.workSchedules.list.mockResolvedValue({ schedules: SCHEDULES });
  });

  // The case this branch's brief calls out by name: WorkScheduleAssignmentRepository
  // #closeOverlappingPredecessors auto-closes an overlapping predecessor for the SAME scope, and the create
  // response never echoes which ids -- that only lands in the audit log.
  it('detects an auto-closed predecessor by diffing before/after, and shows it explicitly -- never silently', async () => {
    api.workScheduleAssignments.list
      .mockResolvedValueOnce({ assignments: [baseAssignment()] }) // initial load: assignmentId 1, still open
      .mockResolvedValueOnce({ // post-create re-list: assignmentId 1 now closed the day before TODAY
        assignments: [
          { ...baseAssignment(), effectiveTo: '2025-12-31' },
          { ...baseAssignment(), assignmentId: 2, workScheduleId: 3, scheduleCode: 'OPS_6D', effectiveFrom: TODAY },
        ],
      });
    api.workScheduleAssignments.create.mockResolvedValue({
      ...baseAssignment(), assignmentId: 2, workScheduleId: 3, scheduleCode: 'OPS_6D', effectiveFrom: TODAY,
    });
    const showToast = vi.fn();
    renderTab(showToast);

    await screen.findByText('OFFICE_5D');
    fireEvent.click(screen.getByRole('button', { name: 'มอบหมายตารางเวลาทำงาน' }));
    fireEvent.change(screen.getByLabelText(/^ขอบเขต/), { target: { value: 'DEPARTMENT' } });

    fireEvent.change(screen.getByLabelText(/^รหัสแผนก/), { target: { value: '5' } });
    fireEvent.change(screen.getByLabelText(/^ตารางเวลาทำงาน/), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText(/^วันที่เริ่มมีผล/), { target: { value: TODAY } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', expect.stringContaining('ปิดการมอบหมายเดิมโดยอัตโนมัติ 1 รายการ')));
    expect(await screen.findByText('ปิดการมอบหมายเดิม 1 รายการโดยอัตโนมัติ')).not.toBeNull();
    expect(screen.getByText(/รหัสรายการที่ถูกปิด: 1/)).not.toBeNull();
  });

  it('surfaces a 409 conflicting-successor rejection inline and keeps the create dialog open', async () => {
    api.workScheduleAssignments.list.mockResolvedValue({ assignments: [baseAssignment()] });
    api.workScheduleAssignments.create.mockRejectedValue(apiError(
      'มีการกำหนดตารางเวลาทำงานที่มีผลในช่วงเวลาเดียวกันอยู่แล้วสำหรับขอบเขตนี้ กรุณาสิ้นสุดหรือแก้ไขรายการเดิมก่อน',
      409,
    ));
    renderTab();

    await screen.findByText('OFFICE_5D');
    fireEvent.click(screen.getByRole('button', { name: 'มอบหมายตารางเวลาทำงาน' }));
    fireEvent.change(screen.getByLabelText(/^ขอบเขต/), { target: { value: 'DEPARTMENT' } });
    fireEvent.change(screen.getByLabelText(/^รหัสแผนก/), { target: { value: '5' } });
    fireEvent.change(screen.getByLabelText(/^ตารางเวลาทำงาน/), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText(/^วันที่เริ่มมีผล/), { target: { value: TODAY } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    expect(await screen.findByText(/กรุณาสิ้นสุดหรือแก้ไขรายการเดิมก่อน/)).not.toBeNull();
    expect(screen.getByRole('dialog')).not.toBeNull();
  });
});
