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
 * and unrelated: that panel renders CustomerQuotation rows off the PricingRequest chain).
 *
 * CORRECTION (S3 round-3 review, NEW-6, 2026-09-23): this used to say this panel renders ONLY
 * `origin = 'DEAL_DIRECT'` rows and the two panels "never share a row" -- that stopped being true
 * once round 1's MAJOR-4 fix widened DealQuotationRepository#findByTicket (which
 * api.dealQuotations.listForTicket above reads from) to also return `origin = 'PRICING_REQUEST'`
 * rows. DealQuotationService#listForTicket now filters those PRICING_REQUEST rows by
 * #canViewPricingRequestOriginRow (sales-owner, sales_manager, ceo only -- see that method's own
 * Javadoc), so for those roles THIS panel's `rows` can include a PRICING_REQUEST-origin quotation
 * alongside any DEAL_DIRECT ones on the same deal, not only the latter.
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
                  {/* GLA-74 part 1 ("สร้างจากใบเดิม" / สั่งเหมือนเดิม) -- this family already sorts
                      together by number (a clone shares its source's base), but carries no
                      parentQuotationId, so without this note it would be indistinguishable from an
                      ordinary first-issue document at a glance. */}
                  {q.derivedFromQuotationId ? (
                    <span className="text-2xs text-text-muted">
                      สั่งเหมือนเดิมจาก {q.derivedFromQuotationNumber ?? `#${q.derivedFromQuotationId}`}
                    </span>
                  ) : null}
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
