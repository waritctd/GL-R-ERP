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

  it('renders assignments with scope, schedule, and effective dates', async () => {
    api.workScheduleAssignments.list.mockResolvedValue({ assignments: [baseAssignment()] });
    renderTab();

    expect(await screen.findByText('OFFICE_5D')).not.toBeNull();
    expect(screen.getByText('แผนก')).not.toBeNull();
    expect(screen.getByText('รหัส 5')).not.toBeNull();
    expect(screen.getByText('— (ยังมีผล)')).not.toBeNull();
  });

  it('shows the scope precedence explainer (EMPLOYEE > DEPARTMENT > DIVISION)', async () => {
    api.workScheduleAssignments.list.mockResolvedValue({ assignments: [] });
    renderTab();

    await screen.findByText('ลำดับความสำคัญเมื่อขอบเขตซ้อนกัน');
    expect(screen.getByText(/พนักงานรายคน > แผนก > ฝ่าย/)).not.toBeNull();
  });

  it('creates an assignment with no overlap and shows a plain success toast', async () => {
    api.workScheduleAssignments.list
      .mockResolvedValueOnce({ assignments: [baseAssignment()] }) // initial load
      .mockResolvedValueOnce({ // post-create re-list -- unrelated row, nothing closed
        assignments: [baseAssignment(), { ...baseAssignment(), assignmentId: 2, scopeId: 9, effectiveFrom: TODAY }],
      });
    api.workScheduleAssignments.create.mockResolvedValue({
      ...baseAssignment(), assignmentId: 2, scopeId: 9, effectiveFrom: TODAY,
    });
    const showToast = vi.fn();
    renderTab(showToast);

    await screen.findByText('OFFICE_5D');
    fireEvent.click(screen.getByRole('button', { name: 'มอบหมายตารางเวลาทำงาน' }));
    fireEvent.change(screen.getByLabelText(/^ขอบเขต/), { target: { value: 'DEPARTMENT' } });

    fireEvent.change(screen.getByLabelText(/^รหัสแผนก/), { target: { value: '9' } });
    fireEvent.change(screen.getByLabelText(/^ตารางเวลาทำงาน/), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText(/^วันที่เริ่มมีผล/), { target: { value: TODAY } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.workScheduleAssignments.create).toHaveBeenCalledWith({
      scopeType: 'DEPARTMENT', scopeId: 9, workScheduleId: 3, effectiveFrom: TODAY, effectiveTo: null,
    }));
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'สร้างรายการมอบหมายสำเร็จ'));
    expect(screen.queryByText(/ปิดการมอบหมายเดิม/)).toBeNull();
  });

  // The case this branch's brief calls out by name: WorkScheduleAssignmentRepository
  // #closeOverlappingPredecessors auto-closes an overlapping predecessor for the SAME scope, and
  // the create response never echoes which ids -- that only lands in the audit log. This tab
  // derives it by diffing the assignment list immediately before/after the POST.
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

  // Back-dating: the server is the authority even when the client-side `min` would normally have
  // caught it (e.g. a timezone-boundary race between the browser's "today" and the server's) --
  // the exact server message must render inline, and the dialog must stay open.
  it('surfaces the server 400 back-dating rejection inline and keeps the create dialog open', async () => {
    api.workScheduleAssignments.list.mockResolvedValue({ assignments: [baseAssignment()] });
    api.workScheduleAssignments.create.mockRejectedValue(apiError(
      `ไม่สามารถกำหนดวันที่เริ่มมีผลย้อนหลังได้ (ต้องไม่ก่อนวันที่ ${TODAY}) เนื่องจากจะกระทบผลการคำนวณเวลาทำงานของวันที่ผ่านมาแล้ว`,
      400,
    ));
    renderTab();

    await screen.findByText('OFFICE_5D');
    fireEvent.click(screen.getByRole('button', { name: 'มอบหมายตารางเวลาทำงาน' }));
    fireEvent.change(screen.getByLabelText(/^ขอบเขต/), { target: { value: 'DEPARTMENT' } });
    fireEvent.change(screen.getByLabelText(/^รหัสแผนก/), { target: { value: '5' } });
    fireEvent.change(screen.getByLabelText(/^ตารางเวลาทำงาน/), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText(/^วันที่เริ่มมีผล/), { target: { value: TODAY } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    expect(await screen.findByText(/ไม่สามารถกำหนดวันที่เริ่มมีผลย้อนหลังได้/)).not.toBeNull();
    expect(screen.getByRole('dialog')).not.toBeNull();
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

  it('ends an assignment through the danger-toned ConfirmDialog', async () => {
    api.workScheduleAssignments.list.mockResolvedValue({ assignments: [baseAssignment()] });
    api.workScheduleAssignments.end.mockResolvedValue({ ...baseAssignment(), effectiveTo: TODAY });
    const showToast = vi.fn();
    renderTab(showToast);

    await screen.findByText('OFFICE_5D');
    fireEvent.click(screen.getByRole('button', { name: /การดำเนินการสำหรับการมอบหมายรหัส 1/ }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'สิ้นสุดการมอบหมาย' }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('แผนก');
    fireEvent.click(screen.getByRole('button', { name: 'สิ้นสุด' }));

    await waitFor(() => expect(api.workScheduleAssignments.end).toHaveBeenCalledWith(1, { effectiveTo: TODAY }));
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'สิ้นสุดการมอบหมายแล้ว'));
  });

  it('surfaces a rejected (back-dated) end inline and keeps the ConfirmDialog open', async () => {
    api.workScheduleAssignments.list.mockResolvedValue({ assignments: [baseAssignment()] });
    api.workScheduleAssignments.end.mockRejectedValue(apiError(
      `ไม่สามารถสิ้นสุดรายการย้อนหลังได้ (ต้องไม่ก่อนวันที่ ${TODAY})`,
      400,
    ));
    renderTab();

    await screen.findByText('OFFICE_5D');
    fireEvent.click(screen.getByRole('button', { name: /การดำเนินการสำหรับการมอบหมายรหัส 1/ }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'สิ้นสุดการมอบหมาย' }));
    await screen.findByRole('dialog');
    fireEvent.click(screen.getByRole('button', { name: 'สิ้นสุด' }));

    expect(await screen.findByText(/ไม่สามารถสิ้นสุดรายการย้อนหลังได้/)).not.toBeNull();
    expect(screen.getByRole('dialog')).not.toBeNull();
  });
});
