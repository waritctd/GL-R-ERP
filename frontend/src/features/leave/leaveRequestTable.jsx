import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { DataTable, expandedRowRegionId } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { downloadBlob } from '../../utils/download.js';
import { leaveStatusLabel as statusInfo, pendingApproverText } from '../../utils/format.js';
import {
  formatDateRange, formatDateTime, formatDays, formatDaysOrDash,
} from './leaveFormatting.js';

// Shared DataTable column/expanded-row/mobile-card definitions for MyLeaveTab's own-request table
// and ReviewQueueTab's review queue. They live here once so the tabs don't fork this column logic
// into two drifting copies; each tab supplies only which rows it shows and what row actions it
// renders.

// §5.2 leave purpose: mirrors LeaveService's KNOWN_PURPOSE_CODES -- the five NAMED purposes plus
// OTHER, the always-available catch-all for the announcement's trailing "เป็นต้น" ("etc."). Used
// by both the submit form (MyLeaveTab.jsx) and this module's expanded-row purposeCode label lookup.
export const LEAVE_PURPOSE_OPTIONS = [
  { value: 'DRIVING_LICENSE_OR_GOVERNMENT', label: 'ทำใบขับขี่ / ติดต่องานราชการ' },
  { value: 'FAMILY_NECESSITY', label: 'กิจธุระอันจำเป็นของครอบครัว' },
  { value: 'RELIGIOUS_PRACTICE', label: 'ปฏิบัติธรรมทางศาสนาตามธรรมเนียมปฏิบัติ' },
  { value: 'WEDDING', label: 'พิธีสมรส (ของตนเองหรือบุตร) — ลาได้ไม่เกิน 3 วัน' },
  { value: 'FAMILY_FUNERAL', label: 'งานศพของบุคคลในครอบครัว' },
  { value: 'OTHER', label: 'อื่นๆ' },
];

const LEAVE_PURPOSE_LABEL_BY_CODE = new Map(LEAVE_PURPOSE_OPTIONS.map((option) => [option.value, option.label]));

// 7 columns: expand toggle, when/who, type/days, reason/attachment, status, reviewed/note,
// actions. Reflows to cards below 721px via each tab's own `mobileCard`; this grid only governs
// the >=721px table layout.
// The สถานะ column takes a MIN-WIDTH FLOOR (6.75rem), not `minmax(0,…)` like its siblings.
//
// Its content is a StatusBadge pill, which cannot shrink: the badge carries `max-width: 100%`
// (styles.css `.data-row > td > .status-badge`), but its TEXT still needs min-content, so the cell
// clips instead of reflowing. At 0.95fr the longest leave status, "โควตาไม่พอ" (AUTO_REJECTED),
// needed 82px in a 74px track and lost its last character.
//
// It shows only at 1041px: `nav-drawer`/`tablet` are max-width variants capped at 1040 and `lg:`
// goes live at 1041, so 1041 is the FIRST width where the desktop column widths apply (see
// [[tablet-lg-breakpoint-overlap-1024-1040]]).
//
// A floor rather than a bigger `fr`: `fr` is proportional, so the clip would just move to a
// narrower width. Safe because this grid only applies at >=721px (`reflow-cards` switches to
// mobile cards under that) and already carries `nav-drawer:min-w-[980px]`.
export const LEAVE_REQUEST_TABLE_GRID = 'grid-cols-[minmax(0,2.25rem)_minmax(0,1.3fr)_minmax(0,1.05fr)_minmax(0,1.5fr)_minmax(6.75rem,0.95fr)_minmax(0,1.3fr)_minmax(0,1fr)] nav-drawer:min-w-[980px] reflow-cards';

export function leaveRequestRowKey(request) {
  return request.id;
}

/** True when `viewer` IS the person this row is waiting on -- so the note below would spend a line telling them
 * their own name. Only the self-referential case is suppressed. */
function pendingOnViewer(request, viewer) {
  if (!viewer) return false;
  if (request.pendingApproverRole === 'manager') {
    return request.managerEmployeeId != null
      && Number(request.managerEmployeeId) === Number(viewer.employeeId);
  }
  if (request.pendingApproverRole === 'hr') return hasPermission(viewer.role, 'canReviewLeave');
  return false;
}

/**
 * "Who this is waiting on" -- rendered beside a SUBMITTED status badge, in the shared table column
 * below and in each tab's own mobileCard/calendar-list render (MyLeaveTab.jsx, TeamLeaveTab.jsx,
 * ReviewQueueTab.jsx -- none of those funnel their StatusBadge through this module, so they each
 * render this component directly). Renders nothing for any other status, or when the backend could
 * not resolve a pendingApproverRole for the row.
 *
 * No "รอ" ("waiting for") prefix here: the adjacent status badge already says that (รออนุมัติ);
 * repeating it made this note's text a superset of the badge's own and broke e2e locators that
 * matched on the badge's exact label. Bare "CEO (คุณราม)" reads fine next to a "รออนุมัติ" badge.
 *
 * `viewer` (optional) suppresses the note when it would name the person reading it -- see
 * pendingOnViewer above. Passed by the two APPROVER-side surfaces (ReviewQueueTab, TeamLeaveTab),
 * where a manager's own queue otherwise repeated "ผู้จัดการ (คุณนา)" on every single pending row;
 * deliberately NOT passed by MyLeaveTab, where "who is this sitting with" is the one thing the
 * requester most wants to know and can never be about themselves.
 */
export function PendingApproverNote({ request, viewer }) {
  if (request.status !== 'SUBMITTED') return null;
  if (pendingOnViewer(request, viewer)) return null;
  const text = pendingApproverText(request.pendingApproverRole, request.pendingApproverName);
  if (!text) return null;
  return <small className="text-text-muted">{text}</small>;
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
 * Expanded-row detail surfaced via DataTable's `renderExpanded`: paidDays/unpaidDays,
 * quotaRemainingBefore alongside quotaRemainingAfter, purposeCode, emergencyFiling,
 * startTime/endTime, reviewedAt. An AUTO_REJECTED row's systemNote is called out FIRST, on its
 * own, so it cannot be buried behind reviewerNote.
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
      {/* The FULL reason, first and full-width: the collapsed row's "เหตุผล / เอกสาร" cell is
          width-constrained and cuts long reasons, so the expanded row must show the complete text.
          Spans the full grid (`col-span-full`): a sentence needs the width. */}
      <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:grid-cols-3">
        <div className="col-span-full min-w-0">
          <DetailField label="เหตุผล" value={request.reason} />
        </div>
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
          // Partial-day span: startDate/endDate may differ for a timed request, so the label/value
          // read as "13:00 (วันเริ่ม) - 17:30 (วันสิ้นสุด)" (the formatDateRange(...) above already
          // gives the actual dates); a single-day request keeps the plain "13:00 - 17:30".
          <DetailField
            label={request.startDate === request.endDate ? 'ช่วงเวลา (ลาบางส่วนของวัน)' : 'ช่วงเวลา (วันเริ่ม - วันสิ้นสุด)'}
            value={`${request.startTime || '-'} - ${request.endTime || '-'}`}
          />
        ) : null}
        <DetailField label="พิจารณาเมื่อ" value={formatDateTime(request.reviewedAt)} />
        {!isAutoRejected && request.reviewerNote ? (
          <DetailField label="หมายเหตุผู้พิจารณา" value={request.reviewerNote} />
        ) : null}
      </div>
    </div>
  );
}

/** `expandedId`/`onToggleExpand` drive the dedicated expand-toggle column (same convention CommissionPage.jsx
 * already uses: a first, header-less column with an `aria-expanded` icon button). */
export function buildLeaveRequestColumns({
  expandedId, onToggleExpand, renderActions, actionsHeader = '', viewer,
}) {
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
          {/* Two figures that wrap as whole phrases. The sub-line was one inline run, so "0.38 วัน ·
              เหลือ 6.37 วัน" wrapped after "เหลือ" and orphaned "6.37 วัน". A `flex-wrap` row makes
              the FIRST break opportunity the one between the two figures; each figure then wraps
              internally only if it still does not fit, and `formatDays`' no-break spaces keep every
              number with its unit (worst case "1 ชั่วโมง 45 นาที · เหลือ 28 วัน 6 ชั่วโมง 15 นาที"
              is wider than this column at every viewport). `min-w-0` lets the items shrink instead
              of holding their min-content width and overflowing the cell's `overflow: clip`.

              No "·" separator: it is either stranded at the end of a line or orphaned at the start
              of the next, and "เหลือ" already tells the two figures apart. `tabular-nums` rather
              than `font-mono`, which lines up Thai glyphs too. */}
          <small className="flex flex-wrap items-baseline gap-x-2 tabular-nums">
            <span className="min-w-0">{formatDays(request.totalDays)}</span>
            {/* No-break space after the label, as `formatDays` does after every number: stops
                "เหลือ" being stranded from its figure. Written as an escape, not a literal NBSP,
                so it survives an editor that normalises whitespace. */}
            <span className="min-w-0">เหลือ{'\u00A0'}{formatDays(request.quotaRemainingAfter)}</span>
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
        // Surface unpaid days in the COLLAPSED row too, not only in the expanded detail, so it
        // isn't buried behind an extra click.
        const hasUnpaidDays = Number(request.unpaidDays || 0) > 0;
        return (
          <span className="flex flex-col items-start gap-1">
            <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
            <PendingApproverNote request={request} viewer={viewer} />
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
        // An AUTO_REJECTED row's systemNote gets its OWN clearly-labelled line (not a
        // `reviewerNote || systemNote || requestedAt` fallback squeezed into one `<small>`), so the
        // reason is visible without opening the row.
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
      // Right-aligned because the number of buttons varies by row (a pending request offers
      // approve/reject/cancel, a decided one only cancel); left-packed, the trailing control did not
      // line up and the column read as ragged. `align` rather than a wrapper: the cell is a block
      // <td> holding inline-flex buttons, so `text-align` reaches them, and DataTable applies the
      // same class to the header, which a wrapper would not.
      key: 'actions',
      header: actionsHeader,
      align: 'right',
      render: (request) => renderActions(request),
    },
  ];
}

function isPermissionError(error) {
  return error?.status === 403;
}

// A rejected/auto-rejected request's own retry link: one of the composer's three named deep-link
// entry points (LeaveRequestPage.jsx's doc comment lists all three). Prefills the type/dates the
// rejected request used, so the employee fixes the ONE thing the system flagged instead of
// re-deciding everything.
function retryLeaveRequestHref(request) {
  const params = new URLSearchParams({
    type: request.leaveTypeCode,
    start: request.startDate,
    end: request.endDate,
  });
  return `/leave/new?${params.toString()}`;
}

const noSummaryPrefix = () => '';

/**
 * The request table shared by the "ของฉัน" and team tabs: DataTable (loading skeleton / real rows /
 * pagination / error+retry) plus a StatePanel for the three zero-row cases: `filtered`
 * (non-default filters applied, offers a clear-filters action), `empty` (teaching copy, no data
 * at all yet), and `denied` (the request errored with a 403). `emptyState` is the tab's own
 * teaching copy; `viewer`/`summaryPrefix` are the only per-tab row differences.
 */
export function LeaveRequestsSection({
  requestsQuery, rows, hasCustomFilters, onClearFilters, expandedId, onToggleExpand, onCancel, user, showToast,
  viewer, emptyState, summaryPrefix = noSummaryPrefix,
}) {
  const navigate = useNavigate();
  // The medical-certificate download for the requester's OWN expanded row (the reviewer-side
  // equivalent lives in ReviewQueueTab.jsx). Local to this section since only this table's
  // expanded row needs it.
  const [downloadingAttachmentId, setDownloadingAttachmentId] = useState(null);
  async function downloadAttachment(request) {
    setDownloadingAttachmentId(request.id);
    try {
      const blob = await api.leave.downloadAttachment(request.attachmentId);
      downloadBlob(blob, `leave-attachment-${request.id}`, 'pdf');
    } catch (error) {
      showToast('error', error.message || 'ดาวน์โหลดเอกสารไม่สำเร็จ');
    } finally {
      setDownloadingAttachmentId(null);
    }
  }
  const loading = requestsQuery.isPending;
  const denied = requestsQuery.isError && isPermissionError(requestsQuery.error);
  const hasError = requestsQuery.isError && !denied;

  const columns = useMemo(() => buildLeaveRequestColumns({
    expandedId,
    onToggleExpand,
    viewer,
    renderActions: (request) => {
      const isOwn = Number(request.employeeId) === Number(user.employeeId);
      const canCancel = isOwn && request.status === 'SUBMITTED';
      const canRetry = isOwn && ['AUTO_REJECTED', 'REJECTED'].includes(request.status);
      if (!canCancel && !canRetry) return null;
      return (
        <span className="flex items-center gap-1">
          {canRetry ? (
            <Button
              type="button"
              variant="icon"
              title="ยื่นคำขอใหม่"
              aria-label="ยื่นคำขอใหม่"
              onClick={() => navigate(retryLeaveRequestHref(request))}
            >
              <Icon name="refresh" size={14} />
            </Button>
          ) : null}
          {canCancel ? (
            <Button type="button" variant="icon" title="ยกเลิก" aria-label="ยกเลิก" onClick={() => onCancel(request.id)}>
              <Icon name="ban" size={14} />
            </Button>
          ) : null}
        </span>
      );
    },
  }), [expandedId, onToggleExpand, onCancel, user, viewer, navigate]);

  function mobileCard(request) {
    const status = statusInfo(request.status);
    const isOwn = Number(request.employeeId) === Number(user.employeeId);
    const canCancel = isOwn && request.status === 'SUBMITTED';
    const canRetry = isOwn && ['AUTO_REJECTED', 'REJECTED'].includes(request.status);
    const expanded = expandedId === request.id;
    // Same collapsed-row unpaid-days badge as the shared desktop status column and ReviewQueueTab.jsx's
    // mobile card; reads `unpaidDays` (a §5 WARN gate's unpaidByRuleDays is always a SUBSET of it,
    // per LeaveRequestDto's Javadoc). "Part of my leave will be unpaid" matters on the requester's
    // OWN history view as much as on the approver's queue.
    const hasUnpaidDays = Number(request.unpaidDays || 0) > 0;
    return (
      <>
        <div className="flex min-w-0 items-start justify-between gap-3">
          {/* Wraps, never truncates — see ReviewQueueTab.jsx's own comment: `truncate` cut the
              Buddhist-era year off the end date at 390px, which reads as a complete date. */}
          <strong className="min-w-0 text-sm font-extrabold text-text">
            {formatDateRange(request.startDate, request.endDate)}
          </strong>
          <span className="flex shrink-0 items-start gap-1.5">
            {/* Stacked vertically, never inline: two badges on one line in a narrow column force
                truncation, and stacking needs no breakpoint variant. */}
            <span className="flex flex-col items-start gap-1">
              <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
              {hasUnpaidDays ? (
                <StatusBadge tone="warning">ไม่รับค่าจ้าง {formatDays(request.unpaidDays)}</StatusBadge>
              ) : null}
            </span>
            <Button
              type="button"
              variant="icon"
              aria-expanded={expanded}
              aria-controls={expandedRowRegionId(request.id)}
              title={expanded ? 'ซ่อนรายละเอียด' : 'ดูรายละเอียด'}
              aria-label={expanded ? 'ซ่อนรายละเอียด' : 'ดูรายละเอียด'}
              onClick={() => onToggleExpand(request.id)}
            >
              <Icon name={expanded ? 'chevronUp' : 'chevronDown'} size={14} />
            </Button>
          </span>
        </div>
        <PendingApproverNote request={request} viewer={viewer} />
        <span className="min-w-0 truncate text-xs text-text-muted">
          {summaryPrefix(request)}{request.leaveTypeNameTh || request.leaveTypeCode} · {formatDays(request.totalDays)}
        </span>
        <span className="min-w-0 truncate text-xs text-text-muted">{request.reason}</span>
        {canRetry ? (
          <Button
            type="button"
            variant="secondary"
            className="mt-1 min-h-11"
            onClick={() => navigate(retryLeaveRequestHref(request))}
          >
            <Icon name="refresh" size={14} />
            ยื่นคำขอใหม่
          </Button>
        ) : null}
        {canCancel ? (
          <Button type="button" variant="secondary" className="mt-1 min-h-11" onClick={() => onCancel(request.id)}>
            <Icon name="ban" size={14} />
            ยกเลิกคำขอ
          </Button>
        ) : null}
      </>
    );
  }

  if (!loading && denied) {
    return (
      <StatePanel
        state="denied"
        description={requestsQuery.error?.message || 'ไม่มีสิทธิ์เข้าถึงรายการนี้'}
      />
    );
  }

  if (!loading && !hasError && rows.length === 0) {
    return hasCustomFilters ? (
      <StatePanel
        state="filtered"
        action={<Button type="button" variant="secondary" onClick={onClearFilters}>ล้างตัวกรอง</Button>}
      />
    ) : emptyState;
  }

  return (
    <DataTable
      columns={columns}
      rows={rows}
      getRowKey={leaveRequestRowKey}
      gridClassName={LEAVE_REQUEST_TABLE_GRID}
      // DataTable brings its OWN `<Panel flush>` card and this table is wrapped in a titled
      // `<Panel flush>`, so two 1px borders would sit adjacent (a card inside a card). Every other
      // DataTable caller renders it bare; these two leave tabs keep the outer panel only for its
      // section title, so the inner card's chrome goes.
      //
      // Scroll is deliberately NOT set here: Panel's own `flush` default handles the 980px grid
      // floor. See ReviewQueueTab.jsx's comment at the same spot.
      panelClassName="rounded-none border-0"
      loading={loading}
      error={hasError}
      onRetry={() => requestsQuery.refetch()}
      mobileCard={mobileCard}
      renderExpanded={(request) => {
        if (expandedId !== request.id) return null;
        return (
          <>
            {renderLeaveRequestExpanded(request)}
            {request.attachmentId ? (
              <div className="mt-3 flex flex-wrap items-center justify-between gap-3 rounded-md border border-border bg-surface px-3 py-2">
                <span className="inline-flex min-w-0 items-center gap-2 text-sm text-text">
                  <Icon name="fileText" size={16} className="text-icon-muted" />
                  <span className="min-w-0 truncate">{request.attachmentFileName || 'เอกสารแนบ'}</span>
                </span>
                <Button
                  type="button"
                  variant="secondary"
                  disabled={downloadingAttachmentId === request.id}
                  onClick={() => downloadAttachment(request)}
                >
                  <Icon name="fileText" size={14} />
                  {downloadingAttachmentId === request.id ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลด'}
                </Button>
              </div>
            ) : null}
          </>
        );
      }}
      showPagination
    />
  );
}
