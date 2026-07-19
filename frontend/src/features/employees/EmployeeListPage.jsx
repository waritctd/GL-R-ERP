import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { hasPermission } from '../../app/permissions.js';
import { Avatar } from '../../components/common/Avatar.jsx';
import { Button } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FilterBar, PageStack } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { formatMoney, formatThaiDate } from '../../utils/format.js';
import { statuses } from '../../data/referenceData.js';
import { EmployeeFormModal } from './EmployeeFormModal.jsx';

// Employment status is a single axis. `statusId` already carries everything the
// old boolean `active` select did — the backend derives `active` as
// `!"RSG".equals(statusId)` (EmployeeStatus.java) — so the two selects that used
// to sit side by side asked the same question twice and could be combined into
// states that match nothing. One select, keyed on `statusId` alone.
//
// `ปฏิบัติงานอยู่` (still on duty) is deliberately distinct from ACT's own
// `ทำงานปกติ`, which means *not on probation*; reusing that label would make the
// default view and the ACT option read identically while returning different rows.
const CURRENT_STATUS_IDS = ['ACT', 'PRB'];
const DEFAULT_STATUS = 'current';

const STATUS_FILTER_OPTIONS = [
  { value: DEFAULT_STATUS, label: 'ปฏิบัติงานอยู่' },
  ...statuses.map((status) => ({ value: status.id, label: status.th })),
  { value: 'all', label: 'ทั้งหมด' },
];

const DEFAULT_SORT = 'name.asc';

const SORT_OPTIONS = [
  { value: 'name.asc', label: 'ชื่อ ก-ฮ' },
  { value: 'name.desc', label: 'ชื่อ ฮ-ก' },
  { value: 'hireDate.desc', label: 'วันที่เริ่มงาน ใหม่ → เก่า' },
  { value: 'hireDate.asc', label: 'วันที่เริ่มงาน เก่า → ใหม่' },
  { value: 'salary.desc', label: 'เงินเดือน มาก → น้อย' },
  { value: 'salary.asc', label: 'เงินเดือน น้อย → มาก' },
];

function isKnown(options, value) {
  return options.some((option) => option.value === value);
}

function uniqueOptions(rows, valueKey, labelKey) {
  const options = new Map();
  rows.forEach((row) => {
    const value = row[valueKey];
    const label = row[labelKey] || value;
    if (value && label && !options.has(value)) {
      options.set(value, { value, label });
    }
  });
  return [...options.values()].sort((a, b) => a.label.localeCompare(b.label, 'th'));
}

const columns = [
  {
    key: 'name',
    header: 'พนักงาน',
    sortable: true,
    sortAccessor: (employee) => employee.nameTh,
    searchAccessor: (employee) => employee.nameTh,
    render: (employee) => (
      <span className="employee-cell">
        <Avatar employee={employee} size="sm" />
        <span>
          <strong>{employee.nameTh}</strong>
          <small>{employee.nickName} · {employee.divisionTh}</small>
        </span>
      </span>
    ),
  },
  {
    key: 'code',
    header: 'รหัส',
    render: (employee) => <code>{employee.code}</code>,
  },
  {
    key: 'position',
    header: 'ตำแหน่ง / แผนก',
    render: (employee) => (
      <span>
        <strong>{employee.positionTh}</strong>
        <small>{employee.departmentTh}</small>
      </span>
    ),
  },
  {
    key: 'hireDate',
    header: 'วันที่เริ่มงาน',
    sortable: true,
    sortAccessor: (employee) => employee.hireDate,
    render: (employee) => <span>{formatThaiDate(employee.hireDate)}</span>,
  },
  {
    key: 'salary',
    header: 'เงินเดือน',
    sortable: true,
    sortAccessor: (employee) => employee.salary,
    render: (employee) => <code>{formatMoney(employee.salary)}</code>,
  },
  {
    key: 'status',
    header: 'สถานะ',
    render: (employee) => <StatusBadge tone={employee.statusTone}>{employee.statusTh}</StatusBadge>,
  },
];

/**
 * Mobile record card for one employee. The desktop grid has six columns, and the
 * legacy `.reflow-cards` fallback stacked each of them as its own labelled row —
 * about 266px tall, roughly four people per phone screen. This folds the same
 * fields into three lines (~100px) ordered for a lookup scan: who they are, what
 * they do, then the two figures HR actually reaches for.
 *
 * Card mode has no column header to click, so sorting would silently become
 * desktop-only; the page carries its own เรียงตาม control in the filter panel
 * rather than dropping the affordance.
 */
function EmployeeCard({ employee }) {
  return (
    <>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <span className="flex min-w-0 items-center gap-2">
          <Avatar employee={employee} size="xs" />
          <strong className="min-w-0 truncate text-sm font-extrabold text-text">
            {employee.nameTh}
          </strong>
        </span>
        <span className="shrink-0">
          <StatusBadge tone={employee.statusTone}>{employee.statusTh}</StatusBadge>
        </span>
      </div>

      <span className="min-w-0 truncate text-xs text-text-muted">
        {[employee.nickName, employee.positionTh, employee.departmentTh].filter(Boolean).join(' · ')}
      </span>

      <span className="flex min-w-0 items-baseline justify-between gap-3">
        <span className="min-w-0 truncate text-2xs text-text-muted">
          {[employee.code, formatThaiDate(employee.hireDate)].filter(Boolean).join(' · ')}
        </span>
        <span className="shrink-0 text-sm font-extrabold text-text">
          {formatMoney(employee.salary)}
        </span>
      </span>
    </>
  );
}

export function EmployeeListPage({ user, employees, onCreateEmployee, loading }) {
  const navigate = useNavigate();
  const isMobile = useIsMobile();
  const [searchParams, setSearchParams] = useSearchParams();
  const [creating, setCreating] = useState(false);
  // Desktop has room to show the controls outright; a phone does not, so it
  // opens collapsed and leans on the summary chips below the toggle.
  const [filtersOpen, setFiltersOpen] = useState(() => !isMobile);
  const canCreate = hasPermission(user.role, 'canManageEmployees');

  const search = searchParams.get('q') ?? '';
  const divisionId = searchParams.get('div') ?? '';
  const departmentTh = searchParams.get('dept') ?? '';
  // An unknown value in a hand-edited or stale URL falls back to the default
  // rather than filtering everything out and looking like an empty database.
  const statusParam = searchParams.get('status') ?? DEFAULT_STATUS;
  const status = isKnown(STATUS_FILTER_OPTIONS, statusParam) ? statusParam : DEFAULT_STATUS;
  const sortParam = searchParams.get('sort') ?? DEFAULT_SORT;
  const sortValue = isKnown(SORT_OPTIONS, sortParam) ? sortParam : DEFAULT_SORT;
  const [sortKey, sortDir] = sortValue.split('.');

  function updateParams(changes) {
    setSearchParams((previous) => {
      const next = new URLSearchParams(previous);
      Object.entries(changes).forEach(([key, value]) => {
        if (value) next.set(key, value);
        else next.delete(key);
      });
      // Departments are scoped to a division, so a stale dept would silently
      // empty the list after switching ฝ่าย.
      if ('div' in changes) next.delete('dept');
      return next;
    }, { replace: true });
  }

  const divisionOptions = useMemo(() => uniqueOptions(employees, 'divisionId', 'divisionTh'), [employees]);
  const departmentOptions = useMemo(() => uniqueOptions(
    divisionId ? employees.filter((employee) => employee.divisionId === divisionId) : employees,
    'departmentTh',
    'departmentTh',
  ), [employees, divisionId]);

  const filteredEmployees = useMemo(() => {
    const query = search.trim().toLowerCase();
    return employees.filter((employee) => {
      // Every accessor uses the same normalised term. nameTh and nickName used
      // to match the raw input while nameEn and code matched a trimmed one, so a
      // trailing space — routine on a phone keyboard or a paste — returned
      // nothing while the box still showed text.
      const matchQuery = !query
        || employee.nameTh?.toLowerCase().includes(query)
        || employee.nameEn?.toLowerCase().includes(query)
        || employee.code?.toLowerCase().includes(query)
        || employee.nickName?.toLowerCase().includes(query);
      const matchDivision = !divisionId || employee.divisionId === divisionId;
      const matchDepartment = !departmentTh || employee.departmentTh === departmentTh;
      const matchStatus = status === 'all'
        || (status === DEFAULT_STATUS
          ? CURRENT_STATUS_IDS.includes(employee.statusId)
          : employee.statusId === status);
      return matchQuery && matchDivision && matchDepartment && matchStatus;
    });
  }, [employees, search, divisionId, departmentTh, status]);

  // Chips stand in for the panel while it is closed, so a filtered list never
  // looks like an unfiltered one. The status chip shows for every value except
  // ทั้งหมด — including the default — because "resigned staff are hidden" is
  // exactly what a reader would otherwise miss.
  const activeChips = [
    status !== 'all' && {
      key: 'status',
      label: STATUS_FILTER_OPTIONS.find((option) => option.value === status)?.label,
      onRemove: () => updateParams({ status: 'all' }),
    },
    divisionId && {
      key: 'div',
      label: divisionOptions.find((option) => option.value === divisionId)?.label || divisionId,
      onRemove: () => updateParams({ div: '' }),
    },
    departmentTh && {
      key: 'dept',
      label: departmentTh,
      onRemove: () => updateParams({ dept: '' }),
    },
    sortValue !== DEFAULT_SORT && {
      key: 'sort',
      label: SORT_OPTIONS.find((option) => option.value === sortValue)?.label,
      onRemove: () => updateParams({ sort: '' }),
    },
  ].filter(Boolean);

  // Keyed off the URL rather than the chips: the status chip shows even at the
  // default (it is what hides resigned staff), so counting chips would offer a
  // ล้าง button on an untouched page that clears nothing.
  const hasAnyFilter = [...searchParams.keys()].length > 0;

  async function submitCreate(payload) {
    await onCreateEmployee(payload);
    setCreating(false);
  }

  return (
    <PageStack>
      <PageHeader
        title="พนักงานทั้งหมด"
        subtitle="ค้นหา กรอง และดูข้อมูลพนักงาน"
        actions={canCreate ? (
          <Button type="button" onClick={() => setCreating(true)}>
            <Icon name="plus" />
            เพิ่มพนักงาน
          </Button>
        ) : null}
      />

      <FilterBar>
        <div className="flex w-full flex-wrap items-center gap-[10px]">
          <label className="relative flex-1 min-w-[240px] [&_svg]:absolute [&_svg]:left-3 [&_svg]:top-1/2 [&_svg]:-translate-y-1/2 [&_svg]:text-text-faint [&_input]:pl-10">
            <Icon name="search" />
            <input
              value={search}
              onChange={(event) => updateParams({ q: event.target.value })}
              placeholder="ค้นหาชื่อ ชื่อเล่น หรือรหัสพนักงาน"
              aria-label="ค้นหาพนักงาน"
            />
          </label>
          <Button
            type="button"
            variant="secondary"
            className="max-[720px]:flex-1"
            id="employee-filter-toggle"
            aria-expanded={filtersOpen}
            aria-controls="employee-filter-panel"
            onClick={() => setFiltersOpen((open) => !open)}
          >
            ตัวกรอง
            {activeChips.length > 0 ? (
              <span className="ml-1 inline-flex min-w-[18px] items-center justify-center rounded-pill bg-primary px-[5px] text-2xs font-extrabold text-white">
                {activeChips.length}
              </span>
            ) : null}
            <Icon name={filtersOpen ? 'chevronUp' : 'chevronDown'} />
          </Button>
          {hasAnyFilter ? (
            <Button
              type="button"
              variant="secondary"
              className="max-[720px]:flex-1"
              onClick={() => setSearchParams(new URLSearchParams(), { replace: true })}
            >
              <Icon name="refresh" />
              ล้าง
            </Button>
          ) : null}
        </div>

        {!filtersOpen && activeChips.length > 0 ? (
          <div className="flex w-full flex-wrap items-center gap-2">
            {activeChips.map((chip) => (
              <button
                key={chip.key}
                type="button"
                onClick={chip.onRemove}
                className="inline-flex items-center gap-1.5 rounded-pill border border-border bg-surface-subtle px-2.5 py-1 text-xs text-text-secondary hover:bg-surface-hover"
              >
                {chip.label}
                <Icon name="close" />
                <span className="sr-only">ลบตัวกรอง</span>
              </button>
            ))}
          </div>
        ) : null}

        {filtersOpen ? (
          <div
            id="employee-filter-panel"
            role="region"
            aria-labelledby="employee-filter-toggle"
            className="grid w-full grid-cols-1 gap-[10px] border-t border-border pt-[14px] sm:grid-cols-2 lg:grid-cols-4"
          >
            <label className="grid gap-1 text-sm font-bold text-text-secondary">
              สถานะ
              <select value={status} onChange={(event) => updateParams({ status: event.target.value })}>
                {STATUS_FILTER_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </label>
            <label className="grid gap-1 text-sm font-bold text-text-secondary">
              ฝ่าย
              <select value={divisionId} onChange={(event) => updateParams({ div: event.target.value })}>
                <option value="">ทุกฝ่าย</option>
                {divisionOptions.map((division) => (
                  <option key={division.value} value={division.value}>{division.label}</option>
                ))}
              </select>
            </label>
            <label className="grid gap-1 text-sm font-bold text-text-secondary">
              แผนก
              <select value={departmentTh} onChange={(event) => updateParams({ dept: event.target.value })}>
                <option value="">ทุกแผนก</option>
                {departmentOptions.map((department) => (
                  <option key={department.value} value={department.value}>{department.label}</option>
                ))}
              </select>
            </label>
            <label className="grid gap-1 text-sm font-bold text-text-secondary">
              เรียงตาม
              <select value={sortValue} onChange={(event) => updateParams({ sort: event.target.value })}>
                {SORT_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </label>
          </div>
        ) : null}
      </FilterBar>

      <DataTable
        columns={columns}
        rows={filteredEmployees}
        getRowKey={(employee) => employee.id}
        gridClassName="grid-cols-[minmax(0,2.2fr)_minmax(0,0.9fr)_minmax(0,1.7fr)_minmax(0,1.1fr)_minmax(0,1fr)_minmax(0,0.9fr)] max-[1040px]:min-w-[900px]"
        searchable={false}
        sort={{ key: sortKey, dir: sortDir }}
        onSortChange={(next) => updateParams({ sort: next ? `${next.key}.${next.dir}` : '' })}
        mobileCard={(employee) => <EmployeeCard employee={employee} />}
        onRowClick={(employee) => navigate(`/employees/${employee.id}`)}
        pageSize={12}
        loading={loading}
        emptyState={{ icon: 'users', title: 'ไม่พบพนักงาน', description: 'ลองปรับคำค้นหาหรือตัวกรอง' }}
      />

      {creating ? <EmployeeFormModal employees={employees} onClose={() => setCreating(false)} onSubmit={submitCreate} /> : null}
    </PageStack>
  );
}
