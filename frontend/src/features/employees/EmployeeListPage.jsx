import { useMemo, useState } from 'react';
import { hasPermission } from '../../app/permissions.js';
import { Avatar } from '../../components/common/Avatar.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatThaiDate } from '../../utils/format.js';
import { statuses } from '../../data/referenceData.js';
import { EmployeeFormModal } from './EmployeeFormModal.jsx';

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

export function EmployeeListPage({ user, employees, onOpenEmployee, onCreateEmployee, loading }) {
  const [filters, setFilters] = useState({ search: '', divisionId: '', departmentTh: '', statusId: '', active: 'all' });
  const [creating, setCreating] = useState(false);
  const canCreate = hasPermission(user.role, 'canManageEmployees');

  const divisionOptions = useMemo(() => uniqueOptions(employees, 'divisionId', 'divisionTh'), [employees]);
  const departmentOptions = useMemo(() => uniqueOptions(
    filters.divisionId ? employees.filter((employee) => employee.divisionId === filters.divisionId) : employees,
    'departmentTh',
    'departmentTh',
  ), [employees, filters.divisionId]);

  const filteredEmployees = useMemo(() => {
    const query = filters.search.trim().toLowerCase();
    return employees.filter((employee) => {
      const matchQuery = !query
        || employee.nameTh?.includes(filters.search)
        || employee.nameEn?.toLowerCase().includes(query)
        || employee.code?.toLowerCase().includes(query)
        || employee.nickName?.includes(filters.search);
      const matchDivision = !filters.divisionId || employee.divisionId === filters.divisionId;
      const matchDepartment = !filters.departmentTh || employee.departmentTh === filters.departmentTh;
      const matchStatus = !filters.statusId || employee.statusId === filters.statusId;
      const matchActive = filters.active === 'all' || employee.active === (filters.active === 'active');
      return matchQuery && matchDivision && matchDepartment && matchStatus && matchActive;
    });
  }, [employees, filters]);

  function updateFilter(field, value) {
    setFilters((current) => ({
      ...current,
      [field]: value,
      ...(field === 'divisionId' ? { departmentTh: '' } : {}),
    }));
  }

  async function submitCreate(payload) {
    await onCreateEmployee(payload);
    setCreating(false);
  }

  return (
    <div className="page-stack">
      <PageHeader
        title="พนักงานทั้งหมด"
        subtitle="ค้นหา กรอง และดูข้อมูลพนักงาน"
        actions={canCreate ? (
          <button type="button" className="primary-button" onClick={() => setCreating(true)}>
            <Icon name="plus" />
            เพิ่มพนักงาน
          </button>
        ) : null}
      />

      <section className="filter-bar">
        <label className="search-field">
          <Icon name="search" />
          <input
            value={filters.search}
            onChange={(event) => updateFilter('search', event.target.value)}
            placeholder="ค้นหาชื่อ ชื่อเล่น หรือรหัสพนักงาน"
            aria-label="ค้นหาพนักงาน"
          />
        </label>
        <select aria-label="กรองตามฝ่าย" value={filters.divisionId} onChange={(event) => updateFilter('divisionId', event.target.value)}>
          <option value="">ทุกฝ่าย</option>
          {divisionOptions.map((division) => <option key={division.value} value={division.value}>{division.label}</option>)}
        </select>
        <select aria-label="กรองตามแผนก" value={filters.departmentTh} onChange={(event) => updateFilter('departmentTh', event.target.value)}>
          <option value="">ทุกแผนก</option>
          {departmentOptions.map((department) => <option key={department.value} value={department.value}>{department.label}</option>)}
        </select>
        <select aria-label="กรองตามสถานะ" value={filters.statusId} onChange={(event) => updateFilter('statusId', event.target.value)}>
          <option value="">ทุกสถานะ</option>
          {statuses.map((status) => <option key={status.id} value={status.id}>{status.th}</option>)}
        </select>
        <select aria-label="กรองตามการใช้งาน" value={filters.active} onChange={(event) => updateFilter('active', event.target.value)}>
          <option value="all">ทั้งหมด</option>
          <option value="active">ใช้งานอยู่</option>
          <option value="inactive">พ้นสภาพ</option>
        </select>
        <button
          type="button"
          className="secondary-button"
          onClick={() => {
            setFilters({ search: '', divisionId: '', departmentTh: '', statusId: '', active: 'all' });
          }}
        >
          <Icon name="refresh" />
          ล้าง
        </button>
      </section>

      <DataTable
        columns={columns}
        rows={filteredEmployees}
        getRowKey={(employee) => employee.id}
        gridClassName="employee-table"
        searchable={false}
        onRowClick={(employee) => onOpenEmployee(employee.id)}
        pageSize={12}
        loading={loading}
        emptyState={{ icon: 'users', title: 'ไม่พบพนักงาน', description: 'ลองปรับคำค้นหาหรือตัวกรอง' }}
      />

      {creating ? <EmployeeFormModal employees={employees} onClose={() => setCreating(false)} onSubmit={submitCreate} /> : null}
    </div>
  );
}
