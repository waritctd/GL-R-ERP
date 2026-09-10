import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { downloadBlob } from '../../utils/download.js';
import { formatMoney, formatThaiDate } from '../../utils/format.js';
import { canCreateDealQuotation, dealQuotationStatusLabel } from './quotationMeta.js';

/**
 * "ใบเสนอราคา" (Quotation v2, QUOTATION-V2-PLAN.md) -- this deal's direct quotations, mounted on
 * TicketDetailPage's documents tab ABOVE the PCR-chain DealQuotationPanel (which stays untouched
 * and unrelated: that panel renders CustomerQuotation rows off the PricingRequest chain; this one
 * renders `origin = 'DEAL_DIRECT'` rows the bypass feature writes -- the two never share a row).
 */
export function DealDirectQuotationPanel({ ticketId, deal, user, showToast }) {
  const [downloadingKey, setDownloadingKey] = useState(null);

  const listQuery = useQuery({
    queryKey: queryKeys.dealQuotationsByTicket(ticketId),
    queryFn: () => api.dealQuotations.listForTicket(ticketId).then((r) => r.items ?? []),
  });

  const canCreate = canCreateDealQuotation(user, deal);
  const rows = listQuery.data ?? [];

  async function handleDownload(quotation, format) {
    const key = `${quotation.id}-${format}`;
    setDownloadingKey(key);
    try {
      const blob = format === 'pdf'
        ? await api.dealQuotations.downloadPdf(quotation.id)
        : await api.dealQuotations.downloadXlsx(quotation.id);
      downloadBlob(blob, quotation.number, format);
    } catch (error) {
      showToast?.('error', error.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloadingKey(null);
    }
  }

  return (
    <Panel
      title="ใบเสนอราคา"
      actions={canCreate ? (
        <Link to={`/quotations/new?ticket=${ticketId}`} className={cn(buttonVariants({ variant: 'secondary', size: 'sm' }))}>
          <Icon name="plus" size={14} />
          สร้างใบเสนอราคา
        </Link>
      ) : null}
    >
      {listQuery.isLoading ? (
        <p className="text-xs text-text-muted">กำลังโหลด...</p>
      ) : rows.length === 0 ? (
        <EmptyState
          icon="fileText"
          title="ยังไม่มีใบเสนอราคา"
          description={canCreate ? 'กด "สร้างใบเสนอราคา" เพื่อเริ่มต้น' : undefined}
        />
      ) : (
        <ul className="grid gap-2.5">
          {rows.map((q) => {
            const status = dealQuotationStatusLabel(q.docStatus);
            return (
              <li
                key={q.id}
                className="grid grid-cols-1 gap-2 rounded-md border border-border p-3 sm:grid-cols-[auto_1fr_auto_auto] sm:items-center"
              >
                <Link to={`/quotations/${q.id}`} className="text-xs text-info underline">
                  <code>{q.number}</code>
                </Link>
                <div className="flex flex-col">
                  <span className="tabular-nums font-bold text-text">{formatMoney(q.grandTotal)}</span>
                  <span className="text-2xs text-text-muted">
                    {q.approvedByName ? `อนุมัติโดย ${q.approvedByName} · ${formatThaiDate(q.approvedAt)}` : `สร้างเมื่อ ${formatThaiDate(q.createdAt)}`}
                  </span>
                </div>
                <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                <div className="flex gap-2">
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={downloadingKey === `${q.id}-pdf`}
                    onClick={() => handleDownload(q, 'pdf')}
                  >
                    PDF
                  </Button>
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={downloadingKey === `${q.id}-xlsx`}
                    onClick={() => handleDownload(q, 'xlsx')}
                  >
                    Excel
                  </Button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </Panel>
  );
}
