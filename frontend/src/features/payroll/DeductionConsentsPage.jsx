import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { FieldList } from '../../components/common/FieldList.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { DeductionConsentFormModal } from './DeductionConsentFormModal.jsx';
import { EMPLOYEE_COLUMN, expandColumn } from './deductionColumns.jsx';
import {
  CONSENT_APPLICABLE_DEDUCTION_KINDS,
  formatThaiDate,
  payrollDeductionKindLabel,
} from '../../utils/format.js';

/** HR/CEO view of the written-consent register (issue #376, exposed for #744). ── THIS PAGE RECORDS A FACT.
 * Nothing reads it back — `DeductionWrittenConsentService`'s own javadoc and V107's `COMMENT ON TABLE` both
 * say so explicitly, and `PayrollCalculator` never touches the table. */

// Read is hr + ceo (canViewPayroll, mirroring the GET's hasAnyRole('HR','CEO')); the route guard in
// permissions.js already enforces that, so this page adds no gate of its own for reading. WRITING is hr only.
export function DeductionConsentsPage({ user, showToast }) {
  const queryClient = useQueryClient();
  const canManage = hasPermission(user?.role, 'canManagePayroll');
  const [searchParams, setSearchParams] = useSearchParams();
  // null = closed; { mode: 'create' } or { mode: 'edit', row } = open.
  const [editor, setEditor] = useState(null);
  const [formError, setFormError] = useState('');
  // Both are real server-side request parameters, not client-side narrowing — `employeeId` is deep-link only
  // (same convention as the sibling shortfall ledger), `kind` gets a control below since the applicable set is
  // only four values.
  const employeeId = searchParams.get('employeeId') || '';
  const kind = searchParams.get('kind') || '';
  // `notes` is free-typed HR prose — often the whole point of a row — so it must not sit inline in a cell and
  // must not be mobile-only either: the 721-1040px tablet band stacks the DESKTOP cells via `reflow-cards`, so a
  // field with no desktop column is invisible there too.
  const [expandedId, setExpandedId] = useState(null);

  const consentsQuery = useQuery({
    queryKey: queryKeys.deductionConsents(employeeId, kind),
    // Keep `api.payroll.getDeductionConsents(` contiguous on one line: serverContract.test.js's reachability scan
    // greps component source for exactly that string, so wrapping the member access across lines makes the
    // endpoint look unreachable from any screen.
    queryFn: () => api.payroll.getDeductionConsents({ ...(employeeId ? { employeeId } : {}), ...(kind ? { kind } : {}) }).then((response) => response.items ?? []),
  });
  const rows = useMemo(() => consentsQuery.data ?? [], [consentsQuery.data]);

  // Only HR can open the editor, and only HR may call employees.list (canViewEmployees is ['hr'], and
  // EmployeeController gates it server-side), so this must not fire for a CEO viewer.
  const employeesQuery = useQuery({
    queryKey: queryKeys.employees(),
    queryFn: () => api.employees.list().then((response) => response.employees || []),
    enabled: canManage,
  });
  const activeEmployees = useMemo(
    () => (employeesQuery.data ?? []).filter((employee) => employee.active !== false),
    [employeesQuery.data],
  );

  const upsertMutation = useMutation({
    mutationFn: (payload) => api.payroll.upsertDeductionConsent(payload),
    onSuccess: () => {
      // Invalidate rather than write the response through: the PUT returns only the single row it wrote
      // (findAll(employeeId, kind)), so assigning it over the list would blank the register.
      queryClient.invalidateQueries({ queryKey: ['payroll', 'deductionConsents'] });
      setEditor(null);
      setFormError('');
      showToast?.('บันทึกข้อมูลหนังสือยินยอมแล้ว');
    },
    // Inline, not a toast: the modal stays open so the offending field is fixable without re-entering everything.
    // The backend's own Thai message (400 wrong kind, 404 unknown employee) is preserved rather than replaced by a
    // generic fallback.
    onError: (error) => setFormError(error?.message || 'บันทึกข้อมูลหนังสือยินยอมไม่สำเร็จ'),
  });

  function openEditor(next) {
    setFormError('');
    setEditor(next);
  }

  function setKind(nextKind) {
    const next = new URLSearchParams(searchParams);
    if (nextKind) next.set('kind', nextKind); else next.delete('kind');
    setSearchParams(next, { replace: true });
  }

  const columns = [
    EMPLOYEE_COLUMN,
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
      // Plain text, no badge and no semantic colour — see the file header for why all three of the usual boolean
      // idioms are refused here.
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
    // HR only — the PUT is hasRole('HR'), so a CEO viewer gets no edit affordance at all rather than a control
    // that 403s on submit.
    ...(canManage ? [{
      key: 'edit',
      header: '',
      render: (row) => (
        <Button
          type="button"
          variant="icon"
          title="แก้ไขบันทึก"
          aria-label={`แก้ไขบันทึกหนังสือยินยอมของ ${row.employeeName} (${payrollDeductionKindLabel(row.deductionKind)})`}
          onClick={() => openEditor({ mode: 'edit', row })}
        >
          <Icon name="pencil" size={14} />
        </Button>
      ),
    }] : []),
    expandColumn({
      expandedId,
      setExpandedId,
      hideTitle: 'ซ่อนหมายเหตุ',
      showTitle: 'ดูหมายเหตุ',
      labelFor: (row) => `หมายเหตุของ ${row.employeeName} (${payrollDeductionKindLabel(row.deductionKind)})`,
    }),
  ];

  return (
    <PageStack>
      <PageHeader
        title="ทะเบียนหนังสือยินยอมหักเงิน"
        subtitle="Written deduction consents"
        actions={canManage ? (
          <Button type="button" variant="primary" onClick={() => openEditor({ mode: 'create' })}>
            <Icon name="plus" size={15} />
            บันทึกหนังสือยินยอม
          </Button>
        ) : null}
      />

      <Panel>
        <div className="flex items-start gap-3">
          <Icon name="info" className="mt-[2px] shrink-0 text-icon-muted" />
          <div className="grid gap-2 text-text-secondary leading-normal">
            <p className="m-0">
              {/* Explicit {' '} after a that ends a line: JSX strips the newline between an element and the text line
               * following it, which would run two Thai SENTENCES together. Thai has no inter-word spaces but does separate
               * clauses this way. */}
              ทะเบียนนี้บันทึกว่า<strong>ฝ่ายบุคคลมีหนังสือยินยอมของพนักงานเก็บไว้ในแฟ้มหรือไม่</strong>{' '}
              สำหรับการหักเงิน 4 ประเภทที่ต้องขอความยินยอมเป็นหนังสือตาม พ.ร.บ. คุ้มครองแรงงาน ม.76
            </p>
            <p className="m-0">
              <strong>ไม่ใช่เงื่อนไขในการหักเงิน</strong> ระบบเงินเดือนไม่ได้อ่านข้อมูลในหน้านี้เลย
              การหักเงินเกิดขึ้นตามรายการที่บันทึกไว้ในหน้าเงินเดือนเท่านั้น
              การเพิ่มหรือแก้ไขรายการที่นี่ไม่เปลี่ยนสลิปเงินเดือนของใคร
            </p>
            <p className="m-0">
              โดยเฉพาะคำว่า <strong>“ยังไม่มีหนังสือยินยอม”</strong> เป็นการบันทึกข้อเท็จจริงว่ายังไม่ได้เอกสารมาเก็บเข้าแฟ้ม{' '}
              <strong>ไม่ได้ระงับหรือหยุดการหักเงินรายการใด</strong> และไม่ได้แจ้งเตือนไปยังการประมวลผลเงินเดือน
            </p>
            <p className="m-0">
              ภาษีหัก ณ ที่จ่าย ประกันสังคม และ กยศ. ไม่อยู่ในทะเบียนนี้ เพราะเป็นการหักตามกฎหมายที่ไม่ต้องขอความยินยอม
              ส่วนการอายัดตามหมายบังคับคดีเป็นคำสั่งศาล ซึ่งความยินยอมของพนักงานไม่เกี่ยวข้อง
            </p>
          </div>
        </div>
      </Panel>

      {/* Above DataTable's own search input, rather than via DataTable's `toolbarExtra` — that prop renders INLINE
       * alongside the search field in the same flex-wrap row, so on a narrow viewport it wraps BELOW the search box
       * only because it comes second in DOM order, not clearly above it. */}
      <label className="mb-3 flex flex-col gap-1">
        <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">ประเภทการหัก</span>
        <select
          value={kind}
          onChange={(event) => setKind(event.target.value)}
          className="min-h-9 mobile:min-h-[44px] w-full max-w-xs rounded-md border-[1.5px] border-border-input bg-surface px-2 py-1 text-sm font-normal text-text"
        >
          <option value="">ทั้งหมด</option>
          {CONSENT_APPLICABLE_DEDUCTION_KINDS.map((value) => (
            <option key={value} value={value}>{payrollDeductionKindLabel(value)}</option>
          ))}
        </select>
      </label>

      <DataTable
        columns={columns}
        rows={rows}
        getRowKey={(row) => row.id}
        // Two separate mechanisms, for two separate bands — verified in a real browser at 768px rather than assumed,
        // because the obvious reading of `reflow-cards` is wrong: ≤720px `reflow-cards` + `mobileCard`. Both strings
        // below must stay whole and greppable.
        gridClassName={canManage
          ? 'grid-cols-[minmax(160px,1.4fr)_minmax(150px,1.2fr)_minmax(150px,1.1fr)_minmax(120px,0.9fr)_minmax(110px,0.8fr)_minmax(110px,0.8fr)_44px_44px] nav-drawer:min-w-[924px] reflow-cards'
          : 'grid-cols-[minmax(160px,1.4fr)_minmax(150px,1.2fr)_minmax(150px,1.1fr)_minmax(120px,0.9fr)_minmax(110px,0.8fr)_minmax(110px,0.8fr)_44px] nav-drawer:min-w-[880px] reflow-cards'}
        loading={consentsQuery.isLoading}
        error={consentsQuery.isError}
        errorMessage="โหลดทะเบียนหนังสือยินยอมไม่สำเร็จ"
        retryLabel="ลองใหม่"
        onRetry={() => consentsQuery.refetch()}
        searchable
        searchPlaceholder="ค้นหาชื่อหรือรหัสพนักงาน..."
        // Mirrors DeductionWrittenConsentRepository#findAll's `ORDER BY e.employee_code, c.deduction_kind`. `dir`, NOT
        // `direction` — DataTable reads `initialSort.dir`, and the wrong key fails silently into a default rather than
        // erroring.
        initialSort={{ key: 'employee', dir: 'asc' }}
        caption="ทะเบียนหนังสือยินยอมหักเงิน"
        // Empty is the DEFAULT state of this register everywhere today, not a failure and not an anomaly — nobody has
        // ever been able to record a row. The copy says that plainly, and repeats the non-consequence so an empty
        // table is not read as "nothing is authorised".
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
              {/* Notes carry HR's own explanation of a row and must not be desktop-only — the reason a letter is missing is
               * often the whole point of the record. */}
              <div><dt>หมายเหตุ</dt><dd className="leading-normal">{row.notes || '-'}</dd></div>
              <div><dt>บันทึกล่าสุด</dt><dd>{formatThaiDate(row.updatedAt)}</dd></div>
            </FieldList>
          </div>
        )}
      />

      {editor ? (
        <DeductionConsentFormModal
          mode={editor.mode}
          row={editor.row}
          employees={activeEmployees}
          employeesLoading={employeesQuery.isLoading}
          busy={upsertMutation.isPending}
          formError={formError}
          onClose={() => openEditor(null)}
          onSubmit={(payload) => upsertMutation.mutate(payload)}
        />
      ) : null}
    </PageStack>
  );
}
