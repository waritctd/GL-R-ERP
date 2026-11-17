import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PipelineImportDialog } from './PipelineImportDialog.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      salesBoard: {
        pipelineImport: vi.fn(),
        pipelineImportTemplateUrl: () => '/api/sales/pipeline-imports/template',
      },
    },
  };
});

// Invented rows, one of each action.
const dryRun = {
  rows: 4, toCreate: 1, toUpdate: 1, unchanged: 1, skipped: 1, skippedNeedsDocument: 1, committed: false,
  plan: [
    { row: 2, action: 'CREATE', project: 'โรงแรมสมมุติ', designer: 'สตูดิโอ ก', ownerName: 'คุณทดสอบ ขายดี',
      repCode: 'S1', value: 2500000, ticketId: null, ticketCode: null, changes: [], newCustomer: 'บริษัท สมมุติ จำกัด' },
    { row: 3, action: 'UPDATE', project: 'อาคารสมมุติ', designer: 'สตูดิโอ ข', ownerName: 'คุณทดสอบ ขายดี',
      repCode: 'S3', value: 1240000, ticketId: 11, ticketCode: 'TK-0011', changes: ['โอกาสขาย', 'ช่วงเวลา'] },
    { row: 4, action: 'UNCHANGED', project: 'โชว์รูมสมมุติ', designer: 'สตูดิโอ ค', ownerName: 'คุณทดสอบ ขายดี',
      repCode: 'S5', value: null, ticketId: 12, ticketCode: 'TK-0012', changes: [] },
    { row: 5, action: 'SKIP', project: 'ห้องรับรองสมมุติ', designer: 'สตูดิโอ ง', ownerName: 'คุณทดสอบ ขายดี',
      repCode: null, value: 1880000, ticketId: null, ticketCode: null, changes: [] },
  ],
  issues: [
    { row: 0, severity: 'WARNING', message: 'ไม่รู้จักคอลัมน์: เบอร์โทร — ไม่ถูกนำเข้า' },
    { row: 3, severity: 'WARNING', message: 'คงค่าเดิมในระบบ: ผู้ออกแบบ' },
    { row: 5, severity: 'ERROR', message: 'S4 ไม่มีในระบบ เลือก S3 หรือ S5' },
  ],
};

function renderDialog(props = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(client, 'invalidateQueries');
  const onClose = vi.fn();
  render(
    <QueryClientProvider client={client}>
      <PipelineImportDialog onClose={onClose} {...props} />
    </QueryClientProvider>,
  );
  return { onClose, invalidate };
}

const file = () => new File(['x'], 'Pipeline_ทดสอบ.xlsx', {
  type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
});

async function pickAndCheck() {
  fireEvent.change(screen.getByLabelText('ไฟล์ pipeline'), { target: { files: [file()] } });
  fireEvent.click(screen.getByRole('button', { name: 'ตรวจก่อนนำเข้า' }));
  return screen.findByRole('table');
}

describe('PipelineImportDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.salesBoard.pipelineImport.mockImplementation((_f, isDry) => Promise.resolve(
      isDry ? dryRun : { ...dryRun, committed: true, plan: dryRun.plan.map((p) => (p.action === 'CREATE'
        ? { ...p, ticketId: 99, ticketCode: 'TK-0099' } : p)) },
    ));
  });

  it('starts on the upload step with the template link and nothing sent', () => {
    renderDialog();

    expect(screen.getByRole('dialog', { name: 'นำเข้า pipeline จากชีท' })).toBeTruthy();
    const link = screen.getByRole('link', { name: 'ดาวน์โหลดแม่แบบ' });
    expect(link.getAttribute('href')).toBe('/api/sales/pipeline-imports/template');
    expect(screen.getByRole('button', { name: 'ตรวจก่อนนำเข้า' }).disabled).toBe(true);
    expect(api.salesBoard.pipelineImport).not.toHaveBeenCalled();
  });

  it('the upload step says what the import will not do', () => {
    renderDialog();

    const help = screen.getByTestId('pipeline-import-help').textContent;
    expect(help).toContain('S7–S9');
    expect(help).toContain('ผู้ซื้อหรือเจ้าของโครงการ');
    expect(help).toContain('ไม่เปลี่ยนสถานะงานที่มีอยู่');
    expect(help).toContain('เก็บใน Excel ต่อจนกว่าจะบันทึกเอกสารจริง');
    expect(help).toContain('ชื่อโครงการเดียวกัน = งานเดียว');
    expect(help).toContain('เปลี่ยนชื่อโครงการ');
    expect(help).toContain('-1 ถึง -99');
  });

  it('a dry run shows every row with its result and reasons, and the counts', async () => {
    renderDialog();

    const table = await pickAndCheck();

    expect(api.salesBoard.pipelineImport).toHaveBeenCalledWith(expect.any(File), true);
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(4);
    expect(within(rows[0]).getByText('✓ สร้าง')).toBeTruthy();
    expect(within(rows[0]).getByText('2,500,000')).toBeTruthy();
    expect(within(rows[1]).getByText('↻ อัปเดต')).toBeTruthy();
    expect(within(rows[1]).getByText(/เพิ่ม โอกาสขาย, ช่วงเวลา/)).toBeTruthy();
    expect(within(rows[1]).getByText(/คงค่าเดิมในระบบ: ผู้ออกแบบ/)).toBeTruthy();
    expect(within(rows[2]).getByText('= ไม่เปลี่ยน')).toBeTruthy();
    expect(within(rows[3]).getByText('! ข้าม')).toBeTruthy();
    expect(within(rows[3]).getByText('S4 ไม่มีในระบบ เลือก S3 หรือ S5')).toBeTruthy();
    expect(screen.getByText('สร้าง 1')).toBeTruthy();
    expect(screen.getByText('อัปเดต 1')).toBeTruthy();
    expect(screen.getByText('ข้าม 1')).toBeTruthy();
    expect(screen.getByText('ต้องบันทึกผ่านเอกสาร 1')).toBeTruthy();
    expect(within(rows[0]).getByText('ลูกค้าใหม่: บริษัท สมมุติ จำกัด')).toBeTruthy();
    expect(screen.getByRole('note').textContent).toContain('ไม่รู้จักคอลัมน์: เบอร์โทร');
    expect(screen.getByText('ยังไม่มีอะไรถูกบันทึกจนกว่าจะกดนำเข้า')).toBeTruthy();
    expect(screen.getByText(/Pipeline_ทดสอบ\.xlsx · 4 แถว/)).toBeTruthy();
  });

  it('นำเข้า commits the same file once, then refreshes the boards', async () => {
    const { invalidate } = renderDialog();
    await pickAndCheck();

    fireEvent.click(screen.getByRole('button', { name: 'นำเข้า 2 แถว' }));

    await screen.findByText('นำเข้าแล้ว');
    expect(api.salesBoard.pipelineImport).toHaveBeenCalledTimes(2);
    expect(api.salesBoard.pipelineImport).toHaveBeenLastCalledWith(expect.any(File), false);
    expect(screen.getByText(/สร้างงานใหม่ 1 · อัปเดต 1/)).toBeTruthy();
    // ['tickets'] is the prefix of both the deal list and the sales-board keys (queryKeys.js).
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['tickets'] });
  });

  it('nothing to write disables นำเข้า', async () => {
    api.salesBoard.pipelineImport.mockResolvedValue({
      ...dryRun, toCreate: 0, toUpdate: 0, unchanged: 3, skipped: 1,
      plan: dryRun.plan.map((p) => (p.action === 'SKIP' ? p : { ...p, action: 'UNCHANGED' })),
    });
    renderDialog();
    await pickAndCheck();

    expect(screen.getByRole('button', { name: 'นำเข้า 0 แถว' }).disabled).toBe(true);
  });

  it('กลับไปแก้ไฟล์ returns to the upload step without writing', async () => {
    renderDialog();
    await pickAndCheck();

    fireEvent.click(screen.getByRole('button', { name: 'กลับไปแก้ไฟล์' }));

    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.getByRole('button', { name: 'ตรวจก่อนนำเข้า' })).toBeTruthy();
    expect(api.salesBoard.pipelineImport).toHaveBeenCalledTimes(1);
  });

  it('a refused file shows the server message and stays on upload', async () => {
    api.salesBoard.pipelineImport.mockRejectedValue(new Error('ไม่พบคอลัมน์ สถานะ (S1–S9) ในแถวหัวตาราง — ใช้แม่แบบ'));
    renderDialog();

    fireEvent.change(screen.getByLabelText('ไฟล์ pipeline'), { target: { files: [file()] } });
    fireEvent.click(screen.getByRole('button', { name: 'ตรวจก่อนนำเข้า' }));

    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('ไม่พบคอลัมน์'));
    expect(screen.queryByRole('table')).toBeNull();
  });
});
