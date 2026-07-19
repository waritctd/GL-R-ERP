import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { SalesTabs } from '../sales/SalesTabs.jsx';
import { formatThaiDate, pricingRequestStatusLabel } from '../../utils/format.js';
import { canPickupPricingRequest, pricingRequestRecipientLabel } from './pricingRequestMeta.js';

// Queue-relevant statuses only — DRAFT is the sales rep's private scratchpad
// (never submitted, nothing for Import to act on) and CANCELLED requests have
// no further action, so neither is a useful default filter here. "ทั้งหมด"
// still lets import/ceo/sales_manager see the full picture when needed.
const STATUS_FILTERS = ['', 'SUBMITTED', 'IMPORT_REVIEWING', 'MORE_INFO_REQUIRED', 'DRAFT', 'CANCELLED'];

const COLUMNS = [
  {
    key: 'requestCode',
    header: 'เลขที่ใบขอราคา',
    sortable: true,
    searchAccessor: (row) => row.requestCode,
    // A Link (not a whole-row onRowClick) so this table can carry a pickup
    // <button> per row without nesting one interactive element inside another.
    render: (row) => <Link to={`/pricing-requests/${row.id}`} className="text-xs text-info underline"><code>{row.requestCode}</code></Link>,
  },
  {
    key: 'deal',
    header: 'ดีล / ลูกค้า',
    searchAccessor: (row) => `${row.ticketCode ?? ''} ${row.customerName ?? ''} ${row.projectName ?? ''}`,
    render: (row) => (
      <div className="flex flex-col">
        <span className="font-bold">{row.customerName ?? '-'}</span>
        <span className="text-2xs text-text-muted">{row.ticketCode}{row.projectName ? ` · ${row.projectName}` : ''}</span>
      </div>
    ),
  },
  {
    key: 'recipient',
    header: 'ผู้รับ',
    render: (row) => (
      <span>
        {pricingRequestRecipientLabel(row.recipientType)}
        {row.recipientLabel ? ` · ${row.recipientLabel}` : ''}
      </span>
    ),
  },
  {
    key: 'status',
    header: 'สถานะ',
    sortable: true,
    searchAccessor: (row) => row.status,
    render: (row) => {
      const status = pricingRequestStatusLabel(row.status);
      return <StatusBadge tone={status.tone}>{status.label}</StatusBadge>;
    },
  },
  {
    key: 'itemCount',
    header: 'จำนวนรายการ',
    align: 'right',
    sortable: true,
    render: (row) => row.itemCount,
  },
  {
    key: 'requiredDate',
    header: 'ต้องการภายใน',
    sortable: true,
    render: (row) => formatThaiDate(row.requiredDate),
  },
  {
    key: 'assignedImportName',
    header: 'ผู้รับเรื่อง',
    render: (row) => row.assignedImportName ?? '-',
  },
];

function QueueCard({ row, onPickup, canPickup, pickingUp }) {
  const status = pricingRequestStatusLabel(row.status);
  return (
    <>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <Link to={`/pricing-requests/${row.id}`} className="min-w-0 truncate text-xs text-info underline" onClick={(event) => event.stopPropagation()}>
          <code>{row.requestCode}</code>
        </Link>
        <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
      </div>
      <strong className="min-w-0 truncate text-md leading-snug font-extrabold text-text">
        {row.customerName ?? '-'}
      </strong>
      <span className="min-w-0 truncate text-xs text-text-muted">
        {row.ticketCode}{row.projectName ? ` · ${row.projectName}` : ''}
      </span>
      <span className="min-w-0 truncate text-xs text-text-muted">
        {pricingRequestRecipientLabel(row.recipientType)}{row.recipientLabel ? ` · ${row.recipientLabel}` : ''} · {row.itemCount} รายการ
      </span>
      {row.requiredDate ? (
        <span className="text-xs text-text-muted">ต้องการภายใน {formatThaiDate(row.requiredDate)}</span>
      ) : null}
      {canPickup ? (
        <button
          type="button"
          className="primary-button mt-1"
          disabled={pickingUp}
          onClick={() => onPickup(row.id)}
        >
          รับเรื่อง
        </button>
      ) : null}
    </>
  );
}

/**
 * Import's cross-deal PricingRequest queue (commit 6). Route-guarded by
 * ROLE_PERMISSIONS.canViewPricingRequestQueue (import/ceo/sales_manager) —
 * see app/permissions.js PATH_GUARDS for '/pricing-requests'.
 */
export function PricingRequestQueuePage({ user, showToast }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState('SUBMITTED');

  const queueQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({ status, activeOnly: true }),
    queryFn: () => api.pricingRequests.queue({ status: status || undefined, activeOnly: true }).then((r) => r.items ?? []),
  });
  const rows = queueQuery.data ?? [];

  const pickupMutation = useMutation({
    mutationFn: (id) => api.pricingRequests.pickup(id),
    onSuccess: () => {
      showToast('success', 'รับเรื่องแล้ว');
      queryClient.invalidateQueries({ queryKey: ['pricingRequests'] });
    },
    onError: (error) => showToast('error', error.message || 'รับเรื่องไม่สำเร็จ'),
  });

  const columns = [
    ...COLUMNS,
    {
      key: 'pickup',
      header: '',
      render: (row) => (
        canPickupPricingRequest(user, row) ? (
          <button
            type="button"
            className="secondary-button"
            disabled={pickupMutation.isPending}
            onClick={() => pickupMutation.mutate(row.id)}
          >
            รับเรื่อง
          </button>
        ) : null
      ),
    },
  ];

  return (
    <div className="page-stack">
      <SalesTabs role={user.role} />
      <PageHeader
        title="คิวขอราคา"
        subtitle="ใบขอราคาที่รอฝ่ายนำเข้าดำเนินการ"
        actions={(
          <button
            type="button"
            className="icon-button"
            onClick={() => queryClient.invalidateQueries({ queryKey: ['pricingRequests', 'queue'] })}
            title="รีเฟรช"
            aria-label="รีเฟรช"
          >
            <Icon name="refresh" />
          </button>
        )}
      />

      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-surface p-3">
        <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">สถานะ</span>
        {STATUS_FILTERS.map((value) => {
          const active = status === value;
          const label = value ? pricingRequestStatusLabel(value).label : 'ทั้งหมด';
          return (
            <button
              key={value || 'all'}
              type="button"
              aria-pressed={active}
              className={`inline-flex min-h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-bold ${
                active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface hover:bg-surface-hover'
              }`}
              onClick={() => setStatus(value)}
            >
              {label}
            </button>
          );
        })}
      </div>

      <DataTable
        columns={columns}
        rows={rows}
        getRowKey={(row) => row.id}
        gridClassName="pricing-request-queue-table"
        mobileCard={(row) => (
          <QueueCard
            row={row}
            canPickup={canPickupPricingRequest(user, row)}
            pickingUp={pickupMutation.isPending}
            onPickup={(id) => pickupMutation.mutate(id)}
          />
        )}
        searchable
        loading={queueQuery.isLoading}
        emptyState={{ icon: 'fileText', title: 'ไม่มีใบขอราคาในเงื่อนไขนี้' }}
      />
    </div>
  );
}
