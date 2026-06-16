import { useState } from 'react';
import { Avatar } from '../../components/common/Avatar.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatShortDate, roleLabel } from '../../utils/format.js';
import { UserFormModal } from './UserFormModal.jsx';

export function UserManagementPage({ users, employees, onCreateUser, onUpdateUser }) {
  const [creating, setCreating] = useState(false);
  const activeCount = users.filter((user) => user.active).length;

  async function submitCreate(payload) {
    await onCreateUser(payload);
    setCreating(false);
  }

  return (
    <div className="page-stack">
      <PageHeader
        title="จัดการผู้ใช้งาน"
        subtitle={`${users.length} บัญชี · ใช้งานอยู่ ${activeCount}`}
        actions={(
          <button type="button" className="primary-button" onClick={() => setCreating(true)}>
            <Icon name="userPlus" />
            สร้างผู้ใช้งาน
          </button>
        )}
      />

      <section className="table-panel">
        <div className="user-table table-head">
          <span>ผู้ใช้งาน</span>
          <span>อีเมล</span>
          <span>บทบาท</span>
          <span>วันที่สร้าง</span>
          <span>สถานะ</span>
          <span>การจัดการ</span>
        </div>
        {users.map((user) => {
          const employee = employees.find((item) => item.id === user.employeeId);
          return (
            <div className="user-table table-row" key={user.id}>
              <span className="employee-cell">
                <Avatar employee={employee} name={user.name} size="sm" />
                <span>
                  <strong>{user.name}</strong>
                  <small>{employee?.code || '-'}</small>
                </span>
              </span>
              <code>{user.email}</code>
              <StatusBadge tone={user.role === 'admin' ? 'indigo' : user.role === 'hr' ? 'teal' : 'neutral'}>{roleLabel(user.role)}</StatusBadge>
              <span>{formatShortDate(user.createdAt)}</span>
              <StatusBadge tone={user.active ? 'success' : 'neutral'}>{user.active ? 'ใช้งาน' : 'ปิดใช้งาน'}</StatusBadge>
              <span className="row-actions">
                <button type="button" className="secondary-button" onClick={() => onUpdateUser(user.id, { active: !user.active })}>
                  {user.active ? 'ปิดใช้งาน' : 'เปิดใช้งาน'}
                </button>
              </span>
            </div>
          );
        })}
      </section>

      {creating ? <UserFormModal employees={employees} onClose={() => setCreating(false)} onSubmit={submitCreate} /> : null}
    </div>
  );
}
