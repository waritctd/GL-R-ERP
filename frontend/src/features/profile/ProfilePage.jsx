import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { hasPermission } from '../../app/permissions.js';
import { Avatar } from '../../components/common/Avatar.jsx';
import { Button } from '../../components/common/Button.jsx';
import { DetailHero, InfoGrid } from '../../components/common/FieldList.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel, PageStack } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatShortDate, requestStatus } from '../../utils/format.js';
import { ChangeRequestModal } from './ChangeRequestModal.jsx';

export function ProfilePage({ user, employee, profileRequests, onCreateRequest }) {
  const navigate = useNavigate();
  const [requestField, setRequestField] = useState(null);
  const isEmployee = hasPermission(user.role, 'canUseEmployeeExperience');
  const pendingKeys = useMemo(
    () => new Set(profileRequests.filter((request) => request.status === 'pending').map((request) => request.fieldKey)),
    [profileRequests],
  );

  const requestableFields = [
    { fieldKey: 'phone', fieldLabel: 'เบอร์โทรศัพท์', oldValue: employee?.phone, icon: 'phone' },
    { fieldKey: 'email', fieldLabel: 'อีเมล', oldValue: employee?.email, icon: 'mail' },
    { fieldKey: 'address', fieldLabel: 'ที่อยู่ปัจจุบัน', oldValue: employee?.currentAddress?.line1, icon: 'home' },
    { fieldKey: 'emergency', fieldLabel: 'ผู้ติดต่อฉุกเฉิน', oldValue: `${employee?.emergencyContact?.name} · ${employee?.emergencyContact?.phone}`, icon: 'badgeCheck' },
  ];

  async function submitRequest(payload) {
    await onCreateRequest(payload);
    setRequestField(null);
  }

  return (
    <PageStack>
      <PageHeader title="ข้อมูลของฉัน" subtitle={`${employee?.positionTh || ''} · ${employee?.divisionTh || ''}`} />

      <DetailHero compact>
        <Avatar employee={employee} size="lg" />
        <div>
          <h1>{employee?.nameTh}</h1>
          <p>{employee?.nameEn} · <code>{employee?.code}</code></p>
        </div>
        <StatusBadge tone={employee?.statusTone}>{employee?.statusTh}</StatusBadge>
      </DetailHero>

      <InfoGrid two>
        <Panel title="ข้อมูลติดต่อ">
          <div className="editable-list">
            {requestableFields.map((field) => (
              <div key={field.fieldKey}>
                <Icon name={field.icon} />
                <span>
                  <strong>{field.fieldLabel}</strong>
                  <small>{field.oldValue}</small>
                </span>
                {isEmployee ? (
                  <Button
                    type="button"
                    variant="secondary"
                    onClick={() => setRequestField(field)}
                    disabled={pendingKeys.has(field.fieldKey)}
                  >
                    {pendingKeys.has(field.fieldKey) ? 'รออนุมัติ' : 'ขอแก้ไข'}
                  </Button>
                ) : null}
              </div>
            ))}
          </div>
        </Panel>

        <Panel
          title="คำขอแก้ไขของฉัน"
          actions={isEmployee ? <Button type="button" variant="text" onClick={() => navigate('/my-requests')}>ดูทั้งหมด</Button> : null}
        >
          <div className="request-feed">
            {profileRequests.slice(0, 5).map((request) => {
              const status = requestStatus(request.status);
              return (
                <div className="request-feed-item compact" key={request.id}>
                  <span>
                    <strong>ขอแก้ไข{request.fieldLabel}</strong>
                    <small>{formatShortDate(request.requestedAt)}</small>
                  </span>
                  <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                </div>
              );
            })}
          </div>
        </Panel>
      </InfoGrid>

      {requestField ? <ChangeRequestModal requestField={requestField} onClose={() => setRequestField(null)} onSubmit={submitRequest} /> : null}
    </PageStack>
  );
}
