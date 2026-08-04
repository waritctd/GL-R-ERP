import { useCallback, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { FilterRow, PageStack } from '../../components/common/Layout.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { WorklistFilters } from '../../components/common/WorklistFilters.jsx';
import { formatMoney, formatShortDate } from '../../utils/format.js';
import { TaxAllowanceBreakdown } from './TaxAllowanceBreakdown.jsx';
import { TaxAllowanceEvidenceCount } from './TaxAllowanceEvidenceCount.jsx';
import { TaxAllowanceForm } from './TaxAllowanceForm.jsx';
import { buildAllowanceSubmitBody, declaredAllowanceTotal, defaultAllowanceValues } from './taxAllowanceSchema.js';
import { taxAllowanceStatusInfo } from './taxAllowanceStatus.js';
import { confirmDialogMessageClass } from '../../components/common/ConfirmDialog.jsx';

const REGISTER_GRID = 'grid-cols-[minmax(0,0.4fr)_minmax(0,1.4fr)_minmax(0,1.3fr)_minmax(0,1fr)_minmax(0,0.8fr)_minmax(0,1.1fr)_minmax(0,0.9fr)_minmax(0,0.7fr)] max-[1040px]:min-w-[900px] reflow-cards';

// `NONE` is the one chip that is NOT a backend status — it is the synthesized "this employee has
// no declaration" row, which only exists when the employee list can be enumerated. It is filtered
// out for a viewer without that access (see `visibleStatusChips` below), because for them it can
// only ever match zero rows.
const STATUS_CHIPS = [
  { key: '', label: 'ทั้งหมด' },
  { key: 'NONE', label: 'ยังไม่ได้ยื่น', requiresEmployeeList: true },
  { key: 'PENDING', label: 'รอ HR ตรวจสอบ' },
  { key: 'APPROVED_UNAPPLIED', label: 'ยังไม่ใช้กับเงินเดือน' },
  { key: 'APPLIED', label: 'ใช้กับเงินเดือนแล้ว' },
  { key: 'EXPIRED', label: 'หมดอายุ' },
  { key: 'REJECTED', label: 'ปฏิเสธ' },
];

const MONTH_OPTIONS = Array.from({ length: 12 }, (_, index) => index + 1);

function ApplyDialog({ row, onClose, onConfirm, busy }) {
  const [month, setMonth] = useState(row?.declaration?.effectiveMonth ?? new Date().getMonth() + 1);
  if (!row) return null;
  return (
    <Modal
      title="ใช้กับเงินเดือน"
      onClose={busy ? undefined : onClose}
      footer={(
        <>
          <button type="button" className="secondary-button" onClick={onClose} disabled={busy}>ยกเลิก</button>
          <button type="button" className="primary-button" onClick={() => onConfirm(month)} disabled={busy}>
            {busy ? 'กำลังดำเนินการ…' : 'ยืนยัน'}
          </button>
        </>
      )}
    >
      <p className={confirmDialogMessageClass}>
        จะเริ่มลดภาษีของ <strong>{row.employeeName}</strong> ตั้งแต่งวดเดือน {month}
      </p>
      <FormField label="งวดเดือนที่มีผล" htmlFor="apply-effective-month">
        <select id="apply-effective-month" value={month} onChange={(event) => setMonth(Number(event.target.value))}>
          {MONTH_OPTIONS.map((option) => <option key={option} value={option}>เดือน {option}</option>)}
        </select>
      </FormField>
    </Modal>
  );
}

function OnBehalfModal({ row, caps, onClose, onSubmit, submitting }) {
  if (!row) return null;
  const defaultValues = defaultAllowanceValues(row.declaration);
  return (
    <Modal title={`ยื่นแทนพนักงาน — ${row.employeeName}`} subtitle="HR เป็นผู้ยื่นแทน" onClose={onClose}>
      <TaxAllowanceForm
        caps={caps}
        defaultValues={defaultValues}
        submitting={submitting}
        submitLabel="บันทึกและอนุมัติ"
        formId="tax-allowance-on-behalf-form"
        onSubmit={(values) => onSubmit(row.employeeId, values)}
      />
    </Modal>
  );
}

/**
 * HR/CEO register (issue #387 screen 2). `GET /declarations` INNER JOINs the declaration table
 * (TaxAllowanceDeclarationRepository#findRegister: `FROM hr.tax_allowance_declaration d JOIN
 * hr.employee e`) — it never returns an employee with zero declarations, for any status filter.
 * "One row per active employee" (decision: employees with no declaration must read as
 * ยังไม่ได้ยื่น, not be invisible) is therefore composed HERE, client-side, from two existing reads:
 * the full active-employee list plus the declarations register — not a new backend endpoint.
 *
 * That composition needs `canViewEmployees` (HR only, server-enforced — EmployeeController is
 * `requireAnyRole(user, "hr")`), which CEO does not have. CEO's `canViewTaxAllowanceRegister` still
 * lets this page load and its register read still works; CEO simply cannot enumerate every
 * employee, so CEO's table shows exactly the declarations that exist (no synthesized "ยังไม่ได้ยื่น"
 * rows) rather than silently 403ing.
 *
 * <p>That degradation is now <em>stated</em> rather than left implicit. It previously kept HR's
 * subtitle ("...ของพนักงานทุกคน") and HR's "ยังไม่ได้ยื่น" filter chip — a chip that, without the
 * employee list, can only ever match zero rows — so the one question this view cannot answer
 * ("who hasn't filed?") was the one it appeared to answer with an empty table. The subtitle, the
 * chip row and the empty state all key off `canListEmployees` now.
 */
export function TaxAllowanceReviewPage({ user, showToast }) {
  const queryClient = useQueryClient();
  // `?q=` deep-links here from PayrollPage's per-employee drill-down ("a link to the review
  // screen filtered to that employee", issue #387 screen 3) — there is no employeeId filter on
  // GET /declarations to route-param instead, so this reuses the table's own search box.
  const [searchParams, setSearchParams] = useSearchParams();

  const canReview = hasPermission(user.role, 'canReviewTaxAllowances');
  const canListEmployees = hasPermission(user.role, 'canViewEmployees');

  // Year / status / search live in the URL rather than component state, so a filtered register is
  // shareable and survives a reload. The drill-down link already carried `?q=`; it now carries
  // `?year=` too (TaxAllowanceDrilldown.jsx), which this page previously ignored — drilling in
  // from a prior-year payroll period silently landed on the current year's register.
  const currentYear = new Date().getFullYear();
  const yearOptions = useMemo(
    () => [currentYear + 1, currentYear, currentYear - 1, currentYear - 2],
    [currentYear],
  );
  const requestedYear = Number(searchParams.get('year'));
  // An out-of-range or garbage `?year=` falls back to the current year instead of leaving the
  // <select> on a blank option that matches nothing in the list.
  const taxYear = yearOptions.includes(requestedYear) ? requestedYear : currentYear;
  const search = searchParams.get('q') || '';

  const visibleStatusChips = useMemo(
    () => STATUS_CHIPS.filter((chip) => canListEmployees || !chip.requiresEmployeeList),
    [canListEmployees],
  );
  const requestedStatus = searchParams.get('status') || '';
  // A `?status=NONE` deep-link handed to a viewer whose chip row does not offer NONE would
  // otherwise pin them to a permanently empty table with no visible chip to clear.
  const statusFilter = visibleStatusChips.some((chip) => chip.key === requestedStatus)
    ? requestedStatus
    : '';

  const updateParams = useCallback((patch) => {
    setSearchParams((previous) => {
      const next = new URLSearchParams(previous);
      Object.entries(patch).forEach(([key, value]) => {
        if (value === '' || value == null) next.delete(key);
        else next.set(key, String(value));
      });
      return next;
    }, { replace: true });
  }, [setSearchParams]);

  const [rejectTarget, setRejectTarget] = useState(null);
  const [approveTarget, setApproveTarget] = useState(null);
  const [applyTarget, setApplyTarget] = useState(null);
  const [reverifyTarget, setReverifyTarget] = useState(null);
  const [onBehalfTarget, setOnBehalfTarget] = useState(null);
  // Same expand-toggle idiom as CommissionPage.jsx's calc breakdown: one row expanded at a time,
  // via an explicit per-row control — DataTable's `renderExpanded` shows whatever a caller returns
  // unconditionally, so omitting this state would permanently expand every row instead of making
  // the breakdown opt-in per issue #387 ("an expandable row body").
  const [expandedEmployeeId, setExpandedEmployeeId] = useState(null);

  const capsQuery = useQuery({
    queryKey: queryKeys.taxAllowanceCaps(taxYear),
    queryFn: () => api.payroll.getTaxAllowanceCaps(taxYear).then((response) => response.caps || []),
  });
  const caps = capsQuery.data ?? [];

  const declarationsQuery = useQuery({
    queryKey: queryKeys.taxAllowanceDeclarationsRegister({ year: taxYear }),
    queryFn: () => api.payroll.getTaxAllowanceDeclarations({ year: taxYear }).then((response) => response.items || []),
  });
  // Same queryKey/queryFn shape as useHrData's own employees query (no params) — deliberately, so
  // this shares one cache entry with it instead of colliding under the same key with a
  // differently-filtered result. Filtered to active employees locally instead.
  const employeesQuery = useQuery({
    queryKey: queryKeys.employees(),
    queryFn: () => api.employees.list().then((response) => response.employees || []),
    enabled: canListEmployees,
  });
  const activeEmployees = useMemo(
    () => (employeesQuery.data ?? []).filter((employee) => employee.active !== false),
    [employeesQuery.data],
  );

  const rows = useMemo(() => {
    const declarations = declarationsQuery.data ?? [];
    // Prefer a non-terminal (not WITHDRAWN/SUPERSEDED) row over a terminal one when both exist for
    // the same employee — same "current declaration" rule as taxAllowanceStatus.selectCurrentDeclaration.
    // An employee whose only rows are terminal (e.g. a withdrawn draft) correctly ends up with NO
    // entry here, so taxAllowanceStatusInfo(null) below reports them as ยังไม่ได้ยื่น, not the raw
    // WITHDRAWN status string.
    const currentByEmployee = new Map();
    for (const item of declarations) {
      if (['WITHDRAWN', 'SUPERSEDED'].includes(item.status)) continue;
      const existing = currentByEmployee.get(item.employeeId);
      if (!existing || String(item.submittedAt || '').localeCompare(String(existing.submittedAt || '')) > 0) {
        currentByEmployee.set(item.employeeId, item);
      }
    }

    if (canListEmployees && employeesQuery.data) {
      return activeEmployees.map((employee) => {
        const declaration = currentByEmployee.get(employee.id) ?? null;
        return {
          employeeId: employee.id,
          employeeCode: employee.code,
          employeeName: employee.nameTh,
          declaration,
          statusInfo: taxAllowanceStatusInfo(declaration),
        };
      });
    }
    // CEO fallback (no employee-list access): one row per employee that HAS a declaration.
    return [...currentByEmployee.entries()].map(([employeeId, declaration]) => ({
      employeeId,
      employeeCode: declaration.employeeCode,
      employeeName: declaration.employeeName,
      declaration,
      statusInfo: taxAllowanceStatusInfo(declaration),
    }));
  }, [declarationsQuery.data, employeesQuery.data, activeEmployees, canListEmployees]);

  const filteredRows = useMemo(
    () => (statusFilter ? rows.filter((row) => row.statusInfo.key === statusFilter) : rows),
    [rows, statusFilter],
  );

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceDeclarationsRegister({ year: taxYear }) });
  }

  const approveMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.payroll.approveTaxAllowanceDeclaration(id, reviewerNote),
    onSuccess: () => { invalidate(); showToast?.('success', 'อนุมัติแบบแจ้งแล้ว'); setApproveTarget(null); },
    onError: (error) => showToast?.('error', error.message || 'อนุมัติไม่สำเร็จ'),
  });
  const rejectMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.payroll.rejectTaxAllowanceDeclaration(id, reviewerNote),
    onSuccess: () => { invalidate(); showToast?.('success', 'ปฏิเสธแบบแจ้งแล้ว'); setRejectTarget(null); },
    onError: (error) => showToast?.('error', error.message || 'ปฏิเสธไม่สำเร็จ'),
  });
  const applyMutation = useMutation({
    mutationFn: ({ id, effectiveMonth }) => api.payroll.applyTaxAllowanceDeclaration(id, effectiveMonth),
    onSuccess: () => { invalidate(); showToast?.('success', 'นำแบบแจ้งไปใช้กับเงินเดือนแล้ว'); setApplyTarget(null); },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const reverifyMutation = useMutation({
    mutationFn: (id) => api.payroll.reverifyTaxAllowanceDeclaration(id),
    onSuccess: () => { invalidate(); showToast?.('success', 'ยืนยันแบบแจ้งใหม่แล้ว'); setReverifyTarget(null); },
    onError: (error) => showToast?.('error', error.message || 'ยืนยันใหม่ไม่สำเร็จ'),
  });
  const onBehalfMutation = useMutation({
    mutationFn: ({ employeeId, values }) => api.payroll.createTaxAllowanceDeclarationOnBehalf({
      ...buildAllowanceSubmitBody(values, { taxYear, effectiveMonth: values.effectiveMonth }),
      employeeId,
    }),
    onSuccess: () => { invalidate(); showToast?.('success', 'สร้างและอนุมัติแบบแจ้งแทนพนักงานแล้ว'); setOnBehalfTarget(null); },
    onError: (error) => showToast?.('error', error.message || 'ยื่นแทนพนักงานไม่สำเร็จ'),
  });

  const columns = [
    {
      key: 'expand',
      header: '',
      render: (row) => (
        <button
          type="button"
          className="icon-button"
          aria-expanded={expandedEmployeeId === row.employeeId}
          title="ดูรายละเอียดค่าลดหย่อนเทียบเพดาน"
          aria-label={`ดูรายละเอียดค่าลดหย่อนของ ${row.employeeName} เทียบเพดาน`}
          onClick={() => setExpandedEmployeeId((current) => (current === row.employeeId ? null : row.employeeId))}
        >
          <Icon name={expandedEmployeeId === row.employeeId ? 'chevronUp' : 'chevronDown'} size={14} />
        </button>
      ),
    },
    {
      key: 'employee',
      header: 'พนักงาน',
      sortable: true,
      searchAccessor: (row) => `${row.employeeName} ${row.employeeCode}`,
      render: (row) => (
        <span>
          <strong className="block">{row.employeeName}</strong>
          <small className="text-text-muted">{row.employeeCode}</small>
        </span>
      ),
    },
    {
      key: 'status',
      header: 'สถานะ',
      render: (row) => <StatusBadge tone={row.statusInfo.tone}>{row.statusInfo.label}</StatusBadge>,
    },
    {
      key: 'declared',
      header: 'ค่าลดหย่อนที่ประกาศ',
      align: 'right',
      sortAccessor: (row) => declaredAllowanceTotal(row.declaration),
      sortable: true,
      render: (row) => <span className="font-mono">{row.declaration ? formatMoney(declaredAllowanceTotal(row.declaration)) : '-'}</span>,
    },
    {
      key: 'evidence',
      header: 'หลักฐาน (n)',
      // requireOwnerOrHr (TaxAllowanceDeclarationService) never grants CEO evidence access — a
      // personal medical/insurance/family document, distinct from the declaration's AMOUNTS CEO
      // can see via this same register. Rendering "-" rather than attempting (and silently
      // showing a wrong "0" on the inevitable 404) keeps that distinction honest for CEO viewers.
      render: (row) => (canReview
        ? <TaxAllowanceEvidenceCount declarationId={row.declaration?.declarationId ?? null} />
        : <span className="text-text-muted" title="เฉพาะฝ่ายบุคคลดูหลักฐานได้">-</span>),
    },
    {
      key: 'applied',
      header: 'ใช้กับเงินเดือน',
      render: (row) => (row.declaration?.appliedAt
        ? <span>ตั้งแต่เดือน {row.declaration.appliedEffectiveMonth}</span>
        : <span className="text-text-muted">-</span>),
    },
    {
      key: 'expires',
      header: 'หมดอายุ',
      render: (row) => (row.declaration?.expiresOn ? <span>{formatShortDate(row.declaration.expiresOn)}</span> : <span className="text-text-muted">-</span>),
    },
    {
      key: 'actions',
      header: 'การดำเนินการ',
      render: (row) => {
        if (!canReview) return null;
        const status = row.statusInfo.key;
        const items = [];
        if (status === 'PENDING') {
          items.push({ key: 'approve', label: 'อนุมัติ', icon: 'check', onSelect: () => setApproveTarget(row) });
          items.push({ key: 'reject', label: 'ปฏิเสธ', icon: 'close', tone: 'danger', onSelect: () => setRejectTarget(row) });
        }
        if (status === 'APPROVED_UNAPPLIED') {
          items.push({ key: 'apply', label: 'ใช้กับเงินเดือน', icon: 'badgeDollar', onSelect: () => setApplyTarget(row) });
        }
        if (status === 'EXPIRED') {
          items.push({ key: 'reverify', label: 'ยืนยันใหม่', icon: 'refresh', onSelect: () => setReverifyTarget(row) });
        }
        items.push({ key: 'onBehalf', label: 'ยื่นแทนพนักงาน', icon: 'userPlus', onSelect: () => setOnBehalfTarget(row) });
        return <OverflowMenu items={items} label={`การดำเนินการสำหรับ ${row.employeeName}`} />;
      },
    },
  ];

  return (
    <PageStack>
      <PageHeader
        title="ตรวจสอบแบบแจ้ง ล.ย.01 (ค่าลดหย่อนภาษี)"
        // The two audiences are looking at genuinely different tables, so they are told so. HR
        // sees one row per active employee (non-filers included, synthesized below); a viewer
        // without employee-list access sees only declarations that exist. Same honesty the
        // evidence column already practises for a viewer who cannot open attachments.
        subtitle={canListEmployees
          ? 'ใครมีค่าลดหย่อนอะไรบ้าง — ต่อการยื่นแบบ ล.ย.01 ของพนักงานทุกคน'
          : 'เฉพาะแบบแจ้ง ล.ย.01 ที่ยื่นเข้ามาแล้ว — พนักงานที่ยังไม่ได้ยื่นจะไม่ปรากฏในตารางนี้'}
      />

      <FilterRow>
        <FormField label="ปีภาษี" htmlFor="tax-allowance-year">
          <select
            id="tax-allowance-year"
            value={taxYear}
            onChange={(event) => updateParams({ year: Number(event.target.value) })}
          >
            {yearOptions.map((year) => (
              <option key={year} value={year}>{year}</option>
            ))}
          </select>
        </FormField>
        {/* `min-w-0 flex-1` so the chip row keeps its own horizontal scroller (WorklistFilters
            handles overflow + 44px touch targets internally) instead of forcing the whole
            filter row wide at 360px. */}
        <div className="min-w-0 flex-1">
          <WorklistFilters
            items={visibleStatusChips}
            activeKey={statusFilter}
            onSelect={(key) => updateParams({ status: key })}
            ariaLabel="กรองตามสถานะ"
          />
        </div>
      </FilterRow>

      <DataTable
        columns={columns}
        rows={filteredRows}
        getRowKey={(row) => row.employeeId}
        gridClassName={REGISTER_GRID}
        searchable
        searchPlaceholder="ค้นหาพนักงาน…"
        searchValue={search}
        // Merges into the existing params instead of replacing them, so typing in the search box
        // no longer wipes the year/status the viewer just chose.
        onSearchChange={(value) => updateParams({ q: value })}
        loading={declarationsQuery.isLoading || (canListEmployees && employeesQuery.isLoading)}
        error={declarationsQuery.error}
        onRetry={() => declarationsQuery.refetch()}
        emptyState={{
          icon: 'clipboard',
          title: canListEmployees ? 'ไม่มีข้อมูลพนักงานในปีนี้' : 'ยังไม่มีแบบแจ้ง ล.ย.01 ในปีนี้',
        }}
        renderExpanded={(row) => (row.employeeId === expandedEmployeeId
          ? <TaxAllowanceBreakdown declaration={row.declaration} caps={caps} />
          : null)}
      />

      <ConfirmDialog
        open={!!approveTarget}
        title="อนุมัติแบบแจ้ง"
        message={approveTarget ? `อนุมัติแบบแจ้งของ ${approveTarget.employeeName} ยอดค่าลดหย่อนที่ประกาศ ${formatMoney(declaredAllowanceTotal(approveTarget.declaration))}` : ''}
        busy={approveMutation.isPending}
        onConfirm={() => approveMutation.mutate({ id: approveTarget.declaration.declarationId })}
        onCancel={() => setApproveTarget(null)}
      />
      <ConfirmDialog
        open={!!rejectTarget}
        title="ปฏิเสธแบบแจ้ง"
        message={rejectTarget ? `ปฏิเสธแบบแจ้งของ ${rejectTarget.employeeName}` : ''}
        tone="danger"
        confirmLabel="ปฏิเสธ"
        requireReason
        reasonLabel="เหตุผลที่ปฏิเสธ"
        busy={rejectMutation.isPending}
        onConfirm={(reason) => rejectMutation.mutate({ id: rejectTarget.declaration.declarationId, reviewerNote: reason })}
        onCancel={() => setRejectTarget(null)}
      />
      <ConfirmDialog
        open={!!reverifyTarget}
        title="ยืนยันแบบแจ้งใหม่"
        message={reverifyTarget ? `ยืนยันแบบแจ้งของ ${reverifyTarget.employeeName} ใหม่ (ต่ออายุอีก 1 ปี)` : ''}
        busy={reverifyMutation.isPending}
        onConfirm={() => reverifyMutation.mutate(reverifyTarget.declaration.declarationId)}
        onCancel={() => setReverifyTarget(null)}
      />
      {applyTarget ? (
        <ApplyDialog
          row={applyTarget}
          busy={applyMutation.isPending}
          onClose={() => setApplyTarget(null)}
          onConfirm={(month) => applyMutation.mutate({ id: applyTarget.declaration.declarationId, effectiveMonth: month })}
        />
      ) : null}
      {onBehalfTarget ? (
        <OnBehalfModal
          row={onBehalfTarget}
          caps={caps}
          submitting={onBehalfMutation.isPending}
          onClose={() => setOnBehalfTarget(null)}
          onSubmit={(employeeId, values) => onBehalfMutation.mutate({ employeeId, values })}
        />
      ) : null}
    </PageStack>
  );
}
