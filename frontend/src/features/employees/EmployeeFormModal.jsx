import { useMemo } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FormGrid, formGridSpan2 } from '../../components/common/Layout.jsx';
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

const employeeFormSchema = z.object({
  nameTh: z.string().min(1, 'กรุณาระบุชื่อ-นามสกุล'),
  nameEn: z.string(),
  nickName: z.string(),
  email: z.string().min(1, 'กรุณาระบุอีเมล').pipe(z.email('รูปแบบอีเมลไม่ถูกต้อง')),
  phone: z.string().min(1, 'กรุณาระบุเบอร์โทร'),
  divisionId: z.string(),
  departmentTh: z.string(),
  positionTh: z.string(),
  level: z.string(),
  salary: z.union([z.string(), z.number()]),
  statusId: z.string(),
  hireDate: z.string(),
  locationTh: z.string(),
  address: z.string(),
  emergencyName: z.string(),
  emergencyPhone: z.string(),
});

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

  const {
    register,
    handleSubmit,
    control,
    setValue,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(employeeFormSchema),
    defaultValues: {
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
    },
    mode: 'onChange',
    reValidateMode: 'onChange',
  });

  const formDivisionId = useWatch({ control, name: 'divisionId' });
  const departmentOptions = useMemo(() => uniqueOptions(
    employees.filter((item) => !formDivisionId || item.divisionId === formDivisionId),
    'departmentTh',
    'departmentTh',
  ), [employees, formDivisionId]);

  function submit(values) {
    const division = divisionOptions.find((item) => item.value === values.divisionId);
    onSubmit({ ...values, divisionTh: division?.label, salary: Number(values.salary || 0) });
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
      <FormGrid as="form" id="employee-form" onSubmit={handleSubmit(submit)} noValidate>
        <FormField label="ชื่อ-นามสกุล" htmlFor="employee-nameTh" error={errors.nameTh?.message}>
          <input
            id="employee-nameTh"
            {...register('nameTh')}
            aria-invalid={Boolean(errors.nameTh)}
            aria-describedby={errors.nameTh ? fieldErrorId('employee-nameTh') : undefined}
            required
          />
        </FormField>
        <FormField label="Name" htmlFor="employee-nameEn">
          <input id="employee-nameEn" {...register('nameEn')} />
        </FormField>
        <FormField label="ชื่อเล่น" htmlFor="employee-nickName">
          <input id="employee-nickName" {...register('nickName')} />
        </FormField>
        <FormField label="อีเมล" htmlFor="employee-email" error={errors.email?.message}>
          <input
            id="employee-email"
            type="email"
            {...register('email')}
            aria-invalid={Boolean(errors.email)}
            aria-describedby={errors.email ? fieldErrorId('employee-email') : undefined}
            required
          />
        </FormField>
        <FormField label="เบอร์โทร" htmlFor="employee-phone" error={errors.phone?.message}>
          <input
            id="employee-phone"
            {...register('phone')}
            aria-invalid={Boolean(errors.phone)}
            aria-describedby={errors.phone ? fieldErrorId('employee-phone') : undefined}
            required
          />
        </FormField>
        <FormField label="ฝ่าย" htmlFor="employee-divisionId">
          <select id="employee-divisionId" {...register('divisionId', { onChange: () => setValue('departmentTh', '') })}>
            {divisionOptions.map((division) => <option key={division.value} value={division.value}>{division.label}</option>)}
          </select>
        </FormField>
        <FormField label="แผนก" htmlFor="employee-departmentTh">
          <input id="employee-departmentTh" list="department-options" {...register('departmentTh')} />
          <datalist id="department-options">
            {departmentOptions.map((department) => <option key={department.value} value={department.value}>{department.label}</option>)}
          </datalist>
        </FormField>
        <FormField label="ตำแหน่ง" htmlFor="employee-positionTh">
          <input id="employee-positionTh" {...register('positionTh')} />
        </FormField>
        <FormField label="ระดับ" htmlFor="employee-level">
          <input id="employee-level" {...register('level')} />
        </FormField>
        <FormField label="เงินเดือน" htmlFor="employee-salary">
          <input id="employee-salary" type="number" {...register('salary')} />
        </FormField>
        <FormField label="สถานะ" htmlFor="employee-statusId">
          <select id="employee-statusId" {...register('statusId')}>
            {statuses.map((status) => <option key={status.id} value={status.id}>{status.th}</option>)}
          </select>
        </FormField>
        <FormField label="วันที่เริ่มงาน" htmlFor="employee-hireDate">
          <input id="employee-hireDate" type="date" {...register('hireDate')} />
        </FormField>
        <div className={formGridSpan2}>
          <FormField label="ที่อยู่ปัจจุบัน" htmlFor="employee-address">
            <textarea id="employee-address" rows="2" {...register('address')} />
          </FormField>
        </div>
        <FormField label="ผู้ติดต่อฉุกเฉิน" htmlFor="employee-emergencyName">
          <input id="employee-emergencyName" {...register('emergencyName')} />
        </FormField>
        <FormField label="เบอร์ฉุกเฉิน" htmlFor="employee-emergencyPhone">
          <input id="employee-emergencyPhone" {...register('emergencyPhone')} />
        </FormField>
      </FormGrid>
    </Modal>
  );
}
