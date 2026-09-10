import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FilterField } from '../../components/common/Layout.jsx';
import { downloadBlob } from '../../utils/download.js';
import { yearFrom } from './leaveFormatting.js';

const MONTH_OPTIONS = [
  { value: '', label: 'ทั้งปี' },
  { value: '1', label: 'มกราคม' },
  { value: '2', label: 'กุมภาพันธ์' },
  { value: '3', label: 'มีนาคม' },
  { value: '4', label: 'เมษายน' },
  { value: '5', label: 'พฤษภาคม' },
  { value: '6', label: 'มิถุนายน' },
  { value: '7', label: 'กรกฎาคม' },
  { value: '8', label: 'สิงหาคม' },
  { value: '9', label: 'กันยายน' },
  { value: '10', label: 'ตุลาคม' },
  { value: '11', label: 'พฤศจิกายน' },
  { value: '12', label: 'ธันวาคม' },
];

// How many years back the year select offers -- a report is a look-back document, so "this year
// and the two before it" covers the overwhelming majority of real requests (a tax-year lookup, an
// exit reconciliation) without an open-ended picker nobody needs.
const YEAR_LOOKBACK = 2;

/**
 * Printable leave-records report (รายงานสรุปใบลางาน, 2026-09) download control -- a year + optional
 * month picker and a download button, shared between MyLeaveTab.jsx (`scope="own"`, the employee's
 * own history) and TeamLeaveTab.jsx (`scope="team"`, one grouped PDF covering every direct report).
 * Neither call carries an employeeId -- see LeaveService#ownLeaveReport/#teamLeaveReport's Javadoc
 * for why the scope is entirely server-derived; this control only ever chooses WHEN, never WHO.
 */
export function LeaveReportDownload({ scope, showToast }) {
  const currentYear = yearFrom();
  const [year, setYear] = useState(String(currentYear));
  const [month, setMonth] = useState('');
  const isTeam = scope === 'team';

  const mutation = useMutation({
    mutationFn: () => {
      const params = { year: Number(year), month: month ? Number(month) : undefined };
      return isTeam ? api.leave.downloadTeamReport(params) : api.leave.downloadMyReport(params);
    },
    onSuccess: (blob) => {
      const filenameBase = isTeam ? `glr-leave-report-team-${year}` : `glr-leave-report-${year}`;
      downloadBlob(blob, filenameBase, 'pdf');
      showToast?.('success', 'ดาวน์โหลดรายงานใบลาแล้ว');
    },
    onError: (error) => showToast?.('error', error.message || 'ดาวน์โหลดรายงานใบลาไม่สำเร็จ'),
  });

  const yearOptions = Array.from({ length: YEAR_LOOKBACK + 1 }, (_, index) => currentYear - index);

  return (
    <div className="flex flex-wrap gap-[10px] items-end bg-surface border border-border rounded-md p-[14px]">
      <FilterField label="ปี">
        <select value={year} onChange={(event) => setYear(event.target.value)}>
          {yearOptions.map((optionYear) => (
            <option key={optionYear} value={optionYear}>{optionYear + 543}</option>
          ))}
        </select>
      </FilterField>
      <FilterField label="เดือน">
        <select value={month} onChange={(event) => setMonth(event.target.value)}>
          {MONTH_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
      </FilterField>
      <Button
        type="button"
        variant="secondary"
        onClick={() => mutation.mutate()}
        disabled={mutation.isPending}
      >
        <Icon name="fileText" />
        {isTeam ? 'ดาวน์โหลดรายงานทีม' : 'ดาวน์โหลดรายงานของฉัน'}
      </Button>
    </div>
  );
}
