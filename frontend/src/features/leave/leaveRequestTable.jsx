import { Button } from '../../components/common/Button.jsx';
import { expandedRowRegionId } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { leaveStatusLabel as statusInfo } from '../../utils/format.js';
import {
  formatDateRange, formatDateTime, formatDays, formatDaysOrDash,
} from './leaveFormatting.js';

// Leave-surface IA rebuild, Phase A1: shared DataTable column/expanded-row/mobile-card
// definitions for MyLeaveTab's own-request table and ReviewQueueTab's review queue --
// both used to be the SAME hand-rolled `.table-panel` / `LEAVE_TABLE_GRID` /
// `table-head` / `data-row` div grid inside the pre-A1 LeavePage.jsx (one table doing
// both jobs at once). Splitting the page into two tabs must not also fork this column
// logic into two slowly-drifting copies, so it lives here once and each tab supplies
// only what actually differs: which rows it shows and what row actions it renders.

// §5.2 leave purpose (V125): mirrors LeaveService's KNOWN_PURPOSE_CODES -- the five
// NAMED purposes plus OTHER, the always-available catch-all for the announcement's
// trailing "เป็นต้น" ("etc."). Lifted verbatim from the pre-A1 LeavePage.jsx: used by
// both the submit form (MyLeaveTab.jsx) and this module's expanded-row purposeCode
// label lookup, so both read the same copy.
export const LEAVE_PURPOSE_OPTIONS = [
  { value: 'DRIVING_LICENSE_OR_GOVERNMENT', label: 'ทำใบขับขี่ / ติดต่องานราชการ' },
  { value: 'FAMILY_NECESSITY', label: 'กิจธุระอันจำเป็นของครอบครัว' },
  { value: 'RELIGIOUS_PRACTICE', label: 'ปฏิบัติธรรมทางศาสนาตามธรรมเนียมปฏิบัติ' },
  { value: 'WEDDING', label: 'พิธีสมรส (ของตนเองหรือบุตร) — ลาได้ไม่เกิน 3 วัน' },
  { value: 'FAMILY_FUNERAL', label: 'งานศพของบุคคลในครอบครัว' },
  { value: 'OTHER', label: 'อื่นๆ' },
];

const LEAVE_PURPOSE_LABEL_BY_CODE = new Map(LEAVE_PURPOSE_OPTIONS.map((option) => [option.value, option.label]));

// 7 columns: expand toggle, when/who, type/days, reason/attachment, status, reviewed/
// note, actions. Reflows to cards below 721px via each tab's own `mobileCard` -- this
// grid only governs the >=721px table layout, same convention as the pre-A1
// LEAVE_TABLE_GRID it replaces.
export const LEAVE_REQUEST_TABLE_GRID = 'grid-cols-[minmax(0,2.25rem)_minmax(0,1.3fr)_minmax(0,1.05fr)_minmax(0,1.5fr)_minmax(0,0.95fr)_minmax(0,1.3fr)_minmax(0,1fr)] max-[1040px]:min-w-[980px] reflow-cards';

export function leaveRequestRowKey(request) {
  return request.id;
}

function DetailField({ label, value }) {
  if (value == null || value === '') return null;
  return (
    <div className="min-w-0">
      <span className="block text-xs font-bold text-text-muted">{label}</span>
      <span className="block break-words">{value}</span>
    </div>
  );
}

/**
 * Expanded-row detail surfaced via DataTable's `renderExpanded` -- the fields the pre-A1
 * table dropped on the floor entirely: paidDays/unpaidDays, quotaRemainingBefore
 * alongside quotaRemainingAfter, purposeCode, emergencyFiling, startTime/endTime,
 * reviewedAt. An AUTO_REJECTED row's systemNote is called out FIRST, on its own,
 * instead of the old third-priority fallback behind reviewerNote in a single `<small>`
 * (`reviewerNote || systemNote || requestedAt`) that could bury an auto-rejection
 * reason entirely.
 */
export function renderLeaveRequestExpanded(request) {
  const isAutoRejected = request.status === 'AUTO_REJECTED' && request.systemNote;
  return (
    <div className="grid gap-3">
      {isAutoRejected ? (
        <div className="rounded-md border border-danger-border bg-surface px-3 py-2 text-sm">
          <strong className="block text-danger">เหตุผลที่ระบบปฏิเสธอัตโนมัติ</strong>
          <span>{request.systemNote}</span>
        </div>
      ) : null}
      <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:grid-cols-3">
        <DetailField
          label="วันลาที่รับค่าจ้าง / ไม่รับค่าจ้าง"
          value={`${formatDaysOrDash(request.paidDays)} / ${formatDaysOrDash(request.unpaidDays)}`}
        />
        <DetailField
          label="โควตาก่อนคำขอ / คงเหลือหลังคำขอ"
          value={`${formatDays(request.quotaRemainingBefore)} / ${formatDays(request.quotaRemainingAfter)}`}
        />
        {request.purposeCode ? (
          <DetailField label="เหตุผลการลากิจ" value={LEAVE_PURPOSE_LABEL_BY_CODE.get(request.purposeCode) || request.purposeCode} />
        ) : null}
        <DetailField label="ลากิจฉุกเฉิน" value={request.emergencyFiling ? 'ใช่' : 'ไม่ใช่'} />
        {request.startTime || request.endTime ? (
          <DetailField label="ช่วงเวลา (ลาบางส่วนของวัน)" value={`${request.startTime || '-'} - ${request.endTime || '-'}`} />
        ) : null}
        <DetailField label="พิจารณาเมื่อ" value={formatDateTime(request.reviewedAt)} />
        {!isAutoRejected && request.reviewerNote ? (
          <DetailField label="หมายเหตุผู้พิจารณา" value={request.reviewerNote} />
        ) : null}
      </div>
    </div>
  );
}

/**
 * `expandedId`/`onToggleExpand` drive the dedicated expand-toggle column (same
 * convention CommissionPage.jsx already uses: a first, header-less column with an
 * `aria-expanded` icon button). `renderActions` lets each tab supply its own row
 * actions -- MyLeaveTab only ever shows a self-cancel button, ReviewQueueTab shows
 * approve/reject/cancel -- without this module needing to know which.
 */
export function buildLeaveRequestColumns({ expandedId, onToggleExpand, renderActions, actionsHeader = '' }) {
  return [
    {
      key: 'expand',
      header: '',
      render: (request) => {
        const expanded = expandedId === request.id;
        const subject = request.employeeName || request.employeeCode || request.employeeId;
        return (
          <Button
            type="button"
            variant="icon"
            aria-expanded={expanded}
            aria-controls={expandedRowRegionId(leaveRequestRowKey(request))}
            title={expanded ? 'ซ่อนรายละเอียด' : 'ดูรายละเอียด'}
            aria-label={`${expanded ? 'ซ่อน' : 'ดู'}รายละเอียดคำขอลาของ ${subject}`}
            onClick={() => onToggleExpand(request.id)}
          >
            <Icon name={expanded ? 'chevronUp' : 'chevronDown'} size={14} />
          </Button>
        );
      },
    },
    {
      key: 'when',
      header: 'ช่วงลา / พนักงาน',
      searchAccessor: (request) => `${request.employeeName || ''} ${request.employeeCode || ''} ${request.reason || ''}`,
      render: (request) => (
        <span>
          <strong>{formatDateRange(request.startDate, request.endDate)}</strong>
          <small>{request.employeeName || request.employeeCode || request.employeeId}</small>
        </span>
      ),
    },
    {
      key: 'type',
      header: 'ประเภท / จำนวนวัน',
      render: (request) => (
        <span>
          <strong>{request.leaveTypeNameTh || request.leaveTypeCode}</strong>
          <small>
            <span className="font-mono">{formatDays(request.totalDays)}</span>
            {' · เหลือ '}
            <span className="font-mono">{formatDays(request.quotaRemainingAfter)}</span>
          </small>
        </span>
      ),
    },
    {
      key: 'reason',
      header: 'เหตุผล / เอกสาร',
      render: (request) => (
        <span>
          <strong>{request.reason}</strong>
          <small>{request.attachmentFileName || '-'}</small>
        </span>
      ),
    },
    {
      key: 'status',
      header: 'สถานะ',
      render: (request) => {
        const status = statusInfo(request.status);
        // FIX (Phase A1): an unpaid-days row used to be indistinguishable from any
        // other APPROVED/status badge -- surface it in the COLLAPSED row too, not
        // only in the expanded detail, so it isn't buried behind an extra click.
        const hasUnpaidDays = Number(request.unpaidDays || 0) > 0;
        return (
          <span className="flex flex-col items-start gap-1">
            <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
            {hasUnpaidDays ? (
              <StatusBadge tone="warning">ไม่รับค่าจ้าง {formatDays(request.unpaidDays)}</StatusBadge>
            ) : null}
          </span>
        );
      },
    },
    {
      key: 'reviewed',
      header: 'อนุมัติ / หมายเหตุ',
      render: (request) => {
        // FIX (Phase A1): the old collapsed cell fell back through
        // `reviewerNote || systemNote || requestedAt` -- three different meanings
        // squeezed into one `<small>` with no label to say which one is showing.
        // An AUTO_REJECTED row's systemNote is now its OWN clearly-labelled line
        // here too (not just in the expanded detail), so the reason is visible
        // without opening the row at all.
        if (request.status === 'AUTO_REJECTED' && request.systemNote) {
          return (
            <span>
              <strong className="text-danger">ระบบปฏิเสธอัตโนมัติ</strong>
              <small>{request.systemNote}</small>
            </span>
          );
        }
        return (
          <span>
            <strong>{request.reviewedByName || '-'}</strong>
            <small>{request.reviewerNote || formatDateTime(request.requestedAt)}</small>
          </span>
        );
      },
    },
    {
      // Right-aligned because the number of buttons varies by row: a pending
      // request offers approve/reject/cancel, an already-decided one only
      // cancel. Left-packed, that put the last button 88px further left on the
      // shorter rows (measured 1292 vs 1380 on the HR review queue), so the
      // column read as ragged rather than as a column. Anchoring on the right
      // edge lines the trailing control up whatever the count.
      //
      // `align` rather than a wrapper: the cell is a block <td> holding the
      // buttons as inline-flex siblings, so `text-align` reaches them, and
      // DataTable applies the same class to the header — which a wrapper would
      // not have done.
      key: 'actions',
      header: actionsHeader,
      align: 'right',
      render: (request) => renderActions(request),
    },
  ];
}
