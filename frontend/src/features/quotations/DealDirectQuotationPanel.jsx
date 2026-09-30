/* Hallmark · genre: modern-minimal · macrostructure: Workbench · design-system: design.md · designed-as-app · pre-emit critique: P4 H4 E4 S4 R4 V5 */
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { downloadBlob } from '../../utils/download.js';
import { formatMoney, formatThaiDate } from '../../utils/format.js';
import { dealQuotationStatusLabel } from './quotationMeta.js';

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
 *
 * GLA-136 (owner ruling 2026-09-30): this panel no longer CREATES a direct quotation. Direct
 * quotations are quotation-only documents written at /quotations (the list page's own "สร้าง"
 * entry, which mints a quotation-only container ticket), never from inside a pipeline deal — a
 * pipeline deal is priced through its คำขอราคา chain instead. The "สร้างใบเสนอราคา" link that used
 * to sit in this panel's header is gone; the list below is unchanged. TicketDetailPage still
 * passes `deal`/`user`; they are no longer read here.
 *
 * Slice 2 §D (SLICE-2-FLOW-A.md, owner-approved IA D2) restores a create affordance here — as the
 * two-route choice (ผ่านคำขอราคา / ใบเสนอราคาตรง), built and gated by TicketDetailPage and handed in
 * as `actions` (the Panel header's right side) plus one `note` line under the title. This panel
 * only places them; it decides nothing about who may see them.
 */
export function DealDirectQuotationPanel({ ticketId, showToast, actions = null, note = null }) {
  const [downloadingKey, setDownloadingKey] = useState(null);

  const listQuery = useQuery({
    queryKey: queryKeys.dealQuotationsByTicket(ticketId),
    queryFn: () => api.dealQuotations.listForTicket(ticketId).then((r) => r.items ?? []),
  });

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
    <Panel title="ใบเสนอราคา" actions={actions}>
      {note ? <p className="m-0 mb-4 text-sm text-text-muted">{note}</p> : null}
      {listQuery.isLoading ? (
        <p className="text-xs text-text-muted">กำลังโหลด...</p>
      ) : rows.length === 0 ? (
        <EmptyState
          icon="fileText"
          title="ยังไม่มีใบเสนอราคา"
          // Slice 2 §D: when this viewer has the route buttons, the way in is right above.
          description={actions ? 'เริ่มจากปุ่มด้านบน — ผ่านคำขอราคา หรือใบเสนอราคาตรง' : 'สร้างได้จากหน้า ใบเสนอราคา'}
        />
      ) : (
        <ul className="m-0 grid list-none gap-2.5 p-0">
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
