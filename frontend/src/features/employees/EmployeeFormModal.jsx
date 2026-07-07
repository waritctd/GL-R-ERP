import { useMemo, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { divisions, statuses } from '../../data/referenceData.js';

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

export function EmployeeFormModal({ employee, employees = [], onClose, onSubmit }) {
  const divisionOptions = useMemo(() => {
    const existing = uniqueOptions(employees, 'divisionId', 'divisionTh');
    const known = divisions.map((division) => ({ value: division.id, label: division.th }));
    const merged = new Map([...known, ...existing].map((option) => [option.value, option]));
    if (employee?.divisionId && !merged.has(employee.divisionId)) {
      merged.set(employee.divisionId, { value: employee.divisionId, label: employee.divisionTh || employee.divisionId });
    }
    return [...merged.values()];
  }, [employee, employees]);
  const defaultDivision = employee?.divisionId || divisionOptions[0]?.value || '';
  const [form, setForm] = useState(() => ({
    nameTh: employee?.nameTh || '',
    nameEn: employee?.nameEn || '',
    nickName: employee?.nickName || '',
    email: employee?.email || '',
    phone: employee?.phone || '',
    divisionId: defaultDivision,
    departmentTh: employee?.departmentTh || '',
    positionTh: employee?.positionTh || 'เจ้าหน้าที่',
    level: employee?.level || 'O2',
    salary: employee?.salary || '',
    statusId: employee?.statusId || 'ACT',
    hireDate: employee?.hireDate || '',
    locationTh: employee?.locationTh || 'สำนักงานใหญ่ กรุงเทพฯ',
    address: employee?.currentAddress?.line1 || '',
    emergencyName: employee?.emergencyContact?.name || '',
    emergencyPhone: employee?.emergencyContact?.phone || '',
  }));

  const departmentOptions = useMemo(() => uniqueOptions(
    employees.filter((item) => !form.divisionId || item.divisionId === form.divisionId),
    'departmentTh',
    'departmentTh',
  ), [employees, form.divisionId]);

  function update(field, value) {
    setForm((current) => {
      const next = { ...current, [field]: value };
      if (field === 'divisionId') {
        next.departmentTh = '';
        next.divisionTh = divisionOptions.find((division) => division.value === value)?.label;
      }
      return next;
    });
  }

  function submit(event) {
    event.preventDefault();
    const division = divisionOptions.find((item) => item.value === form.divisionId);
    onSubmit({ ...form, divisionTh: division?.label, salary: Number(form.salary || 0) });
  }

  return (
    <Modal
      title={employee ? 'แก้ไขข้อมูลพนักงาน' : 'เพิ่มพนักงาน'}
      subtitle={employee ? employee.code : 'Employee database'}
      onClose={onClose}
      footer={(
        <>
          <Button variant="secondary" type="button" onClick={onClose}>ยกเลิก</Button>
          <Button type="submit" form="employee-form">
            <Icon name="check" />
            บันทึก
          </Button>
        </>
      )}
    >
      <form id="employee-form" className="form-grid" onSubmit={submit}>
        <label>
          ชื่อ-นามสกุล
          <input value={form.nameTh} onChange={(event) => update('nameTh', event.target.value)} required />
        </label>
        <label>
          Name
          <input value={form.nameEn} onChange={(event) => update('nameEn', event.target.value)} />
        </label>
        <label>
          ชื่อเล่น
          <input value={form.nickName} onChange={(event) => update('nickName', event.target.value)} />
        </label>
        <label>
          อีเมล
          <input type="email" value={form.email} onChange={(event) => update('email', event.target.value)} required />
        </label>
        <label>
          เบอร์โทร
          <input value={form.phone} onChange={(event) => update('phone', event.target.value)} required />
        </label>
        <label>
          ฝ่าย
          <select value={form.divisionId} onChange={(event) => update('divisionId', event.target.value)}>
            {divisionOptions.map((division) => <option key={division.value} value={division.value}>{division.label}</option>)}
          </select>
        </label>
        <label>
          แผนก
          <input list="department-options" value={form.departmentTh} onChange={(event) => update('departmentTh', event.target.value)} />
          <datalist id="department-options">
            {departmentOptions.map((department) => <option key={department.value} value={department.value}>{department.label}</option>)}
          </datalist>
        </label>
        <label>
          ตำแหน่ง
          <input value={form.positionTh} onChange={(event) => update('positionTh', event.target.value)} />
        </label>
        <label>
          ระดับ
          <input value={form.level} onChange={(event) => update('level', event.target.value)} />
        </label>
        <label>
          เงินเดือน
          <input type="number" value={form.salary} onChange={(event) => update('salary', event.target.value)} />
        </label>
        <label>
          สถานะ
          <select value={form.statusId} onChange={(event) => update('statusId', event.target.value)}>
            {statuses.map((status) => <option key={status.id} value={status.id}>{status.th}</option>)}
          </select>
        </label>
        <label>
          วันที่เริ่มงาน
          <input type="date" value={form.hireDate || ''} onChange={(event) => update('hireDate', event.target.value)} />
        </label>
        <label className="span-2">
          ที่อยู่ปัจจุบัน
          <textarea value={form.address} onChange={(event) => update('address', event.target.value)} rows="2" />
        </label>
        <label>
          ผู้ติดต่อฉุกเฉิน
          <input value={form.emergencyName} onChange={(event) => update('emergencyName', event.target.value)} />
        </label>
        <label>
          เบอร์ฉุกเฉิน
          <input value={form.emergencyPhone} onChange={(event) => update('emergencyPhone', event.target.value)} />
        </label>
      </form>
    </Modal>
  );
}
