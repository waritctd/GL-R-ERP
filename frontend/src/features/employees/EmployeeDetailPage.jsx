import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { hasPermission } from '../../app/permissions.js';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Avatar } from '../../components/common/Avatar.jsx';
import { Breadcrumbs } from '../../components/common/Breadcrumbs.jsx';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { DetailHero, FieldList, InfoGrid } from '../../components/common/FieldList.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatMoney, formatThaiDate } from '../../utils/format.js';
import { EmployeeFormModal } from './EmployeeFormModal.jsx';

const tabDefs = [
  { id: 'personal', label: 'ข้อมูลส่วนตัว', icon: 'user' },
  { id: 'employment', label: 'การจ้างงาน', icon: 'briefcase' },
  { id: 'history', label: 'ประวัติ', icon: 'clock' },
  { id: 'sensitive', label: 'ข้อมูลอ่อนไหว', icon: 'shield', restricted: true },
];

export function EmployeeDetailPage({ user, onUpdateEmployee }) {
  const { id } = useParams();
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState('personal');
  const [editing, setEditing] = useState(false);
  const canManage = hasPermission(user.role, 'canManageEmployees');
  const canSeeSensitive = user.role === 'hr';

  const onBack = () => navigate('/employees');

  const employeeQuery = useQuery({
    queryKey: queryKeys.employeeDetail(id),
    queryFn: () => api.employees.get(id).then((response) => response.employee),
    enabled: !!id,
  });
  const employee = employeeQuery.data ?? null;

  if (!employee) {
    if (employeeQuery.isLoading) {
      return <EmptyState title="กำลังโหลดข้อมูลพนักงาน" />;
    }
    return <EmptyState title="ไม่พบข้อมูลพนักงาน" description="กลับไปเลือกรายชื่อพนักงานอีกครั้ง" />;
  }

  const visibleTabs = tabDefs.filter((tab) => !tab.restricted || canSeeSensitive);

  async function submitEdit(payload) {
    // Pass the URL id so the query-key invalidated in useHrData
    // (`employeeDetail(id)`) matches this page's query key exactly.
    await onUpdateEmployee(id, payload);
    setEditing(false);
  }

  return (
    <PageStack>
      <Breadcrumbs items={[{ label: 'พนักงานทั้งหมด', onClick: onBack }, { label: employee.nameTh }]} />
      <Button type="button" variant="text" onClick={onBack}>
        <Icon name="chevronLeft" />
        กลับไปรายชื่อพนักงาน
      </Button>

      <DetailHero>
        <Avatar employee={employee} size="xl" />
        <div>
          <h1>{employee.nameTh}</h1>
          <p>{employee.nameEn} · ชื่อเล่น {employee.nickName}</p>
          <div className="flex flex-wrap gap-x-5 gap-y-[10px] mt-3 text-text-secondary text-sm [&_span]:inline-flex [&_span]:gap-[7px] [&_span]:items-center">
            <span><Icon name="badge" /> <code>{employee.code}</code></span>
            <span><Icon name="briefcase" /> {employee.positionTh}</span>
            <span><Icon name="building" /> {employee.divisionTh} · {employee.departmentTh}</span>
          </div>
        </div>
        <StatusBadge tone={employee.statusTone}>{employee.statusTh}</StatusBadge>
        {canManage ? (
          <Button type="button" onClick={() => setEditing(true)}>
            <Icon name="pencil" />
            แก้ไข
          </Button>
        ) : null}
      </DetailHero>

      <nav className="flex gap-[2px] border-b border-border-input overflow-x-auto" aria-label="Employee detail sections">
        {visibleTabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            className={cn(
              'inline-flex items-center gap-[7px] min-h-[44px] px-[14px] border-0 border-b-2 border-transparent bg-transparent text-text-muted font-bold whitespace-nowrap',
              activeTab === tab.id ? 'text-primary border-b-primary' : '',
            )}
            onClick={() => setActiveTab(tab.id)}
          >
            <Icon name={tab.icon} />
            {tab.label}
          </button>
        ))}
      </nav>

      {activeTab === 'personal' ? <PersonalTab employee={employee} /> : null}
      {activeTab === 'employment' ? <EmploymentTab employee={employee} canSeeSalary={canSeeSensitive} /> : null}
      {activeTab === 'history' ? <HistoryTab employee={employee} canSeeSalary={canSeeSensitive} /> : null}
      {activeTab === 'sensitive' && canSeeSensitive ? <SensitiveTab employee={employee} /> : null}

      {editing ? <EmployeeFormModal employee={employee} onClose={() => setEditing(false)} onSubmit={submitEdit} /> : null}
    </PageStack>
  );
}

function PersonalTab({ employee }) {
  return (
    <InfoGrid two>
      <CollapsibleSection title="ข้อมูลส่วนบุคคล">
        <FieldList>
          <div><dt>คำนำหน้า · เพศ</dt><dd>{employee.titleTh} · {employee.genderTh}</dd></div>
          <div><dt>วันเกิด</dt><dd>{formatThaiDate(employee.birthDate)} ({employee.age} ปี)</dd></div>
          <div><dt>สัญชาติ</dt><dd>{employee.nationality}</dd></div>
          <div><dt>สถานภาพ</dt><dd>{employee.maritalStatus}</dd></div>
        </FieldList>
      </CollapsibleSection>
      <CollapsibleSection title="ช่องทางติดต่อ">
        <FieldList>
          <div><dt>อีเมล</dt><dd>{employee.email}</dd></div>
          <div><dt>เบอร์โทรศัพท์</dt><dd>{employee.phone}</dd></div>
          <div><dt>บัตรพนักงาน</dt><dd><code>{employee.badge}</code></dd></div>
        </FieldList>
      </CollapsibleSection>
      <CollapsibleSection title="ที่อยู่ปัจจุบัน" defaultOpen={false}>
        <p className="leading-[1.7] text-text-secondary">{employee.currentAddress.line1}<br />{employee.currentAddress.district} {employee.currentAddress.province} {employee.currentAddress.postalCode}</p>
      </CollapsibleSection>
      <CollapsibleSection title="ผู้ติดต่อฉุกเฉิน" defaultOpen={false}>
        <FieldList>
          <div><dt>ชื่อ</dt><dd>{employee.emergencyContact.name}</dd></div>
          <div><dt>ความสัมพันธ์</dt><dd>{employee.emergencyContact.relationship}</dd></div>
          <div><dt>เบอร์โทร</dt><dd>{employee.emergencyContact.phone}</dd></div>
        </FieldList>
      </CollapsibleSection>
    </InfoGrid>
  );
}

function EmploymentTab({ employee, canSeeSalary }) {
  return (
    <InfoGrid two>
      <CollapsibleSection title="การมอบหมายงานปัจจุบัน">
        <FieldList>
          <div><dt>ฝ่าย</dt><dd>{employee.divisionTh}</dd></div>
          <div><dt>แผนก</dt><dd>{employee.departmentTh}</dd></div>
          <div><dt>ตำแหน่ง</dt><dd>{employee.positionTh}</dd></div>
          <div><dt>ระดับ</dt><dd><code>{employee.level}</code></dd></div>
          <div><dt>สถานที่ทำงาน</dt><dd>{employee.locationTh}</dd></div>
          <div><dt>รายงานต่อ</dt><dd>{employee.reportsTo}</dd></div>
          <div><dt>วันที่เริ่มงาน</dt><dd>{formatThaiDate(employee.hireDate)}</dd></div>
          <div><dt>วันที่บรรจุ</dt><dd>{formatThaiDate(employee.confirmationDate)}</dd></div>
        </FieldList>
      </CollapsibleSection>
      <div className="highlight-panel">
        <CollapsibleSection title="ค่าตอบแทน">
          {canSeeSalary ? (
            <>
              <div className="salary-value">{formatMoney(employee.salary)}</div>
              <span>ต่อเดือน · {employee.payType}</span>
            </>
          ) : (
            <p>แสดงเฉพาะบทบาท HR และ ADMIN</p>
          )}
        </CollapsibleSection>
      </div>
    </InfoGrid>
  );
}

function HistoryTab({ employee, canSeeSalary }) {
  return (
    <InfoGrid two>
      <CollapsibleSection title="ประวัติการมอบหมายงาน">
        <div className="timeline-list">
          {employee.assignments.map((assignment) => (
            <div key={`${assignment.from}-${assignment.title}`} className={assignment.current ? 'current' : ''}>
              <strong>{assignment.title}</strong>
              <span>{assignment.division} · {assignment.department}</span>
              <small>{formatThaiDate(assignment.from)} - {assignment.to ? formatThaiDate(assignment.to) : 'ปัจจุบัน'}</small>
            </div>
          ))}
        </div>
      </CollapsibleSection>
      <CollapsibleSection title="ประวัติเงินเดือน" defaultOpen={false}>
        {canSeeSalary ? (
          <div className="salary-history">
            {employee.salaryHistory.map((item) => (
              <div key={`${item.date}-${item.newSalary}`}>
                <span>
                  <strong>{formatMoney(item.newSalary)}</strong>
                  <small>{formatThaiDate(item.date)} · {item.note}</small>
                </span>
                <small>จาก {formatMoney(item.oldSalary)}</small>
              </div>
            ))}
          </div>
        ) : <p className="muted">แสดงเฉพาะบทบาท HR และ ADMIN</p>}
      </CollapsibleSection>
    </InfoGrid>
  );
}

function SensitiveTab({ employee }) {
  return (
    <div className="sensitive-panel">
      <CollapsibleSection
        title="ข้อมูลอ่อนไหว"
        defaultOpen={false}
        headerRight={<StatusBadge tone="teal">PDPA</StatusBadge>}
      >
        <FieldList columns={3}>
          <div><dt>เลขบัตรประชาชน</dt><dd><code>{employee.sensitive.nationalId}</code></dd></div>
          <div><dt>เลขประจำตัวผู้เสียภาษี</dt><dd><code>{employee.sensitive.taxId}</code></dd></div>
          <div><dt>เลขประกันสังคม</dt><dd><code>{employee.sensitive.socialSecurityNo}</code></dd></div>
          <div><dt>โรงพยาบาลประกันสังคม</dt><dd>{employee.sensitive.socialSecurityHospital}</dd></div>
          <div><dt>กองทุนสำรองเลี้ยงชีพ</dt><dd><code>{employee.sensitive.providentFundNo}</code></dd></div>
        </FieldList>
      </CollapsibleSection>
    </div>
  );
}
