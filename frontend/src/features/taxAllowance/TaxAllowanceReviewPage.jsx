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
import {
  hasAllowanceDisagreement, resolvePayrollAllowance, taxAllowanceStatusInfo, taxAllowanceStatusShortLabel,
} from './taxAllowanceStatus.js';
import { Button } from '../../components/common/Button.jsx';

const REGISTER_GRID = 'grid-cols-[minmax(0,0.4fr)_minmax(0,1.4fr)_minmax(0,1.3fr)_minmax(0,1fr)_minmax(0,0.8fr)_minmax(0,1.1fr)_minmax(0,0.9fr)_minmax(0,0.7fr)] max-[1040px]:min-w-[900px] reflow-cards';

// `NONE` is the one chip that is NOT a backend status — it is the synthesized "this employee has
// no declaration" row, which only exists when the employee list can be enumerated. It is filtered
// out for a viewer without that access (see `visibleStatusChips` below), because for them it can
// only ever match zero rows.
//
// Labels come from `taxAllowanceStatusShortLabel` (taxAllowanceStatus.js) — the canonical map —
// rather than a literal here: this array used to carry its own copy of each label and it had
// already drifted from that map (APPROVED_UNAPPLIED and EXPIRED both read differently here than on
// the StatusBadge). `''` (ทั้งหมด) isn't a status at all, so it keeps its own literal, and
// `requiresEmployeeList` is a register-only display concern, not status vocabulary — both stay here.
const STATUS_CHIPS = [
  { key: '', label: 'ทั้งหมด' },
  { key: 'NONE', label: taxAllowanceStatusShortLabel('NONE'), requiresEmployeeList: true },
  // "register shows what payroll actually uses" (2026-08): the queue of employees whose
  // hr.employee_tax_allowance row is still reducing withholding right now but was never reviewed
  // through a declaration (taxAllowanceStatusInfo's GRANDFATHERED_APPLIED branch,
  // taxAllowanceStatus.js). Same requiresEmployeeList reasoning as NONE just above -- this key can
  // only ever land on a synthesized no-declaration row, which needs the full employee list to exist.
  { key: 'GRANDFATHERED_APPLIED', label: taxAllowanceStatusShortLabel('GRANDFATHERED_APPLIED'), requiresEmployeeList: true },
  // F6 review remediation: the MIRROR queue GRANDFATHERED_APPLIED above was missing -- employees
  // whose old allowance row lapsed (EXPIRED_UNVERIFIED, taxAllowanceStatusInfo's
  // GRANDFATHERED_EXPIRED branch) and so silently STOPPED reducing withholding. HR needs to find
  // those at least as much as the still-applying-unreviewed case; without this chip there was no way
  // to filter to them at all. Same requiresEmployeeList reasoning as the two chips above.
  { key: 'GRANDFATHERED_EXPIRED', label: taxAllowanceStatusShortLabel('GRANDFATHERED_EXPIRED'), requiresEmployeeList: true },
  { key: 'PENDING', label: taxAllowanceStatusShortLabel('PENDING') },
  { key: 'APPROVED_UNAPPLIED', label: taxAllowanceStatusShortLabel('APPROVED_UNAPPLIED') },
  { key: 'APPLIED', label: taxAllowanceStatusShortLabel('APPLIED') },
  { key: 'EXPIRED', label: taxAllowanceStatusShortLabel('EXPIRED') },
  { key: 'REJECTED', label: taxAllowanceStatusShortLabel('REJECTED') },
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
          <Button variant="secondary" onClick={onClose} disabled={busy}>ยกเลิก</Button>
          <Button onClick={() => onConfirm(month)} disabled={busy}>
            {busy ? 'กำลังดำเนินการ…' : 'ยืนยัน'}
          </Button>
        </>
      )}
    >
      <p className="confirm-dialog-message">
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
      {/* sectioned=false: keeps this modal's ORIGINAL "every group expanded" layout. It is a
          one-off compact entry for staff who never log in (decision #9) -- no evidence attachment
          exists on the on-behalf endpoint, and there is no returning-user "which section did I
          already start" need for a single-sitting form, so the #tax-allowance-sections step flow
          (TaxAllowancePage's own self-service screen) is deliberately not turned on here. */}
      <TaxAllowanceForm
        caps={caps}
        defaultValues={defaultValues}
        submitting={submitting}
        submitLabel="บันทึกและอนุมัติ"
        formId="tax-allowance-on-behalf-form"
        onSubmit={(values) => onSubmit(row.employeeId, values)}
        sectioned={false}
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
 *
 * <p><strong>"Register shows what payroll actually uses" (2026-08).</strong> A THIRD read joins in
 * here: `GET /api/payroll/tax-allowances?year=` (hr.employee_tax_allowance —
 * `PayrollReconciliationDtos.EmployeeTaxAllowanceDto`), which is what `PayrollCalculator` actually
 * reads to compute withholding and previously had no UI caller at all. It is a SEPARATE table from
 * the declaration register above, joined into `rows` below by employeeId
 * (`resolvePayrollAllowance`, taxAllowanceStatus.js) — same HR+CEO gate as the declaration register
 * (`PayrollController#getTaxAllowances`: `hasAnyRole('HR','CEO')`), so no new exposure. A
 * no-declaration employee with a live payroll row is no longer indistinguishable from one with
 * genuinely nothing (`taxAllowanceStatusInfo`'s GRANDFATHERED_APPLIED/GRANDFATHERED_EXPIRED
 * branches); the two tables can also legitimately disagree for an employee who has BOTH, which the
 * expanded row (`TaxAllowanceBreakdown`) surfaces rather than the summary badge (no 9th column).
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
  // "Register shows what payroll actually uses" (2026-08) — see this component's own header
  // comment. hr.employee_tax_allowance, NOT hr.tax_allowance_declaration; joined into `rows` below.
  const payrollAllowancesQuery = useQuery({
    queryKey: queryKeys.taxAllowances(taxYear),
    queryFn: () => api.payroll.getTaxAllowances(taxYear).then((response) => response.items || []),
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
    const payrollAllowances = payrollAllowancesQuery.data ?? [];
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

    // "Register shows what payroll actually uses" (2026-08) — joins the SECOND source in (see this
    // component's header comment). `payrollResolution` stays authoritative only when `declaration`
    // is null (taxAllowanceStatusInfo); `hasDisagreement`/`payrollAllowance` feed the expanded row
    // (TaxAllowanceBreakdown) regardless of whether a declaration exists, since the two can disagree
    // even when both are present.
    function buildRow(employeeId, employeeCode, employeeName, declaration) {
      const payrollResolution = resolvePayrollAllowance(payrollAllowances, employeeId, taxYear);
      const payrollAllowance = payrollResolution?.applying ?? payrollResolution?.expired ?? null;
      // F4 review remediation: sourced from the ONE shared predicate (taxAllowanceStatus.js) that
      // TaxAllowanceBreakdown's own expanded-row flag also reads now, rather than this file keeping
      // an inline copy that had quietly drifted from it (this copy excluded an EXPIRED_UNVERIFIED
      // `payrollAllowance` correctly; the other one didn't -- see that function's own doc comment).
      const hasDisagreement = hasAllowanceDisagreement(declaration, payrollAllowance);
      return {
        employeeId,
        employeeCode,
        employeeName,
        declaration,
        payrollAllowance,
        hasDisagreement,
        statusInfo: taxAllowanceStatusInfo(declaration, payrollResolution),
      };
    }

    if (canListEmployees && employeesQuery.data) {
      return activeEmployees.map((employee) => buildRow(
        employee.id, employee.code, employee.nameTh, currentByEmployee.get(employee.id) ?? null,
      ));
    }
    // CEO fallback (no employee-list access): one row per employee that HAS a declaration.
    return [...currentByEmployee.entries()].map(([employeeId, declaration]) => buildRow(
      employeeId, declaration.employeeCode, declaration.employeeName, declaration,
    ));
  }, [declarationsQuery.data, payrollAllowancesQuery.data, employeesQuery.data, activeEmployees, canListEmployees, taxYear]);

  const filteredRows = useMemo(
    () => (statusFilter ? rows.filter((row) => row.statusInfo.key === statusFilter) : rows),
    [rows, statusFilter],
  );

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceDeclarationsRegister({ year: taxYear }) });
    // F5 review remediation: approving/applying/reverifying a declaration can write
    // hr.employee_tax_allowance (TaxAllowanceDeclarationService#apply/#reverify -- see
    // taxAllowanceStatus.js's own comment on those two methods), which is exactly what
    // `payrollAllowancesQuery` above reads. Without this, the expanded row's payroll-side panel
    // (TaxAllowanceBreakdown) and the summary badge/disagreement flag it feeds kept showing
    // pre-action data until a manual reload -- the register going stale doing the one thing this
    // feature exists to keep honest. Bare-prefix form (not `queryKeys.taxAllowances(taxYear)`),
    // same idiom `['tickets']`/`['payroll']`/`['pricingRequests']` already use elsewhere in this
    // codebase for "invalidate the whole namespace, not just this one param combination".
    queryClient.invalidateQueries({ queryKey: ['taxAllowances'] });
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
        <Button
          variant="icon"
          aria-expanded={expandedEmployeeId === row.employeeId}
          title="ดูรายละเอียดค่าลดหย่อนเทียบเพดาน"
          aria-label={`ดูรายละเอียดค่าลดหย่อนของ ${row.employeeName} เทียบเพดาน`}
          onClick={() => setExpandedEmployeeId((current) => (current === row.employeeId ? null : row.employeeId))}
        >
          <Icon name={expandedEmployeeId === row.employeeId ? 'chevronUp' : 'chevronDown'} size={14} />
        </Button>
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
      // The disagreement flag rides in THIS column (not a 9th one — the table is tight at 1040px,
      // see REGISTER_GRID above) so the summary row still answers "is there a problem here" even
      // when a declaration exists and keeps its own ordinary badge; "what exactly" is the expanded
      // row's job (TaxAllowanceBreakdown), not this cell's.
      render: (row) => (
        <span className="inline-flex flex-wrap items-center gap-1.5">
          <StatusBadge tone={row.statusInfo.tone}>{row.statusInfo.label}</StatusBadge>
          {row.hasDisagreement ? (
            <span
              className="inline-flex items-center gap-1 text-2xs font-bold text-warning-dark"
              // F1: "ระบบเงินเดือนใช้จริง" (what payroll actually uses) would overclaim -- the
              // figure this compares against is the RAW stored hr.employee_tax_allowance total,
              // before PayrollCalculator's caps, not the post-clamp applied amount (see
              // TaxAllowanceBreakdown.jsx's own comment on `payrollTotal`). "บันทึกในระบบเงินเดือน"
              // (recorded in the payroll system) makes the same "these two disagree" point honestly.
              title="ยอดที่ประกาศไว้ในแบบ ล.ย.01 กับยอดที่บันทึกในระบบเงินเดือนไม่ตรงกัน — ดูรายละเอียดในแถวขยาย"
            >
              <Icon name="triangleAlert" size={12} />
              ยอดไม่ตรงกัน
            </span>
          ) : null}
        </span>
      ),
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
        // "ยื่นแทนพนักงาน" is a CREATE verb (a new, HR-authored, auto-approved declaration), not a
        // review verb — offering it unconditionally used to sit it in the same list as อนุมัติ/
        // ปฏิเสธ/ใช้กับเงินเดือน/ยืนยันใหม่ regardless of whether creating a fresh one made any
        // sense for the row's current state. OverflowMenu (components/common/OverflowMenu.jsx) has
        // no separator/group concept to render it as visually distinct, so the fix here is which
        // statuses offer it at all:
        //  - NONE: the only path in for staff who never log in (decision #9) — always offered.
        //  - REJECTED / EXPIRED: that submission is already settled (a final HR decision) or lapsed
        //    (inert) — refiling starts fresh, it does not discard anything live. Always offered.
        //  - APPROVED_UNAPPLIED: HR's own prior approval, not an unreviewed employee claim, and the
        //    only way to correct it before it reaches payroll (there is no "unapprove"). The modal
        //    pre-fills the existing values, so nothing is silently blind-overwritten. Kept.
        //  - PENDING: dropped. The backend (TaxAllowanceDeclarationService#createOnBehalf) clears
        //    the way by unconditionally WITHDRAWING any pending row first — so next to อนุมัติ/
        //    ปฏิเสธ, the two actions that actually decide THIS submission, "ยื่นแทนพนักงาน" would
        //    silently discard an employee's own live, awaiting-review submission with no recorded
        //    reason. HR wanting to override a pending submission should reject it (which requires a
        //    reason) and refile from REJECTED, not skip past review.
        //  - APPLIED: dropped — the declaration is already in effect, so filing a new one is not a
        //    next step at all (the clear case: this status now shows no menu, nothing to do here).
        if (status !== 'PENDING' && status !== 'APPLIED') {
          items.push({ key: 'onBehalf', label: 'ยื่นแทนพนักงาน', icon: 'userPlus', onSelect: () => setOnBehalfTarget(row) });
        }
        return <OverflowMenu items={items} label={`การดำเนินการสำหรับ ${row.employeeName}`} />;
      },
    },
  ];

  return (
    <PageStack>
      <PageHeader
        // Matches the sidebar's own pattern (AppShell.jsx: ค่าลดหย่อนภาษี / ตรวจสอบค่าลดหย่อนภาษี)
        // and the employee page's noun order (TaxAllowancePage.jsx: "ค่าลดหย่อนภาษี (แบบ ล.ย.01)")
        // instead of inverting it — both pages are titles for the same object, ล.ย.01.
        title="ตรวจสอบค่าลดหย่อนภาษี (แบบ ล.ย.01)"
        // The two audiences are looking at genuinely different tables, so they are told so. HR
        // sees one row per active employee (non-filers included, synthesized below); a viewer
        // without employee-list access sees only declarations that exist. Same honesty the
        // evidence column already practises for a viewer who cannot open attachments.
        subtitle={canListEmployees
          ? 'ใครมีค่าลดหย่อนอะไรบ้าง — ต่อการยื่นแบบ ล.ย.01 ของพนักงานทุกคน'
          // Appended, not edited in place: the original sentence stays byte-identical (existing
          // tests match it verbatim) and the new sentence states the SAME "no employee list"
          // limitation now also hides a no-declaration employee with a live payroll allowance
          // (GRANDFATHERED_APPLIED/GRANDFATHERED_EXPIRED — see this component's header comment).
          : 'เฉพาะแบบแจ้ง ล.ย.01 ที่ยื่นเข้ามาแล้ว — พนักงานที่ยังไม่ได้ยื่นจะไม่ปรากฏในตารางนี้ '
            + 'พนักงานที่มีค่าลดหย่อนเดิมจากก่อนระบบนี้แต่ไม่เคยยื่นแบบแจ้งก็จะไม่ปรากฏเช่นกัน'}
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
        loading={declarationsQuery.isLoading || payrollAllowancesQuery.isLoading || (canListEmployees && employeesQuery.isLoading)}
        error={declarationsQuery.error || payrollAllowancesQuery.error}
        onRetry={() => { declarationsQuery.refetch(); payrollAllowancesQuery.refetch(); }}
        // `rows` (pre-status-filter) vs `filteredRows` (post-filter, what DataTable actually got)
        // restores DataTable's own "(กรองจาก N)" screen-reader clause (DataTable.jsx: FIX G/F7) for
        // the zero-row case, same as TicketListPage's `unfilteredTotal={allDeals.length}`. It does
        // NOT by itself change the visible empty-state text below — DataTable's EmptyState `title`
        // never reads `unfilteredTotal` (confirmed by reading DataTable.jsx/EmptyState.jsx directly:
        // the visible `<strong>` always renders `emptyState.title` verbatim, aria-hidden so it isn't
        // announced a second time; only the separate sr-only live region gets the "(กรองจาก N)"
        // clause). The conditional title/description below is the part that fixes what a SIGHTED
        // viewer sees, same split TicketListPage.jsx already uses (`activeFilterCount > 0 ? ... :
        // ...` alongside its own `unfilteredTotal`) — the two props are a pair, not alternatives.
        unfilteredTotal={rows.length}
        emptyState={statusFilter && rows.length > 0
          ? {
            // Reachable from any chip whose bucket is empty for this tax year (e.g. REJECTED with
            // no rejections) — distinct from "no employee data at all", which used to render here
            // and claim exactly that even though `rows` (every other status) was non-empty.
            icon: 'clipboard',
            title: 'ไม่พบพนักงานที่ตรงกับตัวกรองนี้',
            description: `ลองเลือก "ทั้งหมด" เพื่อดูพนักงานทั้งหมด ${rows.length} คน`,
          }
          : {
            icon: 'clipboard',
            title: canListEmployees ? 'ไม่มีข้อมูลพนักงานในปีนี้' : 'ยังไม่มีแบบแจ้ง ล.ย.01 ในปีนี้',
          }}
        renderExpanded={(row) => (row.employeeId === expandedEmployeeId
          ? <TaxAllowanceBreakdown declaration={row.declaration} caps={caps} payrollAllowance={row.payrollAllowance} />
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
