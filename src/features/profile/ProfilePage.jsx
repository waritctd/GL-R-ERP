import { useMemo, useState } from 'react';
import { Avatar } from '../../components/common/Avatar.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatShortDate, requestStatus } from '../../utils/format.js';
import { ChangeRequestModal } from './ChangeRequestModal.jsx';

export function ProfilePage({ user, employee, profileRequests, onCreateRequest, onRoute }) {
  const [requestField, setRequestField] = useState(null);
  const isEmployee = user.role === 'employee';
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
    <div className="page-stack">
      <PageHeader title="ข้อมูลของฉัน" subtitle={`${employee?.positionTh || ''} · ${employee?.divisionTh || ''}`} />

      <section className="detail-hero compact">
        <Avatar employee={employee} size="lg" />
        <div>
          <h1>{employee?.nameTh}</h1>
          <p>{employee?.nameEn} · <code>{employee?.code}</code></p>
        </div>
        <StatusBadge tone={employee?.statusTone}>{employee?.statusTh}</StatusBadge>
      </section>

      <div className="info-grid two">
        <section className="panel">
          <h2>ข้อมูลติดต่อ</h2>
          <div className="editable-list">
            {requestableFields.map((field) => (
              <div key={field.fieldKey}>
                <Icon name={field.icon} />
                <span>
                  <strong>{field.fieldLabel}</strong>
                  <small>{field.oldValue}</small>
                </span>
                {isEmployee ? (
                  <button
                    type="button"
                    className="secondary-button"
                    onClick={() => setRequestField(field)}
                    disabled={pendingKeys.has(field.fieldKey)}
                  >
                    {pendingKeys.has(field.fieldKey) ? 'รออนุมัติ' : 'ขอแก้ไข'}
                  </button>
                ) : null}
              </div>
            ))}
          </div>
        </section>

        <section className="panel">
          <div className="panel-header">
            <h2>คำขอแก้ไขของฉัน</h2>
            {isEmployee ? <button type="button" className="text-button" onClick={() => onRoute('myrequests')}>ดูทั้งหมด</button> : null}
          </div>
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
        </section>
      </div>

      {requestField ? <ChangeRequestModal requestField={requestField} onClose={() => setRequestField(null)} onSubmit={submitRequest} /> : null}
    </div>
  );
}
