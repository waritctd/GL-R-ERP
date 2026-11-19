import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
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
});
