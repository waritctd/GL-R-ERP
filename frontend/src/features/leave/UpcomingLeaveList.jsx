import { useMemo } from 'react';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { leaveStatusLabel as statusInfo } from '../../utils/format.js';
import { formatDateRange } from './leaveFormatting.js';
import { PendingApproverNote } from './leaveRequestTable.jsx';

// Leave-surface IA restructure (2026-08-10). Extracted from the two BYTE-IDENTICAL "ปฏิทินวันลา"
// panels that lived inline in MyLeaveTab.jsx and TeamLeaveTab.jsx. That duplication is the reason
// defect D3 existed at all: one heading string, two copies, two different scopes, and a rename
// applied to one copy would have silently left the other behind.
// See .design/leave-surface-ia/INFORMATION_ARCHITECTURE.md.
//
// ── Why this is "upcoming" and no longer a "calendar" ────────────────────────
// The panels this replaces were titled ปฏิทินวันลา ("leave calendar") but were structurally
// INCAPABLE of showing upcoming leave (defect D1). Both tabs defaulted their filter to
// `to: todayIso()`, and LeaveRepository#findRequests matches `lr.start_date <= :toDate`, so any
// leave starting after today was excluded from the query before the panel ever saw a row. A
// "calendar" that can only show the past is a name describing an intention, not the data.
//
// The fix is not the rename on its own: this panel must be fed by its own FORWARD window, which is
// why callers pass a separately-queried `requests` list rather than reusing the history filter's
// result. If it shared the history query again, applying a past date range would empty it -- an
// "upcoming" panel that vanishes when you look at last month.
//
// Sorted ASCENDING (soonest first), deliberately opposite to the history table's newest-first
// order: "what is coming up" reads forward from today, "what happened" reads backward from today.

const ACTIVE_STATUSES = ['SUBMITTED', 'APPROVED'];

/**
 * Forward-looking list of a person's (or a team's) approved and still-pending leave.
 *
 * `requests` should already be windowed by the caller's own forward query -- this component filters
 * to the active statuses, orders soonest-first and caps the row count, but it does NOT date-filter,
 * because it has no opinion about how far ahead "upcoming" reaches for a given caller.
 *
 * `showEmployee` controls the secondary line: the team/company views need to say WHOSE leave a row
 * is, the personal view does not (in `ของฉัน` every row is the viewer's own, so printing their own
 * name on all eight is noise).
 */
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
              // Ported off the legacy `.leave-calendar-item` class (styles.css), which set
              // `white-space: nowrap; text-overflow: ellipsis` on the nested strong/small and so
              // CLIPPED long leave-type and employee names -- the same defect class as the table
              // sub-lines this restructure fixes globally. The replacement below deliberately
              // wraps instead: this is a short, low-density list where a second line costs
              // nothing and a truncated name costs the reader the answer.
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
