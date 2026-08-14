import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { CompactStatRow } from '../../components/common/CompactStatRow.jsx';
import { DataTable, expandedRowRegionId } from '../../components/common/DataTable.jsx';
import { FieldList } from '../../components/common/FieldList.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import {
  formatMoney,
  formatThaiDate,
  formatThaiMonthYearFromMonthInputValue,
  payrollDeductionKindLabel,
} from '../../utils/format.js';

/**
 * HR/CEO read view of the garnishment shortfall ledger (issue #376, exposed for #744).
 *
 * Rows are written only by `DeductionObligationService#recordGarnishmentShortfalls` as a side
 * effect of a payroll run, and the endpoint is read-only — there is deliberately no write half
 * here. A row means the amount ordered under a หมายบังคับคดี could not be withheld in full because
 * the ป.วิ.พ. ม.302 cap applied, so the difference never reached the creditor. Nothing on this page
 * computes any of that: every figure is rendered straight from the DTO.
 *
 * This is a route of its own rather than a section of PayrollPage because the ledger is
 * cross-month by nature — the endpoint takes no month parameter and orders `payroll_month DESC`
 * across every period, while PayrollPage is organised around one selected month. Same audience
 * shape and same precedent as `/tax-allowance-review`.
 */
function monthLabel(payrollMonth) {
  if (!payrollMonth) return '-';
  // payrollMonth is a LocalDate on the wire ("YYYY-MM-DD", always the 1st of the month), so it is
  // narrowed to the "YYYY-MM" shape formatThaiMonthYearFromMonthInputValue expects.
  return formatThaiMonthYearFromMonthInputValue(payrollMonth.slice(0, 7));
}

// No `user` prop: the page has no gate of its own beyond the route's own PATH_GUARDS entry
// (canViewPayroll = hr + ceo), which mirrors the endpoint's @PreAuthorize("hasAnyRole('HR','CEO')").
export function DeductionShortfallsPage() {
  const [searchParams] = useSearchParams();
  const employeeId = searchParams.get('employeeId') || '';
  // DataTable's `renderExpanded` is caller-driven: the page owns the open row and returns null when
  // collapsed (same shape as CommissionPage and the leave tables). shortfallReason is a paragraph
  // of legal Thai, so it must never sit inline in a cell — DESIGN.md §13.
  const [expandedId, setExpandedId] = useState(null);

  const shortfallsQuery = useQuery({
    queryKey: queryKeys.deductionShortfalls(employeeId),
    // Keep `api.payroll.getDeductionShortfalls(` contiguous on one line: serverContract.test.js's
    // reachability scan greps component source for exactly that string, so wrapping the member
    // access across lines makes the endpoint look unreachable from any screen.
    queryFn: () => api.payroll.getDeductionShortfalls(employeeId ? { employeeId } : {}).then((response) => response.items ?? []),
  });
  const rows = useMemo(() => shortfallsQuery.data ?? [], [shortfallsQuery.data]);

  const totals = useMemo(() => ({
    rows: rows.length,
    employees: new Set(rows.map((row) => row.employeeId)).size,
    shortfall: rows.reduce((sum, row) => sum + Number(row.shortfallAmount ?? 0), 0),
  }), [rows]);

  const columns = [
    {
      key: 'payrollMonth',
      header: 'เดือนจ่าย',
      sortable: true,
      sortAccessor: (row) => row.payrollMonth ?? '',
      render: (row) => monthLabel(row.payrollMonth),
    },
    {
      key: 'employee',
      header: 'พนักงาน',
      sortable: true,
      sortAccessor: (row) => row.employeeCode ?? '',
      searchAccessor: (row) => `${row.employeeCode ?? ''} ${row.employeeName ?? ''}`,
      render: (row) => (
        <span className="grid">
          <strong className="truncate" title={row.employeeName}>{row.employeeName}</strong>
          <code className="text-text-muted text-2xs">{row.employeeCode}</code>
        </span>
      ),
    },
    {
      key: 'deductionKind',
      header: 'ประเภทการหัก',
      render: (row) => payrollDeductionKindLabel(row.deductionKind),
    },
    {
      key: 'requestedAmount',
      header: 'ขอหักงวดนี้',
      align: 'right',
      sortable: true,
      sortAccessor: (row) => Number(row.requestedAmount ?? 0),
      render: (row) => <span className="font-mono">{formatMoney(row.requestedAmount)}</span>,
    },
    {
      key: 'actualAmount',
      header: 'หักได้จริง',
      align: 'right',
      sortable: true,
      sortAccessor: (row) => Number(row.actualAmount ?? 0),
      render: (row) => <span className="font-mono">{formatMoney(row.actualAmount)}</span>,
    },
    {
      key: 'shortfallAmount',
      header: 'ส่วนต่างค้างหัก',
      align: 'right',
      sortable: true,
      sortAccessor: (row) => Number(row.shortfallAmount ?? 0),
      // Bold only, NOT text-danger: every row in this ledger is already a realised shortfall by
      // construction (only shortfall > 0 rows are ever written), so colouring every single one
      // "danger" red carries no differentiating information within this table — it would be
      // decoration/alarm, not signal, which DESIGN.md's colour-rationing rule (§5, "never from
      // alarm") rules out. Emphasis comes from weight instead.
      render: (row) => (
        <strong className="font-mono font-extrabold text-text">{formatMoney(row.shortfallAmount)}</strong>
      ),
    },
    {
      key: 'expand',
      header: '',
      render: (row) => {
        const expanded = expandedId === row.id;
        return (
          <Button
            type="button"
            variant="icon"
            aria-expanded={expanded}
            aria-controls={expandedRowRegionId(row.id)}
            title={expanded ? 'ซ่อนเหตุผล' : 'ดูเหตุผลที่หักไม่ครบ'}
            aria-label={`${expanded ? 'ซ่อน' : 'ดู'}เหตุผลที่หักไม่ครบของ ${row.employeeName} เดือน ${monthLabel(row.payrollMonth)}`}
            onClick={() => setExpandedId((current) => (current === row.id ? null : row.id))}
          >
            <Icon name={expanded ? 'chevronUp' : 'chevronDown'} size={14} />
          </Button>
        );
      },
    },
  ];

  return (
    <PageStack>
      <PageHeader
        title="ยอดค้างหักตามหมายบังคับคดี"
        subtitle="Deduction shortfalls"
      />

      <Panel>
        <div className="flex items-start gap-3">
          <Icon name="info" className="mt-[2px] shrink-0 text-icon-muted" />
          <div className="grid gap-2 text-text-secondary leading-normal">
            <p className="m-0">
              รายการนี้คือรอบที่ระบบ<strong>หักเงินตามหมายบังคับคดีได้ไม่ครบ</strong>ตามจำนวนที่เจ้าพนักงานบังคับคดีแจ้งมา
              เพราะติดเพดานการอายัดตาม ป.วิ.พ. ม.302 ส่วนต่างที่หักไม่ได้จึงยังไม่ได้นำส่งเจ้าหนี้
            </p>
            <p className="m-0">
              ฝ่ายบุคคลต้องพิจารณายกยอดไปหักในงวดถัดไป หรือแจ้งเจ้าพนักงานบังคับคดีให้ทราบ
            </p>
            <p className="m-0">
              ระบบบันทึกรายการเหล่านี้ให้อัตโนมัติทุกครั้งที่ประมวลผลเงินเดือน หน้านี้จึงเป็น<strong>การดูอย่างเดียว</strong>
              หากตัวเลขไม่ถูกต้อง ต้องแก้ที่ข้อมูลตั้งต้นแล้วประมวลผลเงินเดือนของเดือนนั้นใหม่
            </p>
          </div>
        </div>
      </Panel>

      <CompactStatRow
        loading={shortfallsQuery.isLoading}
        loadingCount={3}
        aria-label="กำลังโหลดสรุปยอดค้างหัก"
        items={[
          { key: 'rows', label: 'รายการค้างหัก', value: totals.rows, helper: 'Shortfall records' },
          { key: 'employees', label: 'พนักงานที่เกี่ยวข้อง', value: totals.employees, helper: 'Employees affected' },
          // Labelled "ที่แสดง" on purpose: this is a sum of the rows currently loaded, not a
          // backend-authoritative outstanding balance.
          { key: 'shortfall', label: 'รวมส่วนต่างที่แสดง', value: formatMoney(totals.shortfall), helper: 'Total shown' },
        ]}
      />

      <DataTable
        columns={columns}
        rows={rows}
        getRowKey={(row) => row.id}
        // `nav-drawer:min-w-[860px] reflow-cards`: the 721-1040px tablet band is not covered by
        // `mobileCard` (useIsMobile's breakpoint is 720px), so without this the grid's own
        // minmax() floors (804px combined) would force a bare horizontal scroll instead of the
        // stacked, data-label-prefixed reflow every other DataTable page in this app gets at that
        // width — see CLAUDE.md's "tablet band hides data two opposite ways" note.
        gridClassName="grid-cols-[minmax(110px,0.8fr)_minmax(160px,1.4fr)_minmax(150px,1.2fr)_minmax(110px,0.8fr)_minmax(110px,0.8fr)_minmax(120px,0.9fr)_44px] nav-drawer:min-w-[860px] reflow-cards"
        loading={shortfallsQuery.isLoading}
        error={shortfallsQuery.isError}
        errorMessage="โหลดรายการยอดค้างหักไม่สำเร็จ"
        retryLabel="ลองใหม่"
        onRetry={() => shortfallsQuery.refetch()}
        searchable
        searchPlaceholder="ค้นหาชื่อหรือรหัสพนักงาน..."
        // `dir`, NOT `direction` — DataTable reads `initialSort.dir === 'desc'`. The wrong key
        // fails silently into ascending order, contradicting the server's own ORDER BY.
        initialSort={{ key: 'payrollMonth', dir: 'desc' }}
        caption="ยอดค้างหักตามหมายบังคับคดี"
        // Empty is the GOOD outcome here — every ordered garnishment was withheld in full — so the
        // copy says that rather than reading like a failed load. DataTable renders the error and
        // loading states separately, so this can be unambiguous.
        emptyState={{
          icon: 'badgeCheck',
          title: 'ไม่มียอดค้างหัก',
          description: 'ทุกรอบที่ผ่านมา ระบบหักเงินตามหมายบังคับคดีได้ครบตามจำนวนที่เจ้าพนักงานบังคับคดีแจ้งไว้',
        }}
        renderExpanded={(row) => (row.id !== expandedId ? null : (
          <FieldList columns={1}>
            <div>
              <dt>เหตุผลที่หักไม่ครบ</dt>
              <dd className="leading-normal">{row.shortfallReason || '-'}</dd>
            </div>
            <div>
              <dt>รอบเงินเดือน</dt>
              <dd>{row.payrollPeriodId ? `#${row.payrollPeriodId}` : 'ไม่ระบุ'}</dd>
            </div>
            <div>
              <dt>บันทึกล่าสุด</dt>
              <dd>{formatThaiDate(row.updatedAt)}</dd>
            </div>
          </FieldList>
        ))}
        mobileCard={(row) => (
          <div className="grid gap-2">
            <div className="flex items-start justify-between gap-3">
              <span className="grid">
                <strong>{row.employeeName}</strong>
                <code className="text-text-muted text-2xs">{row.employeeCode}</code>
              </span>
              <span className="text-text-muted text-sm">{monthLabel(row.payrollMonth)}</span>
            </div>
            <span className="text-text-secondary text-sm">{payrollDeductionKindLabel(row.deductionKind)}</span>
            <FieldList columns={1}>
              <div><dt>ขอหักงวดนี้</dt><dd className="font-mono">{formatMoney(row.requestedAmount)}</dd></div>
              <div><dt>หักได้จริง</dt><dd className="font-mono">{formatMoney(row.actualAmount)}</dd></div>
              <div>
                <dt>ส่วนต่างค้างหัก</dt>
                {/* Bold only — see the desktop column's own comment on why not text-danger here. */}
                <dd className="font-mono font-extrabold text-text">{formatMoney(row.shortfallAmount)}</dd>
              </div>
              {/* The reason is the operative content of a row — a mobile card that drops it would
                  leave the reader with numbers and no instruction on what to do about them. */}
              <div><dt>เหตุผลที่หักไม่ครบ</dt><dd className="leading-normal">{row.shortfallReason || '-'}</dd></div>
            </FieldList>
          </div>
        )}
      />
    </PageStack>
  );
}
