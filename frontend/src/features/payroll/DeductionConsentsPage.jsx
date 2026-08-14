import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { DataTable, expandedRowRegionId } from '../../components/common/DataTable.jsx';
import { FieldList } from '../../components/common/FieldList.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import {
  CONSENT_APPLICABLE_DEDUCTION_KINDS,
  formatThaiDate,
  payrollDeductionKindLabel,
} from '../../utils/format.js';

/**
 * HR/CEO view of the written-consent register (issue #376, exposed for #744).
 *
 * ── THIS PAGE RECORDS A FACT. IT DOES NOT GATE ANYTHING. ─────────────────────
 * `hr.deduction_written_consent` is evidentiary bookkeeping: "does HR hold a signed consent letter
 * for this employee and this deduction kind?". Nothing reads it back —
 * `DeductionWrittenConsentService`'s own javadoc and V107's `COMMENT ON TABLE` both say so
 * explicitly, and `PayrollCalculator` never touches the table. A row here neither authorises nor
 * blocks a single baht of deduction.
 *
 * That distinction is fragile in the UI layer, because every visual idiom this app has for a
 * boolean — a green/red `StatusBadge`, a check/cross glyph, a "compliant N of M" counter — is
 * borrowed from surfaces where the boolean IS a gate. So this page deliberately gives up all three:
 *
 *   1. NO StatusBadge. In this app a pill badge means a backend lifecycle status or a work-state
 *      (DESIGN.md §15); `consentOnFile` is neither, and dressing it as one would import "approved /
 *      rejected" connotations wholesale. The value renders as plain text.
 *   2. NO semantic colour. Not success-green for true, not danger-red for false. `false` is a
 *      perfectly ordinary, correct state of this register — it means "we have not got the letter" —
 *      and colouring it as a failure would assert a consequence that does not exist.
 *   3. NO completeness scorecard. A "มีหนังสือยินยอม 3 จาก 10" stat strip would read as a compliance
 *      meter counting down to something, which is exactly the false implication to avoid. The page
 *      leads with prose stating what the record is not.
 *
 * The explainer Panel is load-bearing, not decoration: it names the very value most likely to be
 * misread ("ยังไม่มีหนังสือยินยอม") and says in so many words that it stops nothing.
 *
 * ── THE REGISTER IS EMPTY IN EVERY ENVIRONMENT TODAY ─────────────────────────
 * Rows reach this table through exactly one path: the PUT this branch is also wiring up, which had
 * no client at all until now. V107 seeds nothing and no other service writes it. So the empty state
 * below is not an edge case — it is what production, UAT and a fresh mock session all show until
 * an HR user records the first row.
 */

// Read is hr + ceo (canViewPayroll, mirroring the GET's hasAnyRole('HR','CEO')); the route guard in
// permissions.js already enforces that, so this page adds no gate of its own for reading.
export function DeductionConsentsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  // Both are real server-side request parameters, not client-side narrowing — `employeeId` is
  // deep-link only (same convention as the sibling shortfall ledger), `kind` gets a control below
  // since the applicable set is only four values.
  const employeeId = searchParams.get('employeeId') || '';
  const kind = searchParams.get('kind') || '';
  // `notes` is free-typed HR prose — often the whole point of a row ("รอพนักงานเซ็นกลับ") — so it
  // must not sit inline in a cell (DESIGN.md §13) and must not be mobile-only either: the 721-1040px
  // tablet band stacks the DESKTOP cells via `reflow-cards`, so a field with no desktop column is
  // invisible there too. An expanded row is the same answer the sibling shortfall ledger uses.
  const [expandedId, setExpandedId] = useState(null);

  const consentsQuery = useQuery({
    queryKey: queryKeys.deductionConsents(employeeId, kind),
    // Keep `api.payroll.getDeductionConsents(` contiguous on one line: serverContract.test.js's
    // reachability scan greps component source for exactly that string, so wrapping the member
    // access across lines makes the endpoint look unreachable from any screen.
    queryFn: () => api.payroll.getDeductionConsents({ ...(employeeId ? { employeeId } : {}), ...(kind ? { kind } : {}) }).then((response) => response.items ?? []),
  });
  const rows = useMemo(() => consentsQuery.data ?? [], [consentsQuery.data]);

  function setKind(nextKind) {
    const next = new URLSearchParams(searchParams);
    if (nextKind) next.set('kind', nextKind); else next.delete('kind');
    setSearchParams(next, { replace: true });
  }

  const columns = [
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
      sortable: true,
      sortAccessor: (row) => row.deductionKind ?? '',
      render: (row) => payrollDeductionKindLabel(row.deductionKind),
    },
    {
      key: 'consentOnFile',
      header: 'หนังสือยินยอม',
      sortable: true,
      sortAccessor: (row) => (row.consentOnFile ? 1 : 0),
      // Plain text, no badge and no semantic colour — see the file header for why all three of the
      // usual boolean idioms are refused here. The two states are distinguished by weight and by
      // the presence of a document glyph (a letter exists / no letter yet), never by hue, so the
      // column reads identically in greyscale and carries no pass/fail charge.
      render: (row) => (row.consentOnFile ? (
        <span className="inline-flex items-center gap-1.5 font-bold text-text">
          <Icon name="fileText" size={14} className="shrink-0 text-icon-muted" />
          มีหนังสือยินยอม
        </span>
      ) : (
        <span className="text-text-muted">ยังไม่มีหนังสือยินยอม</span>
      )),
    },
    {
      key: 'consentDocumentReference',
      header: 'เลขที่เอกสาร',
      render: (row) => (row.consentDocumentReference
        ? <code className="text-xs">{row.consentDocumentReference}</code>
        : <span className="text-text-muted">-</span>),
    },
    {
      key: 'consentDate',
      header: 'วันที่ในหนังสือ',
      sortable: true,
      sortAccessor: (row) => row.consentDate ?? '',
      render: (row) => (row.consentDate ? formatThaiDate(row.consentDate) : <span className="text-text-muted">-</span>),
    },
    {
      key: 'updatedAt',
      header: 'บันทึกล่าสุด',
      sortable: true,
      sortAccessor: (row) => row.updatedAt ?? '',
      render: (row) => formatThaiDate(row.updatedAt),
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
            title={expanded ? 'ซ่อนหมายเหตุ' : 'ดูหมายเหตุ'}
            aria-label={`${expanded ? 'ซ่อน' : 'ดู'}หมายเหตุของ ${row.employeeName} (${payrollDeductionKindLabel(row.deductionKind)})`}
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
        title="ทะเบียนหนังสือยินยอมหักเงิน"
        subtitle="Written deduction consents"
      />

      <Panel>
        <div className="flex items-start gap-3">
          <Icon name="info" className="mt-[2px] shrink-0 text-icon-muted" />
          <div className="grid gap-2 text-text-secondary leading-normal">
            <p className="m-0">
              ทะเบียนนี้บันทึกว่า<strong>ฝ่ายบุคคลมีหนังสือยินยอมของพนักงานเก็บไว้ในแฟ้มหรือไม่</strong>
              สำหรับการหักเงิน 4 ประเภทที่ต้องขอความยินยอมเป็นหนังสือตาม พ.ร.บ. คุ้มครองแรงงาน ม.76
            </p>
            <p className="m-0">
              <strong>ไม่ใช่เงื่อนไขในการหักเงิน</strong> ระบบเงินเดือนไม่ได้อ่านข้อมูลในหน้านี้เลย
              การหักเงินเกิดขึ้นตามรายการที่บันทึกไว้ในหน้าเงินเดือนเท่านั้น
              การเพิ่มหรือแก้ไขรายการที่นี่ไม่เปลี่ยนสลิปเงินเดือนของใคร
            </p>
            <p className="m-0">
              โดยเฉพาะคำว่า <strong>“ยังไม่มีหนังสือยินยอม”</strong> เป็นการบันทึกข้อเท็จจริงว่ายังไม่ได้เอกสารมาเก็บเข้าแฟ้ม
              <strong>ไม่ได้ระงับหรือหยุดการหักเงินรายการใด</strong> และไม่ได้แจ้งเตือนไปยังการประมวลผลเงินเดือน
            </p>
            <p className="m-0">
              ภาษีหัก ณ ที่จ่าย ประกันสังคม และ กยศ. ไม่อยู่ในทะเบียนนี้ เพราะเป็นการหักตามกฎหมายที่ไม่ต้องขอความยินยอม
              ส่วนการอายัดตามหมายบังคับคดีเป็นคำสั่งศาล ซึ่งความยินยอมของพนักงานไม่เกี่ยวข้อง
            </p>
          </div>
        </div>
      </Panel>

      <DataTable
        columns={columns}
        rows={rows}
        getRowKey={(row) => row.id}
        // `nav-drawer:min-w-[840px] reflow-cards`: the 721-1040px tablet band sits above
        // useIsMobile's 720px breakpoint, so `mobileCard` does not apply there. Without the
        // reflow the grid's own minmax() floors would force a bare horizontal scroll at that
        // width instead of the stacked, data-label-prefixed cards every other DataTable page
        // gets — see CLAUDE.md's "tablet band hides data two opposite ways" note.
        gridClassName="grid-cols-[minmax(160px,1.4fr)_minmax(150px,1.2fr)_minmax(150px,1.1fr)_minmax(120px,0.9fr)_minmax(110px,0.8fr)_minmax(110px,0.8fr)_44px] nav-drawer:min-w-[880px] reflow-cards"
        loading={consentsQuery.isLoading}
        error={consentsQuery.isError}
        errorMessage="โหลดทะเบียนหนังสือยินยอมไม่สำเร็จ"
        retryLabel="ลองใหม่"
        onRetry={() => consentsQuery.refetch()}
        searchable
        searchPlaceholder="ค้นหาชื่อหรือรหัสพนักงาน..."
        // Mirrors DeductionWrittenConsentRepository#findAll's `ORDER BY e.employee_code,
        // c.deduction_kind`. `dir`, NOT `direction` — DataTable reads `initialSort.dir`, and the
        // wrong key fails silently into a default rather than erroring.
        initialSort={{ key: 'employee', dir: 'asc' }}
        caption="ทะเบียนหนังสือยินยอมหักเงิน"
        toolbarExtra={(
          <label className="flex items-center gap-2 text-xs font-bold text-text-secondary">
            ประเภทการหัก
            <select
              value={kind}
              onChange={(event) => setKind(event.target.value)}
              className="min-h-9 rounded-md border-[1.5px] border-border-input bg-surface px-2 py-1 text-sm font-normal text-text"
            >
              <option value="">ทั้งหมด</option>
              {CONSENT_APPLICABLE_DEDUCTION_KINDS.map((value) => (
                <option key={value} value={value}>{payrollDeductionKindLabel(value)}</option>
              ))}
            </select>
          </label>
        )}
        // Empty is the DEFAULT state of this register everywhere today, not a failure and not an
        // anomaly — nobody has ever been able to record a row. The copy says that plainly, and
        // repeats the non-consequence so an empty table is not read as "nothing is authorised".
        emptyState={{
          icon: 'clipboard',
          title: kind || employeeId ? 'ไม่มีรายการตามเงื่อนไขที่เลือก' : 'ยังไม่มีรายการในทะเบียน',
          description: kind || employeeId
            ? 'ลองล้างตัวกรองเพื่อดูรายการทั้งหมดในทะเบียน'
            : 'ยังไม่มีการบันทึกหนังสือยินยอมของพนักงานคนใดไว้ในระบบ ทะเบียนที่ว่างอยู่ไม่มีผลต่อการหักเงิน การหักเงินยังคงทำงานตามรายการในหน้าเงินเดือนตามปกติ',
        }}
        renderExpanded={(row) => (row.id !== expandedId ? null : (
          <FieldList columns={1}>
            <div>
              <dt>หมายเหตุ</dt>
              <dd className="leading-normal">{row.notes || '-'}</dd>
            </div>
            <div>
              <dt>บันทึกครั้งแรก</dt>
              <dd>{formatThaiDate(row.recordedAt)}</dd>
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
            </div>
            <span className="text-text-secondary text-sm">{payrollDeductionKindLabel(row.deductionKind)}</span>
            <FieldList columns={1}>
              <div>
                <dt>หนังสือยินยอม</dt>
                <dd className={row.consentOnFile ? 'font-bold text-text' : 'text-text-muted'}>
                  {row.consentOnFile ? 'มีหนังสือยินยอม' : 'ยังไม่มีหนังสือยินยอม'}
                </dd>
              </div>
              <div><dt>เลขที่เอกสาร</dt><dd>{row.consentDocumentReference || '-'}</dd></div>
              <div><dt>วันที่ในหนังสือ</dt><dd>{row.consentDate ? formatThaiDate(row.consentDate) : '-'}</dd></div>
              {/* Notes carry HR's own explanation of a row and must not be desktop-only — the
                  reason a letter is missing is often the whole point of the record. */}
              <div><dt>หมายเหตุ</dt><dd className="leading-normal">{row.notes || '-'}</dd></div>
              <div><dt>บันทึกล่าสุด</dt><dd>{formatThaiDate(row.updatedAt)}</dd></div>
            </FieldList>
          </div>
        )}
      />
    </PageStack>
  );
}
