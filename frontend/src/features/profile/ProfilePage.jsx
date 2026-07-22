import { useMemo, useState } from 'react';
import { Avatar } from '../../components/common/Avatar.jsx';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { DetailHero } from '../../components/common/FieldList.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel, PageStack } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { hasPermission } from '../../app/permissions.js';
import { formatAddress, formatShortDate, requestStatus } from '../../utils/format.js';
import { ChangeRequestModal } from './ChangeRequestModal.jsx';

const MY_REQUESTS_TABLE_GRID = 'grid-cols-[minmax(0,1.2fr)_minmax(0,2fr)_minmax(0,2fr)_minmax(0,0.9fr)_minmax(0,1fr)] max-[1040px]:min-w-[900px] reflow-cards';

export function ProfilePage({ user, employee, profileRequests, onCreateRequest }) {
  const [requestField, setRequestField] = useState(null);
  // Employee-tier users (employee + the new warehouse/qc roles) request profile changes rather
  // than edit directly — tie the affordance to the permission, not a single hard-coded role.
  const isEmployee = hasPermission(user.role, 'canSubmitProfileRequests');
  const pendingKeys = useMemo(
    () => new Set(profileRequests.filter((request) => request.status === 'pending').map((request) => request.fieldKey)),
    [profileRequests],
  );
  const pendingCount = profileRequests.filter((request) => request.status === 'pending').length;

  // `displayValue` exists only for address: the row shows the complete address,
  // but the change request still carries `line1` as its old value. Approving an
  // address request calls EmployeeRepository.updateAddressLine, which writes only
  // `house_no` — district/province/postal_code are untouched columns. Submitting
  // a whole address as the new value would therefore store it in `house_no` and
  // leave the old tail behind, rendering a duplicated address. Editing stays
  // scoped to the street line until the write path can take all four fields.
  const requestableFields = [
    { fieldKey: 'phone', fieldLabel: 'เบอร์โทรศัพท์', oldValue: employee?.phone, icon: 'phone' },
    { fieldKey: 'email', fieldLabel: 'อีเมล', oldValue: employee?.email, icon: 'mail' },
    {
      fieldKey: 'address',
      fieldLabel: 'ที่อยู่ปัจจุบัน',
      oldValue: employee?.currentAddress?.line1,
      displayValue: formatAddress(employee?.currentAddress),
      icon: 'home',
    },
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

      <Panel title="ข้อมูลติดต่อ">
        <div className="editable-list">
          {requestableFields.map((field) => (
            <div key={field.fieldKey}>
              <Icon name={field.icon} />
              <span>
                <strong>{field.fieldLabel}</strong>
                <small>{field.displayValue ?? field.oldValue}</small>
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

      {/* The full request table, absorbed from the former /my-requests page —
          same `ownRequests` data, so a separate page only split one story in
          two. `.table-panel` is a sibling of the panel above, never nested. */}
      <section className="table-panel">
        <div className="flex items-center justify-between gap-[14px] py-4 px-5 border-b border-border">
          <h2 className="m-0 text-lg">คำขอแก้ไขของฉัน</h2>
          {pendingCount > 0 ? (
            <span className="!text-xs !text-text-muted">{pendingCount} คำขอรออนุมัติ</span>
          ) : null}
        </div>
        <div className={`${MY_REQUESTS_TABLE_GRID} table-head`}>
          <span>ข้อมูลที่ขอแก้</span>
          <span>ค่าเดิม</span>
          <span>ค่าใหม่</span>
          <span>วันที่</span>
          <span>สถานะ</span>
        </div>
        {profileRequests.length === 0 ? (
          <EmptyState
            icon="clipboard"
            title="ยังไม่มีคำขอแก้ไข"
            description={isEmployee ? 'กด "ขอแก้ไข" ที่ข้อมูลติดต่อด้านบนเพื่อส่งคำขอ' : undefined}
          />
        ) : profileRequests.map((request) => {
          const status = requestStatus(request.status);
          return (
            <div className={`${MY_REQUESTS_TABLE_GRID} data-row`} key={request.id}>
              <StatusBadge tone="indigo">{request.fieldLabel}</StatusBadge>
              <span className="old-value">{request.oldValue}</span>
              <strong>{request.newValue}</strong>
              <span>{formatShortDate(request.requestedAt)}</span>
              <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
            </div>
          );
        })}
      </section>

      {requestField ? <ChangeRequestModal requestField={requestField} onClose={() => setRequestField(null)} onSubmit={submitRequest} /> : null}
    </PageStack>
  );
}
