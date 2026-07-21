import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { factoryPurchaseOrderStatusLabel, formatMoney, formatThaiDate } from '../../utils/format.js';

// Step 7: Factory Purchase Order and Import Execution — minimal Import/CEO-facing list. Every
// row is Import/CEO-territory raw supplier PO data (ProcurementService.RAW_PO_ROLES); this page
// is only reachable by those roles (see AppShell.jsx nav + app/permissions.js PATH_GUARDS).
const STATUS_FILTERS = [
  { value: '', label: 'ทั้งหมด' },
  { value: 'OPEN', label: 'เปิดใบสั่งซื้อ' },
  { value: 'SHIPPING', label: 'สินค้าเดินทาง' },
  { value: 'RECEIVED', label: 'รับสินค้าแล้ว' },
  { value: 'CANCELLED', label: 'ยกเลิกแล้ว' },
];

function PoCard({ po }) {
  const status = factoryPurchaseOrderStatusLabel(po.status);
  return (
    <>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <code className="min-w-0 truncate text-xs text-text-muted">{po.poNumber}</code>
        <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
      </div>
      <strong className="min-w-0 text-md leading-snug font-extrabold text-text">{po.factoryName}</strong>
      <span className="min-w-0 truncate text-xs text-text-muted">
        {[po.pricingRequestCode, po.ticketCode].filter(Boolean).join(' · ')}
      </span>
      <span className="text-sm font-bold text-text">{formatMoney(po.totalAmount)} {po.currency}</span>
    </>
  );
}

export function ProcurementListPage({ showToast }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const statusFilter = searchParams.get('status') ?? '';
  const [searchText, setSearchText] = useState('');

  const poQuery = useQuery({
    queryKey: queryKeys.factoryPurchaseOrderList(statusFilter),
    queryFn: () => api.procurement.list(statusFilter || undefined)
      .then((response) => response.factoryPurchaseOrders || []),
  });
  const orders = poQuery.data ?? [];
  if (poQuery.error) showToast?.('error', poQuery.error.message || 'โหลดข้อมูลไม่สำเร็จ');

  function updateStatus(value) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (value) next.set('status', value); else next.delete('status');
      return next;
    }, { replace: true });
  }

  function invalidate() {
    return queryClient.invalidateQueries({ queryKey: ['factoryPurchaseOrders'] });
  }

  const columns = useMemo(() => [
    {
      key: 'poNumber',
      header: 'เลขที่ใบสั่งซื้อ',
      searchAccessor: (po) => [po.poNumber, po.factoryName, po.pricingRequestCode, po.ticketCode].filter(Boolean).join(' '),
      render: (po) => (
        <span className="flex min-w-0 flex-col gap-0.5">
          <strong className="block truncate text-text">{po.factoryName}</strong>
          <code className="block truncate text-2xs text-text-muted">{po.poNumber}</code>
        </span>
      ),
    },
    {
      key: 'deal',
      header: 'ดีล / ใบขอราคา',
      render: (po) => (
        <span className="flex min-w-0 flex-col gap-0.5">
          <span className="block truncate">{po.pricingRequestCode}</span>
          <span className="block truncate text-2xs text-text-muted">{po.ticketCode}</span>
        </span>
      ),
    },
    {
      key: 'status',
      header: 'สถานะ',
      sortable: true,
      sortAccessor: (po) => po.status,
      render: (po) => {
        const status = factoryPurchaseOrderStatusLabel(po.status);
        return <StatusBadge tone={status.tone}>{status.label}</StatusBadge>;
      },
    },
    {
      key: 'total',
      header: 'มูลค่ารวม',
      align: 'right',
      sortable: true,
      sortAccessor: (po) => Number(po.totalAmount ?? 0),
      render: (po) => <span>{formatMoney(po.totalAmount)} {po.currency}</span>,
    },
    {
      key: 'updatedAt',
      header: 'อัปเดตล่าสุด',
      sortable: true,
      sortAccessor: (po) => new Date(po.updatedAt),
      render: (po) => <span className="text-xs text-text-muted">{formatThaiDate(po.updatedAt)}</span>,
    },
  ], []);

  return (
    <div className="page-stack">
      <PageHeader
        title="ใบสั่งซื้อโรงงาน"
        subtitle="Factory Purchase Orders — ต้นทาง สินค้า/ราคา จาก pricing_costing_item ที่ได้รับอนุมัติแล้วเท่านั้น"
        actions={(
          <button type="button" className="icon-button" onClick={invalidate} title="รีเฟรช" aria-label="รีเฟรช">
            <Icon name="refresh" />
          </button>
        )}
      />

      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-surface p-3">
        <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">สถานะ</span>
        {STATUS_FILTERS.map((item) => {
          const active = statusFilter === item.value;
          return (
            <button
              key={item.value || 'all'}
              type="button"
              aria-pressed={active}
              className={`inline-flex min-h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-bold ${
                active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface hover:bg-surface-hover'
              }`}
              onClick={() => updateStatus(active ? '' : item.value)}
            >
              {item.label}
            </button>
          );
        })}
      </div>

      <DataTable
        columns={columns}
        rows={orders}
        getRowKey={(po) => po.id}
        gridClassName="procurement-table"
        onRowClick={(po) => navigate(`/factory-purchase-orders/${po.id}`)}
        mobileCard={(po) => <PoCard po={po} />}
        searchable
        searchValue={searchText}
        onSearchChange={setSearchText}
        searchPlaceholder="ค้นหาเลขที่ / โรงงาน / ใบขอราคา / ดีล"
        initialSort={{ key: 'updatedAt', dir: 'desc' }}
        loading={poQuery.isLoading || poQuery.isFetching}
        emptyState={{
          icon: 'fileText',
          title: 'ไม่มีใบสั่งซื้อโรงงาน',
          description: 'ยังไม่มีใบสั่งซื้อโรงงานในเงื่อนไขที่เลือก',
        }}
      />
    </div>
  );
}
