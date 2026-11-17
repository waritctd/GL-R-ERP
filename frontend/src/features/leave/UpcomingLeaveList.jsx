import { useMemo } from 'react';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { leaveStatusLabel as statusInfo } from '../../utils/format.js';
import { formatDateRange } from './leaveFormatting.js';
import { PendingApproverNote } from './leaveRequestTable.jsx';

// Leave-surface IA restructure (2026-08-10). A "calendar" that can only show the past is a name describing an
// intention, not the data. Extracted from the two BYTE-IDENTICAL "ปฏิทินวันลา" panels that lived inline in
// MyLeaveTab.jsx and TeamLeaveTab.jsx.

const ACTIVE_STATUSES = ['SUBMITTED', 'APPROVED'];

/** Forward-looking list of a person's (or a team's) approved and still-pending leave. */
export function UpcomingLeaveList({
  title,
  requests,
  loading = false,
  emptyTitle = 'ยังไม่มีวันลาที่กำลังจะถึง',
  emptyDescription,
  showEmployee = false,
  limit = 8,
}) {
  const items = useMemo(
    () => requests
      .filter((request) => ACTIVE_STATUSES.includes(request.status))
      .slice()
      .sort((first, second) => first.startDate.localeCompare(second.startDate))
      .slice(0, limit),
    [requests, limit],
  );

  return (
    <Panel title={title}>
      <div className="grid gap-2.5">
        {loading ? (
          <div className="grid gap-2.5" aria-busy="true" aria-label="กำลังโหลดวันลาที่กำลังจะถึง">
            {Array.from({ length: 3 }, (_, index) => (
              <Skeleton key={index} height={38} />
            ))}
          </div>
        ) : items.length === 0 ? (
          <EmptyState icon="calendar" title={emptyTitle} description={emptyDescription} />
        ) : items.map((request) => {
          const status = statusInfo(request.status);
          return (
            <div
              // Ported off the legacy `.leave-calendar-item` class (styles.css), which set `white-space: nowrap;
              // text-overflow: ellipsis` on the nested strong/small and so CLIPPED long leave-type and employee names -- the
              // same defect class as the table sub-lines this restructure fixes globally.
              className="flex items-start justify-between gap-[14px] min-w-0 border-b border-surface-subtle py-2.5 text-sm text-text-secondary last:border-b-0"
              key={request.id}
            >
              <span className="min-w-0">
                <strong className="block break-words font-bold text-text">
                  {formatDateRange(request.startDate, request.endDate)}
                </strong>
                <small className="block break-words text-xs text-text-muted">
                  {showEmployee ? `${request.employeeName || request.employeeCode} · ` : ''}
                  {request.leaveTypeNameTh || request.leaveTypeCode}
                </small>
              </span>
              <span className="flex min-w-0 flex-col items-end gap-1">
                <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                <PendingApproverNote request={request} />
              </span>
            </div>
          );
        })}
      </div>
    </Panel>
  );
}
