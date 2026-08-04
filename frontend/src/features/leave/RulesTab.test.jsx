import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RulesTab } from './RulesTab.jsx';
import { api } from '../../api/index.js';
import { downloadBlob } from '../../utils/download.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    leave: {
      types: vi.fn(),
      policyDocumentAvailable: vi.fn(),
      downloadPolicyDocument: vi.fn(),
    },
  },
}));

vi.mock('../../utils/download.js', () => ({
  downloadBlob: vi.fn(),
}));

// A full 7-type fixture (the real hr.leave_type row set — SICK/PERSONAL/VACATION/MATERNITY/
// MILITARY/ORDINATION/LEAVE_WITHOUT_PAY, see V13/V85/V116) with every LeaveTypeDto rule field
// present, values taken verbatim from V116/V119/V120/V124/V125/V127's migration comments — NOT
// mockApi.js's own db.leaveTypes (which omits LEAVE_WITHOUT_PAY; see RulesTab.jsx's own comment on
// why that omission is handled gracefully rather than assumed away here).
const ALL_LEAVE_TYPES = [
  {
    code: 'SICK', nameTh: 'ลาป่วย', nameEn: 'Sick leave', annualQuotaDays: 30, requiresAttachment: true,
    paidDaysCap: null, advanceNoticeDays: 0, minServiceMonths: 0, maxConsecutiveDays: null, oncePerEmployment: false,
    dayCountBasis: 'WORKING_DAYS', proratedFirstYear: false, firstYearMaxDays: null,
    certificateFilingWindowDays: 3, noCertificateMonthlyTolerance: 3, emergencyMonthlyAllowance: null,
    carriesForward: false,
  },
  {
    code: 'PERSONAL', nameTh: 'ลากิจ', nameEn: 'Personal leave', annualQuotaDays: 7, requiresAttachment: false,
    paidDaysCap: null, advanceNoticeDays: 1, minServiceMonths: 0, maxConsecutiveDays: null, oncePerEmployment: false,
    dayCountBasis: 'WORKING_DAYS', proratedFirstYear: true, firstYearMaxDays: 3,
    certificateFilingWindowDays: null, noCertificateMonthlyTolerance: 0, emergencyMonthlyAllowance: 3,
    carriesForward: false,
  },
  {
    code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation leave', annualQuotaDays: 6, requiresAttachment: false,
    paidDaysCap: null, advanceNoticeDays: 3, minServiceMonths: 0, maxConsecutiveDays: null, oncePerEmployment: false,
    dayCountBasis: 'WORKING_DAYS', proratedFirstYear: true, firstYearMaxDays: null,
    certificateFilingWindowDays: null, noCertificateMonthlyTolerance: 0, emergencyMonthlyAllowance: null,
    carriesForward: true,
  },
  {
    code: 'MATERNITY', nameTh: 'ลาคลอดบุตร', nameEn: 'Maternity leave', annualQuotaDays: 98, requiresAttachment: true,
    paidDaysCap: 45, advanceNoticeDays: 0, minServiceMonths: 0, maxConsecutiveDays: null, oncePerEmployment: false,
    dayCountBasis: 'CALENDAR_DAYS', proratedFirstYear: false, firstYearMaxDays: null,
    certificateFilingWindowDays: null, noCertificateMonthlyTolerance: 0, emergencyMonthlyAllowance: null,
    carriesForward: false,
  },
  {
    code: 'MILITARY', nameTh: 'ลารับราชการทหาร', nameEn: 'Military service leave', annualQuotaDays: 366, requiresAttachment: true,
    paidDaysCap: 60, advanceNoticeDays: 0, minServiceMonths: 0, maxConsecutiveDays: null, oncePerEmployment: false,
    dayCountBasis: 'WORKING_DAYS', proratedFirstYear: false, firstYearMaxDays: null,
    certificateFilingWindowDays: null, noCertificateMonthlyTolerance: 0, emergencyMonthlyAllowance: null,
    carriesForward: false,
  },
  {
    code: 'ORDINATION', nameTh: 'ลาอุปสมบท', nameEn: 'Ordination leave', annualQuotaDays: 60, requiresAttachment: false,
    paidDaysCap: 15, advanceNoticeDays: 0, minServiceMonths: 12, maxConsecutiveDays: null, oncePerEmployment: true,
    dayCountBasis: 'WORKING_DAYS', proratedFirstYear: false, firstYearMaxDays: null,
    certificateFilingWindowDays: null, noCertificateMonthlyTolerance: 0, emergencyMonthlyAllowance: null,
    carriesForward: false,
  },
  {
    code: 'LEAVE_WITHOUT_PAY', nameTh: 'ลาไม่รับค่าจ้าง', nameEn: 'Leave without pay', annualQuotaDays: 0, requiresAttachment: false,
    paidDaysCap: 0, advanceNoticeDays: 0, minServiceMonths: 0, maxConsecutiveDays: null, oncePerEmployment: false,
    dayCountBasis: 'WORKING_DAYS', proratedFirstYear: false, firstYearMaxDays: null,
    certificateFilingWindowDays: null, noCertificateMonthlyTolerance: 0, emergencyMonthlyAllowance: null,
    carriesForward: false,
  },
];

function renderRulesTab() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <RulesTab />
    </QueryClientProvider>,
  );
}

describe('RulesTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.types.mockResolvedValue({ leaveTypes: ALL_LEAVE_TYPES });
    api.leave.policyDocumentAvailable.mockResolvedValue(false);
  });

  it('shows a loading state before leave types resolve', () => {
    api.leave.types.mockReturnValue(new Promise(() => {}));
    renderRulesTab();
    expect(screen.getByText('กำลังโหลดข้อมูล')).not.toBeNull();
  });

  it('shows an error state with retry when leave types fails to load', async () => {
    api.leave.types.mockRejectedValue(new Error('boom'));
    renderRulesTab();
    expect(await screen.findByText('boom')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'ลองใหม่' })).not.toBeNull();
  });

  it('renders the everyday sections (SICK/PERSONAL/VACATION) expanded by default', async () => {
    renderRulesTab();
    // Expanded means the body text is in the DOM without clicking anything.
    expect(await screen.findByText(/ลาป่วยตามที่ป่วยจริง/)).not.toBeNull();
    expect(screen.getByText(/ลากิจธุระจำเป็น/)).not.toBeNull();
    expect(screen.getByText(/ลาพักร้อนประจำปี/)).not.toBeNull();
  });

  it('renders the rare sections (MATERNITY/MILITARY/ORDINATION/unpaid) collapsed, with a "พบไม่บ่อย" flag', async () => {
    renderRulesTab();
    await screen.findByText('5.4 ลาคลอดบุตร');

    // Collapsed: CollapsibleSection does not render its body at all until opened.
    expect(screen.queryByText(/ลาคลอดบุตร นับรวมวันลา/)).toBeNull();
    const maternityHeader = screen.getByRole('button', { name: /5\.4 ลาคลอดบุตร/ });
    expect(maternityHeader.getAttribute('aria-expanded')).toBe('false');
    expect(within(maternityHeader.closest('.collapsible-header')).getByText('พบไม่บ่อย')).not.toBeNull();

    fireEvent.click(maternityHeader);
    expect(await screen.findByText(/ลาคลอดบุตร นับรวมวันลา/)).not.toBeNull();
    // paidDaysCap renders once expanded (45.00 วัน — formatDays' th-TH NumberFormat).
    expect(screen.getByText('45 วัน')).not.toBeNull();
  });

  it('never renders MILITARY\'s annualQuotaDays sentinel (366), even once expanded', async () => {
    const { container } = renderRulesTab();
    const militaryHeader = await screen.findByRole('button', { name: /5\.5 ลารับราชการทหาร/ });
    fireEvent.click(militaryHeader);

    await screen.findByText(/ลาเพื่อเข้ารับการตรวจเลือก/);
    // Scoped to MILITARY's own anchor wrapper — SICK/PERSONAL/VACATION legitimately render their
    // own "โควตาต่อปี" rows elsewhere on the same page, so a page-wide query would false-positive.
    const militarySection = container.querySelector('[id="5.5"]');
    expect(within(militarySection).queryByText('โควตาต่อปี')).toBeNull();
    expect(within(militarySection).queryByText(/366/)).toBeNull();
    // The paid cap (a real, non-sentinel figure) still renders.
    expect(within(militarySection).getByText('60 วัน')).not.toBeNull();
  });

  it('renders the type-agnostic department-coverage section (5.3.2) expanded, with no FieldList', async () => {
    renderRulesTab();
    expect(await screen.findByText(/จะไม่อนุมัติคำขอลาที่ทำให้ไม่มีใครในแผนกเดียวกัน/)).not.toBeNull();
  });

  it('lists the five named §5.2 purposes from the shared LEAVE_PURPOSE_OPTIONS list', async () => {
    const { container } = renderRulesTab();
    await screen.findByText('5.2 ลากิจ');
    const purposeList = within(container.querySelector('[id="5.2"]')).getByRole('list');
    const items = within(purposeList).getAllByRole('listitem').map((item) => item.textContent);
    expect(items).toEqual([
      'ทำใบขับขี่ / ติดต่องานราชการ',
      'กิจธุระอันจำเป็นของครอบครัว',
      'ปฏิบัติธรรมทางศาสนาตามธรรมเนียมปฏิบัติ',
      'พิธีสมรส (ของตนเองหรือบุตร) — ลาได้ไม่เกิน 3 วัน',
      'งานศพของบุคคลในครอบครัว',
    ]);
    expect(items.some((text) => text === 'อื่นๆ')).toBe(false);
  });

  it('gives every section a focusable anchor id matching its LeaveRuleCode sectionRef', async () => {
    const { container } = renderRulesTab();
    await screen.findByText('5.3 ลาพักร้อน');
    for (const id of ['5.1', '5.2', '5.3', '5.3.2', '5.3.3', '5.3.4', '5.3.5', '5.4', '5.5', '5.6', 'unpaid']) {
      const node = container.querySelector(`[id="${id}"]`);
      expect(node, `expected an anchor for section ${id}`).not.toBeNull();
      expect(node.getAttribute('tabindex')).toBe('-1');
    }
  });

  it('shows a disabled/explained state when the policy document is not configured', async () => {
    renderRulesTab();
    expect(await screen.findByText('ยังไม่มีไฟล์ประกาศฉบับเต็ม')).not.toBeNull();
    expect(screen.queryByRole('button', { name: /ดาวน์โหลด/ })).toBeNull();
  });

  it('offers a working download when the policy document is configured', async () => {
    api.leave.policyDocumentAvailable.mockResolvedValue(true);
    const blob = new Blob(['%PDF-1.4'], { type: 'application/pdf' });
    api.leave.downloadPolicyDocument.mockResolvedValue(blob);

    renderRulesTab();
    const downloadButton = await screen.findByRole('button', { name: /ดาวน์โหลด/ });
    fireEvent.click(downloadButton);

    await waitFor(() => expect(downloadBlob).toHaveBeenCalledWith(blob, 'glr-leave-policy-announcement', 'pdf'));
  });

  it('surfaces the download error inline instead of throwing', async () => {
    api.leave.policyDocumentAvailable.mockResolvedValue(true);
    api.leave.downloadPolicyDocument.mockRejectedValue(new Error('Download failed'));

    renderRulesTab();
    const downloadButton = await screen.findByRole('button', { name: /ดาวน์โหลด/ });
    fireEvent.click(downloadButton);

    expect(await screen.findByText('Download failed')).not.toBeNull();
  });
});
