import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatShortDate } from '../../utils/format.js';
import { TaxAllowanceBreakdown } from './TaxAllowanceBreakdown.jsx';
import { selectCurrentDeclaration, taxAllowanceStatusInfo } from './taxAllowanceStatus.js';

/**
 * Payroll drill-down (issue #387 screen 3) — embedded below the existing "ค่าลดหย่อนรวม" line in
 * PayrollPage.jsx's detail panel. `GET /declarations` has no `employeeId` filter (only
 * year/status — see the endpoint table), so this fetches the year's register once and picks this
 * one employee's row client-side, same composition TaxAllowanceReviewPage.jsx uses for the full
 * table. No new API parameter invented for this.
 */
export function TaxAllowanceDrilldown({ employeeId, employeeCode, taxYear }) {
  const registerQuery = useQuery({
    queryKey: queryKeys.taxAllowanceDeclarationsRegister({ year: taxYear }),
    queryFn: () => api.payroll.getTaxAllowanceDeclarations({ year: taxYear }).then((response) => response.items || []),
  });
  const capsQuery = useQuery({
    queryKey: queryKeys.taxAllowanceCaps(taxYear),
    queryFn: () => api.payroll.getTaxAllowanceCaps(taxYear).then((response) => response.caps || []),
  });

  const declaration = useMemo(
    () => selectCurrentDeclaration((registerQuery.data ?? []).filter((item) => item.employeeId === Number(employeeId))),
    [registerQuery.data, employeeId],
  );
  const statusInfo = taxAllowanceStatusInfo(declaration);

  if (registerQuery.isLoading) {
    return <p className="m-0 text-sm text-text-muted">กำลังโหลดข้อมูลลดหย่อนภาษี…</p>;
  }

  return (
    <div className="grid gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <StatusBadge tone={statusInfo.tone}>{statusInfo.label}</StatusBadge>
        {declaration?.expiresOn ? <span className="text-xs text-text-muted">หมดอายุ {formatShortDate(declaration.expiresOn)}</span> : null}
      </div>
      <TaxAllowanceBreakdown declaration={declaration} caps={capsQuery.data ?? []} />
      {/* `year` travels with the link: this component is rendered for whatever payroll period is
          open, and without it the register always opened on the CURRENT year — so drilling in
          from a prior-year period landed on a table that could not contain the row just clicked. */}
      <Link
        to={`/tax-allowance-review?q=${encodeURIComponent(employeeCode || '')}&year=${taxYear}`}
        className="text-sm font-bold text-primary"
      >
        ดูในหน้าตรวจสอบของ HR
      </Link>
    </div>
  );
}
