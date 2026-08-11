import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { SalesTabs } from '../sales/SalesTabs.jsx';
import { formatThaiDate, pricingRequestStatusLabel } from '../../utils/format.js';
import { canPickupPricingRequest, pricingRequestRecipientLabel } from './pricingRequestMeta.js';

// Import's three meaningful stages (owner ruling, 2026-08-11) — รับเรื่อง,
// เจรจาราคากับโรงงาน, รอ CEO อนุมัติราคา — bracketed by the two ends of the
// queue: SUBMITTED (nothing has picked it up yet) and CANCELLED (dead, but
// still needs to be findable). Deliberately absent: DRAFT, the sales rep's
// private scratchpad, which is never Import's work; and MORE_INFO_REQUIRED,
// whose ขอข้อมูลเพิ่มเติม feature was removed from the product entirely.
// "ทั้งหมด" still lets import/ceo/sales_manager see the full picture.
//
// `statuses` is a LIST, not a string, because เจรจาราคากับโรงงาน still has to
// match TWO stored values. V139 merged COSTING_IN_PROGRESS into
// AWAITING_FACTORY_RESPONSE and dropped it from the DB constraint, but rows sit
// in the old value for the whole window between this code deploying and the
// migration running — so the chip keeps matching both ON PURPOSE. Do not
// "simplify" it to a single status: doing so makes those rows unreachable from
// the chip that is supposed to find them, and the failure is invisible in tests
// because every fixture is already on the new value.
//
// GET /api/pricing-requests takes a SINGLE `status` value
// (PricingRequestController#list -> PricingRequestService.list, which 400s on
// anything that is not one valid status — and COSTING_IN_PROGRESS is no longer
// one), so a two-status chip cannot be expressed server-side at all. It
// therefore fetches everything (status=undefined) and narrows the rows below.
// Once V139 has run everywhere, this entry can collapse to a single-value chip
// and the client-side filter can go with it.
//
// Labels are read from pricingRequestStatusLabel (utils/format.js), the
// canonical source, so a chip can never drift from the badge on the row it
// filters to. Only the "all" chip carries its own label.
const STATUS_FILTERS = [
  { key: 'ALL', label: 'ทั้งหมด', statuses: [] },
  { key: 'SUBMITTED', statuses: ['SUBMITTED'] },
  { key: 'IMPORT_REVIEWING', statuses: ['IMPORT_REVIEWING'] },
  { key: 'FACTORY_NEGOTIATION', statuses: ['AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS'] },
  { key: 'READY_FOR_CEO_REVIEW', statuses: ['READY_FOR_CEO_REVIEW'] },
  { key: 'CANCELLED', statuses: ['CANCELLED'] },
];

function statusFilterLabel(filter) {
  return filter.label ?? pricingRequestStatusLabel(filter.statuses[0]).label;
}

const COLUMNS = [
  {
    key: 'requestCode',
    header: 'เลขที่คำขอราคา',
    sortable: true,
    searchAccessor: (row) => row.requestCode,
    // A Link (not the whole-row `onRowClick` DataTable optionally offers) so
    // this table can carry a pickup <button> per row without nesting one
    // interactive element inside another — `onRowClick`'s own
    // interactive-target guard would let the button work fine, but this page
    // still opts out of it: with a per-row link *and* a per-row pickup
    // button already doing double duty, adding a third, whole-row target
    // would just be a redundant way to reach the same place.
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
        <Button
          type="button"
          variant="primary"
          className="mt-1"
          disabled={pickingUp}
          onClick={() => onPickup(row.id)}
        >
          รับเรื่อง
        </Button>
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
  const [filterKey, setFilterKey] = useState('SUBMITTED');
  const filter = STATUS_FILTERS.find((f) => f.key === filterKey) ?? STATUS_FILTERS[0];

  // One status -> the API filters it server-side. Zero (ทั้งหมด) or two
  // (เจรจาราคากับโรงงาน) -> fetch everything and narrow below; see STATUS_FILTERS
  // for why the two-status chip cannot be pushed down to the query.
  const queryStatus = filter.statuses.length === 1 ? filter.statuses[0] : undefined;

  const queueQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({ status: queryStatus, activeOnly: true }),
    queryFn: () => api.pricingRequests.queue({ status: queryStatus, activeOnly: true }).then((r) => r.items ?? []),
  });
  const rows = useMemo(() => {
    const items = queueQuery.data ?? [];
    if (filter.statuses.length < 2) return items;
    return items.filter((row) => filter.statuses.includes(row.status));
  }, [queueQuery.data, filter]);

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
          <Button
            type="button"
            variant="secondary"
            disabled={pickupMutation.isPending}
            onClick={() => pickupMutation.mutate(row.id)}
            data-testid="pcr-queue-pickup"
          >
            รับเรื่อง
          </Button>
        ) : null
      ),
    },
  ];

  return (
    <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
      <SalesTabs role={user.role} />
      <PageHeader
        title="คิวขอราคา"
        subtitle="คำขอราคาที่รอฝ่ายนำเข้าดำเนินการ"
        actions={(
          <Button
            type="button"
            variant="icon"
            onClick={() => queryClient.invalidateQueries({ queryKey: ['pricingRequests', 'queue'] })}
            title="รีเฟรช"
            aria-label="รีเฟรช"
          >
            <Icon name="refresh" />
          </Button>
        )}
      />

      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-surface p-3">
        <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">สถานะ</span>
        {STATUS_FILTERS.map((option) => {
          const active = filterKey === option.key;
          const label = statusFilterLabel(option);
          return (
            <button
              key={option.key}
              type="button"
              aria-pressed={active}
              className={`inline-flex min-h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-bold ${
                active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface hover:bg-surface-hover'
              }`}
              onClick={() => setFilterKey(option.key)}
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
        emptyState={{ icon: 'fileText', title: 'ไม่มีคำขอราคาในเงื่อนไขนี้' }}
      />
    </div>
  );
}
