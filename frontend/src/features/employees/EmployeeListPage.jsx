import { useMemo, useState } from 'react';
import { hasPermission } from '../../app/permissions.js';
import { Avatar } from '../../components/common/Avatar.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatThaiDate } from '../../utils/format.js';
import { departments, divisions, statuses } from '../../data/demoData.js';
import { EmployeeFormModal } from './EmployeeFormModal.jsx';

const pageSize = 8;

export function EmployeeListPage({ user, employees, onOpenEmployee, onCreateEmployee }) {
  const [filters, setFilters] = useState({ search: '', divisionId: '', departmentTh: '', statusId: '', active: 'all' });
  const [page, setPage] = useState(1);
  const [creating, setCreating] = useState(false);
  const canCreate = hasPermission(user.role, 'canManageEmployees');

  const departmentOptions = filters.divisionId
    ? departments[filters.divisionId] || []
    : Object.values(departments).flat();

  const filteredEmployees = useMemo(() => {
    const query = filters.search.trim().toLowerCase();
    return employees.filter((employee) => {
      const matchQuery = !query
        || employee.nameTh.includes(filters.search)
        || employee.nameEn.toLowerCase().includes(query)
        || employee.code.toLowerCase().includes(query)
        || employee.nickName.includes(filters.search);
      const matchDivision = !filters.divisionId || employee.divisionId === filters.divisionId;
      const matchDepartment = !filters.departmentTh || employee.departmentTh === filters.departmentTh;
      const matchStatus = !filters.statusId || employee.statusId === filters.statusId;
      const matchActive = filters.active === 'all' || employee.active === (filters.active === 'active');
      return matchQuery && matchDivision && matchDepartment && matchStatus && matchActive;
    });
  }, [employees, filters]);

  const pages = Math.max(1, Math.ceil(filteredEmployees.length / pageSize));
  const safePage = Math.min(page, pages);
  const rows = filteredEmployees.slice((safePage - 1) * pageSize, safePage * pageSize);

  function updateFilter(field, value) {
    setPage(1);
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
          />
        </label>
        <select value={filters.divisionId} onChange={(event) => updateFilter('divisionId', event.target.value)}>
          <option value="">ทุกฝ่าย</option>
          {divisions.map((division) => <option key={division.id} value={division.id}>{division.th}</option>)}
        </select>
        <select value={filters.departmentTh} onChange={(event) => updateFilter('departmentTh', event.target.value)}>
          <option value="">ทุกแผนก</option>
          {departmentOptions.map((department) => <option key={department} value={department}>{department}</option>)}
        </select>
        <select value={filters.statusId} onChange={(event) => updateFilter('statusId', event.target.value)}>
          <option value="">ทุกสถานะ</option>
          {statuses.map((status) => <option key={status.id} value={status.id}>{status.th}</option>)}
        </select>
        <select value={filters.active} onChange={(event) => updateFilter('active', event.target.value)}>
          <option value="all">ทั้งหมด</option>
          <option value="active">ใช้งานอยู่</option>
          <option value="inactive">พ้นสภาพ</option>
        </select>
        <button
          type="button"
          className="secondary-button"
          onClick={() => {
            setPage(1);
            setFilters({ search: '', divisionId: '', departmentTh: '', statusId: '', active: 'all' });
          }}
        >
          <Icon name="refresh" />
          ล้าง
        </button>
      </section>

      <section className="table-panel">
        <div className="employee-table table-head">
          <span>พนักงาน</span>
          <span>รหัส</span>
          <span>ตำแหน่ง / แผนก</span>
          <span>วันที่เริ่มงาน</span>
          <span>เงินเดือน</span>
          <span>สถานะ</span>
        </div>
        {rows.length === 0 ? (
          <EmptyState title="ไม่พบพนักงาน" description="ลองปรับคำค้นหาหรือตัวกรอง" />
        ) : rows.map((employee) => (
          <button type="button" className="employee-table table-row" key={employee.id} onClick={() => onOpenEmployee(employee.id)}>
            <span className="employee-cell">
              <Avatar employee={employee} size="sm" />
              <span>
                <strong>{employee.nameTh}</strong>
                <small>{employee.nickName} · {employee.divisionTh}</small>
              </span>
            </span>
            <code>{employee.code}</code>
            <span>
              <strong>{employee.positionTh}</strong>
              <small>{employee.departmentTh}</small>
            </span>
            <span>{formatThaiDate(employee.hireDate)}</span>
            <code>{formatMoney(employee.salary)}</code>
            <StatusBadge tone={employee.statusTone}>{employee.statusTh}</StatusBadge>
          </button>
        ))}

        <footer className="pagination">
          <span>แสดง {(safePage - 1) * pageSize + (rows.length ? 1 : 0)}-{Math.min(safePage * pageSize, filteredEmployees.length)} จาก {filteredEmployees.length}</span>
          <div>
            <button type="button" className="icon-button" onClick={() => setPage(Math.max(1, safePage - 1))} disabled={safePage === 1}>
              <Icon name="chevronLeft" />
            </button>
            <strong>{safePage} / {pages}</strong>
            <button type="button" className="icon-button" onClick={() => setPage(Math.min(pages, safePage + 1))} disabled={safePage === pages}>
              <Icon name="chevronRight" />
            </button>
          </div>
        </footer>
      </section>

      {creating ? <EmployeeFormModal onClose={() => setCreating(false)} onSubmit={submitCreate} /> : null}
    </div>
  );
}
