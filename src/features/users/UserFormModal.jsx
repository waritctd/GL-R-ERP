import { useState } from 'react';
import { Modal } from '../../components/common/Modal.jsx';
import { Icon } from '../../components/common/Icon.jsx';

const roles = [
  ['employee', 'EMPLOYEE · พนักงาน'],
  ['supervisor', 'SUPERVISOR · หัวหน้างาน'],
  ['hr', 'HR · ทรัพยากรบุคคล'],
  ['director', 'DIRECTOR · กรรมการ'],
  ['admin', 'ADMIN · ผู้ดูแลระบบ'],
];

export function UserFormModal({ employees, onClose, onSubmit }) {
  const [form, setForm] = useState({ employeeId: employees[0]?.id || '', email: '', role: 'employee', password: 'demo1234' });

  function update(field, value) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  function submit(event) {
    event.preventDefault();
    onSubmit({ ...form, employeeId: Number(form.employeeId) });
  }

  return (
    <Modal
      title="สร้างผู้ใช้งาน"
      subtitle="User account"
      onClose={onClose}
      footer={(
        <>
          <button type="button" className="secondary-button" onClick={onClose}>ยกเลิก</button>
          <button type="submit" form="user-form" className="primary-button">
            <Icon name="check" />
            สร้าง
          </button>
        </>
      )}
    >
      <form id="user-form" className="form-grid single" onSubmit={submit}>
        <label>
          พนักงาน
          <select value={form.employeeId} onChange={(event) => update('employeeId', event.target.value)}>
            {employees.map((employee) => <option key={employee.id} value={employee.id}>{employee.code} · {employee.nameTh}</option>)}
          </select>
        </label>
        <label>
          อีเมล
          <input type="email" value={form.email} onChange={(event) => update('email', event.target.value)} required />
        </label>
        <label>
          บทบาท
          <select value={form.role} onChange={(event) => update('role', event.target.value)}>
            {roles.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <label>
          รหัสผ่านชั่วคราว
          <input value={form.password} onChange={(event) => update('password', event.target.value)} required />
        </label>
      </form>
    </Modal>
  );
}
